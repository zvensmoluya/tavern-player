# EJS 提示词执行接入

本轮让已安装适配明确选择的世界书 EJS 原文在 QuickJS 中执行。MVU 继续维护变量；EJS 读取当前检查点与聊天历史并返回提示文字。没有逐卡重写条件逻辑，也没有增加第二套状态存储。编译契约为 `native-compiler-6`。

## 安装与启用

模型通过 `NativeCompilationDraft.ejsSourceIds` 选择启用的世界书程序来源。本地从完整原文生成 `NativeAdaptation.ejsTemplates`，每项保存 `bookId`、`entryId` 和 `sourceContentSha256`。模板始终取自不可变角色快照；安装和发送均检查来源、唯一性、大小及哈希。最多 32 项，每项原文最多 256000 UTF-16 单元。

同一条目不能同时声明 EJS 执行和旧 `worldBookTextSelections`。新适配应直接选择完整模板，并删除该条目原有的阶段选文替代；其余仍在使用的状态和展示配置不因此自动迁移。安装校验不证明模板语义已通过审计。

普通导入仍不执行脚本。已有适配没有这些新引用，需要重新“准备游玩”并新建会话才能使用新模板；已保存对话继续使用原有快照。

## 发送事务

1. ChatViewModel 捕获当前选中分支的历史与 MVU 检查点；重生成使用目标回复之前的检查点。
2. PromptCompiler 先完成历史的 Prompt Regex/Macro 投影。世界书沿用既有启用覆盖、关键词、递归扫描、分组、概率和计时规则。
3. 对匹配并通过分组选择的 EJS 条目，先在临时 Macro 事务中处理 WORLD_INFO Regex/Macro，再执行 EJS。若这些处理改变了 EJS 代码块，拒绝执行，防止名字或变量插值生成新代码。代码块内部的 Macro 暂不支持。
4. 世界书预算按渲染结果计算；预算丢弃的条目不提交其 Macro 写入。递归触发仍扫描源条目，执行结果不重新触发世界书。
5. 渲染文字通过临时占位符穿过后续 Prompt 模板，在主上下文计数前一次性按字面量放回；返回值中的 Macro/EJS 标记不会再次解释。主预算及 Provider 最终计数都覆盖实际输出。
6. 缺少求值结果时，同步编排返回到应用的挂起执行边界。每轮生成缓存相同模板、变量和历史的结果，重新编排及 Provider 裁剪复用该结果；下一轮、其他分支和其他卡不共享缓存。任何临时编排均不提交运行状态。

执行错误显示 `EJS_EVALUATION_FAILED` 及条目来源，不将出错条目的未执行模板回退发送，不吞掉失败后继续生成。取消沿原生成任务传播。EJS 不参与开场初始化、流式片段或回复后的变量提交；这些仍由已有 MVU 事务负责。

## 引擎与宿主范围

EJS 固定为 npm `3.1.10`，使用客户端构建；lodash 固定为已有 `4.18.1`，仅为路径读取提供依赖。框架、原始许可证和 bundle SHA-256 随普通 APK 的 `assets/ejs/` 打包，运行时不下载脚本。每次模板求值在专用线程建立并释放独立 QuickJS，限制为 32 MiB 引擎内存、1 MiB 栈、默认 2 秒求值时间，JSON 输入最多 2 MiB 字符、输出最多 262144 字符。进程内限制不等于独立进程隔离。

支持的只读接口：

| 接口 | Player 数据与语义 |
| --- | --- |
| `variables` | 完整 MVU 检查点的只读 JSON 副本，包括 `stat_data`；没有检查点时为空对象 |
| `getvar(key, options)` | lodash 路径读取；支持 `defaults`、`clone` 和 `cache/message` scope；不支持全局、本地或按另一消息查询的 scope |
| `getChatMessage(index, role)` | 当前分支中经过 Prompt 投影的历史，支持负索引 |
| `getChatMessages(count[, role])` / `(start, end[, role])` | 锁定上游实现的过滤和切片语义 |
| `matchChatMessages(pattern, options)` | 同一消息内匹配任意或全部模式；字符串按 JavaScript 正则匹配 |
| `print`、标准 JavaScript、`async/await` | 原始 EJS 控制、循环、局部计算和文字输出 |

不提供持久写入、`include`、DOM、网络、文件、Android 对象、完整 Tavern Helper、扩展注入钩子或跨条目的共享 JS 变量。`<%=` 在提示词模式下使用原扩展的直接文字输出语义，不做 HTML 转义。原生状态栏仍需独立映射 MVU 数据。

## 上游依据与已知语义

参照固定为 ST-Prompt-Template 提交 `d6f520d149aba146305b0b781ddd691d449c28d2`：

- [聊天读取实现](https://github.com/zonde306/ST-Prompt-Template/blob/d6f520d149aba146305b0b781ddd691d449c28d2/src/function/chat.ts)
- [变量读取实现](https://github.com/zonde306/ST-Prompt-Template/blob/d6f520d149aba146305b0b781ddd691d449c28d2/src/function/variables.ts)
- [EJS 调用及转义](https://github.com/zonde306/ST-Prompt-Template/blob/d6f520d149aba146305b0b781ddd691d449c28d2/src/function/ejs.ts)
- [原扩展处理流程](https://github.com/zonde306/ST-Prompt-Template/blob/d6f520d149aba146305b0b781ddd691d449c28d2/docs/features.md)

上游一般在酒馆组装 Prompt 后处理模板；Player 当前支持的是独立、完整的世界书模板，在条目 Regex/Macro 后执行，并提前取得结果用于渲染预算。跨条目程序作用域、最终 Prompt 查询和扩展注入流程不在兼容范围。原卡未锁定其历史扩展版本，不能据此倒推作者当时的运行结果。

特别是此提交中，`getChatMessages(-2, 0, 'user')` 是 `.slice(-2, 0)`，结果为空，并非“最近两条用户消息”；`matchChatMessages` 在未指定 `end` 时，传入的第三个 `role` 参数也不会被对应重载使用。C-04 使用了前一种写法。本轮保留这些可观察行为，不改原卡、不猜测修复，并以中性用例单独记录。

## 验证与复现

`tools/mvu-probe/ejs-probe-card.mjs` 只接受 C-04 原件哈希，按提交及文件哈希获取参照源码，保留原聊天/变量函数体与参照 EJS 引擎；浏览器消息投影依赖在对照实验中使用恒等实现，输入为已投影文字。原件、参照源码及预期输出全部留在忽略的 `build/`，不进入普通 APK。

```powershell
npm --prefix tools/mvu-probe run build
npm --prefix tools/mvu-probe test
node tools/mvu-probe/ejs-probe-card.mjs
npm --prefix tools/mvu-probe audit --registry=https://registry.npmjs.org --json
.\gradlew.bat --no-configuration-cache --max-workers=2 :content-core:test :conversation-core:test :app:testDebugUnitTest --tests '*QuickJsEjsRuntimeTest' --tests '*ChatViewModelTest' --tests '*QuickJsMvuRuntimeTest' --tests '*NativeCompilationServiceTest' :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Node 对照覆盖四段原始模板的 50 组输入，以及 9 组独立宿主边界；相同原模板输入和预期结果供真实 QuickJS JVM 测试读取。核心测试覆盖来源改变、重复引用、旧选文冲突、关闭/未触发条目、Regex/Macro 顺序、渲染预算、输出字面量和宏生成代码拒绝。聊天测试使用真实 MVU/EJS，检查候选切换、重生成、编辑截断、磁盘恢复与重置。

Android 共享契约测试已提供。真机仍需检查实际耗时、取消及前后台/进程恢复行为；本地构建不替代设备验证或真实模型的叙事遵循验收。

本轮本地结果：

- 内容层 62 项：61 通过，1 项因未配置本地 ST 默认预设夹具跳过；会话核心 111 项全部通过。
- 应用专项 52 项全部通过：EJS 6 项（包括上述 50 组原模板在真实 QuickJS 中的对照和原卡实际 Prompt）、聊天 36 项、MVU 4 项、编译服务 6 项。
- Node 自动测试 7 项通过；原模板 50 组及独立宿主 9 组对照一致。npm 官方审计报告 0 项已知漏洞。
- Debug APK、Android 测试 APK 与 `lintDebug` 通过。检查普通 APK 中 EJS bundle 为 27947 字节，字节与构建产物和 provenance 哈希一致；普通 APK 不含 C-04 或中性测试夹具。
- `git diff --check` 通过。未执行设备测试、实际聊天模型请求或自动适配模型请求；编译链路使用本地草稿及真实原件验证。

首次构建遇到 Maven TLS 握手失败，重试后成功；新增测试中的构造参数和空白预期问题已修正。没有改动全局 Gradle 配置或已有用户内容。
