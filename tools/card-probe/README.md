# card-probe — HTML 候选片段与宿主调用观察

> 定位：保留的辅助验证工具，暂停扩建。接口契约直接查阅扩展文档与实现；本工具仅用于具体动态路径的复核，不用于推断完整兼容层或估算生态覆盖率。

用桌面 Edge + CDP 运行卡片的 HTML 候选片段，记录注入宿主命名空间的访问、调用、异常和资源阻断。宿主是**诊断用模拟实现**，返回值、初始状态和事件由场景控制，不代表 ST 或 Player 的正确实现。

[复核报告](../../docs/archive/html-surface-probe-20260909.md)记录结果与限制；[路线讨论](../../docs/html-surface-discussion-20260909.md)说明产品背景。

## 安装与验证

需要 Node 22+、本机 Edge。其他 Chromium 可通过 `EDGE_PATH` 指定。以下命令在仓库根目录运行：

```powershell
npm --prefix tools/card-probe ci
npm --prefix tools/card-probe run prepare:libs
npm --prefix tools/card-probe test
npm --prefix tools/card-probe run test:browser
```

依赖由 lockfile 固定；Zod 的浏览器 bundle 在 `build/` 本地生成。浏览器测试验证绑定调用、事件后状态重读、点击写入和回环 IP 资源阻断，不依赖社区素材。

## 样本清点与加载期调查

```powershell
node tools/card-probe/extract.mjs source
node tools/card-probe/probe.mjs 0d9f7714 --list
node tools/card-probe/probe.mjs 0d9f7714 --surface 0
node tools/card-probe/survey.mjs
```

提取器识别当前源目录顶层的 PNG/JSON，原件保持不变。输出以原件 SHA-256 命名，当前 `build/inventory.json` 是本轮唯一输入清单；旧 `build/cards` 文件不会自动混入。相同卡的 PNG/JSON 可以具有不同原件哈希，统计文件数不能当作不同卡数量。

候选来自 Regex 替换模板、开场、备用开场和世界书中的有限 HTML 标签检测。候选可能停用、缺少匹配输入，或根本不用于页面；普通调查不把它们当作真实启用的展示面。只取第一个符合条件的代码围栏，不覆盖所有 HTML/Markdown 语法。

每次批量调查生成独立 `build/runs/<run-id>/`，记录失败项并保存当前汇总至 `build/survey-v2.json`。不读取旧调用日志补齐失败结果。

## 定向交互复核

```powershell
node tools/card-probe/probe.mjs 8f24972a --surface 1 --message first --scenario tools/card-probe/scenarios/s02-opening.json --output tools/card-probe/build/scenarios/s02-opening.json
node tools/card-probe/probe.mjs 8f24972a --surface 3 --message alt:5 --scenario tools/card-probe/scenarios/s02-events.json --output tools/card-probe/build/scenarios/s02-events.json
node tools/card-probe/probe.mjs 0d9f7714 --surface 0 --message first --scenario tools/card-probe/scenarios/c03-opening.json --output tools/card-probe/build/scenarios/c03-opening.json
node tools/card-probe/verify-evidence.mjs
```

`--message` 从原卡选择开场，要求指定 Regex 未停用、标记为展示规则且确实匹配；应用该条原始替换模板，再去掉代码围栏。消息正文同时提供给模拟 `getChatMessages`。这是**单规则投影实验**，没有实现完整的 ST/Player Regex 排序、placement/depth、Macro、Markdown 和消息生命周期。

场景可以指定 `host.state`、`host.messages`、`host.initialized`，以及顺序执行的 `state`、`event`、`input`、`click` 步骤。缺失或禁用的点击目标记录为失败。`executed` 仅表示动作已派发，业务成功须另检查调用、错误和状态。

默认 MVU 全局已初始化；可用 `initialized: []` 测试等待。事件名称保持稳定，注册的回调会在明确触发后执行。模拟变量与消息只存在于该页面的测试内存，不提供持久化、候选恢复或完整 scope 语义。其他占位返回值与未实现调用必须结合 `lib/host.mjs` 解读。

## 输出与边界

记录包含原件/HTML/场景/宿主记录器/浏览器驱动和库的哈希、浏览器版本、观察窗口、加载与最终快照、分阶段调用、动作结果、错误、被阻断资源、未实现调用、等待中的全局初始化及日志溢出数。

- `observed` 只表示记录器就绪并取得日志，不表示页面业务初始化成功。
- 固定观察窗口默认 4500 ms，`--timeout` 可调整；延迟任务、未触发分支和缺资源路径仍可能漏测。
- 零调用不能证明纯静态；运行出错或等待也可能产生零调用。
- 宿主代理会改变能力检测与控制流。结果是给定模拟条件下的调用观察，不能据此证明完整接口面或成本比例。
- 页面通过虚拟 HTTPS URL 载入，CDP 仅提供指定页面和四个本地库，其他被拦截请求失败；CSP 限制连接、frame、worker、表单和外部脚本。保留 Chromium sandbox，使用独立临时 profile，不开放 `file://` 读取。该配置不是经过攻击验证的通用不可信程序隔离方案，也不代表资源阻断不影响调用覆盖。
- 外部模块、字体和图片未真实加载，注入的库版本未验证与作者版本一致。没有测试真实 Tavern Helper/MVU 初始化程序，也没有 Android/WebView 验收。
- 原素材、DOM 文本、生成资源、profile 和完整日志只写入忽略的 `build/`；公共文档使用中性样本编号与哈希。
