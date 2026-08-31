# Tavern Player 当前产品边界

> 状态：当前有效的产品与兼容性决定。
>
> 范围：V1 的单角色、普通、结构化文本对话。已落地的代码边界见 [`architecture.md`](architecture.md)。

## 1. 产品与兼容原则

- Tavern Player 是面向角色对话和剧情体验的播放器，不是 SillyTavern 创作工作台、小说续写器或第三方扩展宿主。
- 用户主流程围绕 Character、Preset、Model 和 Conversation；底层能力不必都暴露为独立管理界面。
- 与角色的 Conversation 是一次情景体验，不是独立的可分享“作品”，不建立 `Experience` 对象。
- 只支持结构化 role messages 的对话管线，不支持 Text Completion 字符串续写管线。
- 简化发生在支持能力的集合上，不发生在已选择能力的语义上：先明确不支持的 ST 能力；能力一旦被选择，就以 ST 的可观察行为作为语义基线。
- 只复刻行为，不复制 ST 的源码结构、全局状态、ToolManager、事件系统或扩展架构，也不为了内部结构更优雅而重新解释已选择的能力。

## 2. 内容资产与配置

### Character Card

- 在当前范围内不裁剪 Character Card 的官方行为语义，包括角色定义、开场与备用开场、示例对话、system prompt / post-history override 和 depth prompt。
- Character Book、Character-scoped Regex 和卡片文本中的 Macro 保留关联关系，但分别受 World Book、Regex 和 Macro 的产品边界约束。
- Character Card 字段最终怎样进入请求，遵循后续实现的 ST Preset / Prompt 编排语义。
- 当前导入基线是 Character Card V2 / V3，并兼容 V1 JSON 与 PNG；不支持 CHARX、YAML、BYAF 和独立 Preset 导入。
- V3 `nickname` 是 Prompt 与聊天作者身份，角色库仍显示卡片 `name`。

### Preset

- 不主动裁剪 Preset 的核心官方语义。保留通用 Prompt、Prompt 定义与顺序、启用状态、role、marker、placement / depth、generation settings 和通用模型请求控制。
- `charDescription`、`scenario`、`worldInfo`、`chatHistory` 等 marker 是可跨 Character 复用的动态内容插槽，不表示 Preset 与某个 Character 绑定。
- Preset 是当前全局活跃的生成配置和写作策略。切换 Preset 后，所有后续生成直接使用新 Preset。
- Preset 不属于 Conversation 的剧情状态；Conversation 不保存 Preset snapshot、版本引用或历史绑定。单次生成 metadata 可以记录当时使用的 Preset，但这只是诊断信息。
- Preset 引用的 Macro、Regex、Tools 和 Provider 特殊能力分别受对应领域的产品边界约束。

### World Book

- World Book 在产品上与 Character 强关联。当前 Character 决定本轮参与的 World Book，不建立 ST 的 global、conversation、persona 多来源组合以及来源优先、混排和去重规则。
- 一个 Character 可以关联多本 World Book；内部仍可分别保存 Character Card 与 World Book 数据。
- 保留 Character World Book 条目的官方运行语义，包括 constant、关键词、secondary logic、scan depth、概率、分组、递归、独立预算、sticky / cooldown / delay、order、placement 和 at-depth。
- World Book 定义随 Character 一起进入 Character Snapshot；sticky / cooldown / delay 等运行状态属于 Conversation。
- 不支持 vectorized / embedding 候选激活、聊天或文件向量记忆 / RAG、外部强制激活入口和 `automationId`。

### Persona

- Persona 是可复用的用户身份资产，只保留 name 与 avatar。name 是 `{{user}}` 和用户消息身份的数据来源，avatar 用于聊天展示。
- Conversation 直接选择当前 Persona / UserIdentity。切换 Persona 不改写已有历史消息，历史消息保留发送时的用户身份。
- 不支持 Persona description 及其 placement / depth / role、Persona Lorebook、Persona 参与 World Book 扫描、Character → Persona 绑定，以及应用默认、Character 关联、Conversation 锁定或临时 Persona 等自动选择拓扑。

## 3. Conversation 与剧情状态

- 一个 Character 可以创建多个独立 Conversation。
- Conversation 当前实现 opening swipe、assistant regenerate / swipe 和进程恢复。edit、delete、continue、branch / checkpoint 留在后续 Conversation 工作；impersonate、quiet 和自动操作不作为 V1 核心要求。
- 创建 Conversation 时，从当前 Character Asset 实例化 Character Snapshot：`Character Asset -> Character Snapshot -> Conversation`。
- Character Asset 的后续修改不会隐式改变旧 Conversation。旧 Conversation 升级角色版本必须显式进行。
- Conversation 不维护 ST 式的 scenario、system prompt、examples 等 Character override patch。未来若允许会话内修改角色设定，修改的是该 Conversation 自己的 Character Snapshot。
- Character Snapshot 表示这场剧情使用什么角色定义；Conversation Runtime State 表示剧情运行到了什么状态；Chat History 表示实际发生了什么。
- Conversation Runtime State 包含当前 Persona、Macro local variables、World Book timed state，以及 swipe / branch 等历史状态。

## 4. 请求处理语义

### Macro

- 保留角色、当前用户身份和聊天状态等角色 / 会话级上下文引用，以及普通文本、条件、计算和随机能力。
- 保留当前 Conversation 范围内 local variables 的读取与修改。
- 不支持跨角色、跨会话共享的 global variables，也不得把 global variable 自动降级为 local variable。
- group、Text Completion 和第三方扩展动态 Macro 随对应领域排除。
- 行为基线固定为 SillyTavern 1.18.0（commit `8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8`）默认的新 Macro Engine；只纳入当前产品边界列出的 Macro。

### Regex

- 只保留 Character-scoped 和 Preset-scoped Regex，删除 application-global Regex。
- Regex 是 Character / Preset 的附属运行能力，不建立独立 Regex 资产或全局管理层。
- 已保留规则的 placement、顺序、深度、Prompt / storage / display 投影和 Macro replacement 等行为，以 ST 的可观察语义为基线，并受 Macro 产品边界约束。

### Token / Context

- Context 管理是必须能力。保留 ST 的内容取舍语义，包括回复 token 预留、必选 Prompt、历史由近到远进入、examples 与 history 的预算关系、World Book 独立预算，以及 depth injection 等内容的既定优先关系。
- Tokenizer、模型 context window 和实际请求 token 计算由 Tavern Player 自己正确实现。兼容目标是 ST 的 context semantics，不是 ST 当前的 token accounting implementation。

## 5. Provider 与回复能力

- role / system 转换、模型参数映射、流式事件解析、usage、finish reason 和错误处理属于 Provider adapter 的基础职责。
- Tavern Player 强制采用自己的流式生成策略，不兼容 Preset 的 streaming 开关；Provider 不支持 streaming 时进行能力降级。
- 支持 Reasoning / Thinking 的接收、保存和必要展示。Provider 的 reasoning 与 thought signature 映射在 adapter 中处理。
- Assistant Prefill 是内容语义，予以保留；各 Provider 的具体表达由 adapter 处理。
- 不使用 `previous_response_id` 等 Provider Hosted State。供应商托管状态不是 Conversation 语义或运行依赖。
- 不支持 Provider 原生多候选 `n`；swipe 通过再次生成新候选实现。
- V1 不支持 Multimodal，包括用户图片、附件、模型图片生成和图片内联。
- V1 不支持 JSON Schema / structured output、Provider web search、logprobs 等特殊生成模式。

## 6. Tools、扩展与外部系统

- 不复刻 ST ToolManager，不支持动态工具注册、STscript、第三方扩展工具、stealth tools 或任意外部执行框架。
- V1 没有 Tavern Player 原生 tools，不实现 tool execution、tool history 或 recursive generation。
- Provider 的 function / tool calling 协议表达可以独立存在或预留，但不表示产品支持 Tools。只有未来明确增加原生工具时，才建立执行语义。
- Preset 中依赖 ST Tool runtime 的 `function_calling`、tool recursion 和相关字段当前不产生能力。
- 不运行第三方脚本、扩展事件系统或任意扩展 Macro。未来是否把某项扩展能力重新实现为原生能力，必须另行明确决定。

## 7. 当前交付边界

- Character Card 导入、不可变 Character Snapshot、Macro / Regex / World Book 编排、token accounting、流式发送和 Conversation 恢复已经形成实现契约。
- 默认 Persona 暂时固定为“旅人”；Persona 管理器仍未进入当前阶段。
- 使用一个内置结构化聊天 Preset；社区 Preset 样本的独立导入不在当前阶段。
- 真实社区卡中的未知扩展会原样保留并报告。远程脚本、第三方动态 Macro 和富 HTML 状态栏不会执行、联网加载或被伪装为已兼容。
- 后续工作集中在 Conversation 编辑能力、Persona / Preset 产品化与更广的内容资产管理；这些工作不自动重新打开已经冻结的安全和兼容边界。
