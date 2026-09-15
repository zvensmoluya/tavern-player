# Tavern Player 当前实现架构

> 状态：描述当前仓库已经建立的边界，更新于 2026-09-15。默认网页消息区、宿主桥和原程序装载已接入，范围见[网页运行契约](web-runtime.md)。文中 Native 编译描述独立可选适配实现。
>
> 产品与兼容性决定见 [`product-direction.md`](product-direction.md)。

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

模块边界刻意把“不可信角色卡内容”与网络、文件系统和 Android UI 隔开。纯 Kotlin 内容层不自行联网或运行浏览器；作者页面/后台脚本由 app 层隔离 WebView 执行，MVU/EJS 和 Native 适配由相应 QuickJS 宿主执行。

`content-core` 定义 Native 内容适配、只读路径绑定和高层 Surface 契约。`native-compiler-15` 保留完整相关 JS/HTML/EJS 材料，让模型选择公共程序并生成有来源关联的 JS 模块、Surface 投影与 handler。模型开放程序表达，Player 控制界面表达，不提供通用组件树。原 MVU/EJS、简单绑定和完整草稿模板继续复用。

当前编译分两次模型请求：先以完整 Program View 选择 MVU 或 Player 状态来源，再用同一份源码和所选分支的契约编译。MVU 分支不提供独立 Player 状态、旧写入器及其类型；Player 分支不提供 MVU 配置与写入接口。接收端校验分支、Schema 引用及绑定来源，跨分支字段即使为空也拒绝。运行时 `NativeSurfaceField` 由实际序列化描述单独列出，与固定 `NativeFormField` 区分。选择阶段使用 LOW、主编译使用 HIGH reasoning；两阶段用量分别记录，额外读取完整源码会增加输入成本。分支语义仍由模型判断，本地只验证引用有效性，不增加 JS 写法识别器。

`app` 在编译与安装时校验真实 QuickJS 模块加载及导出，显示时执行只读投影。用户操作通过显式声明的状态读取、MVU 直接替换、程序私有状态、草稿及辅助生成接口调用宿主。操作检查点独立保存于消息候选，保留原消息结束快照；宿主写入先保存再发布，切换候选恢复所属 head，中断不自动重发请求。结构和加载检查均不证明整卡等价。宿主范围、取消语义和本地验证见 [JS 动态原生 Surface](archive/native-script-surfaces-20260908.md)。

## 网页运行路径

`BrowserProgramReader` 保留原文与来源，`BrowserProgramPreparer` 仅解析并识别登记公共模块。`BrowserSession` 连接 Android WebMessageListener 与可信外壳，`BrowserConversation` 负责身份/版本校验、同步视图快照与纯状态提案；`ConversationSession` 统一持有可写会话、已提交基线、版本与生成任务，串行提交原生/网页操作；`ChatViewModel` 只做生命周期和 UI 适配。网页消息修改不调用用户编辑的 Macro 重处理或截断入口。

网页外壳先创建一个持久作者会话协调 iframe，再挂载消息和后台脚本。`session.mjs` 统一保存共享对象、事件监听器、待提交视图和命令队列；各 `host.mjs` 保留页面身份与原生传输入口。作者内容与协调器同源，通过实际对象引用共享函数和回调；协调器仍与可信外壳不同源且不直接访问原生桥。原生事实事件只向协调器分发一次，销毁页面按所属身份清理监听器、等待及排队操作。

普通流式文字通过 DOM 修补续写；静态富文本在持久 iframe 内清洗并更新，保留未变节点和用户输入状态。展示缓冲未完整 HTML/CSS，作者交互页面仍在最终事务保存后启动，准备完成再替换可见占位。源码或候选变化按作者页面生命周期重建，静态修补不会用于可执行作者页面。

可信外壳和作者兼容父页面使用不同来源；消息和后台脚本各自持有候选/脚本身份。初次快照、后续增量、50 ms 合并更新、先保存后确认、失败停止实例及销毁取消均由生产路径实现。`executionMode` 缺省为旧 Native，新建主入口显式 BROWSER，角色详情同时提供原生入口，并按 `BrowserProgram.hasAuthorRuntime` 说明原生模式不会运行作者脚本与世界书模板，保存程序指纹、候选变量/head 与资源版本。

网页图片命中已有持久资源索引，缺失图片按需补入。`WebResourceRepository` 独立保存 HTTPS 静态资源，URL 在会话内绑定首个内容哈希，重定向模块/CSS 保留最终解析基址。具体协议、容量、接口、恢复和验证命令集中维护在[网页运行契约](web-runtime.md)。

## Tavern Shelf 接收

`app` 中的 `ShelfTransferClient` 消费 Tavern Shelf Transfer Protocol v1。角色库首页通过系统二维码扫描器取得短期 URL；Android 17 在首次连接前请求本地网络权限。客户端读取 manifest 和原始 source，限制为现有 32 MiB 导入上限，并核对字节数与 SHA-256 后按 `kind` 路由到现有仓库：

- `character` 进入 `CharacterRepository`；
- `preset` 进入 `PresetRepository`，沿用现有 Preset 格式检测、规范化和完整来源保留；
- `worldbook` 进入 `WorldBookRepository`，校验独立 ST World Info / Character Book JSON 并默认停用。

二维码 URL 只用于当前接收，不进入持久化状态。Shelf 使用局域网明文 HTTP，因此 Android 应用显式允许 cleartext；该能力只由 Shelf 接收入口触发。

## Character 内容层

`CharacterCardImporter` 支持 V1、V2、V3 JSON 与带 `tEXt` metadata 的 PNG / APNG。它执行输入大小、PNG chunk / CRC、base64、UTF-8 和必选名称校验；同时存在 `ccv3` 与 `chara` 时采用 `ccv3`。

导入结果规范化为不可变 `CharacterAsset`，并保留：

- 官方角色字段、开场、备用开场、原始 examples 和 Prompt overrides；
- V3 nickname 派生的 prompt name；
- depth prompt、内嵌 Character Book 与 Character Regex；
- 未识别的官方字段、extensions 和资产 URI；
- 来源格式、规范版本、SHA-256、原始 JSON 与兼容性诊断。

导入只识别与保存来源，不执行程序或抓取远程依赖。新建网页会话通过独立 BrowserProgramReader/BrowserProgramPreparer 装载声明范围内的原程序；未知能力继续报告限制。

## Preset 内容层

`PresetImporter` 只接受不超过 32 MiB 的可识别 ST OpenAI / Chat Completion JSON。它解析 Prompt 定义池、`character_id=100001`（兼容 `100000`）的全局 order、未使用定义、legacy prompt、generation / control settings 和 Preset Regex。缺失定义引用按 ST 行为跳过并告警，不让局部坏数据阻断整份资产。

完整当前对象保存在 `PresetAsset.source`，不可变恢复点由 `PresetAsset.initialState` 保存完整初始 source；恢复设置时保留当前资产名称。未知字段、Provider / 模型选择、endpoint、自定义 headers/body 和凭据形字段都是惰性内容：可以落盘和重新导出，但不会自动改变 Player 连接、发起网络请求或获得脚本执行权。`PresetExporter` 把编辑后的正式字段合并回完整 current source；内容指纹基于合并后的规范化 JSON。

`BuiltInPresets.default` 是内置恢复基线：ST 默认 Prompt 骨架、中性 main prompt、无额外文风限制、context 不设人为上限、回复上限 32768。为完整正文、思考及变量更新保留充足输出空间，不用过低预算作为常规聊天或实测配置。仓库可以持久化同 ID 的当前调整版本，但删除和恢复基线仍由代码内置版本约束；已有保存的预设参数保留，需调整回复上限或恢复默认基线才使用新值。

`PresetGenerationSettings.disabledParameters` 保存请求控制的显式拔插状态。关闭不会清空最后编辑值；导出会移除对应 ST 顶层字段，重新导入时也由字段是否存在恢复开关。内置默认只开启回复上限，避免无意义的 sampler 默认值进入不同 Provider。

Preset 编辑与仓库行为：

名称大小写不敏感且唯一；重复内容复用已有资产，同名异内容自动编号。删除 active 项会原子回退到内置默认。

导入、内置和“另存为”分别捕获不可变初始版本，当前编辑不覆盖该恢复基线。编辑采用显式保存；带修改返回时必须选择保存、放弃或继续编辑。“另存为”从当前草稿创建并立即启用新 Preset；导出 JSON 使用当前草稿。

从 Preset 列表进入一项时会先把它设为全局 active，再编辑同一项。主界面只把实际 `prompt_order` 中的普通 Prompt 与 Preset Regex 投影为快速开关；Prompt 开关只修改既有 order entry 的 `enabled`，不插入、移除或移动队列，未编排定义继续完整保留。

Prompt 文本与兼容字段、Preset 名称 / 控制格式 / 结构 marker、模型请求参数分别位于独立次级页面。请求参数可逐项开启或关闭；关闭时保留本地值，但从兼容 Provider 请求和 ST 导出中移除。切换 Provider 不反向修改 Preset，Provider 协议必填值由播放器的安全预算补齐并进入诊断。

## 全局 World Book

接入与验证记录见[全局世界书](archive/global-world-books-20260915.md)。

`WorldBookImporter` 接受至多 32 MiB 的 UTF-8 JSON：ST World Info 的 `entries` 对象，或独立 Character Book 的 `entries` 数组。ST 扁平字段先归一化，再复用角色书解析器；重复 ID、无效条目拒绝整份导入。保留原始 JSON 和字段诊断，不执行程序。字段基线参考固定 [ST world-info.js](https://github.com/SillyTavern/SillyTavern/blob/8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8/public/scripts/world-info.js)。

`WorldBookRepository` 异步读取独立原子 manifest，保存来源、不可变导入定义、启用状态和玩家覆盖，先落盘后发布；损坏文件不自动覆盖。书 ID 使用独立全局命名空间；同源重复导入复用已有项，不改变启用状态。另存为以当前导出内容建立新的恢复基线和 ID。

生成开始捕获 `GlobalWorldBookSnapshot`，只为本次编排合并 Character 书和全局书，不改角色资产或会话角色快照。Provider 重裁剪复用同一份捕获。全局条目继续使用现有 `WorldBookEngine` 和 required 注入保证；timed state 进入该会话的 runtime checkpoint。网页会话为全局原始 EJS 建立哈希引用，编辑模板后沿用来源变化的跳过／强制失败规则；原生模式不新增模板执行能力。全局库不暴露为作者脚本可写的共享状态。

来源组合沿用已有引擎：角色书先于全局书消耗共享总预算，全局书保持库内导入顺序，各书显式预算照常生效；分组和递归限于书内，注入按原位置及 order 合并。未新增跨书递归或 ST 可配置混排，也不按名称／正文去重。停用后不再参与；保存的计时检查点按既有引擎的代际规则处理。

导出保留来源结构及未知字段，合并正文和启用调整；`entries[*].extensions.tavern_player_mode` 保存 Player 的 `AUTO` / `FORCED` / `DISABLED`，重新导入恢复。ST 不识别该扩展，因此导出不承诺 ST 能执行“始终注入”。角色阅读、会话阅读和全局阅读复用同一阅读器，全局页明确显示跨会话修改范围。

## Conversation Runtime

性能路径更新（2026-09-15）：`DefaultTokenAccounting` 显式提供各条 prepared message 的可加和成本与请求开销。Context 裁剪先扣除已删除消息的成本，结束前再做完整请求校验；自定义非加和计数器继续逐次完整校验。Character Regex 与世界书关键词复用有界 Pattern 缓存，以实际展开/兼容改写后的源码和 flags 为键，每次匹配仍创建独立 Matcher，宏事务与规则诊断不缓存。实现及测量边界见[性能改进记录](performance-improvements-20260915.md)。

`GenerationPlanner` 是发送编排边界，当前由 `PromptCompiler` 实现。一次 generation transaction 依序处理：

1. USER_INPUT storage Regex 与 Macro；
2. prompt-side 历史投影；
3. World Book 逐轮扫描、分组、概率、正文 Macro、预算与递归；入选的普通正文执行 WORLD_INFO Regex，已选 EJS 使用其只读模板入口；
4. examples、角色 / Preset overrides、模板、generation trigger、names behavior、system squash、depth prompt 和各 placement 注入；
5. Player 固定的 Conversation State system projection、assistant prefill 与 context 预算 / 裁剪；
6. Provider 最终计数与必要的重新裁剪。

Macro 采用固定 ST 1.18.0（commit `8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8`）行为基线的新 Macro Engine 子集。local variables 的变更、稳定随机数和 World Book timed state 都属于求值事务：编排预览不会重复提交；请求成功准备后才提交 prompt runtime，完整回复的 AI_OUTPUT 投影在结束时提交。

Regex 来源顺序为 Preset 后 Character。canonical storage、Provider prompt 和安全 display 是不同投影；Provider 原始 reasoning / signature 不被破坏，当前 Preset 只控制其展示投影。开头的 JavaScript 正向后向断言会改写为保留前缀的等价前向匹配，编号捕获组与 `{{match}}` 仍按原规则求值，以避开 Android 可变长度 lookbehind 的性能陷阱。不支持的 JS 正则语义会跳过并报告，生产执行使用共享有界 worker 和 250 ms 单规则熔断；测试可以注入确定性执行策略。

每次新建 Conversation、发送、重试或 regenerate 都先深拷贝当前 active Preset。该不可变快照贯穿编排、Provider 请求和流式 output projection；运行期间的全局切换不改变已开始事务，下一次生成立即使用新资产。Generation plan 与 MessageVariant 保存名称、内容指纹和参数诊断，不保存可供运行时反查的 Preset 引用；历史 display 使用当前 active Preset 重投影。

World Book 的 sticky / cooldown 状态以 `bookId:entryId` 保存；delay 根据当前选中分支的消息数判断。自动模式保留角色字段扫描开关、分组、概率、递归、WORLD_INFO Regex 与预算行为，默认 scan depth 为 2、世界书预算为有效输入预算的 25%、递归关闭。历史修正与语义范围见[世界书阅读与编排修正](archive/world-book-reader-and-semantics-20260908.md)。

`ConversationRecord.worldBookState.playerOverrides` 按书 id / 条目 id 保存玩家的 `mode` 和 `content`，与作者程序使用的 `activation`、`forcedBooks`、`editedContent` 分开。`ConversationWorldBookController` 只修改玩家覆盖，不改 `CharacterSnapshot` 或角色资产；恢复使用方式与恢复正文分别处理。读取展示和编排时合成覆盖，玩家明确选择的模式优先于作者启停，正文在 Native 原文选择后覆盖，避免原文范围失效或选文覆盖玩家文字。作者删除条目时回收对应玩家覆盖。旧作者书级 force 接口仍只绕过关键词和概率，不暴露为玩家“始终注入”。

普通生成、重新生成及带预设的网页辅助生成显式传入会话世界书状态。玩家强制项在 `WorldBookEngine` 绕过自动条件及世界书预算，仍做正文 Macro / Regex / EJS 处理；空输出或失效模板不能满足强制要求。强制文字以本轮临时标记沿原位置编排，缺失的 marker / outlet 补到历史前；文字在最终编排时恢复为字面量。`PreparedMessage.required` 贯穿名字处理、system squash、上下文预算及 Provider 重裁剪，无法容纳时返回明确错误。`GenerationPlan.worldBookInjections` 对逐项实际处理文字和最终消息做核对，区分已注入、未注入与未确认，不把 `activatedWorldBookEntries` 作为最终注入证据。结果随消息候选持久化。

玩家覆盖在会话级保存，不随候选回退；sticky / cooldown 继续使用剧情检查点。世界书保存期间占用 `worldBookSaving`，先落盘再发布，正文编辑器仅在成功后退出。

## Token 与 Provider

导入期 Native 编译独立于普通聊天预算：不使用固定 context／16K output 兜底，也不以保守 token 估算阻止完整源码请求。连接的模型输出上限存在时直接使用；未知时省略协议可选的输出限制。Anthropic 的 `max_tokens` 必填，缺少模型声明时使用 65,536 回退值。真实上下文容量由 Provider 判定；本地来源、格式及运行资源校验仍然执行。

`TokenAccounting` 先进行本地内容选择：

- 已映射的 OpenAI 模型使用 JTokkit 的 r50k / p50k / cl100k / o200k 编码与消息 framing；
- 未知或自定义 endpoint 使用带消息开销的保守 UTF-8 估算；
- 当前模型的用户覆盖和模型目录提供明确声明的上限，并不代表已经过 Provider 实测验证：存在时会约束 Preset 声明的 context / output 预算；
- 模型连接与目录未声明上限时，context / output 直接采用 Preset 声明并标记为未验证，不再按模型名称猜测能力；Preset 也没有声明 context 时不设置本地上下文上限，由 Provider 判定请求是否超限。output 受明确的本轮 context 约束；未知 context 不产生额外约束。此规则同时用于普通聊天和原生动作辅助生成。
- context 未声明时，`maxContext` / `maxPrompt` 宏返回空值；世界书不套用依赖 context 的百分比预算，但仍遵守世界书显式 token budget。Token 计数继续记录，未知容量不触发本地裁剪。

最终协议请求构造后，Anthropic 与 Gemini 使用官方 count-tokens endpoint 验证；OpenAI 已映射模型使用本地精确计数；其余请求保持 `ESTIMATED`。超过已声明的上下文预算时最多按同一优先级重新裁剪三次；容量未知时只记录计数并提交原请求。

`ModelGateway` 保留五个协议原生客户端：OpenAI Responses、OpenAI Chat Completions、Anthropic Messages、Gemini Interactions 和 Gemini GenerateContent。不同协议拥有各自的强类型请求与流式事件，不通过伪通用 OpenAI 请求模型抹平差异，也不依赖 Provider hosted state。

app mapper 先拔除 Preset 中已关闭的 generation settings，再在 adapter 边界做能力映射：Responses 使用 output / temperature / top-p / reasoning / verbosity；Chat Completions 另含 penalties、seed 和 name；Anthropic 映射 sampler、manual / adaptive thinking 与可用 prefill；Gemini Interactions 只映射 output、seed 和 thinking level；GenerateContent 映射 sampler、seed、penalties 与 thinking config。Anthropic 的 `max_tokens` 是协议必填，Preset 关闭 output limit 时使用播放器已经预留的安全预算并记录诊断。OpenAI-compatible 协议可以原生表达的显式 reasoning / verbosity 会乐观转发，不再依赖模型名称白名单；“已应用”表示已经编码进请求，最终是否接受由 Provider 响应确认。Responses 遇到 Player 固定状态回写契约时，把当前 Conversation State 投影与该契约编码为顶层 `instructions`，其他卡片 / World Book / Preset system 消息保留时序并降为 `developer`，确保不可信内容不能改写 Player-owned 当前事实或放宽执行协议。协议本身无法表达或明确需要模型特定形态的参数仍会省略并进入 `ProviderRequestPreview` 和流式诊断。所有请求强制流式、单候选和无 hosted continuation state。

## Android 仓库与界面

角色详情提供独立“角色资源”入口。`CharacterImageDiscovery` 只提取原卡中的静态图片引用；`CharacterImageRepository` 在用户点击准备后，通过独立 HTTP 客户端下载并校验，保存到 `filesDir/tavern/characters/{id}/resources/images`，按内容哈希在角色内去重。页面仅从本地文件预览，下载完成项跨进程复用，不进入相册，也不自动淘汰。普通导入仍不抓取远程资源；该入口不调用模型、不修改原卡或 Native 编译协议。支持范围和限制见[角色图片资源](archive/character-image-resources-20260909.md)。

角色详情和对话共用 `WorldBookReaderScreen`，`WorldBookReaderComponents` 提供本模块的纸面阅读样式、目录摘录、翻页栏和使用方式面板，无搜索或书级控制。`display_index` 存在时沿用作者展示顺序，否则按原数组顺序。多书只标明内容来源；目录对模板显示占位提示，正文以可选择的原始文字分块呈现。详情的上一项、下一项沿目录顺序导航；使用方式常驻底部，选项和恢复操作按需展开，正文编辑从右上角进入独立页面。角色详情只读；对话详情另显示最近生成的注入结果。编辑草稿、选中内容及列表位置可恢复。对话内使用全屏窗口保留下层 WebView，阅读不会销毁作者运行环境；生成中可读和翻页，调整锁定。

`QuickJsMvuRuntime` 通过 `MvuConversationRuntime` 接入声明 MVU 的适配卡：会话创建时初始化开场候选，完整回复与重启式编辑时更新变量，候选切换恢复持久检查点。完整上游状态保存为 `ConversationRuntimeState.mvuState`，随已有候选一起序列化，记录 bundle/卡程序哈希以拒绝交叉恢复。MVU 的事务结果同时包含状态与 `processedText`：开场初始化、完整回复和重启式编辑消费两者，处理后正文经过 Macro / STORAGE / DISPLAY 投影，模型或编辑原文仍保存为 `sourceText`。生成结束后的重投影复用同一次 MVU 结果，不重复执行变量命令；取消、截断与缺少完成事件不提交 MVU 结果。仅保存文字的编辑不执行 MVU。固定 MVU/Zod bundle 与许可证由本地构建带入应用 APK；原卡程序来自已安装的适配快照，不进行运行期下载。完整变量树进入下一轮 Prompt 及变量读取宏，Native Status、Scene 和 Collection 通过 `ConversationStateReader` 直接读取该快照，绑定不生成另一份业务状态。EJS 已作为独立的只读提示词执行入口接入，见下文。详见 [聊天接入记录](archive/mvu-chat-integration-20260907.md)。

`CharacterRepository` 在 app-private 目录中按角色保存版本化 manifest、原始 source、静态头像缩略图和通过 Native Decoder 验证的本地 PNG/JPEG/WebP 资产。SHA-256 相同的导入返回已有资产；同名但内容不同的卡片形成新资产。资产物化限制内嵌字节数、边长和像素数，远程 URI 不会联网解析。

经过校验的 `NativeAdaptation` 可以旁挂到同一 Character manifest；安装时必须匹配原始 `sourceSha256`，并且所有 State、Adapter、View、Form 与本地 asset 引用都通过确定性验证。它不会修改 `source.png` / `source.json`。新建 Native Conversation 捕获该适配及其初始状态快照；之后替换 Character 上的适配不会改写旧 Conversation。角色详情可显式请求模型准备适配或导入手工产物；普通导入和 Shelf 接收不会自动调用模型。

`PresetRepository` 以一个原子 app-private manifest 保存当前 Preset、初始 source 树和全局 active ID；内置默认的初始版本由代码注入。它提供导入并激活、显式保存、恢复初始版本、从当前草稿另存为并激活、删除和无损导出；内容去重、大小写不敏感唯一命名以及删除 active 后回退都在同一持久状态边界完成。原始文件的空白与键格式不单独保存，但解析后的全部 JSON 数据都会保留。

`PersonaRepository` 原子保存一份全局默认 Persona。角色库中的身份编辑器允许修改 name、description 与可选头像；创建 Conversation 时捕获当前值，之后修改默认身份不会改写已有 Conversation。当前没有身份列表、选择器或 Character 绑定。

`ConversationRepository` 通过 `RoomConversationStore` 保存 Character Snapshot、Persona、turn/variants、运行检查点、世界书意图和生成诊断，不保存 Conversation 级 Preset 绑定。会话头、摘要、草稿、消息、候选、生成计划分片、原生操作、原始流日志与不可变内容引用分表；相同正文和检查点按内容哈希复用。角色列表只读摘要，打开会话才重建该会话对象，读取中共享相同检查点和正文实例。Persona description 的编排语义保持不变。

数据库位于应用私有目录 `tavern/conversation.db`，Room schema 1 导出在 `app/schemas`。旧 `tavern/conversations/*.json` 首次逐份导入、完整字段回读比较，全部成功后激活；损坏文件阻止激活，原文件始终保留。JSON 导入格式仍为 Conversation record schema v3，不支持更早的 `adaptationState` 名称。正常写入不再生成会话 JSON 文件。

`ConversationSession` 是业务写入所有者：持有 `commitRevision` 和独立 `draftSeq`，所有持久业务提交通过统一提交锁，存储拒绝过期版本。普通打字仅更新 draft 表，不改最近活动时间。流式事件先按序进入独立日志，预览最多约每 50 ms 更新，进度每 500 ms 或达到批次门槛写入。Finished 只记录结束原因，继续接收尾随 usage；最终投影、MVU 结果、正文、用量、运行状态与日志删除在同一事务提交，成功后发布 COMPLETE。保存失败保留待提交结果并阻止后续业务写入，用户可重试保存。切会话与关闭先结束任务并等待落盘；进程意外退出后按原始事件重建 INTERRUPTED 正文，不重新调用模型、MVU 或作者动作。详细边界见[会话存储与事务实现](conversation-storage.md)。

应用启动只立即创建角色库所需的轻量对象；角色 manifest 在后台 I/O 初始化并同时建立路径元数据索引，头像、来源文件和本地资产查询不再重复反序列化完整 manifest。角色库首屏会在后台 I/O 初始化 Room/旧数据导入并持续订阅已有对话摘要，以显示每张卡的对话数量，无需先进入详情。Preset、模型连接与 Chat ViewModel 仍按进入详情或对应功能后才创建；连接 DataStore 的已解析状态由应用级共享流复用，多个 ViewModel 不会分别解析同一份模型目录缓存。角色详情的系统返回与页面返回按钮共用同一导航操作，清除角色选择并回到角色库。

Conversation State 属于同一个 `ConversationRuntimeState`，因此跟随既有消息前后检查点、regenerate、swipe、截断与进程恢复语义。`UpdateVariableSetV1Adapter` 与 `UpdateVariableJsonPatchV1Adapter` 只把唯一、完整 assistant update envelope 中白名单路径的 scalar 更新解码为 `ConversationStatePatch`，`NativeAdaptationRuntime` 在消息候选完成时一次应用；完整空块是已确认的 no-op，缺块不是状态事实，缺失内层或外层闭合标签的畸形块不会被宽松修复。原始 `sourceText` 保留机器块用于摄入与诊断；声明对应 Adapter 后，Player 在聊天 storage/display 的 Macro / Regex 投影前剥离已识别的完整机器块，并在流式阶段暂时隐藏未闭合块。畸形块不直接写入状态；单个畸形块只有在独立确认成功后才从 canonical 展示移除，歧义的多个块保持可见。Adapter 不负责 UI 或 Prompt。`PromptCompiler` 把适配声明的 label/type/description 与当前值按稳定顺序编码为固定 JSON system projection；该投影不经过卡片模板、Macro 或 Regex，也不允许 Adaptation 指定 role、位置或格式。声明 Adapter 时，Player 另生成固定 dialect 与白名单回写契约，并要求每轮以完整块确认更新或 no-op。

生产生成器在主回复缺少、未闭合或给出畸形块时执行一次固定的独立状态确认：输入只包括 Player 状态、已校验白名单和 JSON 转义的当轮对话证据，输出只接受完整 envelope。主回复已有合法块时不增加调用；确认失败时不猜测、不覆盖原回复，并留下状态未确认诊断。成功确认的块独立保存在 `stateConfirmation`，不可变 `sourceText` 仍是主回复原文；状态摄入优先读取确认块，canonical storage/display 可在确认成功后移除原回复中的单个畸形机器片段，两次 Provider usage 合并记录。该流程不创建 Adaptation，也不是自动适配编译器。

Compose 同时提供既有固定 Status、Scene、Collection、Form，以及 JS 动态投影的 Status、Collection、Form、Scene 和 Action Group。固定 Form 按原消息来源与开场匹配生成草稿，仅抑制其已声明接管的纯展示 Regex；动态 Form 和操作通过声明的 handler 进入宿主提交。原有 Scene 可按 scalar State 的精确值读取已安装本地静态图片，动态 Scene 尚未接入远程图片。支持只读状态绑定与受控 JS 行为，不提供通用布局组件树。详见[Surface 实现记录](archive/native-script-surfaces-20260908.md)。

每个消息候选同时保存投影前 `sourceText`、canonical storage content，以及消息处理前、内容投影前和处理后的 Conversation Runtime State。聊天气泡可以直接编辑已完成的用户或 assistant 消息。“保存文字”只重新产生该候选的 canonical content，保留 reasoning、生成 metadata、其他候选、后续 Turn 和当前运行状态；后续请求读取修正后的历史，但不会假装已经重新执行过去的 Macro 或 World Book 状态变化。“从这里重新生成 / 继续”才把该 Turn 收敛为一个手动候选、截断其后全部 Turn，并把当前运行状态恢复为编辑后结果。旧后缀不会作为隐藏分支保留。状态检查点不设历史窗口，未来 branch 是否以及如何建立仍是独立产品决定。

模型连接按模型 ID 保存可选的 context / output token 上限覆盖。覆盖值逐字段优先于 Provider 模型目录，只进入运行时能力解析，不反向修改 Preset；切换模型会切换到对应模型自己的覆盖记录。

界面主流程是角色库 → 角色详情 / 兼容性报告 → 新建或恢复 Conversation → Chat。角色库可进入单一默认身份编辑器；角色库和 Chat 都可以进入 Preset 中心，Chat 另有运行中禁用的快捷切换 bottom sheet。Preset 中心通过 Storage Access Framework 导入 / 导出；选择列表项会先激活再编辑。详情默认只展示实际 order 中的普通 Prompt 与 Regex 快速开关，Prompt 开关只改 `enabled`，不会改变成员关系或相对顺序；单项内容、兼容字段、结构设置和请求参数使用独立全屏次级页面。全部修改显式保存，带未保存修改返回时提供保存、放弃和继续编辑；不新增或删除 Prompt 定义，也不重写 Regex。导入和浏览不要求模型配置，首次发送时才引导配置。恢复对话、产生新消息和生成结束时，Chat 会定位到最新消息；只有 reasoning 尚无正文的流会显示轻量“正在思考…”状态。聊天气泡直接提供“保存文字”和“从这里重新生成 / 继续”；只有后者会在存在后续消息或其他 swipe 时确认将被丢弃的事实。开场和备用开场是 opening swipe；regenerate 为最后一个 assistant turn 增加候选，切换已缓存候选不会重新求值 Macro。

网页模式在破坏性清理前取得 DISPLAY 投影，交给单 WebView 的可信消息外壳；普通 HTML 清理后展示，完整 body 围栏进入不同源的作者兼容区。Native 模式继续采用原有 Compose 安全 Markdown。消息编辑在网页模式使用原生弹窗，沿用文字保存/截断重启两种业务入口。聊天页的系统返回与顶部返回按钮共用导航操作，回到角色详情；忙碌时消费系统返回而不离开，保持与顶部返回按钮禁用状态一致。键盘在场时先由输入法处理返回。

## EJS 提示词执行

网页模式从原世界书读取 EJS 入口并绑定原文哈希，无需编译；Native 编译契约通过 `ejsSourceIds` 选择完整世界书模板，本地安装为来源哈希绑定的 `ejsTemplates`，与同条目的旧原文分支选择互斥。`PromptCompiler` 在条目触发/分组及 WORLD_INFO Regex/Macro 后请求求值；`QuickJsEjsRuntime` 在应用挂起边界执行 EJS 并提供只读 MVU 检查点、当前分支的 Prompt 历史和有限查询接口。编排与 Provider 重裁剪使用同一轮结果缓存，不重放脚本。世界书和最终上下文预算计入实际输出；渲染结果作为字面量插入，不再次执行 Macro/EJS。异常阻止请求，取消释放引擎。各模板实例独立，不保存另一份变量时间线。来源、确切历史范围语义及验证见 [EJS 接入记录](archive/ejs-quickjs-integration-20260907.md)。

## 当前尚未实现

当前闭环不包含 CHARX、YAML、BYAF、Text Completion Preset、空白 Preset 创建、多 Persona 管理 / 选择 / 绑定、Character 编辑 / 导出、独立 Regex 管理、完整第三方扩展宿主，以及 Conversation delete、continue 和可保留旧后缀的 branch / checkpoint。真实社区卡和 OpenAI Preset 可以进入对话；已安装适配的受控能力按上述契约运行，其余 Tavern Helper 依赖不被伪装为兼容。直接运行作者程序的已实现范围见[网页运行契约](web-runtime.md)。
