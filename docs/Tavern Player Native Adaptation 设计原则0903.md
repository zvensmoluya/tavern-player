# Tavern Player Native Adaptation 设计原则

> 状态：当前目标方向，尚未完全反映仓库现有实现。
>
> 本文定义 Tavern Player 希望收敛到的产品与架构边界，不把现有 `AdaptationArtifact v1`、通用 UI Node 或 Action 视为必须保留的兼容契约。

## 1. 目标

Tavern Player 的目标不是重新实现 SillyTavern 的 Web 运行环境，也不是建立一套新的低代码平台。

其内容适配目标是：

> **兼容玩法，不兼容旧运行环境。**

对于依赖 HTML、CSS、JavaScript、插件 API 和浏览器运行时实现的复杂角色卡，Tavern Player 不追求原样执行其前端程序，而是提取其中真正影响角色扮演体验的内容与语义，并使用 Tavern Player 自己的原生界面重新呈现。

优先保留：

- Narrative：剧情正文与角色内容；
- Conversation State：影响当前和后续剧情的状态；
- User Input：剧情继续所必需的用户输入；
- Assets：CG、立绘、背景、物品图片等内容资源；
- Conversation Semantics：状态对后续 Prompt 与模型行为的影响。

允许降级：

- Web 页面布局；
- CSS 主题与视觉效果；
- DOM 结构；
- 动画与复杂转场；
- JavaScript UI Runtime；
- 第三方插件 Runtime。

因此，视觉等价不是 Tavern Player 的核心兼容目标。

这里的“能玩”首先意味着：

- 剧情可以正常开始并继续；
- 推进剧情所需的用户输入可以完成；
- 核心状态能够随 Conversation 保存、恢复并影响后续模型理解；
- 原卡的复杂视觉允许降级；
- 无法恢复的演出、CG 切换或附加界面不会阻断主要文字体验。

---

## 2. 不建立新的低代码运行时

早期 Adaptation 方案曾经出现以下方向：

- 通用 UI Node；
- Binding；
- Action；
- Condition；
- Event；
- 通用 capability；
- AI 生成可执行 Adaptation Artifact。

如果继续增加：

```text
condition
expression
computed
loop
event
action
binding
component tree
lifecycle
```

即使最终格式是 JSON 而不是 JavaScript，本质上仍然会演化成一套新的低代码前端框架。

这会带来两个问题：

1. Tavern Player 再次承担一个通用应用 Runtime；
2. AI 的适配任务重新变成程序生成，而不是内容理解。

因此：

> **Adaptation Contract 不应该成为一门新的 UI 编程语言。**

AI 可以理解旧程序，但不能获得设计和扩张 Player Runtime 的权力。

---

## 3. Native UI 的目的：由 Player 接管表现

设计 Native View 的目的不是让 Adaptation 获得更强的 UI 表达能力，而是：

> **由 Tavern Player 接管最终用户体验。**

Adaptation 描述：

- 内容是什么；
- 数据来自哪里；
- 用户需要输入什么；
- 哪些信息值得展示。

Tavern Player 自己决定：

- 布局；
- 卡片样式；
- 分页；
- 滚动；
- 搜索；
- Detail Sheet；
- 手机端适配；
- 动画；
- Compose 状态更新；
- 最终视觉风格。

因此，“可翻页框”“双栏”“折叠面板”等表现形式原则上不进入 Adaptation Schema。

---

## 4. 第一阶段 Native View

第一阶段不建立通用组件树，只维护极少数 Tavern Player 官方设计的完整 View。

### 4.1 Status View

用于展示少量当前状态，例如：

- 好感度；
- HP；
- 金钱；
- 时间；
- 地点；
- 身份；
- 当前阶段。

典型数据形态：

```text
key → scalar
```

例如：

```text
好感度: 82
金币: 120
地点: 酒馆
```

Status View 默认只读。

---

### 4.2 Collection View

用于展示一组同类结构化对象，例如：

- 背包；
- 装备；
- 技能；
- 任务；
- 人物关系；
- 地点列表；
- 物品列表。

典型数据形态：

```text
collection → records
```

例如：

```text
背包

铁剑
  攻击: 12
  耐久: 70

魔法药水
  数量: 3
  品质: 稀有
```

Collection View 可以由 Tavern Player 内部选择：

- 列表；
- 卡片；
- 分页；
- 搜索；
- 展开详情。

这些都属于 Player UI，不属于 Adaptation 能力。

---

### 4.3 Form View

用于用户提供结构化输入，例如：

- 开局人物设定；
- 身份选择；
- 世界设定；
- 自定义背景；
- 剧情选项；
- 少量结构化交互。

默认交互路径：

```text
用户输入
↓
生成 Draft
↓
用户确认
↓
正常 Conversation Turn
```

Form View 不因此获得通用：

```text
state.set
insertMessage
regenerate
worldBook.toggle
runScript
```

等能力。

如果某类 Setup 确实需要一次性初始化状态，应作为 Form 自身明确、有限的生命周期能力单独设计，而不是建立 Action Graph。

### 4.4 View 的来源与演化

官方 Native View 来自 Tavern Player 对文字角色体验的产品判断，而不是来自社区卡功能的频率统计。

真实社区卡可以用于：

- 验证已有 View 是否能保留核心玩法；
- 证伪现有设计假设；
- 暴露不可接受的降级；
- 帮助评估某项能力是否符合 Player 的产品方向。

但某种实现出现得多，不会自动使它成为新的 View 或 capability。社区内容用于检验产品边界，不负责定义 Player Runtime。

Native View 采用扩展淘汰制：可以在开发阶段新增、合并、拆分或删除，不为尚未发布的内部 Adaptation 数据保留兼容层。

---

## 5. Read / Input 只是概念模型

从用户与 Conversation 的关系看，特殊 Native UI 可以理解成两个方向：

```text
Native Surface
├── Read
└── Input
```

Read：

> 用户观察已经存在的 Conversation 数据。

Input：

> 用户向 Conversation 提供新的信息。

但 `ReadSurface`、`InputSurface` 暂时不需要成为正式协议。

实际产品仍然可以只存在：

```text
Status
Collection
Form
```

这些具体、受控、由 Tavern Player 设计的 Native View。

---

## 6. Versioned Conversation State 是核心领域能力

去掉旧 Web UI 后，大量复杂玩法最终可能只是：

```text
读取属性
更新属性
增加集合成员
删除集合成员
收集用户输入
展示结构化信息
```

这些操作本身并不复杂。

真正需要正确处理的是：

> **Conversation State 具有时间维度。**

例如：

```text
Turn 10
affection = 20

Turn 11 / candidate A
affection = 30

用户切换到 candidate B
```

此时状态必须恢复到 Turn 10 对应 checkpoint，再应用 candidate B 的状态变化。

因此需要正确支持：

- swipe；
- regenerate；
- rollback；
- history truncation；
- persistence；
- process restore；
- conversation snapshot。

这不是前端 Reactive Store，而是：

> **Versioned Conversation State。**

State 与消息时间线必须保持一致。

---

## 7. Legacy State Adapter

旧生态中的变量和状态协议不应该进入 Tavern Player 的核心 Runtime。

需要一层明确的反腐层：

> **Legacy State Adapter / Legacy State Decoder**

其职责只有：

> 将已知旧生态协议解析为 Tavern Player 可以理解的状态变化。

例如：

```text
<UpdateVariable>
旧生态状态更新表达
</UpdateVariable>
```

经过：

```text
UpdateVariableAdapter
```

得到：

```text
好感度 = 10
```

随后 Adapter 的职责结束。

可以存在多个专门 Adapter：

```text
InitVarAdapter
UpdateVariableV1Adapter
JsonStateBlockAdapter
...
```

没有必要为了统一所有旧协议，再建立一门万能解析语言。

旧生态的复杂度应该被限制在边界：

```text
ST / Tavern Helper / legacy protocol
                 ↓
         Legacy State Adapter
                 ↓
      Conversation State
```

---

## 8. State Adapter 不负责数据绑定

Legacy State Adapter 不负责：

- UI Binding；
- computed value；
- expression；
- condition；
- rendering；
- arbitrary script execution。

Conversation State 更新后，Compose 如何重新渲染属于 Tavern Player 的普通应用内部实现。

可以内部使用：

- StateFlow；
- ViewModel；
- Compose recomposition。

这些能力不属于 Adaptation Contract。

Adaptation 最多声明简单映射：

```text
好感度 ← player.affection
金币   ← player.money
地点   ← world.location
```

State path 第一阶段保持简单、静态、确定。

避免逐渐支持：

```text
JSONPath
selector
filter
map
computed expression
conditional expression
```

否则状态映射本身也会重新演化成编程语言。

---

## 9. State 对模型也必须成立

State 不只是 UI 数据。

同一份 Conversation State 至少存在两个重要 Projection：

```text
Conversation State
       │
       ├──► Native View
       │      给用户看
       │
       └──► Prompt Projection
              给模型看
```

如果 Tavern Player 成功显示：

```text
好感度: 80
```

但模型下一轮仍然不知道好感度已经是 80，则只能认为 UI 恢复成功，玩法语义没有真正恢复。

因此必须独立设计：

> **State → Conversation Semantics。**

State 如何影响 PromptCompiler，是适配正确性的核心组成部分。

该 Projection 由 Tavern Player 定义。Adaptation 可以提供字段名称、类型、值和必要的语义描述，但不应自由指定：

- Prompt 模板；
- role 与插入位置；
- 条件表达式；
- 动态计算；
- 任意 Prompt 注入。

否则 State Projection 会成为另一种 Prompt 编程语言。

---

## 10. Assets 是一级内容概念

CG、立绘、背景、物品图等不属于旧 Web Runtime。

它们首先是：

> **内容资产。**

因此 Tavern Player 拒绝运行 JavaScript，并不意味着需要拒绝原卡携带或引用的视觉资源。

整体内容模型可以理解为：

```text
Conversation
├── Narrative
├── Versioned State
├── Assets
└── Native Views
```

Assets 可以包括：

- Character portrait；
- Expression sprite；
- Scene image；
- CG；
- Item image；
- Background；
- 其他受支持的静态媒体。

例如：

```text
location = beach
      │
      ▼
scene asset = beach.webp
```

或者：

```text
inventory item
├── name: 铁剑
└── image: sword.webp
```

Native View 可以消费：

```text
State + Assets
```

但 Assets 本身不属于 UI Component System。

---

## 11. 远程资源与代码执行是不同边界

允许加载受控远程图片，与允许执行角色卡中的 JavaScript 是两件完全不同的事情。

Tavern Player 可以考虑支持：

```text
embedded/local asset     默认允许
HTTPS remote image       受控允许
HTTP remote image        默认拒绝
script / executable      拒绝
```

远程资源不属于第一阶段 Native Adaptation 的必备能力。第一阶段可以只保存和使用原卡内嵌或本地可验证的静态资源；远程加载应作为独立产品与安全决策另行启用。

远程资源加载应遵循独立安全策略，包括：

- 仅接受支持的媒体格式；
- 使用 Native Decoder；
- 不使用 WebView 执行内容；
- 限制下载大小；
- 限制解码尺寸；
- 限制重定向；
- 不携带用户凭据；
- 不继承 Tavern Player Cookie；
- 本地缓存；
- 必要时提示第三方网络请求。

其目的只是：

> 保存原作者的视觉内容。

而不是恢复旧网页运行环境。

---

## 12. 内容回到所属模型

Adaptation 不需要定义一种通用降级格式。复杂旧 UI 中能够恢复的内容，应按语义回到 Tavern Player 已有或明确建立的内容模型：

```text
剧情正文                 → Conversation Text
人物、地点与规则说明     → title / description / content
状态与同类结构化对象     → Conversation State + Native View
无法恢复的交互与副作用   → Unsupported Behavior
```

不能恢复的行为应明确报告，而不是转换成另一种表现格式后视为已经兼容。Native View 表达不了某项行为，也不意味着应该增加新的 UI Runtime 能力。

---

## 13. 数据规模不等于玩法复杂度

判断超级角色卡的兼容难度时，不应该使用：

- HTML 行数；
- CSS 行数；
- JavaScript 行数；
- UI 页面数量；
- 状态字段数量；

作为主要复杂度指标。

例如一个大型地图系统在 Web 中可能包含：

```text
SVG
CSS
hover
zoom
drag
tooltip
marker
animation
```

但其真正的剧情状态可能只是：

```text
currentLocation = "B"
visited = [...]
unlocked = [...]
```

背包、任务、技能、人物关系也可能类似：

```text
Inventory  = Collection<Record>
Quest      = Collection<Record>
Relation   = Collection<Record>
Map        = Records + currentLocation
CG         = State + Assets
```

即使存在数百个字段，从数据管理角度仍然不是困难问题。

因此需要明确：

> **大量数据是可以接受的，大量可编程行为不是。**

---

## 14. 真正需要关注的是行为复杂度

真正提高 Runtime 复杂度的是状态变化所携带的副作用。

例如：

```text
A 改变
↓
计算 B
↓
达到条件自动触发 C
↓
修改 World Book
↓
触发额外生成
↓
启动定时事件
```

这类能力与单纯存在 100 个、500 个状态字段完全不同。

因此评估角色卡时，更有价值的问题不是：

> 这张卡有多少代码？

而是：

> **它真正存在多少种状态变换，以及这些状态变换产生多少外部副作用？**

这应该成为未来 Compatibility Analysis 的重要指标。

---

## 15. Adaptation Schema 红线

设计任何 Adaptation 字段时，都应该首先判断：

> 这个字段描述的是内容语义，还是描述 UI 应该如何运行？

允许：

```text
title
label
description
statePath
valueType
quantity
min/max
asset reference
```

谨慎：

```text
presentation variant
```

原则上拒绝：

```text
layout
width
columns
padding

visibility expression
condition
loop
computed

onClick
onChange
event
watch
action

component tree
function
script
```

后一类概念开始进入 Schema 时，意味着 Tavern Player 正重新滑向前端框架或低代码平台。

---

## 16. AI 的职责

AI 可以参与复杂旧内容的理解，但不能获得 Runtime 设计权。

AI 适合：

```text
理解旧 HTML / JS / 插件协议
↓
识别真正影响玩法的数据和交互
↓
识别已有 Legacy Adapter
↓
提取 State / Assets / Input
↓
填写 Tavern Player 已定义的 Adaptation 数据
```

AI 不负责：

```text
设计 UI Tree
创造新组件
编排 Action Graph
定义事件
创造表达式
新增 Runtime capability
```

基本原则：

> **AI 理解，机器裁决。**

AI 输出必须落入 Tavern Player 已经定义和验证的有限能力空间。

AI 在适配与游玩中的参与方式不由本文额外限制。部分角色卡本来就要求模型在回复中产生结构化状态文本，再由 Player 解码并渲染；这属于角色玩法语义，而不是需要排除的运行依赖。

早期适配研究不优先建设自动编译器。可以直接让成熟 Coding Agent 同时阅读原始角色卡与 Tavern Player 仓库，作为兼容工程师协助：

- 理解真实玩法；
- 修改或验证 Player 的 Native 能力；
- 编写专用 Legacy Adapter；
- 生成临时 fixture；
- 运行测试并报告无法恢复的行为。

这一阶段的适配结果可以随核心设计一起重做，不需要成为长期持久化资产，也不需要提前建立缓存、分发、迁移和跨仓库协议。

当 Native View、Versioned Conversation State、Legacy Adapter 与验证边界足够稳定后，自动编译能力直接进入 Android 导入流程。其预期形态只是本地预处理、一次受控模型请求、本地确定性校验和本地保存；开发 Harness 可以继续辅助调试，但不成为产品部署前提。

验收应关注原内容与 Native 结果的可观察玩法，而不只检查产物格式合法或能够执行。

---

## 17. Shelf 的边界

Shelf 第一阶段保持为内容基础设施：

```text
保存
浏览
传输
```

不负责定义：

- Player Runtime；
- Adaptation Schema；
- State Adapter；
- AI Compiler；
- repair；
- Player capability；
- 派生产物语义。

Runtime 与 Adaptation Contract 的演化权始终属于 Tavern Player。

Shelf 第一阶段只传输原始内容，不携带或生成 Native Adaptation。稳定前的派生产物缺少长期身份与兼容承诺，为它建立存储、传输和版本协商属于不必要成本。

只有未来出现明确的跨设备复用或分享需求时，才重新评估 Shelf 是否缓存并传输经过 Player 校验的派生产物；该能力不是当前架构前提。

---

## 18. 推荐整体架构

```text
                    原始 ST 内容
                         │
          ┌──────────────┴──────────────┐
          │                             │
          ▼                             ▼
 Narrative / Prompt / WB          Legacy Runtime Data
          │                             │
          │                     Legacy State Adapter
          │                             │
          │                             ▼
          │                  Versioned Conversation State
          │                             │
          │                 ┌───────────┴───────────┐
          │                 │                       │
          │                 ▼                       ▼
          │          Prompt Projection        Native Views
          │                                   Status
          │                                   Collection
          │
          └──────────────────────┐
                                 ▼
                          Conversation Runtime


原卡 Assets
     │
     ▼
Asset Model / Cache
     │
     ├────────► Message / Scene
     └────────► Native Views


用户
 │
 ▼
Form View
 │
 ▼
Draft
 │
 ▼
Normal Conversation Turn
```

架构中原则上不存在：

```text
Universal Component Tree
Reactive Binding Engine
Expression Language
Action Graph
Script Runtime
General Event System
```

---

## 19. 当前设计重心

下一阶段不应继续优先扩充 UI Component。

更值得明确的是：

### A. Versioned Conversation State

明确：

- 最小数据类型；
- Record / Collection 的边界；
- checkpoint；
- swipe；
- regenerate；
- rollback；
- persistence；
- conversation snapshot。

### B. Legacy State Adapter

明确：

- Adapter 如何识别旧协议；
- Adapter 的输入输出；
- 允许写入哪些状态；
- 如何验证状态变化；
- Adapter 责任在哪里终止。

### C. State → Conversation Semantics

明确：

- 哪些状态需要进入 Prompt；
- 如何进行稳定 Projection；
- 与历史、swipe、regenerate 的关系；
- 如何避免状态与模型上下文失步。

### D. Asset Model

明确：

- Asset identity；
- embedded 与 remote resource；
- 缓存；
- Conversation snapshot 与资产稳定性；
- Native View 如何引用资产；
- 安全策略。

---

## 20. 核心原则

当前设计可以最终压缩为以下原则：

> **Tavern Player 不迁移旧网页程序，只恢复其中真正影响角色扮演体验的数据、输入、资产和剧情语义。**

> **Native View 的存在是为了让 Player 接管 UI，而不是让 Adaptation 获得自由设计 UI 的能力。**

> **Legacy State Adapter 只负责解释旧生态状态协议，不负责数据绑定。**

> **Conversation State 是带时间线的领域状态，而不是前端 Reactive Store。**

> **Assets 是内容，不是 Runtime。拒绝 JavaScript 不意味着拒绝 CG、立绘和其他视觉资源。**

> **大量数据可以接受，大量可编程行为必须谨慎。**

> **兼容难度应该由状态变换与副作用衡量，而不是由旧前端代码量衡量。**

> **叙事回到 Conversation，描述进入内容字段，结构化数据进入 State 与 Native View；无法恢复的行为明确报告，而不是改写成新的 UI 语言。**

> **如果 Adaptation Contract 开始出现 expression、event、action、condition 和 component tree，说明设计已经重新滑向低代码平台。**

最终 Tavern Player 希望成为的不是：

> 一个能够运行各种简化 Web 程序的新平台。

而是：

> **一个能够从 SillyTavern 内容中恢复角色扮演真正需要的剧情、状态、输入与视觉资产，并使用自己的原生体验重新呈现这些内容的角色对话播放器。**
