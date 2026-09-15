# 会话存储与事务实现

更新：2026-09-15。对应[性能设计](performance-design.md)中的 Room、统一会话协调与流式事务。

## 生产路径

`ChatViewModel → ConversationSession → ConversationRepository → RoomConversationStore`。

ViewModel 仅暴露 UI 状态、转发输入与处理销毁。Session 持有唯一可写 record、已提交基线、草稿、生成缓冲、待提交结果和业务版本；原生与网页操作共用提交锁。数据库只保存和读取数据，不执行模型、规则或作者代码。

Room 2.8.4 使用 KSP 生成 DAO，开启 WAL；schema 1 保存在 `app/schemas`，没有 destructive migration。选型来源：[Room 发布说明](https://developer.android.com/jetpack/androidx/releases/room)、[KSP 发布记录](https://github.com/google/ksp/releases/tag/2.3.6)。

| 数据 | 表及行为 |
| --- | --- |
| 会话头 | `conversation_head`：版本、角色/Persona/运行状态/世界书的内容引用 |
| 列表 | `conversation_summary`：角色 ID、活动时间、消息数、预览、执行模式；列表 Flow 仅观察该表 |
| 草稿 | `conversation_draft`：正文、来源、单调递增 draftSeq；普通打字不改历史或活动时间 |
| 消息与候选 | `conversation_turn`、`variant`：稳定身份、顺序、选中候选、正文/推理/检查点引用 |
| 生成诊断 | `generation`、`generation_part`：计划元数据、逐条请求消息、trace、diagnostic；正文按内容复用 |
| 原生操作 | `runtime_operation`：每个操作独立存储，保留已成功的小事务及结束状态 |
| 流式进度 | `stream_progress`、`stream_chunk`：求值上下文、有序原始事件、持久/投影水位、最近预览 |
| 内容 | `content_blob`：带版本前缀的 SHA-256 指纹和不可变 UTF-8 bytes，引用共享正文和检查点 |
| 旧数据 | `legacy_import`：来源哈希、逐份完成标记、全体激活标记 |

活动会话的读取共享相同正文与运行检查点实例，避免把完整 turn 列表重新编码成 JSON 后再解码。保存以已提交对象为基线，只编码变化的候选和计划部分。业务事务校验 revision、消息所属会话、候选所属消息、选中候选与重复身份。

## 流式提交

1. 保存 STREAMING 候选及编排计划，建立生成日志，随后开始 Provider 请求。
2. 接收的 text、reasoning、signature、独立状态确认、结束原因、usage 和诊断保持原顺序进入原始队列。正文与推理使用可增长缓冲。
3. 预览任务最多约每 50 ms 投影一次；停止到来时取消并等待预览任务。原始批次每 500 ms 保存，或在待保存载荷达到 64 Ki 字符 / 256 事件时提前保存；单个 Provider 事件不被截断。
4. Finished 只记录原因，继续消费尾随 usage；Flow 结束后再做最终投影与一次 MVU 更新。
5. 最终候选、canonical/source/reasoning、用量、运行状态和日志删除在一个 SQLite 事务内完成。事务成功才发布 COMPLETE，作者完成页面此时才可装载。
6. 保存失败保留待提交结果，进入 storageFailed，阻止后续业务操作并提供“重试保存”。最终事务重试复用已计算结果，不重复调用模型或 MVU。原生显式操作失败保留已成功提交的事实，重试保存其停止回执。

落盘临界区不可被协程取消截断：取消先等数据库结果交回 Session，之后再完成任务清理。普通草稿可独立保存，不让每次按键使业务 revision 失效；网页草稿发布直接替换 draft/revision，复用已有历史数组。

孤立内容回收安排在生成完成后的空闲任务，按数据库现存引用扫描，不放入最终提交事务。当前尚非分批增量 GC；规模很大时仍需进一步优化。

## 导入与中断恢复

首次访问逐份导入 `tavern/conversations/*.json`；每份在事务内回读并比较全部持久字段，再写入来源哈希。全部成功才激活 Room，随后只写数据库。旧 JSON 始终保留原路径、原 bytes。损坏、内容变化或不一致会停止激活并显示错误，修复来源后可续跑；已激活后不重新用旧备份覆盖新数据。

打开会话时把遗留 STREAMING 及未结束原生动作标记为 INTERRUPTED。有原始日志时，使用保存的预设、模型标识、求值时间/时区和投影版本重建正文/推理，保留签名与用量；不提交本次未完成的运行状态，不重做 MVU、模型或作者动作。恢复结果与日志删除同事务。无法识别的投影版本拒绝恢复并保留日志，尚无跨投影版本的降级展示。

## 当前边界

- 列表不加载完整历史；打开时仍加载该活动会话的全部领域记录及诊断。数据库分页与单个计划读取接口已提供，尚未把 UI 改为有界数据库窗口。
- 正文预览仍会构造原生 UI 快照后差分；原始进度独立保存，但未完成完整强类型变化集改造。
- 辅助作者生成沿用现有事件协议与显式宿主写入；上述候选终结事务对应主聊天回复。
- EJS 仍使用现有求值策略，未实现挂起续行；未改变作者 iframe 生命周期或引入引擎池。
- 进程突然退出最多丢失尚未落盘的一批原始事件/草稿，不保证后台继续生成。最终业务状态不会半提交。

## 验证

新增真实文件 SQLite 测试覆盖完整旧数据对照、损坏导入续跑、草稿隔离/过期拒绝、跨会话身份拒绝、事件连续性、最终事务回滚，以及原始文本/推理签名/尾随用量恢复。Session 测试在最终事务内部制造失败，断言 UI 和数据库仍为 STREAMING，重试后一次完成且模型调用次数不增加。

已执行并通过：

- `.\gradlew.bat :conversation-core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:lintDebug --max-workers=2 --no-configuration-cache --no-daemon`：核心 184 项全部通过；应用 259 项中 241 项通过、18 项沿用可选条件跳过；仪器测试编译、APK、lint 通过。
- 消息编辑改为先保存再发布、重试同时保存新增草稿后，再执行 `.\gradlew.bat :app:testDebugUnitTest --tests '*ChatViewModelTest' :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:lintDebug --max-workers=2 --no-configuration-cache --no-daemon`：46 项会话回归、仪器测试编译、APK 与 lint 通过。
- `tools/web-runtime` 下执行 `npm test`（43 项）与 `node --test --test-concurrency=1 test/browser/*.test.mjs`（14 项），全部通过。
- 最终 lint 为 0 errors、16 warnings；`git diff --check` 与七份相关 Markdown 的本地链接检查通过。

lint 曾发生一次 JAR 类路径读取异常；JAR 完整性检查正常，随后使用新 Gradle 进程完成验证，没有禁用规则。`adb devices` 无设备，未执行仪器测试、真机进程终止、旋转/导航、WebView 完成页面、键盘输入、PSS/耗电或真实 Provider 验收。上述 JVM/浏览器结果不代表 Android 设备耗时。
