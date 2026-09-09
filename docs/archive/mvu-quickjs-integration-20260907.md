# QuickJS MVU 宿主

> 归档说明（2026-09-09）：保留阶段设计、源码审计与验证证据；文中的“当前”“下一步”和版本号指记录当时，不作为现行产品决策或完整运行契约。现行入口见[文档导航](../README.md)、[产品边界](../product-direction.md)和[实现架构](../architecture.md)。

> 后续实现：MVU 已接入适配产物和聊天事务，当前编译契约为 native-compiler-5；见 [MVU 聊天接入](mvu-chat-integration-20260907.md)。下文先前阶段的未接入描述保留为历史记录。

> 2026-09-07。用户选择 QuickJS，并要求先完成本地验证。本轮将 Node 实验中的上游变量链路移到 QuickJS Kotlin 宿主；没有启用普通聊天的自动脚本执行。

## 实现

- 引擎固定为 `io.github.dokar3:quickjs-kt:1.0.10`。应用使用 Android JNI，本地单元测试通过依赖替换使用同版本 JVM JNI。
- `QuickJsMvuRuntime` 在专用线程创建、调用和释放引擎；同一实例的调用通过 Mutex 串行处理。它没有暴露网络、文件、Android 对象或任意原生函数给卡。
- 程序、消息和变量通过 JSON 字符串传入。卡的 Schema 是显式加载的程序；消息内容不会直接拼成 JavaScript 代码。
- 引擎执行上限默认 2 秒，加载 bundle 上限 10 秒，JS 内存上限 96 MiB、栈上限 1 MiB。协程超时同时约束异步等待；异常或取消后不再复用受影响实例。
- `ConversationRuntimeState.mvuState` 保存完整上游 MvuData，并带 bundle 与卡程序的 SHA-256。不同程序的检查点不能交叉使用；旧记录没有该字段时默认为 null。
- 每次更新由 Kotlin 传入选中路径的旧检查点。JS 只建立前一检查点与当前消息的临时视图，让上游 `handleVariablesInMessage` 工作，不在 JS 中维护第二份持久聊天历史。
- 返回值同时保留原始 `sourceText`、框架处理后的文本、变量、事件与诊断。上游补占位符不会要求覆盖 Provider 原始正文。
- 上游跳过不足 5 个字符的助手回复时，宿主沿用输入检查点，避免把普通短回复误判为状态丢失；共用契约检查覆盖此行为。

代码入口：

- [QuickJsMvuRuntime.kt](../../app/src/main/java/io/github/zvensmoluya/tavernplayer/conversation/mvu/QuickJsMvuRuntime.kt)
- [checkpoint-host.mjs](../../tools/mvu-probe/checkpoint-host.mjs)
- [共用运行契约检查](../../app/src/sharedTest/java/io/github/zvensmoluya/tavernplayer/conversation/mvu/MvuRuntimeContract.kt)
- [桌面测试](../../app/src/test/java/io/github/zvensmoluya/tavernplayer/conversation/mvu/QuickJsMvuRuntimeTest.kt)
- [Android 测试](../../app/src/androidTest/java/io/github/zvensmoluya/tavernplayer/conversation/mvu/QuickJsMvuAndroidTest.kt)

## 验证范围

共用检查执行真实 QuickJS 和上游 MVU/Zod，不 mock JS 执行结果。覆盖初始化与多开场、四种更新、动态 Record、默认值与数值限幅、事件顺序中的 Zod 入口、逐命令拒绝、JSON Pointer 转义、消息作为数据传输、重复从同一检查点计算、程序身份检查。

持久化检查使用实际 `ConversationRepository`：保存完整消息候选和检查点，重新创建仓库读取，切换保存的候选，并销毁 QuickJS 后重新建引擎继续更新。它验证持久化边界，不等于已通过 ChatViewModel 完成端到端聊天。

可选 C-04 检查读取本地 Node 实验生成的原始 Schema/初始化夹具；普通检查使用中性夹具。两类都需要先运行 JS 构建；缺少本地材料时专项测试会明确跳过。

设备当前未连接，用户选择先验证本地。Android 耗时、内存、设备兼容性与生命周期行为仍需真机检查，不能由 JVM 数字推导。

## 本次结果

- Node 参照测试 4 项通过，C-04 参照实验 15 类检查通过。
- 实际 QuickJS JVM 测试 4 项通过、0 跳过：中性完整链路与真实磁盘恢复、C-04 原始程序、死循环中断、悬挂 Promise 取消。既有角色/会话仓库测试 5 项通过。
- `conversation-core` 的 106 项测试通过。
- 普通 Debug APK、Android 测试 APK 构建通过，`lintDebug` 通过。测试方法返回类型修正后另重建测试 APK；未运行设备测试。
- APK 内容检查确认普通 APK 不含 `assets/mvu/`，实验 bundle、许可证和 C-04 程序只在本地测试 APK。64 位 QuickJS ELF 的 LOAD 段为 16 KB 对齐，APK 内 QuickJS 原生库条目也为 16 KB 对齐；此检查不能代替 16 KB 设备运行。

构建期间发生既有 Kotlin 增量缓存占用冲突，因此使用本地 init 脚本把各模块输出移到 `build/mvu-quickjs`，并关闭本次命令的增量编译；没有删除其他构建进程的缓存或修改全局编译设置。核心实际命令为：

```powershell
.\gradlew.bat -I tools/mvu-probe/build/isolated.gradle --no-configuration-cache --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' :app:testDebugUnitTest --tests '*QuickJsMvuRuntimeTest' --tests '*CharacterAndConversationRepositoryTest' :conversation-core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

本地 `isolated.gradle` 内容为：

```groovy
gradle.beforeProject { project ->
    project.layout.buildDirectory.set(new File(project.projectDir, 'build/mvu-quickjs'))
}
```

测试报告位于 `app/build/mvu-quickjs/reports/tests/testDebugUnitTest/` 和 `conversation-core/build/mvu-quickjs/reports/tests/test/`。一次仓库往返检查最初把 `save()` 自动更新的记录时间误当作差异，现比较实际保存返回值；变量数据没有因此改动。

## 本地成本观察

以下是桌面 JVM 上一次测试的观测，不是 Android 基准；当时有 Gradle 编译/分析并行运行，不能据此估计手机启动时间：

| 项目 | 观测 |
|---|---|
| 自包含 JS 源码 bundle（minify） | 1,253,424 字节，包含宿主注入库；不是完整 APK 增量 |
| ARM64 QuickJS 原生库 | 919,696 字节，AAR 内压缩后约 410 KB |
| 创建实例、加载 bundle、注册程序 | 1,916 ms |
| 一次六命令更新 | 6 ms |
| 测试结束时 QuickJS 自报已用内存 | 16,865,281 字节；不包含整个 JVM/Android 进程 |

加载成本值得单独关注，因此宿主支持复用已创建实例。冷启动、稳态更新、连续运行内存和设备进程被回收后的恢复耗时仍需真机测量，不把单次桌面结果写成性能保证。

## 当前边界

这是可调用的 Kotlin 宿主和消息检查点接入，不是“导入任意 MVU 卡即可自动游玩”。卡安装与程序准备入口、普通生成链路调用、原生状态展示和 Prompt 变量读取尚未切换。EJS 仍是独立依赖。宿主保留实验中的有限接口：宏原样返回，世界书设置写入仅影响临时视图，不实现整个 Tavern Helper。

QuickJS 是内嵌的进程内执行器，内存与时间限制不等于独立进程沙箱。当前入口只接受调用者显式提供的受控 bundle 和已审计程序；尚未开放任意导入脚本执行。

构建产物包含明确固定的依赖，没有运行期远程 import。MVU/Zod bundle、原始许可证和可选原卡程序只进入本地测试资产与测试 APK，普通 APK 不包含这些脚本。Zod 辅助库分发授权差异沿用 [前次记录](mvu-integration-probe-20260907.md)，本轮没有将本地测试成功视为发行授权已解决。

## 复跑

```powershell
npm --prefix tools/mvu-probe ci
npm --prefix tools/mvu-probe run build
npm --prefix tools/mvu-probe test
npm --prefix tools/mvu-probe run probe -- $env:COMMUNITY_CARD
.\gradlew.bat :app:testDebugUnitTest --tests '*QuickJsMvuRuntimeTest'
.\gradlew.bat :conversation-core:test :app:assembleDebug :app:assembleDebugAndroidTest
```

真实样本路径由本地 `COMMUNITY_CARD` 提供，不写入版本库。构建工具会同时输出 `provenance.json`，记录固定上游提交、bundle 字节数和哈希。
