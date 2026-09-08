# JS 动态原生 Surface：首轮实现

日期：2026-09-08。实现范围是可执行原型；真实模型编译与 Android 实机验收仍需单独完成。

## 已接通的链路

原卡程序材料 → `native-compiler-9` → 来源关联的 ES modules、Surface 入口、handler 和能力声明 → 本地结构校验、QuickJS 加载与导出检查 → 安装 → 新对话快照 → 动态原生界面 → JS 操作 → 宿主持久提交 → 重新投影。

**开放程序表达，收紧界面表达。** 模型可保留、抽取或改写 JS 函数，Player 负责原生视觉设计。没有通用组件树、布局节点、HTML renderer 或 JSON 操作码解释器。

原有 MVU、只读 EJS、固定状态绑定和草稿表单继续使用。简单绑定无需改写；多选表单仍可使用原有完整模板路径。旧适配和旧会话不被自动重编译，新增字段具有缺省值，不清空真实会话数据。

## 编译与显示契约

`NativeAdaptation.script` / `NativeCompilationDraft.script` 使用 `NativeScriptProgram(version=1)`：

| 字段 | 内容 |
| --- | --- |
| `modules` | `id/code/sourceIds/transformation`；完整 ES module 源码、原材料来源 ID、转换说明 |
| `surfaces` | `id/module/export/surface`；高层 Surface 类型与投影入口 |
| `handlers` | `id/module/export`；可调用的 JS 导出 |
| `capabilities` | 实际宿主能力声明，Kotlin 再次检查 |
| `initialState` | 原程序需要跨调用保存的私有状态初值，不复制 MVU 业务数据 |

编译器验证来源存在并启用，原卡整体 SHA-256 绑定安装对象；运行记录再以程序产物 SHA-256 绑定操作。`sourceIds` 和转换说明是可追溯关联，不证明重写等价。`compilationEvidence` 随适配保存完整来源证据。

`present(context)` 接收只读 JSON：`state/programState/draft/userName/characterName/history`。MVU 的 `state` 是含 `stat_data/schema` 的完整检查点，否则为既有 Player 状态；历史含当前选中的消息角色与文字。缺失 MVU 初始化不能自行填造业务默认值。返回声明类型的 `NativeSurfaceData` 或 `null` 隐藏。

| Surface | 首轮语义 |
| --- | --- |
| Status | 条目标题、值/描述、状态文字及操作 |
| Collection | 动态条目、稳定 key、描述、状态及逐条操作 |
| Form | 文本或单选字段、默认值、必填和提交操作 |
| Scene | 场景描述和相关条目；未增加远程图片 |
| Action Group | 描述和可执行操作 |

Surface 输出严格解码，拒绝 `children/style` 等未声明字段；输出与入口类型必须一致。条目与操作 ID 必须唯一，handler 必须已声明。条件、循环、派生值都在 JS 中执行。Form 输入属于 UI；handler 收到用户输入和当时的投影身份。

当前界面入口沿用聊天详情面板。历史消息详情仍读取消息结束时的原有状态视图；本轮未添加历史动态 Surface 回放面板。

## 宿主方法与状态语义

handler 签名为 `async function(context, args, input)`。每个宿主调用必须 `await`。handler 中原草稿文字为 `context.draftText`；`context.draft` 是操作接口。

| 声明 | 方法 | 实际效果 |
| --- | --- | --- |
| `VARIABLES_READ` | `context.variables.read()` | 读取当前完整业务状态副本 |
| `MVU_REPLACE` | `context.variables.replaceMvu(fullData)` | 直接替换完整 MVU 数据，必须保留对象类型 `stat_data/schema`；不调用 Schema、MVU 消息解析或更新事件 |
| `PROGRAM_STATE_REPLACE` | `context.program.replace(object)` | 保存显式程序私有状态；后续调用及恢复可读 |
| `DRAFT_REPLACE` | `context.draft.replace(text)` | 保存并替换输入，不自动发送 |
| `GENERATE_TEXT` | `context.generation.text({prompt})` | 当前聊天连接执行单独辅助请求，返回完整文字；不自动使用历史、预设、世界书，不追加消息或解析 MVU |

辅助请求最多预留 2048 输出 tokens，受连接的上下文/输出设置和发送前预算检查限制。只对其语义确实符合的原调用使用；不能把 Tavern Helper 的任意 `generate` 调用都换成此接口。未提供通用 Player 业务状态写入、消息编辑/创建、跨消息事件注册、任意网络、外部模块或 DOM 宿主。

原 MVU 消息更新链及其已支持回调继续运行。自定义动作的直接 MVU 替换与该链路明确分开，不能因此宣称新增了完整 Tavern Helper/MVU API 兼容性。

## 操作提交、取消与恢复

`MessageVariant.nativeOperations` 保存操作记录和按顺序产生的检查点。原 `runtimeStateAfter` 保留消息结束时的状态，操作不覆写它。`ConversationRecord.runtimeState` 是当前选中分支的活动状态，持久效果统一通过协调器更新该状态和操作提交。

每条记录包含调用 ID、投影版本、程序哈希、Surface/handler、参数、输入、运行状态、提交链和辅助请求 ID。版本覆盖会话、所选历史、当前状态、草稿和程序，避免只比较消息数量。执行前核对操作确实来自当前 Surface；handler 仍要检查源码中的业务前置条件。

- 同一会话一次运行一个操作；期间聊天、输入修改和候选切换暂时禁用，可独立取消。首轮没有实现“切换候选即取消”的控制路径。
- 宿主调用串行，写入先保存再发布、再向 JS 返回。网络等待不持有仓库存储锁。迟到调用还需通过协程状态与有效操作 ID 检查。
- 后续错误或取消不会回滚此前已保存的写入。辅助生成只在完整结束后返回文字，失败与截断不进入后续成功路径。
- 连续点击被 UI 和操作检查拒绝；重复操作 ID 被拒绝，不自动重放。不承诺远端请求恰好执行一次。
- 切换候选读取该候选的操作 head；重生成使用回复之前的状态；从历史重新开始会丢弃目标消息后的动作。只改文字沿用现有状态保留行为。
- 动作生成的未编辑草稿不会带到其他候选；用户改写后成为普通输入。
- 仓库启动时将未结束操作标为 `INTERRUPTED`，保留已保存检查点和请求记录；不续跑 Promise、不自动重发生成。

当前快照以内嵌方式保存，未增加独立数据库提交图；长期大量操作的存储压缩不在首轮范围内。既有固定 PlayerChoice/Setup 仍是各自原流程，未迁移为 JS handler。

运行状态携带最近的 `nativeCommitId`：既有选择或记忆流程在动作后继续修改状态时，其新快照保留该身份，候选恢复可以识别它比原动作提交更新。记忆失效及一次性 Setup 同步处理新增动作检查点，避免切换候选复活旧记忆或丢失初始化。单次 JS 操作最多保存 128 个持久提交。

## 执行与探测

每次投影和操作使用独立 QuickJS 实例。模块先在没有宿主绑定的环境加载，再给 handler 提供受声明限制的能力。模块只允许加载产物中确切 ID 的源码，无远程下载、字节码输入或跨调用 JS 堆。

编译服务与手工安装入口均检查真实模块加载和导出函数；这会发现语法、静态依赖、缺失导出、顶层不支持调用和加载超时。它不执行用户操作，也不证明所有状态下的 `present` 和 handler 都成功。显示时再用真实会话上下文执行投影并验证 Surface。

内存 32 MiB、栈 1 MiB，显示/加载总期限 2 秒，整个动作期限 120 秒，包含等待辅助生成。首轮还没有独立累计 CPU 时间与网络等待时间的预算；不能把动作的 120 秒写成纯网络预算。调用取消会释放执行实例。使用仓库已锁定的 [quickjs-kt v1.0.10](https://raw.githubusercontent.com/dokar3/quickjs-kt/v1.0.10/README.md) 提供的模块与异步桥机制。

## 报告调整

来源未评估仍属于不确定性，不能改写为“已支持”。本地保留逐来源证据，用户报告合并未评估数量并去重；停用来源与无关元数据不再默认生成重复缺项提示。详情先显示摘要和待支持数量，其余内容默认折叠。适配状态仍为 `PARTIAL`。

## 文件与验证

- 内容契约：`content-core/.../NativeScriptModels.kt`、`NativeCompilationInstructions.kt`、`NativeAdaptationCompiler.kt`、`NativeAdaptationValidator.kt`。
- 操作状态与恢复：`conversation-core/.../NativeOperations.kt`、`ConversationModels.kt`。
- JS 执行：`app/.../conversation/script/QuickJsNativeRuntime.kt`。
- 会话/UI：`ChatViewModel.kt`、`NativeScriptSurfaceView.kt`、`ChatScreen.kt`、`ConversationRepository.kt`。
- 编译安装/说明：`NativeCompilationService.kt`、`CharacterRepository.kt`、`CharacterLibraryScreen.kt`。

测试覆盖真实 QuickJS 的模块导入、派生集合、异步行为、取消、超时、缺失能力、非法 Surface；操作分支恢复、过期调用、连续点击、消息结束快照隔离、保存重读和 Compose 输入/操作绑定。

`NativeSurfaceCommunityTest` 按 SHA-256 找到当前本机 C-04 原件，提取其实际库存显示循环，在最小 DOM 容器下执行，与手写 Collection 投影比较零值/空值回退、条目增删和更新。原件和含原文的产物不进入版本库，摘要保存在忽略路径 `app/build/native-surface-community-audit.json`。这只是局部显示语义对照，不是完整浏览器验收，也不是模型自动编译成绩。

本轮环境没有联网实验的 `.env`，未执行真实模型编译；Android 实机验证按用户要求推后。

后续实机需覆盖 Android QuickJS 模块加载与取消、详情面板长列表/键盘输入，以及操作等待期间进入后台和系统终止进程后的恢复。桌面真实 QuickJS 与仓库存储测试不能替代这些 Android 生命周期与 JNI 验证。

### 最终本地结果

| 模块 | 测试数 | 通过 | 条件跳过 | 失败 |
| --- | ---: | ---: | ---: | ---: |
| content-core | 66 | 64 | 2 | 0 |
| conversation-core | 128 | 128 | 0 | 0 |
| app | 173 | 158 | 15 | 0 |
| 合计 | 367 | 350 | 17 | 0 |

跳过项涉及可选原件、手工适配夹具和联网实验；新增 Surface、操作、保存失败重试、报告折叠和本地 C-04 库存对照测试均实际运行。Lint 为 0 errors / 11 warnings，剩余告警涉及既有依赖版本、应用图标和 KTX 用法。Debug APK 构建成功，未安装到设备。

执行过的最终检查：

```powershell
.\gradlew.bat :content-core:test :conversation-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

完整检查成功后，修正了新 Form 输入容器的 Lint 告警，使用只读 Map 与整体替换更新，并再次执行以下应用检查，结果 `BUILD SUCCESSFUL`：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

本地日志位于忽略路径 `build/native-surface-final.log` 与 `build/native-surface-ui-final.log`；APK 为 `app/build/outputs/apk/debug/app-debug.apk`。Markdown 链接、围栏及 `git diff --check` 检查通过。未创建 Git 提交。

### 配置连接后的真实模型实验

用户补充本地连接配置后，使用配置中的 `gpt-5.6-terra`、`OPENAI_RESPONSES` 执行真实编译。原始请求、响应和审计保存在忽略目录 `app/build/native-compilation-runs/`；未修改模型响应来取得通过结果。

| 实验目录 | 样本 / 版本 | 模式 | 结果 |
| --- | --- | --- | --- |
| `1788851348835` | C-04 / v8 | LIVE | 模型完整返回，但同一来源出现两条评估，被本地拒绝；且固定列表遗漏原 JS 兜底逻辑 |
| `1788851626481` | C-04 / v9 | LIVE | Ready，模块加载、安装、初始投影成功；新增测试的审计序列化错误导致测试失败 |
| `1788851843573` | C-04 / v9 | REPLAY | 修复测试后重放上一行原始响应，安装、重载、初始投影和三组库存检查全部通过 |
| `1788851891633` | C-03 / v9 | LIVE | 编译、安装、五字段表单、多选、空值兜底、真实聊天生成与会话重载全部通过 |

v8 使用 26,838 输入 / 3,245 输出 token；v9 使用 26,974 输入 / 4,018 输出 token，均以 `completed` 结束。重放没有再次调用编译模型。

C-03 的 v9 编译使用 8,187 输入 / 969 输出 token，以 `completed` 结束；普通聊天另用用户配置的 `deepseek-v4-pro`，实际完成回复。该卡保留既有多选草稿表单，不强制改造成当前仅支持单选的 Script Form。

v9 明确每个来源最多一条评估；同一来源含已恢复与未支持行为时，用一条 UNCERTAIN 说明双方并关联恢复目标。对固定绑定无法表达的条件、格式化和兜底计算，明确要求生成 JS Surface，不能将语义丢失笼统归为展示差异。此处调整的是编译指导，没有放宽安装校验。

C-04 生成一个 ES module、一个 Status、三个 Collection，保留原 MVU Schema 和四个 EJS 模板，没有复制 MVU 业务状态，也没有凭空生成操作 handler。实际执行原始生成代码，对比初始会话与持久重载的投影，并测试空库存、零数量、空描述、条目删除与数量/描述变化。检查保留原卡 `数量 || 1`、空描述兜底；测试状态仅用于只读投影，没有执行业务写入。

这证明该样本的生成、安装和选定显示行为可运行，不证明整卡行为等价、真实聊天中的 MVU 更新、任意作者 JS 或设备界面均已验证。实机验证继续推后。

本轮通过的命令：

```powershell
# TAVERN_COMPILER_LIVE=1，按样本哈希选择 C-04，REPLAY_FILE 指向上述 v9 原始响应。
.\gradlew.bat :content-core:test :app:testDebugUnitTest --tests '*NativeCompilationLiveTest' --rerun --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

其中 content-core 为 64 通过、2 条件跳过；选定联网测试以 REPLAY 模式通过。先前的 APK 是 v8 构建，本轮提示词变更后尚未重新打包 APK。

C-03 清除源哈希和重放参数、保留 `TAVERN_COMPILER_LIVE=1`，执行以下命令并通过：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*NativeCompilationLiveTest' --rerun --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

本轮日志：`build/native-compiler-v8-live-c04.log`（失败）、`build/native-compiler-v9-live-c04.log`（测试记录失败）、`build/native-compiler-v9-replay-c04.log`（通过）、`build/native-compiler-v9-live-c03.log`（通过）。
