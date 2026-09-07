# 只读状态绑定与模型编译精简

## 决定与影响范围

统一原生界面的读取接口，不统一所有卡的变量结构，也不把 MVU 变成必选依赖。MVU 卡复用自身的状态检查点；非 MVU 卡保留 Player 状态；纯文本卡不需要结构化状态。界面只负责展示，不额外生成、保存或推导业务事实。

本次涉及内容适配模型及校验、会话读取投影、当前/历史原生界面和模型准备协议。`NativeCompilationDraft` 升至 `native-compiler-7`，删除模型输出中的 `progressions`、`worldBookTextSelections` 及对应组装代码；旧编译响应不再兼容。手工 Native 适配中的 Player 阶段、原文选择、Setup 和显式选择仍有独立使用场景，保留其既有运行路径与测试。已安装适配和旧会话不会自动升级；重新准备后新建会话才能使用新绑定。

## 读取方式

`NativeStateReader` 是只读接口。`ConversationStateReader` 持有某个选中候选的完整 `ConversationRuntimeState`，按绑定路由到 Player 状态表或 MVU 的 `data`。未声明别名的 Player 状态键直接读取原状态表。绑定不物化为 `ConversationStateSnapshot`，不产生第二份 Prompt 状态投影，也不增加状态确认模型请求。

适配中的展示配置示例：

```json
{
  "stateBindings": [
    {"key":"inventory","source":"MVU","path":"/stat_data/物品栏","type":"RECORD"}
  ],
  "collections": [{
    "id":"inventory","title":"背包","stateKey":"inventory","shape":"OBJECT",
    "fields":[
      {"key":"name","label":"物品","entryKey":true},
      {"key":"quantity","label":"数量","path":"/数量"}
    ]
  }]
}
```

- 路径使用 RFC 6901 JSON Pointer，最多 512 字符、32 段；`~0` 和 `~1` 分别表示原字段名中的 `~` 和 `/`。空路径表示当前根值，不是缺失配置；集合行用 `path:""` 可展示对象字典中的字符串值，例如衣物名称。
- 绑定只含 `key/source/path/type`，没有初始值、回退值、表达式、通配符或写入动作。别名不能重复或覆盖 Player 状态键。MVU 来源需要已选择 MVU 程序；Player 路径与声明类型在初始状态上校验。
- 运行时再次检查类型；缺失、null 或类型不符显示“状态不可用”，不拿默认值伪造事实。MVU 动态路径在安装时不能凭结构校验证明语义正确，须用实际检查点验收。
- Status 和 Scene 读取标量。Collection 支持数组与对象字典，字段可读取直接 property、行内路径或对象 property name。投影行仅是临时展示，不保存另一份背包。
- 有效空集合与不可用来源分别显示空态和错误提示。Scene 的缺失状态不能意外命中空字符串资产映射。
- 摘要、当前详情和历史详情使用同一读取接口。历史详情包含 Status、Scene、Collection，读取该消息的后置检查点，不以当前值补历史。

MVU 继续负责 Schema 校验、默认值与更新，界面不能绕开原运行时直接改状态。原生表单仍是输入草稿；本次没有引入通用动作接口或表单写状态能力。

## 准备流程如何变轻

模型按卡的实际能力输出：

| 内容 | v7 职责 |
| --- | --- |
| MVU | 选择原 Schema 脚本，生成必要的只读路径绑定；本地拒绝重复状态、旧消息写入器和 Player 写入选择 |
| EJS | 选择原模板，保留原条件、循环和历史读取；不再重写成阶段表与原文分支 |
| 非 MVU 状态 | 保留必要的 Player 状态定义和标量协议映射，不自动套用 MVU |
| 原生展示和输入 | 生成字段标签、集合展示字段、源表单控件和原文草稿引用 |
| 普通世界书和正则 | 保留现有引擎行为，不逐条重新编译普通静态正文 |
| 不支持的宿主行为 | 明确说明缺口，不编造第二套业务逻辑 |

编译指令明确区分两个宿主：MVU 提供 Zod、lodash（含 `_.clamp`）、YAML 和立即执行的 `$(callback)` 启动包装；这不等于提供 DOM/jQuery。EJS 支持单个或数组匹配模式，但不暴露全局 lodash。原界面的 DOM/轮询由原生检查点观察替代，不能据此把已有可读状态误报为不支持。

评估 `targets` 指向模型自身返回的配置，例如 `/ejsSourceIds/0`；本地先验证来源并完成组装，再校验评估引用。模型不必推导安装后内部字段名。摘要和评估本身不能作为“已还原”的目标。

输入仍保留完整相关 JS、HTML 和 EJS 代码以判断宿主依赖和展示语义，不使用特定玩法识别器。元数据省略默认值；删除已无用途的 EJS 原文区间清单及内部区间索引，只用占位符保留代码之间的文本位置。完整原模板留在本地并按来源哈希安装。精简重点是减少逻辑重写、状态重复输出和无用途数据，而非承诺任意卡固定比例的 token 节省。

## 验证范围与实测记录

确定性覆盖包括路径转义与空根、数组下标、类型变化与缺失、别名冲突、来源缺失、对象/数组集合、历史隔离、持久化不增加 Player 状态，以及 MVU 卡拒绝第二个写入机制。真实 QuickJS 测试同时核对原样本的物品增删、数量变化和穿着读取；聊天事务覆盖候选切换、重新生成、历史编辑、重置与磁盘恢复。

真实样本 C-04 通过原件 SHA-256 `fa7e8ec564887780b331d0da29f7966f58f3688d49e6faf587d2d80ae9aecefe` 追溯。原件和模型响应只保存在本地忽略目录，不提交到本次改动。

实测明确区分模型是否发出更新协议与界面是否正确读取状态：某次自然语言物品请求只返回确认正文，没有机器更新块，MVU 和背包保持原检查点。播放器不根据“已记下”的正文重新推导物品。后续带明确原协议要求的请求用于验证真实生成、MVU 更新和原生展示链路；这不证明模型在任意对话中都会遵循协议。

### 已完成的验收

- 内容层：64 项，63 通过，1 项因未配置可选 ST 默认预设夹具跳过；会话核心 115 项全通过；应用专项 72 项全通过。合计 250 通过、1 跳过。
- 应用专项包括聊天事务 36、界面 17、编译服务 6、原生适配回归 3、QuickJS EJS 6、QuickJS MVU 4。界面检查额外覆盖无 Status 的历史背包、场景缺失值与长描述不覆盖字段标签。
- Debug APK、Android 测试 APK、`lintDebug` 和 `git diff --check` 通过。普通 APK 中没有本地样本、实时测试配置或 `.env`。
- Android 模拟器上 6 项相关检查通过：原样本 MVU、通用 MVU 检查点、EJS、两个编译/解析检查、既有非 MVU 原生界面。另有 1 项真实模型库存链路和 1 项保存对话的界面回放通过。物理设备的后台切换、系统回收与不同机型行为未验证。
- C-04：使用用户准备的独立编译连接重新生成适配，9 条路径在实际初始化快照中全部可读；4 份原始 EJS、背包/衣橱/穿着 3 个对象集合，Player 状态数为 0。实测模型为 `gpt-5.6-terra`，该次 Provider 计数为输入 25699、输出 2834，其中推理 1552 tokens；这些是单次观察，不是固定成本保证。
- C-04 Android：使用用户的聊天连接（`deepseek-v4-flash`）完成两次真实生成，新增物品数量 3 → 取用后数量 2；界面直接显示 2，上一条消息仍读取 3，磁盘恢复后相同。实际新增条目名取自 MVU 数据，不改写模型输出；两轮仅两次聊天请求，没有 Player 状态副本或独立确认调用。初次截图发现长描述挤占标签，修正后用该已保存对话进行无网络界面回放并核对数量和排版。
- C-03（SHA-256 `0d9f771474cab7f170f33700e9a0db6b87df96451a4da473c0cfa9f8b70e8c22`）：独立真实编译、安装、五项表单及空值草稿、实际聊天和重新加载通过；MVU、状态定义和状态适配器均为空。编译 Provider 计数为输入 6912、输出 1487，其中推理 1034 tokens。

C-04 编译和 C-03 编译/聊天的私有记录分别位于 `app/build/native-compilation-runs/1788792157792/` 与 `app/build/native-compilation-runs/1788792779037/`。Android 两轮记录和截图位于 `app/build/binding-live-1788792615555/`；`inventory-verified.png` 为保存对话的组件回放，不是新增模型请求。模型、省略更新块及第一次物品名称断言的失败记录仍保留在本地，未将它们算作通过。

模型响应测试暴露过评估目标使用内部安装字段名、遗漏宿主能力说明导致错误拒绝等问题；本次已修正契约并重新取得真实编译成功结果。编译端点出现过一次 HTTP 503 和一次连接失败，重试后恢复。凭据从 `.env` 读取；Android 测试只通过标准输入暂存到应用私有配置，加载后即删除，连接和密钥只保留在测试内存中，不打包或提交。

### 实际验证命令

Windows 原构建目录出现文件占用与一次 Lint 分析器异常，后续使用忽略目录内的本地 init 脚本隔离输出，未删除其他构建进程的缓存。`tools/mvu-probe/build/bindings-isolated.gradle` 的内容为：

```groovy
gradle.beforeProject { project ->
    project.layout.buildDirectory.set(new File(project.projectDir, 'build/native-bindings'))
}
```

以下命令已执行；最终应用重新验证包含长描述排版修正，核心实现自首次通过后未再修改：

```powershell
.\gradlew.bat -I tools/mvu-probe/build/bindings-isolated.gradle --no-configuration-cache --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' :content-core:test :conversation-core:test :app:testDebugUnitTest --tests '*ChatViewModelTest' --tests '*ChatScreenTest' --tests '*NativeCompilationServiceTest' --tests '*NativeAdaptationRegressionTest' --tests '*QuickJsEjsRuntimeTest' --tests '*QuickJsMvuRuntimeTest'
.\gradlew.bat -I tools/mvu-probe/build/bindings-isolated.gradle --no-configuration-cache --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' :app:testDebugUnitTest --tests '*ChatScreenTest' --tests '*ChatViewModelTest' --tests '*NativeCompilationServiceTest' --tests '*NativeAdaptationRegressionTest' --tests '*QuickJsEjsRuntimeTest' --tests '*QuickJsMvuRuntimeTest' :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

真实编译通过 `TAVERN_COMPILER_LIVE=1` 和 `NativeCompilationLiveTest` 显式运行；C-04 同时设置上述源哈希，C-03 不设置。使用相同隔离参数执行 `:app:testDebugUnitTest --tests '*NativeCompilationLiveTest'`。不使用手工适配答案，C-04 最终运行也没有使用响应回放。

Android 实际运行了 `AndroidJUnitRunner` 的 `QuickJsMvuAndroidTest`、`QuickJsEjsAndroidTest`、`NativeCompilationAndroidTest`、`PressureCardNativeAndroidTest`。实时测试通过 `bindingLive=1` 选择 `NativeBindingLiveAndroidTest#compiledOriginalCardUpdatesOneStateAndRendersInventoryOnAndroid`；界面回放通过 `bindingReplay=1` 选择 `#savedRealConversationRendersBoundViewsWithoutNetwork`。实时模式需预先向应用私有目录提供 `binding-live-adaptation.json` 及只含 `baseUrl/apiKey/protocol/model` 的 `binding-live-config.json`；配置不属于测试资产。

测试报告位于各模块 `build/native-bindings/`，可安装 APK 位于 `app/build/native-bindings/outputs/apk/debug/app-debug.apk`。
