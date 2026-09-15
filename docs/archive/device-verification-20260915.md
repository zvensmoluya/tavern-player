# 最新版本模拟器安装与验证（2026-09-15）

基线：`50557ca`，加本记录所述仪器测试修复。设备为 `emulator-5554`，Android 15 / API 35、x86_64，WebView `151.0.7922.202`。本轮覆盖安装，没有清除应用数据。

## 安装与旧数据

- 使用 Gradle Wrapper 构建应用和仪器测试 APK，`adb install -r` 安装成功，实际启动 MainActivity。
- 安装前备份应用私有数据。升级后对照备份核验 23 个会话、39 条消息、51 个候选：正文、候选选择、草稿、角色/身份/运行状态的原有字段保留；旧 JSON 字节和导入哈希一致，Room 已激活，SQLite `integrity_check` 返回 `ok`。
- 模拟器已安装 APK 与本次本地 APK 逐字节相同；包内 13 项网页资源与当前网页构建产物一致。
- APK SHA-256：`d6de637d2f39d5dfe15dd9cec4741cce4fe55e07cef85e19bee4a207b3610d21`。

## 实测结果

| 范围 | 结果 |
| --- | --- |
| Android WebView | 作者字段真实输入、桥接保存与重开、跨页面共享对象/同步事件通过 |
| 长历史与流式追加 | 125 条历史初显 50 条、加载至 100 条，流式更新保留历史阅读位置，回到底部后保持贴底 |
| Android 运行时 | 2 项 Regex、3 项 MVU、1 项 EJS、1 项凭据存储测试通过 |
| 强停恢复 | prepare → force-stop → recover 分别在不同进程执行，状态与候选检查点一致 |
| 真实模型对话 | C-03 / P-01、网页模式、DeepSeek Responses / `deepseek-flash`；最终复测两轮正文 2,263 / 2,783 字，消息显示、持久化、新 Repository / ViewModel 重开通过 |
| 真实会话跨进程恢复 | 强停后独立 recover 测试通过；完整记录、消息和运行状态哈希一致，恢复期间零模型请求 |
| 键盘 | 真实聊天截图中完整 Gboard 弹出，输入框和发送按钮位于键盘上方 |
| 全局世界书 | 通过系统文件选择器导入中性 JSON，默认停用；启用、阅读、修改保存后强停重启，启用状态和修改正文保留；最后通过界面删除测试书 |

第一组仪器测试共 12 项：10 项通过，2 项按条件跳过。跨进程恢复项随后单独执行 prepare/recover 均通过；私有 schemaRegression 夹具测试未启用。C-04 的既有可选 MVU 夹具本次存在并通过。

真实模型测试共进行了两次两轮对话：首次两轮生成成功，但重开断言失败；修复后重新执行两轮和独立进程恢复均通过。两次新增测试会话保留便于检查。临时模型配置由测试读取后删除，内存凭据清理、原预设恢复、测试预设删除均核验成功。样本哈希见本机指标及[样本编号](../reference/community-samples.md)，不在文档保存原始内容。

## 修复

仅修改 `BrowserLiveAndroidTest.kt`，未改变产品代码：

1. `ChatRoute` 新增全局世界书回调后，旧位置参数把资源解析器传给无参回调，测试 APK 编译失败。改用 `resolveAssetPath` 命名参数。
2. C-03 的显示 Regex 在开场超出深度范围后隐藏作者表单；旧恢复测试仍要求存在消息 iframe，导致 `fresh-reopen` 超时。仅初次开场要求作者页面，重开仍检查 WebView、文档加载、消息节点、运行时错误以及完整持久记录和 UI 正文。没有增加超时或跳过恢复测试。

实测期间一次额外 `uiautomator dump` 与运行中的 instrumentation 抢占 UiAutomation 服务，导致 dump 辅助进程异常；未作为应用崩溃计数，后续 UI dump 均在 instrumentation 结束后执行。

## 执行与证据

构建成功命令：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=2
.\gradlew.bat :app:assembleDebugAndroidTest --max-workers=2 --console=plain
git diff --check
```

设备测试通过 `adb shell am instrument -w -r` 调用 `AndroidJUnitRunner`：BrowserSessionAndroidTest、RegexAndroidSmokeTest、QuickJsMvuAndroidTest、QuickJsEjsAndroidTest、AndroidKeystoreCredentialStoreTest；恢复项分别指定 `webRecoveryPhase=prepare/recover`；BrowserLiveAndroidTest 指定 `browserLive=1`、`browserSample=C-03`、`browserLiveTurns=2`，最后单独指定 `browserLivePhase=recover`。

本机忽略目录 `app/build/device-verification-20260915/` 保存安装前备份、升级核验脚本/结果、仪器测试日志、UI 层级、截图和匿名指标。没有提交备份、凭据或构建产物。未创建提交或推送。

未运行完整 JVM 单测或 lint（本轮仅改仪器测试）。未覆盖实体手机、其他 Android/API/输入法、生成中杀进程、部分正文停止、候选切换、全局世界书导出/Shelf 权限，以及全局书对真实请求的注入；不将模拟器单次首屏或 PSS 数据视为性能基准。

## 后续手机升级故障与修复

同日将上述 APK 安装到 V2458A 后，用户反馈“开始新对话”没有反应。手机实际复现，错误被显示在详情页下方原生适配区域：旧会话格式不受支持，导入未激活。此前模拟器验证只有 v3，且真实模型测试直接调用仓库创建，未覆盖混合旧格式和角色详情入口；不能据此前结果推断手机升级已完整验收。

手机有 108 份旧记录：v1 21 份、v2 20 份、v3 67 份。Room 导入新增的 `schemaVersion == 3` 检查拒绝旧记录，阻止全库激活及新建。旧 JSON 仓库本来能够解码它们，保存时也不改原版本。

修复允许 v1–v3，沿用此前读取规则及默认值，保留来源 bytes 和记录版本，未知版本仍拒绝；角色详情的操作提示移至新对话按钮下。新增混合旧版本导入/新建/重启、未知版本阻止激活回归。

执行 `.\gradlew.bat :app:testDebugUnitTest --tests '*RoomConversationRepositoryTest' --tests '*NativeEntryScreenTest' :app:assembleDebug --max-workers=2 --console=plain`：9 项仓库、6 项界面测试全部通过，APK 构建成功。`git diff --check` 通过；未运行完整测试或 lint。

修复 APK SHA-256：`f4d17d14f991d0da4fcfac09eb4f076da897efb7e8cb323a34315a078409f599`。覆盖安装到手机后，同一张未重新导入的角色卡通过实际“开始新对话”按钮成功进入网页聊天并显示开场页面。手机数据已先备份；随后强停取库，对照核验 108 个旧会话、1,257 条消息、1,456 个候选的正文、候选选择、草稿及当前支持的快照字段，原 JSON bytes/来源哈希不变，数据库完整性通过，新建会话另行保留。

对照明确排除已退出当前模型的 `worldBookActivationOverrides`、`delayRemaining`、`delayStartedTurn` 字段；这些旧字段仍在原 JSON 中，不宣称本次恢复其旧运行语义。没有重导角色卡、清空手机数据、发送真实模型请求或创建提交。证据位于同一本机忽略目录的 `phone-*` 文件。
