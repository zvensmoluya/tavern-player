# 模拟器聊天实测与修复（2026-09-12）

承接[角色库修复收尾](library-startup-resume-20260912.md)。基线为 `f8eb490` 加工作区未提交修复；
本轮用实际应用界面、adb 输入事件和 WebView DevTools 观察验证，不以主机浏览器模拟替代设备结果。

## 环境与范围

- `emulator-5554`，Android 15 / API 35，WebView `151.0.7922.202`，Gboard。
- C-03 原卡，SHA-256 `0d9f771474cab7f170f33700e9a0db6b87df96451a4da473c0cfa9f8b70e8c22`；样本映射见[编号表](../reference/community-samples.md)。使用内置“默认”Preset，网页模式，无 Native 适配。
- 使用本机 `.env` 默认 DeepSeek 配置，`OPENAI_RESPONSES` / `deepseek-flash`。目录与独立短探测返回 HTTP 200；短探测的 64 token 上限导致 `incomplete`，同时得到 `OK` 正文，不是 Key 失效。
- 应用得到两轮正常回复和两份重新生成的完整候选；另验证一次正文尚未到达时的主动停止。
- 保留原有应用数据，测试使用新增对话。测试连接保存在应用自身凭据存储中，没有将 Key 写入仓库或文档。

模拟器最初使用 Gboard 手写浮动工具条。为验证完整键盘，临时关闭系统 `stylus_handwriting_enabled`，
重启输入法后再测；结束时删除临时设置，恢复原先未显式设置的状态。`show_ime_with_hard_keyboard` 原值为 1，保持不变。

## 两个新增问题及修复

### 聊天页系统返回退出到桌面

在键盘已收起、生成已完成时按 `KEYCODE_BACK`，前台变成系统 Launcher；顶部“返回”则能回角色详情。
原因是 `ChatScreen` 没有注册系统返回处理。

为聊天页添加 `BackHandler`，复用 `actions.back()`；忙碌时消费返回，沿用顶部返回按钮禁止离开的行为。
修复后实际复验：空闲时回角色详情；生成开始后按返回仍在聊天页，可以继续停止；完整键盘在场时第一次返回
只收起键盘，第二次返回才回角色详情。

### 作者字段获得焦点后无法输入

点击 C-03 表单底部输入框，键盘会弹出，但 adb 输入文字后 DOM 字段长度仍为 0，字段还留在可见区域之外。
`WebMessageView` 在 WebView 获得焦点时调用全局 `focusManager.clearFocus()`，把刚获得的 View 焦点也清掉了。

移除该回调及无用的 `LocalFocusManager` 引用，交由 AndroidView 的焦点互操作处理。相同原卡和字段上，
真实键盘输入 `Tea only.` 后 DOM 长度为 9；再切原生输入栏输入 `Native focus check`，切回作者字段追加
` Extra`，两边分别保留各自文字，只有作者字段持有焦点。作者字段此时完整位于消息可见区域内。

本次两个修复只改 Kotlin UI，不改网页资产与指纹。旧测试对话在覆盖安装后仍可直接恢复。

## 已通过的实测

| 操作 | 证据 |
| --- | --- |
| 原卡表单提交 | DevTools 填写字段并点击原页面确认按钮，调用原作者处理器；原生草稿与落盘 draft 均为 216 字符，发送可用 |
| 真实两轮对话 | 原生发送按钮触发 DeepSeek；正文分别为 229、140 字符，状态 COMPLETE，最终 5 条消息 |
| 思考展开保持 | 第一轮每 200 ms 采样；思考长度经历 11 个不同值，已展开后的采样中没有折回，开场 iframe 保留 |
| 流式回看历史 | 第二轮从底部向前 300 CSS px 处观察；正文与思考增长时滚动位置未被拉回底部 |
| 完整键盘避让 | 网页高度 646 → 334 CSS px；原生输入框底部由屏幕 y=2316 移到 y=1496，发送按钮保持在键盘上方 |
| 候选生成与切换 | 第二轮最终保存 3 个完整候选，正文长度 140、231、202；前后切换时 DOM 正文去空白后的 SHA-256 与选中候选一致 |
| 主动停止 | 确认网页已显示“正在生成…”后按系统返回，仍留在聊天页，再点原生停止；显示“已停止生成”，已有完整候选保留 |
| 第一次进程恢复 | force-stop 后从角色详情打开原对话；5 条消息、完整 turns、runtimeState 与 draft 均与保存记录一致 |
| 带候选和草稿恢复 | 选中第 2 个候选，输入 `Draft recovery check.` 后强停并重开；3 个候选、选中索引、运行状态、草稿全部一致，草稿也显示在原生输入栏；正文 DOM 哈希仍匹配 |
| 网页与原生输入切换 | 修复后两边均可输入，互不覆盖；作者输入框随浏览器滚动进入键盘上方 |

第二轮完成时开场表单被移除，核查原卡 Regex 为 `minDepth=0`、`maxDepth=2`；五条消息时开场深度已超界，
这是显示投影改变，不是未变化的页面被流式重建。移除时滚动位置补偿约 706 CSS px，与原 iframe 高度一致，
最终仍停留在历史区域。不要将此样本行为记成新的重建 bug。

停止发生在正文出现前；现有 `finishFailure` 会删除空候选，所以没有新增 CANCELLED 候选。本轮确认的是
取消交互、停止提示及原候选保留，没有验证带部分正文的 CANCELLED 气泡。

## 验证与产物

实际执行并通过：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*ChatScreenTest' :app:assembleDebug
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest --tests '*ChatScreenTest'
git diff --check
```

最终 ChatScreenTest 为 19 项通过、0 失败、0 跳过。每次 Kotlin 修复后均覆盖安装并走实际界面复验。
最终 APK SHA-256：`84403697ec09d84ce02e7f5410c683c93e4109c69d7b1b4b435f3db7e7432654`。

本机忽略目录 `app/build/live-verification-20260912/` 保存 UI 层级、屏幕图、流式采样摘要、
候选正文哈希和恢复对比结果。DevTools 端口转发在结束时移除。产物及原始会话不提交到版本库。

未执行全模块测试、lint 或完整 instrumentation 套件；本轮设备操作为手工驱动的自动化输入。
没有覆盖实体手机、Android 26–29、其他输入法、复杂 MVU/EJS 卡的完整玩法、进程在生成中被杀、
以及已有部分正文时的停止。之前记录的 Responses `incomplete_details.reason` 丢失仍是独立待办。

未创建提交或推送。
