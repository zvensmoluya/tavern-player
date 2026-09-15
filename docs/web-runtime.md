# 网页消息区与原程序运行契约

更新：2026-09-10。运行配置为 `player-web-1`。这是声明范围内的兼容宿主，不是完整 SillyTavern 或 Tavern Helper。默认新建对话使用网页模式；已有记录缺省解释为旧 Native 模式，切换模式必须新建对话。

## 执行与状态归属

Compose 保留导航、模型与预设选择、输入栏及编辑确认弹窗。一个 WebView 显示消息列表、Markdown、富 HTML 和消息操作。展示使用现有 DISPLAY 投影；原始正文、Prompt 投影和展示内容分开保存，DOM 不自动回写正文。

普通 Markdown/HTML 经 DOMPurify 清理，禁止作者脚本和事件属性。包含完整 `<body>…</body>` 的围栏代码块（标记为 html 时也接受省略 `</body>` 的合法 HTML）在消息 COMPLETE 后作为作者页面装载；其他代码块保留为代码。流式预览每 50 ms 至多一次；收到结束事件后继续接收用量，最终正文、状态与用量事务保存成功才发布 COMPLETE 并装载作者页面。原生首次发送完整快照，后续发送变更消息、顺序与变更状态。首次显示最近 50 条，点击每次向前加载 50 条。已挂载页面保留至该段页面源码或候选改变、历史被截断、切换对话或退出页面；同一条消息里其他文字或思考内容变化不重建它，滚动和追加新消息也不重新执行旧页面。消息行按段维护：标题、思考、各段正文与状态文案各自独立更新，未变化的段不触碰；已挂载段按契约重建时，新页面先带上旧页面最后实测的高度，读者看到的文档高度不先掉再涨。状态文案（正在生成/已停止/已中断/生成失败）与状态同步改写，非 COMPLETE 的页面段只显示占位文案并随状态改写，COMPLETE 后占位与状态文案一并移除。含表格、图片或内联 HTML 的正文段落在 static 帧里渲染，与主壳共用 `src/message.css` 的正文排版基线，作者自己的内联声明与 `<style>` 块仍然覆盖基线。

`BrowserProgramReader` 直接读取角色与当前预设的 `extensions.tavern_helper` 对象或键值对数组，遍历 `scripts` 目录，保留原文、来源 JSON pointer、启用状态和 SHA-256。它不经过 Native 编译器、模型筛选或裁剪。程序语法由 Acorn 解析，解析不执行源码。

角色卡缺少新容器时，也读取历史 `TavernHelper_scripts`（脚本包装项、文件夹和直接脚本项）及
`TavernHelper_characterScriptVariables`。遵循上游迁移优先级：新容器即使为空也不与旧数据合并；
旧脚本未声明启用状态时默认为停用，文件夹本身不提供启停。读取不重写原件，来源位置仍指向旧字段。

页面和后台脚本由 WebView 执行。后台脚本归属当前对话页面，不依附滚动可见性。切换预设后，已开始的生成使用捕获配置；生成结束后撤销旧预设脚本并加载新脚本。后台脚本可以提供按钮，不提供任意扩展设置界面或完整 ST DOM。

独立的 MVU 加载语句与登记的 `mvu_zod` Schema 模块由已有 QuickJS MVU 宿主处理。原加载器不再进入 WebView，避免同一回复执行两次 MVU 更新。混合 MVU 加载器与其他副作用的脚本明确停用；多份 Schema 或不能在无 DOM 宿主装载的 Schema 不自动拆分。世界书中的 EJS 原模板按来源哈希登记，使用已有只读 QuickJS EJS 引擎；触发、Regex/Macro、求值缓存与预算继续走 PromptCompiler，无需模型适配产物。

已登记的 MagVarUpdate jsDelivr 加载地址同时接受无 ref 和 `@master`；2026-09-12 核实后者返回的发布文件
与锁定提交相同，见 [C-08 依赖核查与接入验证](archive/legacy-mvu-dependency-audit-20260912.md)。
执行仍使用随应用打包的固定 MVU，不运行期跟随 master，也不把任意 ref 视为相同版本。

## 接口范围

`errorCatched(fn)` 可包装同步或异步初始化函数：正常时保留参数、返回值和同步/异步行为，失败时显示诊断并继续抛出原错误。支持作者页面常用的 `$(errorCatched(...))` 启动方式。

参照提交和文件记录在 [upstream-contract.json](../tools/web-runtime/upstream-contract.json)。Tavern Helper 基线为 `3de7ef981f378517779eb32ab5ecb82c033e4db4`，ST 基线为 `8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8`。MVU、Schema 辅助库和 EJS 使用已有 [公共程序锁](../tools/mvu-probe/upstream-lock.json)。浏览器库由 npm lock 固定，构建产物附带许可证和文件哈希清单。

| 能力 | 当前支持 | 明确限制 |
| --- | --- | --- |
| 消息读取 | `getChatMessages` 的楼层、范围、角色、隐藏过滤及候选读取；当前/最后消息身份 | 只读取当前对话，不提供其他会话访问 |
| 消息修改 | `setChatMessages` 修改正文、身份、候选数组、变量、附加数据和隐藏状态；新增 `createChatMessages` / `deleteChatMessages` / `rotateChatMessages` | 结构变化需要 affected/all 刷新；正文按字面量保存，不调用用户编辑的 Macro/截断流程 |
| 变量 | `getVariables`、`replaceVariables`、`updateVariablesWith`、`insertOrAssignVariables`、`insertVariables`、`deleteVariable`；chat、message、character、当前 script 作用域；`getAllVariables` 聚合读取 | character 修改当前对话角色快照；独立 global、预设资产、其他脚本作用域读写尚未接入；单份变量最多 1 MiB |
| 事件 | 注册、注销、一次性监听、顺序调整、自定义异步事件；消息、候选、渲染、生成和 MVU 更新事件 | 不提供完整扩展事件集合；同一作者会话内支持跨页面同步 `eventEmitAndWait`，不访问其他对话 |
| 生成 | `generate` 使用当前连接和预设；`generateRaw` 接受显式 role/content 数组；生成 ID、流式事件与停止 | 不接受 custom_api、凭据、任意 Provider、工具、图片或注入/覆盖参数；raw 不接受内置 marker 名称 |
| MVU | 初始化、完整回复更新、候选检查点读取和直接替换 | 替换保留原 schema；不提供完整扩展编辑器/设置、跨引擎共享闭包 |
| EJS | 已登记原世界书模板的只读求值 | 页面私有状态须先通过宿主保存；未完成初始化导致缺少变量时明确失败 |
| 世界书 | `getWorldbook` 与旧版 `getLorebookEntries` 读取；条目读写 `createWorldbookEntries` / `updateWorldbookWith` / `replaceWorldbook` / `deleteWorldbookEntries` 及旧版 `setLorebookEntries` / `replaceLorebookEntries` / `createLorebookEntries` / `deleteLorebookEntries` / `updateLorebookEntriesWith`；书级 `getWorldbookNames` / `getCharWorldbookNames` / `getCharLorebooks` / `rebindCharWorldbooks` / `createWorldbook` / `createOrReplaceWorldbook` / `deleteWorldbook`；`setWorldbookEnabled` / `setWorldbookEntryEnabled` 启停 | 只改当前对话快照与本次对话的世界书意图，不改变其他角色资产；启停与改写属于会话、不随候选回退；没有全局与聊天文件作用域（`getGlobalWorldbookNames` 返回空、`rebindGlobalWorldbooks` 明确拒绝）；不提供世界书引擎设置（`getLorebookSettings` / `setLorebookSettings`）；条目 `filters` 与 `automation_id` 不支持 |
| Regex | `getTavernRegexes` / `replaceTavernRegexes` / `updateTavernRegexesWith` 管理当前角色规则；旧 scope=character/all 可用 | 全局/预设规则、任意角色资产、同步格式化及 Macro 回调未接入 |
| 页面辅助 | 共享对象初始化/等待、脚本身份、按钮、toastr 诊断、有限父页面 | 父页面仅提供 `#send_textarea` 与 `#send_but`；不提供 ST 内部模块/播放器工具栏 |

消息与后台脚本共用一个持久的作者会话协调 iframe；它和作者内容同源、和可信消息外壳不同源，不持有原生桥。同步读取来自统一的 JS 会话视图。同步写入立即更新待提交视图并排队保存，生成与异步消息修改排在此前写入之后。`updateVariablesWith` 保留同步/异步回调对应的返回类型。每次提交携带运行实例、候选身份、状态版本和请求 ID；Kotlin 验证后计算原子提案，保存成功才发布或确认。重复请求不会重复执行；旧候选、已销毁页面、过期状态或忙碌会话拒绝提交。一次存储失败停止整个网页运行实例，恢复已保存视图并提供重试入口。

2026-09-10 起，变量接口按参照源码 `dd8327d437eb0cdbd1eddc23b5491812870aa7d8` 补齐：角色/预设助手容器中的 `variables` 与脚本 `data` 原样保留在程序快照；脚本尚未保存过变量时读取自身 `data`，显式写入空对象不会重新回退到初值。`getAllVariables` 在消息页面按角色初值 → 聊天变量 → 第 0 楼至当前楼层的选中候选变量浅合并，返回独立副本，不读取未来楼层。Player 没有应用级全局变量仓库，该合并层为空。脚本上下文按角色初值 → 当前脚本变量 → 聊天变量合并；参照实现只在消息 iframe 分支合并楼层，虽然其类型注释描述了更大的脚本范围，本次遵循实现。它不等价于只读取最新消息，也不访问其他对话。

`insertOrAssignVariables` 深合并并覆盖旧值，`insertVariables` 深合并但保留已有值，数组均整体替换；`deleteVariable` 沿用 Lodash 路径及 `unset` 返回语义（路径本已不存在也可返回 true）。这些辅助函数继续使用同一保存队列和候选检查点，没有另建状态仓库。

## 兼容推进与样本验收（2026-09-10）

后续推进已转为[完整能力域及依赖顺序](reference/helper-compatibility.md)，逐项清单见[兼容目录](../tools/web-runtime/compatibility-catalog.json)。下表保留上一轮变量改动和样本验证的实际范围，不作为按样本补 API 的开发顺序。会话协调器现已接入生产宿主；以下变量/样本表格保留此前一轮的范围，新增运行环境交付见本节后文。

实现路线是“作者原程序 → 酒馆助手兼容接口 → Player 业务操作”，页面计算与 DOM 留在浏览器。接口名、参数、默认值、返回类型、事件次序和保存语义一起验收；未接入能力是兼容缺口，不自动归因于安全限制。

| 能力组 | 本次进展 | 后续缺口 |
| --- | --- | --- |
| 初始化与父页面表单 | C-05 原 HTML 表单在生产网页外壳/父页面脚本下完成输入栏回写，刷新保留字段 | Android 原卡完整发送/生成链路与真机输入法仍需验证 |
| 变量 | 聚合读取、深合并/补缺/删除、角色与脚本初值 | 其他作用域的实际存储归属与接口 |
| 消息与事件 | 保留已有接口，本地复杂样本词法扫描发现实际引用 | 消息修改后的页面更新、跨页面事件及完整卡流程验收 |
| 世界书与 Regex | 世界书读取/启停保持当前实现；发现复杂样本引用 `updateTavernRegexesWith` | 世界书写入、Regex 修改及其 Prompt/显示生效链路 |
| MVU/EJS | 保留当前单宿主路径 | 更广加载形式的执行归属与重复更新验证 |

C-05 JSON SHA-256 为 `68c9429e69a9c38d8e8b79cace03675c99ca48830ed25a49ac89ce61dda961a9`。可选测试 `test/browser/community.test.mjs` 按哈希定位本地原件，直接加载第一个 Regex 替换页面，不复制或重写源码；填写表单、点击确认后验证共享草稿、没有自动发送、页面刷新保留输入。它使用生产 JS 和模拟原生桥，不执行 Android Regex/Macro 投影或真实模型生成，因此不代表完整游玩验收。无原件时明确跳过；摘要保存到忽略路径 `tools/web-runtime/build/community-form-audit.json`，不包含原文或文件名。

本地六份 JSON/PNG 原件的词法扫描摘要位于忽略路径 `tools/web-runtime/build/local-capability-audit.json`，只记录哈希及疑似调用名，不代表所有分支执行过，也不能证明无外部依赖。此次已执行的 JS 契约测试为 16 项、Edge 集成测试为 2 项，均通过，包括 C-05 原件；核心验证执行 `:content-core:test :conversation-core:test` 并通过。

本次另执行 `:app:testDebugUnitTest :app:assembleDebug`，应用单测与 Debug APK 构建成功。核心及应用共报告 425 项、失败/错误 0、跳过 20 项（沿用各可选测试条件）；不把跳过项计为通过。当前 `adb devices` 无连接设备，未执行 Android instrumentation 或真机原卡发送/生成验收。

有限父页面与作者内容同属不可信区域，使用不同于可信消息外壳的来源。只有可信主框架能调用 WebMessageListener。作者内容可以修改自己的兼容父页面，但不能读取原生桥、播放器 DOM、文件路径或模型凭据。WebView 禁止文件/content URI、弹窗、设备权限、Worker 和 Service Worker；CSP 配合取得层限制资源协议与请求方法。作者页面可以使用 `localStorage` / `sessionStorage`：每个会话用不同来源装载，存储因此按会话隔离，同一会话重新打开仍能读回；这些内容不属于会话记录，导出与迁移不携带，容量由 WebView 配额限制。

## 会话级作者运行环境（2026-09-10）

`session.mjs` 由持久作者协调 iframe 持有，页面的 `host.mjs` 只绑定身份、接口和传输入口。外壳先等待协调器就绪，再加载消息页面与脚本。原生事实事件只发送给协调器一次，不再逐页面广播后重复执行全局监听器。

- `initializeGlobal(name, value)` 发布实际对象和函数；作者页面及其兼容父页通过 getter 访问同一对象。`waitGlobalInitialized` 支持先等待后发布，以及后来创建的页面；MVU 已保存状态仍可唤醒现有原生 facade。
- `eventOn`、`eventOnce`、`eventMakeFirst/Last`、`eventOnButton` 及清理操作使用会话级注册表。清理仅移除调用页面拥有的监听器。`eventEmit` 按顺序等待普通异步监听器；`eventEmitAndWait` 同步调用、不等待 Promise。对照登记的 ST EventEmitter，once 在回调前移除，其异步返回不阻塞后续监听；异常报告后继续后续监听器。
- 所有作者页面共用待提交视图、状态版本和保存队列。跨页面立即写后读可见，不再各自携带独立队列的旧版本。保存失败停止所有作者写入，恢复同一已保存视图。
- 页面销毁注销监听、拒绝未完成等待、撤销该页面发布的全局注册并取消未提交操作；正在等待原生结果的传输 Promise 也被释放，避免阻塞其他页面。已保存结果仍以原生快照为准。其他作者显式持有的旧对象引用无法自动撤销，不宣称能清空任意闭包。
- 候选身份变化在新快照到达时先使旧页面失效；预设脚本继续保留到当前生成结束，再销毁旧实例、加载新实例。滚动、追加消息不销毁协调器。

这批完成生产协调器、共享对象、初始化、事件、共享队列和清理；不等于设计中 A 的全部内容已完成。完整 SillyTavern Context、脚本管理接口、完整事件集合和更广 MVU/EJS provider 归属仍未交付。

本轮 JS 契约测试 26 项和 Edge 测试 3 项通过。生产网页集成覆盖双页面同步回调、原生事件不重复、销毁清理、预设生成中保留/结束后替换和原表单回归；独立架构证明仍保留。新增 Android 测试 `authorPagesShareObjectsAndSynchronousEventsThroughProductionSession` 通过编译但未执行：当前无连接设备，SDK 未安装 emulator。应用单测、Debug APK 和 Android 测试 APK 构建通过；设备上的同源 sibling 访问、生命周期及原卡完整生成流程仍需验收。

## 键盘避让与视口同步（2026-09-11）

键盘弹出时消息区会被真正压缩，而不只是把输入栏盖住的界面效果：底部输入栏消费一次 `WindowInsets.safeDrawing` 的底部与水平方向 inset（键盘在场取 IME 高度，键盘不在让开导航栏与显示缺口），底部条因此变高，Scaffold 随之把消息区（WebView）压到键盘之上。原生侧不再对键盘另加 padding，避免同一方向上重复让开。这样浏览器一侧的视口高度与可见高度一致，聚焦在作者页面里的输入框由浏览器自己的滚入可视区处理。

- 窗口策略：API 30 起调用 `enableEdgeToEdge()`，系统栏图标固定为深色（应用只有浅色配色，默认样式会跟随系统深色模式切白图标），避让统一交给 Compose 的 WindowInsets；API 26–29 保留系统 `adjustResize` 收缩窗口的旧路径，因为关闭 decor 适配后更低版本无法可靠上报 IME inset，反而会让输入栏被键盘盖住。manifest 显式声明 `android:windowSoftInputMode="adjustResize"`。
- 输入栏：发送／停止与输入框同一行，输入区最多 5 行，键盘占去高度时按钮不会被挤出可见区。原生输入栏与 WebView 的焦点切换交给 Compose 的 AndroidView 互操作处理；不要在 WebView 获得焦点的回调中调用全局 `clearFocus()`，它会连 WebView 自身的焦点一起清掉，导致作者字段无法接收键盘输入。
- 网页侧：WebView 变矮触发 `resize`，主壳用变化前的底部偏移判断读者是否本来贴底（直接按新几何重算会把原本贴底的读者判成已上滚），再把新的可见高度广播给所有作者帧；作者帧把 CSS 的 `min-height:<n>vh` 折算成 `calc(<n> * var(--player-frame-vh))`，收到广播只更新该变量，纯 CSS 生效、不重建页面。焦点位于作者帧内时暂停自动贴底，把滚入可视区让给浏览器；焦点离开后下一次渲染恢复跟随。三处 viewport meta 加 `interactive-widget=resizes-content`。

2026-09-12 已在 Android 15 / API 35 模拟器、WebView 151.0.7922.202 和 Gboard 完整键盘上复验：原生输入栏与发送按钮让开键盘，网页可见高度从 646 CSS px 缩到 334 CSS px；移除上述清焦点回调后，C-03 作者字段可接收真实键盘输入，并随焦点滚入可见区；原生输入栏与作者字段来回切换时各自保留文字。详见[模拟器聊天实测](archive/device-chat-verification-20260912.md)。实体设备、其他 Android/WebView 版本仍需验收，未单独证明 `interactive-widget` 对该结果的贡献。

## 消息与角色规则操作（2026-09-10）

- 消息读取按参照实现截断越界端点、排序反向范围，非法范围返回空列表；默认结果保留旧版候选字段。消息修改合并指向同一楼层的更新，接受负索引、名称/角色和候选附加数据；候选数组按最长输入补齐，选中编号截断到有效范围。无效楼层按参照实现忽略，其余非法参数整批拒绝。空候选数组明确拒绝，避免无法选中任何消息。
- 新增消息支持 `insert_before` 和旧别名 `insert_at`、负位置、默认身份及初始变量/extra；删除支持负索引、去重和忽略越界项；rotate 按半开区间交换两段。结构变化保留消息身份和仍有效的网页实例，重新编号但不重跑无关作者页面。结构操作 `refresh:none` 按参照 managed surface 路径明确拒绝。
- 结构操作保存后才确认，不调用模型，不重放 MVU，不回退当前会话状态。新建普通消息的显式变量独立于沿用的会话 MVU 检查点，空对象也是真实值。新增、移动、删除和候选附加数据均覆盖序列化恢复。
- character 变量写入当前对话的角色快照；与 chat 检查点独立，切候选不撤回角色配置。没有修改资产库里的原件，也没有建立应用级 global 存储。
- 角色 Regex 经助手字段映射写入实际 `RegexDefinition`，由原有 Prompt/Display 引擎执行。保存先完成，显示刷新按上游延迟 1 秒合并，允许作者继续执行消息操作；相同规则回写不重复安排刷新。原始正文不因此改写。全局与预设规则仍未支持；scope=all 在当前仅有角色规则的配置下读取该集合，不能写入标记为 global 的规则。
- 原生快照与操作回复共用一份消息事件事实视图，避免同一次保存重复发送消息事件；新增 user 消息提供 `MESSAGE_SENT`。完整上游事件集合及所有载荷时机仍不在此次覆盖范围。
- 历史楼层的消息变量宏按该楼层选中候选取值：`get_message_variable` / `format_message_variable` 的展示投影复用网页楼层 API 同一条候选变量读取路径（候选显式变量或候选最新 MVU 检查点），不再用会话当前 head，因此同一条历史消息在原生气泡、网页气泡与作者页面读到的值一致。候选显式保存的空变量是真实值（含有关联运行状态但缺少 `stat_data` 的情况），按既有「缺失值输出 null」处理，不回退到会话当前变量；候选完全没有变量来源时保持原路径。提示词侧的同类宏仍按当前运行状态投影：显示按楼层、提示词按当前，这个分叉是有意的。

`test/browser/complex.test.mjs` 按原件哈希读取四个复杂 PNG 样本的原始页面，验证开局候选选择、五次点击后改 Regex 并选择另一个开局、状态面板、标签切换及省略 body 结束标签的 HTML。测试使用生产网页外壳及接口；桥接保存模拟，初始变量由原件 initvar YAML 构造，装饰媒体/字体不加载；不把它宣称为完整 Android 或真实模型验收。另有 Kotlin 核心测试验证实际存储模型与 Display/Prompt 分离，应用测试验证真实保存、延迟刷新与后续消息操作。

`get_message_variable` / `format_message_variable` 现在也读取 `stat_data` 下的点路径和数字数组下标；精确对象键优先，缺失值输出 null，继续过滤私有字段。MVU QuickJS 宿主的 `substitudeMacros` 在解析初始化 YAML 和回复更新前替换当前 `user` / `char`，避免未展开占位符被 YAML 当作复杂映射键。其他 ST 宏并未因此全部接入。

原生测试 `localComplexOriginalsPrepareCompileAndAcceptACompletedReply` 按哈希读取四份原件，执行真实导入与程序准备、MVU 初始化、显示投影和 Prompt/EJS 编排，再用确定性 JSONPatch 回复修改一个数值字段，保存恢复检查点并编排下一轮。它不请求真实模型，也不执行 Android WebView；浏览器测试和原生测试是两侧互补证据，不等于已在手机上完成所有剧情分支。

本轮验证：`tools/web-runtime` 的 `npm test` 30 项、`npm run test:browser` 7 项通过；`tools/mvu-probe` 的 `npm test` 12 项通过。`gradlew.bat :conversation-core:test :app:testDebugUnitTest :app:assembleDebug` 成功：核心 149 项无跳过，应用 202 项中 17 项沿用可选条件跳过，其余通过。新增四原件测试实际执行，没有跳过；旧 C-04 专用 EJS 夹具测试仍因其独立生成夹具缺失而跳过。APK 内 Web/MVU 资产与当前生成 bundle 一致。没有执行本轮 Android 设备或真实模型请求验收。

后续仍缺提示词注入及完整生成钩子、预设/global 变量、完整 ST Context/脚本管理、世界书引擎设置与全局及聊天文件作用域，以及 MVU/EJS provider 覆盖。这些未完成项保留在能力目录中，不以单张卡的测试结果替代契约验收。

## 对话内世界书读写（2026-09-10）

世界书内容写入落在当前对话的 `CharacterSnapshot` 上：会话记录是这场对话世界书条目的唯一所有者，写入不触碰角色资产，也不建立第二份资产库。会话里的世界书因此可能与该角色新建对话时捕获的快照不同，这是有意的对话隔离，不是缓存不一致。

玩家意图与剧情派生状态分开归属：条目启停、书级参与方式和正文改写存在会话级的 `worldBookState`，**不随消息候选切换或回溯回退**；sticky / cooldown 等由剧情派生的运行状态继续跟随候选检查点。这取代了此前把两者放在同一个 runtimeState、导致切候选会回退玩家开关的行为。

- 条目身份：卡内条目沿用原 `uid`（保存在 `sourceId`），作者新建的条目由 Player 在书内分配 uid 并持久化。新 uid 取书内已用最大值之后的下一个，删除后该值可能被再次使用，与上游的选取方式一致。删除条目时一并回收它的激活覆盖与 sticky / cooldown / delay 跨轮状态。uid 在书内不唯一（例如卡内条目缺少 id 且与另一条目的数字 id 相撞）时，按 uid 的写入与删除明确失败，不会命中任意一条或一次删掉多条。
- 两种形状：新版嵌套结构（`strategy` / `position` / `recursion` / `effect` 分组）与旧版扁平结构是同一份定义的两个投影，不是两份数据。读出后原样回写不改变条目含义：`selective` 与激活策略互不牵连，深度插入与身份按当前值还原，outlet 条目在旧版结构里没有对应值因此保持原样，`case_sensitive` / `match_whole_words` 的 `same_as_global` 视为未提供。写入次要关键字逻辑时会一并清掉卡内原始的 `selectiveLogic` 扩展，否则写入不会生效；`extra` 在其余字段之前落盘，因此「读取 → 修改 → 回写」不会被读出的旧扩展覆盖。`sticky` / `cooldown` / `delay` 的显式 `null` 表示清除，与「未提供」区分开。条目的 `filters` 非空或 `automation_id` 非空时明确报错，不静默丢弃。
- 书级操作：新建的书先不参与编排，要由 `rebindCharWorldbooks` 或 `setWorldbookEnabled` 显式启用。`rebindCharWorldbooks` 在本次对话的书集合内同时决定参与项与顺序，不新增或删除书。Player 没有独立的世界书注册表，书的集合与参与状态都属于这场对话，这与上游“全局世界书目录 + 绑定”的结构不同。
- 书级三态：「不参与」（`setWorldbookEnabled(name,false)`）／「自动」（默认，按作者规则）／「必定生效」（`worldbook.books.force`）。必定生效使该书**当前已启用**条目按常开处理并跳过概率，启停、互斥分组、预算、顺序与位置照常——因此它不会把成对的默认关闭变体一起塞进提示词。这是 Player 自己的产品能力，ST 没有按书强制激活的概念。
- 正文改写留痕：条目正文被改写（界面上编辑或作者程序写入）时记下改写前的原文，会话内据此显示「已改过」并提供恢复（`worldbook.entries.restore`，可按条目或整本书）。恢复只动正文，不影响启停与必定生效；把内容改回原文会让留痕自动消失。恢复入口需要向用户说明：作者的设计可能已经被改动破坏，恢复只还原正文、不还原剧情影响。
- 条目内容变化后的 EJS 模板：模板引用按登记时的原文哈希校验。内容被改写后，该模板不再执行，而是记一条 `STALE_EJS_TEMPLATE` 警告并继续本轮编排；这取代了此前“整张卡编译失败、这场对话无法再生成”的行为。改写后内容仍含 `<%` 的条目不注入模板源码，避免把无法执行的片段塞进提示词；作者把它改成普通文字后，该条目按普通世界书条目正常进入下一轮提示词。已安装的 Native 适配仍按来源哈希保持严格失败。
- 提交与原子性沿用变量写入：一次提交保存成功后才发布，失败停止整个网页运行实例。`getLorebookSettings` / `setLorebookSettings`、全局与聊天文件作用域保留为兼容缺口，不返回假成功。

兼容目录已按本机 helper 检出 `dd8327d4` 重生成，`audit-contract.mjs --check` 通过；世界书域相关名称的状态随之更新。

验证：`conversation-core` 新增 `BrowserWorldBookTest`（15 项），覆盖写入后按关键字进入下一轮提示词的实际内容、未命中时不注入、角色资产与另一场对话不受影响、序列化恢复、uid 分配与删除后的状态回收、条目级启停按对外 uid 寻址、旧版扁平结构双向映射与原样回写不改变含义、uid 歧义明确失败、显式 `null` 清除计时效果、读取改写回写后新逻辑压过被带回的旧扩展，以及失效模板的两种去向（仍是模板则跳过注入，改成普通文字则正常参与）；`tools/web-runtime` 的 `host.test.mjs` 新增 10 项覆盖新旧接口到桥方法的映射与调用序。本轮实际执行 `:conversation-core:test` 与 `:app:testDebugUnitTest`（17 项沿用可选条件跳过），全部通过；`tools/web-runtime` 的 `npm test` 40 项通过。两侧都用模拟宿主或单元行为，不代表真机与真实模型验收；作者新建的含 `<%` 条目不在已登记模板内，仍会作为普通文本注入。

## 资源与恢复

图片请求复用 `CharacterImageRepository` 的原地址索引与持久原图。预先准备入口保留；运行时发现的新地址按需下载并进入同一索引。沿用 PNG/JPEG/WebP、单图 8 MiB、8192 边长、3200 万像素、角色 256 MiB 和 512 项限制。HTTP(S) 图片走原有独立图片下载器与公网检查。

其他资源只接受无凭据公网 HTTPS GET，使用独立无 Cookie/模型凭据客户端，最多三次重定向。blob 按内容寻址并由整个应用共享，URL 绑定分两级：应用级全局索引记录原 URL、最终 URL、MIME、长度、SHA-256 与最后使用时间；会话资源索引记录同一批字段并校验角色哈希，作为该会话固定版本的快照。先原子保存 blob，再原子发布索引。每项最多 8 MiB，每会话最多 512 项/64 MiB，应用级 blob 总量最多 512 MiB。新建对话遇到已登记的 URL 时优先命中全局索引，直接复用验证后的本地 blob 并把该 entry 原样快照进本会话索引，不再重新下载；会话内版本因此仍然固定，离线从 blob 读取，快照连同重定向后的最终地址一起复制，上游日后改动路径不会改写旧会话的相对依赖。命中全局索引但本地 blob 缺失时，该会话本就没有固定版本，按当前远端内容重新登记；命中本会话快照而 blob 缺失或损坏时只接受同哈希恢复，远端已变更时报告错误，不悄悄升级旧会话。应用级淘汰只在登记新资源时发生：按最后使用时间删除没有任何会话索引引用、不在本次写入保护集合内、且写入已超过十分钟的 blob；仍有会话引用的资源宁可略超上限也不删除，任一无法解析的会话索引都会让本次淘汰整体放弃，淘汰不修改任何会话索引。应用级索引损坏时改名为 `index.json.corrupt` 并从会话索引重建；应用级索引不存在时同样从会话索引迁移，按会话目录名排序取先到者。最后使用时间在内存中累积，变化足够多才回写索引，回写失败只影响淘汰顺序，不影响缓存命中。“重新准备网页资源”是原生用户操作：暂停当前网页运行，重新取得已有依赖，在下载窗口内新登记的绑定保留自身版本，全部校验成功后原子替换整份版本索引并重建网页，被采纳的新版本同时写入全局索引、立即对其余会话可用；失败或停止保留原索引。作者脚本不能自行触发版本更新。“重试加载网页”只重试当前版本，不刷新已固定脚本。

重定向模块由 Acorn 按语法位置重写相对 import 和动态 import 基址；CSS 使用 PostCSS/value parser 保留相对 URL 和 @import 基址。原始下载字节不被修改。`import.meta.url` 使用最终地址，其他 `import.meta` 能力不在首版范围。未登记的裸模块名仍会明确加载失败。

执行模式、运行配置指纹、候选状态和资源版本持久保存。退出、WebView 销毁或进程终止不保存 JS 堆；重建时重新装载原程序并注入检查点，不重放宿主请求或自动重发生成。作者自己的初始化代码仍会再次运行；只留在闭包/DOM 中的状态不能恢复，一次性副作用需作者依据保存状态处理。运行配置指纹变化会要求新建对话，首版不迁移旧 JS 堆或升级旧运行配置。

## 验证入口

### 2026-09-15 更新范围

可信外壳按消息对象变化处理行，正文按 display 文本复用解析结果；只有 reasoning、状态或草稿变化时不重跑正文 Markdown。原生桥在顺序未变时省略 `order`，外壳将变化字段与变化消息直接传给作者协调器；协调器在没有待确认写入时只复制变化内容，草稿增量保留历史消息数组。有待确认写入时仍保守重建 overlay，作者操作结果仍可携带完整权威快照，因此这还不是设计中的全链路 `player-bridge-2`。

只有协调器启动配置携带完整作者快照，普通 page/static/script 配置仅保留身份、源码和视口，并连接现有协调器。已挂载页面的生命周期和事件顺序保持原契约。自动贴底回调在真正执行时再次检查阅读位置与 iframe 焦点，避免排队期间玩家上滚后又被拉到底部。完整验证与尚未覆盖范围见[性能改进记录](performance-improvements-20260915.md)。

- `:content-core:test` 与 `:conversation-core:test`：原文读取、旧执行模式反序列化、字面消息修改、原子失败、过期候选和候选检查点。
- `:app:testDebugUnitTest`：实际保存失败、图片复用/动态索引、离线资源/哈希/版本固定、原卡 MVU/EJS 免编译装载和现有聊天回归。
- `tools/web-runtime` 的 `npm test` 与 `npm run test:browser`：同步视图、队列、事件、CSS/import 解析，以及真实 Edge 的作者页面、父输入/发送、来源隔离和页面保留。
- 段级重建与状态文案：`test/browser/render.test.mjs` 在真实 Edge 上量流式富文本更新期间文档高度与滚动位置（贴底读者与回看历史的读者分别断言）、只改文字或思考时作者页面的 DOM/未保存输入/监听器是否保留、状态与占位文案随状态改写，以及候选或页面源码变化时确实重建。
- 正文排版基线：`src/message.css` 由主壳与 static 帧共用（`test/browser/render.test.mjs` 断言含 Markdown 表格的普通正文在 static 帧内与壳文档字体、行距、单元格边框一致）。作者的样式在 static 帧里有两条通路且都压过基线：内联声明，以及 `<style>` 块——段首的 `<style>` 会被整文档解析提升进 `<head>`，所以清洗时按整文档读取再放回帧内，否则整块丢失（内容中间的 `<style>` 本来就留在 body）。样式仍只作用于这个没有宿主能力的沙箱帧：它可以发网络请求（与已允许的 `<img>` 同类），但拿不到桥、宿主接口与播放器 DOM。
- `BrowserSessionAndroidTest`：生产 WebView 桥、表单触摸及字符输入回写、图片高度变化、状态保存与重建、长历史和流式阅读位置；进程恢复使用独立 prepare/recover instrumentation 运行，中间终止应用进程。

- 键盘避让：`:app:testDebugUnitTest` 的 `ChatScreenTest` 验证可视高度不足时发送按钮与输入框仍在同一行并落在可见区内；`tools/web-runtime` 的 `test/browser/viewport.test.mjs` 用缩小视口模拟键盘，验证作者 `vh` 折算跟随可见高度、读者贴底状态跨视口变化保持、焦点在作者帧内时暂停自动贴底。真机输入法交互按上文未验证项处理。

中性样本 C-04 使用现有原件及审计检查点，原件 SHA-256 为 `fa7e8ec564887780b331d0da29f7966f58f3688d49e6faf587d2d80ae9aecefe`。样本与生成夹具不打包进生产 APK、不进入仓库；不调用导入编译模型。单个样本通过不代表整卡或全部扩展兼容。

本轮执行 `:content-core:test :conversation-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`，核心及应用单测共 424 项、失败 0、按原有条件跳过 5 项；JS 契约测试 12 项和 Edge 集成测试通过。Android 测试类中的表单/重建及长历史测试通过，进程恢复另行以 prepare/recover 两次运行并在中间 force-stop 验证。为避开本机其他 Gradle 任务持有的常规构建目录锁，本轮使用临时 init script 将输出重定向到独立构建目录；未更改项目默认构建路径。


本轮设备为 Pixel 7 API 35 模拟器（Android 15）。125 条合成文字历史首次挂载 50 条用时 3255 ms，向前加载至 100 条后，流式更新保留顶部阅读位置，返回底部能继续跟随。该次应用进程 PSS 为 230235 KiB（约 225 MiB），不包含独立 WebView 渲染进程，也不是纯网页增量；测试包含 instrumentation/调试开销，不能作为真机性能结论。另行的 prepare → force-stop → recover 两个 instrumentation 进程验证了已保存候选状态。实体设备输入法、触摸选区及复杂作者表单仍需按具体设备回归。
