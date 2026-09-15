# 全局世界书接入与验证

## 交付边界

全局世界书与预设独立，入口位于角色首页和聊天的世界书菜单。支持文件／Shelf 导入、多书启停、正文编辑、逐项按原条件／始终注入／停用、分别恢复正文和使用方式、另存为及导出。导入和副本默认停用。现有角色资产与会话无需迁移。

生成入口捕获全局定义与玩家覆盖，只合并到本轮编排输入；不改 Character Snapshot。普通发送、重试、重新生成和带预设的网页辅助生成使用该捕获，raw 请求仍只使用显式消息。计时状态沿各自会话检查点保存，预设切换不会改变全局书选择。

分组、递归及预算沿现有多书引擎执行，不在本次扩为完整 ST 来源混排。角色书先于全局书参与预算筛选，最终按原位置和 order 编排。作者世界书 API 尚未接入独立全局库；全局书的原始 EJS 在网页生成路径建立哈希引用，修改模板沿已有来源校验执行。详细规则见[当前架构](../architecture.md)和[产品方向](../product-direction.md)。

## 自动验证

执行命令：

```powershell
.\gradlew.bat --no-parallel --max-workers=1 :content-core:test :conversation-core:test :app:testDebugUnitTest --tests '*WorldBookRepositoryTest' --tests '*WorldBookReaderScreenTest' --tests '*ChatViewModelTest' --tests '*CharacterLibraryViewModelTest' --tests '*ChatScreenTest' --tests '*TavernPlayerAppTest' :app:assembleDebug --console=plain
```

- content-core：89 项，86 通过、3 项可选本机夹具检查跳过，无失败。
- conversation-core：187 项通过，包括强制全局条目、预设独立性、会话计时隔离和全局 EJS 来源校验。
- app：88 项中 87 通过，既有 Native 写入失败后的恢复测试超时；全局仓库、Shelf 路由、阅读范围提示、聊天发送与重新生成接线测试通过。该条命令因超时返回失败，未继续执行 APK 任务。

单独复跑超时项并重建 APK：

```powershell
.\gradlew.bat --no-parallel --max-workers=1 :app:testDebugUnitTest --tests '*ChatViewModelTest.failed native write never publishes success and can retry after storage recovers' :app:assembleDebug --console=plain
```

测试通过，`BUILD SUCCESSFUL`。未修改该既有测试或放宽其超时限制。另一次高并发尝试遇到 Gradle 测试进程连接本机端口超时，降低并发后内容测试通过。

最后修正 raw 辅助生成不读取全局书库后，执行：

```powershell
.\gradlew.bat --no-parallel --max-workers=1 :app:testDebugUnitTest --tests '*ChatViewModelTest.global books are captured for each send and regenerate independently of preset' :app:assembleDebug :app:lintDebug --console=plain
```

测试、APK 重建和 lint 均通过，`BUILD SUCCESSFUL`。lint 为 0 error、18 warning，来自依赖版本提示、既有应用图标及 KTX 用法建议。未引入新的权限或网络协议。`git diff --check` 通过。

## 设备验证

`adb devices` 没有连接设备，未执行真机验证。仍需覆盖系统文件选择器导入／导出、Shelf 局域网扫码及权限流程、应用重启后的全局书选择与实际模型请求。自动仓库测试已覆盖重新实例化后的持久化恢复，但不能代替这些设备行为。
