# MVU 公共变量能力接入实验

> 2026-09-07。用户要求实际尝试引入 MVU 能力。本轮完成固定源码的可运行接入实验，未修改 Android 运行时，不继续扩建旧的逐标量适配器。

> 后续：用户已选择 QuickJS，新增 [Kotlin 宿主与检查点接入](mvu-quickjs-integration-20260907.md)。下文保留 Node 参照实验事实，不代表当前仍只有 Node 实现。

## 结果

C-04 原始初始化数据与原始 Zod 注册脚本，能在提供有限宿主接口后由 MVU 及其 Zod 辅助库直接处理。无需把动态物品、衣橱、数值增减翻译成各自的 Player 专门规则。

真实样本验证通过 15 项断言类别：原始初始化与 Schema、四种更新操作、动态 Record、数值转换、条目默认值、数值限幅、状态栏占位符补入、旧消息保持不变、从前一条消息重生成、候选切换和 JSON 恢复。另有 4 个中性自动测试覆盖多开场初始化、连续更新、候选与恢复、逐命令失败、下划线路径保护和 JSON Pointer 转义。

这证明了所选上游执行路径可以脱离网页面板运行；不代表 App 已接通，也不是整卡游玩验收。没有模型联网请求、EJS 求值、原生界面渲染或 Android 性能/生命周期测试。

## 固定来源

- MVU：[MagVarUpdate，提交 `61010dab47bc3a08a1b626320bf7fc8c9573eca4`](https://github.com/MagicalAstrogy/MagVarUpdate/tree/61010dab47bc3a08a1b626320bf7fc8c9573eca4)。
- Zod 注册辅助库：[tavern_resource，提交 `276040b5f26436f18662d841fb667429e82b26d4`](https://github.com/StageDog/tavern_resource/tree/276040b5f26436f18662d841fb667429e82b26d4)。
- 真实样本 C-04 SHA-256：`fa7e8ec564887780b331d0da29f7966f58f3688d49e6faf587d2d80ae9aecefe`，与此前复盘样本相同。
- 中性夹具：[state-card.json](../tools/mvu-probe/fixtures/state-card.json)。
- 每个上游文件的原始字节哈希和下载地址：[upstream-lock.json](../tools/mvu-probe/upstream-lock.json)。依赖下载至忽略目录，未把上游代码复制进应用源码。

原卡远程 import 未指定提交。本实验主动锁定以上版本，不能倒推该卡作者历史运行时使用的确切版本。

## 实际执行链路

```text
原卡世界书与开场
  → MVU initCheck / loadInitVarData
  → VARIABLE_INITIALIZED 事件
  → 原卡 registerMvuSchema：默认值、类型与范围
  → 消息候选保存完整 MvuData

合成助手消息中的 JSONPatch
  → MVU handleVariablesInMessage / extractCommands
  → COMMAND_PARSED_for_zod
  → mvu_zod 执行操作并逐项校验原卡 Schema
  → 已处理命令清理、更新结束事件
  → MVU 补入 <StatusPlaceHolderImpl/>
  → 宿主将变量保存到该消息候选
```

MVU 的 `schema`、`stat_data`、`initialized_lorebooks` 等字段保持原结构，没有转成 Player 平铺字段白名单。Zod 辅助库会接管命令执行，并在更新结束后使用自身的 schema 标记、删除旧 display/delta 数据；不能只把它当成导入期字段描述。

状态栏占位符也不完全依赖聊天模型：此次执行的 MVU 消息处理函数在助手消息缺少占位符时会补入。此前只从展示正则反推触发来源，遗漏了框架这个职责。

## 宿主接口与实际替代

| 接口 | 实验实现 | Player 接入需要核对的部分 |
|---|---|---|
| `eventOn / eventEmit` | 按注册顺序执行并等待异步监听 | 保留 MVU 与 Zod 的事件顺序，状态更新完成后再提交候选 |
| `getChatMessages / getLastMessageId`、`SillyTavern.chat` | 内存消息与选中候选 | Player turn/variant 到上游消息语义的映射；不重新创建第二条时间线 |
| `updateVariablesWith / setChatMessages` | 候选变量及消息文字写入 | 同一次消息提交中保存变量与投影，保留 Provider 原始正文 |
| 世界书读取 | 提供原卡条目，包括普通 Prompt 中关闭的 initvar 条目 | 初始化语义与普通世界书激活语义分开 |
| `registerVariableSchema` | 记录注册；实际命令校验仍由上游 Zod 监听执行 | 本轮未实现酒馆助手变量编辑器的自动验证 |
| `substitudeMacros` | 原样返回 | 必须连接 Player 对应宏语义；本轮更新文本不含宏 |
| `$`、通知和设置 | 两处确定的 DOM 查询、诊断记录、内存设置 | 以固定宿主值/诊断替代网页面板；不让上游覆盖用户的世界书设置 |

测试只提供用到的接口；未知 DOM 查询和未实现的消息选择方式直接报错，不伪装整个 Tavern Helper 已存在。候选选择及存储恢复由模拟宿主负责，实际 MVU 负责从前一有效消息的选中候选取状态和执行更新。

## 相较当前 Player 的重要差异

1. 当前 Player 的旧适配器逐项映射标量；实验保留原始嵌套状态，由框架处理动态键和对象。
2. 当前 Player 会整批拒绝非法操作；测试中的 MVU + Zod 组合跳过非法命令，仍执行后面的合法命令。正式接入不能继续用旧适配器的“整批合法”判断替代框架结果。
3. 上游解析器的接受范围比当前严格 envelope 宽。本轮只用完整 JSONPatch 更新，不声称沿用 Player 的畸形块拒绝语义。
4. 框架可以修改助手消息以补占位符。Player 需要在保留不可变 `sourceText` 的前提下承接这一结果。
5. 此样本前端读取最新助手消息变量，界面操作只涉及展开、主题和页签。状态执行实验不要求先建立通用界面 DSL；EJS Prompt 模板仍是另一项依赖。

## 后续接入边界

此次保留 Android 原实现，是因为实验尚未提供 Android JavaScript 宿主。下一个可执行工作是选定并验证无网页 UI 的 JavaScript 执行环境，将完整 MvuData 接到已有消息候选检查点，再让原生状态与集合组件读取该状态。不能把 Node VM 的成功直接记为 Android 支持，也不应继续让适配模型逐卡重写 MVU。

还有一个实际分发问题：MVU 自身的 [LICENSE](https://github.com/MagicalAstrogy/MagVarUpdate/blob/61010dab47bc3a08a1b626320bf7fc8c9573eca4/LICENSE) 是 MIT，而此版本 `mvu_zod` 所在仓库的 [根 LICENSE](https://github.com/StageDog/tavern_resource/blob/276040b5f26436f18662d841fb667429e82b26d4/LICENSE) 是 AFPL 文本；其源文件未另附 MIT 标记。本轮把原许可证与下载源码一同留在本地，不将该辅助库打包分发，也不作授权兼容性的法律判断。正式引入原包前应明确该辅助库授权。

## 验证

已执行：

```powershell
npm --prefix tools/mvu-probe run build
npm --prefix tools/mvu-probe test
npm --prefix tools/mvu-probe run probe -- $env:COMMUNITY_CARD
git diff --check
```

真实样本命令实际传入本地 C-04 路径；文档使用环境变量表示，避免记录原始素材文件名。报告位于忽略目录 `tools/mvu-probe/build/card-report.json`。

未执行 Gradle 和真机检查：本轮没有修改 Kotlin、Android 依赖或应用入口；Android JavaScript 执行、生命周期、生产仓库存储和完整游玩仍未覆盖。
