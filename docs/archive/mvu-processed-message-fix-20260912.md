# MVU 处理后正文回写修复（2026-09-12）

样本 C-09 的世界状态栏在下一轮消失，检查点中的变量实际仍在更新。固定 MVU 上游 `handleVariablesInMessage` 返回处理后正文，并在缺少 `<StatusPlaceHolderImpl/>` 时补出标签；Player 此前只消费状态，丢弃 `processedText`。该卡的状态栏 DISPLAY Regex 没有深度限制。

## 修复边界

完整生成与重启式编辑同时消费 MVU 状态和处理后正文；开场初始化也保留上游处理后的正文。正文仍经过原有 Macro / STORAGE / DISPLAY 投影，`sourceText` 保留模型或手工输入原文。生成收尾复用该次 MVU 结果，避免再次从原文投影时覆盖补出的标签，也不重复执行变量命令。开场正文未改变时沿用已有投影。

取消、截断、缺少正常结束事件不提交 MVU 结果；“保存文字”不执行变量命令。已保存的旧候选不会自动重放或迁移，需要重新生成或重启式编辑才应用本次修复。

## 验证

执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*ChatViewModelTest' --tests '*QuickJsMvuRuntimeTest' --tests '*BrowserProgramPreparerTest' :app:assembleDebug
git diff --check
```

56 项测试，55 通过、1 跳过。跳过项需要未安装的可选复杂原件。真实 MVU + EJS 测试明确让生成器不输出状态栏标签，断言原文保持不变、canonical content 恰好含一个标签、DISPLAY Regex 实际生成状态栏内容；同时覆盖连续生成、重新生成、候选切换、文字编辑、重启式编辑、重置、磁盘恢复和不完整回复。

模拟器 Android 15 / API 35、WebView 151，使用 C-09 原件网页模式及 DeepSeek Responses，保留 32768 输出上限。一次连接失败后重试成功：

- 重新生成：原文 1598 字符、不含标签；保存正文 1624 字符、含 MVU 补出的标签。实际页面显示世界状态，时间从 14:17 更新到 14:19。
- 连续下一轮：原文 938 字符，自带标签；保存正文没有重复追加，页面时间为 14:20。
- 安装最终 APK 并强制停止后重新打开：消息候选与完整运行状态和停止前逐项一致，两轮状态栏仍显示 14:19 / 14:20。

随后已将相同 APK 覆盖安装到手机并启动，设备端包哈希与本地一致；手机上的对话实测交由用户继续。样本某备用开场还有嵌套未闭合更新块及约 10 CSS px 的 iframe 高度偏差；属于独立内容 / 排版问题，本次未修改。
