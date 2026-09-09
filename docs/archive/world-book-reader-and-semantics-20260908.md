# 世界书阅读与编排修正

> 归档说明（2026-09-09）：保留阶段设计、源码审计与验证证据；文中的“当前”“下一步”和版本号指记录当时，不作为现行产品决策或完整运行契约。现行入口见[文档导航](../README.md)、[产品边界](../product-direction.md)和[实现架构](../architecture.md)。

世界书继续属于角色卡，同时作为角色内容向玩家开放阅读。角色详情在开始对话按钮下提供“世界书”入口；列表按书展示全部条目，支持标题、关键词及正文搜索。点击条目可阅读完整原文并选择复制文字，包括停用条目和模板／HTML 字面内容。长正文分块呈现，不用摘要替代正文；阅读不执行模板、不修改启用状态。返回列表保留搜索和滚动位置。

## 运行修正

依据固定的 [SillyTavern 世界书源码](https://github.com/SillyTavern/SillyTavern/blob/8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8/public/scripts/world-info.js)核对 `WorldInfoBuffer`、`WorldInfoTimedEffects`、`checkWorldInfo` 和 inclusion group 处理，不移植上游全局状态或扩展系统。

- 角色字段按条目原始 `match_character_description`、`match_character_personality`、`match_character_depth_prompt`、`match_scenario`、`match_creator_notes` 开关参与扫描，缺省关闭；聊天仍按 scan depth 扫描。原生记忆的既有额外扫描输入单独保留。Persona 扫描仍在当前产品范围之外。
- `delay` 表示聊天消息数门槛。使用编排前当前选中分支的完整消息数，而非生成次数或被 Regex 隐藏后的消息数；首次命中在门槛之后即可激活。重新生成及历史截断自然沿相应历史判断。
- 每轮候选经过分组、概率和预算后才产生递归输入。分组落选内容不能在该轮触发其他条目；已入选的分组不能再被较后递归层的候选抢占。概率失败在同次生成中不重掷，sticky 条目不重掷概率并优先取得预算。
- 第一次普通条目预算溢出后，停止本书其余普通条目和后续递归；显式 `ignore_budget` 条目仍可入选并计入用量。保留 Player 的独立书本预算与 token 计数策略。
- 普通条目先展开 Macro，用同一份文字检查预算、推进递归，入选后执行 WORLD_INFO Regex。丢弃条目不提交其临时 Macro 写入。EJS 保留既有 Regex／Macro → 有界模板执行顺序及渲染预算；递归读取处理后的模板源文，不用 EJS 渲染输出触发递归或再次执行模板。
- 修正 `selectiveLogic` 的来源映射：`0=AND_ANY`、`1=NOT_ALL`、`2=NOT_ANY`、`3=AND_ALL`。原实现把 1 和 3 颠倒。

## 已保存内容

扫描开关与 `selectiveLogic` 从已保留的原始 extensions 读取，旧角色和会话快照也能使用；没有生成第二份扫描设置。旧归一化枚举不会覆盖明确的原始数值。手工构造且无原始扩展字段的条目仍使用其显式枚举值。

删除错误的 `delayRemaining`／`delayStartedTurn` 运行字段。仓库现有的忽略未知字段解码会跳过旧计数，保留 sticky、cooldown 等状态；不重写历史消息或重放旧请求，后续编排采用新规则。无需删除应用数据或重新模型适配。

## 验证与边界

针对性回归覆盖五种扫描开关、零扫描深度、四种附加关键词逻辑、消息门槛与历史恢复、分组跨递归隔离、概率失败不重掷、预算溢出与豁免、Macro 预算／递归、EJS 落选不执行及输出字面量。阅读界面验证入口可见、搜索、停用内容、多书同条目 ID、恢复与完整长正文。

本轮是明确问题的修正，不是完整 ST 差分验收。高级 JS 正则、ST 的消息分隔／发言者扫描格式、数字递归延迟层级及其他未选择的世界书能力仍按既有支持边界处理。sticky／cooldown 的现有代际检查点行为没有扩为新的消息计时模型。

脚本宿主支持范围保持为已选择的有界接口；任意远程依赖不是本项目待补的兼容目标。

回归测试中的普通世界书正则语义使用可注入的即时执行策略，避免线程调度干扰匹配断言；灾难回溯测试仍使用生产执行器，保留 250 ms 超时与两线程上限。MVU 历史编辑测试先断言界面状态，再等待仓库发布已保存状态后检查磁盘恢复，避免把界面结束忙碌误当成落盘完成。

执行命令：

```powershell
.\gradlew.bat -I tools/mvu-probe/build/worldbook-isolated.gradle --no-configuration-cache --no-watch-fs --no-parallel --max-workers=1 --continue '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' :content-core:test :conversation-core:test :app:testDebugUnitTest --tests '*WorldBookReaderScreenTest' --tests '*TavernPlayerAppTest' --tests '*NativeCompilationScreenTest' --tests '*ChatViewModelTest' --tests '*ChatScreenTest' --tests '*QuickJsEjsRuntimeTest' --tests '*CharacterAndConversationRepositoryTest' --tests '*NativeAdaptationRegressionTest' :app:assembleDebug :app:lintDebug
git diff --check
```

本机常规输出目录发生过 JAR 打包失败，首次隔离运行又发现 JVM 测试 classpath 缺少自身生产类。验证使用被忽略的本地 init 脚本，将各模块输出放到 `build/worldbook`，并令 Kotlin JVM 测试依赖和加载本轮构建的自身 JAR；项目构建配置没有因此改变。脚本的有效配置如下：

```groovy
gradle.beforeProject { project ->
    project.layout.buildDirectory.set(new File(project.projectDir, 'build/worldbook'))
    project.plugins.withId('org.jetbrains.kotlin.jvm') {
        project.tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
            dependsOn(project.tasks.named('jar'))
            classpath += project.files(project.tasks.named('jar').flatMap { it.archiveFile })
        }
    }
}
```

- `content-core:test`：63 项通过，2 项因未配置可选本地角色卡／Preset 样本跳过。
- `conversation-core:test`：124 项通过。
- 上述 8 个 app 测试类：73 项通过，2 项因未准备可选 C-04 原始样本／参考结果跳过。4 项新增阅读界面测试全部通过。
- `assembleDebug` 通过，APK 位于 `app/build/worldbook/outputs/apk/debug/app-debug.apk`。
- `lintDebug` 通过：0 error、13 warning，内容为构建／库版本更新建议、应用图标与既有 KTX 用法建议；报告位于 `app/build/worldbook/reports/lint-results-debug.html`。整条 Gradle 命令完成并返回 `BUILD SUCCESSFUL`。
- 未连接 Android 设备，也未安装模拟器；未执行真机／仪器测试。长正文滚动、系统文字选择、返回及活动重建仍需设备验证。没有调用真实模型。
