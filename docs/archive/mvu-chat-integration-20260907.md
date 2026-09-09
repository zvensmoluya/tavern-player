# MVU 聊天接入

> 归档说明（2026-09-09）：保留阶段设计、源码审计与验证证据；文中的“当前”“下一步”和版本号指记录当时，不作为现行产品决策或完整运行契约。现行入口见[文档导航](../README.md)、[产品边界](../product-direction.md)和[实现架构](../architecture.md)。

本轮将此前的 QuickJS 调用底座接入适配产物、会话创建和正常聊天。编译契约更新为 `native-compiler-5`。

> 同日后续已增加独立的 [EJS 提示词执行入口](ejs-quickjs-integration-20260907.md)，编译契约为 `native-compiler-6`。下文 EJS 不在范围的说明保留为本轮 MVU 接入边界。

## 产物与构建

模型通过 `mvu.schemaSourceId` 选择启用的原卡 Schema 脚本，本地逐字复制为 `NativeAdaptation.mvu.schemaScript`。初始化世界书和开场白直接读取不可变角色快照。模型不重写变量指令实现，也不生成框架源码。MVU 与旧 `assistantStateAdapters` 不能同时安装，避免两个写入器解释同一更新块。

Gradle 的 `prepareMvuRuntime` 构建固定上游 bundle，首次运行先按 npm lock 安装依赖；构建机需要 Node/npm 和首次下载所需网络。普通 APK 现在包含 `mvu/runtime.js`、provenance 和原始许可证。应用资产目录独立于测试资产目录，不复制中性夹具或 C-04 程序。运行时不下载依赖。宿主依赖固定为 lodash 4.18.1 与 YAML 2.9.0，修复构建审计发现的旧依赖告警；对应上游公告为 [lodash](https://github.com/lodash/lodash/security/advisories/GHSA-r5fr-rjxr-66jc) 和 [YAML](https://github.com/eemeli/yaml/security/advisories/GHSA-48c2-rrv3-qjmp)。

原卡导入本身仍不执行脚本；只有显式安装了 MVU 适配的卡才进入此路径。结构校验不等于完整的源码审计，不宣称支持任意 Tavern Helper API。

## 会话事务

- 新建会话：在写入仓库之前运行 MVU 初始化，分别保存每个开场候选的状态。初始化失败不写入半成品对话。
- 完整回复：使用生成计划捕获的上一检查点执行一次 MVU，成功后提交候选结果。Provider 原始正文保留；显示投影不会再次应用变量指令。
- 流式片段、截断、取消、缺少结束事件：不提交 MVU 更新，保留上一有效检查点。错误沿既有生成错误入口显示。
- 重新生成：从该回复之前的检查点计算新候选。候选切换直接恢复对应快照，不重放指令。
- 编辑：`TEXT_ONLY` 保持已有状态与后续历史；`RESTART` 从原检查点重算并截断后续消息。编辑开场采用初始化语义，保留 `<initvar>` 的覆盖行为。
- 重置：等待旧生成结束，重新初始化全部开场。恢复已保存会话使用既有检查点；缺少检查点的已有 MVU 历史不会被静默当作新开场初始化。发送前校验框架及程序指纹，拒绝使用不匹配的检查点。

完整 `stat_data` 同时提供给 `get_message_variable::stat_data`、`format_message_variable::stat_data` 和当前提示词中的 MVU 状态投影。后续请求读取所选路径的变量，包含动态对象；这不提供 EJS 求值。

每个初始化或更新事务建立独立 QuickJS 实例并在 `finally` 释放，没有跨卡全局 JS 状态或第二份持久时间线。这个选择优先保证取消与切换隔离，每轮需要重新加载 bundle；真机稳态性能测量后再决定是否复用实例。

## 边界

MVU 变量没有自动同步到旧 Native State/status/collection 配置；编译报告必须继续区分变量逻辑与展示映射。DOM、任意远程 import、完整 Tavern Helper 和 EJS 不在本轮范围。进程内 QuickJS 的资源限制不等于独立进程沙箱。

本轮只进行本地构建和测试。辅助库分发授权问题沿用前次记录，打包本地开发 APK 不表示发行授权已解决；没有发布或推送产物。

## 本地验证

- 应用专项 51 项通过、0 跳过：聊天 36 项、角色/会话仓库 5 项、编译服务 6 项、真实 QuickJS 4 项。
- 内容层 59 项通过、2 项可选本地导入夹具检查跳过；会话核心 106 项通过。
- Node 参照 4 项通过；C-04 原始程序 14 类检查通过。npm 安装审计报告 0 项已知漏洞。
- 普通 Debug APK 与 Android 测试 APK 构建通过。普通 APK 只含框架、provenance 与两份许可证，不含样本夹具；核对 APK 内 bundle SHA-256 与 provenance 一致。
- `lintDebug` 通过。未运行设备测试、真实聊天模型请求或自动适配模型请求；本轮编译校验使用本地测试响应。

实际 Gradle 命令使用前轮记录的本地隔离输出脚本，避免占用既有增量缓存：

```powershell
.\gradlew.bat -I tools/mvu-probe/build/isolated.gradle --no-configuration-cache --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' :content-core:test :conversation-core:test :app:testDebugUnitTest --tests '*ChatViewModelTest' --tests '*QuickJsMvuRuntimeTest' --tests '*CharacterAndConversationRepositoryTest' --tests '*NativeCompilationServiceTest' :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
npm --prefix tools/mvu-probe test
npm --prefix tools/mvu-probe run probe -- $env:COMMUNITY_CARD
```

`COMMUNITY_CARD` 使用本地绝对路径。验证发现并修复了宿主世界书设置的部分字段合并行为：整份替换会在第二次初始化时丢失必需字段。聊天测试还覆盖重算期间禁止切换/重置，防止异步结果跨会话写入。
