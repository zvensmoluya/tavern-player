# MVU 接入实验

当前 Gradle 构建会调用此工具，生成应用使用的固定框架资产（`build/app-assets/mvu`）；只含框架、provenance 与许可证，不含原卡或测试夹具。测试资产仍在独立的 `build/android-assets`。详见 [聊天接入](../../docs/archive/mvu-chat-integration-20260907.md)。

直接运行固定版本的 MVU 初始化、更新和消息处理源码，以及卡所引用的 `registerMvuSchema` 辅助库；不重新实现四种更新操作。Node 宿主提供消息、候选、世界书、事件和诊断接口，验证公共依赖能否脱离酒馆网页工作。

这是开发实验。现已提供 [QuickJS Kotlin 宿主](../../app/src/main/java/io/github/zvensmoluya/tavernplayer/conversation/mvu/QuickJsMvuRuntime.kt) 与 JVM/Android 共用验证，已接入声明 MVU 的适配产物、正常聊天和 Prompt 变量读取；原生界面不会自动绑定这些变量。Node 参照实验见 [实验记录](../../docs/archive/mvu-integration-probe-20260907.md)，后续见 [QuickJS 接入记录](../../docs/archive/mvu-quickjs-integration-20260907.md)。

## 运行

使用 Node.js 22。在仓库根目录执行：

```powershell
npm --prefix tools/mvu-probe ci
npm --prefix tools/mvu-probe run build
npm --prefix tools/mvu-probe test
```

构建还会生成不依赖 Node 的自包含 `build/android-assets/mvu/runtime.js`，显式打包 lodash、YAML 和 Zod。运行实际 QuickJS JNI 与 Player 磁盘仓库检查：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*QuickJsMvuRuntimeTest'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
```

正常 Gradle 构建会先准备 bundle；缺少原始样本时只有可选样本检查跳过。Android 测试类为 `io.github.zvensmoluya.tavernplayer.conversation.mvu.QuickJsMvuAndroidTest`，与桌面共用 `MvuRuntimeContract`；桌面 JNI 成功不代表 Android 已执行。

可选真实样本验证：将环境变量 `COMMUNITY_CARD` 指向本地 C-04 PNG，然后执行：

```powershell
npm --prefix tools/mvu-probe run probe -- $env:COMMUNITY_CARD
```

真实样本入口只接受已经审计的 SHA-256，不是任意脚本导入器。它读取 PNG 中的原卡数据，只运行启用的 Zod 注册脚本；使用原开场和原世界书初始化，后续更新为中性合成消息。不会请求聊天模型，也不会执行卡的 HTML、EJS、其他脚本或远程加载器。

真实样本验证成功后，还会写出本地 `build/android-assets/mvu/c04-program.json`，供可选 QuickJS 真实样本检查使用。该文件包含原始程序与开场数据，属于忽略目录内的私有测试材料；不会进入普通应用 APK。

`build.mjs` 按 `upstream-lock.json` 下载固定提交的源码并验证每个文件的 SHA-256。所有上游文件保持原字节，仅在构建解析时把依赖连接到本地模块；Pinia 设置及 Vue i18n 入口替换为明确的宿主 shim。npm 依赖由 `package-lock.json` 锁定。首次构建需要网络，已缓存且哈希匹配的构建及测试不需要网络。

上游源码、原始许可证、bundle、真实样本报告均在被忽略的 `build/` 中；`node_modules/` 也不进入版本库。真实样本报告为 `build/card-report.json`，只输出中性实验标识、哈希、检查项和调用接口，不保存原卡正文或人物名称。`android-assets` 仅进入本地 Android 测试 APK；普通 APK 通过独立的 `app-assets` 目录包含框架及许可证，不包含样本程序。

## 边界

- `host.mjs` 的 Node VM 用于分离测试上下文，不能当作执行不可信脚本的安全沙箱。仅用于仓库内的受控夹具和已审计哈希的样本。
- MVU 的操作解析、初始化、schema 处理与 Zod 的命令事件处理来自上游；宿主负责提供消息与存储接口。
- Node 参照宿主用内存数组模拟酒馆消息结构；它的 JSON 序列化测试不代表 Player 磁盘仓库验证。QuickJS 共用检查另外调用实际 `ConversationRepository`，Android 生命周期仍需真机验证。
- 宏接口在实验中原样返回文本；世界书设置写入只记录在实验宿主内；`registerVariableSchema` 仅记录注册，不实现酒馆助手的变量编辑器。
- 实验加载的入口不含 MVU 设置面板、额外模型请求、全局事件调度等全部功能。不能据此声称整个 MVU 插件已运行。
- MVU 仓库提供 MIT 许可；此固定版本的 Zod 辅助库所在仓库根许可证是 AFPL 文本。二者不能合称 MIT。当前只本地下载、构建和测试，正式随应用分发前需要明确该辅助库的授权，实验不作许可证兼容结论。

## EJS 提示词

同一构建工具还将固定 EJS 客户端引擎及只读宿主打包到 `build/app-assets/ejs/`，使用已有 QuickJS JNI，不增加另一套 JavaScript 引擎。`npm test` 同时运行中性 EJS 用例。

`npm run probe:ejs` 按哈希读取仓库本地 C-04 原件，或使用 `npm run probe:ejs -- <local-source>`；按提交和文件哈希下载参照扩展源码，比较原始模板输出。生成的原件、参照及用例仅进入忽略的 `build/`，其中 `build/android-assets/ejs/` 供 JVM/Android 测试使用。普通 APK 不包含这些样本。

宿主接口、历史范围中的上游特殊行为和验证见 [EJS 接入记录](../../docs/archive/ejs-quickjs-integration-20260907.md)。


## Schema 模块加载

`schema-script.mjs` 通过 Acorn 检查完整 AST。`cdn.jsdelivr.net` 与 `testingcf.jsdelivr.net` 下 `/gh/StageDog/tavern_resource/dist/util/mvu_zod.js` 的静态命名导入（可使用别名）和字面量 `import()` 映射到打包的同一个 helper；原始远程内容不会下载。原程序局部变量、顶层 await 和 try/catch 顺序保留。其他 URL、计算得到的 import 地址和未映射导出在运行原程序前拒绝。

加载现在返回 Promise，Node 参照宿主和 QuickJS 宿主均等待 Schema 注册后才初始化。QuickJS 对异步加载施加超时并在失败／取消时释放实例。程序原文和程序哈希不改写为模型输出。打包产物变化会改变 bundle 指纹；已有检查点仍按现有规则拒绝跨 bundle 继续，应使用新对话验证，不自动迁移既有状态。
