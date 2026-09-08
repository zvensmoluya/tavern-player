# JS 动态原生 Surface：首轮实现

日期：2026-09-08。实现范围是可执行原型；v9 真实模型实验结果见下文，Android 实机验收仍需单独完成。

最新 [原 MVU 加载与开局流程保留](native-opening-workflow-20260908.md) 记录 v13–v15：修复已锁定 helper 的动态导入与原 schema 类型保留，增加安装前真实初始化检查，以及只读开场候选上下文。C-01 的 v14 原始产物通过六组开局对照；v15 最终请求受网关响应大小上限阻断，文案修正未获整体验证。

后续已用当前 v11 产物完成 [C-04 Android 模拟器实测](native-surface-device-verification-20260908.md)：发现并修复聊天结束后动态面板未恢复的问题，两轮真实聊天、重生成、候选切换和磁盘重载通过。物理设备与自然聊天协议遵循仍未覆盖。

随后进行了 [DeepSeek C-01 / C-02 复杂卡编译与 reasoning 记录](native-deepseek-complex-card-experiment-20260908.md)：C-02 安装与初始化通过；C-01 耗尽 16,384 输出额度且未返回最终 JSON。该实验未改 v11 提示词。

## 已接通的链路

原卡程序材料 → `native-compiler-11` → 来源关联的 ES modules、Surface 入口、handler 和能力声明 → 本地结构校验、QuickJS 加载与导出检查 → 安装 → 新对话快照 → 动态原生界面 → JS 操作 → 宿主持久提交 → 重新投影。

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

编译器验证来源存在并启用，原卡整体 SHA-256 绑定安装对象；运行记录再以程序产物 SHA-256 绑定操作。`sourceIds` 和转换说明是可追溯关联，不证明重写等价。`compilationEvidence` 随适配保存模型报告的具体缺口与本地提取警告，不是逐来源评级或行为等价证明。

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
| `MVU_REPLACE` | `context.variables.replaceMvu(fullData)` | 直接替换完整 MVU 数据，必须保留 `stat_data` 对象及原 `schema` 的值和类型（固定 Zod helper 可使用字符串标记）；不调用 Schema、MVU 消息解析或更新事件 |
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

### v10：减少模型的报告任务

编译草案删除 `assessments`（来源评级、理由、结果指针），改为可省略的 `limitations: List<String>`，只报告无法保留的具体功能。模块来源、MVU/EJS 和表单引用仍按原有规则校验；不调整原卡材料筛选或宿主执行语义。

本地合并重复缺口并忽略空项，不因缺口重复而拒绝主体产物；没有缺口说明也不自动声明完整支持。停止生成逐来源“未评估”提示，兼容状态仍为 PARTIAL，保留统一的未证明行为等价提醒和输入覆盖警告。

这是开发期编译草案契约变更，旧 v9 模型原始响应不可直接按 v10 草案重放；已安装的 NativeAdaptation 和会话格式未变，不会被重新编译。以上 v9 实验记录仍只证明 v9 的结果。v10 契约调整当轮未发起新的付费请求；后续用户授权的真实对照结果见下节。

本轮执行 `./gradlew.bat :content-core:test --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'`：64 通过、2 条件跳过。覆盖缺口去重、缺省报告不生成未评估提示，以及生成模块的来源与入口校验。未重新打包 APK、未执行实机验证。

### v10：Terra 与聊天连接 DeepSeek 的真实对照

用户随后授权调用测试，使用同一 C-04 原卡和 v10 编译契约，分别使用编译连接 `gpt-5.6-terra` 与当前聊天连接 `deepseek-v4.1-flash-expires-on-0910`。后者是用户当前配置的模型标识，不沿用先前的 v4-pro。

两份本地 `compiler-request.json` 完全一致，SHA-256 为 `bc3098adb967a8fbed54eb8d7d2246dc610ce4128e1d4f67dec64639b9a9ffcb`；协议均为 OPENAI_RESPONSES，计划为 HIGH 推理、128,000 上下文、16,384 输出预算。相同计划不代表服务商底层采用相同的推理实现或 token 计数。

| 实验目录 | 模型 / 模式 | 输入 token | 输出 token（包含推理） | 推理 token | 结果 |
| --- | --- | ---: | ---: | ---: | --- |
| `1788853432101` | Terra / LIVE | 26,823 | 5,465 | 4,043 | Ready，安装和初始投影成功；库存测试误要求 key 等于物品名而失败 |
| `1788853620665` | DeepSeek / LIVE | 27,692 | 14,988 | 13,523 | 首次请求及安装、投影、库存变化、重载测试全部通过 |
| `1788853714584` | Terra / REPLAY | — | — | — | 修正测试的身份假设后，重放原始响应全部通过，无再次模型调用 |

两个 LIVE 均以 completed 结束。Terra 生成一个 Status 和三个 Collection；DeepSeek 生成两个 Status 和两个 Collection。两者均生成一个模块、保留四个 EJS 模板与原 MVU Schema，未生成业务 handler。

库存测试现在按可见物品名匹配条目，并检查内容更新、删除与重现后的 key 稳定性，允许原名或稳定派生 key，不再要求实现必须使用原名。继续检查条目数量、零数量兜底、空描述兜底和数量/描述变化；没有编辑模型响应取得通过。

本次 v10 Terra 比此前 v9 的输入少 151 token，但报告的推理从 2,649 增至 4,043；单次样本不能证明删除来源评级降低了推理成本。DeepSeek 本次能够完成样本，但推理 token 更多；不同服务商计数与计费规则可能不同，未取得本轮账单，不能从 token 数直接断言谁更便宜。DeepSeek 输出使用了约 91.5% 的本次预算，也不足以推断更复杂脚本的成功率。

执行命令（分别配置两组连接，以及 Terra 原始响应重放）：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*NativeCompilationLiveTest' --rerun --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

日志位于 `build/native-compiler-v10-live-c04-terra.log`（测试身份假设失败）、`build/native-compiler-v10-live-c04-deepseek.log`（通过）、`build/native-compiler-v10-replay-c04-terra.log`（通过）。本次仍是只读显示样本，未验证高级业务 handler、完整聊天 MVU 更新或实机界面；未重新打包 APK。

### v11：澄清身份和职责，保留通用语义原则

区分配置声明 ID 与动态 Surface 条目 key：后者允许中文，优先复用原数据身份，只在唯一性或长度要求需要时派生，不要求哈希。规则与现有验证器一致，不改变执行契约。

把来源样本的具体兜底表达式提示改为通用的缺失值、空值、类型转换和副作用顺序保留原则。明确先保留行为再选展示，并指出布局、刷新调度、持久化和入口校验由 Player 承担；合并 MVU 直接写入语义的重复说明，仍保留其不触发 Schema/回调的关键限制。

这轮只调整编译指导；不改变原卡提取、运行时、输出数据结构和推理强度，不新增逐步分析报告。不声称 prompt 字数或推理成本下降。接下来优先用未参与调整的样本验证材料覆盖、宿主能力和行为保真度，而非围绕既有输出继续追加特例。先前模型实验仍对应 v10，不能当作 v11 的联网验证。

验证：`./gradlew.bat :content-core:test --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'` 通过（64 通过、2 条件跳过），`git diff --check` 通过。未运行新的付费编译、未重新打包 APK、未执行实机验证。

### v11：新增样本 C-05 的 DeepSeek 编译

用户提供新卡后，使用当前聊天连接 `deepseek-v4.1-flash-expires-on-0910`、HIGH 推理和未针对该卡调整的 v11 契约执行一次真实编译。C-05 SHA-256：`7df0b58b2a46ac9ae2169c45f715a58760ebdad63017c5a860222d808beabe32`。原卡含 15 条世界书，其中 9 条正文未发送；动态界面源码约 1.7 万字符。材料使用现有提取逻辑，没有为本次实验定制筛选。

实验目录 `app/build/native-compilation-runs/1788856126333`：输入 21,992、输出 9,349（含推理 8,087）token，结束原因为 completed。模型返回了语法有效的 JSON，但顶层额外填写 `version: "native-compiler-11"`；NativeCompilationDraft 没有该字段，严格解码返回 COMPILER_INVALID_JSON。这是草案契约错误，未观察到内容审核拒绝或输出截断。

输出包含 MVU Schema 引用、六个固定状态绑定和一个角色 Collection 的 JS 模块；这些只是产物内容观察，未通过安装、模块加载、初始化或投影测试。原始响应未修改，也没有删除字段后计为成功。该样本尚不能宣称编译成功或行为保真。

执行 `./gradlew.bat :app:testDebugUnitTest --tests '*NativeCompilationLiveTest' --rerun --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'`，显式选择 C-05 和聊天连接，结果 1 项失败；日志 `build/native-compiler-v11-live-c05-deepseek.log`。未追加付费重试、未打包或安装 APK。测试入口补充按哈希区分 C-05，避免把所有新卡误记为 C-04。

### v11 入口规范化与 C-05 原始响应重放

仅对顶层 `version` 做窄范围处理：必须是与当前 NativeCompilationInstructions.VERSION 完全一致的字符串，识别后移除并在 compilationEvidence 中记录“输入规范化”。不匹配、非字符串或 null 返回 COMPILER_VERSION_MISMATCH。其他未知字段仍严格解码；嵌套 script.version 保留原来的执行契约校验，不启用全局 ignoreUnknownKeys。没有调整 prompt、修改原始响应文件或生成 JS。

原始实验 `1788856126333` 离线重放至 `1788856706411`，结果 Ready。安装、MVU 初始化、初始角色集合投影、六个状态绑定及持久重载全部通过，没有追加模型费用。首次格式失败记录仍然有效；修复后的结果表明当时多余的元数据字段阻断了可运行产物。

补充中性数据诊断直接执行原 parseObjectBlock 和原始生成 JS：在解析器输入边界，原函数将连续空白压缩；生成 JS 保留连续空白。只有三个字段的测试记录在原解析器中保留三项，生成描述固定展开为十六行，含缺失值占位。此诊断没有模拟原宿主的宏序列化、完整 DOM 或 Schema 初始化，不能将合成的缺失字段情况当成真实会话必然故障；但证明两条处理路径并非逐项相同。私有诊断文件为 `build/native-c05-parser-probe.cjs`、`build/native-c05-parser-input.json`、`build/native-c05-parser-audit.json`。

验证命令：

```powershell
# 原目录首次构建因中间 class 文件缺失失败，之后用忽略目录中的 init 脚本隔离各模块输出。
.\gradlew.bat --init-script build/native-normalization-isolated.gradle --no-configuration-cache :content-core:test :app:testDebugUnitTest --tests '*NativeCompilationLiveTest' --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
node build/native-c05-parser-probe.cjs
```

隔离重放日志：`build/native-compiler-v11-replay-c05-isolated.log`，BUILD SUCCESSFUL；核心测试 65 通过、2 条件跳过，应用选定重放测试通过。核心回归覆盖版本回显记录、错误/非字符串版本拒绝、未知执行字段拒绝及嵌套脚本版本拒绝。未重新打包 APK、未执行实机或整卡玩法验收。

### v11：移除请求材料中的编译版本元数据

Program View 不再附带顶层编译器 `version`，避免把本地追踪字段混入模型需要理解的源码材料。编译版本仍保存在本地 GenerationPlan.presetId 和实验 metadata.compilerVersion 中；脚本运行契约 `script.version=1` 保持不变。此前对同版本回显的窄范围规范化继续保留，其他未知字段仍严格校验。本次没有改变源码内容、筛选或执行语义。

验证：`./gradlew.bat --init-script build/native-normalization-isolated.gradle --no-configuration-cache :content-core:test --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'` 通过，65 通过、2 条件跳过；原材料回归增加顶层无 version 检查。日志 `build/native-source-envelope-tests.log`。未调用模型、未重新打包 APK。

### 接收流程拆分与请求材料精简

新增 NativeCompilationReceiver，按三层处理结果：

1. 格式整理：接受 BOM、完整的无语言/JSON 代码围栏及带无歧义前后说明的单一 JSON 对象；处理已知版本回显。空缺/null/空白摘要使用本地中性说明，过长摘要截短至 1024 字符。记录每项规范化，不修改 JS 或执行字段。
2. 执行契约：由序列化描述符检查字段路径、未知字段、必需字段、类型和枚举，最多返回 32 条定位错误。依然严格解码、校验引用和运行能力；不丢弃未知执行字段、不猜测补齐截断 JSON、不从多个结果中挑选一个。原卡哈希和来源映射仍由本地装配。
3. 运行验证：应用继续执行 QuickJS 加载与导出检查；加载失败与协程加载超时使用不同错误码，并明确此前执行契约已通过。安装、MVU 初始化、投影和会话重载由后续验证检查，不把结构通过等同于行为等价。

请求材料删除 sources 的本地 path/bookId/entryId/regexId、worldBooks 的重复 bookId/entryId 和正文字数，以及 preservedLocally 统计。source.id/sourceId、种类、启用状态、原代码、行为元数据和覆盖警告保留；完整映射仍在本地 NativeProgramSource 中，原卡与素材不变。这轮不删除 CSS、作者注释或其他可能关联行为的源码，也不引入自动模型重试。

回归覆盖包装整理后执行代码完全一致、多 JSON/截断/数组拒绝、摘要默认与截短、嵌套错误路径和未知能力拒绝。首次应用回归因旧测试仍要求 preservedLocally 失败，按新输入契约更新后通过：核心 69 通过、2 条件跳过，编译相关应用测试 9 通过（含 C-05 原始响应重放）。重放目录 `1788857750362`，没有再次模型调用。

```powershell
.\gradlew.bat --init-script build/native-normalization-isolated.gradle --no-configuration-cache :content-core:test :app:testDebugUnitTest --tests '*NativeCompilation*' --rerun --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

回归日志 `build/native-receiver-regression-replay.log`。未重新打包 APK、未执行实机验收。

随后用户授权的 DeepSeek 真实回测目录 `1788857820056`：当前聊天模型 `deepseek-v4.1-flash-expires-on-0910`，输入 20,793、输出 11,142（含推理 9,752）token，completed / Ready。相较此前 C-05 输入 21,992 少 1,199 token；输出与推理更多，不据此声称总费用降低。

这次响应只含 summary/mvu/script，没有顶层版本或包装，无需规范化即通过执行契约、JS 加载、安装、MVU 初始化、初始投影与重载。模型生成一个 Status，初始投影含 34 个条目，包含世界、用户和角色字段，没有固定 stateBindings。改变 Surface 划分是本次模型输出的选择，不是接收器改写结果。测试不证明原文本解析器、默认值、字段顺序和所有边界行为等价。

真实调用执行 `./gradlew.bat --init-script build/native-normalization-isolated.gradle --no-configuration-cache :app:testDebugUnitTest --tests '*NativeCompilationLiveTest' --rerun --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'`，一次通过；日志 `build/native-receiver-live-c05-deepseek.log`。原始输出完整保存在忽略目录，未手动修改、未触发自动修复请求。`git diff --check` 通过，未创建提交。
