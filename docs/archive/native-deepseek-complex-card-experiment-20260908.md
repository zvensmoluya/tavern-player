# DeepSeek 复杂卡编译与 reasoning 记录

> 归档说明（2026-09-09）：保留阶段设计、源码审计与验证证据；文中的“当前”“下一步”和版本号指记录当时，不作为现行产品决策或完整运行契约。现行入口见[文档导航](../README.md)、[产品边界](../product-direction.md)和[实现架构](../architecture.md)。

日期：2026-09-08。用户要求尝试本机新 DeepSeek 连接，并建议保存服务返回的 thinking，作为理解编译困难的辅助证据。本轮固定 `native-compiler-11` 的提示词、源码提取与运行契约，只扩展实验记录，不根据本次输出调整编译器。

## 记录方式

`NativeCompilationLiveTest` 在实验生成器外包一层事件观察，逐段将 Provider 暴露的 `GenerationEvent.ReasoningDelta` 写入 `compiler-reasoning.txt`，将正文流写入 `compiler-response-stream.txt`；原有最终响应、请求计划、usage 与验证结果继续保存。没有返回 reasoning 时文件为空；不能从 reasoning token 数推导具体文本，也不尝试获取未暴露内容。

逐段保存使后续拒绝或传输失败不会抹去已经收到的片段。metadata 增加 `exposedReasoningBytes`。这些记录不回填编译请求，不影响生产编译器，不构成训练或微调。原文可能包含社区源码与人物名称，仅保存在现有忽略目录，本文使用中性样本编号。

## 固定条件与结果

模型：用户当前聊天连接 `deepseek-v4.1-flash-expires-on-0910`；协议 OPENAI_RESPONSES；HIGH 推理；输出预算 16,384。两张卡的原件哈希见 [样本编号](../reference/community-samples.md)。本次未使用模型修改后的源码或手工适配。

| 实验 | 样本 | 声明 context | Provider 输入 / 输出 / 推理 tokens | 结果 |
| --- | --- | --- | --- | --- |
| `1788874644880` | C-01 | 128,000 | 未发送 | 本地 `COMPILER_CONTEXT_LIMIT`，无生成费用 |
| `1788874738005` | C-02 | 128,000 | 29,375 / 4,090 / 3,422 | completed / Ready；安装、MVU 初始化、绑定读取及快照重载通过 |
| `1788874815405` | C-01 | 512,000，实验性未验证预算 | 95,644 / 16,384 / 16,384 | incomplete / Rejected；正文为空，无可安装产物 |

C-01 默认预算的拒绝来自 `provider-request-utf8-upper-bound` 保守估算，并非 Provider 拒绝。查询配置服务的模型目录未获得该模型可用的上限元数据。第二次仅通过既有测试环境覆盖声明 context，没有删除源码或修改应用默认值。Provider 实际接收约 9.56 万输入 token，并不能证明其最大上下文为 512K。实际输入加本次输出额度为 112,028，低于原默认 128K，说明本次本地上界估算阻断了实际可接收的输入。

C-02 保存 13,574 字节 reasoning 和 2,266 字节最终 JSON。产物保留一个 MVU Schema、一个原 EJS 模板、八个状态绑定，以及一个五字段消息面板；没有生成 JS 模块。八个绑定在真实初始化状态中均有值，未创建第二份 Player 业务状态。模型报告远程图片／DOM 随机头像和部分网页展示不保留；本地覆盖报告还指出未解释扩展及 38 个静态世界书正文未进入材料。通过不等于整卡功能全部恢复，本轮未执行其设备游玩。

C-01 保存 65,957 字节 reasoning；usage 的 outputTokens 与 reasoningTokens 均为 16,384，最终正文为零字节，finishReason 为 incomplete。这是输出预算耗尽的直接证据，不是 JSON 格式或脚本加载错误。reasoning 中的代码片段没有被抽取为产物、安装或计为成功。

## reasoning 能提供什么线索

先检查最终响应和运行结果，再阅读 reasoning。以下仅为可验证的诊断候选，不作为模型内部因果解释：

- C-02 多次比较模型消息标签面板与 MVU 状态面板的职责，最终同时生成两者。原件确有消息标签展示和结构化变量，但是否需要两个面板属于适配展示取舍，不能只凭模型认为有用就认定保真。
- C-01 大量内容用于讨论开局表单、确认交互、静态配置与 JS Surface 的选择，以及宿主不提供的候选切换／发送能力。末尾仍在组织模块代码，没有形成最终 JSON。它提示复杂来源在当前单次预算下可能难以完成，但不能据此断言某一句提示词导致了耗尽。
- 中间文字讨论过降级操作顺序、确认方式和字段表示；这些没有成为最终产物，不能记成已实现的兼容缺陷或修复。应以真实输出和执行对照确认。
- reasoning 无法证明模型理解了未传入的扩展或静态正文，因此必须与本地材料覆盖报告一起看。

保存记录本身不会使模型过拟合。风险来自按单个样本的自述追加特例、反复调到同一张卡通过，再以该卡证明泛化。本轮没有调整提示词；C-01、C-02 都已经观察过，若后续根据它们调优，就应将它们视为开发样本，另选未参与调整的样本验证。

若继续对照，优先保持材料与提示词不变，只改变一个预算或推理参数，区分预算不足和契约表达问题；不能用放宽校验或补写生成代码取得成功。

## 复现与验证

环境选择：`TAVERN_COMPILER_LIVE=1`、`TAVERN_COMPILER_CONFIG_SUFFIX` 为空字符串（当前聊天连接）、`TAVERN_COMPILER_SOURCE_SHA256` 为所选样本哈希。C-01 第二次额外设置 `TAVERN_COMPILER_CONTEXT_TOKENS=512000`。不修改 `.env`。

三次均执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*NativeCompilationLiveTest' --rerun --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

结果依次为 1 失败、1 通过、1 失败，与上表一致。日志分别为 `build/native-deepseek-c01-v11.log`、`build/native-deepseek-c02-v11.log`、`build/native-deepseek-c01-v11-512k.log`。所有原始材料位于 `app/build/native-compilation-runs/<实验编号>/`。

reasoning 文件 SHA-256：C-02 为 `7e815beafe8a0155b367790c34d8a2a773a6289a05c9e37016117546192c7862`；C-01 发送后的实验为 `e4b2f8fb17a99e4287447f5634126626459adc7fcae8a06fe1599864aa6e15f6`。

`git diff --check` 通过。本轮只修改实验测试与文档，未扩大宿主能力、重新打包或安装 APK，未创建提交。此前 C-04 的设备实测与修复单独记录，不能作为这两张卡的设备验收。

## 后续：移除编译专用 token 硬限

用户明确要求删除不合理的编译预算后，`NativeCompilationService` 不再设置默认 128,000 context 或 16,384 output，也不调用保守 token 预检来拒绝完整材料。上下文实际容量由 Provider 判定。连接声明的 output limit 直接使用，不再压到 16K；未声明时省略 Responses、Chat Completions、Gemini Interactions 和 GenerateContent 的可选输出限制。Anthropic 必填 `max_tokens`，未知时回退 65,536。内部计划用 output=0 与关闭 OUTPUT_LIMIT 表示未知值，该零值不发送给可选协议。

普通聊天预算、源码筛选、v11 提示词、JSON 接收器和安装契约均未调整。本地输入／JSON／运行资源体积限制继续存在，不把取消 token 默认值描述为无限资源执行。

对照实验 `1788875410317` 没有 context 环境覆盖。请求 `messages` 与上一次 C-01 实验完全相同：

| 项目 | 结果 |
| --- | --- |
| 模型 | 同一 DeepSeek 连接，HIGH 推理 |
| context / output 限制 | 未声明 context；未发送可选输出上限 |
| Provider usage | input 95,644；output 31,549；reasoning 27,492 |
| 完整性 | completed，收到最终 JSON |
| 记录 | reasoning 110,960 字节；原始 JSON 与流式正文单独保存 |
| 本地结果 | `COMPILER_INVALID_DECISION`，未安装 |

本次拒绝原因具体为：响应同时选择 MVU 和旧 `playerChoices`，违反 MVU 不得使用 Player-only 旧写入流程的执行契约。模型已生成两个 JS 模块，但没有通过后续安装与运行验证。没有删掉冲突字段再计成功，也没有自动修复或追加模型请求。该对照证明取消本地输出限制后能完成超过 16K 的返回，不证明更多推理能保证正确产物。

验证：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*NativeCompilationServiceTest' --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
# 同前文真实编译命令，选 C-01，未设置 TAVERN_COMPILER_CONTEXT_TOKENS。
.\gradlew.bat :app:testDebugUnitTest --tests '*NativeCompilationLiveTest' --rerun --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
.\gradlew.bat :app:testDebugUnitTest --tests '*NativeCompilationServiceTest' :app:assembleDebug --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

预算回归 8 项通过，覆盖未知上限不发送可选字段、保守估算不拦截、声明的 131,072 输出上限不被压缩，以及 Anthropic 必填回退。真实编译测试如实失败，原因见上表。日志为 `build/native-compiler-budget-tests.log`、`build/native-deepseek-c01-provider-budget.log` 和 `build/native-compiler-budget-build.log`。未安装更新后的 APK，未创建提交。


## 后续：v12 按状态来源提供编译契约

用户要求处理混合提示词对模型的误导后，改为两阶段编译。第一阶段由同一模型阅读完整 Program View，以 LOW reasoning 返回 MVU/Player 及原 Schema 来源；本地只检查来源存在、启用且为 SCRIPT，不根据卡名或某种 JS 写法分类。第二阶段继续使用同一份源码，以 HIGH reasoning 接收所选分支的能力说明与输出结构。

- MVU 分支移除 `state`、`assistantStateAdapters`、`playerChoices` 及其不可达类型，要求保留选定 Schema；只读绑定只能使用 MVU。
- Player 分支移除 `mvu`、`MVU_REPLACE` 能力与说明；只读绑定只能使用 Player。共同的 EJS、固定展示／草稿表单、JS Surface 能力继续共享。
- 接收器按分支检查原始字段；跨分支字段即使是空数组或 null 也拒绝，不静默删除。主编译不能更换状态来源或 Schema。
- 固定 `NativeFormField` 与运行时 `NativeSurfaceField` 分开描述。后者的完整结构来自实际 Kotlin 序列化描述，明确 `value:string`、`options:[string]`，没有 `type`、`initialValues`、`placeholder` 或对象选项。没有新增控件或写入能力。
- 选择与主编译的请求、响应、接口公开 reasoning、usage 分别记录。生产结果的 `usage` 仍表示主编译，`routing.usage` 单列选择成本；选择失败不发主编译，取消不重试。

### 真实对照

仍使用 `deepseek-v4.1-flash-expires-on-0910`，未设置编译 context/output 默认上限。C-01 两阶段的源码消息与 v11 实验 `1788875410317` 逐字相同，未提供人工答案；实发 MVU 提示词中没有旧 Player 写入字段或状态定义类型。

| 实验 / 样本 | 阶段 | 输入 / 输出 / reasoning tokens | 结果 |
| --- | --- | --- | --- |
| `1788876905043` / C-01 | 选择 | 91,815 / 72 / 58 | 选择 MVU、`script0`；Provider 报告 cached 91,648 |
| 同上 | 主编译 | 95,516 / 19,556 / 16,688 | completed / Ready，JS 加载通过；后续原 MVU 初始化失败 |
| `1788877076534` / C-03 | 选择 | 4,423 / 47 / 40 | 选择 Player |
| 同上 | 主编译 | 8,225 / 4,532 / 3,999 | completed / Ready；表单、真实聊天、安装与恢复通过 |

C-01 两阶段合计输入 187,331、输出 19,628 tokens，选择会增加输入读取与等待。不能只比较主编译而忽略选择请求，也不能用这一次 reasoning 从 27,492 降到 16,688 证明普遍效率改善。缓存数字仅为 Provider 报告，未换算费用。

C-01 最终 JSON 不再包含旧 `playerChoices`。两个 JS 模块通过真实 QuickJS 加载检查，确认交互使用 `programState` 暂存待确认标记，handler 声明并调用 MVU 直接替换。它生成状态、集合和操作组，没有生成运行时表单，也没有生成固定开局表单；模型将开场候选切换、自动发送、多选控件等报告为缺口。因此，本次真实结果没有验证 C-01 的运行时表单字段修复，更不能证明开局流程已恢复。

原 MVU 初始化在创建对话时失败：`SyntaxError: Unexpected token (3:33)`，发生于 `mvu-program.js` 加载调用。原 `script0` 以顶层 `await import(...)` 加载 Schema helper，并在 catch 中切换 CDN。现有 `tools/mvu-probe/schema-script.mjs` 仅映射受支持的静态命名导入，移除模块声明后按 script 解析，并拒绝动态导入。这属于现有原程序加载边界；本轮没有改写原 Schema、放宽加载器或手工补全产物。`metadata.result=Ready` 只表示编译／JS 加载完成，不能覆盖随后失败的初始化，完整测试仍为失败。异常记录保存在该实验目录的 `runtime-failure.xml`，未进入真实 MVU 状态下的 Surface 投影验收。

C-03 则完成五字段表单、四个多选项、顿号拼接、空值回退、真实模型回复、安装、新对话快照和保存重读。没有额外 Player 业务状态。本轮未运行新 APK 的真机或模拟器测试。

首次 v12 联网实验 `1788876838460` 在选择响应后被新增实验记录器中断：`GenerationUsage` 不是可序列化类型。主编译未发出；选择流中已收到 MVU / `script0`，usage 未成功保存，不能据此填入费用或完整成功状态。记录器改为显式保存用量字段后，才执行上表的新请求。失败资料保留，没有覆盖或冒充 Provider 错误。

### 本地验证

```powershell
.\gradlew.bat :content-core:test :app:testDebugUnitTest --tests '*NativeCompilationServiceTest' --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
# 分别选择 C-01 / C-03，使用同一 DeepSeek 连接：
.\gradlew.bat :app:testDebugUnitTest --tests '*NativeCompilationLiveTest' --rerun --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
.\gradlew.bat :content-core:test :app:testDebugUnitTest --tests '*NativeCompilationServiceTest' --tests '*QuickJsNativeRuntimeTest' :app:assembleDebug --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

定向回归覆盖完整源码不变、两阶段设置与用量、无效／停用 Schema 来源、跨分支字段／能力／绑定拒绝、选择截断／取消不发主编译、已有适配保留，以及真实 QuickJS 对运行时表单字符串选项的接收和旧固定字段的拒绝。结果与最终构建日志见 `build/native-compiler12-unit.log`、`build/native-compiler12-final.log`；联网日志为 `build/native-compiler12-c01-retry.log` 与 `build/native-compiler12-c03.log`。未创建提交。

最终本地结果：content-core 74 项中 73 通过、1 项条件跳过；应用定向 15 项全部通过（编译服务 10、真实 QuickJS 5），共 88 通过、1 跳过、0 失败。Debug APK 构建与 `git diff --check` 通过。C-01 的联网初始化失败单独保留，未计入上述本地通过数。
