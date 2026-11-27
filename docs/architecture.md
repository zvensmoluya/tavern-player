# Tavern Player 当前实现架构

> 状态：描述当前仓库已经建立的边界，更新于 2026-11-25。默认网页消息区、宿主桥和原程序装载已接入，范围见[网页运行契约](web-runtime.md)。
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

模块边界刻意把“不可信角色卡内容”与网络、文件系统和 Android UI 隔开。纯 Kotlin 内容层不自行联网或运行浏览器；作者页面/后台脚本由 app 层隔离 WebView 执行，MVU/EJS 由 QuickJS 宿主执行。

## 网页运行路径

`BrowserProgramReader` 保留原文与来源，`BrowserProgramPreparer` 仅解析并识别登记公共模块。`BrowserSession` 连接 Android WebMessageListener 与可信外壳，`BrowserConversation` 负责身份/版本校验、同步视图快照与纯状态提案；`ConversationSession` 统一持有可写会话、已提交基线、版本与生成任务，串行提交玩家与作者页面的会话操作；`ChatViewModel` 只做生命周期和 UI 适配。网页消息修改不调用用户编辑的 Macro 重处理或截断入口。

网页外壳先创建一个持久作者会话协调 iframe，再挂载消息和后台脚本。`session.mjs` 统一保存共享对象、事件监听器、待提交视图和命令队列；各 `host.mjs` 保留页面身份与原生传输入口。作者内容与协调器同源，通过实际对象引用共享函数和回调；协调器仍与可信外壳不同源且不直接访问原生桥。原生事实事件只向协调器分发一次，销毁页面按所属身份清理监听器、等待及排队操作。

普通流式文字通过 DOM 修补续写；静态富文本在持久 iframe 内清洗并更新，保留未变节点和用户输入状态。展示缓冲未完整 HTML/CSS，作者交互页面仍在最终事务保存后启动，准备完成再替换可见占位。源码或候选变化按作者页面生命周期重建，静态修补不会用于可执行作者页面。

可信外壳和作者兼容父页面使用不同来源；消息和后台脚本各自持有候选/脚本身份。初次快照、后续增量、50 ms 合并更新、先保存后确认、失败停止实例及销毁取消均由生产路径实现。新建记录标记为 `BROWSER`。列表与打开入口只接受网页会话，旧原生记录留在原存储中。

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

开关直接修改草稿，点击条目名称进入详情。导入与保存提示显示在列表外。从详情、请求参数或结构设置返回时保留主列表的滚动位置。文本处理详情先显示规则作用和处理位置，匹配与替换内容按需展开。

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

Regex 来源顺序为 Preset 后 Character。canonical storage、Provider prompt 和安全 display 是不同投影；Provider 原始 reasoning / signature 不被破坏，当前 Preset 只控制其展示投影。斜杠字面量允许 flags 后有空白，裸正则的首尾空格参与匹配。开头的 JavaScript 正向后向断言会改写为保留前缀的等价前向匹配，编号捕获组与 `{{match}}` 仍按原规则求值，以避开 Android 可变长度 lookbehind 的性能陷阱。不支持的 JS 正则语义会跳过并报告，生产执行使用共享有界 worker 和 250 ms 单规则熔断；测试可以注入确定性执行策略。

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

`MvuConversationRuntime` 在网页会话创建、完整回复和重启式编辑时执行角色声明的 MVU 程序。处理后的正文参与显示与编排，原文和 MVU 检查点随候选保存，切换候选时恢复。

`CharacterRepository` 在 app-private 目录中按角色保存版本化 manifest、原始 source、静态头像缩略图和通过 Native Decoder 验证的本地 PNG/JPEG/WebP 资产。SHA-256 相同的导入返回已有资产；同名但内容不同的卡片形成新资产。资产物化限制内嵌字节数、边长和像素数，远程 URI 不会联网解析。

`PresetRepository` 以一个原子 app-private manifest 保存当前 Preset、初始 source 树和全局 active ID；内置默认的初始版本由代码注入。它提供导入并激活、显式保存、恢复初始版本、从当前草稿另存为并激活、删除和无损导出；内容去重、大小写不敏感唯一命名以及删除 active 后回退都在同一持久状态边界完成。原始文件的空白与键格式不单独保存，但解析后的全部 JSON 数据都会保留。

`PersonaRepository` 原子保存一份全局默认 Persona。「我的」中的身份编辑器允许修改 name、description 与可选头像；创建 Conversation 时捕获当前值，之后修改默认身份不会改写已有 Conversation。当前没有身份列表、选择器或 Character 绑定。

`ConversationRepository` 通过 `RoomConversationStore` 保存 Character Snapshot、Persona、turn/variants、运行检查点、世界书意图和生成诊断，不保存 Conversation 级 Preset 绑定。会话头、摘要、草稿、消息、候选、生成计划分片、原生操作、原始流日志与不可变内容引用分表；相同正文和检查点按内容哈希复用。角色列表只读摘要，打开会话才重建该会话对象，读取中共享相同检查点和正文实例。Persona description 的编排语义保持不变。

数据库位于应用私有目录 `tavern/conversation.db`，Room schema 1 导出在 `app/schemas`。旧 `tavern/conversations/*.json` 首次逐份导入、完整字段回读比较，全部成功后激活；损坏文件阻止激活，原文件始终保留。JSON 导入格式仍为 Conversation record schema v3，不支持更早的 `adaptationState` 名称。正常写入不再生成会话 JSON 文件。

`ConversationSession` 是业务写入所有者：持有 `commitRevision` 和独立 `draftSeq`，所有持久业务提交通过统一提交锁，存储拒绝过期版本。普通打字仅更新 draft 表，不改最近活动时间。流式事件先按序进入独立日志，预览最多约每 50 ms 更新，进度每 500 ms 或达到批次门槛写入。Finished 只记录结束原因，继续接收尾随 usage；最终投影、MVU 结果、正文、用量、运行状态与日志删除在同一事务提交，成功后发布 COMPLETE。保存失败保留待提交结果并阻止后续业务写入，用户可重试保存。切会话与关闭先结束任务并等待落盘；进程意外退出后按原始事件重建 INTERRUPTED 正文，不重新调用模型、MVU 或作者动作。详细边界见[会话存储与事务实现](conversation-storage.md)。

应用启动只立即创建角色库所需的轻量对象；角色 manifest 在后台 I/O 初始化并同时建立路径元数据索引，头像、来源文件和本地资产查询不再重复反序列化完整 manifest。首页会在后台 I/O 初始化 Room/旧数据导入并持续订阅已有对话摘要，供全局对话列表和角色详情共用，无需先进入详情。Preset、模型连接与 Chat ViewModel 仍按进入详情或对应功能后才创建；连接 DataStore 的已解析状态由应用级共享流复用，多个 ViewModel 不会分别解析同一份模型目录缓存。角色详情的系统返回与页面返回按钮共用同一导航操作，清除角色选择并回到角色库。

每个消息候选同时保存投影前 `sourceText`、canonical storage content，以及消息处理前、内容投影前和处理后的 Conversation Runtime State。聊天气泡可以直接编辑已完成的用户或 assistant 消息。“保存文字”只重新产生该候选的 canonical content，保留 reasoning、生成 metadata、其他候选、后续 Turn 和当前运行状态；后续请求读取修正后的历史，但不会假装已经重新执行过去的 Macro 或 World Book 状态变化。“从这里重新生成 / 继续”才把该 Turn 收敛为一个手动候选、截断其后全部 Turn，并把当前运行状态恢复为编辑后结果。旧后缀不会作为隐藏分支保留。状态检查点不设历史窗口，未来 branch 是否以及如何建立仍是独立产品决定。

新建连接时直接填写请求 URL、选择请求格式、输入可选的 API Key。「保存并获取模型」保存后打开模型列表。默认地址为 OpenAI 基础地址，默认格式为 Responses。切换格式保留用户填写的 URL，端点解析和同源路由回退由所选协议处理。

已有连接的详情页展示地址、格式、Key 设置状态与当前模型。地址与格式在同一页保存，跨源复用凭据须确认目标地址。更换 Key 在详情页原位输入、取消或保存，界面与连接元数据仅需凭据引用，无需保留尾号。连接名称默认使用主机名，重命名和模型上限各自在自己的编辑窗口保存。

模型页以模型名称标识各个连接，并显示当前用于聊天的模型。点击「使用」切换到已配置的模型。全屏模型列表支持搜索、刷新目录与手填模型 ID。点选后保存并返回，连接就绪时显示已选用的模型名称。修改连接参数和测试连接都保留原先的模型选择。目录保留上次成功获取的结果；「测试连接」则使用已保存的配置实际请求所选模型，结果和重试入口留在详情页。每次测试重新读取连接与凭据，区分本机凭据读取失败、服务身份验证失败（HTTP 401）、拒绝访问（HTTP 403）和网络连接失败。

模型连接按模型 ID 保存可选的 context / output token 上限覆盖。覆盖值逐字段优先于 Provider 模型目录，只进入运行时能力解析，不反向修改 Preset；切换模型会切换到对应模型自己的覆盖记录。

界面以「对话｜角色｜模型｜我的」四个主区域组织。冷启动进入全局对话列表，每个 Conversation 独立展示并直接续聊；角色页 → 角色详情仍提供新建和已有对话入口。「我的」提供单一默认身份编辑器、Preset 中心和全局世界书入口；Chat 仍可进入 Preset 中心，并保留运行中禁用的快捷切换 bottom sheet。Preset 中心通过 Storage Access Framework 导入 / 导出；选择列表项会先激活再编辑。详情默认只展示实际 order 中的普通 Prompt 与 Regex 快速开关，Prompt 开关只改 `enabled`，不会改变成员关系或相对顺序；单项内容、兼容字段、结构设置和请求参数使用独立全屏次级页面。全部修改显式保存，带未保存修改返回时提供保存、放弃和继续编辑；不新增或删除 Prompt 定义，也不重写 Regex。导入和浏览不要求模型配置，首次发送时才引导配置。恢复对话、产生新消息和生成结束时，Chat 会定位到最新消息；只有 reasoning 尚无正文的流会显示轻量“正在思考…”状态。聊天气泡直接提供“保存文字”和“从这里重新生成 / 继续”；只有后者会在存在后续消息或其他 swipe 时确认将被丢弃的事实。开场和备用开场是 opening swipe；regenerate 为最后一个 assistant turn 增加候选，切换已缓存候选不会重新求值 Macro。

网页模式在破坏性清理前取得 DISPLAY 投影，交给单 WebView 的可信消息外壳；普通 HTML 清理后展示，格式化后识别出的页面代码块按顺序进入不同源的作者兼容区。消息编辑在网页模式使用原生弹窗，沿用文字保存/截断重启两种业务入口。聊天页的系统返回与顶部返回按钮共用导航操作，回到进入时的全局对话列表或角色详情；忙碌时消费系统返回而不离开，保持与顶部返回按钮禁用状态一致。键盘在场时先由输入法处理返回。

## EJS 提示词执行

网页模式从原世界书读取 EJS 入口并绑定原文哈希。`PromptCompiler` 在条目触发/分组及 WORLD_INFO Regex/Macro 后请求求值；`QuickJsEjsRuntime` 在应用挂起边界执行 EJS 并提供只读 MVU 检查点、当前分支的 Prompt 历史和有限查询接口。编排与 Provider 重裁剪使用同一轮结果缓存，不重放脚本。世界书和最终上下文预算计入实际输出；渲染结果作为字面量插入，不再次执行 Macro/EJS。异常阻止请求，取消释放引擎。各模板实例独立，不保存另一份变量时间线。来源、确切历史范围语义及验证见 [EJS 接入记录](archive/ejs-quickjs-integration-20260907.md)。

## 首页布局与导航状态

`TavernPlayerApp` 保存当前页面、聊天来源、会话 ID 与详情角色 ID；主区域由 `HomeScaffold` 统一提供底栏。详情、聊天和各编辑页面收起底栏。模型编辑的系统返回关闭编辑器，身份编辑的系统返回与页面返回一致；从其他主区域按系统返回先回到对话页。页面的搜索和滚动状态由 `SaveableStateHolder` 保存，切换主区域后可以恢复。

全局对话列表沿用 Room 的摘要流，不读取完整消息历史，也不按角色折叠。行内展示角色名、最新摘要、更新时间、消息数和创建时间；创建时间用于区分同角色的多场对话，不生成故事标题。缺少原角色资产时保留入口与占位身份，仍可加载会话自身的快照；当前摘要表不含快照角色名，因此此时列表显示「已保存的角色」。打开新会话期间显示等待或错误状态，不展示上一场对话或演示内容。

角色页共用同一数据源和名字/标签过滤，切换两列封面或单列名册。显示偏好保存在 `player_ui` Preferences DataStore 的 `character_library_layout` 字段，值为 `grid` / `list`，缺省或未知值使用 `grid`。偏好读取失败不阻断角色加载，保存失败在页面提示。角色卡辅助信息取最多三个非空标签，再回退到作者或「查看角色详情」。顶部「＋」菜单复用文件导入与 Shelf 扫码；原角色页中的模型、预设、身份和世界书快捷入口已移到新的导航位置。

角色头像在 I/O 线程按最大边不超过 768 px 的采样尺寸解码，坏图或无图使用占位；共享线性图标由 Compose 绘制，未引入新图片、字体或动画资源。页面视觉仍为可迭代首版，不将布局探索图作为主题规范。

## 启动扉页

首次角色库初始化期间展示 Compose 绘制的童话书扉页：浅纸色、固定细纸纹、书页与枝叶线描、逐渐显现的字标，以及低幅度明灭的星星。纸色沿用现有主题背景，不引入图片、视频、外部字体、动画 SDK 或网络资源；纹理坐标按尺寸缓存，不逐帧生成随机噪声。

`CharacterLibraryUiState.initialLoading` 等待角色/默认身份初始化结束及对话摘要首次返回（空库也算完成）。异常终止对应等待并沿用角色库错误提示，不让动画掩盖读取失败。没有最低播放时长，数据就绪后用 180 ms 淡化进入默认的对话页；加载更快时可直接跳过扉页。后台返回、导航回库和导入不会重置首次加载标记。动画沿用 Compose 的系统动画时长缩放；系统禁用动画时显示静态完成画面。Android 系统启动窗口仍由系统管理，窗口底色与扉页一致。

静态预览入口为 `StorybookPagePreview` / `StorybookPageLandscapePreview`。设备验收需覆盖冷启动快/慢加载、空库、错误提示、后台恢复、系统关闭动画、横屏与大字体，以及帧率和启动耗时；主机测试不作为这些效果的实测证据。

## 当前尚未实现

当前闭环不包含 CHARX、YAML、BYAF、Text Completion Preset、空白 Preset 创建、多 Persona 管理 / 选择 / 绑定、Character 编辑 / 导出、独立 Regex 管理、完整第三方扩展宿主，以及 Conversation delete、continue 和可保留旧后缀的 branch / checkpoint。真实社区卡和 OpenAI Preset 可以进入对话。直接运行作者程序的已实现范围见[网页运行契约](web-runtime.md)。
