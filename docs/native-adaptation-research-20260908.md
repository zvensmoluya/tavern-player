# 角色卡程序与界面适配：路线、算法和验证研究

日期：2026-09-08。状态：研究与讨论材料，不是实施规范。

本研究重新展开方案空间，不预先规定必须一次模型调用、必须全原生、禁止代码转换、必须引入行为图，或必须兼容全部酒馆。目标是比较不同方法能保留哪些实际体验、把复杂度放在哪里，以及用什么证据选择。

## 1. 研究结论与证据强度

**现阶段最值得研究的是“原程序执行、宿主语义接入、界面表达”三者如何组合，而不是先决定增加哪个 IR 或调用几次模型。** 这三项可以分别选择方法，同一张卡也不必采用单一路线。

现有证据支持以下判断：

1. 对已覆盖的 MVU/EJS 类行为，复用公共运行时明显减少了逐卡逻辑翻译；仓库历史实验提供了具体例子。但现有少数样本不足以推断整个社区的卡片分布。
2. 作者自定义 JS 的难度至少包含算法、宿主依赖、执行时序、隐含状态和界面耦合五个维度。“高级”不是可用于路由的单一分类。
3. 原生静态配置、JS 驱动的宿主组件、局部 Web 界面、原代码加兼容层、局部源码转换，都有合理适用范围。现在没有证据应当排除其中任何一条研究路线。
4. AST、运行轨迹、模型分析和对照测试提供不同证据。任何一种都不能独立产出完整可靠的“这张卡做什么”。
5. 模型解释、两阶段生成、执行反馈都有研究依据，也有失败和适用范围限制；不能仅靠架构直觉选定。
6. 比优化报告更有决策价值的，是建立同一行为在源宿主和目标宿主中的对照方法。报告应表达这些证据，不承担证明职责。

以上属于综合判断。尚未得到新样本分布、路线成功率、手机运行成本或模型请求成本的实测数据。本轮完成公开一手资料调查和本地源码审查，没有运行新角色卡、请求适配模型或实现新宿主。

## 2. 调查基础与版本范围

本地审查基线为提交 `e371c246c500acb86cf9c4b4062a068b370c6370` 及当前工作区。重点检查了编译输入、提示词、输出组装、MVU/EJS 宿主、模块预处理、会话检查点和既有实验记录。

上游资料包括 SillyTavern 扩展文档、Tavern Helper 类型声明及事件实现、MVU、Prompt Template、QuickJS、Acorn、Jelly、Jalangi2、Remote DOM、Android WebView，以及程序翻译研究。文内在使用处提供来源。

须区分三种版本：

- Player 已锁定的 MVU 源码提交为 `61010dab47bc3a08a1b626320bf7fc8c9573eca4`，Schema 辅助库提交为 `276040b5f26436f18662d841fb667429e82b26d4`，见 [upstream-lock.json](../tools/mvu-probe/upstream-lock.json)。
- Player 的 EJS 宿主注释及对照记录指定 Prompt Template `d6f520d149aba146305b0b781ddd691d449c28d2`。
- 本轮查阅的上游 `main/master/beta` 文档和声明是检索时的分支资料，未全部固化为提交快照；用于研究能力范围，不能直接成为 Player 的新兼容基线，也不能证明旧卡当时依赖的行为。

用户提供的两份保留研究稿作为待检验的观点，未作为外部实证来源。

## 3. 先重新定义问题：到底要保留什么

角色卡不是单一 JS 程序。它可能同时包含自然语言规则、模板、变量框架、界面、事件脚本和远程依赖。移植结果至少要分别考察：

| 维度 | 示例 | 可以怎样比较 |
| --- | --- | --- |
| 提示词行为 | 条件选文、历史清理、插入位置 | 同一上下文下输出文字、消息顺序及触发条目 |
| 状态行为 | 数值变化、物品增删、Schema 默认值 | 相同输入后的变量树及中间更新 |
| 交互行为 | 填表、选择、点击、取消 | 用户操作导致的草稿、状态、消息和请求 |
| 时序行为 | 先更新后显示、事件等待、重复监听 | 事件轨迹、提交顺序、重复执行次数 |
| 恢复行为 | 重生成、候选切换、进程恢复 | 恢复前后下一步行为是否一致 |
| 表现与阅读 | 排版、折叠、地图、图像 | 信息可读性、操作可达性及必要的视觉结果 |

可用研究记法表示为：

`Trace(程序, 宿主版本, 初始状态, 历史, 操作序列, 外部输入)`

Trace 不只包含最终 JSON，还包括发给模型的请求、草稿、可见数据、状态变化和外部效果。不同路线可以接受不同的视觉差异，但必须明确比较了什么。这个记法是研究用观察模型，不要求在产品中实现通用轨迹引擎。

尤其要区分作者写的确定性规则与作者交给聊天模型的自然语言规则。若原卡让聊天模型判断是否消耗道具，不能因为本地没有消耗函数就判定缺失；若源码确实有消耗函数，也不能仅靠提示聊天模型“请消耗”就认为等价。仓库 [原生适配复盘](native-adaptation-retrospective-20260907.md) 已记录这种误判。

## 4. 上游机制说明了什么

### 4.1 酒馆宿主远大于 JS 解释器

SillyTavern 扩展能够访问应用上下文、监听事件、管理设置和聊天元数据。文档同时指出事件参数并不统一，需要结合实际发出事件的位置理解。由此可见，“函数名存在”只说明表面接口，不能代替上下文和时序语义。[SillyTavern 扩展开发](https://docs.sillytavern.app/for-contributors/writing-extensions/)

Tavern Helper 通过 iframe 执行脚本。[Tavern Helper 项目说明](https://github.com/N0VI028/JS-Slash-Runner) 其公开接口覆盖变量、消息、事件和模型生成。这里至少有四组对移植非常重要的事实：

- 变量具有消息、聊天、角色、脚本、预设、全局等不同作用域；`updateVariablesWith` 还区分同步与异步更新函数。[变量声明](https://raw.githubusercontent.com/N0VI028/JS-Slash-Runner/main/@types/function/variables.d.ts)
- 消息 API 涉及候选读取、消息编辑、创建、删除和刷新选项，部分刷新会触发事件。[消息声明](https://raw.githubusercontent.com/N0VI028/JS-Slash-Runner/main/@types/function/chat_message.d.ts)
- 事件 API 包含去重、移到最前或最后、一次性监听及销毁时卸载；不是单纯的名称到回调数组。[事件声明](https://raw.githubusercontent.com/N0VI028/JS-Slash-Runner/main/@types/iframe/event.d.ts)、[事件实现](https://raw.githubusercontent.com/N0VI028/JS-Slash-Runner/main/src/function/event.ts)
- 脚本可以主动请求生成，携带提示词覆盖和注入，也有生成标识与取消接口。因此一部分“高级脚本”实际是生成编排程序。[生成声明](https://raw.githubusercontent.com/N0VI028/JS-Slash-Runner/main/@types/function/generate.d.ts)

这些资料证明相关能力在生态中存在，不证明它们在角色卡中的使用频率。

### 4.2 MVU、EJS 引擎和整个扩展不能混同

MVU 的公共变量更新代码可以作为可复用程序研究，而非要求模型逐卡重写更新协议。[Player 锁定的 MVU 更新实现](https://raw.githubusercontent.com/MagicalAstrogy/MagVarUpdate/61010dab47bc3a08a1b626320bf7fc8c9573eca4/src/function/update_variables.ts)

但 Prompt Template 的公开功能还包含生成、渲染及消息注入入口。执行一个 EJS 字符串，不意味着复现整个扩展在提示词管线中的位置、作用域和钩子。[Prompt Template 功能文档](https://raw.githubusercontent.com/zonde306/ST-Prompt-Template/master/docs/features.md)

**研究推论：** 原代码复用的实际单位可能是一个公共库、一个宿主执行入口或一组共同生命周期的模块，不一定是一条世界书，也不一定是一整张卡。

### 4.3 QuickJS 提供执行机制，宿主提供外部世界

QuickJS 提供脚本与模块执行、C 函数接入、Runtime/Context、内存上限和中断接口。其 Runtime、Realm 及对象共享关系有明确语义。[QuickJS 文档](https://bellard.org/quickjs/quickjs.html)

**研究推论：** 升级 JS 语言能力、提供浏览器 DOM、实现酒馆 API、保证消息恢复，是四类不同工作。更多标准 JS 语法不能自动补齐另外三项。

## 5. 对现有实现的新核查

### 5.1 不是完全没有 AST

`NativeProgramExtractor` 仍主要依靠字段、标记和正则整理材料，但 [schema-script.mjs](../tools/mvu-probe/schema-script.mjs) 已使用 Acorn 解析模块声明，移除允许的 import/export，并拒绝未映射的动态导入。

当前项目已经有“用 AST 做精确、局部处理”的实例。缺少的是通用的编译输入分析，不能笼统说整个项目没有语法分析基础。

### 5.2 MVU 宿主已有少量写入和事件能力

[checkpoint-host.mjs](../tools/mvu-probe/checkpoint-host.mjs) 实现了局部 `eventOn/eventEmit`、`setChatMessages`、`replaceVariables`、`updateVariablesWith` 等入口；另有空实现和特殊 DOM 分支。

但这些入口服务于固定 MVU bundle 的临时消息视图。例如 `eventOn` 只向数组添加回调，没有完整实现上游返回值、去重和卸载；变量作用域限于消息；`saveChat` 是空函数。因此：

- 不能说 Player 完全没有事件或变量写入机制。
- 也不能把这些同名函数直接登记成“支持 Tavern Helper 对应 API”。

评估兼容层需要记录“在哪个宿主、针对哪个版本、哪些参数和效果成立”，而非仅列函数名。

### 5.3 持久化变量树不是持久化整个 JS 程序

[MvuConversationRuntime.kt](../app/src/main/java/io/github/zvensmoluya/tavernplayer/conversation/mvu/MvuConversationRuntime.kt) 的 `withRuntime` 每次创建并关闭运行时；更新从外部传入的检查点开始。

这对已有 MVU 路径有清晰意义，但若未来作者脚本使用模块变量、闭包计数、内存缓存或跨事件 Promise，它们不会因为保存 `mvuState.data` 自动恢复。这个结论是根据生命周期代码作出的推断，不代表已在真实作者脚本上复现故障。

### 5.4 安装验证与执行验证仍分离

[NativeCompilationService.kt](../app/src/main/java/io/github/zvensmoluya/tavernplayer/characters/NativeCompilationService.kt) 做一次模型生成和本地 `complete()`；没有在普通编译入口运行 QuickJS 探测。仓库确实有独立的运行、Android 及真实对话测试，不能把“编译入口不试运行”说成“项目从未验证运行”。

## 6. 七条可比较的路线

下面是研究选项，不是要求全部实现。各项判断为基于机制的推论，尚无本项目路线对照数据。

| 路线 | 保留或转换什么 | 主要收益 | 难点与可能失败方式 |
| --- | --- | --- | --- |
| A. 现有原生配置路线 | 模型生成字段、列表、表单，复用 MVU/EJS | 手机体验统一，产物容易检查，现有基础最多 | 表达不了的交互被省略；配置逐步膨胀 |
| B. 原 JS + 更广宿主兼容层 | 保留逻辑，补 API、模块加载和事件 | 减少逐卡翻译，公共投入可覆盖多卡 | 版本、作用域、顺序、隐含状态与恢复成本 |
| C. JS 驱动原生组件 | 脚本输出组件树或更新消息，原生渲染，事件回到 JS | 动态界面与原生体验可并存 | 老 HTML 不会自动适用；需映射 UI API、事件和布局 |
| D. 局部 Web 界面 | 保留 HTML/CSS/JS，接入 Player 数据和操作 | 原布局和 DOM 行为保留较多 | 宿主依赖仍需接入；多楼层内存、滚动、输入与恢复需测 |
| E. 保留完整或较大的酒馆 Web 宿主 | 把原环境作为实际播放路径之一 | 减少宿主再实现，适合作为高保真参照 | 与原生会话存在所有权竞争；维护和用户体验代价较大 |
| F. 局部源码转换 | AST 或模型抽取/改写耦合片段，其余原样执行 | 可绕开难以提供的 DOM 或宿主依赖 | 需要证明闭包依赖、异常、副作用与顺序未变 |
| G. 共享适配包或作者提供入口 | 编译结果、依赖包、人工接入元数据复用 | 把成本从每位玩家重复请求转为一次制作 | 来源身份、版本失效、可复用范围和生态启动问题 |

### 6.1 原生不一定等于静态 JSON

Remote DOM 展示了另一种结构：脚本在独立环境构造节点树，通过消息同步到宿主；宿主可控制允许的组件，并把事件传回。其现成实现面向 Web DOM，不是 Android Compose 转换器。[Remote DOM](https://raw.githubusercontent.com/Shopify/remote-dom/main/README.md)

它还提供有限的 DOM polyfill，用于没有原生 DOM 的环境；这不是完整浏览器。[Remote DOM polyfill](https://raw.githubusercontent.com/Shopify/remote-dom/main/packages/polyfill/README.md)

**对 Player 的研究价值：** 可以考察“保留 UI 的 JS 状态和事件，只改组件表达”这一中间路线。是否适合 QuickJS、是否能映射现有 jQuery/HTML、能保留多少原样式，必须实验，不能从已有库直接推导成功。

### 6.2 局部 WebView 不应仅因已有产品方向而退出研究

Android 提供 WebView 与原生消息桥，可以承载 Web 内容并与宿主通信。[Android JS bridge](https://developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge)

研究时可以让聊天正文保持原生，仅将特定交互区域置于 Web 界面；也可以把 Web 环境只作为开发期参照。需要实测键盘、焦点、长消息滚动、主题、可访问性、动态尺寸和后台恢复。

桥暴露哪些能力会影响隔离与维护代价。来源校验和接口暴露是架构成本的一部分，而不是判定整条路线不可行的理由。[Android WebView bridge 风险说明](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges)

### 6.3 局部转换值得保留，不能一概等同于重写整卡

例如计算函数只接受状态和用户输入，DOM 代码只负责取值和显示，可以研究把该函数接到原生输入。另一种情况是条件判断直接依赖元素是否可见、DOM 顺序或浏览器布局，此时“抽出业务函数”本身就在改变程序。

局部转换应与原程序加兼容层比较。模型生成的小段 JS、固定 AST 变换、原生实现都可以作为候选；不能因为模型可能犯错就提前排除，也不能因为编译通过就接受。

### 6.4 适配成本也可以在导入前支付

共享包可以包含原件哈希、模块依赖、目标宿主版本、界面定义及验证证据；作者也可能直接提供适配入口。它能减少用户等待，却不能凭哈希证明语义正确。研究应比较按原件、程序来源、公共依赖和卡族复用的粒度。

## 7. 作者自定义 JS：按行为拆解难度

| 脚本特征 | 值得比较的方法 | 必须查明的问题 |
| --- | --- | --- |
| 纯计算、排序、随机规则 | 原 JS 执行；局部转换；输入输出对照 | 数字边界、随机来源、异常与副作用 |
| Schema 和更新回调 | 公共 MVU 宿主；补实际回调接口 | 注册时机、处理顺序、是否修改传入对象 |
| 只读派生展示 | 原 JS 计算后展示；模型生成原生投影 | 是临时显示结果还是持久事实 |
| 点击后修改状态 | 原回调加桥；抽取局部函数；保留 Web 交互 | 状态来源、重复点击、失败与取消 |
| 收到消息后执行 | 事件兼容层；显式生命周期接入 | 事件顺序、一次性监听、卸载与重入 |
| 自动调用模型 | 生成 API 兼容；Player 侧操作编排 | 请求上下文、取消、结果提交、重试副作用 |
| DOM 深度耦合 | 局部 Web；DOM 子集；源码转换 | 布局查询、父窗口、选择器与隐含状态 |
| 远程模块和动态导入 | 预先打包；按依赖解析；版本化模块仓库 | 可获取版本、模块身份、加载顺序和资源 |

一个说明性例子，不来自社区素材：

```js
let uses = 0;
onClick(async () => {
  uses += 1;
  const result = await requestDescription(uses);
  saveVariable('description', result);
});
```

其难点不在 `uses += 1`：原生按钮如何触发闭包？请求期间切换候选怎么办？失败时 `uses` 是否保留？恢复后 `uses` 来自哪里？同样的变量树不保证下一次行为相同。

可以比较持续保存 JS 实例、显式导出程序状态、记录输入并重放、局部转换为可恢复状态等方法。持续实例仍要面对进程死亡；重放必须控制随机、时钟、网络及模型响应，否则会重复外部效果。这里应先测不同作者写法，不能直接指定所有脚本改用一种状态模型。

## 8. 程序分析算法：每一层能知道多少

### 8.1 语法索引

Acorn 提供 AST、源码位置和 token/comment 信息，适合建立导入、函数、模板字面量及调用位置索引。其文档还指出，现代 JS 的单独词法分析存在依赖解析上下文的歧义。[Acorn API](https://raw.githubusercontent.com/acornjs/acorn/master/acorn/README.md)

对 Player 的用途包括准确定位模板、保留来源范围、识别静态字符串参数、分离可解析脚本。应当记录“语法上存在一个调用”，而非立即写成“程序一定读取这个变量”。解析失败也应保留材料。

### 8.2 静态依赖与副作用分析

可研究局部常量传播、别名追踪、函数调用边及状态读写集合。投入可以从单函数逐步扩展，不必从零实现整个 JS 分析器。

Jelly 已提供 JS/TS 调用图和库使用分析，面向 Node.js，明确采用不完全健全的近似建模，部分依赖分析还可能很耗时。它证明这类方法已有工具，也证明不能把“生成完整行为图”当成轻量确定性步骤。[Jelly](https://raw.githubusercontent.com/cs-au-dk/jelly/master/README.md)

**研究推论：** 可先在离线研究环境用静态工具发现依赖，再评估哪些结果值得在 Android 编译路径重用。静态分析报告应区分已解析、可能和未知，不用单个未经校准的 confidence 数值掩盖差异。

### 8.3 动态追踪

在真实宿主 API 边界记录参数、返回值、事件、状态前后差异，比重新实现整个 JS 求值器更接近当前基础。更深入时，可以用插桩追踪分支和属性读取。

Jalangi2 是动态插桩框架的直接实例，但其 README 声明主要支持 ES5.1，部分 ES6 未充分测试，因此本研究只借鉴方法，不将它推荐为现有卡片的即插即用工具。[Jalangi2](https://github.com/Samsung/jalangi2)

未观察到调用不等于不存在调用；记录器若把缺失 API 一律返回空对象，也可能制造“运行成功”。追踪时应区分真实实现、模拟响应和未实现行为。

### 8.4 部分求值与程序切片

对已知初始化参数提前执行一部分代码、留下未知输入对应的剩余程序，是值得研究的方向；从某个输出或操作向后收集依赖，也有机会缩小转换范围。

但任意 JS 的闭包、对象别名、异步行为和宿主调用会增加成本。Prepack 曾研究初始化求值与剩余代码生成，其官方仓库现已归档；它是方法参考，不是本项目可以直接依赖的完整解决方案。[Prepack](https://github.com/facebookarchive/prepack)

### 8.5 模型语义分析

模型可以参与功能归并、数据含义理解、前端重构、依赖假设、测试输入设计和局部转换。没有理由要求它只能填标签，也没有理由让它独自裁决兼容性。

比较合理的研究对象是：模型获得原文、语法索引、运行观察和目标说明时，相比仅获得原文，会减少哪些错误、增加哪些误判？这需要消融实验，而非预先断言“本地事实越多越好”。

## 9. IR 应该回答问题，而不是证明架构像编译器

有必要区分至少三种可能的中间表示：

| 表示 | 回答的问题 | 何时有价值 |
| --- | --- | --- |
| 来源与证据索引 | 某个判断来自哪里、观察条件是什么 | 精确定位、追踪、缓存失效与诊断 |
| 程序接入表示 | 哪些模块在何宿主、何入口执行，效果如何接入 | 多程序协作、不同路线组合、安装验证 |
| 界面表示 | 如何显示、输入、触发交互 | 原生布局、动态组件或局部 Web 容器 |

跨来源行为图可建立在这些记录之上，但不一定必须先于所有生成。模型填写的图仍然是待验证的解释；把同一批不确定判断换成图，并未减少不确定性。

一个值得检验的算法形式是“对每个功能比较候选方案”，而不是源类型到目标能力的唯一映射：

```text
功能及其依赖证据
    → 原程序接入候选
    → 原生映射候选
    → Web 保留候选
    → 局部转换候选
    → 各自所需能力、观察结果及代价
```

候选之间还可能冲突：两个方案都处理同一显示正则、两个状态写入器并存、事件被重复注册。若这些组合问题在样本中普遍出现，图或约束求解才有明确用途。小规模时直接检查约束可能足够；目前不能据此决定引入通用求解器。

选择指标可以包含行为差异、遗漏风险、用户体验、延迟、运行资源与维护投入。先报告多维结果，不提前编造一组权重算出“最佳路线”。

## 10. 模型流程与上下文：文献没有给出单一答案

### 10.1 “先解释再翻译”不是没有依据

Explain-then-Translate 在基于 MultiPL-E 的跨语言实验中观察到，自生成解释能够改善零样本翻译，复杂任务的收益更明显。这支持把解释阶段列入对照，而不是先认定其无效；它没有验证酒馆事件程序或 Android 界面迁移。[Tang 等，EMNLP 2023](https://aclanthology.org/2023.findings-emnlp.119/)

因此，“没有本地验证的中间解释不能保证正确”成立，但“没有本地验证，两阶段就没有价值”过强。可测性能改善与可证明正确性是不同问题。

### 10.2 执行反馈也不能保证更好的修复

FLOURINE 研究用差分模糊测试检查翻译到 Rust 的程序，同时发现其实验中简单重新生成比某些反例反馈更有效。作者讨论了随机反例难读、输入体积等因素；实验排除了并发、网络和文件 I/O，测试也不是等价性证明。[FLOURINE 完整论文，尤其 IV-B、VI-C](https://arxiv.org/html/2405.11514v1)

tHinter 则研究结合覆盖信息和差分结果定位翻译错误，报告了辅助调试收益。它支持研究“定位后的简短证据”，不支持把全部日志直接塞回模型。[tHinter，2025](https://arxiv.org/abs/2501.09475)

CoTran 将编译和符号执行反馈用于训练，说明结构与功能反馈可以分开利用；训练方法的收益不能直接当成运行期增加一次请求的收益。[CoTran，ECAI 2024](https://arxiv.org/abs/2306.06755v4)

### 10.3 应当比较的调用策略

- 一次生成接入与界面配置，作为现有基线。
- 一次生成，但附加语法或运行观察。
- 解释后生成，两阶段保留完整来源追溯。
- 首次失败后按错误类型选择重新生成或定点修正。
- 较复杂卡允许按需查询源码、宿主说明或探测结果。
- 离线制作者执行较重分析，玩家复用版本化产物。

不要求每张卡相同策略。若研究结果支持按复杂度路由，还需验证复杂度指标是否预测收益，而非仅按源码长度猜测。

### 10.4 动态能力上下文与多轮策略是两个独立变量

能力说明可以从宿主契约生成，也可按相关性分块。函数签名能够生成，但作用域、事件顺序、数据复制和恢复语义还需要实现与测试约束，不能只靠类型声明自动推导。

上下文可以采用完整说明、概览加相关详解、或按需检索。应测裁剪是否漏掉适配方向；不存在“固定 prompt 薄就一定好”的结论。公共依赖重复文本可通过版本标识和可靠摘要减少，但未知变体必须保留发现路径。

## 11. 验证应覆盖效果，而不仅是成功运行

### 11.1 五种证据要分别保留

1. 来源与结构成立。
2. 在某宿主版本成功加载和初始化。
3. 在给定状态与操作序列下成功执行。
4. 与源宿主在这些测试上的观察一致。
5. 真机中完成阅读、交互和恢复任务。

前一项不自动推出后一项。缺失初始路径也不应一概失败，需要区分可选、动态创建、路径错误与状态不足。

### 11.2 对照需要两种参照

可以先用 Player 当前宿主做目标环境探测，再用尽可能接近作者环境的酒馆及扩展版本做行为参照。如果源程序也无法执行，应记录参照不可用、依赖漂移或原件问题，而非制造预期答案。

仅在两个地方调用同一份错误 shim，只能证明内部一致，不能证明宿主兼容。

### 11.3 对话任务如何保持可比较

对变量解析、EJS 和界面适配，可给两端相同的录制模型回复、历史、用户操作及可控时钟/随机输入，比较宿主效果。需要测真实聊天遵循时，再单独做实际生成；不能要求两次随机生成的故事逐字相同。

测试不仅覆盖空历史、初始化，还应有动态新增字段、边界数值、多个监听器、取消、重复点击、候选切换和重启。变形测试关系必须来自具体语义，例如“只打开一个纯展示面板不应扣物品”；不能假设所有更新都幂等。

### 11.4 UI 与逻辑的测试不能互相替代

变量正确但操作按钮不可达，仍然影响游玩；页面漂亮但生成草稿改变原文，同样失败。应分别验证可见信息、输入选项、动作结果、长文本、键盘和历史状态。

## 12. 用哪些实验选择路线

这些是提议，尚未执行。重点是让实验能否定方案，而不是展示方案能工作。

### 12.1 先建立样本画像，不急着扩建运行时

已有 C-03/C-04 可作为回归对照，不足以作为留出集。新增样本应覆盖不同作者写法、框架、依赖和交互；同一模板的多个换皮版本不能算多个独立证据。

研究画像至少记录：原件哈希、依赖版本是否可解析、程序入口、状态作用域、DOM 使用、外部效果、恢复要求以及来源是否完整。未知项保留为未知；不在这一阶段强行判定支持。

### 12.2 五组比较

| 实验 | 比较内容 | 能支持或否定什么 |
| --- | --- | --- |
| R-01 输入辅助 | 原文 vs 原文加语法索引 vs 再加运行观察 | 本地分析是否真正减少错误，是否引入漏检 |
| R-02 界面路线 | 固定原生配置、JS 驱动组件、局部 Web | 哪些交互值得转换，哪些保留成本更低 |
| R-03 作者脚本 | 原码加 shim、局部改写、较完整 Web 宿主 | 问题主要在 API、时序、隐含状态还是代码转换 |
| R-04 调用策略 | 单次、解释后生成、失败重试、定点反馈 | 同等预算下多阶段是否值得 |
| R-05 恢复语义 | 常驻实例、检查点重建、录制重放或显式状态导出 | 哪些程序可以稳定跨候选及进程恢复 |

先用少量中性构造样本隔离机制，再在真实留出卡上检验。合成用例不能代替生态覆盖，真实大卡也不能代替可定位的小用例。

### 12.3 应报告的指标

- 功能遗漏、错误恢复声明、行为差异；“整卡成功”要明确必要功能集合。
- 首次通过、最终通过、请求次数、总 tokens、失败阶段。
- 手机首次准备时间和日常运行时间，报告中位数及尾部延迟。
- JS/WebView 内存、后台恢复、连续操作和长会话表现。
- 新增一个样本需要多少专用规则，多少投入可复用。
- 人工复核时间、测试参照缺失比例、未知问题比例。

模型方案应用相同卡片、相同宿主和可比预算，并做重复试验；人工修过的产物不能混入自动成功率。按卡族划分开发集与留出集，避免把相同框架骨架当成跨卡泛化。

### 12.4 哪些结果会改变当前倾向

- 若多数新增卡仅需少量原生字段映射，继续改善现有配置路线即可，不应为少数想象场景建立复杂图。
- 若少数稳定的宿主 API 补齐即可让大量原码工作，应提高公共兼容层优先级。
- 若 DOM 耦合广泛且转换成本高，应认真比较局部 Web 保留，不把它视为失败兜底。
- 若动态组件能覆盖交互而显著降低 Web 运行成本，才值得扩大 JS 驱动原生界面。
- 若局部转换在留出样本对照中可靠，不应因“原文优先”原则把它排除。
- 若多阶段收益不足以抵消延迟和成本，保留单次；若复杂卡收益显著，允许按需追加。

## 13. 当前可以形成的设计判断

**较强判断：** 把程序执行、宿主行为、界面体验和验证证据分别评价。保留源码与版本来源。不能把安装成功作为语义成功，也不能让输入识别器悄悄决定全部可见行为。

**值得优先实验的假设：** 以原程序和公共依赖复用为起点，按功能比较原生映射、局部 Web、动态组件和局部转换；利用静态索引与真实宿主观察帮助模型，而不是让模型只看人工归纳后的行为图。

**尚不应定案：** 是否需要统一 Behavior IR、是否扩大 Tavern Helper 兼容层、是否采用 Remote DOM 类结构、是否引入 WebView、是否允许自动改写 JS、是否默认两轮请求。上述问题都需要样本和对照结果。

这与单纯把提示词改成动态能力表的区别在于：研究对象首先是可保留的实际行为以及各路线代价，提示词和数据结构随证据收敛。

## 14. 后续讨论的源码入口

| 问题 | 当前文件 |
| --- | --- |
| 输入覆盖与来源索引 | [NativeProgramExtractor.kt](../content-core/src/main/kotlin/io/github/zvensmoluya/tavernplayer/content/NativeProgramExtractor.kt) |
| 模型职责和目标说明 | [NativeCompilationInstructions.kt](../content-core/src/main/kotlin/io/github/zvensmoluya/tavernplayer/content/NativeCompilationInstructions.kt) |
| 输出表达能力 | [NativeCompilationModels.kt](../content-core/src/main/kotlin/io/github/zvensmoluya/tavernplayer/content/NativeCompilationModels.kt)、[NativeAdaptationModels.kt](../content-core/src/main/kotlin/io/github/zvensmoluya/tavernplayer/content/NativeAdaptationModels.kt) |
| 本地组装与报告 | [NativeAdaptationCompiler.kt](../content-core/src/main/kotlin/io/github/zvensmoluya/tavernplayer/content/NativeAdaptationCompiler.kt) |
| 请求次数与预算 | [NativeCompilationService.kt](../app/src/main/java/io/github/zvensmoluya/tavernplayer/characters/NativeCompilationService.kt) |
| 结构与引用验证 | [NativeAdaptationValidator.kt](../content-core/src/main/kotlin/io/github/zvensmoluya/tavernplayer/content/NativeAdaptationValidator.kt) |
| 已有 AST 处理 | [schema-script.mjs](../tools/mvu-probe/schema-script.mjs) |
| MVU 的实际 API 语义 | [checkpoint-host.mjs](../tools/mvu-probe/checkpoint-host.mjs)、[quickjs-entry.ts](../tools/mvu-probe/quickjs-entry.ts) |
| 依赖来源与打包 | [upstream-lock.json](../tools/mvu-probe/upstream-lock.json)、[build.mjs](../tools/mvu-probe/build.mjs) |
| EJS 的实际宿主 | [ejs-entry.mjs](../tools/mvu-probe/ejs-entry.mjs) |
| 运行时重建与状态恢复 | [MvuConversationRuntime.kt](../app/src/main/java/io/github/zvensmoluya/tavernplayer/conversation/mvu/MvuConversationRuntime.kt)、[QuickJsMvuRuntime.kt](../app/src/main/java/io/github/zvensmoluya/tavernplayer/conversation/mvu/QuickJsMvuRuntime.kt) |
| 世界书与模板执行位置 | [PromptCompiler.kt](../conversation-core/src/main/kotlin/io/github/zvensmoluya/tavernplayer/conversation/PromptCompiler.kt) |
| 原生状态读取与显示 | [ConversationStateReader.kt](../conversation-core/src/main/kotlin/io/github/zvensmoluya/tavernplayer/conversation/ConversationStateReader.kt)、[NativeAdaptationView.kt](../app/src/main/java/io/github/zvensmoluya/tavernplayer/conversation/NativeAdaptationView.kt) |

文档核查：检查本地链接、Markdown 差异与格式。本轮未执行应用构建、JS/Android 测试、联网编译或新样本运行；本文实验表不是验收结果。
