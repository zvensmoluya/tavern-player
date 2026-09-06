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

- `content-core` 是纯 Kotlin 内容层，负责 Character Card 与 ST OpenAI Preset 的检测、解码、规范化模型、完整来源保留、导出和兼容性诊断。
- `conversation-core` 是纯 Kotlin 对话运行层，负责 Macro、Regex、World Book、Prompt、token accounting 和一次生成事务。
- `model-gateway` 是协议边界，负责协议原生请求、最终 token 验证和流式响应。
- `app` 负责 Android 文件访问、私有仓库、Compose 界面、生命周期和流式持久化。

模块边界刻意把“不可信角色卡内容”与网络、文件系统和 Android UI 隔开。内容 runtime 不具备联网、脚本执行或 WebView 能力。

`content-core` 也定义 Native 内容适配的有限数据模型与确定性校验边界。`NativeAdaptationCompiler` 按来源整理 Program View：保留完整卡内 JS、HTML/正则、EJS 代码及关联变量规则，以本地引用保留 EJS 原文块，不预先识别特定玩法。模型返回 `NativeCompilationDraft`，包含状态、控件、协议和阶段等现有能力配置；本地恢复原文区间与草稿模板、计算来源哈希和严格数值边界，再交给 `NativeAdaptationValidator` 校验类型、白名单、固定 View 引用和资源上限。结构校验不证明模型的语义映射正确。`app` 的 `NativeCompilationService` 使用独立连接选择与固定编译指令发起一次模型请求，完整通过后复用既有安装入口。手工适配仍保留，不存在通用组件/动作 Runtime、自动 repair 或派生缓存服务。当前契约见 [`adaptation-runtime-v1.md`](adaptation-runtime-v1.md)，编译实验与限制见 [`native-compilation.md`](native-compilation.md)。

## Tavern Shelf 接收

`app` 中的 `ShelfTransferClient` 消费 Tavern Shelf Transfer Protocol v1。角色库首页通过系统二维码扫描器取得短期 URL；Android 17 在首次连接前请求本地网络权限。客户端读取 manifest 和原始 source，限制为现有 32 MiB 导入上限，并核对字节数与 SHA-256 后按 `kind` 路由到现有仓库：

- `character` 进入 `CharacterRepository`；
- `preset` 进入 `PresetRepository`，沿用现有 Preset 格式检测、规范化和完整来源保留；
- `worldbook` 当前只识别并提示尚未支持独立导入。

二维码 URL 只用于当前接收，不进入持久化状态。Shelf 使用局域网明文 HTTP，因此 Android 应用显式允许 cleartext；该能力只由 Shelf 接收入口触发。

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

完整当前对象保存在 `PresetAsset.source`，不可变恢复点由 `PresetAsset.initialState` 保存完整初始 source；恢复设置时保留当前资产名称。未知字段、扩展载荷、Provider / 模型选择、endpoint、自定义 headers/body 和凭据形字段都是惰性内容：可以落盘和重新导出，但不会自动改变 Player 连接、发起网络请求或获得脚本执行权。`PresetExporter` 把编辑后的正式字段合并回完整 current source；内容指纹基于合并后的规范化 JSON。

`BuiltInPresets.default` 是内置恢复基线：ST 默认 Prompt 骨架、中性 main prompt、无额外文风限制、context 不设人为上限、回复上限 1024。仓库可以持久化同 ID 的当前调整版本，但删除和恢复基线仍由代码内置版本约束。

`PresetGenerationSettings.disabledParameters` 保存请求控制的显式拔插状态。关闭不会清空最后编辑值；导出会移除对应 ST 顶层字段，重新导入时也由字段是否存在恢复开关。内置默认只开启回复上限，避免无意义的 sampler 默认值进入不同 Provider。

## Conversation Runtime

`GenerationPlanner` 是发送编排边界，当前由 `PromptCompiler` 实现。一次 generation transaction 依序处理：

1. USER_INPUT storage Regex 与 Macro；
2. prompt-side 历史投影；
3. World Book 激活、WORLD_INFO Regex 与 Macro；
4. examples、角色 / Preset overrides、模板、generation trigger、names behavior、system squash、depth prompt 和各 placement 注入；
5. Player 固定的 Conversation State system projection、assistant prefill 与 context 预算 / 裁剪；
6. Provider 最终计数与必要的重新裁剪。

Macro 采用固定 ST 1.18.0 行为基线的新 Macro Engine 子集。local variables 的变更、稳定随机数和 World Book timed state 都属于求值事务：编排预览不会重复提交；请求成功准备后才提交 prompt runtime，完整回复的 AI_OUTPUT 投影在结束时提交。

Regex 来源顺序为 Preset 后 Character。canonical storage、Provider prompt 和安全 display 是不同投影；Provider 原始 reasoning / signature 不被破坏，当前 Preset 只控制其展示投影。开头的 JavaScript 正向后向断言会改写为保留前缀的等价前向匹配，编号捕获组与 `{{match}}` 仍按原规则求值，以避开 Android 可变长度 lookbehind 的性能陷阱。不支持的 JS 正则语义会跳过并报告，生产执行使用共享有界 worker 和 250 ms 单规则熔断；测试可以注入确定性执行策略。

每次新建 Conversation、发送、重试或 regenerate 都先深拷贝当前 active Preset。该不可变快照贯穿编排、Provider 请求和流式 output projection；运行期间的全局切换不改变已开始事务，下一次生成立即使用新资产。Generation plan 与 MessageVariant 保存名称、内容指纹和参数诊断，不保存可供运行时反查的 Preset 引用；历史 display 使用当前 active Preset 重投影。

World Book 状态以 `bookId:entryId` 保存，支持关键词逻辑、正则 key、概率、分组、递归、预算、sticky / cooldown / delay、placement、at-depth 与 outlet。Conversation 另存书本级和条目级 activation override；缺失覆盖时继承 Character Snapshot 默认值，临时停用不冻结计时。强类型控制器先验证一批 `bookId` / `entryId` 再原子返回新 Runtime State，World Book trace 明确记录覆盖造成的启停。默认 scan depth 为 2、总预算为有效输入预算的 25%、递归关闭。

## Token 与 Provider

`TokenAccounting` 先进行本地内容选择：

- 已映射的 OpenAI 模型使用 JTokkit 的 r50k / p50k / cl100k / o200k 编码与消息 framing；
- 未知或自定义 endpoint 使用带消息开销的保守 UTF-8 估算；
- 当前模型的用户覆盖和模型目录是已验证能力：存在时会约束 Preset 声明的 context / output 预算；
- 已验证能力缺失时，context / output 直接采用 Preset 声明并标记为未验证，不再按模型名称猜测能力；Preset 也没有声明 context 时才采用 128K 产品默认预算。output 始终不能超过本轮有效 context。

最终协议请求构造后，Anthropic 与 Gemini 使用官方 count-tokens endpoint 验证；OpenAI 已映射模型使用本地精确计数；其余请求保持 `ESTIMATED`。超限时最多按同一优先级重新裁剪三次。

`ModelGateway` 保留五个协议原生客户端：OpenAI Responses、OpenAI Chat Completions、Anthropic Messages、Gemini Interactions 和 Gemini GenerateContent。不同协议拥有各自的强类型请求与流式事件，不通过伪通用 OpenAI 请求模型抹平差异，也不依赖 Provider hosted state。

app mapper 先拔除 Preset 中已关闭的 generation settings，再在 adapter 边界做能力映射：Responses 使用 output / temperature / top-p / reasoning / verbosity；Chat Completions 另含 penalties、seed 和 name；Anthropic 映射 sampler、manual / adaptive thinking 与可用 prefill；Gemini Interactions 只映射 output、seed 和 thinking level；GenerateContent 映射 sampler、seed、penalties 与 thinking config。Anthropic 的 `max_tokens` 是协议必填，Preset 关闭 output limit 时使用播放器已经预留的安全预算并记录诊断。OpenAI-compatible 协议可以原生表达的显式 reasoning / verbosity 会乐观转发，不再依赖模型名称白名单；“已应用”表示已经编码进请求，最终是否接受由 Provider 响应确认。Responses 遇到 Player 固定状态回写契约时，把当前 Conversation State 投影与该契约编码为顶层 `instructions`，其他卡片 / World Book / Preset system 消息保留时序并降为 `developer`，确保不可信内容不能改写 Player-owned 当前事实或放宽执行协议。协议本身无法表达或明确需要模型特定形态的参数仍会省略并进入 `ProviderRequestPreview` 和流式诊断。所有请求强制流式、单候选和无 hosted continuation state。

## Android 仓库与界面

`CharacterRepository` 在 app-private 目录中按角色保存版本化 manifest、原始 source、静态头像缩略图和通过 Native Decoder 验证的本地 PNG/JPEG/WebP 资产。SHA-256 相同的导入返回已有资产；同名但内容不同的卡片形成新资产。资产物化限制内嵌字节数、边长和像素数，远程 URI 不会联网解析。

经过校验的 `NativeAdaptation` 可以旁挂到同一 Character manifest；安装时必须匹配原始 `sourceSha256`，并且所有 State、Adapter、View、Form 与本地 asset 引用都通过确定性验证。它不会修改 `source.png` / `source.json`。新建 Conversation 捕获该适配及其初始状态快照；之后替换 Character 上的适配不会改写旧 Conversation。当前仓库只安装手工审计产物，不在导入或 Shelf 接收过程中调用模型生成适配。

`PresetRepository` 以一个原子 app-private manifest 保存当前 Preset、初始 source 树和全局 active ID；内置默认的初始版本由代码注入。它提供导入并激活、显式保存、恢复初始版本、从当前草稿另存为并激活、删除和无损导出；内容去重、大小写不敏感唯一命名以及删除 active 后回退都在同一持久状态边界完成。原始文件的空白与键格式不单独保存，但解析后的全部 JSON 数据都会保留。

`PersonaRepository` 原子保存一份全局默认 Persona。角色库中的身份编辑器允许修改 name、description 与可选头像；创建 Conversation 时捕获当前值，之后修改默认身份不会改写已有 Conversation。当前没有身份列表、选择器或 Character 绑定。

`ConversationRepository` 保存完整 Character Snapshot、Persona（name、avatar 与可选 description）、turn / variants、Macro local variables、World Book timed state、World Book activation overrides、`ConversationStateSnapshot` 和 generation metadata，但不保存 Conversation 级 Preset 绑定。Persona description 只作为 `{{persona}}` 与 `personaDescription` marker 的动态内容源，位置和 role 继续由 Preset 决定。写入使用临时文件、fsync 和原子替换；启动时清理未完成导入，并把遗留 `STREAMING` variant 恢复为 `INTERRUPTED`。Conversation record schema v3 不兼容旧的 `adaptationState` 存储名。

Conversation State 属于同一个 `ConversationRuntimeState`，因此跟随既有消息前后检查点、regenerate、swipe、截断与进程恢复语义。`UpdateVariableSetV1Adapter` 与 `UpdateVariableJsonPatchV1Adapter` 只把唯一、完整 assistant update envelope 中白名单路径的 scalar 更新解码为 `ConversationStatePatch`，`NativeAdaptationRuntime` 在消息候选完成时一次应用；完整空块是已确认的 no-op，缺块不是状态事实，缺失内层或外层闭合标签的畸形块不会被宽松修复。原始 `sourceText` 保留机器块用于摄入与诊断；声明对应 Adapter 后，Player 在聊天 storage/display 的 Macro / Regex 投影前剥离已识别的完整机器块，并在流式阶段暂时隐藏未闭合块。畸形块不直接写入状态；单个畸形块只有在独立确认成功后才从 canonical 展示移除，歧义的多个块保持可见。Adapter 不负责 UI 或 Prompt。`PromptCompiler` 把适配声明的 label/type/description 与当前值按稳定顺序编码为固定 JSON system projection；该投影不经过卡片模板、Macro 或 Regex，也不允许 Adaptation 指定 role、位置或格式。声明 Adapter 时，Player 另生成固定 dialect 与白名单回写契约，并要求每轮以完整块确认更新或 no-op。

生产生成器在主回复缺少、未闭合或给出畸形块时执行一次固定的独立状态确认：输入只包括 Player 状态、已校验白名单和 JSON 转义的当轮对话证据，输出只接受完整 envelope。主回复已有合法块时不增加调用；确认失败时不猜测、不覆盖原回复，并留下状态未确认诊断。成功确认的块独立保存在 `stateConfirmation`，不可变 `sourceText` 仍是主回复原文；状态摄入优先读取确认块，canonical storage/display 可在确认成功后移除原回复中的单个畸形机器片段，两次 Provider usage 合并记录。该流程不创建 Adaptation，也不是自动适配编译器。

Compose 只提供 Player 固定的 Status、Scene、Collection 和 Form。Form 根据不可变 `sourceText` marker 附着到消息，只产生待用户确认的聊天 Draft；当 Form 接管 marker 时，display projection 会跳过以同一字面 marker 为入口的 Character HTML replacement，再删除 marker。Scene 只按 scalar State 的精确值读取已安装本地静态图片。不存在 `TEXT` node、通用 action、binding、condition、trigger 或组件树。

每个消息候选同时保存投影前 `sourceText`、canonical storage content，以及消息处理前、内容投影前和处理后的 Conversation Runtime State。聊天气泡可以直接编辑已完成的用户或 assistant 消息。“保存文字”只重新产生该候选的 canonical content，保留 reasoning、生成 metadata、其他候选、后续 Turn 和当前运行状态；后续请求读取修正后的历史，但不会假装已经重新执行过去的 Macro 或 World Book 状态变化。“从这里重新生成 / 继续”才把该 Turn 收敛为一个手动候选、截断其后全部 Turn，并把当前运行状态恢复为编辑后结果。旧后缀不会作为隐藏分支保留。状态检查点不设历史窗口，未来 branch 是否以及如何建立仍是独立产品决定。

模型连接按模型 ID 保存可选的 context / output token 上限覆盖。覆盖值逐字段优先于 Provider 模型目录，只进入运行时能力解析，不反向修改 Preset；切换模型会切换到对应模型自己的覆盖记录。

界面主流程是角色库 → 角色详情 / 兼容性报告 → 新建或恢复 Conversation → Chat。角色库可进入单一默认身份编辑器；角色库和 Chat 都可以进入 Preset 中心，Chat 另有运行中禁用的快捷切换 bottom sheet。Preset 中心通过 Storage Access Framework 导入 / 导出；选择列表项会先激活再编辑。详情默认只展示实际 order 中的普通 Prompt 与 Regex 快速开关，Prompt 开关只改 `enabled`，不会改变成员关系或相对顺序；单项内容、兼容字段、结构设置和请求参数使用独立全屏次级页面。全部修改显式保存，带未保存修改返回时提供保存、放弃和继续编辑；不新增或删除 Prompt 定义，也不重写 Regex。导入和浏览不要求模型配置，首次发送时才引导配置。恢复对话、产生新消息和生成结束时，Chat 会定位到最新消息；只有 reasoning 尚无正文的流会显示轻量“正在思考…”状态。聊天气泡直接提供“保存文字”和“从这里重新生成 / 继续”；只有后者会在存在后续消息或其他 swipe 时确认将被丢弃的事实。开场和备用开场是 opening swipe；regenerate 为最后一个 assistant turn 增加候选，切换已缓存候选不会重新求值 Macro。

聊天正文不使用 WebView。渲染前删除 `script` / `style` 块、剥离其他 HTML 标签并解码实体，只把基础 Markdown 交给 Compose 展示。

## 当前明确不做

当前闭环不包含 CHARX、YAML、BYAF、Text Completion Preset、空白 Preset 创建、多 Persona 管理 / 选择 / 绑定、Character 编辑 / 导出、独立 World Book / Regex 管理、第三方脚本运行、富 HTML WebView，以及 Conversation delete、continue 和可保留旧后缀的 branch / checkpoint。真实社区卡和 OpenAI Preset 可以进入对话，但依赖 TavernHelper 的状态面板、变量玩法和脚本不会被伪装为兼容。
