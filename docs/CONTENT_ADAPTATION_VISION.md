# Tavern Player 内容兼容与适配理念

> 状态：探索性设计
> 本文描述 Tavern Player 对外部角色卡内容兼容问题的长期理念，不代表具体实现已经确定，也不构成对任何第三方扩展、脚本环境或角色卡行为的完整兼容承诺。

---

## 1. 核心立场

Tavern Player 的目标不是建立一套新的角色卡生态，也不是重新定义角色卡应该如何被创作。

它面对的是一个已经存在、已经拥有大量内容和创作者的 SillyTavern 生态。

因此，Tavern Player 的职责应该是：

> **尽可能理解已有角色卡应该怎样被游玩，而不是要求角色作者按照 Tavern Player 的方式重新创作。**

换句话说：

> **Tavern Player 不定义角色卡应该怎样被创作，只负责尽可能理解现有角色卡应该怎样被游玩。**

这也是整个兼容设计中最重要的约束。

如果未来 Tavern Player 内部拥有自己的 Native 数据结构、动作模型、状态系统、中间表示或适配结果，这些都应该首先被视为内部实现细节，而不是新的公共内容标准。

我们不主动推动：

- Tavern Player 专属角色卡格式；
- 面向角色作者的 Tavern Player SDK；
- Tavern Player 创作规范；
- Tavern Player 插件生态；
- Tavern Player 公共角色卡市场；
- 一套试图描述所有角色玩法的通用引擎。

Tavern Player 首先是一个 **Player**。

---

## 2. 为什么传统“格式兼容”已经不够

早期看待 SillyTavern 内容兼容时，问题似乎主要集中在：

- 角色卡格式；
- 世界书；
- Regex；
- Preset；
- Prompt 结构；
- 聊天历史。

这些内容大部分仍然可以被理解为数据。

但现代社区角色卡已经逐渐超出纯数据范畴。

部分角色卡会依赖：

- HTML；
- CSS；
- JavaScript；
- DOM 操作；
- Slash 命令；
- 变量系统；
- 事件；
- Prompt 动态注入；
- 后台模型调用；
- 第三方扩展 API；
- 自定义交互界面。

真实社区卡中已经存在这样的情况：

角色的第一条消息本身只是 `<GAMESTRAT/>`，随后角色卡 Regex 将它替换为完整 HTML 表单。表单中的 JavaScript 收集用户输入，构造剧情内容，再直接操作 SillyTavern 的聊天输入框。

这意味着：

> **前端和脚本已经不再只是装饰，而可能直接构成角色玩法。**

与此同时，Tavern Helper 一类扩展已经形成了较完整的运行能力，包括消息前端渲染、变量、事件、聊天操作、Prompt 注入、额外生成等。

因此，“兼容角色卡”正在逐渐变成：

> **兼容一个携带数据、状态和行为的小型互动内容包。**

---

## 3. 不重新制造 SillyTavern

面对这些内容，一个最直接的解决方案是不断复制原环境。

例如：

- 在 Android 中加入 Web Runtime；
- 复刻 SillyTavern DOM；
- 重新实现 Tavern Helper；
- 模拟 Slash；
- 支持各类历史 JavaScript API；
- 兼容不同第三方扩展；
- 继续补齐插件之间的交互。

这条路线短期看起来最“兼容”。

但长期会形成一个明显的问题：

> **为了播放 SillyTavern 内容，我们重新制造了一个 SillyTavern。**

这与 Tavern Player 本身追求的轻量、Native 和明确产品边界直接冲突。

因此，一个长期原则应该是：

> **尽量兼容内容，而不是继承内容原本所在环境的全部复杂度。**

这并不意味着简单地删除复杂内容。

真正的问题应该变成：

> 这个实现背后，角色作者真正想产生什么玩法效果？

---

## 4. 从实现兼容转向语义适配

同一个玩法，可以通过许多完全不同的方式实现。

例如，“提高角色好感度”可能来自：

- JavaScript；
- jQuery；
- Vue；
- Slash；
- Tavern Helper 变量；
- 某个第三方扩展；
- 一个 DOM hack。

对于原平台而言，这些实现差异非常重要。

对于 Tavern Player 而言，它们却可能表达同一个结果：

> 修改一个角色状态。

类似地：

一段复杂的 DOM 操作可能只是：

> 设置聊天输入草稿。

一个 HTML 状态栏可能只是：

> 展示若干当前状态。

一个后台脚本可能只是：

> 在某个时机执行一次额外生成。

一个世界书修改动作可能只是：

> 改变当前可参与 Prompt 的设定。

因此，一个可能的长期方向是：

> **尽量恢复外部内容的可观察玩法语义，并将其重新表达为 Tavern Player 自己能够稳定执行的行为。**

这里强调的是 **observable behavior**：

- 用户看到了什么；
- 用户能操作什么；
- 状态发生了什么变化；
- 聊天发生了什么变化；
- Prompt 上下文发生了什么变化；
- 是否发生额外模型调用；
- 哪些事件被触发。

至于旧系统具体使用了：

- jQuery；
- iframe；
- DOM selector；
- 某个扩展内部对象；

这些都可能只是历史实现细节。

---

## 5. Native Runtime 是内部实现，不是新的内容标准

为了能够原生播放复杂内容，Tavern Player 很可能最终需要拥有自己的内部能力。

例如：

- Native UI；
- State；
- Chat actions；
- Prompt/context operations；
- Generation；
- Events；
- 必要的媒体行为。

但这些能力不应该被自然演化成一套新的“角色卡创作规范”。

这一点需要长期保持警惕。

如果 Tavern Player 开始要求：

> “角色作者应该调用我们的 `setDraft()`。”

或者：

> “以后请直接发布 Tavern Player Card。”

那么项目实际上就已经开始建立第二个角色卡生态。

这不是当前目标。

更合理的关系是：

```text
外部内容
    ↓
兼容 / 适配
    ↓
Tavern Player 内部能力
```

而不是：

```text
角色作者
    ↓
Tavern Player SDK
    ↓
新的 Tavern 生态
```

内部表示可以随时改变。

内部 API 可以重构。

内部适配结果可以重新生成。

只要最终播放行为保持稳定，外部创作者不应该承担这些兼容债务。

---

## 6. AI 带来的新可能：导入时理解内容

大型语言模型使一种过去很难作为正常软件架构考虑的方案第一次具有现实可能：

> **在角色卡进入 Tavern Player 时，由 AI 阅读其中的程序表达，理解其行为，再将这种行为适配到 Tavern Player。**

这不是简单的代码转换。

更接近于：

> **语义迁移。**

传统程序面对：

```text
HTML
+
CSS
+
JavaScript
+
DOM hack
+
Tavern Helper
+
Slash
+
Regex
```

通常只能：

- 运行它；
- 模拟它；
- 人工重新实现它。

而现代 Agent 可能直接理解：

> “这个表单最终只是收集五个字段，然后把格式化文本填进聊天输入框。”

于是 Tavern Player 不再需要继承原有实现。

它只需要恢复：

> 作者真正想实现的互动。

---

## 7. Import Boundary

这个方向最重要的架构概念，不一定是“AI Compiler”。

更重要的是：

> **复杂度应该尽量被限制在导入边界。**

整体关系可以理解为：

```text
Existing ST Content
        │
        ▼
   Import Boundary
        │
        ├─ parse
        ├─ inspect
        ├─ adapt
        ├─ repair
        ├─ reject
        └─ preserve
        │
        ▼
 Tavern Player
 Native Playback
```

外部世界可以很复杂。

角色卡可以依赖：

- 旧 API；
- 新插件；
- 奇怪 JavaScript；
- 复杂 HTML；
- 历史兼容行为。

但 Tavern Player 的运行阶段仍然应该尽可能保持：

- Native；
- 确定性；
- 可验证；
- 可维护；
- 不依赖原扩展环境。

换句话说：

> **复杂输入不应该必然导致复杂播放器。**

---

## 8. AI 只是 Import Boundary 的一种实现方式

这里也应该避免另一个误区：

> Tavern Player 的兼容策略不应该绑定到某一个 Agent 产品。

“AI 适配”是能力。

Codex、OpenClaw、未来的专用模型调用只是实现。

开发早期，完全可以直接使用真正的 Coding Agent：

```text
原始角色卡
+
Tavern Player Repo
        ↓
      Codex
        ↓
人工监督下完成 Native 移植
```

这甚至可能是最可靠的早期方案。

Codex 可以：

- 阅读完整仓库；
- 理解当前 Native 能力；
- 阅读角色卡代码；
- 修改适配结果；
- 运行测试；
- 修复问题。

对于开发阶段而言，它可以充当一个近乎完整的“兼容工程师”。

这也意味着 Tavern Player 前期没有必要为了未来设想而立即开发复杂 Compiler。

可以先专注：

> **把 Native Player 本身做好。**

真实角色卡需要什么能力，由真实迁移过程逐步暴露。

---

## 9. Codex 可以成为早期的“人工 Compiler”

这个阶段还有一个重要价值：

它可以帮助我们验证：

> Tavern Player 的内部能力是否真的可以吸收不同角色卡玩法。

如果连续迁移多张复杂角色卡后发现，它们最终不断收敛到类似的内部行为：

- UI；
- State；
- Chat；
- Prompt；
- Generation；
- Events；

那么说明 Native Runtime 的方向可能成立。

反过来，如果每一张角色卡都需要完全不同的特殊逃生口，那么说明“语义适配”可能没有我们想象中那么通用。

因此，前期使用 Codex 人工迁移真实卡，本身就是一种研究方法。

不是为了建立新标准。

而是为了观察：

> **现有生态中真正需要被播放器理解的玩法语义是什么。**

---

## 10. OpenClaw Skill 可以是一种旁路形态

如果用户拥有 OpenClaw 一类 Agent Harness，那么未来角色卡适配甚至可能只是一个 Skill。

Skill 可以告诉 Agent：

- Tavern Player 当前支持哪些行为；
- 哪些内容应该保留；
- 哪些实现应该抛弃；
- 如何报告不支持行为；
- 如何避免把原平台复杂度直接复制进来。

于是：

```text
ST Card
   ↓
OpenClaw
   ↓
Tavern Player adaptation skill
   ↓
可播放结果
```

这种形式对于高级用户可能已经足够。

但它不应该成为最终产品依赖。

Tavern Player 普通用户不应该为了导入角色卡：

- 安装 OpenClaw；
- 理解 Skill；
- 配置 Agent；
- 操作外部 Harness。

因此，OpenClaw 更适合作为：

> **实验性 Compiler Harness。**

它可以帮助我们测试和改进适配方法。

最终成熟能力仍然应该尽可能进入 Tavern Player 自身。

---

## 11. 最终目标仍然是手机 App 自己会“吃卡”

最终用户体验应该保持播放器性质。

用户执行：

> 导入角色卡。

Tavern Player 自己判断：

### 普通卡

```text
导入完成
```

### 复杂卡

```text
检测到交互式内容
正在适配……
适配完成
```

### 部分兼容卡

```text
发现无法迁移行为
其余内容可以正常游玩
```

### 有危险行为的卡

```text
检测到不允许的行为
已移除 / 已拒绝
```

用户不需要知道：

- Agent；
- Compiler；
- IR；
- Tavern Helper；
- JS runtime；
- Compatibility bridge。

这些都只是播放器自己的工作。

---

## 12. 手机内置编译不等于手机运行完整 Agent

“手机 App 拥有编译能力”并不意味着需要在 Android 内运行完整 Coding Agent。

一种自然的可能性是：

```text
角色卡
  ↓
手机本地确定性预处理
  ↓
提取 Program View
  ↓
一次模型调用
  ↓
返回适配结果
  ↓
手机本地校验
  ↓
Native Playback
```

这样 AI inference 只发生在导入阶段。

适配完成以后：

> **正常游玩不再需要迁移模型参与。**

这种 inference 更像：

- 编译成本；
- 安装成本；
- 内容转换成本；

而不是持续运行时成本。

---

## 13. Program View：让 AI 看程序，而不是看整个角色

角色卡可能非常大。

但真正与程序行为有关的部分通常只占其中一部分。

Compiler 理想上不需要知道：

- 完整世界书正文；
- 大段角色背景；
- 剧情 prose；
- 用户聊天历史；
- 用户私有状态；
- API Key。

真正有价值的可能是：

- JavaScript；
- HTML；
- 必要 CSS；
- UI 文案；
- Regex；
- Extension metadata；
- Slash；
- 变量名称和结构；
- 对世界书的引用关系；
- 对聊天、Prompt、Generation 的操作。

因此，一个重要的潜在边界是：

> **Compiler 尽量理解程序结构，而不是消费角色叙事内容。**

例如：

如果代码只需要知道：

> `worldbook_27` 存在，并且某个脚本会启用它。

那么 Agent 没有必要阅读 `worldbook_27` 的完整正文。

这不仅减少 token。

也降低：

- Prompt Injection；
- 隐私暴露；
- 不必要内容传输；
- 上下文噪声。

当然，UI 文案和模板字符串本身可能就是程序行为的一部分，因此不能机械地删除所有自然语言内容。

判断标准应该始终是：

> **理解行为是否真的需要这段内容。**

---

## 14. AI 负责理解，不负责授权

角色卡代码属于不可信输入。

Tavern Helper 当前本身也不是强安全沙箱，未知脚本可能接触聊天、模型、角色卡内容或者向外部网络发送数据。

因此：

> **AI 可以帮助发现危险，但不能成为安全边界。**

例如模型可以判断：

> “这段代码疑似尝试外传数据。”

但真正保证安全的应该是 Tavern Player 自身：

> 如果 `arbitrary external network` 不属于允许的 Native 能力，那么即使 Agent 没有识别出恶意意图，它也不能获得该能力。

因此原则应该是：

> **模型负责理解，确定性系统负责裁决。**

Agent 输出只能描述 Tavern Player 已经允许的行为。

Agent 没有资格自行创造新的权限。

---

## 15. AI 适配天然可能带来安全分析

虽然安全不能完全依赖 AI，但导入时语义分析天然提供了一次额外审计机会。

例如可以识别：

- 数据外传行为；
- 凭证访问；
- 异常网络请求；
- 对宿主环境的危险操作；
- 与正常角色玩法无关的隐藏行为；
- 混淆后的可疑逻辑。

这可能形成一种传统角色卡导入器不具备的体验：

> **导入行为本身同时也是一次安全审计。**

但安全审计应该是 AI adaptation 的副产品，而不是它存在的唯一理由。

---

## 16. “坏卡修复”也是自然延伸

语义适配还有一个特别有价值的性质：

> 它不要求原程序当前仍然能够运行。

社区角色卡长期积累后很容易出现：

- API 已废弃；
- 插件升级；
- DOM selector 失效；
- Slash 改名；
- 简单 JS bug；
- 缺失依赖；
- 拼写错误；
- 历史兼容问题。

传统兼容环境面对这些内容，只能不断重新实现旧环境。

而语义迁移可能能够判断：

> 原实现已经坏了，但作者的意图仍然明显。

例如：

原卡代码访问了一个已经失效的旧 DOM selector，但上下文清楚表明它只是希望：

> 设置聊天草稿。

那么 Tavern Player 可以直接恢复这个玩法，而不是恢复那个已经失效的 DOM。

这意味着：

> **Tavern Player 可能播放一张已经无法在原环境正常工作的旧卡。**

这种“修复”并不一定修改原文件。

它只是发生在 Tavern Player 自己的适配结果中。

---

## 17. 不追求任意 JavaScript 完全等价

这一方向不能建立在错误承诺上：

> 任意 JavaScript 都可以被 AI 完美转换。

这既不现实，也没有必要。

目标应该始终更窄：

> **识别与角色玩法相关、并且能够映射到 Tavern Player 当前能力范围内的 observable behavior。**

如果内容：

- 依赖未知扩展；
- 调用无法理解的第三方 API；
- 使用外部服务；
- 需要 Tavern Player 不愿提供的能力；
- 行为无法可靠恢复；

正确结果可以是：

- Full；
- Partial；
- Unsupported；
- Rejected。

而不是为了兼容而不断扩大 Tavern Player 本身。

---

## 18. 不为了兼容提前设计“万能 Roleplay Engine”

这里还有一个尤其需要避免的方向：

> 因为想支持各种角色卡，于是提前设计一个能够表达一切角色玩法的超级 IR。

这种设计很容易重新走向 SillyTavern 的复杂路线。

因此，Tavern Player 的 Native 能力应该更像：

> **从真实游玩需求中生长出来的内部能力集合。**

而不是：

> 一个试图提前覆盖所有 RPG、视觉小说、模拟经营、脚本游戏和插件系统的通用平台。

外部角色卡如果可以被现有能力表达，就适配。

如果不断有真实内容证明某种行为普遍、重要，而且符合 Tavern Player 产品方向，再考虑是否将它加入 Native 能力。

也就是说：

> **兼容需求可以影响 Runtime，但不能无限定义 Runtime。**

最终产品边界仍然属于 Tavern Player。

---

## 19. Library 的定位：跨设备入口，不是公共生态

未来可能存在一个 Tavern Library、桌面 Companion 或 NAS 服务。

它不应该被理解为：

- 公共角色卡市场；
- 社区中心；
- 新的发布平台；
- 创作者生态。

它首先解决的是一个非常现实的用户路径问题：

> **用户很可能在电脑上发现角色卡，但在手机上游玩。**

例如用户可能通过：

- Discord；
- 社区论坛；
- 网盘；
- GitHub；
- QQ 群；
- 浏览器下载；

在电脑上获得角色卡。

而实际消费设备是 Android Tavern Player。

因此，一个本地桌面/NAS Library 可以承担：

```text
PC / Discord / Browser
        ↓
下载角色卡
        ↓
Tavern Library
        ↓
保存 / 整理 / 可选适配
        ↓
QR / LAN / 文件
        ↓
Tavern Player
```

它解决的是：

> **内容从发现设备到播放设备的传输问题。**

---

## 20. Compiler 可以自然挂在 Library 上

因为角色卡已经经过电脑或 NAS，所以如果未来编译过程较重，它可以自然发生在这里：

```text
下载角色卡
    ↓
Library
    ↓
分析兼容性
    ↓
必要时适配
    ↓
手机获取可播放版本
```

这样 Tavern Player 手机端仍然可以保持轻量。

但这只是可选部署形态。

最终手机 App 自身仍然应该拥有直接导入和适配能力，以避免普通用户被迫拥有 PC、NAS 或外部 Agent。

Library 是：

> **方便的旁路。**

不是：

> **Tavern Player 的必备后端。**

---

## 21. Library 不发展 Public Hub

如果未来真的出现 Tavern Player 用户群体，也不意味着我们应该主动建立公共角色卡生态。

如果创作者愿意支持 Tavern Player，最简单的方式仍然应该是：

> 按照原来的 SillyTavern 生态创作。

Tavern Player负责兼容。

这样可以避免逐渐出现：

- Tavern Player 专属格式；
- Tavern Player 创作工具；
- Tavern Player Store；
- Tavern Player 版本兼容规范；
- Tavern Player 插件生态；
- Tavern Player 作者审核体系。

一旦开始承担这些东西，项目的身份就发生了改变。

因此，Library 更适合长期保持：

> **用户自己的内容库。**

---

## 22. 一个可能的演进过程

这个方向完全没有必要一次完成。

可能的实际演进非常自然。

### 阶段一：Native Player

优先做好：

- 聊天；
- 角色；
- Prompt；
- 世界书；
- Regex；
- 状态；
- 性能；
- Android Native UX。

复杂社区卡由开发阶段人工处理。

---

### 阶段二：Codex 充当兼容工程师

遇到真实复杂卡时：

> 把角色卡和 Tavern Player repo 交给 Coding Agent，让它完成一次 Native 移植。

从实际案例中观察：

- 什么能力重复出现；
- 哪些行为真正属于玩法；
- 哪些是 ST 实现债务；
- Native Player 缺什么。

---

### 阶段三：可重复的适配工作流

如果逐渐发现 Codex 每次都在执行类似任务，可以将流程抽象成：

- 固定说明；
- Runtime capability description；
- 输出约束；
- 兼容报告。

这时 OpenClaw Skill 一类形式可能成为很好的实验工具。

---

### 阶段四：专用适配能力

当模式足够稳定以后，才有理由尝试：

> 一次专用模型调用完成角色卡适配。

它不需要拥有完整 coding agent 权限。

只负责：

> 外部程序语义 → Tavern Player 可验证行为。

---

### 阶段五：手机内置

最终 Tavern Player 将适配过程隐藏在普通导入流程中：

> 用户只需要知道这张卡能不能玩。

---

## 23. 成功不意味着“所有卡都能玩”

这一点非常重要。

这个方向真正成功的标准不应该是：

> 100% 运行 SillyTavern 生态的所有 JavaScript。

那仍然是在做兼容环境。

更合理的成功标准是：

> **大量现实角色卡原本依赖复杂外部实现，但 Tavern Player 可以在不继承这些实现的情况下保留它们真正重要的玩法。**

这允许我们长期保持：

- Partial compatibility；
- Explicit unsupported behavior；
- Safe degradation；
- Clear rejection。

而不是建立一个永远填不完的兼容 API 清单。

---

## 24. Tavern Player 始终只是 Player

这个方向最值得保护的其实不是 AI。

也不是 Compiler。

而是 Tavern Player 的身份。

它不是：

> 新 SillyTavern。

不是：

> 通用角色卡 IDE。

不是：

> Agent 开发平台。

不是：

> JavaScript Runtime。

不是：

> 新创作者生态。

它应该始终尽量保持：

> **一个适合真正游玩角色内容的 Native Player。**

所有高级适配能力，都应该服务于这个目标。

而不是反过来使 Player 成为新的复杂平台。

---

## 25. 长期原则

这套思路可以最终压缩成几条原则。

### 1. 兼容内容，不继承复杂度

外部实现可以复杂，Tavern Player 不需要复制全部实现。

### 2. 理解玩法，而不是追逐 API

真正重要的是角色卡对用户产生的可观察行为。

### 3. Native 能力属于内部实现

不主动创建新的角色卡创作标准。

### 4. AI 负责理解，机器负责裁决

Agent 不成为安全边界，也不拥有自行扩展 Runtime 的权力。

### 5. 复杂度尽量停留在 Import Boundary

正常游玩保持 Native、确定性和简单。

### 6. Compiler 是实现，不是产品身份

Codex、OpenClaw、远程模型、本地模型都可以替换。

### 7. 不要求一次解决所有兼容问题

Full、Partial、Unsupported 都是合理结果。

### 8. Library 解决跨设备流转，不建立公共生态

用户可以在电脑发现内容，在手机消费内容。

### 9. 不提前设计万能引擎

Native 能力从真实角色玩法中逐渐生长。

### 10. Tavern Player 始终是 Player

兼容技术再复杂，也不应该改变这一点。

---

## 26. 最终设想

如果这个方向最终成立，用户体验可能非常简单。

用户在电脑上的 Discord 社区下载了一张复杂角色卡。

这张卡原本可能依赖：

- Tavern Helper；
- HTML；
- JavaScript；
- 某些已过时的 API。

用户将它放入自己的 Library，或者直接发送到手机。

Tavern Player 导入它。

后台完成：

- 解析；
- 理解；
- 安全检查；
- 必要的行为恢复；
- Native 适配。

然后用户看到的只是：

> **角色卡已导入。**

进入聊天后，原本通过 Web 表单实现的开场成为 Native 界面。

原本通过脚本实现的状态成为 Native 状态。

原本通过插件完成的行为在 Tavern Player 中正常发生。

而用户不需要：

- 安装插件；
- 管理脚本；
- 理解 Tavern Helper；
- 配置兼容层；
- 知道角色卡原本使用什么技术。

这可能是 Tavern Player 对“兼容”最理想的理解：

> **不是把旧世界搬进来。**

而是：

> **让旧世界里的内容在这里自然地活下来。**

这也是：

> **Built for SillyTavern content. Not SillyTavern complexity.**

在技术层面最彻底的一种表达。
