# 社区素材的文档编号

文档使用中性编号描述社区角色卡和预设，不直接写素材标题、作者名或包含原名的文件名。素材内人物使用“角色 A”“角色 B”等称谓；这些称谓仅在各样本内部有效。原件保持不变，通过开发夹具、原件哈希和实验记录追溯。

| 编号 | 用途 | 追溯依据 |
| --- | --- | --- |
| C-01 | 复杂玩法主样本 | [手工适配夹具](../../app/src/test/resources/native-adaptation/pressure-card-manual.json)中的 `sourceSha256` |
| C-02 | 复杂玩法复用与关系分析样本 | [手工适配夹具](../../app/src/test/resources/native-adaptation/second-pressure-manual.json)中的 `sourceSha256` |
| C-03 | 简单表单回归样本 | [手工适配夹具](../../app/src/test/resources/native-adaptation/doctor-manual.json)中的 `sourceSha256` |
| C-04 | 未参与编译器设计的状态与动态集合样本 | 原件 SHA-256：`fa7e8ec564887780b331d0da29f7966f58f3688d49e6faf587d2d80ae9aecefe`；见[自动适配实验](../archive/native-compilation.md) |
| C-05 | 动态界面与父页面输入回写样本 | 原件 SHA-256：`7df0b58b2a46ac9ae2169c45f715a58760ebdad63017c5a860222d808beabe32`；见 [HTML Surface 探针](../archive/html-surface-probe-20260909.md)中的 S-01 |
| C-06 | 开局选择与状态重读的复杂交互样本 | 原件 SHA-256：`8f24972a97e9cb357e105e5d7d101a7ec3b7ff5c6cc0f98a4024ac94a895583f`；见[HTML Surface 探针](../archive/html-surface-probe-20260909.md)中的 S-02 |
| C-07 | 状态栏与变量事件驱动的界面样本 | 原件 SHA-256：`0166ea69a6bdfa0e7559cc98e877d3d5b6b106bc12e1c712a45ba96585f359f0`；见[HTML Surface 探针](../archive/html-surface-probe-20260909.md)中的 S-03 |
| C-08 | 旧助手脚本格式与 MVU 状态初始化样本 | 原件 SHA-256：`5191b0bcb615e2abe1fa6fef20212e64d6df483f0f453dc929d4bf15d3ef07d4`；见[依赖核查](../archive/legacy-mvu-dependency-audit-20260912.md) |
| C-09 | MVU 处理后正文与跨轮状态栏样本 | 原件 SHA-256：`33285b1a5505a1d8200258483eb4f76c7d9a0dbd5b579943dbda285bc3c78c9e`；见[正文回写修复](../archive/mvu-processed-message-fix-20260912.md) |
| C-10 | MVU beta 初始化与 EJS 世界书引用 | 原件 SHA-256 `3fe1f8be2566ee5ba109fd9e00512554448d227cf2c50f53dc075b7ebd913c05` |
| C-11 | ERA 聊天变量、消息回滚与状态栏查询 | 原件 SHA-256 `eaa0451cfbc70b9f1f2d1fc239bc22e0d018ff2b61935ea0b840a322c8545a1b` |
| P-01 | 本轮原生玩法与关系分析实验使用的社区预设 | 本机生成的测试资产 `community-preset.json`，原始文件 SHA-256 见下方 |

C-05 曾同时被用于指代一个 JSON 原件（SHA-256 `68c9429e69a9c38d8e8b79cace03675c99ca48830ed25a49ac89ce61dda961a9`，见 [web-runtime 记录](../web-runtime.md)）。该 JSON 与 C-03 的 PNG 解出同一份卡数据，不是独立样本；正确编号以本表为准。HTML Surface 探针的历史编号 S-01/S-02/S-03 分别对应当前 C-05/C-06/C-07 的原件。复杂原件的 Player API 依赖核查见[样本依赖核查](sample-dependency-audit.md)。

P-01 原始文件 SHA-256：`2c3a24a50aec709ca754478f26244f5650b2614e3d6f9da131c21c9a354bfc73`。测试对输出上限等参数的调整继续记录在各实验文档中，不把运行时修改误称为原始参数。

早期文档已有的“社区 Preset 样本 A／B”等匿名称谓继续保留；没有明确来源证据时，不将其与本表编号合并。

## 2026-11-26 发送路径核查

**C-10。** 昨日原件回归覆盖导入、开场 MVU 初始化、旧式更新和恢复。首次发送另走 `PromptCompiler`，会执行 UID 22–25 的四个常驻 EJS 条目。用 Node 加载本机构建的 `app-assets/ejs/runtime.js`，以空变量和空历史分别求值四段原模板，四次均得到 `ReferenceError: getwi is not defined`。只求值 `<%= typeof getwi %>` 也返回 `undefined`。应用把引擎异常合并为用户看到的通用错误。

四段模板分别按变量选取十个阶段之一，调用 `await getwi(null, 条目标题)`。按标题逐项比对内嵌世界书，UID 22 的十个目标有九个存在，均关闭自动激活，缺少阶段 08。UID 23–25 的三十个目标均无同名条目。锁定版本的上游允许按标题读取停用条目，也会在适用时搜索其他启用的书；最终找不到时返回空串。修复验收需同时覆盖命中与缺失：单纯返回空串会让发送成功，却丢失原件已有的阶段正文。[上游条目查找](https://github.com/zonde306/ST-Prompt-Template/blob/d6f520d149aba146305b0b781ddd691d449c28d2/src/function/worldinfo.ts#L243-L278) · [上游 getwi 求值](https://github.com/zonde306/ST-Prompt-Template/blob/d6f520d149aba146305b0b781ddd691d449c28d2/src/function/ejs.ts#L303-L332)

修复后，C-10 原件回归增加了首次发送的 Prompt 编排：开场初始化后，四个模板均在真实 QuickJS 中执行，最终消息包含作者关闭自动激活的 UID 4 的正文。中性回归另覆盖缺失标题、玩家改写与手动停用，接口范围见[网页运行契约](../web-runtime.md)。

**C-11。** 按导入器优先级读取 PNG 的 `ccv3` 块。`TavernHelper_scripts` 有四份脚本，启用两份辅助脚本和一份标为 1.4.6 的内嵌 ERA。内嵌源码的 UTF-8 SHA-256 为 `a0768dd0c855dac07cc6e2da51abfdff733d8e2745dcec2f37ef392035b438ed`。另一个从上游 `master` 导入 ERA 的脚本已停用。用 Player 模块解析器检查启用的内嵌代码，仍有两项远端模块依赖，即 Pinia 与 opencc-js。默认开场是选择器占位，五个备用开场各含一个或多个 `<VariableInsert>` 初始化块。

ERA 在聊天变量中保存 `stat_data`、修改日志和选中消息的标识链。消息正文中的标识用于在候选切换或删除后恢复状态。样本的本地状态栏用 `era:getSnapshotAtMk` 请求某条消息的快照，并监听 `era:queryResult` 和 `era:writeDone`。Player 向后加载的脚本重放 `APP_READY`，注册未定义的事件时跳过该次注册。此版本引用的 `MESSAGE_DELETED`、`MESSAGE_EDITED`、`GENERATE_AFTER_DATA` 仍无对应 ST 接口。[ERA 上游架构说明](https://github.com/RockingSisyphus/ERA-EfficientRollbackArchitecture#readme) · [ST 事件注册与就绪重放](https://github.com/SillyTavern/SillyTavern/blob/8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8/public/lib/eventemitter.js) · [Player 事件定义](../../tools/web-runtime/src/host.mjs)

C-11 的世界书含 25 个 EJS 条目，其中 24 个启用。以空变量和空历史逐项求值，这 24 个模板都能返回。实际发送只执行当轮激活的条目。EJS 优先读取本轮所选的 MVU 检查点，没有 MVU 时读取同一检查点的聊天变量。

Android 模拟器实验采用上述原框架、中性开场、固定模型回复和调用公开查询接口的独立脚本。原框架的事件队列延迟读取末楼，用户楼与回复快速连续提交时曾把回复的消息标识记到错误的位置，导致余额变成空对象。Player 仅对上述源码哈希在生成结束、候选已保存时通知框架按当前历史同步。开场值为 10，回复 A 为 8，重新生成 B 为 15。从 B 查询开场得到 10，切回 A 得到 8。重建 Room 仓库和 WebView 后发送下一轮，三次生成请求中的世界书余额依次为 10、10、8。

时序回归在 A 首次写入余额 8 后、框架尚未报告完成时，连续点击网页中的「重新生成」两次。Player 收下一个操作，在当前候选的变量、消息标识和显示文字保存后开始生成 B。读档后立即发送也经过同一检查。实验核对了各候选画面中的 `{{ERA:player.coins}}`、持久正文中的原始占位符和下一轮 EJS 世界书值。这组中性候选测试只覆盖框架。

复现时先运行 `node tools/web-runtime/prepare-era-probe.mjs`。它按原件和依赖的哈希准备测试 APK 专用素材。构建并安装 debug 与 androidTest 包后，用 instrumentation 参数 `-e eraProbe 1` 单独运行 `BrowserLiveAndroidTest#eraOriginalTracksNeutralCandidatesAndRestoredPrompt`。模拟器运行期间所有模型回复和模块均来自本地测试输入。

完整 C-11 原卡另用 `BrowserLiveAndroidTest#originalOpeningRoutesAndRefreshesWithUnavailableImages` 验证。将上面记录哈希的原 PNG 复制到模拟器应用专属外部目录的 `files/C-11.png`，使用同一份模块夹具和 instrumentation 参数 `-e eraCardProbe 1`。测试在独立角色库和存档中点击原开场的第一个候选，待世界信息出现后切换到选项页，核对缺图占位的实际尺寸。随后本地生成器回复一次并更新地点，测试同时核对新楼层状态栏的文字和持久变量。图片下载器固定返回 HTTP 403，测试不访问模型服务。 Android 模拟器使用五份独立新存档连续通过此流程。
