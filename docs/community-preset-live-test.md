# 社区 Preset 真实链路验收

> 状态：2026-09-01 的开发期实验记录，不是兼容性承诺。
>
> 样本：本机未入库的社区 Preset 样本 B 与小型 Character Card 样本。素材名称和内容标识已匿名化；`source/` 不进入版本库。

## 结论

**该社区 Preset 已经能接入 Tavern Player 的核心 Preset 管线，但不能按原始参数开箱完成一轮可见回复。**

导入、保存、激活、Prompt 定义与顺序、样本实际使用的基础 Macro、Preset Regex 调度、context 预算、OpenAI Responses 请求构造、SSE 生命周期、usage、reasoning 和 finish reason 都进入了真实运行路径。原始配置在请求前被安全的 context 检查拒绝；临时降低回复上限后，请求成功到达 Provider，但输出预算全部消耗在 reasoning，未产生正文。

因此当前状态不是“格式只能保存但不能运行”，也不是“已经完整兼容社区玩法”，而是：

```text
核心 OpenAI Preset 语义       已接通
这份样本的主要基础 Macro      已接通
真实 Provider 请求与流式事件  已接通
极端 context / reasoning 假设 有条件
个别社区 Regex                部分兼容
第三方扩展宿主                明确不执行
原参数端到端可见回复           未通过
```

## 实验环境

- Tavern Player Debug APK；
- Pixel 7 AVD，Android 15 / API 35，x86_64；
- Provider host：`api.deepseek.com`；
- 协议：OpenAI Responses；
- 模型：`deepseek-v4-flash`；
- Character Card：本机小型社区样本；
- 活跃 Preset：社区 Preset 样本 B。

API Key 只存在于被 Git 忽略的 `.env` 和模拟器 Android Keystore。本记录不包含凭据。

## 对照组

在导入社区 Preset 样本 B 之前，同一 Provider 与模型已经完成两层对照验证：

1. `/models` 返回 HTTP 200，配置模型存在；`/responses` 返回 HTTP 200 与 `text/event-stream`；
2. Tavern Player 内置 Probe 显示“连接正常”；使用内置默认 Preset 的一次完整 Conversation 生成成功，assistant variant 为 `COMPLETE`，adapter 为 `OPENAI_RESPONSES`，finish reason 为 `completed`。

这排除了网络、鉴权、模型目录、Android Keystore 和 Responses SSE 基础实现故障。后续差异可以归因于该 Preset 的配置及其运行假设。

## 样本结构

导入后 Tavern Player 保存并展示：

- Prompt 定义 142 项；
- `character_id=100001` 的全局 order 59 项，其中 30 项启用；
- 兼容 bucket `character_id=100000` 另有 11 项，其中 10 项启用；运行时采用 `100001`；
- Preset Regex 9 条，其中原文件 8 条启用；
- 导入诊断 6 条，全部为 warning，没有 error。

四个 role 为 `model` 的 Prompt 被安全降级为 `system` 并报告 `INVALID_PROMPT_ROLE`。它们全部处于关闭状态，也不在当前全局 order 中，所以没有影响本轮请求。

### 样本实际使用的 Macro

对 142 个 Prompt 内容的字面统计如下：

| Macro | 调用次数 | 当前结果 |
| --- | ---: | --- |
| `setvar` | 27 | 已支持 |
| `user` | 24 | 已支持 |
| `random` | 12 | 已支持 |
| `getvar` | 9 | 已支持 |
| `roll` | 8 | 已支持 |
| `trim` | 7 | 已支持 |
| `lastUserMessage` | 2 | 已支持 |
| `lastCharMessage` | 1 | 已支持 |
| `//` 注释 | 若干 | 已支持 |

真实编排没有产生 `UNSUPPORTED_MACRO`。这只能证明本次激活路径用到的 Macro 已覆盖，不能外推为所有社区 Macro 已兼容。

### 第三方扩展载荷

样本带有 `extensions.SPreset` 和 `extensions.tavern_helper`：

- `tavern_helper.scripts=[]`，`variables={}`，没有实际 Tavern Helper 脚本或变量状态；
- `SPreset.ChatSquash.enabled=false`；
- `SPreset.MacroNest=false`；
- `ToolBindings` 与 `MessageInjections` 为空；
- `RegexBinding` 引用了 9 条 Regex。

所以 `THIRD_PARTY_SCRIPT_PRESERVED` 是保守的扩展存在告警；其“检测到第三方脚本”的文案对这个样本并不精确。Tavern Player 仍按安全边界保留未知载荷，但不执行第三方脚本。

## 实验一：保持原始参数

输入：

```text
How are you feeling today? Answer briefly.
```

样本原始关键参数：

```text
openai_max_context = 2000000
openai_max_tokens  = 65535
reasoning_effort   = high
show_thoughts      = true
```

自定义模型目录没有提供可信 context / output limits，Tavern Player 使用 32768 context fallback。编排结果：

```text
context            = 32768
estimated input    = 11268
reserved output    = 65535
available input    = 0
```

最终诊断：

```text
ERROR: MANDATORY_CONTEXT_OVERFLOW
必选 Prompt（11268 tokens）超过本轮输入预算 0
```

结果：请求没有发送到 Provider；Conversation 只保存开场和 user turn，没有创建 assistant turn。

这不是缺少 Prompt 导入能力。它暴露的是模型能力信息与社区 Preset 参数之间没有可信交集：播放器不能把 Preset 声明的 200 万 context 当成 Provider 保证，同时 65535 回复预留又大于安全 fallback。

### 当前预算选择规则

Preset 不根据模型名称选择预算。当前由播放器按以下优先级确定模型能力：

1. 使用 Provider 模型目录返回的 `inputTokenLimit` / `outputTokenLimit`；
2. context 元数据缺失时，按少数内置的已知模型名前缀匹配 context；
3. 两者均未命中时，context 使用 32768 的保守 fallback；output 元数据未知时不额外缩小 Preset 的回复上限。

Preset 中的 `maxContextTokens` 和 `maxOutputTokens` 是意图与上限，不是模型能力查询键。当前有效值可简化为：

```text
effective context = min(model context limit, preset max context)
effective output  = min(model output limit,  preset max output)  // 模型 output limit 已知时
```

本轮自定义端点的模型目录没有返回 token limits，模型名称也没有命中内置前缀表，因此 context 回退到 32768，而 Preset 的 65535 output 仍被完整预留。这正是“自定义模型能力目录 / 用户覆盖”尚未完成所造成的失败，不是 Preset 格式没有接上。

## 实验二：临时把回复上限改为 1024

只为继续验证更深链路，曾把活跃 Preset 的 `maxOutputTokens` 临时改为 1024，然后使用“重试这一轮”。测试后已恢复原始 65535。

编排与 Provider 结果：

```text
local estimated input       = 11284
final request estimated     = 11480
provider reported input     = 2608
provider reported output    = 1024
provider reasoning          = 1024
provider total              = 3632
finish reason               = incomplete
visible text                = 0
```

这次请求成功进入 OpenAI Responses adapter，SSE usage、reasoning 与 finish reason 均被正确解析。`top_a` 被明确省略并写入诊断，因为五个当前 Provider adapter 都没有安全等价字段。

结果：1024 个输出 token 全部用于 reasoning，没有正文。应用显示“模型没有返回正文”，不保存空 assistant turn，并提供“重试这一轮”。

这证明核心 Provider 链路已经接通；失败发生在“高 reasoning + 较小临时输出预算”的组合上，不是流解析失败。

## Regex 结果

本轮出现：

```text
WARNING: REGEX_RULE_CIRCUIT_OPEN
Regex“样本语气处理规则”已在当前会话熔断
```

该规则使用 JavaScript 风格的可变长后向断言：

```regex
/(?<=(语气|语调|声音)([\u4e00-\u9fa5]+?))([,，]?)(得?)(如同|像|仿佛).*?(?=[。，,])/g
```

它在生产 worker 的 250 ms 单规则上限内没有完成，因此只熔断当前 Conversation 中的该规则，没有拖死请求线程。安全降级生效，但该条 Regex 的社区预期效果没有实现。

## 兼容矩阵

| 能力 | 本次状态 | 证据与边界 |
| --- | --- | --- |
| JSON 识别与安全落盘 | 通过 | 成功进入 Preset library；0 个导入 error |
| Prompt 定义池 | 通过 | 142 项保存并可浏览 |
| `100001` 全局 order | 通过 | 59 项，30 项启用，进入真实编排 |
| 基础 Macro | 通过本样本 | 激活路径未产生 unsupported Macro |
| local variables | 通过本样本 | `setvar/getvar` 进入事务求值 |
| Preset Regex 调度 | 部分通过 | 规则进入执行；一条因 250 ms 熔断 |
| 未知扩展保留 | 通过 | `SPreset` / Tavern Helper 载荷保留但不执行 |
| 第三方脚本运行 | 不支持（设计边界） | 本样本实际 scripts 为空，不是本轮阻塞点 |
| generation settings | 部分通过 | 通用字段保留；`top_a` 等无安全映射字段被省略 |
| context 预算 | 安全失败 | 原始 65535 回复预留与 32K fallback 冲突 |
| Responses 请求构造 | 通过 | 降低回复上限后真实请求成功发送 |
| SSE / usage / reasoning | 通过 | usage、reasoning=1024、`incomplete` 被解析 |
| 可见 assistant 回复 | 未通过 | 原配置在请求前失败；临时配置只有 reasoning |

## 当前缺少什么

### 确实缺少或仍不完整

1. **自定义模型的可信能力信息。** 模型目录没有提供 limits 时只能使用 32K fallback，无法验证该模型是否能承载社区 Preset 声明的超大 context 与 output。
2. **极端输出预算与 reasoning 的协同策略。** 当前会安全保留 Preset 意图，但不会自动把 65535 改成猜测值；临时设为 1024 又会被 high reasoning 全部消耗。
3. **个别 JavaScript Regex 语义或性能兼容。** 安全熔断正确，但效果缺失。
4. **部分 sampler 的 Provider 表达。** `top_a`、`min_p`、`repetition_penalty` 会保留、导出并告警，但不会伪造映射。
5. **第三方扩展宿主。** `SPreset`、Tavern Helper 脚本、动态 Macro 和事件生命周期不会执行；这是当前明确边界，不是本轮回归。

### 这份样本没有证明缺少

- 它没有实际 Tavern Helper scripts 或 variables；
- 四个 `model` role Prompt 都关闭且未进入 order；
- ChatSquash、MacroNest、ToolBindings 和 MessageInjections 当前均未启用；
- 本次实际用到的基础 Macro 已进入编排；
- Prompt order、World Book 激活、Provider request 和流式事件不是空壳。

## 最终判断

如果“接上”指能导入、能选择、能按顺序编译 Prompt、能执行本样本基础 Macro、能构造真实 Provider 请求并解析流，答案是：**已经接上。**

如果“兼容”指不改任何参数，就能复现社区作者在特定 SillyTavern、模型能力表和扩展环境中的最终体验，答案是：**还没有。**

这份样本当前最先撞到的不是大型第三方脚本缺失，而是模型能力元数据、极端 token 预算、reasoning 配额和一条 Regex 的运行差异。后续适配应逐项决定哪些属于产品要支持的核心语义，哪些继续作为安全降级，而不是笼统增加一个“社区兼容模式”。

## 实验后状态

- 模拟器中旧复杂卡已清除，只保留本轮小型 Character Card 样本；
- 社区 Preset 样本 B 保持为活跃 Preset；
- `maxOutputTokens` 已恢复为原始 65535；
- 首次失败与临时参数重试的 Conversation / 上下文诊断保留；
- 为 SAF 导入复制到公共存储的临时文件已删除。
