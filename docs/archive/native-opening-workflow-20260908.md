# 原 MVU 加载与开局流程保留

> 归档说明（2026-09-09）：保留阶段设计、源码审计与验证证据；文中的“当前”“下一步”和版本号指记录当时，不作为现行产品决策或完整运行契约。现行入口见[文档导航](../README.md)、[产品边界](../product-direction.md)和[实现架构](../architecture.md)。

日期：2026-09-08。用户要求解决编译器整体放弃开局表单的问题，并区分模型降级取舍与宿主能力缺口。本轮保留源码、模型原始响应和失败记录，没有补写模型答案或放宽停用来源检查。

## 核查结果与实现

C-01 的原开局逻辑可分为：选择路线／身体／其他输入、构造条件文本、预览确认、防止覆盖已有草稿、写入草稿、直接替换 MVU 字段、切换开场候选，以及只在自定义分支自动发送。原预设分支保持输入框中的行动提示，等待用户继续填写。

固定 Native 表单只恢复原模板字符串；本例使用条件数组与字符串拼接，不能直接套用该结构。JS Surface 可以执行这段计算，用文本／单选字段和独立见证者切换操作保留有序多项选择。自动切换和发送仍不是当前脚本宿主能力，但不足以成为放弃整个输入与草稿流程的理由。

`native-compiler-15` 明确逐步骤判断支持范围、保留独立可用行为、先选开场再填该候选的表单、用实际界面说明人工步骤，并禁止复制不再成立的自动执行文案。可见字段变化需要显式更新表单操作。handler 返回值不作为提示或失败信号；拒绝操作使用异常，成功反馈通过后续 Surface。模块只能引用启用来源，不能把停用旧版本当作参考文献加入 `sourceIds`。

宿主同时修复两个已证实的兼容问题：

1. Schema 的已知静态／字面量动态导入映射到本地已锁定 helper。保留局部变量、顶层 await 和异常处理顺序；AST 检查先于执行，未知／计算 URL 仍拒绝，没有网络加载器。Node 与 QuickJS 等待注册完成后再初始化，异步加载可超时和取消。
2. 固定版本 Zod helper 在更新结束后可将 MVU `schema` 写成字符串标记。原直接写入接口强制该字段为对象，错误拒绝了完整保留状态的合法写入。现在要求 `stat_data` 为对象，同时原样保留 `schema` 的值和类型；删除或篡改它仍拒绝。没有根据某张卡的标记内容放行特例。

编译服务在返回 Ready 前，实际加载并初始化原 MVU 及开场；异常、超时或初始化 error 诊断均拒绝。此验证只使用临时运行实例，不创建或修改用户对话。JS 上下文增加 `openingSourceIndex` 与 `openingSourceIndices` 只读数据，只在初始开场候选阶段可用，之后分别为 null 和空数组。

bundle 发生变化后，旧 MVU 检查点仍按既有指纹规则不能跨版本继续；本轮以新对话验证，没有迁移用户旧状态。

## C-01 实验记录

| 实验 | 模式／契约 | 结果 |
| --- | --- | --- |
| `1788878088258` | LIVE / v13 | 模型生成开局表单、预览和见证者操作，但两个模块引用停用 `regex0`；按原规则拒绝，未删字段取得成功 |
| `1788878368181` | REPLAY / 原 v12 响应 | 新加载器执行此前未改动的原 Schema，初始化、状态读取、Surface 投影及保存重载通过；没有新增编译请求 |
| `1788878407637` | LIVE / v14 | 编译、JS 加载、MVU 初始化、安装、投影及重载通过；生成开局表单和确认流程 |

v13 主编译 input/output/reasoning 为 96,020 / 31,276 / 24,532；选择为 91,815 / 81 / 67。v14 主编译为 96,167 / 21,573 / 16,558；选择为 91,815 / 50 / 36。两轮使用同一 DeepSeek 模型及完整 C-01 源码，未把旧响应或人工适配加入输入。reasoning 仅保存服务公开的事件，不作为程序正确性的证明。

v14 产物的首轮操作审计遇到上述 `schema` 类型问题。修复宿主后，对同一原始响应再次执行六组差分用例：三条路线分别为空值／填写值，草稿与原程序纯计算函数逐字一致；独立切换的顺序、全选／清空、返回修改不丢输入、错误开场／已有草稿拒绝、草稿先于 MVU 写入，以及写入后的磁盘恢复通过。

参照由 `tools/mvu-probe/opening-reference.mjs` 在已核对哈希的 C-01 源码中抽取纯函数，使用中性输入运行。`NativeOpeningCompilationAuditTest` 根据私有审计映射操作模型生成的真实 Surface 和 handler，经过 NativeOperations 授权与提交，并保存到实际 ConversationRepository。映射只指定控件和操作，不改变生成代码。原始响应、参照、映射与结果分别位于实验目录；对照不是手写表单冒充自动结果。

v14 界面仍复制了少量暗示自动切换的原提示文字；模型报告也将预设分支的发送行为概括得不准确。实际 handler 没有自动切换／发送，这些文字不能作为行为证据。v15 指令针对这类不一致要求全流程文案对应实际能力。

最终 C-01 LIVE / v15 实验 `1788880091239` 在主编译流读取时触发网关既有 8,388,608 byte 响应上限（包含传输事件开销，不能等同于最终文本长度），没有完整响应、主编译用量或安装结果。选择阶段 input/output/reasoning 为 91,815 / 76 / 62；已收到的正文与公开 reasoning 及 `failure.json` 保留在原目录。没有扩大网关上限或用截断内容补装。因此六组行为对照的成功证据属于 v14 原始产物与当前宿主，不能声称 v15 已验证修正界面文案，亦不能声称整卡语义等价或编译稳定成功。

## 普通卡回归与最终验证

C-03 LIVE / v15 首次实验 `1788879214618` 生成的 handler ID 使用大写，违反既有小写标识符规则，拒绝安装。未修改输入的重试 `1788879346123` 通过编译，选择 JS 表单而非固定表单。旧测试只查固定表单，导致测试自身失败；更新测试按实际 Surface 操作后，使用该轮未改动的响应重放 `1788879674493`，空值默认、四个输入、四项多选按原 DOM 顺序拼接、切换保留输入、提交草稿、真实聊天与保存重载通过。没有为此再请求模型或补写输出。

最终本地 Node 12 项通过；content-core 73 项通过、1 项条件跳过；conversation-core 130 项通过；app 定向集合中 25 项本地测试通过，另 1 项 LIVE 测试为上述首次 C-03 编译拒绝。后续 C-03 重放单独通过。C-01 开局审计另验证原 MVU 的初始化诊断及六组流程。完整构建、测试 APK 与 lintDebug 通过，lint 为 0 错误、12 警告。首次扩大测试曾因临时类输出缺失产生 NoClassDefFoundError；关闭配置缓存并重建后上述本地测试通过，没有以修改产品代码规避构建问题。

已在 Android 模拟器安装构建出的 APK 与测试 APK，执行 `QuickJsMvuAndroidTest`：3 项通过、1 项外部 Schema 条件用例跳过。实际 JNI 覆盖动态 helper 导入及更新；没有执行本轮 C-01 全流程界面自动化或物理设备验证，表单行为证据来自 JVM 真实运行时与仓储操作。

构建与设备命令：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r -e class io.github.zvensmoluya.tavernplayer.conversation.mvu.QuickJsMvuAndroidTest io.github.zvensmoluya.tavernplayer.test/androidx.test.runner.AndroidJUnitRunner
```

日志包括 `build/native-workflow-final-retry.log`、`build/native-workflow-c03-replay-build.log`、`build/native-opening-audit-fixed.log`、`build/mvu-dynamic-android.log` 与 `build/native-compiler15-c01.log`。未创建提交。

## 验证方式

```powershell
npm --prefix tools/mvu-probe run build
npm --prefix tools/mvu-probe test
node tools/mvu-probe/opening-reference.mjs <C-01 实验目录>
.\gradlew.bat :conversation-core:test :app:testDebugUnitTest --tests '*NativeOpeningCompilationAuditTest' --rerun --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

开局审计通过环境变量 `TAVERN_OPENING_AUDIT_RUN` 显式启用；默认跳过，不读取用户对话库。Node 测试覆盖静态别名、动态导入与局部变量、未调用函数内的非法导入先行拒绝；JVM/Android 用实际 QuickJS 检查异步加载、初始化与更新。主编译服务另验证初始化失败不会返回可安装结果。
