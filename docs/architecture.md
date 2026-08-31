# Tavern Player 当前实现架构

> 状态：只描述仓库中已经存在的代码边界，不为尚未实现的内容 runtime 预设类、阶段或存储结构。
>
> 产品与兼容性决定见 [`current-discussion-status.md`](current-discussion-status.md)。

## 模块

```text
app
  -> model-gateway
```

- `app` 是 Android 应用，当前实现模型连接的配置、保存、凭据管理、模型目录、连接探测和 Compose 界面。
- `model-gateway` 是 Android 无关的 Kotlin/JVM 模块，负责协议请求、流式响应和共享网络传输。
- Character、Preset、World Book、Conversation 与生成 runtime 尚未形成实现契约。

## Model Gateway

`ModelGateway` 当前提供五个协议原生客户端：

```text
OpenAI Responses
OpenAI Chat Completions
Anthropic Messages
Gemini Interactions
Gemini GenerateContent
```

它们分别拥有自己的强类型 request、流式 event、累积 result、usage 和 finish 语义，不通过一套伪通用的 OpenAI-compatible 请求模型抹平协议差异。

共享传输层负责：

- HTTPS 与目标地址校验；
- 凭据解析和鉴权注入；
- 超时、取消、响应大小限制和同源重定向；
- SSE framing 与错误归一化。

流式调用是当前唯一的生成原语。未知协议事件保留为带 raw JSON 的 `Unknown`；畸形响应、安全失败和传输失败通过 typed exception 报告。

模型目录是 best-effort 能力，不是连接成立或模型可生成的前提。具体协议是否支持某项模型参数或响应字段，由对应 adapter 处理。

## Android App

连接页使用统一的“协议、API 地址、API Key、模型”流程，但不会把不同协议伪装成同一请求格式。

- 协议决定 endpoint 解析和认证方式；厂商模板只提供默认值。
- API Key 由 Android Keystore 保护，连接配置只保存凭据引用和掩码。
- 连接状态与模型目录缓存保存在 DataStore；目录失败不会清空已有选择。
- 连接探测通过最小生成请求验证实际能力。探测结果中的统一 token 摘要只是 UI 投影，不属于 Model Gateway 的公共响应模型。

## 尚未建立的边界

内容导入、领域模型、Conversation 持久化、Prompt 编排、World Book、Macro、Regex、Context 预算和回复处理尚未实现。它们进入设计时必须服从当前产品边界；具体类型、执行阶段、存储划分和兼容性验证方式均未决定。
