# 社区 Preset 真实链路验收

> 状态：2026-09-01 的开发期实验记录，不是兼容性承诺。
>
> 样本：本机未入库的社区 Preset 样本 B 与小型 Character Card 样本。素材名称和内容标识已匿名化；`source/` 不进入版本库。

## 结论

**该社区 Preset 已经能在不修改资产参数、不依赖第三方扩展、也不填写模型 Token 覆盖的情况下完成一轮可见回复。**

最初基线中，原始配置在请求前被 context 检查拒绝；临时降低回复上限后，输出预算又全部消耗在 reasoning。加入未知模型 output 安全协商、按模型 ID 的手动 Token 覆盖入口，以及开头正向 lookbehind 的等价改写后，全新安装复测保持 Preset 原始 65535 回复上限，仅靠自动 fallback 即完成生成。

因此当前状态不是“格式只能保存但不能运行”，也不是“已经完整兼容社区玩法”，而是：

```text
核心 OpenAI Preset 语义       已接通
这份样本的主要基础 Macro      已接通
真实 Provider 请求与流式事件  已接通
极端 context / reasoning 假设 已安全协商
本样本 Preset Regex           已通过
第三方扩展宿主                明确不执行
原参数端到端可见回复           已通过
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
- 基线导入诊断 6 条，全部为 warning，没有 error；其中一条是后来修正的空 Tavern Helper 容器误报，当前同结构导入不再产生该告警。

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

基线导入中的 `THIRD_PARTY_SCRIPT_PRESERVED` 是按扩展对象是否存在作出的保守告警，其“检测到第三方脚本”文案对这个样本并不精确。当前导入器会深查 Tavern Helper 容器：`scripts=[]` 且 `variables={}` 时继续保留载荷，但不再声称存在第三方运行时依赖；存在真实脚本、变量状态或其他非空载荷时仍会告警且绝不执行。

## 实验一：保持原始参数（改进前基线）

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

1. 使用当前连接按模型 ID 保存的手动 Token 覆盖；
2. 使用 Provider 模型目录返回的 `inputTokenLimit` / `outputTokenLimit`；
3. context 元数据缺失时，按少数内置的已知模型名前缀匹配 context；
4. 仍未命中时，context 使用 32768 的保守 fallback；output 未知且 Preset 请求过高时，安全预留不超过有效 context 的一半和 16384 tokens。

Preset 中的 `maxContextTokens` 和 `maxOutputTokens` 是意图与上限，不是模型能力查询键。当前有效值可简化为：

```text
effective context = min(model context limit, preset max context)
effective output  = min(model output limit 或安全 fallback, preset max output)
```

基线版本在模型 limits 未知时完整预留了 Preset 的 65535 output，因而失败。当前版本会保留 Preset 资产原值，但把本轮有效 output 安全收敛为 16384，并明确写入诊断；用户也可以在模型连接中按模型 ID 提供可信 limits。

## 实验二：临时把回复上限改为 1024（改进前深链路验证）

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

## 实验三：改进后保持原始参数

在全新安装的 Debug APK 中重新导入同一批本机样本，重新建立 Provider 连接，不填写模型 Token 覆盖，也不编辑 Preset。输入：

```text
Please continue.
```

Preset 仍保持：

```text
openai_max_context = 2000000
openai_max_tokens  = 65535
```

自动协商与 Provider 结果：

```text
effective context          = 32768
local estimated input      = 11258
final request estimated    = 11454
effective output reserve   = 16384
provider reported input    = 2602
provider reported output   = 12500
provider cached            = 1152
provider reasoning         = 11226
provider total             = 15102
finish reason              = completed
visible text               = yes
```

本轮产生了 `MODEL_CONTEXT_LIMIT_FALLBACK` 与 `MODEL_OUTPUT_LIMIT_FALLBACK`，清楚记录 32K / 16K 的有效安全值。Provider 完成 reasoning 后返回了正文，assistant variant 正常保存；Preset 的原始 65535 没有被改写。

这证明自动 fallback 已足以让本样本通过。模型级手动覆盖是目录元数据缺失时的精确控制入口，不是本轮成功的必要条件。

## Regex 结果

改进前基线出现：

```text
WARNING: REGEX_RULE_CIRCUIT_OPEN
Regex“样本语气处理规则”已在当前会话熔断
```

该规则使用 JavaScript 风格的可变长后向断言：

```regex
/(?<=(语气|语调|声音)([\u4e00-\u9fa5]+?))([,，]?)(得?)(如同|像|仿佛).*?(?=[。，,])/g
```

基线版本直接交给 Android lookbehind 执行，在生产 worker 的 250 ms 单规则上限内没有完成，因此熔断当前 Conversation 中的该规则。

当前版本把位于开头的正向后向断言改写为“消费并保留前缀”的等价前向匹配，同时校正编号捕获组和 `{{match}}`。JVM 测试、API 35 设备测试以及全新安装的真实 Preset 复测均通过；应用级复测产生三条 `REGEX_LOOKBEHIND_REWRITTEN` 诊断，没有 `REGEX_TIMEOUT` 或 `REGEX_RULE_CIRCUIT_OPEN`。

## 兼容矩阵

| 能力 | 本次状态 | 证据与边界 |
| --- | --- | --- |
| JSON 识别与安全落盘 | 通过 | 成功进入 Preset library；0 个导入 error |
| Prompt 定义池 | 通过 | 142 项保存并可浏览 |
| `100001` 全局 order | 通过 | 59 项，30 项启用，进入真实编排 |
| 基础 Macro | 通过本样本 | 激活路径未产生 unsupported Macro |
| local variables | 通过本样本 | `setvar/getvar` 进入事务求值 |
| Preset Regex 调度 | 通过本样本 | 三条开头 lookbehind 完成等价改写；无超时或熔断 |
| 未知扩展保留 | 通过 | `SPreset` / Tavern Helper 载荷保留但不执行 |
| 第三方脚本运行 | 不支持（设计边界） | 本样本实际 scripts 为空，不是本轮阻塞点 |
| generation settings | 部分通过 | 通用字段保留；`top_a` 等无安全映射字段被省略 |
| context 预算 | 安全降级通过 | 32K context fallback 下把 65535 请求收敛为 16384 有效回复预留 |
| Responses 请求构造 | 通过 | 原始 Preset 参数经运行时协商后真实请求成功发送 |
| SSE / usage / reasoning | 通过 | usage、reasoning=11226、`completed` 被解析 |
| 可见 assistant 回复 | 通过 | 全新安装、无手动 limits、未编辑 Preset 时完成并保存正文 |

## 当前缺少什么

### 确实缺少或仍不完整

1. **自定义模型的自动可信能力信息。** 模型目录不提供 limits 时仍需 fallback 或用户按模型 ID 手动填写；播放器不会把 Preset 声明的 200 万 context 当成 Provider 保证。
2. **未知模型的 reasoning 参数表达。** 本轮 Provider 自行产生 reasoning，但 `reasoning_effort` 因模型能力未知而被省略，不能证明社区作者指定的 `high` 被精确执行。
3. **更广的 JavaScript Regex 兼容面。** 本样本的开头正向 lookbehind 已通过；其他无法安全改写的 JS 语义仍会跳过或受熔断保护。
4. **部分 sampler 的 Provider 表达。** `top_a`、`min_p`、`repetition_penalty` 会保留、导出并告警，但不会伪造映射。
5. **第三方扩展宿主。** `SPreset`、Tavern Helper 脚本、动态 Macro 和事件生命周期不会执行；这是当前明确边界，不是本轮回归。

### 这份样本没有证明缺少

- 它没有实际 Tavern Helper scripts 或 variables；
- 四个 `model` role Prompt 都关闭且未进入 order；
- ChatSquash、MacroNest、ToolBindings 和 MessageInjections 当前均未启用；
- 本次实际用到的基础 Macro 已进入编排；
- Prompt order、World Book 激活、Provider request 和流式事件不是空壳。

## 最终判断

如果“兼容这个样本的实际无第三方依赖路径”指不编辑 Preset 就能导入、选择、编译 Prompt、执行基础 Macro / Regex、构造真实请求并得到可见正文，答案是：**已经通过。**

如果“兼容”指精确复现社区作者在特定模型能力表、sampler 和扩展环境中的所有行为，答案仍然是：**不能由这一个样本证明。**

这次先行改进解决了该样本最先撞到的 token 预算和 lookbehind 性能差异，也证明失败与 Tavern Helper 无关。剩余差异已经收敛到未知模型能力声明、Provider sampler / reasoning 表达和本样本没有启用的第三方宿主，而不是核心 Preset 管线。

## 实验后状态

- 模拟器中旧复杂卡已清除，只保留本轮小型 Character Card 样本；
- 社区 Preset 样本 B 保持为活跃 Preset，原始回复上限仍为 65535；
- 模型连接未填写手动 Token 覆盖，最近一次成功使用 32K / 16K 自动 fallback；
- `maxOutputTokens` 已恢复为原始 65535；
- 全新安装复测 Conversation、上下文诊断、usage 与可见正文保留；
- 为 SAF 导入复制到公共存储的临时文件已删除。
