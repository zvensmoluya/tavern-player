# MVU 接入实验

直接运行固定版本的 MVU 初始化、更新和消息处理源码，以及卡所引用的 `registerMvuSchema` 辅助库；不重新实现四种更新操作。Node 宿主提供消息、候选、世界书、事件和诊断接口，验证公共依赖能否脱离酒馆网页工作。

这是开发实验。现已提供 [QuickJS Kotlin 宿主](../../app/src/main/java/io/github/zvensmoluya/tavernplayer/conversation/mvu/QuickJsMvuRuntime.kt) 与 JVM/Android 共用验证，当前 App 的适配安装、Prompt 和原生界面尚未自动切换。Node 参照实验见 [实验记录](../../docs/mvu-integration-probe-20260907.md)，后续见 [QuickJS 接入记录](../../docs/mvu-quickjs-integration-20260907.md)。

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

未准备 bundle 时，MVU 专项测试明确跳过，不影响普通 Gradle 验证。Android 测试类为 `io.github.zvensmoluya.tavernplayer.conversation.mvu.QuickJsMvuAndroidTest`，与桌面共用 `MvuRuntimeContract`；桌面 JNI 成功不代表 Android 已执行。

可选真实样本验证：将环境变量 `COMMUNITY_CARD` 指向本地 C-04 PNG，然后执行：

```powershell
npm --prefix tools/mvu-probe run probe -- $env:COMMUNITY_CARD
```

真实样本入口只接受已经审计的 SHA-256，不是任意脚本导入器。它读取 PNG 中的原卡数据，只运行启用的 Zod 注册脚本；使用原开场和原世界书初始化，后续更新为中性合成消息。不会请求聊天模型，也不会执行卡的 HTML、EJS、其他脚本或远程加载器。

真实样本验证成功后，还会写出本地 `build/android-assets/mvu/c04-program.json`，供可选 QuickJS 真实样本检查使用。该文件包含原始程序与开场数据，属于忽略目录内的私有测试材料；不会进入普通应用 APK。

`build.mjs` 按 `upstream-lock.json` 下载固定提交的源码并验证每个文件的 SHA-256。所有上游文件保持原字节，仅在构建解析时把依赖连接到本地模块；Pinia 设置及 Vue i18n 入口替换为明确的宿主 shim。npm 依赖由 `package-lock.json` 锁定。首次构建需要网络，已缓存且哈希匹配的构建及测试不需要网络。

上游源码、原始许可证、bundle、真实样本报告均在被忽略的 `build/` 中；`node_modules/` 也不进入版本库。真实样本报告为 `build/card-report.json`，只输出中性实验标识、哈希、检查项和调用接口，不保存原卡正文或人物名称。`android-assets` 仅进入本地 Android 测试 APK；普通 APK 只包含 QuickJS 引擎和 Kotlin 宿主，不含 MVU/Zod 程序包。

## 边界

- `host.mjs` 的 Node VM 用于分离测试上下文，不能当作执行不可信脚本的安全沙箱。仅用于仓库内的受控夹具和已审计哈希的样本。
- MVU 的操作解析、初始化、schema 处理与 Zod 的命令事件处理来自上游；宿主负责提供消息与存储接口。
- Node 参照宿主用内存数组模拟酒馆消息结构；它的 JSON 序列化测试不代表 Player 磁盘仓库验证。QuickJS 共用检查另外调用实际 `ConversationRepository`，Android 生命周期仍需真机验证。
- 宏接口在实验中原样返回文本；世界书设置写入只记录在实验宿主内；`registerVariableSchema` 仅记录注册，不实现酒馆助手的变量编辑器。
- 实验加载的入口不含 MVU 设置面板、额外模型请求、全局事件调度等全部功能。不能据此声称整个 MVU 插件已运行。
- MVU 仓库提供 MIT 许可；此固定版本的 Zod 辅助库所在仓库根许可证是 AFPL 文本。二者不能合称 MIT。当前只本地下载、构建和测试，正式随应用分发前需要明确该辅助库的授权，实验不作许可证兼容结论。
