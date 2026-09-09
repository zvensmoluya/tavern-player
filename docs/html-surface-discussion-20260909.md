# HTML Surface 与免编译游玩讨论

日期：2026-09-09。状态：持续讨论与源码核查记录；尚未实现 HTML Surface，也未决定最终渲染容器与兼容范围。经 2026-09-09 讨论修订方向：兼容主路线从逐卡模型编译转向直接执行作者原程序，Native 编译降为可选体验增强。用户要求后续一边讨论一边更新本文，区分用户方向、已核实事实、建议与待验证假设。

## 当前收敛结论（2026-09-09 讨论后修订）

本节是讨论收敛后的方向性结论，不写实施设计，也不把建议写成已实现功能。版本、函数清单与接口细节留待 Host Compatibility Layer 设计时确定。下文保留此前推导与核查证据。

**一句话方向：从“编译社区程序”转向“在已声明的宿主能力边界内运行作者原程序”，Native 编译降级为体验增强。**

### 主路线

1. **兼容主路线从“模型理解并重写原程序”转向“尽量直接执行原程序”。** 直接执行不成立的卡明确报告缺失依赖或缺口，不自动回落到模型编译，也不把文字降级称为完整玩法；否则“尽量”会成为旧路线的后门。
2. **WebView／浏览器运行时因此从 fallback 变成主要兼容基础设施候选，Android 系统 WebView 是首选验证对象。** 这是针对完整页面兼容目标的工程选择，不表示有限 HTML 富文本也必须使用浏览器；Surface 粒度仍未定。
3. **可执行 HTML/JS 的语义来源是 Tavern Helper／酒馆助手提供的网页运行与宿主能力**，不把 SillyTavern 普通消息渲染本身误认为 JS Runtime。具体钉哪个版本、哪些函数进入 compatibility profile，是设计 Host Compatibility Layer 时才需要决定的事；现在钉版本容易造成“复刻 Tavern Helper vX.Y”的错误印象。
4. **Player 只负责有限宿主能力：消息、变量、生成、事件、资源、持久化。** “有限”指条数有限、每条语义完整，不指接口浅——暴露了方法但不保证同步语义、事件顺序和消息索引口径，卡不会报错，只会静默地行为错误。
5. **Native Adaptation 不再决定“能不能玩”，而是可选的原生体验优化。** 它同时是既有卡分析语料：把已实现的原生能力反推成这些卡会调用的宿主接口，是宿主能力 profile 的第一版草案。
6. **QuickJS 继续保留给无 DOM 的 MVU／EJS 等逻辑**，不必全部迁进 WebView；同一段有副作用的程序不能在两个引擎中重复运行。
7. **新路线的主要难点变成状态同步、生命周期、副作用归属、安全与兼容边界。** 这些是有界、可写测试的工程问题，而不是“模型这次又猜错了”。
8. **验收形式随之改变：从“编译器输出对不对”（只能逐卡抽样）变成“宿主调用服务得对不对”**（可逐 API 写 conformance 测试，并用同一条消息、同一检查点在源宿主与 Player 中对照）。

这条路线更健康的原因：不再试图用有限 Native DSL 覆盖开放的社区 UI/JS 空间，而是只定义一个有限、可验证的宿主兼容边界。

### 仍然成立的架构约束

以下原则承接原评审版，与上方主路线并存；原原则 1（优先浏览器运行环境）与 3（Native 退出必经链路）已并入主路线，不再重复。

1. **WebView 提供内容兼容运行环境，Player 保持会话所有权。** 会话、消息、权威业务状态、生成编排、候选与持久化继续由 Player 管理。页面可以执行作者逻辑、持有 UI 状态和受控的数据视图，但不能独立维护另一份持久聊天事实。
2. **引入 WebView 不默认替换现有 QuickJS。** 已验证、独立于 DOM 的 MVU/EJS 等运行路径优先保留；页面 DOM/CSS/layout 与页面事件由 WebView 承担。但不能把“QuickJS 不能被替代”作为技术定律，也不能仅按某个函数是否访问 DOM 拆分原程序。共享闭包、对象身份、同步回调和事件次序可能要求一组程序留在同一 JS 执行环境；具体边界按原行为验证，不预先扩大任意后台脚本的支持范围。
3. **HTML 内容、脚本执行权和渲染容器是三个独立决定。** 区分普通富 HTML、可执行前端页面和后台脚本；普通消息中的 script 不自动执行。普通富 HTML 也可能因排版需求使用禁用脚本的 WebView，因此“用了 WebView”不等于“授予脚本执行权”。不能只见 HTML 标签就决定启动可执行页面。
4. **主要兼容工作转向 Host Compatibility Layer。** 重点是原变量 API、消息 API、生成命令和事件的可观察语义，而非让模型改写每个调用。HTML 识别、消息格式化、资源和渲染生命周期仍属于必须完成的工作，不能认为换上控件后已经解决。
5. **每项业务效果有唯一执行归属和提交路径。** JS 可以计算结果、请求操作，Player 负责权威状态提交与保存；MVU 更新、生成、变量写入和候选切换不能在多个环境重复触发。不把“唯一 owner”误写为所有计算都必须由 Kotlin 重写，也不据此承诺远端生成恰好执行一次。
6. **页面生命周期与业务生命周期分离。** 滚动回收、Surface 重建和重载可重新构建 DOM、绑定事件、展示状态，但不能因此再次执行已完成的开局写入或生成。恢复读取哪个候选、如何处理失效页面的迟到调用，需要显式约束。
7. **角色卡与模型输出中的 JS 按不可信内容处理。** 网络、导航、文件访问和宿主能力默认受限；桥接请求需验证来源、能力和目标会话，不向页面暴露任意 App 对象、文件或模型凭据。具体授权与兼容范围另行确定。[Android 原生桥风险说明](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges)

### 优先验证的机制，而非既定实现

- **JS 侧同步状态视图是重要候选。** 上游同步 `replaceVariables(...); getVariables(...)` 不能机械替换成异步 bridge。镜像可以提供同步可见性，但不会自动解决写入失败、并发修改、事件时序和崩溃恢复。它不是第二份权威数据库；待提交视图、提交确认、版本失效和错误如何影响后续调用，需要先定义契约。不能只靠定时推送 JSON 快照就声称支持同步写后读，也不要求每个 WebView 各自维护完整会话副本。
- **Surface 粒度保持开放。** 一条消息一个 WebView 不作为默认最终方案。整条富消息、局部页面或有限容器组合需要比较列表回收、动态高度、滚动、输入焦点、跨正文样式和真实内存成本。
- **资源优先经 Player 管理的虚拟 HTTPS origin 提供。** 可评估 `WebViewAssetLoader` 及受限路径处理，避免开放 file URLs 或 App 文件树。统一资源机制不等于把所有卡、会话和可信宿主页面放在同一安全源下；需明确跨卡资源授权和浏览器存储隔离，URL 路径不同不构成 origin 隔离。虚拟 HTTPS 地址也不意味着内容自动可信。[WebViewAssetLoader](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)

### 成功标准与首要验证风险

成功标准是：**零导入模型调用、不修改原卡，在真实消息链路中完成核心交互、状态更新、候选与重启恢复。** 普通聊天模型请求不在“零导入模型调用”的排除范围内。“零导入模型调用”只约束是否存在逐卡 AI 编译步骤，不约束网络请求；资源加载与安全政策属于独立问题，不要把这条写成“零网络请求”。允许可追溯的确定性装载与已声明依赖映射；若必须逐卡语义改写或修改样本才能通过，应单独计为适配路径，不算原码直接运行成功。原件哈希、宿主版本和验收行为需留证。

当前最值得优先排除的架构风险是：**多个执行环境之间的状态、生命周期和副作用归属失控。** 这是验证优先级判断，尚不是测量得到的风险排序；WebView 内存、启动与列表性能仍需设备验收。目标是一个 Player 会话系统拥有多个受控执行入口，而不是三个独立维护聊天事实的系统。

首轮对照应至少覆盖：同步写后读；两个 Surface 与后台更新交错；保存失败；旧候选页面的迟到写入；Surface 销毁重建；进程终止恢复；生成请求中断后不自动重复发送。记录 API 返回、可见状态、事件顺序及持久检查点，不能只检查最终截图或 JSON。

## 用户提出的方向

- 基础可用性不应绑定逐卡模型编译。即便假设编译成功率达到 90%，失败成本和等待、付费门槛仍可能损害初次体验；90% 是讨论中的假设，不是实测统计。
- 产品叙事希望由“为了兼容必须编译”转向“先体验，想要更适合自己的原生体验时再编译”。原作者的 HTML 排版与交互也可能是内容价值。
- 初始希望不引入 WebView、利用 Android 原生 HTML 能力；澄清富文本与网页执行的差别后，继续评估浏览器引擎和 HTML Surface。尚未把引入 WebView 写成已批准实施的决定。
- 现有原生状态、消息、生成等能力应复用。优先对齐作者程序调用的接口协议和行为，让原 JS 直接运行，而非默认让模型逐卡重写调用。
- 用户指出内容会通过世界书、Prompt、模型输出、Regex 等进入展示链路，HTML Surface 必须接入实际消息流，不能只考虑独立打开一个静态 HTML 文件。

## 本轮判断与讨论纠正

“原 HTML/CSS/JS + 公共宿主兼容接口 + Player 核心”在架构上可行，值得优先验证。是否比现有整卡原生重构节省总投入，尚无同样本实测结论；预期收益来自保留作者界面和逻辑、把逐卡转换变成公共接口实现。

宿主缺口不意味着必须使用模型编译。缺失接口可以由开发者实现一次并通过回归验证，使用同一行为的多张卡可共同受益。模型仍可帮助开发兼容层或制作可选原生适配，但不应因此成为每次导入的必需依赖。

“编译成功”与“原程序行为等价”不同；保留源码也不自动证明整卡兼容。两条路线都需要对照实际接口效果、事件与状态恢复。没有证据声称支持任意社区卡。

## Android HTML 与引擎分发

`Html.fromHtml` / `HtmlCompat.fromHtml` 与 Compose `AnnotatedString.fromHtml` 属于有限富文本转换，不提供网页 CSS 布局、DOM 和 JavaScript 运行环境。它们不能直接运行作者的表单和页面脚本。[Android Html](https://developer.android.com/reference/android/text/Html)、[Compose HTML 转换](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/AnnotatedString.Companion)

使用系统 `android.webkit.WebView` 时，Android framework / AndroidX API 从设备的 WebView provider package 加载 Chromium 实现；不需要把 Chromium 内核打入 Tavern Player APK，也不需要把 ST 的服务器和整套 Web 应用嵌进去。自行打包独立引擎是另一条路线，不是系统 WebView 的前提。[Chromium WebView 架构](https://chromium.googlesource.com/chromium/src/+/HEAD/android_webview/docs/architecture.md)

运行时仍有真实引擎成本，包括加载、内存、渲染进程、页面与资源管理；系统 provider 的版本和可用性也需在设备上验证。“不随 APK 打包”不能表述为零体积增量或零运行开销。当前没有本项目的体积、内存、启动时间测量。

### 继续讨论：QuickJS 接 DOM 与系统组件的区别

用户追问：给已有 QuickJS 接 DOM 是否真的相当于自建浏览器，以及是否像 Electron 一样借用用户浏览器内核。

需要限定此前“造浏览器”的说法：只提供节点树、选择器、文字修改和少量事件，并不等于实现完整浏览器，可以用已有库或有限宿主实现。QuickJS 本身是 JavaScript 引擎；DOM 是脚本访问文档的对象接口，拥有 DOM 不等于页面已经能显示。[QuickJS 官方说明](https://bellard.org/quickjs/)

如果目标是运行原作者的 HTML/CSS/JS 并保持页面行为，还需要 HTML 解析、CSS 样式计算、布局、绘制、输入交互及布局查询等协同。例如修改文字后，后续同步测量需要看到更新后的布局。可以复用库，不必全部从零写，但持续扩大网页兼容范围会变成浏览器渲染与运行子系统的集成工程。此前说法是对这一目标的成本判断，不是说“补一个 DOM 方法就必须重写 Chromium”。[浏览器渲染过程](https://developer.mozilla.org/en-US/docs/Web/Performance/Guides/How_browsers_work)

分发方式也需纠正：Electron 通常随应用分发自身的 Chromium 和 Node.js，不借用用户已安装的 Chrome。Android 系统 WebView 则使用系统选择的 WebView provider，不是任意默认浏览器，也不会因用户把默认浏览器改为 Firefox 就切换为 Gecko；具体 provider 与 Android 版本和系统配置有关。[Electron 官方介绍](https://www.electronjs.org/docs/latest)、[WebView 架构](https://chromium.googlesource.com/chromium/src/+/HEAD/android_webview/docs/architecture.md)

系统 WebView 自带页面 JavaScript 执行能力，采用它时无需将 QuickJS 接到它的 DOM 上。建议页面 JS 由 WebView 执行；已有 QuickJS 可继续承担适合独立执行的 MVU/EJS 等工作，实际职责划分仍需对照回调和共享状态要求验证。同一段有副作用的程序不能在两个引擎中重复运行。[Chromium WebView 介绍](https://blog.chromium.org/2013/11/introducing-chromium-powered-android.html)

## HTML 来源与消息三种投影

不能把所有前端源码都当成世界书注入 Prompt 后由模型重新输出。需要根据具体来源区分至少以下路径：

1. 世界书或预设给出输出规则，模型返回正文、标签、占位符或结构化数据；展示 Regex 将其中片段替换为作者预写的 HTML/CSS/JS。替换模板可以一直留在本地，不必让模型重写整个前端。
2. 开场、备用开场或消息本身已包含 HTML / 可渲染代码块，无须先调用聊天模型才显示。
3. 世界书或预设确实提供 HTML 模板或输出要求，聊天模型直接返回 HTML；此路径仍依赖聊天模型遵循格式，但不因此需要额外的导入编译模型。
4. 扩展脚本、模块和资源从独立入口加载；不能仅扫描消息 HTML 就宣称它们已接通。

上述是机制分类，不是覆盖率调查。C-04 的既有审计记录包含状态栏占位符替换、更新块折叠与历史 Prompt 清理，说明第一类路径在本地材料中有依据。[样本审计](archive/native-adaptation-retrospective-20260907.md)

示意链路如下，实际 Macro、Regex、Markdown 与扩展顺序需按选定上游版本验证：

```text
世界书 / 预设 / 角色定义 / 历史 Prompt 投影
  → 聊天模型
  → 通用输出处理 → 保存消息
                       ├→ Prompt 投影 → 下一轮模型输入
                       └→ 展示投影 → Markdown / HTML 内容识别 → 消息显示

开场 / 备用开场 ───────────→ 消息与展示链路
本地展示 Regex 替换模板 ───→ 展示投影中的 HTML/CSS/JS
独立脚本与公共依赖 ────────→ 对应运行入口与宿主接口
```

ST 将展示限定 Regex 和 Prompt 限定 Regex 分开；普通规则也可能改变保存文字。不能把渲染后的 DOM 或展示替换产生的 HTML 自动写回消息、再送入下一轮 Prompt，也不能一概清掉作者明确要求进入 Prompt 的 HTML。[ST Regex 文档](https://docs.sillytavern.app/extensions/regex/)、[本地三层投影研究](reference/st-research.md#11-regex-的真实三层投影)

ST 普通消息 HTML 渲染与酒馆助手的脚本界面是不同入口。酒馆助手文档说明，它把符合条件的消息代码块作为 iframe 网页运行，并额外提供变量等宿主能力；普通 ST 消息渲染不直接支持 script 标签。HTML Surface 不能把每个普通 HTML 标签或代码示例都自动升级为可执行程序。[酒馆助手渲染器](https://n0vi028.github.io/JS-Slash-Runner-Doc/guide/基本用法/渲染器.html)

## 当前代码可复用的部分与接入点

- [PromptCompiler](../conversation-core/src/main/kotlin/io/github/zvensmoluya/tavernplayer/conversation/PromptCompiler.kt) 已有独立的存储、Prompt、DISPLAY 投影；`projectDisplayText` 是展示接入点之一。HTML 支持不要求默认重写完整 Prompt 编排。
- [SafeMarkdown](../app/src/main/java/io/github/zvensmoluya/tavernplayer/conversation/SafeMarkdown.kt) 当前在 Markdown 解析前删除 script/style 和 HTML 标签。可执行 HTML 必须在这类破坏性文本清理前分流，并按所选渲染类型处理；不能仅删除清理代码就称为接入完成。
- [NativeDisplayRules](../conversation-core/src/main/kotlin/io/github/zvensmoluya/tavernplayer/conversation/NativeDisplayRules.kt) 会抑制已被原生表单接管的特定纯展示正则。原始 HTML 路径要明确如何处理这些规则，避免界面在渲染前已被旧适配隐藏。
- [QuickJsNativeRuntime](../app/src/main/java/io/github/zvensmoluya/tavernplayer/conversation/script/QuickJsNativeRuntime.kt) 已有状态读取、MVU 替换、程序状态、草稿和辅助生成入口；这些是内部能力基础，不等于完整 ST / Tavern Helper API。
- [MVU 宿主](../tools/mvu-probe/checkpoint-host.mjs) 已用兼容方法执行上游逻辑，但局部消息视图、事件注册与空保存接口只适用于其现有执行范围。EJS 的同名消息方法也有自己的模板宿主契约，不能仅按函数名合并。
- 消息候选、操作检查点、持久化、MVU/EJS 和角色图片资产可以继续服务于新展示路径。免编译运行还需解除公共程序准备对模型适配产物的依赖，不能只换 UI。

## 宿主与界面的职责建议

候选结构为：作者页面在浏览器中运行，通过兼容 JS 接口访问 Player 的消息、变量、生成和资源能力；Player 继续管理持久会话及候选归属。

作者操作自己页面的 DOM，由浏览器处理。作者直接操作 ST 父页面 DOM、导入内部模块或依赖其他扩展时，才需要额外兼容或明确缺口。ST 提供 Context 与事件接口，同时允许扩展访问可变聊天对象和 DOM，因此不能假设所有程序都有严格隔离的接口边界。[ST 扩展开发](https://docs.sillytavern.app/for-contributors/writing-extensions/)

方法对齐需包括参数默认值、变量作用域、返回值、同步/异步、消息索引、候选、事件顺序和异常。上游当前变量接口有同步读写，不能直接替换为 Promise；JS 侧数据视图是待研究方案，必须验证写后读一致性与持久化时机。[上游变量声明](https://raw.githubusercontent.com/N0VI028/JS-Slash-Runner/main/@types/function/variables.d.ts)

界面状态与会话业务状态应明确归属。不要让同一 MVU 消息更新在 QuickJS 和网页中各执行一次，也不要因页面重建再次提交开局操作。是否继续由 QuickJS 承担公共业务程序、页面程序是否需要共享对象或回调语义，都需用原程序验证，尚未决定运行实例布局。

## 待验证问题与下一步建议

- 容器粒度：整条富消息使用 WebView，还是原生正文中嵌入局部 HTML，或按内容类别组合。跨正文 CSS、内联排版、多个代码块会影响选择，不能预设所有内容都适合拆成独立面板。
- 流式与生命周期：半截 HTML 何时挂载、何时更新，避免每个 token 重载页面；列表回收、滚动回看、候选切换、页面销毁后，表单和业务副作用如何处理。相关行为需要真机验证。
- 运行范围：区分普通富 HTML、可执行前端代码块和后台脚本，核对原宿主实际识别与启动顺序。
- 资源与桥接：确定可加载模块、图片路径、外部资源和宿主权限；与已有角色资产管理一致，具体策略尚未批准。
- 兼容效果：用原开场表单、状态面板与至少一个未参与设计的样本，验证零编译模型请求下的显示、操作、状态更新、候选和重启恢复。
- 对照证据：相同消息、检查点与操作在源宿主和目标宿主中比较结果；记录需要逐卡修改的地方，不把模型或人工修改后的卡算作原码直接运行成功。

建议先验证消息展示链路与最小公共接口，再决定扩大兼容范围。没有决定删除 Native 编译器、切换所有聊天显示或迁移已有用户会话。

## 本轮工作与验证范围

本轮更新讨论文档：将收敛结论修订为“在已声明的宿主能力边界内直接运行作者原程序”的主路线，补充宿主语义来源、窄接口深语义、失败出口与验收形式；核查现有 Kotlin/JS 源码和公开一手资料。未修改应用代码，未运行浏览器原型、模型编译、聊天请求或设备测试；本页不作为 HTML Surface 已实现或性能达标的证据。
