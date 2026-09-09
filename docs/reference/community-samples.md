# 社区素材的文档编号

文档使用中性编号描述社区角色卡和预设，不直接写素材标题、作者名或包含原名的文件名。素材内人物使用“角色 A”“角色 B”等称谓；这些称谓仅在各样本内部有效。原件保持不变，通过开发夹具、原件哈希和实验记录追溯。

| 编号 | 用途 | 追溯依据 |
| --- | --- | --- |
| C-01 | 复杂玩法主样本 | [手工适配夹具](../../app/src/test/resources/native-adaptation/pressure-card-manual.json)中的 `sourceSha256` |
| C-02 | 复杂玩法复用与关系分析样本 | [手工适配夹具](../../app/src/test/resources/native-adaptation/second-pressure-manual.json)中的 `sourceSha256` |
| C-03 | 简单表单回归样本 | [手工适配夹具](../../app/src/test/resources/native-adaptation/doctor-manual.json)中的 `sourceSha256` |
| C-04 | 未参与编译器设计的状态与动态集合样本 | 原件 SHA-256：`fa7e8ec564887780b331d0da29f7966f58f3688d49e6faf587d2d80ae9aecefe`；见[自动适配实验](../archive/native-compilation.md) |
| P-01 | 本轮原生玩法与关系分析实验使用的社区预设 | 本机生成的测试资产 `community-preset.json`，原始文件 SHA-256 见下方 |

P-01 原始文件 SHA-256：`2c3a24a50aec709ca754478f26244f5650b2614e3d6f9da131c21c9a354bfc73`。测试对输出上限等参数的调整继续记录在各实验文档中，不把运行时修改误称为原始参数。

早期文档已有的“社区 Preset 样本 A／B”等匿名称谓继续保留；没有明确来源证据时，不将其与本表编号合并。
