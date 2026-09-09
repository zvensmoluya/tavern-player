# 社区 Preset 导入能力调查

> 状态：样本调查记录，不是 Tavern Player 内核规范。当前有效状态见 [`product-direction.md`](../product-direction.md)。
>
> 样本：本机未入库的社区大型 Preset 样本 A。素材名称和内容标识已匿名化；本轮只调查“这个文件导入 ST 时借用了什么宿主能力”，不评价其中 prompt、Regex 或脚本各自想完成的职责。
>
> 真实 Android / Provider 链路结果见 [`community-preset-live-test.md`](../archive/community-preset-live-test.md)。

## 样本的字面结构

先不按 ST 的职责划分，只看 JSON 语法，这个文件可以写成下面这棵树：

```text
PresetRoot                                  object
├─ 44 个扁平基础字段                        primitive
│  ├─ number × 14
│  ├─ boolean × 10
│  └─ string × 20
├─ prompts                                  PromptDefinition[113]
├─ prompt_order                             PromptOrderBucket[1]
│  └─ [0]
│     ├─ character_id                       100001
│     └─ order                              PromptReference[109]
└─ extensions                               object
   ├─ regex_scripts                         RegexScript[29]
   └─ tavern_helper                         object
      ├─ scripts                            ScriptRecord[5]
      └─ variables                          object（本样本为空）
```

根对象没有 `type`、`spec`、`version`、`schema` 或 Preset 名称字段。也没有一个名为 `settings` 的子对象；44 个基础设置直接与三条结构化分支并列。

### 根部的 44 个基础字段

按 JSON 值类型分组如下；这只是对字面结构的整理，不代表文件中存在这些分组：

```text
number[14]
  temperature, frequency_penalty, presence_penalty, top_p,
  top_k, top_a, min_p, repetition_penalty,
  openai_max_context, openai_max_tokens, names_behavior,
  tool_call_recurse_limit, seed, n

boolean[10]
  max_context_unlocked, stream_openai, use_sysprompt,
  squash_system_messages, media_inlining, continue_prefill,
  function_calling, show_thoughts, enable_web_search, request_images

string[20]
  tool_reasoning_mode, send_if_empty, impersonation_prompt,
  new_chat_prompt, new_group_chat_prompt, new_example_chat_prompt,
  continue_nudge_prompt, bias_preset_selected, wi_format,
  scenario_format, personality_format, group_nudge_prompt,
  assistant_prefill, assistant_impersonation, inline_image_quality,
  continue_postfix, reasoning_effort, verbosity,
  request_image_aspect_ratio, request_image_resolution
```

### `prompts`：定义表

113 个定义都有同一组基础字段：

```text
PromptDefinition {
  identifier: string
  name: string
  enabled: boolean
  role: "system" | "user" | "assistant"
  system_prompt: boolean
  marker: boolean
  forbid_overrides: boolean

  content?: string
  injection_position?: number
  injection_depth?: number
  injection_order?: number
  injection_trigger?: array
}
```

可选字段在本样本中的分布：

- `content` 出现在 105/113 项；另外 8 项恰好都是 `marker: true` 的占位定义。
- 三个 `injection_*` 坐标总是成组出现，共 107/113 项。
- 不带注入坐标的 6 项是两个 marker（`dialogueExamples`、`chatHistory`）和四个普通定义（`main`、`nsfw`、`jailbreak`、`enhanceDefinitions`）。
- `injection_trigger` 只出现一次，值是空数组。
- 113 个 `identifier` 全部唯一；role 分布为 user 97、system 13、assistant 3。

因此，从字面结构上可以把 `prompts` 看成一个由 `identifier` 建索引的**定义表**。它不是最终顺序，也不保证每个定义都会被使用。

### `prompt_order`：引用表

```text
PromptOrderBucket {
  character_id: number
  order: PromptReference[]
}

PromptReference {
  identifier: string
  enabled: boolean
}
```

本样本只有一个 bucket，`character_id` 为 `100001`，其中有 109 个引用：

- 109 个引用 identifier 全部唯一；
- 每个引用都能在 `prompts` 定义表中找到；
- `main`、`nsfw`、`jailbreak`、`enhanceDefinitions` 四个定义没有被引用；
- 定义和引用两处都保存了 `enabled`；本样本被引用项的两处值完全相同；
- 引用表中 38 项启用、71 项关闭。

也就是说，这部分不是一棵嵌套 Prompt 树，而是更接近：

```text
定义表 prompts
       ↑ identifier 引用
有序使用表 prompt_order[0].order
```

### `extensions.regex_scripts`：同构记录数组

29 项都具有以下字段，只是 JSON 属性排列顺序有一项不同：

```text
RegexScript {
  id: string
  scriptName: string
  disabled: boolean
  runOnEdit: boolean
  findRegex: string
  trimStrings: array
  replaceString: string
  placement: number[]
  substituteRegex: number
  minDepth: number | null
  maxDepth: number | null
  markdownOnly: boolean
  promptOnly: boolean
}
```

本样本中 27 项未禁用、2 项禁用；28 项的 `placement` 为 `[2]`，1 项为 `[1]`。深度边界多数为 `null`。

### `extensions.tavern_helper`：脚本记录与变量对象

```text
TavernHelperPayload {
  scripts: ScriptRecord[5]
  variables: object
}

ScriptRecord {
  type: string
  enabled: boolean
  name: string
  id: string
  content: string
  info: string
  button: {
    enabled: boolean
    buttons: Array<{ name: string, visible: boolean }>
  }
  data: object
  export_with: {
    data: boolean
    button: boolean
  }
}
```

五项的 `type` 都是 `"script"`，`content` 是脚本文本字符串；`data` 的内部形状各不相同，是每项随附的私有对象。本样本的顶层 `variables` 是空对象。五项合计的 `content` 约 87.6 万字符，但从 JSON 结构上看仍然只是五个字符串叶节点。

## 扩展载荷的来源边界

此处只记录宿主来源，不继续分析载荷想实现的业务效果：

| JSON 路径 | 能力来源 | 没有对应能力时 |
| --- | --- | --- |
| `extensions.regex_scripts` | SillyTavern 仓库内自带的 Regex 扩展 | ST 核心仍可保存字段，但 Regex 规则不会产生效果 |
| `extensions.tavern_helper` | 第三方 Tavern Helper / JS-Slash-Runner 扩展 | ST 本身不认识也不执行其中脚本；载荷只能作为 JSON 被保存 |

本机 SillyTavern checkout 包含 `public/scripts/extensions/regex/` 及其 `manifest.json`、`engine.js` 和 Preset 嵌入脚本界面；同一 checkout 中没有 `tavern_helper` 或 `JS-Slash-Runner` 实现。

因此，样本里的 `content` 虽然是 JavaScript 字符串，但“Preset 可以携带字符串”与“ST 能执行这段 JavaScript”是两项不同事实。执行能力来自另行安装的 Tavern Helper，而不是 OpenAI Preset 格式本身。

## ST 基础宏能力

宏不是 Preset 中独立的一块数据。它是 ST 核心对多种文本字段提供的运行时模板语法，常见写法为 `{{macroName}}` 或 `{{macroName::argument}}`。

当前 ST 自带宏大致分为：

- 环境内容：`user`、`char`、`persona`、角色描述、性格、场景、首条消息、示例消息、当前模型等；
- 聊天状态：最后一条消息、最后一条用户消息、楼层和 swipe ID 等；
- 时间与随机：时间、日期、星期、随机选择、掷骰等；
- 上下文设置：最大 Prompt、上下文和回复 token 数，以及 instruct 模式的前后缀；
- 变量：聊天局部变量与全局变量的读取、写入、增加、递增、删除和存在性检查；
- 控制与文本：条件、注释、换行、裁剪等。

宏在相关文本被准备时求值，而不是在导入 JSON 时求值。其中一部分是纯替换，例如把 `{{user}}` 换成用户名；变量写入宏具有副作用，例如 `setvar` 返回空字符串，同时修改聊天局部变量。

ST 的宏注册表还允许其他模块增加宏。因此必须区分“ST 自带宏语法”与“第三方注册的自定义宏”；后者虽然使用相同的 `{{...}}` 外观，但并不属于 ST 基础能力集合。

## ST Regex 扩展能力

Regex 扩展把一条规则表达为“查找正则 + 替换模板 + 应用条件”。ST 源码把这种规则称为 Regex script，但它不是 JavaScript。

它原生支持的应用位置为：

```text
1  USER_INPUT      用户输入
2  AI_OUTPUT       AI 输出
3  SLASH_COMMAND   斜杠命令结果
5  WORLD_INFO      世界书文本
6  REASONING       推理文本
```

一条规则还可以限制：

- 只处理发送给模型的 Prompt，或只处理 Markdown 显示；
- 是否在编辑消息时运行；
- 只处理聊天历史的某个深度范围；
- 查找正则中的宏是否求值，以及按原文还是正则转义后代入。

替换模板支持完整匹配、编号捕获组、命名捕获组，并在替换末尾继续进行 ST 宏替换。多条获准规则按顺序执行，前一条的输出会成为后一条的输入。

Regex 规则可以来自全局、当前作用域以及当前 Preset；本样本的 `extensions.regex_scripts` 属于第三种。它提供的是受约束的文本转换，不提供任意 JavaScript 执行能力。

## 由结构引出的 ST 运行观察

这份文件并不是由一个统一的“Preset 解释器”完整解析。它利用了 ST 的一种组合机制：

```text
一个 JSON 容器
  -> OpenAI Preset 导入口原样保存
  -> 选择 Preset 时应用核心设置并切换 prompt 数据
  -> 各扩展从 extensions 命名空间认领自己的载荷
  -> 经过各自授权后，在生成阶段执行宏、Regex 和脚本
```

所以这里的 `Preset` 更接近一个**组合包 / 装配清单**，而不只是采样参数或一段 system prompt。

三个状态不能互相等同：

- 导入成功，不表示其中所有能力都能被当前 ST 安装识别；
- Preset 被选中，不表示嵌入的扩展能力已经获得授权；
- 内容被保存，不表示宏、Regex 或脚本已经执行。

## 四个时刻

### 1. 导入

OpenAI Preset 导入口解析 JSON，处理代理地址、自定义端点等敏感连接字段，并触发导入前事件。随后它把整个 Preset 对象交给服务端保存。

服务端 `/api/presets/save` 对 `request.body.preset` 做整体 JSON 序列化，没有按已知字段重建对象。因此 `extensions` 内的第三方载荷可以跟随 Preset 落盘，即使核心不理解其内容。

### 2. 选择

选择 Preset 时，OpenAI 设置模块先触发 `OAI_PRESET_CHANGED_BEFORE`，再应用已知设置，最后触发 `OAI_PRESET_CHANGED_AFTER` 和通用 `PRESET_CHANGED` 事件。

本样本的 47 个顶层字段全部出现在当前 ST OpenAI 设置的已知字段表中；它没有依赖未知顶层字段“碰巧被保留”。其中 `extensions` 被整体赋给当前设置，具体子字段留给扩展认领。

### 3. 授权与接管

Regex 扩展和 Tavern Helper 都从当前 Preset 的 `extensions` 下读取自己的字段，并各自维护 Preset 级允许列表。它们还会继续尊重每条 Regex 或脚本自身的 `enabled` 状态。

因此授权状态是宿主本地状态，不是这份社区 JSON 单方面声明的能力。导入者可以携带代码，但不能仅凭文件中的 `enabled: true` 获得执行权。

### 4. 运行

Prompt Manager 按 `prompt_order` 取出 prompt，并在生成消息集合时依次替换宏。Regex 和 Tavern Helper 脚本也由各自宿主在后续生命周期运行。

换言之，导入阶段主要承载和保存“程序材料”；真正的求值发生在运行阶段。

## 样本实际借用的能力

| 能力层 | 样本中的载荷 | ST 提供的承载能力 | 当前事实 |
| --- | --- | --- | --- |
| OpenAI 设置快照 | 47 个顶层设置字段 | 已知设置应用、选择前后事件、整体保存 | 47/47 均为当前 ST 已知键 |
| Prompt 定义仓库 | `prompts` 113 项 | 带 identifier、role、marker、注入位置等属性的 prompt 对象 | 既有保留类别，也有大量自定义 prompt |
| Prompt 装配顺序 | `prompt_order` 109 项 | 顺序、启用状态和使用成员关系独立于 prompt 定义 | `character_id: 100001` 是 Prompt Manager 的全局虚拟角色 ID，并非真实角色绑定 |
| 未装配资源 | 4 个定义不在 order 中 | 定义可以随包保存但不参与当前装配 | `main`、`nsfw`、`jailbreak`、`enhanceDefinitions` 当前属于未使用定义 |
| 文本宏语言 | 116 次宏调用 | 运行时顺序替换；变量宏可读写聊天变量 | 主要是 `setvar`、`getvar`、`addvar`，另有扩展宏 |
| 扩展命名空间 | `extensions` | Preset Manager 按路径读写扩展字段，并在重命名等操作中保留整体命名空间 | 核心负责携带，不统一解释子对象 |
| Preset Regex | `extensions.regex_scripts` 29 项 | Preset 级 Regex 来源、逐条启用、Preset 级授权 | 样本中 27 项自身启用；是否执行还取决于宿主授权 |
| Preset 脚本树 | `extensions.tavern_helper.scripts` 5 项 | 脚本/文件夹树、按钮、脚本数据、导出控制与 Preset 切换接管 | 样本为 5 个启用脚本；代码总量约 87.6 万字符 |
| Preset 变量 | `extensions.tavern_helper.variables` | 与当前 Preset 一起切换的扩展私有状态 | 由 Tavern Helper 的 schema 和 store 认领 |

这里最“极限”的地方不只是 prompt 或脚本数量，而是**同一个导入物同时装配了多个彼此独立的宿主系统**。

## Prompt 容器的两个重要细节

### 定义、使用和启用是三件事

`prompts` 是定义池，`prompt_order` 是装配清单，order 项上的 `enabled` 决定本次装配是否启用。一个 prompt 可以存在于定义池但完全不在装配清单中。

Tavern Helper 自己在读取 ST Preset 时也把两者区分为 `prompts` 和 `prompts_unused`。这说明“未使用定义”不是样本偶然产生的垃圾，而是社区工具明确保留的一种状态。

### 顺序本身具有执行语义

Prompt Manager 按 order 遍历并准备 prompt，宏替换也发生在这个过程中。本样本大量使用变量读写宏，因此顺序不仅影响消息排列，也可能影响后续 prompt 读取到的变量值。

## 已确认事实与待查项

### 已确认事实

- 样本使用 OpenAI Preset 格式，顶层设置键均被当前 ST 识别。
- Preset 对象由服务端整体序列化保存。
- Prompt 定义与 Prompt 顺序是两组独立数据。
- `100001` 是 OpenAI Prompt Manager 的全局虚拟角色 ID。
- `extensions.regex_scripts` 和 `extensions.tavern_helper` 分别由不同扩展读取。
- Regex 与 Tavern Helper 都存在“脚本自身启用 + Preset 获准”两层开关。
- 宏在生成 prompt collection 时求值，而不是在导入时求值。

### 仍待确认

- 第三方扩展监听多个 Preset 事件时的完整先后关系，以及版本变化是否影响该顺序。
- Tavern Helper 对第一次遇到嵌入脚本的 Preset，具体由哪条 UI 路径完成授权提示。
- 不同 ST / Tavern Helper 版本对脚本 schema、导出清理和迁移字段的兼容范围。
- 样本内自定义宏分别由哪些扩展注册，以及能力缺失时的精确退化行为。

## 调查来源

本机相邻 SillyTavern checkout：

- `public/scripts/openai.js`：OpenAI Preset 导入、选择和已知字段表。
- `public/scripts/PromptManager.js`：prompt 定义、顺序与运行时准备。
- `public/scripts/preset-manager.js`：扩展命名空间读写。
- `public/scripts/extensions/regex/engine.js`：Preset Regex 读取与授权。
- `src/endpoints/presets.js`：Preset 整体保存。

Tavern Helper 官方仓库：

- <https://github.com/N0VI028/JS-Slash-Runner>
- `src/function/preset.ts`：ST Preset 的结构化读取。
- `src/store/settings/preset.ts`：Preset 扩展设置随选择切换。
- `src/type/settings.ts`、`src/type/scripts.ts`：`tavern_helper` 载荷 schema。
