# Tavern Player 当前实现架构

> 状态：描述当前仓库已经建立的边界。
>
> 产品与兼容性决定见 [`current-discussion-status.md`](current-discussion-status.md)。

## 模块

```text
app ───────────────> model-gateway
 │
 └─> conversation-core ─> content-core
```

- `content-core` 是纯 Kotlin 内容层，负责 Character Card 检测、解码、规范化模型和兼容性诊断。
- `conversation-core` 是纯 Kotlin 对话运行层，负责 Macro、Regex、World Book、Prompt、token accounting 和一次生成事务。
- `model-gateway` 是协议边界，负责协议原生请求、最终 token 验证和流式响应。
- `app` 负责 Android 文件访问、私有仓库、Compose 界面、生命周期和流式持久化。

模块边界刻意把“不可信角色卡内容”与网络、文件系统和 Android UI 隔开。内容 runtime 不具备联网、脚本执行或 WebView 能力。

## Character 内容层

`CharacterCardImporter` 支持 V1、V2、V3 JSON 与带 `tEXt` metadata 的 PNG / APNG。它执行输入大小、PNG chunk / CRC、base64、UTF-8 和必选名称校验；同时存在 `ccv3` 与 `chara` 时采用 `ccv3`。

导入结果规范化为不可变 `CharacterAsset`，并保留：

- 官方角色字段、开场、备用开场、原始 examples 和 Prompt overrides；
- V3 nickname 派生的 prompt name；
- depth prompt、内嵌 Character Book 与 Character Regex；
- 未识别的官方字段、extensions 和资产 URI；
- 来源格式、规范版本、SHA-256、原始 JSON 与兼容性诊断。

远程 TavernHelper、第三方变量 Macro、脚本和富 HTML 会被识别并告警，但不会执行或抓取。

## Conversation Runtime

`GenerationPlanner` 是发送编排边界，当前由 `PromptCompiler` 实现。一次 generation transaction 依序处理：

1. USER_INPUT storage Regex 与 Macro；
2. prompt-side 历史投影；
3. World Book 激活、WORLD_INFO Regex 与 Macro；
4. examples、角色 / Preset overrides、depth prompt 和各 placement 注入；
5. context 预算与裁剪；
6. Provider 最终计数与必要的重新裁剪。

Macro 采用固定 ST 1.18.0 行为基线的新 Macro Engine 子集。local variables 的变更、稳定随机数和 World Book timed state 都属于求值事务：编排预览不会重复提交；请求成功准备后才提交 prompt runtime，完整回复的 AI_OUTPUT 投影在结束时提交。

Regex 来源顺序为 Preset 后 Character。canonical storage、Provider prompt 和安全 display 是不同投影；不支持的 JS 正则语义会跳过并报告，后台超时规则会在当前 Conversation 熔断。

World Book 状态以 `bookId:entryId` 保存，支持关键词逻辑、正则 key、概率、分组、递归、预算、sticky / cooldown / delay、placement、at-depth 与 outlet。默认 scan depth 为 2、总预算为有效输入预算的 25%、递归关闭。

## Token 与 Provider

`TokenAccounting` 先进行本地内容选择：

- 已映射的 OpenAI 模型使用 JTokkit 的 r50k / p50k / cl100k / o200k 编码与消息 framing；
- 未知或自定义 endpoint 使用带消息开销的保守 UTF-8 估算；
- context limit 依次取模型目录、已验证模型表和 32K fallback，再受 Preset 与模型 output limit 约束。

最终协议请求构造后，Anthropic 与 Gemini 使用官方 count-tokens endpoint 验证；OpenAI 已映射模型使用本地精确计数；其余请求保持 `ESTIMATED`。超限时最多按同一优先级重新裁剪三次。

`ModelGateway` 保留五个协议原生客户端：OpenAI Responses、OpenAI Chat Completions、Anthropic Messages、Gemini Interactions 和 Gemini GenerateContent。不同协议拥有各自的强类型请求与流式事件，不通过伪通用 OpenAI 请求模型抹平差异，也不依赖 Provider hosted state。

## Android 仓库与界面

`CharacterRepository` 在 app-private 目录中按角色保存版本化 manifest、原始 source 和静态头像缩略图。SHA-256 相同的导入返回已有资产；同名但内容不同的卡片形成新资产。

`ConversationRepository` 保存完整 Character Snapshot、Persona、turn / variants、Macro local variables、World Book timed state 和 generation metadata。写入使用临时文件、fsync 和原子替换；启动时清理未完成导入，并把遗留 `STREAMING` variant 恢复为 `INTERRUPTED`。

界面主流程是角色库 → 角色详情 / 兼容性报告 → 新建或恢复 Conversation → Chat。导入和浏览不要求模型配置，首次发送时才引导配置。开场和备用开场是 opening swipe；regenerate 为最后一个 assistant turn 增加候选，切换已缓存候选不会重新求值 Macro。

聊天正文不使用 WebView。渲染前删除 `script` / `style` 块、剥离其他 HTML 标签并解码实体，只把基础 Markdown 交给 Compose 展示。

## 当前明确不做

当前闭环不包含 CHARX、YAML、BYAF、独立 Preset 导入、Persona 管理、Character 编辑 / 导出、独立 World Book 管理、第三方脚本运行、富 HTML WebView，以及 edit、delete、continue、branch / checkpoint。真实社区卡可以对话，但依赖 TavernHelper 或配套 Preset 的状态面板和变量玩法不会被伪装为兼容。
