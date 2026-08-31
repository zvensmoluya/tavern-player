# SillyTavern 单轮生成内核研究

> 状态：基于源码的执行路径研究，不是 Tavern Player 的实现规范。
>
> 调查版本：SillyTavern `1.18.0`，`release` 分支，提交 `8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8`。
>
> 研究重点：一条普通聊天消息怎样从用户输入变成模型请求，以及模型响应怎样变成会话消息。导入、资产管理和完整插件兼容不属于本轮重点。
>
> 本文只保留源码事实与尚未验证的事实问题。Tavern Player 的当前产品边界见 [`current-discussion-status.md`](current-discussion-status.md)。

## 1. 结论先行

SillyTavern 的“内核”不是角色卡解析器，也不只是 Prompt Manager。更准确地说，它是一条**带状态、可被扩展修改、按模型协议分叉的单轮生成管线**：

```text
用户动作
  -> 写入或调整聊天状态
  -> 投影聊天历史
  -> 扫描并激活世界书
  -> 收集角色、Preset、扩展和会话内容
  -> 按位置、深度、角色和顺序编排
  -> 在 token 预算内选择内容
  -> 转换为目标 Provider 请求
  -> 发送并累计响应
  -> 清理与 Regex 转换
  -> 写入聊天记录
  -> 再做展示投影
```

这条管线有五个重要特征。

第一，**导入不是内核**。导入只负责让角色卡、Preset 和世界书成为 ST 当前设置与运行状态的一部分。真正决定模型看到什么的是生成阶段。

第二，**Preset 的 `prompt_order` 很重要，但不拥有全部顺序**。它控制 Chat Completion Prompt Manager 中相对 Prompt 和 marker 的主顺序；世界书深度注入、扩展 Prompt、控制 Prompt、new-chat nudge、continue prefill、工具调用和 Provider 修正还会在其他代码路径插入或改写消息。

第三，ST 实际维护两套主要编排路径：

- Text Completion 路径把 story string、示例和格式化聊天历史逐步压平成一个字符串；
- Chat Completion 路径使用 Prompt Manager、`ChatCompletion`、`MessageCollection` 和结构化 role messages。

它们共享前半段的聊天投影、世界书扫描和扩展 Prompt 状态，但在注入和 token 预算阶段并不完全同构。

第四，**Regex 不是单一的“回复美化”步骤**。ST 通过 `markdownOnly`、`promptOnly` 和两者都关闭三种使用情境，把同一 placement 下的规则分别用于持久化文本、模型输入和界面显示；此外还有 USER_INPUT、AI_OUTPUT、WORLD_INFO、REASONING 等不同 placement。

第五，**ST 的生成过程不是纯函数**。宏可以读写变量，世界书包含 sticky/cooldown/delay 状态，扩展可以在多个事件和 interceptor 中修改数据，工具调用还会递归发起下一轮 `Generate()`。

## 2. 研究对象与边界

### 2.1 本轮主路径

本轮以以下场景为主：

- 单角色聊天；
- 普通 `normal` 生成；
- Chat Completion API 为主；
- 同时对照 Text Completion 路径；
- 观察角色内容、Preset、聊天历史、世界书、宏、Regex 和 token 预算；
- 跟踪非流式与流式回复进入会话的方式。

以下能力只记录它们如何改变主路径，不展开完整实现：

- 群聊成员选择；
- swipe、regenerate、continue、impersonate、quiet；
- 工具调用递归；
- 图片、音频和视频；
- CFG、向量记忆和第三方扩展；
- 所有 Provider 的字段全集。

### 2.2 主要源码入口

| 职责 | 源码位置 |
| --- | --- |
| 单轮生成总入口 | `SillyTavern/public/script.js:4231`，`Generate()` |
| 扩展 Prompt 注册与读取 | `SillyTavern/public/script.js:3172-3269`、`:8866` |
| Text Completion 深度注入 | `SillyTavern/public/script.js:5569`，`doChatInject()` |
| Chat Completion 编排 | `SillyTavern/public/scripts/openai.js:1176`，`populateChatCompletion()` |
| Prompt 定义和顺序 | `SillyTavern/public/scripts/PromptManager.js` |
| 世界书扫描 | `SillyTavern/public/scripts/world-info.js:4597`，`checkWorldInfo()` |
| 宏入口 | `SillyTavern/public/script.js:2922`，`substituteParams()` |
| Regex 引擎 | `SillyTavern/public/scripts/extensions/regex/engine.js` |
| 回复清理和保存 | `SillyTavern/public/script.js:6383`、`:6583` |
| Markdown 展示投影 | `SillyTavern/public/script.js:1753`，`messageFormatting()` |
| Chat Completion 请求构造 | `SillyTavern/public/scripts/openai.js:2645`、`:3044` |
| 服务端 Provider 转换 | `SillyTavern/src/endpoints/backends/chat-completions.js:2157` |
| role/message 后处理 | `SillyTavern/src/prompt-converters.js` |

文中的行号只对应上述提交，用于复查本次结论，不应被当成跨版本稳定 API。

## 3. ST 中不存在一个孤立的“内核模块”

`Generate()` 同时承担以下职责：

- 处理生成类型和 UI 状态；
- 执行 slash command；
- 把用户输入写进 chat；
- 读取角色卡字段；
- 对聊天历史执行 Prompt 侧 Regex；
- 运行 extension interceptor；
- 发起世界书扫描；
- 组织 Text Completion 或 Chat Completion Prompt；
- 构造请求参数；
- 选择流式或非流式请求；
- 清理、保存和展示响应；
- 在工具调用后递归生成。

因此，“ST 内核”是一个行为边界，而不是源码中现成的可移植包。它大致跨越：

```text
script.js
  + PromptManager.js
  + openai.js
  + world-info.js
  + macros/*
  + extensions/regex/*
  + prompt-converters.js
  + provider endpoint adapters
```

这也解释了为什么只研究 Preset JSON 会产生偏差：Preset 是这条管线的一种输入和配置快照，不是管线本身。

普通单角色 Chat Completion 路径的函数级调用链是：

```text
Generate()
  -> sendMessageAsUser()
  -> getCharacterCardFields()
  -> getRegexedString(chat message, isPrompt=true)
  -> runGenerationInterceptors()
  -> getWorldInfoPrompt()
     -> checkWorldInfo()
  -> setOpenAIMessages()
  -> prepareOpenAIMessages()
     -> PromptManager.getPromptCollection()
     -> preparePromptsForChatCompletion()
     -> populateChatCompletion()
     -> ChatCompletion.getChat()
  -> createGenerationParameters()
  -> sendOpenAIRequest()
  -> Provider endpoint / prompt converter
  -> extract response or StreamingProcessor
  -> cleanUpMessage()
  -> saveReply()
  -> messageFormatting()
```

这条链不是说所有分支都严格线性执行；它是普通路径上可设置观测点的骨架。Text Completion 会在世界书之后转入 story string、`doChatInject()` 和字符串合并路径。

## 4. 一轮生成的真实入口

### 4.1 `Generate()` 的开始并不是 Prompt 编排

`Generate(type, options, dryRun)` 首先处理外围状态：

1. 确保角色不处于 shallow 状态；
2. 发出 `GENERATION_STARTED`；
3. 创建或沿用 abort controller；
4. 对普通输入执行 slash command；
5. 发出 `GENERATION_AFTER_COMMANDS`；
6. 检查连接、群聊和生成类型；
7. 读取输入框文本。

生成类型会改变后续行为。例如：

- `normal` 通常增加一条用户消息；
- `regenerate` 会移除待替换的末条回复；
- `swipe` 从候选回复路径继续；
- `continue` 把末条 assistant 消息视为待续写前缀；
- `impersonate` 生成用户口吻文本但不保存为角色回复；
- `quiet` 为扩展提供不进入普通聊天的生成；
- 工具调用成功后会增加递归深度，再次调用 `Generate('normal', ...)`。

所以“生成类型”不是 UI 标签，而是轮次编排输入的一部分。

### 4.2 普通用户消息在请求构造前就被保存

普通发送会调用 `sendMessageAsUser()`。该函数依次：

1. 对原始输入执行 USER_INPUT placement 的非 Prompt、非 Markdown Regex；
2. 展开宏；
3. 构造 chat message；
4. 附加文件；
5. 写入 chat 并保存；
6. 发出 `MESSAGE_SENT` 和渲染事件。

对应源码为 `public/script.js:5815-5863`。

这意味着 ST 的事务语义是：

> 用户消息先成为会话事实，然后才尝试构造和发送模型请求。

如果消息写入后的编排或模型请求失败，用户消息通常仍留在聊天历史中。它不是一个“请求成功后原子提交 user + assistant”的事务。

同时，普通 USER_INPUT Regex 和宏展开后的结果会直接进入持久化消息；原始输入通常不会作为独立字段保留。

## 5. 发送侧第一阶段：建立本轮聊天投影

### 5.1 读取角色与会话字段

`getCharacterCardFields()` 在每轮生成时解析：

- system prompt；
- post-history instruction / jailbreak；
- description；
- personality；
- scenario；
- example dialogue；
- persona；
- character depth prompt；
- creator notes。

字段通过 `baseChatReplace()` 展开宏、按设置折叠换行并去除 `\r`。群聊还可能从多个角色收集或覆盖部分字段。

这说明角色卡导入后的字符串不是“最终 Prompt 片段”。它们在每轮生成时仍经过模板求值和会话级 override。

### 5.2 Character depth prompt 先进入扩展 Prompt 注册表

角色卡的 depth prompt 不直接拼进角色 description。`Generate()` 会先清除旧 depth prompt，再调用 `setExtensionPrompt()` 注册：

```text
key      = DEPTH_PROMPT 或群聊成员专用 key
position = IN_CHAT
depth    = 卡片声明的 depth
role     = system / user / assistant
scan     = 是否参与世界书扫描
```

后续 Text Completion 和 Chat Completion 分别从同一个 `extension_prompts` 全局表读取它。

这张表是 ST 很重要的内部装配接口。作者注、记忆、向量、角色 depth prompt、世界书 atDepth 结果和扩展都可以通过它参与本轮请求。

### 5.3 聊天历史先生成 Prompt 投影，不直接使用存储文本

`Generate()` 从 chat 中排除普通 system message，但保留允许的工具消息，然后逐条生成 `coreChat`：

```text
stored message
  -> 根据 is_user 选择 USER_INPUT 或 AI_OUTPUT placement
  -> getRegexedString(..., { isPrompt: true, depth })
  -> 附加文件内容
  -> 附加需要进入 Prompt 的媒体标题
  -> prompt-side chat item
```

这里有两个直接结论：

1. 下一轮发送的聊天文本可以不同于数据库中的 `mes`；
2. 世界书随后扫描的也是这份 Prompt 投影，而不是完全原始的存储文本。

reasoning 也会根据设置和深度进行 REASONING placement 的 Prompt 投影，再由 `PromptReasoning` 按模型能力附回消息。

### 5.4 扩展 interceptor 位于世界书扫描之前

在 `coreChat` 建立并计算最大 Prompt token 后，ST 按扩展 manifest 顺序运行 `generate_interceptor`：

```text
interceptor(coreChat, contextSize, abort, generationType)
```

interceptor 可以修改传入的聊天对象，也可以请求中止生成。dry run 会跳过这一阶段。

因此，启用扩展时，世界书扫描和最终 Prompt 不只由角色、Preset 与 chat 决定；扩展在扫描前已经有机会改变候选聊天内容。

## 6. 世界书：一次带预算和状态的查询过程

### 6.1 世界书扫描的输入

`Generate()` 构造的世界书输入包括：

```text
chatForWI
  = Prompt 侧 Regex 处理后的 coreChat
  + 可选的说话者名字
  + 文件附加文本和标题

globalScanData
  = persona
  + character description
  + character personality
  + character depth prompt
  + scenario
  + creator notes
  + generation type trigger

scan-enabled extension prompts
```

聊天消息按从新到旧的方向交给 `WorldInfoBuffer`。具体条目可以使用自己的 scan depth 和匹配设置决定实际扫描窗口。

### 6.2 激活不是简单的关键词查找

`checkWorldInfo()` 的循环至少依次考虑：

- 条目是否禁用；
- generation type trigger；
- 角色名和标签过滤；
- delay、cooldown、sticky；
- recursion 相关限制；
- decorator 强制启用或禁用；
- 外部强制激活；
- `constant`；
- primary keys；
- secondary keys 与 AND ANY、AND ALL、NOT ANY、NOT ALL；
- inclusion group；
- probability；
- 世界书 token budget；
- recursive scan；
- minimum activation 深度扩展。

关键词自身会先展开宏。条目内容在通过概率检查后也会展开宏，然后参与预算计算与递归文本。

世界书预算按最大 Prompt context 的百分比计算，并可再受绝对 cap 限制。它和稍后的总 Prompt token 预算是两层不同的限制。

### 6.3 激活结果不是一段字符串

世界书最终会按 position 分流为：

```text
worldInfoBefore
worldInfoAfter
example-message before / after
author's note top / bottom
atDepth(depth, role)
named outlet
```

条目在进入这些结果前还会执行 WORLD_INFO placement 的 Prompt Regex。

其中：

- before/after 进入 story string 或 Prompt Manager marker；
- example-message 条目进入示例对话集合；
- author's-note 条目会改写本轮作者注 extension prompt；
- atDepth 条目被重新注册到 `extension_prompts`；
- outlet 作为命名输出供其他 Prompt 或扩展领取。

扫描成功后，非 dry-run 还会更新 timed effects，并发出 `WORLD_INFO_ACTIVATED`。每次扫描循环都发出可修改状态的 `WORLDINFO_SCAN_DONE` 事件。

因此世界书同时具备：

- 条件查询；
- 内容排序；
- token 子预算；
- 多目标路由；
- 跨轮状态；
- 扩展钩子。

它不是一个静态 lore 字符串列表。

## 7. 扩展 Prompt：ST 的内部装配总线

`extension_prompts` 的单项结构大致为：

```text
ExtensionPrompt {
  value
  position   // BEFORE_PROMPT / IN_PROMPT / IN_CHAT / NONE
  depth
  scan       // 是否参与世界书扫描
  role       // system / user / assistant
  filter
}
```

读取相同位置的多个 Prompt 时，ST 按 key 排序、过滤 position/depth/role、拼接文本并展开宏。

它承担的能力比名字更宽：

- Character depth prompt；
- Author's Note；
- memory summary；
- vectors；
- 世界书 atDepth；
- persona atDepth；
- quiet prompt；
- 其他扩展注入。

这解释了 ST 为什么看起来像“很多系统共同往 Prompt 里塞文本”：它确实提供了一张共享的、可变的注入表，而不是要求每个能力都成为 Prompt Manager 的正式节点。

## 8. 两条不同的请求编排路径

### 8.1 Text Completion：最终压平成字符串

当 `main_api !== 'openai'` 时，ST 主要走 Text Completion 路径。

#### 8.1.1 story string

ST 把以下内容作为 Handlebars 参数交给 context preset 的 `story_string`：

```text
description
personality
persona
scenario
system
worldInfoBefore / worldInfoAfter
before / after scenario anchors
message examples
```

模板渲染后还会再执行宏替换。根据 context 设置，story string 可以保留在 Prompt 顶部，也可以作为指定 role/depth 的 IN_CHAT extension prompt。

#### 8.1.2 聊天和深度注入

`doChatInject()` 会把 `extension_prompts` 中的 IN_CHAT 内容按 depth 和 role 插入 `coreChat`。之后每条历史消息再经过 instruct/chat template 格式化。

如果启用了 instruct mode，system、user、assistant 消息会被 input/output sequence、name 和 wrap 设置转换为文本片段。角色卡 post-history instruction 还可能在历史末尾作为 user message 插入。

#### 8.1.3 token 预算

Text Completion 的预算过程不是对整棵 Prompt 做一次统一裁剪：

1. 先计算 story string、示例、preamble、尾部指令等固定开销；
2. 预先尝试放入所有深度注入消息；
3. 再从最近聊天开始加入普通历史，直到超出预算；
4. 未 pin 的示例对话使用剩余预算；
5. 组装后如果仍超限，再递归移除示例或最旧聊天。

深度注入因此享有比普通历史更强的预算优先级。`pin_examples` 则改变示例与聊天历史争夺预算的顺序。

#### 8.1.4 最终字符串

最终 Prompt 大致为：

```text
combinedStoryString
+ selectedExamples
+ chatPreamble
+ formattedHistoryWithInjections
+ cyclePrompt / continuation cache
+ last-line instruction / character prefix / bias
```

在压平前后，扩展还能通过：

- `GENERATE_BEFORE_COMBINE_PROMPTS`；
- `GENERATE_AFTER_COMBINE_PROMPTS`；
- `GENERATE_AFTER_DATA`

读取或替换最终内容。

### 8.2 Chat Completion：Prompt Manager 加结构化消息

当 `main_api === 'openai'` 时，ST 不使用 story string 作为最终请求，而调用 `prepareOpenAIMessages()`。

这里的 `openai` 是 ST 的历史命名，实际覆盖 OpenAI、Anthropic、Gemini、Mistral、OpenRouter 和多个兼容服务。

#### 8.2.1 Preset order 的实际作用

Prompt Manager 保存两组数据：

```text
prompts       // identifier -> Prompt 定义
prompt_order  // identifier 的有序引用和 enabled
```

当前 Chat Completion 配置使用 global strategy，虚拟角色 ID 为 `100001`。`getPromptCollection(generationType)` 按 `prompt_order` 遍历：

1. 找到对应 Prompt 定义；
2. 检查 order entry 的 `enabled`；
3. 检查 `injection_trigger` 是否匹配本次生成类型；
4. 展开宏；
5. 按原顺序生成 `PromptCollection`。

如果 `main` 被禁用，ST仍加入一个空 main 占位，以便依赖 main 的相对扩展能够定位。

#### 8.2.2 marker 不是最终消息

角色和世界书运行时内容先被构造成固定 identifier：

```text
worldInfoBefore
worldInfoAfter
charDescription
charPersonality
scenario
personaDescription
```

然后与 Prompt Manager 的 marker 合并：

- marker 提供位置、role、relative/absolute、depth 和 order；
- 运行时对象提供本轮实际 content；
- 角色卡 system prompt 可以覆盖 `main`；
- post-history instruction 可以覆盖 `jailbreak`；
- `forbid_overrides` 和启用状态限制覆盖。

所以 ST 的设计确实允许 Preset 调整角色描述、scenario、世界书 before/after 等内容在最终消息列表中的位置和 role。这是当前社区兼容语义的一部分，不只是编辑器表现。

#### 8.2.3 Relative 与 Absolute

Prompt Manager 中：

- Relative Prompt 按 `prompt_order` 所在索引成为顶层 `MessageCollection`；
- Absolute Prompt 不在该位置直接生成消息，而是按 `injection_depth`、`injection_order` 和 role 插入聊天历史；
- extension prompt 的 IN_CHAT 内容会和 Absolute Prompt 在深度注入阶段汇合。

同一深度下，代码先按 `injection_order` 分组，再按 system、user、assistant 角色生成消息，之后结合历史方向完成最终插入。这里的最终顺序不能只看 Preset 数组位置，还要同时看 depth、order、role 和代码中的反转过程。

#### 8.2.4 代码强制加入的控制内容

以下内容不完全服从普通 `prompt_order`：

- quiet prompt；
- impersonation prompt；
- continue nudge 或 continue prefill；
- assistant prefill；
- group nudge；
- new chat prompt；
- send-if-empty 补位；
- tool definitions 和 tool results。

其中 control prompts 会预留预算并被放在末尾；new-chat message 会在 chatHistory 中保留位置；工具数据也会预分配 token。

因此，把 ST Chat Completion 简化成：

```text
for prompt in prompt_order:
    messages += prompt
```

会遗漏大量实际语义。

#### 8.2.5 Chat Completion token 预算

`ChatCompletion.setTokenBudget(context, response)` 直接令：

```text
promptBudget = openai_max_context - openai_max_tokens
```

之后：

1. 相对 Prompt 和固定内容先加入，超预算会抛出 mandatory prompt 错误；
2. control prompts、new-chat、group/continue nudge 和工具数据先 reserve；
3. 聊天历史从最近到最旧逐条尝试；
4. 超出预算时停止加入更旧历史；
5. 是否 pin examples 决定示例先于还是后于聊天历史消费预算；
6. 最后释放 control reservation 并把 control messages 放入结果。

这是一种**按类别优先级和插入时机分配预算**的算法，不是简单的“总 token 超限就从最早消息开始删”。

#### 8.2.6 最终消息仍可能再改变

`prepareOpenAIMessages()` 产出扁平的 `{role, content, ...}` 后：

- 可选地 squash 连续、无 name 的 system messages；
- 发出 `CHAT_COMPLETION_PROMPT_READY`，监听器可以直接修改 chat；
- `createGenerationParameters()` 加入采样、stop、prefill、reasoning、工具和模型能力字段；
- 发出 `CHAT_COMPLETION_SETTINGS_READY`；
- 服务端还可执行 `custom_prompt_post_processing`；
- Provider adapter 再转换 role、system prompt、name、media、tool 和 reasoning 表示。

因此 Prompt Manager 检查页看到的内容很接近模型输入，但仍不必然等于最终 HTTP body。

## 9. Provider 转换属于内核边界，而不是 Prompt 排序

前端将 Chat Completion 内容先统一为类 ChatML messages，但服务端会按 Provider 再转换。

典型行为包括：

- Anthropic：抽取开头连续 system messages，其他 system 改为 user，转换 tool use/result，并处理 assistant prefill；
- Gemini：抽取开头 system instruction，把 assistant 改为 model，把其他 system/tool 映射到 user，并把媒体变为 parts；
- OpenAI-compatible：根据模型删除不支持的 sampling/stop 字段，处理 reasoning model 的 system role；
- 部分服务：合并连续同 role 消息、插入占位消息或强制 alternate roles；
- Text Completion model：把 messages 再转换成单字符串 prompt。

`custom_prompt_post_processing` 还提供 `merge`、`semi`、`strict`、`single` 等策略，在服务端路由到 Provider 前修改消息集合。

因此 ST 的请求处理至少有三个不同层次：

```text
内容语义编排
  -> 通用 role messages
  -> Provider 能力与格式转换
  -> HTTP 传输
```

## 10. 宏不是纯模板替换

### 10.1 宏在多个阶段重复求值

`substituteParams()` 会出现在：

- 用户消息入库；
- 角色卡字段读取；
- Prompt Manager Prompt 准备；
- extension prompt 读取；
- 世界书 key；
- 世界书 content；
- story string；
- Regex find/replace/trim；
- stop strings；
- prefill 和各种 nudge。

所以“宏展开”不是单一流水线节点。不同内容在被消费时各自展开，先后顺序会影响结果。

### 10.2 变量宏具有副作用

`setvar`、`addvar`、`incvar`、`deletevar` 以及 global 对应项会直接修改变量存储。宏的顺序因此可能变成执行顺序：

```text
Prompt A: {{setvar::mood::angry}}
Prompt B: {{getvar::mood}}
```

如果 A 在 B 前展开，B 会看到新值；反过来则不会。

复杂社区 Preset 使用大量变量宏时，`prompt_order` 不只影响消息排列，也可能影响状态变化。

### 10.3 当前版本有两套宏引擎

当前源码仍保留 legacy regex-based evaluator，同时通过 `experimental_macro_engine` 开关启用新的 lexer/parser/registry engine。`substituteParams()` 根据开关分流。

这意味着“ST 宏兼容”必须声明目标引擎和版本，不能笼统地写成支持 `{{...}}`。

### 10.4 尚需动态验证的副作用边界

从源码调用路径看，dry run、Prompt Manager token 预览和流式重复清理都可能多次触发宏求值。当前代码没有在 `Generate()` 外层建立统一的变量事务。

仅靠静态阅读还不能断言每一种变量修改最终是否持久化、是否被某个保存节流或扩展抵消。尚未确认的问题包括：

- 一次 normal generation 实际执行几次；
- dry run 是否改变局部或全局变量；
- streaming 与 non-streaming 是否结果一致；
- Prompt Manager 预览是否产生状态变化。

## 11. Regex 的真实三层投影

### 11.1 来源与执行顺序

Regex 来源包括：

```text
GLOBAL
PRESET
SCOPED / character
```

`getRegexScripts()` 按源码声明顺序组合为 global、preset、scoped，然后逐条串行执行；后一条看到前一条的结果。Preset 和 scoped 脚本只有在本地允许列表中获准后才会进入实际执行集合。

### 11.2 placement

当前 placement 包括：

```text
USER_INPUT
AI_OUTPUT
SLASH_COMMAND
WORLD_INFO
REASONING
```

旧的 MD_DISPLAY placement 已弃用，展示/Prompt 区分由 flags 表达。

### 11.3 三种使用情境

在 placement 匹配的前提下，ST 根据 flags 选择规则：

| Regex flags | 调用情境 | 典型结果 |
| --- | --- | --- |
| `markdownOnly=false`, `promptOnly=false` | 既非 Markdown、也非 Prompt | 改变入库用户消息、入库 AI 回复或持久化 reasoning |
| `promptOnly=true` | `{ isPrompt: true }` | 改变下一轮发给模型的历史或 WORLD_INFO 文本，不改存储消息 |
| `markdownOnly=true` | `{ isMarkdown: true }` | 改变当前展示 HTML 的源文本，不改存储消息 |

如果一条规则同时打开两个 only，它会在相应的两种投影中分别运行。

### 11.4 用户输入的三种文本

普通用户输入的路径是：

```text
输入框原文
  -> 通用 USER_INPUT Regex
  -> 宏展开
  -> chat[].mes
  -> 随后本轮请求以及以后各轮构造 coreChat 时执行 promptOnly USER_INPUT Regex
  -> 模型输入
  -> messageFormatting 时执行 markdownOnly USER_INPUT Regex
  -> UI
```

ST 默认保存的是通用 Regex 和宏已经处理后的文本，不是输入框原文。

### 11.5 AI 输出的三种文本

普通 AI 回复的路径是：

```text
Provider 累计文本
  -> cleanUpMessage
     -> stop fragment 清理
     -> 通用 AI_OUTPUT Regex
     -> 换行、空白、名字和 instruct 泄漏清理
  -> chat[].mes
  -> 下一轮构造 coreChat 时执行 promptOnly AI_OUTPUT Regex
  -> 模型输入
  -> messageFormatting 时执行 markdownOnly AI_OUTPUT Regex
  -> UI
```

因此 ST 已经隐含区分：

```text
model response
stored/history source
next-prompt projection
display projection
```

但普通消息对象通常只持久化中间的 `mes`，并没有系统性保存最初的 Provider raw text 和每一步 transform trace。

### 11.6 Regex replacement 还会再次求值宏

Regex 引擎支持：

- find regex 中不展开宏、原样展开宏或转义后展开宏；
- `{{match}}`；
- 编号和命名捕获组；
- `trimStrings`；
- replaceString 最后的宏展开。

所以 Regex 也可能触发变量副作用。流式路径对累计文本反复调用 `cleanUpMessage()`，变量宏放在 Regex replacement 中时是否会多次执行，是另一个需要动态 fixture 验证的风险点。

## 12. 接收侧：从 Provider 响应到聊天消息

### 12.1 Provider 解析

Chat Completion 前端按具体来源从事件或响应中分别提取：

- 正文 text；
- reasoning / thinking；
- thought signature；
- tool calls；
- 图片；
- logprobs；
- multi-swipe 候选。

流式响应会累计这些状态，而不是把每个 SSE event 直接当成聊天文本。

### 12.2 非流式路径

非流式成功后：

1. `extractMessageFromData()` 提取正文；
2. 单独提取 reasoning、signature、images 和 swipes；
3. `cleanUpMessage()` 处理正文；
4. REASONING Regex 处理 reasoning；
5. continue 时拼回旧消息；
6. `saveReply()` 写入或更新 chat；
7. 发出消息接收和渲染事件；
8. 保存整个 chat。

### 12.3 流式路径

`StreamingProcessor` 先创建一个占位回复，然后对**当前累计全文**反复调用 `cleanUpMessage()`：

- 中途允许显示未完成句子；
- 中途为可渲染性补齐 Markdown 分隔符，下一次累计投影和最终结果会重新计算；
- 持续更新 `chat[messageId].mes`；
- reasoning 独立累计和持久化；
- 流结束时再做一次 final cleanup；
- 最后同步 swipe、附件、签名、事件并保存 chat。

这不是“token 到达就 append 到最终消息”的简单模型，而是“累计全文不断重新投影”。

### 12.4 存储内容

`saveReply()` 主要保存：

```text
mes
name / is_user / is_system
send_date
generation timing
api / model
reasoning
reasoning signature
token count
media
swipes and swipe_info
```

正文 `mes` 已经过 cleanup 和通用 AI_OUTPUT Regex。reasoning 作为 `extra.reasoning` 分开保存。

## 13. 事件与扩展让执行顺序成为开放系统

一轮生成中与 Prompt/请求直接相关的扩展点至少包括：

```text
GENERATION_STARTED
GENERATION_AFTER_COMMANDS
generate_interceptor
WORLDINFO_SCAN_DONE
WORLD_INFO_ACTIVATED
GENERATE_BEFORE_COMBINE_PROMPTS       // Text Completion 合并前
GENERATE_AFTER_COMBINE_PROMPTS        // 合并字符串后；Chat Completion 此处通常为空字符串
CHAT_COMPLETION_PROMPT_READY           // Chat Completion messages
CHAT_COMPLETION_SETTINGS_READY         // Chat Completion request settings
GENERATE_AFTER_DATA
MESSAGE_RECEIVED
CHARACTER_MESSAGE_RENDERED
```

部分事件只通知，部分事件允许监听器修改传入对象。第三方扩展因此可以在多个层级接管行为：

- Prompt 编排前；
- 世界书扫描循环中；
- messages 已组装后；
- sampling/request data 已构造后；
- 消息保存和渲染后。

这些开放修改点意味着，启用的扩展组合会成为 ST 实际生成语义的一部分。

## 14. 当前可以确认与不能确认的边界

### 已由源码确认

- 普通用户消息在模型请求前写入 chat。
- Prompt 侧聊天历史会重新执行 promptOnly Regex。
- 世界书扫描使用 Prompt 投影后的聊天历史。
- 世界书具有独立预算、递归和跨轮 timed effects。
- Chat Completion 的 `prompt_order` 只控制一部分最终顺序。
- Relative、Absolute、extension prompt 和 control prompt 有不同装配路径。
- Text Completion 与 Chat Completion 使用不同的 token 预算算法。
- Chat Completion messages 在前端组装后仍会被事件和 Provider adapter 修改。
- 通用、promptOnly、markdownOnly Regex 分别对应持久化、请求和展示情境。
- AI 回复正文经过 cleanup 后才保存，reasoning 独立保存。
- 工具调用可以递归触发下一轮生成。

### 尚未通过运行时实验确认

- 所有宏副作用在 dry run、预览和流式模式下的提交次数。
- 每种复杂 Prompt order 与相同 depth/order 冲突的最终稳定顺序。
- 各 Provider 在真实请求中的全部消息修正结果。
- 第三方扩展组合下的完整事件先后关系。
- 不同 ST 版本之间上述行为的兼容稳定性。

静态源码阅读不能替代运行时结果；上述未确认项也不构成已经完成的兼容规范。
