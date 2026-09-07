# Native 内容适配边界 v1

> 社区素材使用中性编号，见 [样本编号约定](community-samples.md)。

> 状态：开发期内部契约，不是角色卡公共格式或创作者 SDK。产品方向见 [`Tavern Player Native Adaptation 设计原则0903.md`](Tavern%20Player%20Native%20Adaptation%20设计原则0903.md)，运行行为边界见 [`Tavern Player Native Domain Operations 设计草案.md`](Tavern%20Player%20Native%20Domain%20Operations%20设计草案.md)。

## 当前方法

第一阶段由兼容工程师直接阅读原始角色卡与 Player 实现，手工填写 `NativeAdaptation`，再由确定性代码校验和执行：

```text
Original source (immutable)
        │
        ▼
Manual behavior audit
        │
        ▼
NativeAdaptation candidate
        │
        ▼
Deterministic validation
        │
        ▼
Native playback
```

2026-09-06 起转向固定能力范围内的自动编译实验，不再要求复杂卡的全部外部功能先完成人工迁移。当前 Program View 保留卡内完整 JS、HTML/正则、EJS 代码与关联变量规则，普通背景留在本地；不再以固定语法识别器限定模型可见的玩法。模型通过一次独立请求生成 `NativeCompilationDraft` 状态、控件和行为配置，本地恢复原文引用与模板并执行下述同一套安装校验；模型不拥有 Runtime 设计权。结构校验与整卡语义验收分开。远程图片延期，编译输出不包含 `memories`。手工适配仍是审计对照，Shelf 继续是可选存储和传输工具；未建设自动 repair 或派生缓存服务。流程与实测状态见 [自动适配实验](native-compilation.md)，已有卡片验收清单见 [Native 玩法还原验收](native-gameplay-fidelity-audit.md)。

当前执行扩展：适配可声明 `mvu` 原始变量程序，以及 `ejsTemplates` 世界书原文引用。EJS 读取当前 MVU 检查点和选中分支历史，在发送时生成文字；同一条目不能同时使用 `worldBookTextSelections`。下文有限分支选择的限制仍适用于该旧配置。详见 [MVU 聊天接入](mvu-chat-integration-20260907.md) 和 [EJS 接入与宿主范围](ejs-quickjs-integration-20260907.md)。

## 原件、安装与 Conversation Snapshot

- `sourceSha256` 把 Native 内容绑定到不可变原件；安装时必须与已导入 Character 的哈希完全相同。
- 适配只旁挂到 app-private Character manifest，不修改 `source.png`、`source.json` 或 Character Card extension。
- 安装先验证完整候选；失败时不写入部分结果。
- 新建 Conversation 捕获当时的 Character 与 Native Adaptation snapshot。之后替换 Character 上的适配不会改写旧 Conversation。
- 手工 fixture 是开发期测试材料，可以随核心设计重做，不承诺跨版本兼容。

## NativeAdaptation v1

`NativeAdaptation` 只描述 Player 已经拥有的内容和固定界面：

```text
NativeAdaptation
├── state
├── assistantStateAdapters
├── status
├── scenes
├── collections
├── forms
├── progressions
├── messagePanels
├── worldBookTextSelections
├── playerChoices
├── guide
├── memories
└── report
```

不存在通用 component tree、binding、condition、event、trigger、action、capability 或脚本字段。

### Conversation State

状态类型为 `STRING`、`NUMBER`、`BOOLEAN`、`RECORD` 和 `COLLECTION`。`RECORD` 与 `COLLECTION` 使用有限、强类型的 scalar field shape。当前 assistant 消息摄入只写入顶层 scalar；结构化状态已经可以被保存和固定 View 读取，但新增、删除集合成员等写操作尚未形成 Player 领域契约。

Conversation State 位于 `ConversationRuntimeState`，每个消息候选保存处理前、投影前和处理后的完整快照。它与 Macro local variables、World Book timed state 和 World Book activation overrides 一起参与 swipe、regenerate、历史截断、持久化和进程恢复。

### Legacy State Adapter

当前只有两个专用反腐层：

- `UPDATE_VARIABLE_SET_V1`：在完整 `<UpdateVariable>` 块中识别 scalar `_.set(path, old, new)`；
- `UPDATE_VARIABLE_JSON_PATCH_V1`：在唯一且完整的 `<UpdateVariable><JSONPatch>...</JSONPatch></UpdateVariable>` 中识别 RFC 6902 外形的 scalar `replace`。

两者只接受适配中逐项声明的精确 `sourcePath -> targetStateKey` 白名单，并按目标状态类型校验值。函数、表达式、对象、数组、动态路径和未声明目标不会执行。JSON Patch 形态不是通用 JSON Patch Runtime；`add`、`remove`、`move` 等操作不获得语义。

Adapter 返回 `ConversationStatePatch` 或带原因的拒绝结果。完整、唯一且每项操作均符合已声明 dialect、路径和类型的块才会整批应用；任意一项错误都会拒绝整批，不产生部分状态。合法空块是经确认的 no-op，缺块、未知路径、类型错误和超限都不等于 no-op。状态确认与摄入共用同一验证入口，不能仅凭 JSON 外壳合法就报告成功。Adapter 不渲染 UI、不发消息、不修改 World Book，也不形成事件系统。

正常对话生成缺少、未闭合或给出畸形状态块时，App 的固定状态确认层会使用同一模型再发起一次短请求。该请求只包含 Player 当前状态、已经校验的 Adapter 白名单，以及作为 JSON 数据转义的本轮 user / assistant 证据；它只接收一个完整 envelope，不生成或修改 `NativeAdaptation`，也不改写主回复。补取成功后，原始主回复原样保存在 `sourceText`，确认块独立保存在 `stateConfirmation`，两次 usage 合并；失败时不猜测状态，并写入生成诊断。主回复已经给出合法块时不会发生第二次调用。

`sourceText` 保留主模型原始输出，`stateConfirmation` 保留可选的独立确认结果，两者不会混写。对于声明了对应 Adapter 的角色，Player 在 Macro / Regex 和聊天存储投影之前剥离已识别的机器状态块；流式阶段已经开始但尚未闭合的状态块会被暂时缓冲。流结束或失败时必须重新执行最终投影：缺失外层结束标签但内层 JSONPatch 有唯一完整边界时保留其后的剧情；无法确定边界时保留原文供诊断，不得继续隐藏整个后缀。畸形块不因此获得状态执行权。其他单个完整但无效的块只有在独立确认成功后才从 canonical 展示中移除；歧义的多个块不被静默隐藏。

### State → Conversation Semantics

当前选中候选的 State 由 Player 以固定 system projection 提供给下一轮模型：

```json
{
  "definitions": [
    {
      "key": "affection",
      "label": "好感度",
      "type": "NUMBER",
      "description": "角色当前对玩家的好感。",
      "fields": []
    }
  ],
  "values": {
    "affection": 80
  }
}
```

key、definition 和 value 按稳定顺序编码；文本作为 JSON 数据转义，不经过 Character Macro 或 Regex。适配不能提供 Prompt 模板、role、插入位置、条件或动态计算。声明 Legacy Adapter 时，Player 还会生成固定的 assistant 状态回写契约；它只列出已校验 dialect、白名单路径和 scalar 类型。模型在正文之后输出并闭合一个机器状态块，没有变化时也用空块明确确认 no-op。主回复缺失或非法时只补确认一次；仍失败则保留上一轮状态，并在消息上标明未确认。在 OpenAI Responses 边界，Player-owned 的当前状态投影与回写契约共同占据顶层 `instructions`；卡片、World Book 与 Preset 的 system 内容保留原有时序，但以较低的 `developer` 权限发送，不能改写 Player 的当前事实或放宽可执行状态协议。

## Player 固定 Native View

### Status View

只读展示 scalar 状态。可选的 `min/max` 只允许用于 `NUMBER`；`group` 为文字分组名，Player 统一呈现分隔标题。长于 24 字符或包含换行的值改用标签下方的完整行宽，短值与数值使用紧凑两列；没有适配提供的布局树。

`NativeStatusItem.enumDisplay` 是固定的双枚举显示查表：只读取该状态和一个 `gateStateKey`，两者分别最多 16 个选项，必须完整覆盖所有组合，结果也必须属于被展示状态的原枚举。它不是派生状态，不参与状态更新、可用性门槛、世界书或 Prompt。显示与记录不同时，详情同时标出“记录值”；缺失或非法状态保留原记录并提示无法匹配，不猜测默认事实。

摘要和详情使用同一只读投影。每条拥有后置检查点的非流式助手候选提供“状态”入口，显示该候选的 `runtimeStateAfter`；不拿当前值填补缺失历史。未确认回复说明正在展示保留下来的记录，玩家选择记录也注明属于该候选。历史查阅不切换候选或恢复当前时间线。带状态的详情面板完整展开，仍可滚动与关闭。

### Scene View

以一个 scalar State 的精确值选择一张已安装的本地静态图片。布局、缩放与空态由 Player 决定。

### Collection View

只读展示一个 `COLLECTION` 中具有同一声明 shape 的 records。列表、卡片、滚动和详情属于 Player UI，不进入适配。

### Form View

普通表单把经校验的字段值投影为聊天输入框 Draft。Draft 需要用户确认后才作为普通 Conversation Turn 发送。普通表单不直接写 State、修改历史、swipe、regenerate、调用模型或切换 World Book。`emptyText` 保留源表单对空输入的展示文案，区别于编辑器中的 `initialValues`。模板单次替换，插入的字段内容不被再次解释为身份引用。草稿随 Conversation 保存。

立即切换对话前先等待当前生成停止与最终保存，避免延迟保存读取到另一个 Conversation。取消生成的收尾保存不随协程取消而跳过；生成完成也在最终保存后才释放发送入口。没有正文的回复不会启动独立状态确认，更不会用思考内容代替故事或推断新的状态。

表单模板只接受 `{{form.field}}`、`{{user}}` 和 `{{char}}`。所有字段都必须进入 Draft，不能收集后静默丢弃。普通 Form marker 只负责把固定表单附着到匹配的原始消息；它不是可订阅 Trigger。

Setup 表单也可使用 `openingIndices` 绑定原卡开场：0 为 first message，1..N 为 alternate greetings。它与 marker 互斥，且同一来源开场不能重复绑定多张表单。消息候选保存 `openingSourceIndex`；空开场被过滤后仍按来源编号识别，不用候选数组位置或正文片段猜测。Player 用固定选项展示表单标题；同一表单可覆盖自定义选择页及其说明候选，选择项指向列表中的第一个来源。

`replacedDisplayRegexIds` 声明该固定表单已接管的原卡显示规则。引用必须在原卡 Regex 中唯一存在，且 `markdownOnly=true`、`promptOnly=false`。仅在对应 assistant 消息匹配表单时，显示投影跳过这些规则；其他消息、同 ID 的预设规则、Prompt、Storage 和原件均保持原有语义。它不能删除模型上下文或取得规则执行权限。替换 HTML 之前仍须审计其中的文字信息；被接管的网页说明若尚未进入原生资料，应列为信息缺失，不能仅称为皮肤降级。

### 一次性 Setup

带 `setup` 的表单拥有固定开局生命周期，仅在唯一 assistant opening 匹配 marker 或来源引用、尚无 user turn 且从未提交 Setup 时可用。输入来源限于 `setup.values` 的预验证常量、`stateFields` 的同类型标量直接复制，以及单选项 `setup` 携带的预验证常量。不执行模板计算、动态路径、条件表达式或动作链。

`NativeSetupController` 验证完整字段、状态类型、世界书和开场快照引用及冲突后，返回同时包含初始化状态、启停覆盖、`setupCommit` 和 Draft 的新 ConversationRecord。可选的 `setup.openingIndex` 在同一事务中选定已有开场，以该候选检查点为当前状态基础；未指定则保留当前候选。这只是一次性开局的目的开场，不开放任意 swipe/action。不存在、空正文或歧义目标均拒绝。已有不同的非空输入草稿也拒绝覆盖。应用先原子落盘，成功后才发布界面和草稿。失败不改变运行状态或草稿；重复或对话开始后的提交被拒绝。

初始化结果进入所有 opening candidate 的前后检查点，因此候选切换、第一轮失败/重试、历史重启及从磁盘恢复不会回到旧默认值。重新开始对话清除本次 Setup。表单不拥有自动发送或生成权限。

固定原生表单使用统一布局；状态摘要固定在输入框上方，完整 Status/Scene/Collection 放入详情面板。表现由 Player 决定，不由适配文件指定颜色、布局树或事件处理器。

### 显式玩家选择

`playerChoices` 属于 Player 固定的“查看选择 → 预览 → 取消或确认”流程。每项只能读取一个枚举状态作为可用性门槛，赋值一个非只读、非派生的枚举状态，并提供一份有界的普通聊天草稿。不能进行数值运算、多个状态写入、世界书启停、模型调用、自动发送或动作编排。普通表单、模型状态回写和定时器不能调用此流程。

只有当前最后一条 assistant 候选已完成时才能提交。预览保存 Conversation、候选、正文、完整状态及草稿的当前值；确认时重新验证，任何变化都要求重新预览。预览与取消不写任何状态。确认产生新记录，应用原子落盘成功后才发布事实与草稿；失败均不发布。每个确认凭据至多提交一次，同一候选最多记录 64 次明确确认。

提交只更新选中候选的 `runtimeStateAfter`，不改写助手正文、生成前检查点或其他候选。`playerChoiceCommits` 记录选择及前后枚举值，界面标出最近一次选择。重新生成从原生成前状态开始，原候选保留玩家选择；从该消息重新开始则重新计算正文状态并移除该消息的选择记录。下轮模型读取已提交的当前状态，草稿仍需玩家发送。

未改写的选择草稿携带来源候选与确认记录引用。切换/丢弃该候选时清除它，切回来不自动填入旧草稿；玩家修改后按普通草稿保存。发送清除草稿来源，不会因恢复或重试重复执行选择。角色卡不能用这些引用挂接任意事件。

### 有界状态与阶段

`numberRange` 对已声明数值执行上下限裁剪，`allowedStrings` 限制有限字符串选项。非法选项仍使整批状态更新失败。数值的每回合变化幅度与剧情事件是否发生，仍由原卡叙事规则约束模型；不会把这部分判断伪装成确定性校验。

`progressions` 是固定阶段表：一个有范围的数值状态决定阶段，一个可选布尔事件标记决定该阶段是否解锁。阈值必须严格递增，输出只能是字符串状态，事件只能是布尔状态，因此不能形成规则递归或任意表达式。初始化、Setup 与助手状态入库后都重新判定阶段，结果随候选检查点保存。

`AssistantStateMapping.writable=false` 声明只读来源映射，可让原卡 `stat_data` 读取看到派生阶段。它不出现在模型回写白名单；模型直接改写阶段会使整批更新失败。原卡关系阈值与剧情锁作为适配数据提供，生产代码不含角色名或该卡阈值。

### 每条消息的资料面板

`messagePanels` 声明一个已知标签和有限字段标签，Player 从不可变 `sourceText` 提取本幕资料，并用统一的原生详情呈现。它不是 HTML 渲染器，不执行 CSS、脚本或模板。完整匹配后，面板内容从叙事正文中移出；原始回复仍完整保存，回看历史时不会被当前状态覆盖。

缺少字段、重复标签、额外未映射内容或过大载荷都不作为成功提取，不静默丢弃原文。流式未闭合块可以暂存，最终无法识别时恢复原文。每幕心境、日历、旁白不自动成为持久变量。

### 只读玩法说明

`guide` 为固定的阅读面板，补回被原生表单接管的网页说明。它引用原卡唯一的显示侧 Regex `replaceString`，保存其 UTF-8 SHA-256，并将静态文字的 UTF-16 半开区间按小节排列。最多 16 节，每节最多 8 段，每段不超过 8192、总计不超过 32768 个 UTF-16 单元。适配只提供标题和来源引用，不提供网页、组件树或交互事件。

安装及阅读均核对来源、哈希、区间和字符边界；缺失、歧义或变化时拒绝部分展示并给出说明。选取的片段不能含脚本、样式、嵌入对象或 EJS 标记；其余 HTML 经过既有惰性文字清理。正文只对 `{{user}}`、`{{char}}` 做一次固定名字替换，插入值和其他 Macro 不再解释。不会运行 Regex、JS、样式、远程资源，也不会向 Prompt 注入说明或修改状态。

开场选择旁提供“玩法说明”，后续仍可从详情阅读。说明使用 Conversation 捕获的原卡和适配快照；开局完成、候选切换和恢复不改变文字来源。原卡导航中的“去下方网页表单”由 Player 的返回聊天入口替代，装饰和主题按钮不复制；原有世界、机制、路线、人物和动向信息保留为只读文字。

## 本地静态 Assets

角色卡中的 `ccdefault:` 图片以及受支持的内嵌 `data:` PNG、JPEG、WebP 可以成为稳定 `assetId` 对应的 app-private 文件。导入执行媒体类型、字节数、边长和像素数限制；显示使用 Android Native Decoder 与有界采样，不使用 WebView。

远程图片、HTTP、cookie、凭据继承、SVG、脚本和动态资源加载不属于 v1。允许显示像素不等于允许执行或联网。

## World Book Domain Runtime

### 原文分支选择

`worldBookTextSelections` 是开发期实验契约：以一个声明了完整 `allowedStrings` 的 STRING 状态，为一个已有世界书条目选择一段原文。它是每次 Prompt 编译的只读投影，最多 8 个条目、每条最多 32 个选项；不建立 Condition、Trigger 或 Operation。

每项携带 `bookId`、`entryId`、`stateKey`、条目正文的 UTF-8 SHA-256，以及各 `stateValue` 对应的 `sourceStart/sourceEndExclusive`。可选的 `sourcePrefix/sourceSuffix` 各引用同一条目的一个固定公共前/后文区间（`start/endExclusive`），用来保留角色指向与外层标签。区间按 Kotlin String 的 UTF-16 单元计数、左闭右开，不能拆开代理对；前文、当前分支、后文必须按源顺序且互不重叠，拼接总长度不超过 8192。拼接后的文本也检查 EJS 标记，不能从片段边界重建代码标记。适配不能提供新增 Prompt 文本。分支必须非空且不含未处理的 EJS 标记；这不代表支持执行其他 EJS，也不证明人工选择的语义正确。

安装时和运行时都核对唯一目标、原文哈希、区间与枚举覆盖。运行时同时核对角色原件哈希；状态缺失或非法时阻止该次生成并给出诊断，不能悄悄选默认分支或把所有分支一起发送。

选择发生在 World Book Engine 的递归扫描和预算核算之前，因此未选中的正文不贡献递归关键词或 token 成本。条目的启用、关键词、角色、插入位置、优先级、概率与计时仍由已有世界书机制处理。原件与快照不改写，不产生启停覆盖；每轮都从当前 Conversation State 重新选择，检查点恢复自然影响下一轮选择。Prompt trace 记录目标条目、状态键和来源区间。

### 启停覆盖

原世界书条目的布尔 `extensions.ignore_budget=true` 由 `WorldBookEntryDefinition.ignoreBudget` 读取，保留在源 extensions 中，因此已存快照无需新增第二份字段。该条目即使在世界书子预算耗尽后仍可激活，消耗仍计入报告，不绕过启用、关键词或概率规则。最终整轮 context 上限与裁剪保持有效，trace 显示子预算豁免及后续裁剪；豁免不保证无限制保留所有世界书。

`WorldBookActivationOverrides` 是 Conversation Runtime 的 Player-owned 能力，不是通用 Adaptation action。当前有两个强类型 intent：

- `SetBookEnabled(bookId, enabled)`
- `SetEntryEnabled(bookId, entryId, enabled)`

执行器先验证全部 snapshot 引用，再原子返回新的 Runtime State；不存在通用 dispatcher。World Book Engine 读取有效覆盖并在 Prompt trace 中说明由 Conversation override 造成的启停。普通 Form 和 Legacy State Adapter 都不能调用这些 intent；一次性 Setup 是明确的生产调用方。

## 确定性验证

安装前至少校验：

- schema version、原件 SHA-256 与可用本地 asset ID；
- State key、类型、完整 Record shape 和资源数量上限；
- Adapter dialect、精确路径语法、白名单映射与 scalar 目标；
- View ID、Form marker、field、option 和 scene value 唯一性；
- Status/Scene/Collection 的 State 类型；
- Form 输入类型、默认值、Draft reference 和长度上限；
- compatibility report 明确区分 restored、degraded 与 unsupported。

验证成功不证明手工适配语义正确。它只证明候选落在 Player 已授权的有限能力空间；玩法正确性仍由人工审计、确定性测试、真实卡证伪和真实对话测试共同验证。

## 当前真实样本

开发夹具 `pressure-card-manual.json` 手工适配本机 `source/复杂压测卡.png`：

- 19 个 scalar 状态和一个严格 JSON Patch-shaped Adapter；
- 固定 Status View 展示全部 19 项，含四名角色心里话和变身；
- 六项数值范围、七项字符串枚举，身体只允许开局 Setup 写入；
- 两条预设开场与自定义开局各有对应原生表单；Setup → 选定来源开场 + 本地身体状态 + Draft；
- opening candidates 保留给现有 swipe；
- PNG 卡面作为可验证本地静态资产；
- 身体行文指导以有限原文分支选择进入实际 Prompt；开场草稿保留各路径输入，空项标签及引导语尚非原 JS 的逐字复刻，模型实际遵循仍需验证。主动战败已映射到固定玩家选择的本地流程；未变身禁用，确认后写战局及普通草稿，不自动发送。原导航说明通过固定只读面板恢复；多轮玩法仍待验收。源码审计没有找到该卡开局动态启停 World Book 的调用，已撤回此前笼统归因。

该夹具用于淘汰 Player 设计，不用于从单卡反推通用 Runtime。

`doctor-manual.json` 对应简单表单样本 C-03，保留五项预约输入、多选合并、全部空值默认文案及原始草稿结构。这张卡没有需要迁移的持久变量协议，因此没有新增模型状态契约。

角色详情提供严格 JSON 的手工适配导入入口，校验原件哈希、资产和世界书引用。只影响后续创建的对话，旧对话保留快照。

`second-pressure-manual.json` 对应第二张复杂卡：九项状态，八项允许模型写入；关系阶段由有限阈值和事件门槛判定，原卡五项本幕资料独立附着到回复。动态关系反馈已按派生阶段选择唯一原文，保留公共角色指向与标签。远程 CG、任意 JS、后台 RUBY 任务仍明确列为未迁移，不用 PARTIAL 标签替代这些具体差异。

原卡的两个固定读取宏 `get_message_variable::stat_data` / `format_message_variable::stat_data` 可根据已声明 Adapter 的精确映射重建当前状态的只读 JSON。不会建立第二份 MVU 存储，也不执行 JavaScript、EJS 或任意状态路径查询。


### 对话记忆

`memories` 描述 Player 自有的分析刷新流程，最多四份记忆。每份有唯一 id、标题、哈希绑定的分析要求条目、最多八份唯一静态资料引用，以及 `firstReply/everyReplies`（首次位置与周期，范围 2..64，首次在周期内）。引用可以来自关闭普通注入的条目；每份正文须非空、不超过 32768 UTF-16 单元且不含 EJS 标记。没有网络地址、脚本、任意事件或动作字段。

正常助手回复完成后，按当前候选路径的 COMPLETE 助手条目计数，包含开场；满足周期时串行分析。导入、打开对话与候选切换本身不发请求。详情中的“更新记忆”是用户明确触发的固定刷新/重试入口。分析使用当前连接，输入为原分析要求、所选资料、该记忆的上次结果、当前状态、当前候选路径的完整普通对话；只做 user/char 的一次固定替换，不执行资料宏。Provider 检查分析上下文容量，超限时不悄悄删历史。状态协议未确认不阻止文本分析；证据显式包含 stateConfirmedForLastReply，要求模型将未确认的数值快照与本轮剧情分开，分析不能回写或确认数值状态。

模型只生成完整分析文本，无 State Adapter，无工具或运行时权限。只有非空、长度不超过 32768、流正常结束的结果才能进入提交。提交再次比较完整对话记录；过时结果拒绝。新结果与当前候选的后置检查点一起原子保存后才发布，记录依据的候选 id、助手条目数、模型和 usage；不增加聊天条目、不改标量状态。取消分析不把已经完成的正文标为取消。保存与发布处于不可取消区间，期间阻止输入及对话重置；若在保存时取消，完成当前提交后不再请求剩余分析。失败保留旧记忆并提示重试。

记忆是 `ConversationRuntimeState.memories`，不是角色源世界书的文件修改。它以 Player 自有常驻世界书条目参与子预算，位于角色设定前；进入最终上下文裁剪与 trace。记忆文字可贡献已有世界书关键词扫描，但不会执行宏或世界书正则，也不经过预设 worldInfoFormat 的二次展开。原世界书内容处理保持原路径。记忆条目先参与预算，因此与原宿主合并聊天世界书的排序可能不同，必须在长链路中核对是否挤掉必要规则。

切换候选恢复该候选记忆，重新生成从回复前检查点分析，编辑历史文本会从所有可恢复检查点清除依赖旧文本的记忆；历史请求快照作为已发送证据保留。详情可展开当前分析，消息状态面板可读当时记忆。模型的分析不是确定性事实，不能用其存在证明剧情被正确总结。

此流程没有复刻 RUBY 面板的任意配置，也不读取原卡连接或越狱配置。当前未接入小白X外部摘要及“最后摘要位置”，分析读取整条当前对话。第二样本的引用别名有歧义，手工选择与原宿主差异见逐项审计；不能把功能接通算作整卡完成。
