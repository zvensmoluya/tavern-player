# Native 内容适配边界 v1

> 状态：现有过渡实现契约。它不是角色卡公共格式或创作者 SDK；后续收敛方向见 [`Tavern Player Native Adaptation 设计原则0903.md`](Tavern%20Player%20Native%20Adaptation%20设计原则0903.md)。

## 目标

Tavern Player 在导入边界理解社区卡依赖的 HTML、JavaScript、Tavern Helper 和宿主 API，将可恢复的可观察玩法表达为受限的 Native 适配产物。原始卡片始终按原始字节保存，适配产物可以删除、替换和重新生成。

```text
Original source (immutable)
        │
        ▼
ProgramView (minimal and redacted)
        │
        ▼
AI semantic compiler
        │
        ▼
AdaptationArtifact candidate
        │
        ▼
Deterministic validation
        │
        ▼
Native playback
```

AI 负责提出语义映射。确定性代码负责格式、能力、资源消耗和权限裁决。AI 输出本身始终是不可信输入。

## 原件与派生产物

- `sourceSha256` 标识不可变原件；适配产物必须绑定同一哈希。
- 非 `ALWAYS` marker、消息状态方言、路径、类型与初始值必须能回溯到同一原件确定性提取出的 `ProgramView`。
- 适配不会重写 PNG、JSON、Character Card extension 或原始 Shelf 文件。
- Player 创建 Conversation 时捕获当时采用的 Character 与 Adaptation snapshot，之后重新编译不改写已有对话。
- Shelf 可以为同一原件缓存不同 compiler/runtime 版本的派生产物，但运行时状态不属于 Shelf。

当前 Shelf 实现把 `program-view-v1.json` 与 `adaptation-v1.json` 保存在原件目录的 `derived/` 下，通过 SQLite 记录原件/派生物哈希与编译状态。角色卡传输 manifest 可携带可选 adaptation；Player 分别校验附件长度、哈希、严格 JSON schema 和 `sourceSha256` 后才挂载。

## ProgramView v1

`ProgramView` 只包含理解程序行为所需的信息：

- 会产生主动 HTML/CSS/JavaScript 的 Regex replacement；
- Tavern Helper 等 script container 中的程序正文；
- Regex trigger、placement 与启用状态；
- 可供产物引用的 World Book opaque handle、名称、大小和摘要；
- 从受支持变量规则中确定性提取的状态方言、点分路径、primitive 类型和初始值；
- 程序中观察到的宿主能力和变量引用；
- 远程依赖的去凭据 locator；
- 被省略叙事字段的名称、字符数和摘要。

它默认不包含 Character description、personality、scenario、opening、examples、system prompt、post-history prompt、creator notes 或 World Book 正文。唯一的 World Book 例外是可识别的变量协议：`[InitVar]` JSON 只保留叶子节点第一个 primitive，丢弃说明文本；变量更新规则只保留方言、变量名和合法路径，不把规则正文交给模型。

确定性提取器在模型调用前处理：

- URL query、fragment 和 user-info；
- bearer token 与常见 credential assignment；
- Windows/macOS/Linux 用户目录路径；
- inline data URI。

远程 URL 在程序正文中替换为 `dependency://<id>`。依赖表保留去凭据、去 query/fragment 的地址，供模型识别依赖类型；该地址不会因此获得联网权限。

## AdaptationArtifact v1

当前 capability 白名单：

| Capability | 含义 |
| --- | --- |
| `ui.native` | 渲染有界 Native 组件树 |
| `chat.setDraft` | 由用户提交 Native 表单后设置聊天草稿 |
| `state.ingest` | 从 assistant 原始回复中的受限操作方言写入声明过的适配状态 |

当前 UI 节点：

- `SECTION`
- `TEXT`
- `STATUS`
- `FORM`

当前表单字段：

- `TEXT`
- `MULTILINE_TEXT`
- `NUMBER`
- `SINGLE_SELECT`
- `MULTI_SELECT`
- `TOGGLE`

当前动作：

- `CHAT_SET_DRAFT`

旧 schema 中的 `STATE_SET`、`STATE_INCREMENT`、`STATE_TOGGLE` 枚举值仍可被解码，但校验与执行都会拒绝。Native Form 默认只生成待用户确认的聊天草稿，不能脱离消息时间线直接修改状态。

模板只能读取当前表单和已声明状态：

```text
{{form.name}}
{{state.affection}}
{{user}}
{{char}}
```

首个消息状态方言为 `UPDATE_VARIABLE_SET_V1`。Artifact 必须逐项声明 `sourcePath -> target state key` 映射；专用 `UpdateVariableSetV1Adapter` 只在完整 `<UpdateVariable>...</UpdateVariable>` 块内接受单行 `_.set('点分路径', oldScalar, newScalar)`，并且 new value 只能是字符串、有限数字或布尔值。Adapter 输出受控 `ConversationStatePatch`，由 Conversation Runtime 一次应用。这里的 `_.set` 只是沿用社区卡已有文本协议的语法外形：Player 不解释 JavaScript，也不执行函数、对象、数组、表达式、未知路径或块外文本。

当前选中候选的 scalar Conversation State 会由 Player 按 key 排序、编码为固定 JSON system projection，并进入下一轮 Prompt。Artifact 不能提供 Prompt 模板、role、插入位置或条件表达式。

不支持任意表达式、函数、循环、递归、动态 capability、文件、网络、DOM、WebView、反射或代码加载。

## 确定性验证

候选产物在进入仓库与进入 Player 时都必须验证：

- schema version 与原件 SHA-256；
- capability 白名单及声明完整性；
- state 初始值类型；
- view、node、field 和 state ID 的唯一性；
- trigger、文本、模板、节点、字段、选项和动作数量上限；
- state binding 与 template reference 可解析；
- 消息状态路径、映射数量、目标类型与 capability 最小声明；
- URL、data URI 和本地资源 URI 不得出现在可执行产物中。

校验失败的候选不会部分执行。模型认为存在但 v1 不支持的行为必须进入 compatibility report，而不能创造新 operation。

## v1 真实样本

首个纵切使用社区卡中常见的 `<GAMESTART/>` / `<GAMESTRAT/>` 开场表单：HTML 表单收集字段，原脚本将格式化文本写入 SillyTavern textarea。适配结果将其恢复为 Compose 表单和 `chat.setDraft`，不运行 jQuery、DOM 操作或原始脚本。

第二个纵切是 `<StatusPlaceHolderImpl/>` 状态栏：Shelf 从 `[InitVar]` 和 `<UpdateVariable>` 规则提取最小状态协议，AI 将 HTML 状态栏映射为 Native 文本与 `STATUS` 进度条，Player 从 assistant 原始回复摄入白名单更新。Native attachment 接管该字面 marker 后，同入口的 Character HTML replacement 不再参与显示投影，叙事正文仍保留。状态随 Conversation checkpoint、regenerate 和 swipe 分支恢复；远程变量脚本、DOM、样式和折叠交互仍不执行。
