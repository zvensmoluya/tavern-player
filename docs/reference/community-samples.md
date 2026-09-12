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
| P-01 | 本轮原生玩法与关系分析实验使用的社区预设 | 本机生成的测试资产 `community-preset.json`，原始文件 SHA-256 见下方 |

C-05 曾同时被用于指代一个 JSON 原件（SHA-256 `68c9429e69a9c38d8e8b79cace03675c99ca48830ed25a49ac89ce61dda961a9`，见 [web-runtime 记录](../web-runtime.md)）。该 JSON 与 C-03 的 PNG 解出同一份卡数据，不是独立样本；正确编号以本表为准。HTML Surface 探针的历史编号 S-01/S-02/S-03 分别对应当前 C-05/C-06/C-07 的原件。复杂原件的 Player API 依赖核查见[样本依赖核查](sample-dependency-audit.md)。

P-01 原始文件 SHA-256：`2c3a24a50aec709ca754478f26244f5650b2614e3d6f9da131c21c9a354bfc73`。测试对输出上限等参数的调整继续记录在各实验文档中，不把运行时修改误称为原始参数。

早期文档已有的“社区 Preset 样本 A／B”等匿名称谓继续保留；没有明确来源证据时，不将其与本表编号合并。
