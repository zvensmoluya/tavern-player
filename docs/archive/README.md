# 历史文档归档

整理于 2026-09-09。这里保存旧方案、已吸收的实现记录、样本审计和验证证据。归档不表示相关代码被删除、记录里的实验失效或所有问题已经解决；它表示这些文件不再承担“当前方向 / 完整实现契约”的入口职责。

当前产品选择见[产品边界](../product-direction.md)，代码现状见[实现架构](../architecture.md)，下一步兼容主路线见[HTML Surface 讨论](../html-surface-discussion-20260909.md)。返回[文档导航](../README.md)。

## 使用约定

- 保留原文的阶段限定、样本哈希、失败记录与实际验证命令。文中的“当前”“下一步”均按记录当时理解，不据此覆盖后续明确决定。
- `adaptation-runtime-v1.md` 的早期固定能力列表不是当前完整契约；已实现的 Script Surface 与公共运行时从实现架构进入，再按需查阅对应记录。
- 旧产品文档的完整快照保留在下方，稳定边界已整理回现行产品文档。历史文件不继续追加新的当前规范；修正文献错误可保留说明，新的验证必须说明版本和范围。
- 当前 HTML Surface 文档保留用户最新修订，只有指向已移动文件的链接随归档更新。

## 旧产品决策与方案探索

- [归档前的产品决策完整快照（截至 2026-09-09）](product-decisions-through-20260909.md)
- [Tavern Player 内容兼容与适配理念](CONTENT_ADAPTATION_VISION.md)
- [Tavern Player Native Adaptation 设计原则](Tavern%20Player%20Native%20Adaptation%20设计原则0903.md)
- [Tavern Player Native Domain Operations 设计草案](Tavern%20Player%20Native%20Domain%20Operations%20设计草案.md)
- [原生适配复盘：暂停扩建，重新审视表达与执行边界](native-adaptation-retrospective-20260907.md)
- [角色卡程序与界面适配：路线、算法和验证研究](native-adaptation-research-20260908.md)
- [原生适配方案研究：界面投影、JS 行为与宿主接入](native-adaptation-solution-study-20260908.md)

## 既有能力的阶段契约与接入记录

- [Native 内容适配边界 v1](adaptation-runtime-v1.md)
- [JS 动态原生 Surface：首轮实现](native-script-surfaces-20260908.md)
- [只读状态绑定与模型编译精简](native-state-bindings-20260907.md)
- [原 MVU 加载与开局流程保留](native-opening-workflow-20260908.md)
- [QuickJS MVU 宿主](mvu-quickjs-integration-20260907.md)
- [MVU 聊天接入](mvu-chat-integration-20260907.md)
- [EJS 提示词执行接入](ejs-quickjs-integration-20260907.md)
- [MVU Schema 导出声明加载修复](mvu-schema-export-fix-20260908.md)
- [助手容器读取与世界书状态展开](helper-world-book-state-read-20260909.md)
- [角色图片资源](character-image-resources-20260909.md)
- [世界书阅读与编排修正](world-book-reader-and-semantics-20260908.md)

## 样本审计、实验与设备验证

- [模拟器聊天实测与修复（2026-09-12）](device-chat-verification-20260912.md)
- [角色库修复收尾与实测续接（2026-09-12）](library-startup-resume-20260912.md)
- [设备与真实 Provider 验证（2026-09-10）](device-live-verification-20260910.md)
- [社区 Preset 真实链路验收](community-preset-live-test.md)
- [MVU 公共变量能力接入实验](mvu-integration-probe-20260907.md)
- [导入期自动适配实验](native-compilation.md)
- [DeepSeek 复杂卡编译与 reasoning 记录](native-deepseek-complex-card-experiment-20260908.md)
- [Native 玩法还原验收](native-gameplay-fidelity-audit.md)
- [Native 玩法实践与实测记录（2026-09-05）](native-gameplay-verification-20260905.md)
- [复杂样本 C-02：原生玩法复用验收](native-second-card-fidelity-audit.md)
- [原生关系分析实验（2026-09-05）](native-memory-experiment-20260905.md)
- [当前编译产物的 Android 实测](native-surface-device-verification-20260908.md)
- [扩展依赖与原生图像能力初查](native-image-dependencies-research-20260908.md)
- [世界书运行语义：差异分类与待办](world-book-semantics-audit-20260907.md)

- [HTML Surface 探针复核](html-surface-probe-20260909.md)：阶段调用观察，已停止扩建；不作为完整宿主契约。
