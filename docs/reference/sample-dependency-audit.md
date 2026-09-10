# 原卡作者程序的依赖核查（三档口径）

> 状态：只读静态扫描 + 既有本地产物复核。本文记录“源码引用了什么、既有探测实际跑到过什么、哪些缺口可能阻塞交互”，不是兼容性结论，也不代表完整游玩验收。生成器不执行卡内程序、不联网、不请求模型。
>
> 生成器：`tools/web-runtime/audit-dependencies.mjs`（入库）；机器可读结果：`tools/web-runtime/build/dependency-audit.json`（忽略路径，可重复生成）。本文数字来自文末所列命令的实际运行。
>
> 原件只在本机 `source/` 只读解码；本文与结果文件只用样本编号和 SHA-256 追溯，不记录素材标题、人物名、作者名或素材文件名。

## 方法

### 三档定义

| 档 | 输出字段 | 判定方式 |
| --- | --- | --- |
| ① 源码里引用了 | `samples[].referenced` | 只读解码原件 → acorn 解析卡内作者 JS → 收集被调用的标识符/成员名 → 与兼容目录的 `playerBinding` 交叉引用 |
| ② 运行时执行了 | `samples[].runtime_evidence` | 读取既有本地产物中记录的宿主调用，逐条附 `origins`，统一标注“既有本地产物” |
| ③ 缺失会阻塞交互 | `samples[].blocking_gaps` | ①或②中出现、而绑定为 `absent` / `rejecting-stub` 的名字；`basis` 区分 `runtime-observed` / `runtime-observed-external` / `source-reference-only` |

第三档是风险候选而不是结论：`source-reference-only` 只由调用点推断、未逐条运行验证；`runtime-observed` 只在对应探测环境和快照中成立。第二档两项本地产物均早于本次核查，可能过期。

### 数据来源

| 用途 | 位置 | 说明 |
| --- | --- | --- |
| 原件 | 本机 `source/`（不入库） | 只读解码 PNG `tEXt` 的 `chara`/`ccv3` 块或 JSON；按 SHA-256 匹配编号 |
| 绑定状态 | `tools/web-runtime/compatibility-catalog.json` | 以 `domains[].entries[].playerBinding` 为准；别名依次回退到 `declarationFacade`、`runtimeRegistrations`、`iframeBoundRegistrations`，结果用 `catalogSource` 标记出处 |
| 运行时档 | `tools/mvu-probe/build/card-report.json`、`tools/card-probe/build/survey-v2.json` | 既有本地产物；缺失时对应证据为空数组，不编造 |
| 桥调用记录 | `tools/web-runtime/build/capability-calls.json` | 由 `test/browser/complex.test.mjs` 最近一次运行写入（本轮新跑），未合并进生成器 |

### 复查命令

```powershell
# 静态核查（只读，不联网）；结果写到 tools/web-runtime/build/dependency-audit.json
node tools/web-runtime/audit-dependencies.mjs source
# 写到别处对照
node tools/web-runtime/audit-dependencies.mjs source --out $env:PI_SCRATCH_DIR/dependency-audit.json
# 复核桥调用（需要本机原件与 Edge；原件缺失时用例 skip 且不写文件）
cd tools/web-runtime; npm run test:browser
```

### 计数口径

- `referenced` 一行对应一个 (样本, API 名)；`locations` 是记录到的引用位置数，包含 `typeof x === 'function'` 能力探测和重复的 Regex 模板，**不等于执行次数**。
- 行号相对提取出的 JS 块，不是原件文件行号。
- 扫描范围：`extensions.tavern_helper` 的脚本内容、`first_mes`/`alternate_greetings`/`mes_example` 与 `regex_scripts[*].replaceString` 里的 `<script>` 块（后者是卡内实际可运行 HTML 的存放位置）；`<script src=...>` 与非 JS 类型跳过，世界书正文不扫描。
- 卡内 enabled=false / disabled 的脚本与模板仍参与扫描，并在 `parse_warnings` 注明。
- 本地声明名按文件内绑定做保守过滤；与本地变量同名的自由全局会被一起过滤。

## 总表（本次实际运行结果）

运行 `node tools/web-runtime/audit-dependencies.mjs source`，匹配到 5 份已知原件（C-03…C-07）；C-01、C-02 本机缺失。合计 19 个 (样本, API) 引用行，全部 `binding-present`。

| 编号 | 原件 SHA-256（前 16 位） | 本机 | 引用 API 数 | 已绑定 | 拒绝桩 | 未提供 | 运行时证据 | 阻塞缺口 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| C-01 | `1945abd1e2368ec3` | 否 | — | — | — | — | — | — |
| C-02 | `b7cf04e3198ffc3a` | 否 | — | — | — | — | — | — |
| C-03 | `0d9f771474cab7f1` | 是 | 1 | 1 | 0 | 0 | 1 | 0 |
| C-04 | `fa7e8ec564887780` | 是 | 5 | 5 | 0 | 0 | 14 | 4 |
| C-05 | `7df0b58b2a46ac9a` | 是 | 0 | 0 | 0 | 0 | 0 | 0 |
| C-06 | `8f24972a97e9cb35` | 是 | 8 | 8 | 0 | 0 | 5 | 0 |
| C-07 | `0166ea69a6bdfa0e` | 是 | 5 | 5 | 0 | 0 | 4 | 0 |

`parse_warnings` 合计 2 条，均为“disabled 但仍参与扫描”的说明；acorn 语法解析失败 0 条。另有 1 份 JSON 原件（SHA-256 `68c9429e69a9c38d…`）不在 C-01…C-07 编号表内，只登记哈希、未扫描。

## 逐样本明细

用途一句话均从调用点上下文推断，未逐条运行验证。

### C-03（简单表单）

- 引用且已绑定：`errorCatched` —— 把父页面表单交互与确认回写包进统一错误捕获（`$(errorCatched(...))`）。
- 运行时证据：`errorCatched`（来源 `card-probe/build/survey-v2.json`，候选 `regex[0]`，1 次被阻断的资源请求）。
- 未解析的自由全局：`$`（jQuery 9 处）、`toastr`（2 处）。它们是页面自身依赖的第三方全局，不属于 Player API 缺口。
- 阻塞缺口：无。无外部 import，无 parse_warning。

### C-04（状态与动态集合）

- 引用且已绑定：
  - `getChatMessages`（3 处）—— 按楼层范围或单楼读取消息，用于回查历史楼层。
  - `getLastMessageId`（2 处）—— 取最新楼层号作为回查上界。
  - `getVariables`（2 处）—— MVU 数据缺失时读取指定楼层的变量（`stat_data`）作后备。
  - `Mvu` / `Mvu.getMvuData`（各 2 处）—— 通过 `window.Mvu` 读取指定楼层的 MVU 状态数据。
- 运行时证据（14 项，来源 `mvu-probe/build/card-report.json`）：`$`、`eventEmit`、`eventOn`、`getCharLorebooks`、`getChatMessages`、`getLastMessageId`、`getLorebookEntries`、`getLorebookSettings`、`Mvu.getMvuData`、`registerVariableSchema`、`setChatMessages`、`setLorebookSettings`、`substitudeMacros`、`updateVariablesWith`；另在 `survey-v2.json` 中观察到 `getChatMessages`、`getLastMessageId`、`Mvu.getMvuData`。
- 阻塞缺口（4 项，均为 `runtime-observed-external`，即卡内引用的外部 MVU 程序在 Node 研究宿主上实际执行到的调用；探测宿主不是 Player，产物也可能过期，按风险线索处理）：
  - `getLorebookSettings` / `setLorebookSettings` —— `absent`；用途：读取/写入世界书设置。
  - `registerVariableSchema` —— `absent`；用途：注册 MVU 变量结构（schema）。
  - `substitudeMacros` —— `absent`；用途：变量文本中的宏替换。
  - 说明：同一批证据里的 `getLorebookEntries` 曾是 `rejecting-stub`，本次核查之后已随[对话内世界书读写](../web-runtime.md)交付，不再计入缺口。
- 外部 import：MVU bundle 与 mvu_zod 两个远程地址各 1 处；内容未下载、未解析，上述运行时缺口正来自该 bundle 的实际执行路径。
- parse_warning：`regex_replace:0` 在卡内 disabled，仍参与扫描。
- 未解析的自由全局：`_`（lodash）、`$`（jQuery）、`z`（Zod）。

### C-05（动态界面）

- 引用且已绑定：无。静态扫描没有在这张卡的脚本块里解析到任何 Player API 调用。
- 运行时证据：无（既有 survey 对该原件两个候选也只记录到 0 调用）。
- 外部 import：MVU bundle 与 mvu_zod 两个远程地址（helper 脚本只做 import 与 Zod schema 声明）；bundle 内容不在本机扫描范围，其中可能调用 Player API。
- 未解析的自由全局：`_`、`$`、`z`。
- 阻塞缺口：无（但见“未被覆盖的部分”第 3 条）。

### C-06（复杂交互与状态重读）

- 引用且已绑定：
  - `waitGlobalInitialized`（2 处）—— 等待 MVU 全局就绪。
  - `eventOn`（4 处）—— 注册 `Mvu.events.VARIABLE_INITIALIZED` 与 `VARIABLE_UPDATE_ENDED` 监听。
  - `Mvu.events`（4 处）—— 事件常量命名空间。
  - `Mvu.getMvuData`（2 处）—— 读取 MVU 状态。
  - `getCurrentMessageId`（4 处）/ `getChatMessages`（2 处）—— 读取当前楼消息正文。
  - `setChatMessages`（4 处）—— 切换第 0 楼消息候选（开局选择）。
  - `updateTavernRegexesWith`（2 处）—— 改写当前角色的正则规则（解锁对应模板）。
- 运行时证据（5 项，来源 `survey-v2.json`）：`eventOn`、`getChatMessages`、`getCurrentMessageId`、`Mvu.getMvuData`、`waitGlobalInitialized`（候选 `regex[3]`…`regex[6]`，其中两段 40k 字符页面各记录到 101 次被阻断的资源请求）。
- 注意：`regex_replace:3` 与 `regex_replace:6` 是同一段界面 HTML，引用位置按模板重复计入。
- 外部 import：同一 MVU bundle 与 mvu_zod 地址共 3 处。
- parse_warning：`script:1` 在卡内 disabled，仍参与扫描。
- 未解析的自由全局：`_`、`$`、`Vue`、`z`。
- 阻塞缺口：无。

### C-07（状态栏与变量事件）

- 引用且已绑定：
  - `waitGlobalInitialized`（1 处）—— 等待 `Mvu` 全局就绪后初始化。
  - `eventOn` / `Mvu.events`（各 1 处）—— 监听 `VARIABLE_UPDATE_ENDED`，变量更新后重绘状态栏。
  - `getAllVariables`（1 处）—— 读取合并后的变量（`stat_data`）。
  - `errorCatched`（1 处）—— 包装入口初始化（`$(errorCatched(init))`）。
- 运行时证据（4 项，来源 `survey-v2.json`，候选 `regex[0]`）：`errorCatched`、`eventOn`、`getAllVariables`、`waitGlobalInitialized`。
- 外部 import：MVU bundle 与 mvu_zod 两个地址各 1 处。
- 未解析的自由全局：`_`、`$`、`z`。
- 阻塞缺口：无。

## 未被覆盖的部分

1. **运行时档全部来自既有本地产物**：`mvu-probe/build/card-report.json` 是 Node 研究宿主（无 Android、无模型请求、无 EJS 与原生渲染验证），`card-probe/build/survey-v2.json` 是模拟宿主 + 无网络浏览器的 2026-09-09 记录；两者都可能过期，`blocked` 是被阻断的资源请求数（含 favicon 等噪声），不是 API 失败数。这些证据只说明“该路径被执行过”，不说明 Player 行为。
2. **C-01、C-02 本机无原件，未扫描**。另有 1 份 JSON 原件（`68c9429e69a9c38d…`）不在 C-01…C-07 编号内，只登记哈希；该 JSON 与 C-03 的 PNG 解出同一份卡数据，不是独立样本。
3. **卡内引用的远程 bundle 未下载、未解析**：C-04…C-07 的 helper 脚本只 import 远程 MVU / mvu_zod 地址，bundle 内部的 API 使用只能由既有产物覆盖（目前仅 C-04 有此类证据）。因此“引用 API 数为 0”不等于运行时不依赖任何 API。
4. **静态扫描看不到的形态**：字符串拼接的 API 名、`eval`、computed 成员访问、运行时动态插入的 `<script>`、`<script src=...>` 外部脚本、`on*` 内联处理器（本轮按范围排除）以及世界书正文中的代码。
5. **`<script>` 提取使用正则**：脚本字符串内部出现 `</script>` 会提前截断。本轮样本未触发，但属于工具限制。
6. **disabled 模板仍计入引用**：C-04 与 C-06 各有一条 disabled 脚本/模板参与扫描，引用数可能高于实际运行路径。
7. **`unmatched_references` 不是缺口清单**：它只列出无法在兼容目录解析的自由全局名（`$`、`_`、`z`、`Vue`、`toastr` 等），既不代表 Player 缺少这些接口，也不覆盖 `window.foo` 形式的作者自建全局。
8. **桥调用记录范围有限**：`capability-calls.json` 只覆盖本次跑到的 4 张卡用例，且是桥方法级别（`ready`、`frame.create`，C-06 另有 `host.messages.set`、`host.regex.replace`），只证明这些桥方法被调用过。
9. **未执行**：真实模型请求、Android 真机/模拟器验收、外部 bundle 联网加载、事件参数契约与完整卡流程验证。

## 本次实际执行

| 命令 | 结果 |
| --- | --- |
| `node tools/web-runtime/audit-dependencies.mjs source` | 匹配 5 份原件；19 个引用行全部已绑定；4 个运行时阻塞缺口（均 C-04）；2 条 disabled 说明；连续两次运行输出字节一致 |
| `npm test`（`tools/web-runtime`） | 通过，0 失败、0 跳过（首次执行 30 项；仓库内其他并行改动加入新用例后重跑 40 项仍通过） |
| `node --test test/browser/complex.test.mjs` | 4 项通过；写入 `build/capability-calls.json`（4 卡各 `ready` + 2×`frame.create`，C-06 另有 `host.messages.set`、`host.regex.replace`） |
