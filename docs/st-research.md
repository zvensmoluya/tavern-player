# SillyTavern 运行语义调查

> 调查对象：`D:\thevox\SillyTavern`，当前 checkout 为 `release` 分支，版本 `1.18.0`。本文记录对 Tavern Player 架构有直接影响的事实，不是 SillyTavern 的完整说明书。

## 结论先行

SillyTavern 不是一个“把角色字段拼成 system prompt”的应用。它更接近一个由多种来源、注入规则和 Provider 转换器组成的 prompt 编排器：

```text
Card / chat metadata / global settings / extensions / preset
  -> prompts with identifiers and placement rules
  -> world-info activation and in-chat injection
  -> token-budgeted ordered message collection
  -> provider-specific conversion
  -> request / stream / response post-processing
```

因此 Tavern Player 应长期保持三条边界：

1. **Source adapter** 负责理解 ST 文件和历史约定。
2. **Content compiler/runtime** 负责稳定、可诊断地执行内容语义。
3. **LLM gateway** 负责把统一请求送到具体模型，并把响应/错误统一回来。

Android UI 只消费运行时产出的消息和状态，不参与 Prompt 拼接。

## 调查到的 ST 事实

### 1. 角色卡有多个来源和版本

- V1 是顶层字段：`name`、`description`、`personality`、`scenario`、`first_mes`、`mes_example`。
- V2 使用 `spec: "chara_card_v2"`、`spec_version: "2.0"`，主要内容位于 `data`，额外包含 `system_prompt`、`post_history_instructions`、`alternate_greetings`、`tags`、`character_book` 和 `extensions`。
- 当前 ST validator 还接受 `chara_card_v3`，版本范围为 `3.x`，但 V3 的 `data` 结构在 validator 中基本保持开放。
- PNG 中通过 `tEXt` chunk 保存 base64 元数据：优先读取 `ccv3`，否则读取 `chara`。JSON、PNG、CharX、BYAF、YAML 等入口最终会被 ST 转换成内部角色对象。
- ST 保存角色时会同时保留标准字段和大量 `extensions`；未知扩展不能被简单抹掉，否则导入后再导出会丢语义。

对 Tavern Player 的含义：MVP 可以只支持 **V1/V2 JSON + PNG 内嵌 V2**，但内部模型应保留 `sourceSpec`、原始字段和结构化诊断，为以后 V3/CharX 适配留位置。

### 2. 角色内容不是单个 prompt

V2 角色中至少有这些具有不同语义的内容：

- `description`、`personality`、`scenario`
- `first_mes` 和 `alternate_greetings`
- `mes_example`，它是带名字和分段约定的示例对话字符串，不是普通文本列表
- `system_prompt` 和 `post_history_instructions`
- `character_book`，即世界书
- `extensions.depth_prompt`、`extensions.regex_scripts` 以及第三方扩展

ST 会将这些内容先映射成带 identifier 的 prompt，例如 `main`、`charDescription`、`charPersonality`、`scenario`、`dialogueExamples`、`chatHistory`、`worldInfoBefore`、`worldInfoAfter` 和 `jailbreak`，再按顺序放入 `ChatCompletion`。

### 3. Prompt 顺序是数据，不是实现细节

ST 的 OpenAI prompt manager 保存两组数据：

- `prompts`：prompt 定义、角色、内容、是否 marker、是否 system prompt、是否允许 override 等。
- `prompt_order`：按角色或全局保存的 identifier 顺序和 enabled 状态。

Prompt 还可以是：

- **relative**：相对主 prompt 的内容；
- **absolute / in-chat**：按 `injection_depth` 和 `injection_order` 插入聊天历史；
- 带 `role` 的 system/user/assistant 消息；
- 被角色覆盖或被扩展动态生成。

ST 的默认 OpenAI 顺序大致是：

```text
main
worldInfoBefore
personaDescription
charDescription
charPersonality
scenario
enhanceDefinitions?
nsfw
worldInfoAfter
dialogueExamples
chatHistory
jailbreak
```

这不是所有 Provider 的最终顺序，因为之后还会做 token budget、in-chat 注入、system message squash 和 Provider 格式转换。

### 4. 世界书是运行时规则，不是静态附件

世界书 entry 通常包含：

- primary keys 和 secondary keys；
- `content`、`enabled`、`constant`、`selective`；
- `insertion_order`、`position`、`depth`、`scan_depth`；
- case-sensitive、whole-word、regex matching；
- selective logic、group scoring、probability；
- recursive scanning、sticky、cooldown、delay 等时序效果。

ST 每次生成时会扫描最近聊天和其他可选上下文，激活 entry，再将内容放到 `worldInfoBefore`、`worldInfoAfter` 或指定深度。激活结果还可能写入聊天 metadata，以影响下一轮。

对 MVP 的含义：第一版只做 `enabled + primary key + recent chat scan + before/after + insertion_order`，但 IR 必须能表达 entry 的触发器、位置和诊断，不能把它设计成只有 `Map<String, String>`。

### 5. Regex 是带作用域和执行时机的后处理

ST 的 Regex 脚本按来源分为 global、preset、scoped，并按优先级合并。每条脚本至少包含：

- `findRegex`、`replaceString`、`trimStrings`；
- placement：user input、AI output、world info、reasoning 等；
- `markdownOnly`、`promptOnly`、`runOnEdit`；
- depth 范围和宏替换选项。

Regex 不是单纯的 UI 展示过滤器；它可能影响发给模型的 prompt，也可能只影响展示给用户的 AI 输出。因此运行时必须区分 `PromptTransform` 和 `RenderTransform`，不能使用一个无上下文的全局 `replace()`。

### 6. ST 的 Preset 至少分成四类

从 `preset-manager.js` 和默认内容可以看出：

1. **Connection/generation preset**：Provider、model、temperature、top_p、max tokens、stream 等。
2. **Context preset**：`story_string`、example separator、chat start、插入位置和深度等。
3. **Instruct preset**：input/output/system sequence、suffix、stop sequence、name behavior 等。
4. **System prompt / reasoning preset**：系统指令、post-history、reasoning 格式。

这些 preset 之间可以绑定到模型或连接。把它们在内部强行合并成一个简单的“预设文本”会丢失模板和 Provider 语义。

Tavern Player 的用户界面可以把它们收敛成一个易懂的“演绎风格”选择，但内部建议保留不同的 capability/adapter 类型。

### 7. Provider 差异在请求前后都存在

ST 的统一聊天消息通常是 `{ role, content }`，但发送前会根据 Provider 做转换：

- system message 是否独立支持；
- 连续同 role 消息是否合并；
- instruct/chat template 如何包裹；
- stop strings、assistant prefill、reasoning、tool calls、图片等如何表达；
- 流式响应如何解析，错误如何归一化。

对 MVP 的建议是只支持一个 **OpenAI Chat Completions 兼容接口**，但从第一天就把它放在 `ModelGateway` 接口后面。不要让编译器直接依赖 HTTP JSON，也不要在 UI 里判断 Provider。

## 建议的长期模块边界

```text
content/
  source/       ST JSON/PNG/CharX adapters
  model/        Content IR, raw extensions, diagnostics
  compiler/     trigger scan, prompt plan, token budget policy
  runtime/      session state, plan evaluation, transforms

gateway/
  api/          provider-neutral request/stream/error types
  openai/       OpenAI-compatible adapter
  ...           future Anthropic, Gemini, local backends

chat/
  model/        conversation and message persistence
  repository/   local storage
  ui/           Compose screens and state holders

preset/
  st/           import adapters for ST preset families
  runtime/      normalized formatting and generation policy
```

模块名不是硬性要求，边界才是。尤其要保证 `content/compiler/runtime` 可以脱离 Android 和 Compose 做 JVM 测试。

## 可供探索的垂直切片

下面是一种基于本次调查的切片设想，用于暴露兼容语义和运行时边界问题，不是 MVP 验收范围或实施顺序。实际探索可以只取其中当前有用的部分。

### 输入范围

- V2 JSON；
- PNG `chara` 元数据；
- 一个角色自带 `character_book`；
- 一个最小 context/system preset；
- 一个 scoped Regex，明确作用于 AI output 或 prompt 其中之一；
- 一个 OpenAI-compatible gateway。

### 运行范围

```text
导入角色
  -> 解析并保留 raw source
  -> 归一化 Content IR
  -> 输入一条用户消息
  -> 扫描并激活世界书
  -> 按 Execution Plan 生成 messages
  -> gateway 发 mock/真实请求
  -> 解析回复
  -> 执行明确作用域的 Regex
  -> 保存并显示会话
```

### 本次调查没有覆盖的方向

- 多 Provider 管理界面；
- V3 全部语义、CharX/BYAF 全部资产；
- 完整 Prompt Manager 编辑器；
- recursive world-info、group scoring、sticky/cooldown、宏语言全覆盖；
- 工具调用、图片、多模态、TTS、生图、长期记忆；
- 独立的远程“网关服务”。

这里的“轻量 LLM 网关”先指客户端内的 provider-neutral API 和一个 OpenAI-compatible adapter，不指另起一个服务器。等需要统一凭据、计费、路由或跨端复用时，再把同一接口外置为服务。

## 一种可能的实验顺序

1. 固定一个脱敏的真实 V2 JSON fixture，并补一个 PNG fixture。
2. 先写 `Content IR`、`Diagnostic`、`ExecutionPlan` 和 `ModelRequest` 的 Kotlin 类型，不写页面。
3. 用 fixture 写 golden test：角色字段、示例对话、世界书触发、Regex、最终 messages。
4. 用 fake gateway 跑通一轮请求/响应，再接 OpenAI-compatible HTTP。
5. 最后把同一条链路接到 Android 的导入、角色详情和聊天页面。

这条顺序的价值在于尽早暴露 ST 语义问题，但它不构成前置条件。可以根据实现中的实际发现调整或放弃。
