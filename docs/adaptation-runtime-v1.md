# Native 内容适配边界 v1

> 状态：已开始实现的内部契约。它不是角色卡公共格式或创作者 SDK。

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
- 适配不会重写 PNG、JSON、Character Card extension 或原始 Shelf 文件。
- Player 创建 Conversation 时捕获当时采用的 Character 与 Adaptation snapshot，之后重新编译不改写已有对话。
- Shelf 可以为同一原件缓存不同 compiler/runtime 版本的派生产物，但运行时状态不属于 Shelf。

## ProgramView v1

`ProgramView` 只包含理解程序行为所需的信息：

- 会产生主动 HTML/CSS/JavaScript 的 Regex replacement；
- Tavern Helper 等 script container 中的程序正文；
- Regex trigger、placement 与启用状态；
- 可供产物引用的 World Book opaque handle、名称、大小和摘要；
- 程序中观察到的宿主能力和变量引用；
- 远程依赖的去凭据 locator；
- 被省略叙事字段的名称、字符数和摘要。

它默认不包含 Character description、personality、scenario、opening、examples、system prompt、post-history prompt、creator notes 或 World Book 正文。

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
| `state.write` | 在 Conversation transaction 内修改声明过的适配状态 |

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
- `STATE_SET`
- `STATE_INCREMENT`
- `STATE_TOGGLE`

模板只能读取当前表单和已声明状态：

```text
{{form.name}}
{{state.affection}}
```

不支持任意表达式、函数、循环、递归、动态 capability、文件、网络、DOM、WebView、反射或代码加载。

## 确定性验证

候选产物在进入仓库与进入 Player 时都必须验证：

- schema version 与原件 SHA-256；
- capability 白名单及声明完整性；
- state 初始值类型；
- view、node、field 和 state ID 的唯一性；
- trigger、文本、模板、节点、字段、选项和动作数量上限；
- state binding 与 template reference 可解析；
- URL、data URI 和本地资源 URI 不得出现在可执行产物中。

校验失败的候选不会部分执行。模型认为存在但 v1 不支持的行为必须进入 compatibility report，而不能创造新 operation。

## v1 首个真实样本

首个纵切使用社区卡中常见的 `<GAMESTART/>` / `<GAMESTRAT/>` 开场表单：HTML 表单收集字段，原脚本将格式化文本写入 SillyTavern textarea。适配结果将其恢复为 Compose 表单和 `chat.setDraft`，不运行 jQuery、DOM 操作或原始脚本。

状态栏与变量更新是紧随其后的样本。它要求把状态纳入 Conversation checkpoint，并明确 assistant output 到 state 的提取事务；在语义确定前不通过临时脚本逃生口实现。
