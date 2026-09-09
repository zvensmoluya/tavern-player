# 当前编译产物的 Android 实测

> 归档说明（2026-09-09）：保留阶段设计、源码审计与验证证据；文中的“当前”“下一步”和版本号指记录当时，不作为现行产品决策或完整运行契约。现行入口见[文档导航](../README.md)、[产品边界](../product-direction.md)和[实现架构](../architecture.md)。

日期：2026-09-08。样本使用本机现有 C-04，原件哈希见 [样本编号](../reference/community-samples.md)。本轮使用当前 v11 自动编译产物，不修改模型返回的 JS，不使用手工适配替代。

## 编译与首轮设备结果

编译实验 `1788873365322` 使用本机编译连接 `gpt-5.6-terra`：25,537 输入 / 4,768 输出 tokens，其中推理 3,106，completed / Ready。原始响应、适配、初始投影与会话保存在忽略目录 `app/build/native-compilation-runs/1788873365322/`。

产物保留 MVU 和四段 EJS，生成一个模块、三个 Surface 入口、面板开关与页签 handler。初始面板关闭，因此初始投影只有操作组。既有 `NativeCompilationLiveTest` 在假定集合初始可见的合成库存检查处失败；不能将该失败直接判为编译器丢失库存。测试还硬编码了 `x` 数量分隔符，本次生成使用 `×`。原失败保留，不修改生成结果或计为完整通过。

新增显式启用的 `NativeSurfaceLiveAndroidTest`，使用生产 ChatViewModel、QuickJS、聊天生成器与磁盘仓库。连接配置通过应用私有目录传入，读取后立即删除，不打入 APK；原件和实验响应不进入版本库。

Android 15 / API 35 模拟器首次实验 `surface-live-1788873638828`：

- 实际点击生成的面板按钮，handler 执行成功。
- DeepSeek 两次真实聊天均完成，获得三份物品后取用一份，MVU 数量为 3 → 2；EJS 编排执行，历史助手检查点仍为 3。
- 使用保存状态直接运行原始生成 JS，库存投影正确显示剩余 2 份。
- 生产 UI 等待动态库存出现超时。没有投影错误提示；聊天结束后动态 Surface 没有恢复。

证据在忽略目录 `app/build/native-surface-device/1788873638828/`，包括请求计划、两轮会话、投影、最终 UI 状态和截图。该失败发生在设备界面验证，尚未执行本轮候选切换步骤。

## 发现与修复

`refreshNativeSurfaces` 在生成期间清空界面。聊天收尾先刷新正文缓存，此时 `running` 仍为 true，随后只把 `running` 置为 false，没有再次请求 Surface 投影。因此成功、失败和取消后，动态界面均可能保持空白，直到后续输入或其他操作触发刷新。

修复在聊天与手动记忆生成的最终收尾处重新请求投影。编译契约、生成代码与 MVU 更新逻辑不变。新增确定性回归覆盖聊天完成、失败和取消；修复前运行该回归，复现等待 Surface 超时。

## 修复后设备复测

同一份未修改的编译产物，实验 `surface-live-1788874083931` 返回 `OK (1 test)`，耗时 36.171 秒。聊天模型为本机连接 `deepseek-v4.1-flash-expires-on-0910`。

| 真实请求 | 输入 / 输出 tokens | 验证结果 |
| --- | --- | --- |
| 获得物品 | 7,777 / 1,285 | MVU 新增记录，数量 3 |
| 取用一份 | 7,758 / 275 | 数量 2，历史检查点仍为 3，动态界面实际可见 |
| 重生成取用回复 | 7,758 / 428 | 从回复前状态计算，仍为 2，没有重复扣减 |

三个请求均 completed。切换到旧候选、再切回新候选，等待持久化后分别核对完整运行状态；重新创建仓库并读取记录，状态一致。直接对持久重载状态执行投影也与保存前一致。没有调用独立状态确认模型，没有第二份 Player 业务状态。

人工查看 `inventory.png`，确认原生面板显示新增物品及数量 2。截图同时显示页签按钮与状态项；库存位于较长详情面板下方，需要滚动。证据在忽略目录 `app/build/native-surface-device/1788874083931/`。

已执行：

```powershell
# 真实编译：TAVERN_COMPILER_LIVE=1，按 C-04 哈希选源。
.\gradlew.bat :app:testDebugUnitTest --tests '*NativeCompilationLiveTest' --rerun --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
# 修复前复现：1 项失败，等待动态面板超时。
.\gradlew.bat :app:testDebugUnitTest --tests '*ChatViewModelTest.native surfaces return*' --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
# 修复后的聊天回归、设备产物和静态检查。
.\gradlew.bat :app:testDebugUnitTest --tests '*ChatViewModelTest' :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
# 用 adb install -r 保留数据安装生产与测试 APK，注入应用私有测试配置后运行。
adb shell am instrument -w -e surfaceLive 1 -e class io.github.zvensmoluya.tavernplayer.conversation.NativeSurfaceLiveAndroidTest io.github.zvensmoluya.tavernplayer.test/androidx.test.runner.AndroidJUnitRunner
```

修复后的 ChatViewModel 回归 40 项全部通过，生产与测试 APK 构建成功，Lint 为 0 errors / 12 warnings（依赖版本、应用图标及既有 KTX 建议）。最终结果见日志 `build/native-refresh-fixed.log`；设备原失败与成功日志分别为 `build/native-c04-device-live.log`、`build/native-c04-device-fixed.log`。`git diff --check` 通过，未创建提交。

## 验证范围

实测采用明确要求完整变量更新块的定向输入，用于核对状态链路，不代表自然聊天中的自动协议遵循率。设备为模拟器，未覆盖物理设备、系统杀进程、MainActivity 完整导航、整卡语义等价或所有页签行为。
