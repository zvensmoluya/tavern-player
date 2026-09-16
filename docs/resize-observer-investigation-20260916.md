# C-07 网页尺寸通知调查

用户报告 Android 网页消息区显示 `ResizeObserver loop completed with undelivered notifications.`，同时页面仍有内容。样本按 [社区编号](reference/community-samples.md) 的 C-07 追溯，原件 SHA-256 为 `0166ea69a6bdfa0e7559cc98e877d3d5b6b106bc12e1c712a45ba96585f359f0`。

## 已确认

- 重新按哈希读取本机原件：数据树没有 `<%` 或 `ResizeObserver` 字面引用；展示 Regex 的 HTML 没有外链 script。历史调查确认 MVU／酒馆助手依赖，不能据此报错判定提示词模板缺失或框架版本冲突。该扫描不覆盖远程模块内部。
- `tools/web-runtime/src/parent.mjs` 在作者窗口安装 ResizeObserver，监听 body 后同步测量并写入 iframe 高度，每次测量都上报外层高度。外壳 `shell.mjs` 收到通知后再次写高度，没有相同高度去重。
- 作者窗口的 error 事件无分类地进入 `notify('error', ...)`，将文字插入父页面 `#error` 并再次测量；外壳还会显示 notice。这是会影响布局的诊断路径。
- `BrowserSession.onConsoleMessage` 将所有 ERROR 转交 onFailure；`WebMessageView` 因此显示故障文字和重试按钮。该回调本身不释放 WebView，也不设置 BrowserSession 的 failed 状态；不能把提示出现说成页面已经停止。
- C-07 的 body 使用 min-height:100vh，内部手机容器使用 calc(100vh - 6px)，另有最大高度和内部滚动。当前兼容处理仅折算 min-height 的 vh，其他 vh 仍以 iframe 视口为依据。存在高度反馈的条件，但尚未证明这是手机这次报错的具体触发点。

## 实际验证及局限

在 `tools/web-runtime` 执行 `node build.mjs`，随后执行 `node --test build/resize-audit.mjs`：1 项通过、0 跳过。调查脚本保存在忽略目录，基于现有 complex 浏览器测试，仅选择 C-07 原件，增加控制台 ERROR、pageerror 和各窗口 ResizeObserver error 采集，并切换 320、360、393、420、600、800 六种宽度与 430、760、500、760 高度序列。

原件展示页初始化及既有交互检查通过，采集到的上述错误均为零，未复现手机错误。测试使用桌面 Edge、模拟宿主与 MVU 快照，装饰资源返回空内容；没有运行完整远程后台程序、真实模型对话或 Android WebView。第一次调查脚本因移动路径后报告输出地址未同步而失败，修正调查脚本地址后重跑通过；不是生产运行失败。

## 建议修复范围

1. 两端统一识别浏览器的已知尺寸循环通知，将其作为可恢复诊断记录，不插入影响布局的错误块，也不因此显示加载失败与重试。保留真正脚本异常、资源失败、状态保存失败的报告，不能笼统吞掉所有 error。
2. 将 observer 引发的尺寸同步合并到动画帧，在高度确实变化时才写 DOM、上报外层；销毁时 disconnect observer 并取消待执行任务。动画帧调度只能减少同帧循环，不能保证任意作者布局最终收敛。
3. 增加中性浏览器回归：真实 ResizeObserver 通知、动态内容增高和缩短、视口变化、相同高度去重及销毁；同时验证普通脚本错误仍可见。现有 C-07 测试仅覆盖初始化和局部交互，不能代替这些检查。
4. Android 真机复核该会话的加载、键盘开合、切后台恢复及消息更新，记录 WebView 版本和尺寸变化。若仍持续振荡，再依据证据处理 vh 与内容测高的反馈，不先全局改写作者 CSS。

以上为修复前调查结论。

## 后续修复

已按上述前两项调整生产代码：网页侧对两条已知浏览器尺寸通知保留 warning，Android 控制台入口精确分类并保留日志；其他异常仍沿原路径报告。ResizeObserver 回调只安排下一动画帧，同帧合并，内外高度写入及尺寸上报去重；销毁时断开监听并取消任务。未扩展作者 CSS 的 vh 改写范围。

执行 `node build.mjs`、`npm test`、`npm run test:browser`，分别完成构建、49 项单元测试和 19 项 Edge 浏览器测试，全部通过且无跳过。新增浏览器用例实际触发 ResizeObserver 循环保护，验证不会插入错误文字，同时验证内容增高／缩短、相同高度不上报、普通脚本异常仍可见；单元测试覆盖合并调度及销毁取消。C-07 原件初始化和交互也包含在本次通过的浏览器测试中。

另执行 `./gradlew.bat :app:testDebugUnitTest --tests '*BrowserDiagnosticsTest'`，构建及 Android 通知分类定向单元测试通过；`git diff --check` 通过。

Android 真机上的原会话、键盘与生命周期复验仍未完成；桌面验证不能证明截图的具体触发链已在该设备消失。运行资源变动仍遵守既有指纹校验，持有旧指纹的会话需要按现有提示新建对话，不在本次改动中放宽校验。
