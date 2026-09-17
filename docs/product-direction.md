# Tavern Player 产品方向与边界

更新：2026-09-15。本页维护产品方向、稳定原则与当前能力边界；实现细节和参数只在架构文档维护。具体代码边界见[实现架构](architecture.md)，当前路线讨论见[HTML Surface 与免编译游玩](html-surface-discussion-20260909.md)。历次暂停、编译版本与旧阶段决定见[归档快照](archive/product-decisions-through-20260909.md)，不再作为并列的当前要求。

## 保存与最近会话

- 最近会话按消息、编辑、候选切换和持久业务操作更新；普通打字和流式中间进度不会反复重排列表。
- 回复只有在正文、用量和状态保存成功后才显示完成。保存失败时保留当前内容，提供“重试保存”，完成前暂停后续业务操作。
- 意外退出后恢复最近已保存的草稿和生成片段，片段标记为已中断；不会自动重新生成或重做作者操作。旧版对话导入失败时保留原文件并显示失败原因。

## 兼容主路线与实现状态

- 兼容主路线从逐卡模型理解、重写程序，转向在声明的宿主能力边界内直接运行作者原程序。Native Adaptation 成为可选原生体验增强，不再承担基础游玩的必经责任；缺失依赖明确报告，不自动回落到模型编译。
- 默认聊天使用原生外壳与输入栏、单个 WebView 消息区。Player 拥有会话、消息、权威状态、生成编排、候选和持久化；原页面/后台脚本由 WebView 执行，MVU/EJS 由 QuickJS 执行。
- 验收目标是零导入模型调用、不改原卡，在真实消息链路完成核心交互、状态更新、候选和重启恢复；不表示零聊天模型调用或零网络请求。
- 新建会话统一使用网页模式；已有会话保留原模式，已有原生会话仍可打开，不自动转换或删除。原生对话与 Native Adaptation 定位为后续增强功能，当前收起原生新建、模型选择/编译、适配文件导入及适配报告入口。底层实现和网页依赖的 MVU/EJS、生成、存储能力保留。导入和 Shelf 接收不执行程序，进入网页会话后按 [player-web-1 契约](web-runtime.md)装载原文与资源，不要求模型编译。
- 下文“不支持”描述当前交付边界，不把旧阶段限制当作排除新路线研究的永久禁令；新能力在明确范围和验证后更新本页。
- 酒馆助手的公开接口和可观察行为作为持续扩展的兼容契约。作者 JS 直接运行，宿主调用映射到 Player 的原生业务能力；不识别并翻译每段作者逻辑，不复刻扩展管理界面。安全约束集中于凭据、原生入口及操作归属，不以代码来自角色卡为由拒绝正常变量、消息或世界书业务能力。现有接口缺口按兼容工作推进，不视作永久安全禁令。
- 兼容按完整能力域及依赖顺序交付，样本负责验收，不按单个报错决定接口范围。会话级共享作者环境先于变量、消息、世界书和生成扩展；清单、目标归属和出口条件见[酒馆助手兼容能力与实现顺序](reference/helper-compatibility.md)。该设计不表示生产运行时已完成重构。

## 1. 产品与兼容原则

- Tavern Player 是面向角色对话和剧情体验的播放器，不是 SillyTavern 创作工作台或小说续写器；原程序兼容只在声明的宿主能力边界内提供。
- 用户主流程围绕 Character、Preset、Model 和 Conversation；底层能力不必都暴露为独立管理界面。
- 与角色的 Conversation 是一次情景体验，不是独立的可分享“作品”，不建立 `Experience` 对象。
- 只支持结构化 role messages 的对话管线，不支持 Text Completion 字符串续写管线。
- 简化发生在支持能力的集合上，不发生在已选择能力的语义上：先明确不支持的 ST 能力；能力一旦被选择，就以 ST 的可观察行为作为语义基线。
- 只复刻行为，不复制 ST 的源码结构、全局状态、ToolManager、事件系统或扩展架构，也不为了内部结构更优雅而重新解释已选择的能力。

## 2. 内容资产与配置

### Character Card

- 在当前范围内不裁剪 Character Card 的官方行为语义，包括角色定义、开场与备用开场、示例对话、system prompt / post-history override 和 depth prompt。
- Character Book、Character-scoped Regex 和卡片文本中的 Macro 保留关联关系，但分别受 World Book、Regex 和 Macro 的产品边界约束。
- Character Card 字段最终怎样进入请求，遵循受支持的 ST Preset / Prompt 编排语义。
- 当前 Character 导入基线是 Character Card V2 / V3，并兼容 V1 JSON 与 PNG；不支持 CHARX、YAML 和 BYAF。
- V3 `nickname` 是 Prompt 与聊天作者身份，角色库仍显示卡片 `name`。

### Preset

- 不主动裁剪 Preset 的核心官方语义。保留通用 Prompt、Prompt 定义与顺序、启用状态、role、marker、placement / depth、generation settings 和通用模型请求控制。
- `charDescription`、`scenario`、`worldInfo`、`chatHistory` 等 marker 是可跨 Character 复用的动态内容插槽，不表示 Preset 与某个 Character 绑定。
- Preset 是当前全局活跃的生成配置和写作策略。切换 Preset 后，所有后续生成直接使用新 Preset。
- Preset 不属于 Conversation 的剧情状态；Conversation 不绑定历史 Preset 来决定后续生成。单次请求使用不可变快照，并保存必要的生成诊断信息。
- Preset 引用的 Macro、Regex、Tools 和 Provider 特殊能力分别受对应领域的产品边界约束。
- 当前只导入可识别的 ST OpenAI / Chat Completion JSON；不支持 Text Completion Preset、空白创建或 Provider / 模型绑定；预设助手脚本受网页运行契约限制。
- 内置“默认”Preset 提供中性的起始配置，可编辑、可恢复且不可删除。导入和另存为的 Preset 也保留独立恢复基线。
- 导入保留完整解析后的 JSON，包括定义池、全局 Prompt order、未使用定义、Prompt / Regex / 控制字段、未知扩展，以及 Provider / 模型、endpoint、自定义 headers/body 和凭据形字段。后几类只作为惰性兼容内容落盘和导出，不会自动改变 Player 连接、触发网络访问或获得执行权；不额外保留原文件的空白与格式。

### World Book

- World Book 支持角色附带与全局独立两种来源。全局世界书是可选的跨角色内容，由玩家独立导入和启用，不绑定预设；两类世界书共用阅读、正文调整与注入机制，运行状态按会话隔离。默认没有启用的全局书，不要求玩家配置才能聊天。
- 全局世界书与预设同级，支持文件和 Shelf 导入，可同时启用多本。导入不自动启用；切换预设不改变启用列表。书级开关决定是否参与，条目继续提供“按原条件／始终注入／停用”，不提供整本全部强制。
- 全局正文与使用方式修改影响所有会话的后续生成，页面明确标注作用范围。正文和使用方式可分别恢复导入基线，另存为以当前内容建立独立副本且默认停用；删除不修改已生成消息。导出保留原始未知字段，“始终注入”使用 Player 命名空间扩展，不伪装成 ST constant。
- 单次生成捕获全局书与调整的不可变快照；不将全局书写入 Character Snapshot。冷却、持续激活等沿会话候选检查点保存，不建立全局运行状态。普通生成、重试、重新生成和带预设的网页辅助生成参与；raw 辅助请求仍只使用调用者显式消息。
- 本轮沿用已有多书引擎：角色书在前、全局书按导入顺序在后分书筛选，共享总世界书预算；分组和递归仍在书内，最终注入按原位置与 order 编排。不按书名或正文自动去重，不增加 Persona 来源或可配置的 ST 来源混排策略；这不是完整 ST 多来源等价承诺。
- 世界书是可阅读的角色内容。角色详情与对话内共用完整阅读页，目录展示标题和正文摘录，沿用作者展示顺序；模板内容只显示提示，不执行或生成摘要。详情以正文为主，提供上一项、下一项连续阅读；不提供搜索；全局书的独立列表仅负责导入和使用管理。阅读包含停用条目，只显示原始文字，不执行模板。
- 内部允许一个 Character 关联多本 World Book；普通 V2 / V3 导入读取一份内嵌 `character_book`。多本书只在内容上标注来源，不把书名或互斥 `group` 推断成阅读分类。
- 角色详情保持只读。对话内和全局书阅读页在详情底部常驻显示本项使用方式，点击后在底部面板选择“按原条件／始终注入／停用”，说明和恢复操作集中在此面板，不挤占正文。作者停用的内容初始为停用，启用的内容沿用原条件。玩家可以手动启用作者默认停用的内容，恢复原设置只撤销该项的手动使用方式。
- “始终注入”是 Player 扩展：选中的内容绕过关键词、概率、延迟、冷却、递归门槛、互斥筛选和世界书预算。强制项优先于同组自动项；用户明确选择的多个强制项可以同时加入。沿原位置编排，预设未提供相应位置或 outlet 时直接补入历史前的必选消息；不会因为隐藏条件静默丢弃。最终上下文及 Provider 重裁剪必须保留它，容量不足或处理后无正文时明确失败，不承诺模型一定在回复中体现。
- 角色世界书的正文修改是对当前游玩的局部调整，通过详情右上角“修改”进入独立文字编辑页，保留明确的保存、恢复原文和未保存提示。正文草稿显式保存到本对话，失败保留草稿；“恢复原文”先恢复草稿，再由玩家保存。玩家正文和使用方式独立覆盖作者内容，不修改角色资产、其他对话或之后的新对话；对话内标明手动调整和正文修改。
- 正文调整保留既有模板约束：被登记为 EJS 模板的条目改写后，不执行来源已变化的模板；改成普通文字后按普通内容使用。仍含模板代码时跳过注入并诊断，设为始终注入时明确失败。编辑入口说明这一影响，恢复原文可恢复模板。作者程序自己的世界书读写能力继续保留。
- 玩家调整独立于作者程序状态，属于会话级，不随消息候选或历史重启回退；sticky / cooldown 等剧情派生状态继续跟随候选检查点，delay 根据当前选中分支的消息数判断。只读浏览在生成中可用，调整在生成及保存期间禁用。
- 内容详情提供最近一次生成的注入结果，以最终请求正文为依据；激活不等同于注入。无法确认的转换显示“未确认注入”，旧记录缺少证据时不推断结果。
- 保留受支持的触发、排序、注入、预算和跨轮状态语义。高级正则、扫描格式和递归仍有明确边界，不能将功能覆盖视为整体语义等价；具体行为与证据见[实现架构](architecture.md)。
- 角色附带 World Book 定义随 Character 一起进入 Character Snapshot；全局定义由独立仓库管理。
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
- Macro 兼容基线固定到明确的上游版本，只纳入当前产品边界列出的能力；版本与执行机制见[实现架构](architecture.md)。

### Regex

- 只保留 Character-scoped 和 Preset-scoped Regex，删除 application-global Regex。
- Regex 是 Character / Preset 的附属运行能力，不建立独立 Regex 资产或全局管理层。
- 已保留规则的 placement、顺序、深度、Prompt / storage / display 投影和 Macro replacement 等行为，以 ST 的可观察语义为基线，并受 Macro 产品边界约束。
- 耗时或不支持的规则应隔离并给出诊断，不拖垮对话；具体改写和熔断机制见[实现架构](architecture.md)。

### Token / Context

- Context 管理是必须能力。保留 ST 的内容取舍语义，包括回复 token 预留、必选 Prompt、历史由近到远进入、examples 与 history 的预算关系、World Book 独立预算，以及 depth injection 等内容的既定优先关系。
- Tokenizer、模型 context window 和实际请求 token 计算由 Tavern Player 自己正确实现。兼容目标是 ST 的 context semantics，不是 ST 当前的 token accounting implementation。
- 模型连接、目录与 Preset 明确声明的上限用于分配预算；声明不等于 Provider 实测验证。上下文容量均未声明时不设置产品默认上限，由 Provider 接受请求或返回超限错误。运行时约束不能静默改写 Preset 资产。
- 模型连接允许按模型 ID 手动覆盖 context / output token 上限，用于目录不声明能力的自定义模型。覆盖值属于连接侧模型能力，逐字段优先于目录元数据，不绑定或改写 Preset。

## 5. Provider 与回复能力

- role / system 转换、模型参数映射、流式事件解析、usage、finish reason 和错误处理属于 Provider adapter 的基础职责。
- Tavern Player 强制采用自己的流式生成策略，不兼容 Preset 的 streaming 开关；当前请求路径要求 Provider 支持流式。
- 支持 Reasoning / Thinking 的接收、保存和必要展示。Provider 的 reasoning 与 thought signature 映射在 adapter 中处理。
- Provider 返回的原始 reasoning 与 thought signature 始终保存；当前 Preset 的 `show_thoughts` 和 display Regex 只控制展示投影。
- Assistant Prefill 是内容语义，予以保留；各 Provider 的具体表达由 adapter 处理。
- 不使用 `previous_response_id` 等 Provider Hosted State。供应商托管状态不是 Conversation 语义或运行依赖。
- 不支持 Provider 原生多候选 `n`；swipe 通过再次生成新候选实现。
- 当前模型对话不支持 Multimodal，包括用户图片、附件与模型图片生成；角色静态图片资源属于内容资产能力，不等于多模态模型请求。
- V1 不支持 JSON Schema / structured output、Provider web search、logprobs 等特殊生成模式。
- 各 Provider 映射已开启且协议能够表达的 Preset 参数；明确声明的参数采用乐观转发，不按模型名称白名单决定是否发送。无法表达或被拒绝的能力应给出诊断，不反向改写 Preset。字段映射和协议必填值处理见[实现架构](architecture.md)。

## 6. 脚本、原生适配与工具边界

- 网页模式直接读取原脚本、消息页面和 EJS 入口，不使用模型材料筛选。Native 编译、安装与原生显示机制保留为后续增强，当前不在角色详情开放入口。两条路线均只提供声明的宿主能力，不承诺任意扩展。
- 原生界面通过统一只读接口读取状态。MVU 卡使用自身检查点和原数据路径，非 MVU 卡保留 Player 状态，纯文本卡不需要结构化状态；不为了界面再复制一份业务事实。
- 已有 JS 操作允许声明的状态读取、MVU 直接替换、程序私有状态、草稿替换和辅助生成。写入先保存再发布，操作检查点归属消息候选；失败或取消不回滚此前已保存写入，中断不自动重发。接口限制见[实现架构](architecture.md)。
- 既有固定 Form、Legacy State Adapter、PlayerChoice、Setup 和实验性关系分析保留各自语义，不因新路线默认扩建、删除或迁移。Native 安装与角色原件哈希绑定，旧会话不自动更换适配。
- World Book 书本级与条目级 activation override 跟随候选、截断和恢复；当前不存在完整 Tavern Helper、STscript、任意扩展事件/Macro 或通用外部执行框架。
- 当前不复刻 ST ToolManager，不提供动态工具注册、tool execution/history 或递归工具生成。Preset 中的 function calling 等字段保留不等于获得执行能力。

## 7. 角色资源与传输

- 图片属于角色的持久内容资产。角色详情的“角色资源”在本地发现静态引用，用户点击准备后下载、校验和保存；该流程不依赖模型或 Native 编译。
- 原图保存于应用私有存储，不进入相册，不纳入普通缓存淘汰。缩略图、解码缓存与下载临时文件可重建；未来删除角色需考虑仍引用资源的会话。
- 图片来源包括已支持的静态 assets、HTML/Markdown 固定地址与内嵌图片。提前准备只发现静态引用；网页游玩时动态地址按需保存到同一索引。图片准备本身不表示作者交互已兼容。
- 已保存图片不因远程地址失效而失效。备份、迁移和导出应按角色资产整体考虑，具体打包方案尚未实现。资源取得与容量边界见[图片实现记录](archive/character-image-resources-20260909.md)。
- Shelf 通过二维码传输单份原始 Character、World Book 或受支持的 Preset，接收端校验后走本地导入。独立 World Book 进入全局列表并默认停用；协议见[传输契约](reference/transfer-protocol-v1.md)。

## 阅读与维护

已交付能力以 README 和[实现架构](architecture.md)为入口；网页兼容范围与恢复限制见[运行契约](web-runtime.md)；路线背景保留于[讨论记录](html-surface-discussion-20260909.md)。上游语义资料、样本编号与阶段实验从[文档导航](README.md)进入。实验记录不替代当前契约，验证结果只证明其记录的版本和场景。
