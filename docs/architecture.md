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

- `content-core` 是纯 Kotlin 内容层，负责 Character Card 与 ST OpenAI Preset 的检测、解码、规范化模型、清理、导出和兼容性诊断。
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

## Preset 内容层

`PresetImporter` 只接受不超过 32 MiB 的可识别 ST OpenAI / Chat Completion JSON。它解析 Prompt 定义池、`character_id=100001`（兼容 `100000`）的全局 order、未使用定义、legacy prompt、generation / control settings 和 Preset Regex。缺失定义引用按 ST 行为跳过并告警，不让局部坏数据阻断整份资产。

未知字段和扩展载荷保存在 `PresetAsset.sanitizedSource`，但不会执行。递归清理器在任何落盘或导出前移除 endpoint、代理凭据、自定义 headers/body、账户标识和其他连接数据。`PresetExporter` 把编辑后的正式字段合并回清理后的 source，保留未知扩展并再次执行安全过滤；内容指纹基于规范化安全 JSON。

`BuiltInPresets.default` 是不可变内置资产：ST 默认 Prompt 骨架、中性 main prompt、无额外文风限制、context 不设人为上限、回复上限 1024。

## Conversation Runtime

`GenerationPlanner` 是发送编排边界，当前由 `PromptCompiler` 实现。一次 generation transaction 依序处理：

1. USER_INPUT storage Regex 与 Macro；
2. prompt-side 历史投影；
3. World Book 激活、WORLD_INFO Regex 与 Macro；
4. examples、角色 / Preset overrides、模板、generation trigger、names behavior、system squash、depth prompt 和各 placement 注入；
5. assistant prefill 与 context 预算 / 裁剪；
6. Provider 最终计数与必要的重新裁剪。

Macro 采用固定 ST 1.18.0 行为基线的新 Macro Engine 子集。local variables 的变更、稳定随机数和 World Book timed state 都属于求值事务：编排预览不会重复提交；请求成功准备后才提交 prompt runtime，完整回复的 AI_OUTPUT 投影在结束时提交。

Regex 来源顺序为 Preset 后 Character。canonical storage、Provider prompt 和安全 display 是不同投影；Provider 原始 reasoning / signature 不被破坏，当前 Preset 只控制其展示投影。不支持的 JS 正则语义会跳过并报告，生产执行使用共享有界 worker 和 250 ms 单规则熔断；测试可以注入确定性执行策略。

每次新建 Conversation、发送、重试或 regenerate 都先深拷贝当前 active Preset。该不可变快照贯穿编排、Provider 请求和流式 output projection；运行期间的全局切换不改变已开始事务，下一次生成立即使用新资产。Generation plan 与 MessageVariant 保存名称、内容指纹和参数诊断，不保存可供运行时反查的 Preset 引用；历史 display 使用当前 active Preset 重投影。

World Book 状态以 `bookId:entryId` 保存，支持关键词逻辑、正则 key、概率、分组、递归、预算、sticky / cooldown / delay、placement、at-depth 与 outlet。默认 scan depth 为 2、总预算为有效输入预算的 25%、递归关闭。

## Token 与 Provider

`TokenAccounting` 先进行本地内容选择：

- 已映射的 OpenAI 模型使用 JTokkit 的 r50k / p50k / cl100k / o200k 编码与消息 framing；
- 未知或自定义 endpoint 使用带消息开销的保守 UTF-8 估算；
- context limit 依次取模型目录、已验证模型表和 32K fallback，再受 Preset 与模型 output limit 约束。

最终协议请求构造后，Anthropic 与 Gemini 使用官方 count-tokens endpoint 验证；OpenAI 已映射模型使用本地精确计数；其余请求保持 `ESTIMATED`。超限时最多按同一优先级重新裁剪三次。

`ModelGateway` 保留五个协议原生客户端：OpenAI Responses、OpenAI Chat Completions、Anthropic Messages、Gemini Interactions 和 Gemini GenerateContent。不同协议拥有各自的强类型请求与流式事件，不通过伪通用 OpenAI 请求模型抹平差异，也不依赖 Provider hosted state。

app mapper 在 adapter 边界应用 Preset generation settings：Responses 使用 output / temperature / top-p / reasoning / verbosity；Chat Completions 另含 penalties、seed 和 name；Anthropic 映射 sampler、manual / adaptive thinking 与可用 prefill；Gemini Interactions 只映射 output、seed 和 thinking level；GenerateContent 映射 sampler、seed、penalties 与 thinking config。模型能力未知时保守省略，省略项进入 `ProviderRequestPreview` 和流式诊断。所有请求强制流式、单候选和无 hosted continuation state。

## Android 仓库与界面

`CharacterRepository` 在 app-private 目录中按角色保存版本化 manifest、原始 source 和静态头像缩略图。SHA-256 相同的导入返回已有资产；同名但内容不同的卡片形成新资产。

`PresetRepository` 以一个原子 app-private manifest 保存用户 Preset、清理后的 source 树和全局 active ID，内置默认由代码注入。它提供导入、激活、显式保存、重命名、复制、删除和安全导出；内容去重、大小写不敏感唯一命名以及删除 active 后回退都在同一持久状态边界完成。原始未清理 Preset 不落盘。

`ConversationRepository` 保存完整 Character Snapshot、Persona、turn / variants、Macro local variables、World Book timed state 和 generation metadata，但不保存 Conversation 级 Preset 绑定。写入使用临时文件、fsync 和原子替换；启动时清理未完成导入，并把遗留 `STREAMING` variant 恢复为 `INTERRUPTED`。

界面主流程是角色库 → 角色详情 / 兼容性报告 → 新建或恢复 Conversation → Chat。角色库和 Chat 都可以进入 Preset 中心；Chat 另有运行中禁用的快捷切换 bottom sheet。Preset 中心通过 Storage Access Framework 导入 / 导出，编辑器显式保存或取消，只允许编辑已有 Prompt / order 和启停已有 Regex。导入和浏览不要求模型配置，首次发送时才引导配置。开场和备用开场是 opening swipe；regenerate 为最后一个 assistant turn 增加候选，切换已缓存候选不会重新求值 Macro。

聊天正文不使用 WebView。渲染前删除 `script` / `style` 块、剥离其他 HTML 标签并解码实体，只把基础 Markdown 交给 Compose 展示。

## 当前明确不做

当前闭环不包含 CHARX、YAML、BYAF、Text Completion Preset、空白 Preset 创建、Persona 管理、Character 编辑 / 导出、独立 World Book / Regex 管理、第三方脚本运行、富 HTML WebView，以及 Conversation edit、delete、continue、branch / checkpoint。真实社区卡和 OpenAI Preset 可以进入对话，但依赖 TavernHelper 的状态面板、变量玩法和脚本不会被伪装为兼容。
