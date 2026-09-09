# 网页消息区与原程序运行契约

更新：2026-09-09。运行配置为 `player-web-1`。这是声明范围内的兼容宿主，不是完整 SillyTavern 或 Tavern Helper。默认新建对话使用网页模式；已有记录缺省解释为旧 Native 模式，切换模式必须新建对话。

## 执行与状态归属

Compose 保留导航、模型与预设选择、输入栏及编辑确认弹窗。一个 WebView 显示消息列表、Markdown、富 HTML 和消息操作。展示使用现有 DISPLAY 投影；原始正文、Prompt 投影和展示内容分开保存，DOM 不自动回写正文。

普通 Markdown/HTML 经 DOMPurify 清理，禁止作者脚本和事件属性。包含完整 `<body>…</body>` 的围栏代码块在消息 COMPLETE 后作为作者页面装载；其他代码块保留为代码。流式更新每 50 ms 至多一次，完成立即发布。原生首次发送完整快照，后续发送变更消息、顺序与变更状态。首次显示最近 50 条，点击每次向前加载 50 条。已挂载页面保留至消息内容/候选改变、历史被截断、切换对话或退出页面；滚动和追加新消息不重新执行旧页面。

`BrowserProgramReader` 直接读取角色与当前预设的 `extensions.tavern_helper` 对象或键值对数组，遍历 `scripts` 目录，保留原文、来源 JSON pointer、启用状态和 SHA-256。它不经过 Native 编译器、模型筛选或裁剪。程序语法由 Acorn 解析，解析不执行源码。

页面和后台脚本由 WebView 执行。后台脚本归属当前对话页面，不依附滚动可见性。切换预设后，已开始的生成使用捕获配置；生成结束后撤销旧预设脚本并加载新脚本。后台脚本可以提供按钮，不提供任意扩展设置界面或完整 ST DOM。

独立的 MVU 加载语句与登记的 `mvu_zod` Schema 模块由已有 QuickJS MVU 宿主处理。原加载器不再进入 WebView，避免同一回复执行两次 MVU 更新。混合 MVU 加载器与其他副作用的脚本明确停用；多份 Schema 或不能在无 DOM 宿主装载的 Schema 不自动拆分。世界书中的 EJS 原模板按来源哈希登记，使用已有只读 QuickJS EJS 引擎；触发、Regex/Macro、求值缓存与预算继续走 PromptCompiler，无需模型适配产物。

## 接口范围

参照提交和文件记录在 [upstream-contract.json](../tools/web-runtime/upstream-contract.json)。Tavern Helper 基线为 `3de7ef981f378517779eb32ab5ecb82c033e4db4`，ST 基线为 `8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8`。MVU、Schema 辅助库和 EJS 使用已有 [公共程序锁](../tools/mvu-probe/upstream-lock.json)。浏览器库由 npm lock 固定，构建产物附带许可证和文件哈希清单。

| 能力 | 当前支持 | 明确限制 |
| --- | --- | --- |
| 消息读取 | `getChatMessages` 的楼层、范围、角色、隐藏过滤及候选读取；当前/最后消息身份 | 只读取当前对话，不提供其他会话访问 |
| 消息修改 | `setChatMessages` 改写已有正文、候选内容、选中候选、变量和隐藏状态；`refresh` 为 none/affected/all | 不新增、删除或移动楼层，不增减候选数量；正文按字面量保存，不调用用户编辑的 Macro/截断流程 |
| 变量 | `getVariables`、`replaceVariables`、`updateVariablesWith`；chat、message、当前 script 作用域 | global、角色/预设资产、其他脚本作用域不开放；单份变量最多 1 MiB |
| 事件 | 注册、注销、一次性监听、顺序调整、自定义异步事件；消息、候选、渲染、生成和 MVU 更新事件 | 不提供完整扩展事件集合；同步跨页面 `eventEmitAndWait` 明确失败 |
| 生成 | `generate` 使用当前连接和预设；`generateRaw` 接受显式 role/content 数组；生成 ID、流式事件与停止 | 不接受 custom_api、凭据、任意 Provider、工具、图片或注入/覆盖参数；raw 不接受内置 marker 名称 |
| MVU | 初始化、完整回复更新、候选检查点读取和直接替换 | 替换保留原 schema；不提供完整扩展编辑器/设置、跨引擎共享闭包 |
| EJS | 已登记原世界书模板的只读求值 | 页面私有状态须先通过宿主保存；未完成初始化导致缺少变量时明确失败 |
| 世界书 | `getWorldbook` 读取当前角色世界书；显式 `setWorldbookEnabled` / `setWorldbookEntryEnabled` | 旧版 `getLorebookEntries` 明确拒绝；不提供通用资产编辑，不改变其他角色资产 |
| 页面辅助 | 初始化等待、脚本身份、按钮、toastr 诊断、有限父页面 | 父页面仅提供 `#send_textarea` 与 `#send_but`；不提供 ST 内部模块/播放器工具栏 |

同步读取来自 JS 会话视图。同步写入立即更新待提交视图并排队保存，生成与异步消息修改排在此前写入之后。`updateVariablesWith` 保留同步/异步回调对应的返回类型。每次提交携带运行实例、候选身份、状态版本和请求 ID；Kotlin 验证后计算原子提案，保存成功才发布或确认。重复请求不会重复执行；旧候选、已销毁页面、过期状态或忙碌会话拒绝提交。一次存储失败停止整个网页运行实例，恢复已保存视图并提供重试入口。

有限父页面与作者内容同属不可信区域，使用不同于可信消息外壳的来源。只有可信主框架能调用 WebMessageListener。作者内容可以修改自己的兼容父页面，但不能读取原生桥、播放器 DOM、文件路径或模型凭据。WebView 禁止文件/content URI、弹窗、设备权限、Worker 和 Service Worker；CSP 配合取得层限制资源协议与请求方法。

## 资源与恢复

图片请求复用 `CharacterImageRepository` 的原地址索引与持久原图。预先准备入口保留；运行时发现的新地址按需下载并进入同一索引。沿用 PNG/JPEG/WebP、单图 8 MiB、8192 边长、3200 万像素、角色 256 MiB 和 512 项限制。HTTP(S) 图片走原有独立图片下载器与公网检查。

其他资源只接受无凭据公网 HTTPS GET，使用独立无 Cookie/模型凭据客户端，最多三次重定向。会话资源索引记录原 URL、最终 URL、MIME、长度、SHA-256 与角色来源；先原子保存 blob，再原子发布索引。每项最多 8 MiB，每会话最多 512 项/64 MiB。已取得 URL 在该会话内固定版本，离线从验证后的 blob 读取。损坏资源只接受同哈希恢复；远端已变更时报告错误，不悄悄升级旧会话。“重新准备网页资源”是原生用户操作：暂停当前网页运行，重新取得已有依赖，全部校验成功后原子替换整份版本索引并重建网页；失败或停止保留原索引。作者脚本不能自行触发版本更新。新建对话也会重新确定网页依赖版本；“重试加载网页”只重试当前版本，不刷新已固定脚本。

重定向模块由 Acorn 按语法位置重写相对 import 和动态 import 基址；CSS 使用 PostCSS/value parser 保留相对 URL 和 @import 基址。原始下载字节不被修改。`import.meta.url` 使用最终地址，其他 `import.meta` 能力不在首版范围。未登记的裸模块名仍会明确加载失败。

执行模式、运行配置指纹、候选状态和资源版本持久保存。退出、WebView 销毁或进程终止不保存 JS 堆；重建时重新装载原程序并注入检查点，不重放宿主请求或自动重发生成。作者自己的初始化代码仍会再次运行；只留在闭包/DOM 中的状态不能恢复，一次性副作用需作者依据保存状态处理。运行配置指纹变化会要求新建对话，首版不迁移旧 JS 堆或升级旧运行配置。

## 验证入口

- `:content-core:test` 与 `:conversation-core:test`：原文读取、旧执行模式反序列化、字面消息修改、原子失败、过期候选和候选检查点。
- `:app:testDebugUnitTest`：实际保存失败、图片复用/动态索引、离线资源/哈希/版本固定、原卡 MVU/EJS 免编译装载和现有聊天回归。
- `tools/web-runtime` 的 `npm test` 与 `npm run test:browser`：同步视图、队列、事件、CSS/import 解析，以及真实 Edge 的作者页面、父输入/发送、来源隔离和页面保留。
- `BrowserSessionAndroidTest`：生产 WebView 桥、表单触摸及字符输入回写、图片高度变化、状态保存与重建、长历史和流式阅读位置；进程恢复使用独立 prepare/recover instrumentation 运行，中间终止应用进程。

中性样本 C-04 使用现有原件及审计检查点，原件 SHA-256 为 `fa7e8ec564887780b331d0da29f7966f58f3688d49e6faf587d2d80ae9aecefe`。样本与生成夹具不打包进生产 APK、不进入仓库；不调用导入编译模型。单个样本通过不代表整卡或全部扩展兼容。

本轮执行 `:content-core:test :conversation-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`，核心及应用单测共 424 项、失败 0、按原有条件跳过 5 项；JS 契约测试 12 项和 Edge 集成测试通过。Android 测试类中的表单/重建及长历史测试通过，进程恢复另行以 prepare/recover 两次运行并在中间 force-stop 验证。为避开本机其他 Gradle 任务持有的常规构建目录锁，本轮使用临时 init script 将输出重定向到独立构建目录；未更改项目默认构建路径。


本轮设备为 Pixel 7 API 35 模拟器（Android 15）。125 条合成文字历史首次挂载 50 条用时 3255 ms，向前加载至 100 条后，流式更新保留顶部阅读位置，返回底部能继续跟随。该次应用进程 PSS 为 230235 KiB（约 225 MiB），不包含独立 WebView 渲染进程，也不是纯网页增量；测试包含 instrumentation/调试开销，不能作为真机性能结论。另行的 prepare → force-stop → recover 两个 instrumentation 进程验证了已保存候选状态。实体设备输入法、触摸选区及复杂作者表单仍需按具体设备回归。
