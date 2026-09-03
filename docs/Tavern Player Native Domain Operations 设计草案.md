# Tavern Player Native Domain Operations 设计草案

> 状态：产品与架构草案，尚未形成实现契约。
>
> 本文补充 [`Tavern Player Native Adaptation 设计原则0903.md`](Tavern%20Player%20Native%20Adaptation%20设计原则0903.md) 中尚未展开的行为迁移边界。文中的概念名称不构成对现有 `AdaptationArtifact v1` 的兼容承诺。

## 1. 文档目标

Tavern Player 不以兼容旧 JavaScript Runtime 为目标。但是，旧 SillyTavern 内容中的 JavaScript、Tavern Helper 调用、扩展 API 和事件逻辑，并不全部只是前端表现。

其中一部分代码可能表达明确的角色扮演领域行为，例如：

- 改变某个 World Book 或 Entry 在当前 Conversation 中的启用状态；
- 在一次明确的 Setup 提交中初始化 Conversation State；
- 把用户的结构化输入投影为聊天 Draft。

这些行为如果被当作普通 UI 一并丢弃，角色卡可能仍能显示和聊天，但玩法语义已经改变。因此需要建立以下边界：

> **Tavern Player 不兼容旧 JavaScript API；它只识别其中能够落入 Player 既有产品生命周期的有限领域语义，并将其转译为 Player 自己拥有的状态变化、生命周期意图或 UI Effect。**

整体过程是：

```text
Legacy JS / Plugin API
        ↓
Semantic Translation
        ↓
Player-owned Intent
        ↓
Deterministic Validation
        ↓
Owning Lifecycle / Domain Runtime
```

它不是 JavaScript Runtime，也不是通用命令总线。

## 2. 旧 JavaScript 不是一个整体能力

分析旧内容时，不使用：

```text
有 JS
→ 支持 / 不支持
```

而是按代码实际表达的语义分诊。

```text
Legacy Behavior
├── Presentation
├── Conversation State Mutation
├── UI Effect
├── Lifecycle Intent
├── Domain Runtime Mutation
└── Arbitrary Program
```

### 2.1 Presentation

只改变页面表现，例如 DOM 显隐、Tab、class、CSS 动画、展开折叠、前端局部翻页和图片切换。

处理方式：

```text
Presentation
→ Native View
→ Assets
→ Markdown
→ 或明确降级
```

它不进入 Conversation Runtime。

### 2.2 Conversation State Mutation

描述角色世界发生了什么，例如：

```text
affection = 80
money += 10
inventory += sword
location = tavern
```

处理方式：

```text
Legacy State Protocol
        ↓
Legacy State Adapter
        ↓
Versioned Conversation State
```

它描述世界状态，不是 Native Domain Operation。

### 2.3 UI Effect

只改变当前本地界面的暂态结果，例如：

```text
SetDraft(text)
```

Draft 不改变剧情事实、PromptCompiler 或历史消息，因此不进入 Conversation Runtime，也不跟随消息 checkpoint。它只能由拥有该能力的固定 Native View 生命周期产生。

### 2.4 Lifecycle Intent

只在一个明确产品生命周期内有意义，例如：

```text
SetupCommitted
→ 初始化允许的状态
→ 应用允许的 Conversation 覆盖
→ 产生 Draft
```

Lifecycle Intent 不能被抽成任意时刻均可调用的通用 Operation。它由所属产品流程定义输入、验证、原子性和至多一次语义。

### 2.5 Domain Runtime Mutation

不是单纯修改剧情数据，而是要求 Tavern Player 改变后续运行行为，例如：

```text
SetConversationWorldBookEnabled
SetConversationWorldBookEntryEnabled
```

这类行为必须产生可持久化、可回溯的 Conversation Runtime 结果，并由使用该结果的领域组件解释。

### 2.6 Arbitrary Program

包括任意 JavaScript、`eval`、timer、后台循环、任意 callback、`fetch`、任意网络访问、DOM Runtime、动态代码加载、通用事件监听和插件生命周期。

处理结果是：

```text
Unsupported
```

不能为了恢复这些行为继续扩张 Native Operation Contract。

## 3. 四类 Player 输出的边界

| 类型 | 描述 | 是否持久化 | 是否跟随 checkpoint | 当前例子 |
| --- | --- | --- | --- | --- |
| Conversation State | 世界现在是什么样 | 是 | 是 | 好感度、地点 |
| UI Effect | 当前界面暂时做什么 | 否 | 否 | 设置 Draft |
| Lifecycle Intent | 一个固定产品流程提交什么 | 取决于提交结果 | 取决于提交结果 | Setup Commit |
| Domain Runtime Mutation | Player 后续如何运行 | 结果持久化 | 是 | World Book 启停覆盖 |

判断顺序是：

1. 能否纯粹表达为 Conversation State；
2. 是否只是本地 UI Effect；
3. 是否只属于某个已有产品生命周期；
4. 只有必须改变 Player 后续领域行为时，才建立 Domain Runtime Mutation；
5. 其余复杂程序行为保持 Unsupported。

例如，仅保存：

```text
worldBookEnabled = false
```

却不改变 World Book Engine 的候选范围，并没有恢复原行为。真正的启停必须成为 Conversation Runtime 中由 World Book Engine 读取的覆盖状态。

## 4. Native Domain Operations 不是 Action Graph

禁止建立：

```text
Action {
    type
    args
    trigger
    condition
    next
    onSuccess
    onFailure
}
```

也不提供：

- 通用 `Operation[]`；
- 可由 Adaptation 选择的通用 Trigger；
- 条件、循环、分支和动作链接；
- 动态参数表达式；
- 跨领域命令编排。

即使 Operation 和 Trigger 各自都是有限枚举，只要 Artifact 可以自由组合：

```text
Trigger × Operation[]
```

它们仍然会形成简化的动作系统。

正确模型是：

> **每项能力由 Tavern Player 定义，并固定属于一个明确的领域或产品生命周期。Adaptation 只能填写该契约允许的数据，不能决定它在其他时机执行。**

因此第一阶段不需要公共 `DomainOperation` 基类、通用 dispatcher 或通用 trigger 字段。不同领域可以使用不同的强类型 Intent 和执行入口。

## 5. 能力必须由 Player 拥有

每项能力必须在 Tavern Player 源码中明确实现，并固定以下内容：

- 参数结构与参数来源；
- 可以引用的对象；
- Conversation、Character 或 UI 作用域；
- 唯一允许的调用入口；
- 验证与原子提交规则；
- 时间线和持久化语义；
- 用户可见性与诊断；
- 失败后的行为。

Artifact 或 AI 不能修改这些规则、创造新能力或把能力搬到其他生命周期。

## 6. World Book Activation Override

World Book 启停是第一类值得建立强类型 Runtime Mutation 的候选能力。

### 6.1 当前模型事实

当前 Tavern Player 中：

- `CharacterSnapshot.worldBooks` 保存 Conversation 使用的 World Book 定义；
- `WorldBookDefinition` 有稳定 `id`，但没有书本级 `enabled` 字段；进入 Snapshot 的书本默认参与编排；
- `WorldBookEntryDefinition.enabled` 是 Snapshot 中的定义值；
- `ConversationRuntimeState.worldBookEntries` 只保存 sticky、cooldown、delay 等计时状态；
- `WorldBookEngine` 当前直接读取 Entry 定义上的 `enabled`。

因此不能通过修改 Character Asset 或原地修改 Snapshot 定义实现 Conversation 级启停。

### 6.2 Conversation 级覆盖

需要新增概念上的 Conversation Runtime 数据：

```text
WorldBookActivationOverrides
├── books:   bookId → Boolean
└── entries: bookId → entryId → Boolean
```

Map 中不存在键表示继承 Snapshot 默认值：

```text
effectiveBookEnabled = bookOverride ?: true

effectiveEntryEnabled =
    effectiveBookEnabled
    && (entryOverride ?: entryDefinition.enabled)
```

覆盖只影响当前 Conversation，不修改 Character Asset、Character Snapshot 或其他 Conversation。

第一版固定以下计时语义：启停覆盖只改变 Entry 是否有资格参与本轮激活；sticky、cooldown、delay 等计时仍随现有 Conversation turn clock 推进，不因为临时停用而冻结。

### 6.3 强类型 Intent

概念意图可以是：

```text
SetConversationWorldBookEnabled(
    bookId,
    enabled
)

SetConversationWorldBookEntryEnabled(
    bookId,
    entryId,
    enabled
)
```

执行前必须确认：

- `bookId` 存在于当前 Conversation 的 Character Snapshot；
- `entryId` 属于指定 World Book；
- 参数类型合法；
- 调用来自该能力允许的固定生命周期；
- 一批提交中的所有 Intent 均有效。

验证通过后，Runtime 原子地产生新的 `WorldBookActivationOverrides`；验证失败时不提交部分结果。下一次 Prompt 编译由 World Book Engine 读取有效启用值。

Prompt trace 和兼容性诊断应显示某个书本或 Entry 因 Conversation override 被启用或停用，避免产生不可观察的隐藏 Prompt 变化。

## 7. Chat Draft 是 UI Effect

```text
SetDraft(text)
```

只设置当前聊天输入框内容。它不：

- 自动发送；
- 修改历史消息；
- 调用模型；
- 产生 token 消费；
- 改变 Conversation Runtime；
- 跟随消息 checkpoint 恢复。

因此 `SetDraft` 不进入 Domain Operation Catalog。它是 Form View 在提交成功后可以产生的固定 UI Effect。

`AppendDraft`、`ClearDraft` 暂无独立产品需求，第一阶段不加入。

## 8. Greeting Selection 不是通用 Operation

当前 Tavern Player 已把 Greeting 和 Alternate Greeting 表达为 opening message candidates，并使用现有候选选择语义处理 swipe。

如果未来需要在 Conversation 创建前根据 Setup 选择 Greeting，它首先属于 Conversation Creation Input；Conversation 创建后选择已有 opening candidate，则继续使用消息候选选择，不需要新增：

```text
SelectGreeting(greetingId)
```

只有现有创建输入和候选选择都无法表达产品决定的语义时，才为 Greeting 另行设计生命周期契约。第一阶段不把它加入 Native Domain Operations。

## 9. Setup Commit 是固定生命周期

当前普通 Form View 的默认路径保持：

```text
用户输入
→ 生成 Draft
→ 用户确认
→ 正常 Conversation Turn
```

它不修改 Conversation State 或 Runtime。

如果未来明确建立一次性 Setup Form，应为它设计专用的 `SetupCommitPlan`，而不是复用 `Operation[]`：

```text
SetupCommitPlan
├── permittedStateInitializations
├── permittedWorldBookOverrides
└── draftProjection
```

Setup 提交必须：

1. 验证所有字段和目标引用；
2. 计算完整提交结果；
3. 原子写入允许的 Conversation State 与 Runtime override；
4. 记录 Setup 已提交的至多一次状态；
5. 最后产生 Draft UI Effect。

任何一步失败都不提交部分状态，也不产生 Draft。

### 9.1 参数来源

Setup Contract 的参数只允许来自：

```text
Literal
    编译期确定并通过验证的常量

DirectFormField
    指定字段到指定目标的一对一类型匹配复制

SelectedOptionPayload
    用户选择的 Option 携带的预验证常量结果

SnapshotReference
    当前 Character Snapshot 中已经存在的稳定 ID
```

不允许：

- 表达式；
- 模板计算 Runtime 参数；
- 任意状态路径读取；
- 条件映射；
- 多步动作链接；
- 根据字段内容拼接目标 ID。

`DirectFormField` 和 `SelectedOptionPayload` 是 Setup 生命周期自己的有限输入语义，不扩展为通用 Binding Engine。

## 10. 调用入口属于领域，不建立 Trigger 系统

一个能力是否安全，不仅取决于它做什么，还取决于什么时候执行。但这不意味着需要建立通用 Trigger Catalog。

第一阶段采用固定拥有关系：

```text
普通 Form 提交
→ Draft UI Effect

未来 Setup Form 提交
→ SetupCommitPlan

Assistant 消息提交
→ 指定 Legacy Adapter 的强类型 Patch

Conversation 创建
→ ConversationRepository 自己的初始化逻辑
```

其中：

- `ConversationCreated` 不是 Adaptation 可以订阅的事件；
- `ExplicitUserInteraction` 不是通用 Trigger，具体 Native View 只能产生自己契约内的结果；
- `AssistantStateUpdateIngested` 不是通用事件总线，指定 Adapter 只能返回其声明的强类型 Patch；
- 不允许把 World Book Intent 任意挂接到消息、Regex、timer 或状态变化上。

如果未来某个领域需要新的调用入口，必须作为该领域的产品生命周期单独设计，而不是向 Trigger 枚举追加一个值。

## 11. 不支持任意条件触发

第一阶段明确不支持：

```text
when state.x > 10
when inventory contains ...
when turn % 3 == 0
when message matches regex
when timer expires
```

因为：

```text
Condition
+ Trigger
+ Runtime Mutation
```

已经足以建立一个脚本系统。

如果某项行为看似需要条件触发，应依次判断：

1. 是否属于 Prompt / World Book 已有原生机制；
2. 是否能直接表达为 Conversation State；
3. 是否属于某个固定 Native View 生命周期；
4. 是否可以安全降级；
5. 最后才讨论新的受控领域能力。

## 12. Runtime 结果必须进入 Conversation 时间线

任何会改变后续 Conversation 语义的结果，都不能形成旁路全局状态。

例如：

```text
Turn 10
World Book A = enabled

Turn 11 candidate A
→ disable A

用户 swipe 到 candidate B
```

此时 World Book A 必须恢复到 Turn 10 的 override，再应用 candidate B 对应的结果。

因此 World Book override 和已提交的 Setup 初始化结果必须进入 `ConversationRuntimeState`，并正确参与：

- swipe；
- regenerate；
- rollback；
- history truncation；
- persistence；
- process restore。

Draft 等 UI Effect 不改变后续 Conversation 语义，不进入这条时间线。

## 13. Intent 与执行结果

为了分离翻译意图和 Runtime 裁决，可以在具体领域内区分请求期 Intent 与执行结果：

```text
Intent:
SetConversationWorldBookEnabled(
    bookId = "xxx",
    enabled = false
)

Result:
Applied(newOverrides)

或：
Rejected(UNKNOWN_WORLD_BOOK)
```

Intent 只在当前验证和提交事务中存在。Conversation 持久化保存应用后的 Runtime State，不保存通用命令历史，不依赖命令 replay 恢复状态，也不因此建立 Event Sourcing。

不同领域不必实现共同的序列化 `DomainOperationCommand`。只有出现明确的跨领域基础设施需求时，才讨论共享接口。

## 14. AI 与 Behavior Adapter 的职责

AI 或确定性分析器可以理解旧 JavaScript，例如识别：

```text
setWorldBookEnabled("night_mode", false)
```

并尝试填入 Player 已经定义的生命周期契约或强类型 Intent。

它们不能：

- 决定 Tavern Player 应该支持什么能力；
- 创造新 Intent；
- 选择任意执行时机；
- 编排多个 Intent 形成工作流；
- 把动态程序伪装成静态参数；
- 在没有对应能力时自动扩充 Runtime。

对于 HTTP 请求、timer、动态按钮、未知扩展 API 或无法静态收敛的控制流，结果必须是 `PARTIAL` 或 `UNSUPPORTED`。

## 15. Source Evidence 是溯源，不是授权

Behavior Adapter 输出应尽量能回溯到原内容，例如记录：

```text
evidence:
  sourceSha256: ...
  sourcePath: ...
  sourceKind: legacy_js
  observedApi: setWorldBookEnabled
  targetText: night_mode
```

但 JavaScript 中的调用可能位于死代码、字符串、别名、动态属性或复杂控制流。Validator 无法一般性证明“该调用确实会以此语义执行”。因此 Source Evidence 只用于：

- 来源追踪；
- 人工审计；
- 兼容性报告；
- 翻译置信度说明。

执行授权与安全性来自：

- 原件 hash 绑定；
- Player 固定的能力契约；
- 固定调用入口；
- 参数类型和大小限制；
- Snapshot 目标引用验证；
- Runtime 再验证；
- 原子失败语义。

复杂推断无法确定时标记 `PARTIAL` 或 `UNSUPPORTED`，不能因为 Evidence 看似合理就执行。

## 16. 能力目录的演化顺序

Native Domain Operation 不能因为某张卡使用了某个 API 就直接加入 Runtime。正确顺序是：

```text
Player 产品判断
        ↓
Experimental Contract
        ↓
闭门实现与内部验证
        ↓
真实内容证伪
        ↓
Stable / Remove / Merge / Redesign
```

真实内容用于检验产品边界，不负责通过出现频率定义能力。

评估一项能力时应回答：

1. 它表达的是角色扮演领域语义，还是旧平台实现细节？
2. 它是否必须改变 Tavern Player Runtime？
3. 能否通过 State、UI Effect 或现有生命周期表达？
4. 它的结果能否确定性验证、原子提交并跟随 Conversation 恢复？
5. 用户是否能够理解或诊断它造成的 Prompt 行为变化？
6. 加入它是否让 Tavern Player 更像通用运行平台？
7. 不支持它时，内容能否合理降级？

## 17. 第一阶段候选边界

第一阶段真正的 Conversation Runtime Mutation 只考虑：

```text
SetConversationWorldBookEnabled
SetConversationWorldBookEntryEnabled
```

与它们相邻但不属于 Operation Catalog 的能力是：

```text
SetDraft
→ Form View 的 UI Effect

InitializeConversationState
→ 未来 SetupCommitPlan 的初始化数据
```

`SelectGreeting` 第一阶段继续使用现有 Conversation 创建和 opening candidate 选择语义，不新增 Operation。

第一阶段明确不开放：

```text
SendMessage
InsertMessage
DeleteMessage
ReplaceMessage

Regenerate
SelectSwipe

TriggerGeneration
ExtraGeneration

ToolCall
Timer
NetworkRequest
ArbitraryPromptInjection
```

这些操作涉及用户历史副作用、token 消费、自动模型调用、外部权限或非显式行为。只有新的 Player 产品决定才能重新讨论。

## 18. 建议实现顺序

当本草案转为实现任务时，顺序应是：

1. 在 `ConversationRuntimeState` 中建立 World Book activation override，并确定序列化边界；
2. 让 `WorldBookEngine` 计算 Snapshot 默认值与 Conversation override 的有效启用状态；
3. 补齐 swipe、regenerate、历史截断、进程恢复和 Prompt trace 测试；
4. 设计强类型 World Book Intent 与原子执行器，但不建立通用 dispatcher；
5. 只有在正式决定一次性 Setup 产品流程后，再建立 `SetupCommitPlan`；
6. 最后才让 Behavior Adapter 或 AI 编译结果填写这些既有契约。

真实卡验证发生在闭门实现之后，用于 Stable、Remove、Merge 或 Redesign，而不是边写 Runtime 边增加 API。

## 19. 总体行为转译流程

```text
                         Legacy Content
                               │
       ┌───────────────┬───────┼──────────┬────────────────┐
       │               │       │          │                │
       ▼               ▼       ▼          ▼                ▼
 Presentation   State Mutation UI Effect Lifecycle    Host Behavior
       │               │       │        Intent             │
       ▼               ▼       │          │                ▼
 Native / MD      State Adapter│     Owning Contract  Typed Domain
 Assets                │       │          │           Runtime Intent
                       ▼       ▼          ▼                │
                 Versioned   Native UI  Atomic Commit      ▼
                    State      Effect        │       Runtime Validation
                       │                    │                │
                       └────────────┬───────┴────────────────┘
                                    ▼
                         Conversation Runtime


                    Arbitrary Program
                           │
                           ▼
                       Unsupported
```

## 20. 核心设计原则

> **JavaScript 是旧生态表达行为的一种实现载体，不是 Tavern Player 的兼容单位。**

> **Presentation 可以 Native 化或降级；数据变化进入 Versioned State；本地暂态结果成为 UI Effect；固定产品流程使用专用 Lifecycle Intent；真正改变 Player 后续行为的部分才成为有限的 Domain Runtime Mutation。**

> **State 描述世界是什么样；Domain Runtime Mutation 描述 Player 后续如何运行。**

> **能力属于具体领域和生命周期，不建立可以自由组合的 Trigger、Operation 列表或通用 dispatcher。**

> **影响后续 Conversation 语义的结果必须进入 Versioned Conversation Runtime；UI Effect 不进入剧情时间线。**

> **AI 可以识别并填写既有契约，但不能创造能力、选择任意执行时机或编排工作流。**

> **Source Evidence 用于溯源和审计，不是执行授权或语义正确性的证明。**

> **没有对应 Player 能力的旧 API 默认 Unsupported，而不是推动 Runtime 自动扩张。**

最终目标不是为每个 Tavern Helper API 建立一个 Tavern Player API，而是：

> **识别旧平台中真正属于角色扮演体验的有限行为语义，并让它们以 Player 自己拥有、可验证、可回溯的产品能力存在。**
