# Tavern Player 当前产品边界

> 当前（2026-09-08）：已按用户决定进入实现，`native-compiler-11` 接通 JS 动态投影、高层 Native Surface 和操作检查点。开放程序表达、收紧界面表达；通用组件树不是默认能力。实际接口与验证边界见 [首轮实现](native-script-surfaces-20260908.md)。下文早期版本和暂停决定保留为历史。


> 后续实现：MVU 已接入适配产物和聊天事务，世界书 EJS 已通过只读宿主在 QuickJS 执行，当前编译契约为 native-compiler-7；见 [MVU 聊天接入](mvu-chat-integration-20260907.md)、[EJS 接入](ejs-quickjs-integration-20260907.md)和[状态绑定](native-state-bindings-20260907.md)。下文先前阶段的未接入描述保留为历史记录。

> 状态：当前有效的产品与兼容性决定。
>
> 范围：V1 的单角色、普通、结构化文本对话。已落地的代码边界见 [`architecture.md`](architecture.md)。

> 2026-09-07：原生适配方向暂停推进，待用户研究后再讨论。下文相关内容描述既有实现和此前决定，不代表继续扩建固定 Native 能力已获授权；DSL 与 MVU 兼容的调整尚未定案。见 [本次复盘](native-adaptation-retrospective-20260907.md)。

> 同日后续：已按用户要求实际运行 MVU 与 Zod 注册辅助库，完成 [公共变量能力接入实验](mvu-integration-probe-20260907.md)。C-04 原始初始化与四种更新等验证通过；目前是 Node 宿主实验，App 尚未接入，不继续逐卡扩建标量适配协议。

> 随后用户选择 QuickJS，并要求先做本地验证。已新增 [QuickJS Kotlin 宿主](mvu-quickjs-integration-20260907.md)，完整 MvuData 可随 Player 消息检查点保存，普通导入与聊天入口尚未自动启用。

## 2026-09-07：状态来源与编译职责

- 原生界面统一读取只读状态接口；统一的是读取方式，不要求所有卡采用 MVU 或相同数据结构。MVU 卡绑定其检查点原路径，非 MVU 卡继续使用 Player 状态，纯文本卡不需要结构化状态。
- `stateBindings` 不带初值或业务更新规则，不复制 MVU 背包、数值或阶段。当前与历史详情都读取对应候选的同一检查点；缺失值明确显示不可用。
- 模型准备流程负责识别原程序、生成展示绑定和报告宿主兼容性缺口。`native-compiler-7` 删除 EJS 阶段表与原文分支翻译输出，MVU 分支拒绝第二份状态与旧写入器。非 MVU 的必要 Player 状态和草稿表单保留。
- 只读绑定不会授予界面直接修改 MVU 状态的权限。手工适配已有的 Player 阶段、Setup 和选择能力保留；不据此扩建通用动作框架。
- 详见 [实现与验证记录](native-state-bindings-20260907.md)。下文较早阶段的全面禁止脚本和仅手工安装描述，以已明确接入的 MVU/EJS 有界宿主及本次决定为准。

## 1. 产品与兼容原则

- Tavern Player 是面向角色对话和剧情体验的播放器，不是 SillyTavern 创作工作台、小说续写器或第三方扩展宿主。
- 用户主流程围绕 Character、Preset、Model 和 Conversation；底层能力不必都暴露为独立管理界面。
- 与角色的 Conversation 是一次情景体验，不是独立的可分享“作品”，不建立 `Experience` 对象。
- 只支持结构化 role messages 的对话管线，不支持 Text Completion 字符串续写管线。
- 简化发生在支持能力的集合上，不发生在已选择能力的语义上：先明确不支持的 ST 能力；能力一旦被选择，就以 ST 的可观察行为作为语义基线。
- 只复刻行为，不复制 ST 的源码结构、全局状态、ToolManager、事件系统或扩展架构，也不为了内部结构更优雅而重新解释已选择的能力。

## 2. 内容资产与配置

### Character Card

- 在当前范围内不裁剪 Character Card 的官方行为语义，包括角色定义、开场与备用开场、示例对话、system prompt / post-history override 和 depth prompt。
- Character Book、Character-scoped Regex 和卡片文本中的 Macro 保留关联关系，但分别受 World Book、Regex 和 Macro 的产品边界约束。
- Character Card 字段最终怎样进入请求，遵循后续实现的 ST Preset / Prompt 编排语义。
- 当前 Character 导入基线是 Character Card V2 / V3，并兼容 V1 JSON 与 PNG；不支持 CHARX、YAML 和 BYAF。
- V3 `nickname` 是 Prompt 与聊天作者身份，角色库仍显示卡片 `name`。

### Preset

- 不主动裁剪 Preset 的核心官方语义。保留通用 Prompt、Prompt 定义与顺序、启用状态、role、marker、placement / depth、generation settings 和通用模型请求控制。
- `charDescription`、`scenario`、`worldInfo`、`chatHistory` 等 marker 是可跨 Character 复用的动态内容插槽，不表示 Preset 与某个 Character 绑定。
- Preset 是当前全局活跃的生成配置和写作策略。切换 Preset 后，所有后续生成直接使用新 Preset。
- Preset 不属于 Conversation 的剧情状态；Conversation 不保存 Preset snapshot、版本引用或历史绑定。单次生成 metadata 可以记录当时使用的 Preset，但这只是诊断信息。
- Preset 引用的 Macro、Regex、Tools 和 Provider 特殊能力分别受对应领域的产品边界约束。
- 当前只导入不超过 32 MiB、可识别的 ST OpenAI / Chat Completion JSON；不支持 Text Completion Preset、空白创建、Provider / 模型绑定或第三方脚本授权。
- 内置“默认”Preset 使用 ST 默认 Prompt 骨架、中性 main prompt、模型 context 上限和 1024 回复上限。它不可删除，但当前版本可以直接编辑；“恢复初始设置”回到代码内置版本。
- 导入保留完整解析后的 JSON，包括定义池、全局 Prompt order、未使用定义、Prompt / Regex / 控制字段、未知扩展，以及 Provider / 模型、endpoint、自定义 headers/body 和凭据形字段。后几类只作为惰性兼容内容落盘和导出，不会自动改变 Player 连接、触发网络访问或获得执行权；不额外保留原文件的空白与格式。
- 名称大小写不敏感且唯一；重复内容复用已有资产，同名异内容自动编号。删除 active 项会原子回退到内置默认。
- 导入、内置和“另存为”分别捕获不可变初始版本，当前编辑不覆盖该恢复基线。编辑采用显式保存；带修改返回时必须选择保存、放弃或继续编辑。“另存为”从当前草稿创建并立即启用新 Preset；导出 JSON 使用当前草稿。
- 从 Preset 列表进入一项时会先把它设为全局 active，再编辑同一项。主界面只把实际 `prompt_order` 中的普通 Prompt 与 Preset Regex 投影为快速开关；Prompt 开关只修改既有 order entry 的 `enabled`，不插入、移除或移动队列，未编排定义继续完整保留。
- Prompt 文本与兼容字段、Preset 名称 / 控制格式 / 结构 marker、模型请求参数分别位于独立次级页面。请求参数可逐项开启或关闭；关闭时保留本地值，但从兼容 Provider 请求和 ST 导出中移除。切换 Provider 不反向修改 Preset，Provider 协议必填值由播放器的安全预算补齐并进入诊断。

### World Book

- World Book 在产品上与 Character 强关联。当前 Character 决定本轮参与的 World Book，不建立 ST 的 global、conversation、persona 多来源组合以及来源优先、混排和去重规则。
- 世界书是可阅读的角色内容。角色详情在开始对话按钮后提供明确入口，按书浏览、搜索标题／关键词／正文，读取包含停用条目的原始内容；阅读不改变作者的启用设置或执行模板。
- 一个 Character 可以关联多本 World Book；内部仍可分别保存 Character Card 与 World Book 数据。
- 目标是保留受支持的 Character World Book 条目语义，包括 constant、关键词、secondary logic、scan depth、概率、分组、递归、独立预算、sticky / cooldown / delay、order、placement 和 at-depth。2026-09-08 修正角色字段扫描开关、delay 消息数门槛、逐轮分组／概率／预算／递归，以及附加关键词逻辑导入映射；详见[世界书阅读与编排修正](world-book-reader-and-semantics-20260908.md)。高级正则、扫描格式和数字递归层级仍有明确边界，不能将功能覆盖视为整体语义等价。
- World Book 定义随 Character 一起进入 Character Snapshot；sticky / cooldown 等运行状态属于 Conversation，delay 根据该会话当前选中分支的消息数判断。
- 不支持 vectorized / embedding 候选激活、聊天或文件向量记忆 / RAG、外部强制激活入口和 `automationId`。

### Persona

- 当前只维护一份全局默认 Persona，保留 name、avatar 与可选 description，并在角色库提供显式编辑入口。默认值仍是 name 为“旅人”、description 为空。
- name 是 `{{user}}` 和用户消息身份的数据来源，description 是 `{{persona}}` 与 Preset `personaDescription` marker 的动态内容源；具体编排完全由当前 Preset 决定，不另行实现 Persona 自有的 placement / depth / role。
- 新建 Conversation 捕获当时的默认 Persona 快照；之后修改默认身份不改写已有 Conversation 及其历史消息。当前不提供多 Persona 列表或创建对话时的选择器，也不支持 Persona Lorebook、Persona 参与 World Book 扫描、Character → Persona 绑定、Conversation 临时 Persona 等拓扑。

## 3. Conversation 与剧情状态

- 一个 Character 可以创建多个独立 Conversation。
- Conversation 当前实现 opening swipe、assistant regenerate / swipe、进程恢复，以及用户 / assistant 历史消息的内联编辑。“保存文字”保留后续事实与既有运行状态，只让未来请求读取修正后的 canonical history；“从这里重新生成 / 继续”才从消息检查点恢复 Macro 与 World Book 状态，收敛当前候选并永久截断后续历史，它不是保留旧后缀的 branch。delete、continue、branch / checkpoint 留在后续 Conversation 工作；impersonate、quiet 和自动操作不作为 V1 核心要求。
- 创建 Conversation 时，从当前 Character Asset 实例化 Character Snapshot：`Character Asset -> Character Snapshot -> Conversation`。
- Character Asset 的后续修改不会隐式改变旧 Conversation。旧 Conversation 升级角色版本必须显式进行。
- Conversation 不维护 ST 式的 scenario、system prompt、examples 等 Character override patch。未来若允许会话内修改角色设定，修改的是该 Conversation 自己的 Character Snapshot。
- Character Snapshot 表示这场剧情使用什么角色定义；Conversation Runtime State 表示剧情运行到了什么状态；Chat History 表示实际发生了什么。
- Conversation Runtime State 包含当前 Persona、Macro local variables、World Book timed state 和 Conversation State；各消息候选保存处理前后的状态快照，swipe、regenerate、截断与恢复必须选择同一条时间线上的状态。

## 4. 请求处理语义

### Macro

- 保留角色、当前用户身份和聊天状态等角色 / 会话级上下文引用，以及普通文本、条件、计算和随机能力。
- 保留当前 Conversation 范围内 local variables 的读取与修改。
- 不支持跨角色、跨会话共享的 global variables，也不得把 global variable 自动降级为 local variable。
- group、Text Completion 和第三方扩展动态 Macro 随对应领域排除。
- 行为基线固定为 SillyTavern 1.18.0（commit `8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8`）默认的新 Macro Engine；只纳入当前产品边界列出的 Macro。

### Regex

- 只保留 Character-scoped 和 Preset-scoped Regex，删除 application-global Regex。
- Regex 是 Character / Preset 的附属运行能力，不建立独立 Regex 资产或全局管理层。
- 已保留规则的 placement、顺序、深度、Prompt / storage / display 投影和 Macro replacement 等行为，以 ST 的可观察语义为基线，并受 Macro 产品边界约束。
- 对开头的正向后向断言允许进行保持匹配、编号捕获和替换结果的前向等价改写，用于规避 Android 可变长度 lookbehind 的性能差异；无法安全改写的规则继续受熔断保护。
- Regex 的生产单规则熔断为 250 ms；执行策略可在测试中注入，超时或工作队列拒绝只禁用当前 Conversation 中的坏规则并产生诊断。

### Token / Context

- Context 管理是必须能力。保留 ST 的内容取舍语义，包括回复 token 预留、必选 Prompt、历史由近到远进入、examples 与 history 的预算关系、World Book 独立预算，以及 depth injection 等内容的既定优先关系。
- Tokenizer、模型 context window 和实际请求 token 计算由 Tavern Player 自己正确实现。兼容目标是 ST 的 context semantics，不是 ST 当前的 token accounting implementation。
- 模型连接覆盖和模型目录给出的 context / output limits 是已验证能力，存在时会约束 Preset 声明的预算。两者都缺失时，播放器按 Preset 声明分配预算并明确标记“未经 Provider 验证”，不再维护模型名称能力表；Preset 也没有声明 context 时才使用 128K 产品默认预算。任何运行时约束都不能静默改写 Preset 资产。
- 模型连接允许按模型 ID 手动覆盖 context / output token 上限，用于目录不声明能力的自定义模型。覆盖值属于连接侧模型能力，逐字段优先于目录元数据，不绑定或改写 Preset。

## 5. Provider 与回复能力

- role / system 转换、模型参数映射、流式事件解析、usage、finish reason 和错误处理属于 Provider adapter 的基础职责。
- Tavern Player 强制采用自己的流式生成策略，不兼容 Preset 的 streaming 开关；Provider 不支持 streaming 时进行能力降级。
- 支持 Reasoning / Thinking 的接收、保存和必要展示。Provider 的 reasoning 与 thought signature 映射在 adapter 中处理。
- Provider 返回的原始 reasoning 与 thought signature 始终保存；当前 Preset 的 `show_thoughts` 和 display Regex 只控制展示投影。
- Assistant Prefill 是内容语义，予以保留；各 Provider 的具体表达由 adapter 处理。
- 不使用 `previous_response_id` 等 Provider Hosted State。供应商托管状态不是 Conversation 语义或运行依赖。
- 不支持 Provider 原生多候选 `n`；swipe 通过再次生成新候选实现。
- V1 不支持 Multimodal，包括用户图片、附件、模型图片生成和图片内联。
- V1 不支持 JSON Schema / structured output、Provider web search、logprobs 等特殊生成模式。
- OpenAI Responses、OpenAI Chat Completions、Anthropic Messages、Gemini Interactions 和 Gemini GenerateContent 分别映射已开启且各自能表达的 Preset 参数。用户关闭的字段先于能力判断被拔除；协议原生可表达的显式参数采用乐观转发，不以模型名称白名单决定是否发送。协议要求但 Preset 已关闭的必填字段使用播放器预算；明确无法表达、需要模型特定结构或被 Provider 拒绝的能力才降级，并进入请求预览与上下文诊断。
- `top_a`、`min_p`、`repetition_penalty` 以及协议无法表达的 assistant prefill 只保留、导出并告警。Tavern Player 强制 `stream=true`、单候选和无 Provider hosted state。

## 6. Tools、扩展与外部系统

- 脚本宿主只提供已明确选择的 MVU／EJS 有界接口。任意远程依赖属于支持范围之外，不是已承诺但尚未完成的兼容任务；不得将其列为默认扩建目标。
- 不复刻 ST ToolManager，不支持动态工具注册、STscript、第三方扩展工具、stealth tools 或任意外部执行框架。
- V1 没有 Tavern Player 原生 tools，不实现 tool execution、tool history 或 recursive generation。
- Provider 的 function / tool calling 协议表达可以独立存在或预留，但不表示产品支持 Tools。只有未来明确增加原生工具时，才建立执行语义。
- Preset 中依赖 ST Tool runtime 的 `function_calling`、tool recursion 和相关字段当前不产生能力。
- 不运行第三方脚本、扩展事件系统或任意扩展 Macro。未来是否把某项扩展能力重新实现为原生能力，必须另行明确决定。
- 已明确加入的第一组 Native 适配能力是 Player 固定的 Status / Scene / Collection / Form、assistant 消息状态摄入、本地静态图片与聊天草稿写入。消息摄入只接受手工适配白名单映射的 `UPDATE_VARIABLE_SET_V1` 或 `UPDATE_VARIABLE_JSON_PATCH_V1` scalar 操作，经专用 Legacy State Adapter 产生 `ConversationStatePatch`；Native Form 只生成待用户确认的草稿，不允许通过通用 Action 脱离消息时间线直接改状态。Native 内容必须匹配原件 SHA-256 并通过类型、大小和引用校验，不代表运行原脚本或开放通用工具执行。
- Conversation Runtime 已加入书本级与条目级 World Book activation override，以及只属于该领域的强类型原子控制器。覆盖跟随消息 checkpoint、swipe、regenerate、历史截断、持久化和进程恢复；普通 Form 与 Legacy Adapter 都不能调用它，也不存在通用 Operation dispatcher 或 Trigger。

## 7. 当前交付边界

- Character Card 导入、不可变 Character Snapshot、Macro / Regex / World Book 编排、token accounting、流式发送和 Conversation 恢复已经形成实现契约。
- 单一默认 Persona 已可编辑并持久化；多身份资产管理、选择与绑定仍不进入当前阶段。
- 全局 Preset 资产库、ST OpenAI Preset 导入 / 导出、受控编辑、五协议参数映射和聊天快捷切换已经形成实现契约。
- 真实社区卡中的未知扩展会原样保留并报告。远程脚本、第三方动态 Macro 和富 HTML 状态栏不会执行、联网加载或被伪装为已兼容。
- 复杂卡可以旁挂不改写原件的 `NativeAdaptation`。当前已验证的运行闭环包括“opening marker → Native Form → Draft”、“assistant JSON Patch-shaped 状态块 → 白名单 State Patch → 固定 Status”和“本地静态资产 → Native Decoder → 固定 Scene”。同一份选中候选状态以稳定、不可配置的 `{definitions, values}` JSON system projection 提供给下一轮模型。
- 复杂样本 C-01 的开发夹具由兼容工程师手工审计，明确区分 restored、degraded 和 unsupported。真实卡用于证伪 Player 的设计，不通过出现频率或单卡实现反推通用 Runtime。
- 当前主线改为固定现有能力的自动适配：本地整理完整程序与关联规则 → 一次独立模型理解并生成 Native 配置 → 本地原文恢复与校验 → 安装 → 新对话。已撤掉按特定 Zod、表单和分支语法选择候选的 v3 识别器；预处理按来源和文本边界过滤，不代替模型理解玩法。角色详情提供实验入口，真实编译效果仍须独立验收；复杂卡全部外部行为还原不再是开始编译的前置条件。远程图片延期，已有关系分析维持实验且不由本轮编译器生成；不增加通用脚本运行时、自动 repair、派生缓存或 Shelf 适配附件。见 [自动适配实验](native-compilation.md)。
