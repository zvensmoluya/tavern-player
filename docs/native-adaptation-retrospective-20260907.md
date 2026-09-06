# 原生适配复盘：暂停扩建，重新审视表达与执行边界

> 2026-09-07。状态：暂停推进，待用户后续研究。本文记录已核实事实、复盘判断和未决问题，不是已批准的重构方案。

## 本次决定

用户要求先刹车，记录复盘，稍后再研究。当前不继续扩建 Native 能力、设计新 DSL 或接入扩展框架。保留现有实现与实验结果，后续方向由下一次讨论确定。

用户重新强调的意图是：替代 HTML/CSS 的界面表达，以及 JS 的行为表达，利用模型完成向 DSL 的翻译。适配的目标是保留原卡逻辑，而非为每张卡重新设计玩法。

“统一状态与 MVU 兼容层 + 界面和行为 DSL”是助手在讨论中提出的候选方向，用户尚未批准实施。直接运行原 JS、原生实现公共依赖或采用其他方案，也没有在本轮作出选择。

## 已核实的当前实现

- 当前没有接入或运行完整 MVU 框架。卡内远程 import 被保留为源码和适配模型输入，不等于该依赖已经执行。
- Player 自有状态存储、消息候选快照、持久化、恢复和界面读取能力。
- 旧协议适配器只接受逐项声明的标量路径：`_.set` 或 JSON Patch 外形的 `replace`。`delta / insert / remove` 未接通；对象、数组和动态路径也不能通过该适配器写入。
- `RECORD / COLLECTION` 可以保存并展示初值，但这不等于动态集合更新已实现。
- native-compiler-4 让模型读取完整卡内程序，输出现有 `NativeAdaptation` 配置；输出仍受固定功能及其限制约束，并不是通用界面或行为 DSL。
- 当前分别存在 status、forms、collections、progressions、worldBookTextSelections、playerChoices 等配置。阶段和文本选择尤其依赖有限数值分段或单枚举，无法组合表达样本中的全部条件。

对应实现：

- [NativeAdaptationModels.kt](../content-core/src/main/kotlin/io/github/zvensmoluya/tavernplayer/content/NativeAdaptationModels.kt)
- [NativeCompilationInstructions.kt](../content-core/src/main/kotlin/io/github/zvensmoluya/tavernplayer/content/NativeCompilationInstructions.kt)
- [LegacyStateAdapter.kt](../conversation-core/src/main/kotlin/io/github/zvensmoluya/tavernplayer/conversation/LegacyStateAdapter.kt)
- [ConversationStatePromptProjector.kt](../conversation-core/src/main/kotlin/io/github/zvensmoluya/tavernplayer/conversation/ConversationStatePromptProjector.kt)
- [NativeWorldBookTextProjector.kt](../conversation-core/src/main/kotlin/io/github/zvensmoluya/tavernplayer/conversation/NativeWorldBookTextProjector.kt)

## 原卡审计纠正了什么

样本编号见 [community-samples.md](community-samples.md)。本轮以 C-04 为主，C-03 为对照，核查启用脚本、正则、变量规则及条件模板，并与实际生成的适配结果比较。

### C-04：模型驱动的状态卡

原卡主要链路为：

    世界书规则与当前状态
      → 聊天模型生成剧情和更新块
      → MVU 执行变量更新并保存状态
      → 界面读取状态并显示

- 两个启用的助手脚本分别导入 MVU、注册 Zod 状态结构，没有卡内自定义的购买或物品效果执行引擎。
- 状态界面 JS 读取最新助手消息的变量，显示数值、背包、衣橱和当前穿着；交互是展开、主题和页签切换。没有购买、消耗或换装的业务按钮，也没有相应状态写入逻辑。
- 世界书要求聊天模型根据剧情判断时间推进、物品获得与消耗、衣物增加和数值变化，然后输出更新指令。
- 四段 EJS 中，两段按数值阶段选择提示；另两段根据地点、物品、数量、衣橱条目数、近期用户消息和组合条件选择提示。代码中的局部计算用于选文，没有直接写入持久剧情状态。
- 正则承担状态栏占位符替换、更新块折叠及历史 Prompt 清理；不能把全部状态执行归到正则。

实际适配结果保留了标量状态更新、初始集合展示和两组阶段文本。动态背包与衣橱更新，以及另外两段组合条件选文，没有等价接通。未迁移条目的原文保留不等于 EJS 已执行。

还存在输出协议冲突：原卡规则要求 `replace / delta / insert / remove`，Player 同时要求仅输出白名单内的标量 `replace`。当前解码器遇到不支持的操作会拒绝整批更新。不能把这种拒绝笼统归因为模型输出能力不足。

### C-03：表单生成聊天草稿

原 JS 读取五个表单字段，处理多选拼接和空值默认值，生成文字后填入聊天输入框，由玩家发送。原生表单已经覆盖该主链路，无需另建剧情业务机制。

用户认可简单开局表单是当前已取得的成果，同时保留过拟合的疑虑。现有证据支持 C-03 的表单主链路已覆盖，尚不能推导所有仅含开局表单的卡都能等价迁移；不同的 JS 文本构造与交互仍需独立样本验证。

### MVU 与消息编排的区别

MVU 是基于酒馆助手的变量维护脚本。这里的 `replace / delta / insert / remove` 针对状态对象：设值、增减数值、插入条目、删除条目。角色卡要求聊天模型输出这些指令，框架执行修改。

本轮讨论的社区项目明确为 [酒馆助手 / Tavern Helper：N0VI028/JS-Slash-Runner](https://github.com/N0VI028/JS-Slash-Runner) 和 [小白助手：RT15548/LittleWhiteBox](https://github.com/RT15548/LittleWhiteBox)。MVU README 中的“酒馆助手”直接链接前者。两者是不同扩展，MVU 是前者之上的变量维护脚本；对 C-04 的 MVU 核查不能代替对这两个项目全部能力的审计。

这些操作本身不表示增删聊天消息或重排 Prompt。状态可以附存于消息，供后续回复和界面读取，这与消息正文和顺序是不同职责。

来源：[MVU README](https://github.com/MagicalAstrogy/MagVarUpdate)、[变量更新实现](https://github.com/MagicalAstrogy/MagVarUpdate/blob/beta/src/function/update_variables.ts)、[状态存储说明](https://github.com/MagicalAstrogy/MagVarUpdate/blob/beta/doc/stat_var.md)。本轮查看的是公开仓库当前资料，未证明它与样本无版本号远程导入在所有历史时点完全一致，也未验证所有扩展版本。

## 复盘判断

1. 助手此前将动态物品等缺口扩大解释为缺少“完整业务引擎”，没有先对照原卡执行逻辑。原卡已经把这些判断交给聊天模型，不能额外要求 Player 重新实现购买、换装或物品效果规则。
2. v3 的固定源码识别器曾限制模型看见什么；v4 虽已撤除识别器，输出端仍受狭窄的固定功能限制。完整源码输入没有自动解决目标语言的表达能力问题。
3. 当前通用状态操作不完整，却已有多种用途很窄的专门配置，导致遇到源程序时倾向逐项增加功能。这是需要重新审视的抽象边界，尚不能据此认定所有现有配置都应删除。
4. 应区分三类问题：模型翻译错误、目标运行时表达不了、公共依赖没有提供。三者不能统一写成“适配不确定”。
5. 安装成功、结构校验通过、单个状态更新通过，都不能证明整卡语义等价；同样，原卡本就交给模型判断的自然语言规则，也不因没有本地确定性业务实现而自动算作缺失。

## 待下一次讨论

- 原生替代的边界：哪些内容保留，哪些翻译，哪些公共框架职责由 Player 统一实现？
- MVU 兼容应覆盖什么版本与可观察行为，如何避免逐卡重复翻译公共依赖？
- DSL 需要哪些可组合的界面、数据读取、条件、遍历和交互能力？哪些既有专门配置可以被组合表达替代？
- 如何统一模型输出协议、状态存储结构和界面数据绑定，避免双重结构与相互冲突的提示要求？
- 哪些消息快照、持久化、来源引用和校验能力可以继续复用？
- 用哪些中性样本行为验证保真，避免再次从“能安装”推导“能完整游玩”？

以上仅列问题，不据此启动实现或扩大范围。

## 证据与验证范围

- C-04 原件 SHA-256：`fa7e8ec564887780b331d0da29f7966f58f3688d49e6faf587d2d80ae9aecefe`。
- 对照实验：C-04 `1788706390515`；C-03 `1788706590338`，详见 [native-compilation.md](native-compilation.md)。本地原件和实验产物留在既有忽略目录，不随本文提交。
- 本轮为源码、样本和已有实验产物的只读审计，没有新增完整游玩验证，也没有因本轮讨论改动运行时代码。
- 本次暂停记录仅修改文档；执行 `git diff --check`，不运行构建或测试。
