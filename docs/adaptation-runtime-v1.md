# Native 内容适配边界 v1

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

当前不建设 `ProgramView`、AI compiler、repair、派生缓存或 Shelf 分发协议。真实模型只用于测试对话玩法；它不参与生成适配，也不拥有 Runtime 设计权。等 Native View、Versioned Conversation State、Legacy State Adapter 和验证边界稳定后，再单独设计 Android 导入期的自动适配流程。

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

key、definition 和 value 按稳定顺序编码；文本作为 JSON 数据转义，不经过 Character Macro 或 Regex。适配不能提供 Prompt 模板、role、插入位置、条件或动态计算。声明 Legacy Adapter 时，Player 还会生成固定的 assistant 状态回写契约；它只列出已校验 dialect、白名单路径和 scalar 类型。模型应在长剧情正文之前先输出并闭合一个机器状态块，没有变化时也用空块明确确认 no-op。在 OpenAI Responses 边界，Player-owned 的当前状态投影与回写契约共同占据顶层 `instructions`；卡片、World Book 与 Preset 的 system 内容保留原有时序，但以较低的 `developer` 权限发送，不能改写 Player 的当前事实或放宽可执行状态协议。

## Player 固定 Native View

### Status View

只读展示少量 scalar 状态。可选的 `min/max` 只允许用于 `NUMBER`，由 Player 决定列表与进度表现。

### Scene View

以一个 scalar State 的精确值选择一张已安装的本地静态图片。布局、缩放与空态由 Player 决定。

### Collection View

只读展示一个 `COLLECTION` 中具有同一声明 shape 的 records。列表、卡片、滚动和详情属于 Player UI，不进入适配。

### Form View

表单只有一个结果：把经校验的字段值投影为聊天输入框 Draft。Draft 需要用户确认后才作为普通 Conversation Turn 发送；提交表单不会直接写 State、修改历史、swipe、regenerate、调用模型或切换 World Book。

表单模板只接受 `{{form.field}}`、`{{user}}` 和 `{{char}}`。所有字段都必须进入 Draft，不能收集后静默丢弃。Form marker 只负责把固定表单附着到匹配的原始消息；它不是可订阅 Trigger。

## 本地静态 Assets

角色卡中的 `ccdefault:` 图片以及受支持的内嵌 `data:` PNG、JPEG、WebP 可以成为稳定 `assetId` 对应的 app-private 文件。导入执行媒体类型、字节数、边长和像素数限制；显示使用 Android Native Decoder 与有界采样，不使用 WebView。

远程图片、HTTP、cookie、凭据继承、SVG、脚本和动态资源加载不属于 v1。允许显示像素不等于允许执行或联网。

## World Book Domain Runtime

`WorldBookActivationOverrides` 是 Conversation Runtime 的 Player-owned 能力，不是通用 Adaptation action。当前有两个强类型 intent：

- `SetBookEnabled(bookId, enabled)`
- `SetEntryEnabled(bookId, entryId, enabled)`

执行器先验证全部 snapshot 引用，再原子返回新的 Runtime State；不存在通用 dispatcher。World Book Engine 读取有效覆盖并在 Prompt trace 中说明由 Conversation override 造成的启停。普通 Form 和 Legacy State Adapter 都不能调用这些 intent；正式 Setup 生命周期尚未建立。

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
- 固定 Status View；
- 自定义开局 Form → Draft；
- opening candidates 保留给现有 swipe；
- PNG 卡面作为可验证本地静态资产；
- HTML/CSS/JavaScript、宿主 API、自动发送、历史改写、动态 World Book 切换等明确标为降级或不支持。

该夹具用于淘汰 Player 设计，不用于从单卡反推通用 Runtime。
