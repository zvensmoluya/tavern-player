# Tavern Player Architecture v0.1

> 状态：讨论草案，尚未冻结为实现契约。
>
> 本文回答“架构应该如何走”，不回答“现在马上写哪些页面或类”。具体字段、执行顺序和兼容范围，必须先经过 SillyTavern 语义调查与 fixture 验证。

## 1. 架构目标

Tavern Player 的长期目标，是把 SillyTavern 社区长期形成的角色卡、Preset、世界书、Regex、宏和其他约定，编译成一个干净、可观察的原生运行时。

架构必须同时满足：

- 用户体验仍然围绕角色、预设、模型和聊天四件事。
- 外部社区格式可以持续增加，但不会污染核心运行时。
- 一轮生成的输入、编排、请求、响应和状态变化都能被解释和测试。
- 核心语义不依赖 Android、Compose、HTTP 或某一个模型 Provider。
- 能力可以逐步增加，但不需要一次发明完整的插件平台或通用脚本框架。

## 2. 明确不追求的东西

- SillyTavern 工作台的原生重写。
- 一开始就设计新的公开角色卡或 DSL 格式。
- 把所有社区能力都做成独立的管理页面。
- 以 `Manager`、`Factory`、`Provider` 等抽象填满尚未证实的不稳定点。
- MVP 阶段的远程统一网关服务、多端同步、插件 API 或通用脚本执行器。

## 3. 概念分层

目录可以保持产品导向，但概念上应区分四层：

```text
外部社区内容
  -> Compatibility / Import
  -> 规范化领域模型
     CharacterPackage / Preset / ChatSession / ModelProfile
  -> Compiler / Runtime
     Context / Lore / Macro / Transform / Budget / Plan
  -> Ports and Adapters
     Gateway / Tokenizer / Room / Files / Secrets / UI
```

### 3.1 Compatibility / Import

这一层理解外部世界：CCv1、CCv2、CCv3、PNG、JSON、CharX、ST backup，以及未来出现的社区格式。

它的责任是：

- 读取原始资产；
- 判断格式和版本；
- 提取标准字段与扩展字段；
- 保留原始数据和来源信息；
- 生成规范化领域对象；
- 对部分支持、未知字段和格式错误产生结构化诊断。

它不应该把外部对象直接泄漏给 runtime、chat 或 UI。

### 3.2 规范化领域模型

这一层表达 Tavern Player 自己理解的内容，而不是复刻 ST 的对象树。

候选对象包括：

- `CharacterPackage`：完整角色内容包；
- `Preset`：演绎与生成策略；
- `ChatSession`：一次会话及其消息；
- `ModelProfile`：用户选择的模型连接配置；
- `RuntimeState`：会话中会变化的运行状态。

这些对象不应依赖 Android、Room、HTTP 或 SillyTavern 类型。

### 3.3 Compiler / Runtime

这一层是项目真正的内核。它将领域对象和本轮上下文编排成模型请求，并把模型响应转换成消息和状态变化。

它不应该直接读取文件、访问数据库、调用 Compose 或操作具体 Provider。

### 3.4 Ports and Adapters

这里放真正不稳定的边界：

- `ModelGateway`：五种协议原生请求、流式事件、取消和统一传输错误；
- `Tokenizer` / `TokenCounter`：上下文预算所需的计数能力；
- `AssetImporter`：外部资产导入入口；
- repository ports：角色、Preset、会话和消息的存取。

当前 `:model-gateway` 实现包括 OpenAI Responses、OpenAI Chat Completions、Anthropic Messages、Gemini Interactions 和 Gemini GenerateContent 五个原生客户端。Android Keystore、DataStore、连接模板与展示名称、Compose 连接管理均属于 `app`，不反向进入网关模块。

## 4. 依赖方向

概念上的依赖关系应保持为：

```text
compat  -> CharacterPackage / Preset
data    -> domain models + repository ports
runtime -> domain models + TokenCounter port
gateway -> protocol-native request / event types + shared transport
app     -> runtime / chat / gateway / repositories
```

关键规则：

- `runtime` 永远不能 import `compat`、`sillytavern`、`ccv2` 或其他外部格式名称。
- `app` 不负责拼接 Prompt，不实现世界书、宏或 Regex 语义。
- `data` 不拥有领域模型；Room Entity 应映射到领域对象。
- `gateway` 不反向修改 `ChatSession`；请求结果由上层应用流程显式写入会话。
- 不用全局 Event Bus 代替明确的函数输入、返回值和状态变化。

这些规则比目录名称更重要。未来可以调整文件夹，但不能反转依赖方向。

## 5. 三种必须分开的东西

### 5.1 内容包不是执行计划

`CharacterPackage` 表达“角色是什么”，不表达“本轮最终发送什么”。

```text
RawAsset
  -> CharacterPackage
  -> CompiledPlan
  -> GenerationRequest
```

同一个角色在不同聊天历史、Preset、模型上下文限制下，可以产生不同的 `CompiledPlan`。

### 5.2 执行计划不是运行状态

`CompiledPlan` 表达本轮要执行的步骤和消息；`RuntimeState` 表达会话推进后留下的状态，例如未来可能支持的 sticky/cooldown、递归扫描状态或其他时序信息。

编译器应返回状态变化，而不是自行修改数据库：

```text
compile(input, state) -> plan + diagnostics
execute(plan, gateway) -> response events
process(response, state) -> message result + state delta
```

这使得 dry-run、golden test、重放和错误恢复成为可能。

### 5.3 Prompt Transform 不是 Display Transform

一个 Regex 可能改变发给模型的文本，也可能只改变用户看到的文本。两者必须有不同的阶段和作用域。

候选表示应至少能表达：

- 输入来源；
- 执行阶段；
- 目标内容；
- 顺序或优先级；
- 是否产生诊断。

不能让一个无上下文的 `replace()` 隐式同时改变 Prompt、原始消息和 UI 展示。

## 6. 核心领域对象的候选职责

### `CharacterPackage`

角色是完整的内容单元，而不是只有六个文本字段。候选组成：

```text
CharacterPackage
├─ identity
├─ greetings
├─ lore
├─ transforms
├─ asset references
├─ typed runtime policy
├─ provenance / source metadata
└─ preserved extensions
```

其中：

- `lore` 表达角色携带的世界知识和触发规则；
- `transforms` 表达角色作用域的 Prompt/Display 变换；
- `asset references` 指向头像或附属资源，不把大块二进制直接塞进运行时对象；
- `provenance` 应能追踪到来源文件和字段，而不只是记录一个导入时间；
- 未知扩展可以保留，但不能自动变成 runtime 可执行代码。

### `Preset`

用户可以看到一个简单的演绎风格，但内部仍需保留不同语义：

```text
Preset
├─ writing instructions
├─ prompt layout
├─ generation policy
├─ transform policy
└─ source metadata / compatibility state
```

ST 的 context、instruct、system prompt、reasoning 和采样参数可以分别从 compat 层进入，再归一化到这些字段。不能把它们全部当成一段可拼接文本。

### `ChatSession` 和 `Message`

会话至少需要知道：

- 使用的角色、Preset 和 ModelProfile；
- 消息的角色、内容和顺序；
- 请求中、成功、失败、取消等生成状态；
- 必要的 raw model output、display output 和处理记录。

为了允许未来重新处理 Regex 或诊断问题，建议不要只保存最终展示文本。

### `ModelProfile`

它是用户配置，不是 Provider 实现。候选内容包括连接类型、endpoint、model id、上下文限制和默认生成参数引用。

密钥不应直接放入 `ModelProfile`，而应通过 secrets 存储和引用机制取得。

## 7. 一轮生成的候选编排

下面是用于研究和讨论的候选流程，执行顺序尚未冻结：

```text
TurnInput
  = CharacterPackage
  + Preset
  + ChatSession
  + ModelProfile
  + RuntimeState
  + user input

        |
        v
Resolve context and macros
        |
        v
Activate lore against explicit chat context
        |
        v
Build ordered prompt sections/messages
        |
        v
Apply layout and role rules
        |
        v
Apply token budget policy
        |
        v
Apply prompt-scoped transforms
        |
        v
CompiledPlan + Trace + Diagnostics
        |
        v
Protocol request mapping
        |
        v
ModelGateway native client stream
        |
        v
ResponsePipeline
        |
        v
MessageResult + StateDelta + Diagnostics
```

最终顺序不能凭直觉确定。需要用真实 ST fixture 验证：宏到底在哪些阶段解析、世界书扫描使用什么上下文、示例对话插在什么位置、Regex 在哪个阶段生效，以及 token budget 如何影响结果。

## 8. Model Gateway 边界

首个可运行切片已经将“轻量模型网关”实现为 Android 无关的 `:model-gateway` Kotlin/JVM 模块。它不是远程服务，也不是 OpenAI-compatible 统一接口：

```text
ModelGateway
├─ responses
├─ chatCompletions
├─ anthropicMessages
├─ geminiInteractions
└─ geminiGenerateContent
```

五个客户端分别接收自己的强类型 request，返回自己的 `Flow<ProtocolEvent>`。共享的是 HTTPS 校验、鉴权注入、超时、响应上限、同源重定向、SSE framing、取消和 typed error；正文、reasoning/thinking、signature、usage 与 finish 状态不在传输层互相冒充。

流式调用是唯一生成原语。每种协议提供 accumulator 得到自己的 result 与 usage 类型，不维护第二套非流式 HTTP 路径，也不以共享 result 抹平字段语义。未知事件保留 raw JSON 并成为 `Unknown`，协议增加事件不会令流崩溃；畸形 JSON、越界响应和安全失败仍作为 `GatewayException` 抛出。

连接探针为了展示统一的 token 摘要，会在 `app` 的 `ProbeService` 中把五种原生 usage 映射为 `ProbeUsage`。这只是当前页面的 UI 投影，不属于网关契约，也不能被未来 Runtime 当作协议无关的模型响应。

连接保存完整操作 URL，不猜测 `/v1`，也不根据 endpoint 自动切换协议。Gemini Interactions 与 GenerateContent 是两个客户端；Vertex Express 复用 GenerateContent 客户端。官方 endpoint、鉴权默认值与 Vertex Express 等可选项是 App 的连接模板，不是网关对 Provider 的内置认识。OpenRouter 的一个 key 对应多种协议和模型，属于以后可选的聚合层，不进入当前内核。

Preset 与 ST Runtime 尚未开始映射生成参数。当前连接探针固定用协议原生字段发送 512 token 上限，并省略 temperature、top-p、top-k。等 Runtime 有真实需求时，再在明确的协议边界完成映射，而不是先发明共享 sampling options 或通用 Provider DSL。

## 9. 存储边界

MVP 的数据库可以保持简单，候选记录为：

```text
characters
presets
model_profiles
chat_sessions
messages
imported_assets
```

不一开始拆出 `WorldBookEntity`、`RegexEntity`、`MacroEntity`、`PromptNodeEntity`。角色内部的 lore 和 transforms 首先属于角色包的一部分，除非未来出现明确的独立查询、共享或编辑需求。

`imported_assets` 至少应记录：

- 原始来源类型；
- 内容 hash；
- 原始文件的应用内副本或可恢复引用；
- importer 版本；
- 兼容性状态和诊断摘要。

规范化包也应带版本。以后兼容器升级时，可以基于 raw asset 重新导入，而不是只能修补已经丢失的数据。

## 10. 兼容性验证

兼容性目标不是“代码长得像 ST”，而是：

> 对相同输入，在声明的支持范围内得到相同的可观察结果。

每个 fixture 最好同时保存：

- 输入资产；
- 规范化后的关键结果；
- 聊天历史；
- 预期激活的 lore；
- 预期执行计划或最终 Prompt；
- 预期 raw/display response transform 结果；
- 已知的部分支持和诊断。

初始 corpus 可以按语义风险组织，而不是按文件扩展名组织：

```text
fixtures/
  normal-card/
  lore-heavy-card/
  regex-prompt-card/
  regex-display-card/
  preset-heavy-card/
  malformed-card/
  cursed-community-card/
```

## 11. 当前仍未决定的事项

以下问题在 Architecture v0.1 中故意保持开放：

1. `CharacterPackage` 的最小规范化字段和未知扩展的保留形式。
2. Lore 激活、宏解析、Prompt layout 和 transform 的确切顺序。
3. Preset 的不同 ST 来源如何合并，冲突时谁覆盖谁。
4. 宏的首批支持范围，以及是否允许任何动态脚本。
5. Token budget 需要精确 tokenizer 还是可插拔估算器。
6. RuntimeState 哪些部分需要持久化，哪些只存在于一轮编排中。
7. 流式响应中间状态如何暴露给 Chat UI，同时保持会话写入的一致性。
8. raw message、display message 和 transform trace 的最终存储格式。

这些问题应通过 ST 调查、fixture 和小型设计实验解决，而不是先凭目录结构决定。

## 12. 如何使用这份草案

本文提供候选边界和需要留意的风险，不构成开始实现前必须完成的设计阶段，也不规定 Kotlin 类型、模块目录或实施顺序。

术语表、依赖图、时序图、候选结构和 fixture 都可以在它们有助于当前探索时产生。实现也可以反过来验证、推翻或简化本文中的判断。已经通过实际代码确认的决定应更新到文档中；仍未验证的内容继续保持为候选项。

当前探索问题和放弃条件见 [`exploration.md`](exploration.md)。
