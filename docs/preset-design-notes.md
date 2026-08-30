# Preset 设计讨论笔记

> 状态：讨论记录，尚未形成实现规范。
>
> 当前范围仅限 Preset；角色卡与 Preset 如何共同参与最终上下文，留到角色卡研究阶段再讨论。

## 样本使用方式

`source/梦鲸思客V4-0818-和谐版.json` 不是一个适合从零推导最小内核的样本：

- 规模很大；
- 大量 Prompt 默认关闭；
- 携带 29 条 Regex；
- 携带五段依赖第三方 Tavern Helper / JS-Slash-Runner 的 JavaScript；
- 混合了 Preset 内容、输出格式约定、用户设置界面和第三方工作流。

它可以留作以后验证兼容边界的压力样本，但不继续用它定义 Tavern Player 的最小内核。

## 当前共识

### 不为区别 ST 而改造 Preset

在 Preset 这一层，社区已经形成的核心语义相对清楚：

```text
Preset
├─ 模型请求与生成设置
├─ Prompt 定义
├─ Prompt 顺序与启用状态
└─ Prompt 文本中使用的宏
```

Tavern Player 暂时没有必要为了“不同于 ST”而发明新的 Preset 概念。产品和内核真正可能出现差异的地方，更可能是角色卡、世界书、聊天状态与 Preset 如何共同形成一轮上下文。

### `buildRequest` 只是实现边界

此前讨论的：

```text
buildRequest(...) -> messages + model parameters
processResponse(...) -> displayText + historyText
```

不是新的社区格式或复杂编译器方案，只是描述一次生成最基本的两段行为：构造请求和处理回复。是否需要单独的响应投影能力，要在调查更普通的 Preset 后再决定。

### 第三方 JavaScript 不属于当前内核范围

`extensions.tavern_helper` 依赖社区第三方插件。当前只确认其来源并保留样本，不设计 JavaScript 运行适配、插件事件系统或对应 UI。

### 宏、Regex 和变量先保持来源边界

- 基础宏是 ST 核心文本能力，不是 Preset 中独立的数据分区。
- 聊天变量和全局变量是带作用域的键值状态；社区内容可以赋予其“记忆”语义。
- `extensions.regex_scripts` 来自 ST 自带 Regex 扩展，不是任意 JavaScript。
- 是否把 Preset Regex 纳入 Tavern Player 的首批 Preset 能力，尚未决定。

## 当前最小理解

不考虑第三方插件时，一个 ST OpenAI Preset 至少表达：

```text
GenerationSettings
PromptDefinitions
PromptOrder
```

Prompt 定义和使用顺序是否在 Tavern Player 内部继续分开保存，暂不决定。ST 样本表明“已定义但未进入顺序”的 Prompt 确实可能存在，因此导入阶段不能未经判断就丢弃。

## 第一个设计分歧：内容所有权

ST 的 Prompt Manager 是最终的统一编排区。Preset 的 order 中包含 `charDescription`、`scenario`、`worldInfoBefore`、`worldInfoAfter`、`chatHistory` 等 marker，因此 Preset 可以调整来自角色卡和世界书的内容在总 Prompt 中的位置与启用状态。

Tavern Player 当前讨论的方向不同：

```text
Preset 只拥有自己的模型请求设置和请求 Prompt
Character 拥有自己的角色内容及其内部编排
Chat 拥有聊天历史
```

角色卡内容最终仍会进入同一次模型请求，但“进入请求”不等于“由 Preset 控制”。Preset 不应天然获得逐项开关、重排或覆盖角色卡内容的权力。

最终请求仍然需要一个合并规则，但这个规则应属于会话内核，而不是把角色内容变成 Preset 的子项。具体合并位置和冲突规则尚未决定。

## 真实角色卡证据

调查对象：`D:\AICenter\51359e29e5cd773c.png`。PNG 同时包含 `chara` 和 `ccv3` 文本块，内容相同，是 `chara_card_v3` / `3.0` 角色卡。

其主要字段规模为：

```text
description                  0
personality                  0
scenario                     0
mes_example                  0
system_prompt                0
post_history_instructions    0
first_mes                 1078 字符
character_book              16 项 / 合计 57,807 字符
```

16 个 character book 条目中：

- 14 项为 `constant: true`，只有 2 项依赖关键词选择；
- 12 项启用，4 项关闭；
- 12 项通过 ST extension position `atDepth` 插入在深度 4；
- 其余 4 项位于角色定义前后；
- 所有条目都有顺序、开关和扩展注入设置。

这说明在当前社区实践中，character book 经常不只是“按关键词召回的背景知识”。它也充当角色卡真正的模块化正文容器，承载常驻角色设定、可选模块和按聊天深度注入的内容。

目前只记录由此暴露的边界，不在 Preset 阶段决定角色卡内部模型：

- ST 的固定角色字段表达力和注入位置有限；
- 世界书条目提供模块、启用、条件、顺序和深度，因此社区把大量角色内容迁入其中；
- Tavern Player 若坚持角色卡拥有自身内容，就不能把这些条目简单视为由 Preset 控制的 `worldInfoBefore/After` 文本。

## 尚未讨论清楚的问题

- 找到一个更普通、较少依赖扩展的社区 Preset，核对最常用字段集合。
- 顶层字段中哪些直接影响模型请求，哪些只是 ST 界面或兼容行为。
- Prompt 的 marker、注入深度与角色覆盖，在普通 Preset 中是否常见。
- Preset 是否需要承担输出 Regex，还是 Regex 属于独立的可选内容能力。
- 用户开关 Prompt 时，最小需要保存的是单个 `enabled`，还是还需要分组和选项语义。
- 基础宏需要支持到什么范围，特别是带副作用的变量宏。
- Preset、角色卡和聊天历史采用什么固定合并边界，同时不让 Preset 接管角色卡内部模块。

以上问题只用于后续调查，不预设解决方案。

## 候选：最小请求构造（原 Preset-only 讨论）

> 状态：该标题所表达的“Preset-only”方向已被后续讨论修正。保留本节用于记录最小请求构造思路；产品模型从一开始就允许 Character 存在，只是其贡献可以为空。

先令角色卡上下文为空，只验证 Preset 能否独立驱动聊天：

```text
Preset + ChatSession
  -> ModelRequest
```

运行时只需要三类输入：

```text
RuntimePreset {
  generationSettings
  promptSequence
  assistantPrefill?
}

PromptSequenceItem =
  | PromptMessage { id, role, template, enabled }
  | ChatHistorySlot

ChatSession {
  messages
  variables
}
```

最小构造过程：

1. 按顺序遍历 `promptSequence`；
2. 跳过关闭的项，启用的普通 Prompt 展开基础宏后成为一条消息；
3. 遇到 `ChatHistorySlot` 时插入当前聊天消息；
4. 如有 assistant prefill，则追加在请求末尾；
5. 将 `generationSettings` 与消息列表交给模型网关。

角色卡为空时，不需要 `EmptyCharacter` 对象，只令未来的 character contributions 为 `[]`。首个版本的模型回复可以原样保存和显示；Regex 输出投影可在基本请求链路跑通后单独讨论。

导入 ST Preset 时，可将出现在 `prompt_order` 中的普通 Prompt 连同其启用状态转为有序 `PromptMessage`，将 `chatHistory` marker 转为 `ChatHistorySlot`。未进入 order 的定义和暂不支持的字段仍保留在原始导入数据中，不需要进入最小运行模型。

## 产品定位：内容使用器，不是 Preset 创作工作台

Tavern Player 不把“在 Preset 区从空白开始手搓 Prompt”作为推荐或主要产品流程。不可避免的增删改查主要服务于内容资产管理：

- 导入、查看、选择、启用和删除；
- 重命名、复制、调整模型参数；
- 切换内容作者已经提供的 Prompt 选项；
- 在必要时修复或修改已有内容。

这里的 `Create` 可以主要表现为导入或复制已有内容，而不是突出“新建空白 Preset”。`Update` 也不必等于提供一个类似 ST Prompt Manager 的全功能创作台。具体可编辑范围尚未决定。

## 候选：按所有权划分请求区域

角色卡应从产品模型开始就存在；没有选角色卡时，它只是贡献空内容，而不是进入一种单独的 Preset-only 模式。

“Character 区域”首先表示所有权，不要求最终请求中只有一段连续文本：

```text
Preset-owned
  请求前置指令
  请求末尾指令
  assistant prefill

Character-owned
  角色定义与常驻模块
  条件模块
  按聊天深度插入的模块

Chat-owned
  用户与 assistant 历史消息
```

会话内核只负责按一份固定契约合并这些贡献。Preset 可以在自己的区域内排序和开关自己的 Prompt，但不逐项查看、关闭或重排 Character-owned 内容；Character 同样不能修改 Preset-owned Prompt。

一个尚未确认的最小区域顺序是：

```text
Preset prelude
Character context
Chat history（包含 Character 自己的深度注入）
Preset turn directive
Assistant prefill
```

需要继续讨论的是：区域的相对位置完全由内核固定，还是允许 Preset 在有限锚点间放置“完整的 Character 区域”。无论选择哪种，Preset 都不获得 Character 区域内部的控制权。

## 候选：由固定消费者处理 Preset

“消费者领取字段”只作为内核内部的固定分工，不设计动态插件注册、事件监听或第三方能力认领：

```text
ST Preset JSON
├─ 顶层生成字段
│  └─ GenerationSettingsImporter
├─ prompts + prompt_order
│  └─ PresetPromptImporter
├─ extensions.regex_scripts
│  └─ RegexImporter（可选能力）
└─ extensions.tavern_helper
   └─ UnsupportedAttachment（原样保留，不执行）
```

导入后形成的候选内部对象：

```text
PresetAsset {
  source
  generationSettings
  promptModules
  regexRules?
  unsupportedAttachments
}

PresetPromptModule {
  id
  name
  role
  template
  enabled
  placement
  depth?
  order
}
```

运行时不理解 Prompt 写了什么，只执行它声明的机械语义：

- 是否启用；
- role；
- 在 Preset 自己区域内的相对顺序；
- 注入锚点或聊天深度；
- 基础宏展开。

这份复杂样本中还包含三类不应混为 Preset 核心的能力：

- 28 条针对 AI 输出的 Regex，属于可选输出转换；
- 多套 DeepSeek、Gemini、Kimi 等 Prompt 变体，ST 原生只把它们表现为普通 Prompt 及 enabled，模型分组和切换界面来自 Tavern Helper；
- Tavern Helper JavaScript 提供 UI、请求修改、回复修复和二次生成，当前均不进入内核。

因此首个实现范围可以按顺序收缩为：

1. 导入顶层生成设置和 `prompts` / `prompt_order`；
2. 根据 enabled、role、order 和 placement 生成 Preset-owned contributions；
3. 与 Character-owned contributions 和 Chat history 按固定区域契约合并；
4. 展开必要的 ST 基础宏并构造模型请求；
5. 首先原样保存和显示模型回复；确认普通社区内容确实依赖后，再增加受限 Regex 转换；
6. 第三方 JavaScript 始终只保留和报告，不运行。
