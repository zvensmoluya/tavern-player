# C-08 旧格式 MVU 依赖核查（2026-09-12）

样本原件 SHA-256：`5191b0bcb615e2abe1fa6fef20212e64d6df483f0f453dc929d4bf15d3ef07d4`。
基线为 `1636b89`。先核查依赖并执行局部实验，随后补齐生产接入；两个阶段的证据分别记录如下。

## 设备复现与原因

原 PNG 经模拟器系统文件选择器导入，用网页模式新建对话；Android 15 / API 35 的作者页面显示
`无法加载状态数据 (characterData missing)`。在作者页面执行其使用的消息读取接口，开场消息的 `data` 为 `{}`。

卡内 `extensions.TavernHelper_scripts` 是旧格式数组，元素为 `{type: "script", value: {...}}`，
包含一个启用的 MVU 加载脚本；原世界书另有 `[InitVar]` 条目。修复前 `BrowserProgramReader` 仅读取
`extensions.tavern_helper`，导致此脚本未进入程序准备流程。卡所用加载地址还带 `@master`，
修复前 `programs.mjs` 的 MVU 身份匹配不接受该形式。这是已确定的两个接入缺口。

## 原始依赖对照

- 卡中地址：`https://testingcf.jsdelivr.net/gh/MagicalAstrogy/MagVarUpdate@master/artifact/bundle.js`。
- 此次请求 HTTP 200，307765 字节，SHA-256 `be149c7fb531c9f8755bde722931b3389ac8470218248fec12440d2b2a9b9c89`。
- `git ls-remote` 返回当时的 HEAD、master 和 beta 均指向 `61010dab47bc3a08a1b626320bf7fc8c9573eca4`，
  与项目 [MVU 锁](../../tools/mvu-probe/upstream-lock.json) 相同。
- 下载[该固定提交的 bundle](https://raw.githubusercontent.com/MagicalAstrogy/MagVarUpdate/61010dab47bc3a08a1b626320bf7fc8c9573eca4/artifact/bundle.js)，
  字节数及 SHA-256 与卡中 CDN 地址返回值一致。这里比较的是两份上游发布文件，不是 Player 自行打包的运行资产。
- [酒馆助手角色设置源码](https://github.com/N0VI028/JS-Slash-Runner/blob/3de7ef981f378517779eb32ab5ecb82c033e4db4/src/store/settings/character.ts)
  明确迁移 `TavernHelper_scripts` 和 `TavernHelper_characterScriptVariables`；只有新容器不存在时才采用旧数据。
  因此该字段有真实的上游历史格式依据，并非卡私自使用的另一种 MVU 框架。

`@master` 是可变引用；此次文件相同不能作为未来所有分支、标签及版本等价的证明。

## 现有底层局部验证

直接在 Node VM 中装载现有 `tools/mvu-probe/build/app-assets/mvu/runtime.js`，输入原卡世界书及开场，
不提供额外 Schema，也不修改原卡。使用原卡说明中的旧式命令 `_.set('世界.日期', 1, 2)`，
放入确定性的中性回复与 `UpdateVariable` 块。

结果：初始化得到四个顶层状态字段；日期由 1 变为 2；初始化和更新均无 error 诊断；原始检查点保持为 1；
从序列化后的同一检查点重复更新得到相同结果。实验脚本还以断言核对上述结果。

本机忽略目录 `app/build/live-verification-20260912/` 内的 `audit-dependencies.mjs` 和
`probe-state-runtime.mjs` 分别执行依赖对照与运行验证，摘要保存在 `dependencies/report.json` 和
`dependencies/runtime-report.json`。下载的上游源码、bundle 与原卡数据留在忽略目录，不进入版本库。

依赖核查阶段证明现有 MVU 引擎可以处理本样本的初始化和一次旧式更新；当时还没有验证 Android 导入后的完整链路、
状态面板、真实模型输出、EJS 分支及候选切换，也没有运行 Gradle、实体设备或新增模型请求。

## 已完成接入

按上游格式补齐旧助手脚本容器读取，覆盖脚本包装项、目录和直接脚本项；保持新格式优先，保留来源位置、
启用状态、脚本数据和身份。旧脚本默认停用，目录本身没有启停语义。旧角色变量字段也会读取。
将已经核实的 `@master` 地址形式交给现有固定版本宿主，不把任意版本地址一概认作等价；混合副作用仍拒绝拆分执行。

原卡不改写、不重新导入，只在覆盖安装后新建网页对话，取得完整的 MVU 开场检查点。
之前缺失程序/检查点的旧测试对话保留，本轮没有为旧历史补造状态。

用户进一步明确要求保留充分模型预算，因此内置默认回复上限由 1024 提高为 32768 tokens，模拟器实际使用的
预设也通过界面改为 32768 并保存。已有保存的预设不会被代码基线更新强制覆盖，导入预设的自定义预算保持其原意。

## 模拟器与真实 Provider 验证

Android 15 / API 35，覆盖安装，使用 DeepSeek `deepseek-flash` / Responses，内置默认 Prompt。

| 检查 | 结果 |
| --- | --- |
| 原卡开场 | `getChatMessages(0).data` 包含 `stat_data` 等 MVU 字段；原作者面板显示 2025 年 9 月 1 日及三个初始数值，无状态加载错误 |
| 旧式更新 | 两次短消息生成完整回复，状态日期依次从 1 → 2 → 3；包含占位标签的回复装载原状态面板 |
| 充足预算的正常回复 | 32768 输出上限下，用正常的中性早餐场景请求推进一天；回复 COMPLETE，日期 3 → 4，面板显示相同变化 |
| 独立候选 | 将该用户消息的“一天”改为“两天”，使用“保存文字”保留原回复，再点重新生成；新候选 COMPLETE，日期从前一轮的 3 → 5，不在上一候选的 4 上累加 |
| 候选切换 | 两个候选分别为第 4 日、第 5 日；上一条/下一条切换后，原作者面板与落盘 MVU 状态一致 |
| 覆盖安装及强停恢复 | 安装最终 APK、force-stop 并重开原对话；7 条消息、全部 turns、runtimeState 和草稿与保存记录相同；仍选中第 2 个候选，日期为 5，面板无加载错误 |

两份正常场景候选的存储正文分别为 569、1014 字符。原卡面板读取 `display_data`，日期可显示
`3->5 (说明)`；这是作者原格式，不是 Player 额外拼接。更早楼层的面板继续读取自身候选状态，
超出原卡 Regex 的 `maxDepth=2` 后按原规则移除。

早期仍为 1024 上限时，同一测试对话出现一次无正文的生成失败及一次在变量更新块中途截断的 ERROR 回复；
MVU 检查点未推进。没有把这些请求算作成功，也没有在本轮修复 Responses 提前结束原因丢失。
短消息验证只作为局部诊断；最终正常场景和候选验证均使用 32768 上限。

本机忽略目录保存 `c08-previous-panels.jsonl`、`c08-next-panels.jsonl`、对应落盘记录及
`c08-recovery-summary.json`。下载文件、会话及测试产物不提交。结束时移除 DevTools 端口转发。

## 自动验证

实际通过：

```powershell
npm --prefix tools/web-runtime test
.\gradlew.bat :content-core:test --tests '*BrowserProgramReaderTest' :app:testDebugUnitTest --tests '*BrowserProgramPreparerTest' :app:assembleDebug
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :content-core:test --tests '*BrowserProgramReaderTest' --tests '*PresetImporterTest' :app:testDebugUnitTest --tests '*BrowserProgramPreparerTest' --tests '*PresetRepositoryTest' --tests '*PresetViewModelTest' --tests '*PresetScreenTest' :app:assembleDebug
git diff --check
```

网页契约 40 项通过，无跳过。最终 Kotlin/应用 6 个测试类共 28 项，26 项通过、2 项跳过、无失败或错误。
跳过原有四复杂原件合并测试（可选原件不齐）和 ST Default.json 导入测试（未配置该夹具）；新增 C-08 原件测试实际执行，
覆盖真实导入、QuickJS 初始化、旧式更新、检查点序列化恢复与后续 EJS Prompt 编排。

最终 APK SHA-256：`80761ca779326b5a5a64e37ba7b8329d6570584c2c92594aa65fbba807b8d437`。
未执行完整测试集、lint、完整 instrumentation 套件、实体手机及整卡所有剧情/EJS 分支验收。
本次修改尚未提交或推送。
