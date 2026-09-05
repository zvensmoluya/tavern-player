# 原生关系分析实验（2026-09-05）

> 社区素材使用中性编号，见 [样本编号约定](community-samples.md)。

## 结论与范围

有限的“分析 → 保存 → 注入”流程已通过真实模型和 Android 验证，可以作为实验能力保留。它不等同于支持完整 RUBY / LittleWhiteBox，也没有证明关系分析必需或普遍改善聊天。

用户明确不要求搬迁外部插件。当前支持哈希绑定的分析要求和静态资料、按助手回复计数刷新、当前连接上的额外模型请求、候选所属的分析快照、固定原生查看与刷新入口。只有适配声明 `memories` 才启用；普通卡不会增加请求。没有任意 JS、第三方插件运行时、可编程调度、检索系统或原插件配置面板。

## 验证设计

- 来源：复杂样本 C-02 的真实已保存对话，开场与两轮普通日常交流，共 5 条消息。沿用原始正文、卡片快照和数值状态，不伪造聊天进度。
- 三项分析分别采用原卡的角色 A、角色 B、双人关系要求；资料引用采用人工选定的通用人物条目，别名差异见 [第二张卡审计](native-second-card-fidelity-audit.md)。
- 使用授权的 `deepseek-v4-pro`。分析使用独立请求、temperature 0.2、reasoning LOW、输出上限 8192；不继承源脚本的连接、越狱和任意生成配置。
- 在原生详情手动生成三份分析，重建仓库及聊天界面后恢复，再手动刷新三份。第二次请求逐项验证包含对应上次分析。
- 使用相同卡片、历史、下一条输入、编译时间和社区预设样本 P-01，分别编译无分析和有分析的下一轮请求。两条请求的生成设置一致；最终输出上限 16384、reasoning LOW。
- 对照直接调用现有模型网关，保存正文和实际请求，不将输出状态块结算为新的对话状态。这是关系分析对后续生成的探索性对照，不是完整状态协议验收。

输入为普通的午后买纸墨情节，要求保持现有关系，不突然和解或制造新冲突。未运行原版 LittleWhiteBox，未尝试复刻其摘要召回。

## 实际结果

`NativeGameplayLiveAndroidTest#savedSecondPressureMemoryExperiment` 与 Android 占位符回归联合运行，`OK (2 tests)`，349.431 秒。

真实记录位于忽略目录 `app/build/native-gameplay-rounds/1788618710850/`：

- `input.json`：输入对话；
- `analysis-request-1..6.json`：六次分析的实际计划；
- `analysis-first.json`、`analysis-refresh.json`：两次保存后的完整对话；
- 同名前缀 PNG：统一详情面板中的分析查看；
- `without-analysis-request.json`、`with-analysis-request.json`：两条实际对照请求；
- `without-analysis.txt`、`with-analysis.txt`：两条原始生成文本；
- `response-1..8.sse`：原始模型响应，不含请求认证头。

三项首次输出为 479、358、356 字符；刷新后为 494、343、355 字符。六次均完整结束，保存没有新增聊天消息、修改数值状态或替其他候选写入分析。重新打开仓库与聊天界面没有额外触发请求。

强制停止目标应用后，在新的 instrumentation 进程运行 `NativeMemoryAndroidTest`，`OK (2 tests)`，2.394 秒；存盘 Runtime、消息候选和末条候选分析快照与之前一致。已检查详情截图，分析通过固定标题、元数据和展开按钮展示；正文作为普通文本显示，源格式标签也保持可见，不执行 HTML。

| 请求 | 输入 tokens | 输出 tokens（含推理） |
| --- | ---: | ---: |
| 首次三项分析合计 | 28,469 | 4,616 |
| 刷新三项分析合计 | 29,347 | 2,278 |
| 下一轮，无分析 | 13,774 | 6,252 |
| 下一轮，有分析 | 14,640 | 2,472 |

本次三份分析使下一轮输入增加 866 tokens，约 6.3%。两条请求原有的 21 项世界书激活集合相同，有分析请求仅多了三项分析条目，没有挤掉原条目。这不代表长历史或其他预算配置下也不会发生裁剪。

## 对效果的观察

- 无分析：角色拒绝代购纸墨，继续维持距离，另一角色自然参与日常对话；剧情可以继续。
- 有分析：同一角色先拒绝，再说出“青墨”半句，仍保持疏离；另一角色转向提出自己的采买和同行要求。
- 两条回复都没有突然和解；后一条体现了更多微小让步，但只有一组随机生成，无法把差异确定归因于分析，更不能判定优劣。
- 分析输出不仅回顾事实，还包含后续互动指导，例如如何停顿、接物或回应。这属于额外的角色演绎引导，可能帮助保持风格，也可能固定动作套路、放大原分析中的推测。它不是客观事实数据库。
- 两条对照回复都输出了当前未支持的 `delta` 数值操作。本实验没有解决这一独立协议缺口，不能据此宣称复杂样本 C-02 完整通过。

## 本地验证与故障修复

本地总计 286 项测试，284 项通过、2 项跳过、0 失败，覆盖 content-core、conversation-core、app；Debug APK、测试 APK 与 lint 通过。

新增分析测试包括来源与哈希校验、周期、过时结果拒绝、取消及重试、候选隔离、历史编辑失效、持久化、字面注入和关键词作用。两个完整周期使用确定性回复序列验证：触发位置为 5/10/15 与 20/25/30，重新读取不会重复触发；这不是 30 轮真实模型聊天。

实际发现并修复：

1. 分析文本曾在 Prompt 组装时再次经过宏展开，现改为字面注入；测试确认 `setvar` 字符串不能修改本地变量。
2. 状态确认曾阻塞独立文本分析。现在允许读取已完成的对话，并在证据中明确 `stateConfirmedForLastReply=false`；数值状态仍未确认，分析不能代为确认或修改它。
3. 占位符正则的右花括号未转义，桌面 JVM 接受而 Android ICU 拒绝。现显式转义，并增加不联网的 Android 回归测试，验证只替换一次 user/char。
4. 为分析保存增加不可取消的提交区间，并在落盘期间阻止输入及重置；回归测试在保存内部触发取消，确认已保存的一份与界面一致，剩余分析不再请求。

失败记录也保留：`1788615527675` 在普通聊天的 delta / 截断状态确认处失败，未进入分析；`1788618270708` 与 `1788618523554` 暴露 Android 正则问题，未发出分析请求。后续通过记录不覆盖这些失败。

主要执行命令：

```powershell
.\gradlew.bat :content-core:test :conversation-core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-parallel
.\gradlew.bat :app:testDebugUnitTest --tests '*ChatViewModelTest' --no-parallel
.\gradlew.bat :conversation-core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-parallel
.\gradlew.bat :app:assembleDebugAndroidTest --no-parallel
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-parallel
.\gradlew.bat :app:lintDebug --no-parallel
```

联网测试显式需要目标应用 `cache/native-live.env` 和真实保存对话 `cache/native-live-replay.json`；进程恢复测试显式需要 `cache/native-memory-checkpoint.json`。只在指定模拟器运行，未使用连接的实体手机。测试配置不进入 Git，测试完成后移除设备临时配置。

## 保留的限制

- 分析在回复结束后串行执行，期间不能发送下一轮，可停止；没有后台并发任务系统。
- 当前读取完整选中历史。长历史超限时保留旧分析并报错，没有自动压缩、摘要链或向量检索。
- 失败允许手动重试；不补跑任意历史周期，不复刻原插件的调度与幂等策略。
- 来源资料选择、分析输出质量和长期演绎收益尚无普遍保证；本轮不将它们升级为自动编译的前置条件。
- 这次交付是可复现的原生分析实验，不是完整卡片还原或自动编译完成。
