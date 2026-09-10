# 设备与真实 Provider 验证记录（2026-09-10）

本轮在已连接的 Android 模拟器上用 `am instrument` 实际执行了仓库内的仪器化测试，并用本地测试凭据发起了真实模型请求。记录实际执行结果与失败范围，不代表整卡行为结论。

夹具来自本机 `source/`，可能是临时的；本文只把夹具当作本次运行的输入，不据此得出关于夹具本身的设计结论。

## 环境

| 项 | 值 |
| --- | --- |
| 设备 | `emulator-5554`，Android 15 / API 35，`x86_64` |
| adb | 35.0.2（`platform-tools`） |
| 应用包 | `io.github.zvensmoluya.tavernplayer`（debug，debuggable） |
| 测试包 | `io.github.zvensmoluya.tavernplayer.test` |
| 构建 | `:app:assembleDebug :app:assembleDebugAndroidTest`，BUILD SUCCESSFUL in 33s |
| 本机网络 | FlClash 代理，宿主 DNS 为 `198.18.0.2`（Clash 系默认 fake-ip 池 `198.18.0.0/15`）；模拟器继承同一解析结果 |

androidTest 资产由 `app/build.gradle.kts` 的 `preparePressureCardAndroidTestAsset` 从本机 `source/` 注入；已确认 androidTest APK 内含 C-01 / C-02 / C-03 三张卡的图与 P-01 preset。

## 执行结果

| 范围 | 结果 |
| --- | --- |
| 设备离线套件（全量 `am instrument -w -r`） | 30 个测试启动，16 通过，0 失败，14 个因缺 opt-in 参数被 assume 跳过 |
| 主机单元测试（4 模块） | BUILD SUCCESSFUL，483 个测试，0 失败，8 跳过 |
| 社区卡验收（`-DcommunityCard`，绝对路径） | 1 个测试通过 |
| BrowserLive 开场阶段 C-03 | 通过（0 次请求，无运行时通知，1 article / 2 iframe） |
| BrowserLive 开场阶段 C-02 | 失败：`BL_PAGE_METRICS`，页面通知"网页资源不可用：图片下载失败，请检查网络后重试" |
| BrowserLive 真实对话 C-03 | 失败：`BL_ASSISTANT_INCOMPLETE`，1 次请求，0 字节正文 |
| Native gameplay 医生表单场景（真实 API） | 失败，但落盘了原始 provider 响应体 |

## 已确认要修的问题

### E1 代理网络下远程资源被误拦（兼容缺陷，已修）

`app/src/main/java/.../characters/HttpCharacterImageFetcher.kt:86` 的 `publicAddress` 拒绝 `198.18.0.0/15`，`app/src/main/java/.../conversation/web/WebResourceRepository.kt:397` 有同样的检查。

该网段是 Clash / Mihomo 系 fake-ip 的默认池。本轮宿主机正是这种配置，因此 C-01 / C-02 卡内引用的远程图片与 CDN 依赖全部下载失败。C-02 卡内引用同一主机 200 处；C-01 引用 `cdn.jsdelivr.net` / `testingcf.jsdelivr.net`，即作者页面依赖 CDN 的标准写法。

使用代理网络的用户属于常见配置，应当放行，而不是拦截。同一时刻模型网关没有这个防护，于是表现为"聊天能用、资源用不了"，容易被误判成卡片问题。

配套的文案问题：`HttpCharacterImageFetcher.kt:41` 的 `IOException("图片下载失败，请检查网络后重试")` 覆盖了 `:91` 抛出的 `UnknownHostException("图片地址必须指向公共网络")`，把策略拦截报告成网络故障。

### E2 缺少模型侧错误的直接透传（已修）

`app/src/main/java/.../conversation/ChatViewModel.kt:2063`：

```kotlin
is GatewayException.HttpFailure -> "模型服务暂时不可用（HTTP $status）"
```

本轮真实触发的 400 响应体是：

```json
{"error":{"message":"Unsupported parameter: 'top_p' is not supported with this model.","param":"top_p","type":"invalid_request_error"}}
```

provider 已经指明了参数名，但被整条替换掉，用户只看到"模型服务暂时不可用（HTTP 400）"。400 是确定性客户端错误，该文案会引导反复重试。

这是外部错误，播放器不应解析兜底，但**应该原样透传**，让用户能看到 provider 说了什么。这是本轮阻塞 live 验证的直接原因。

触发它的 `top_p` 是 Preset 里的标准参数，用户可以通过手调预设绕开，因此本条的重点是错误透传，而不是为 `top_p` 建立模型能力探测。

### E3 Responses 适配器丢弃 `incomplete_details`

全仓库对 `incomplete_details` 零命中。`app/src/main/java/.../conversation/ConversationGenerator.kt:151`：

```kotlin
is ResponsesEvent.Finished -> emit(GenerationEvent.Finished(event.status))
```

`status` 只有 `completed` / `incomplete`，而提前结束的原因（`max_output_tokens` / `content_filter`）在 `incomplete_details.reason` 中，从未读取。对照 Chat Completions 分支 `:166` 传的是真实 `finish_reason`（`stop` / `length`）。

这是**响应侧**字段，与请求侧是否采信 Preset 预算无关；网关不采信 Preset 预算是有意设计。丢弃它的后果是"被输出上限截断"与"被内容过滤"在上层无法区分，五个适配器的完成原因不在同一套词汇表里。

### E4 端点候选回退未覆盖生成路径（已修）

`candidates()` 会为不带版本段的地址生成 `[base, base/v1]` 两个候选，但回退循环只存在于 `ConnectionRepository.refreshModels`（约 `:115`，`MODEL_ROUTE_FALLBACK_STATUSES = setOf(404, 405, 501)`），并且要在目录拉取成功后才把生效候选写回连接。`save()`（`:29`）直接取第一个候选。

生成路径只用存下来的单一端点：`ConnectionGenerator` 的 `ModelGatewayConversationGenerator.stream`（`:144`）与 `validateTokens` 都走 `connection.target()`。

后果：provider 只实现 `/v1/responses` 且没有 `/models` 时，端点会永远停在 `https://host/responses`。本条已在处理中。

## 与产品结论无关、仅作环境备注

- 本机 `.env` 中的某个测试模型名已下架，provider 返回 `The supported API model names are …, but you passed …`。这是本地配置，不是项目问题。
- 用 PowerShell `Set-Content -Encoding utf8` 生成的设备侧 env 文件带 BOM，会让 `BrowserLiveAndroidTest` 的解析器在第 85 行抛 `BL_CONFIG_FORMAT`（BOM 使首行既不以 `#` 开头也不含 `=`）。需要 `UTF8Encoding($false)` 写入。

## 夹具与脚手架相关（不据此改代码）

补充：`BrowserLiveAndroidTest` 的 opening 阶段不是幂等的。它在第 189 行要求重新导入的样例卡
`nativeAdaptation == null`，而 `NativeGameplayLiveAndroidTest.install()` 会给同一张卡装上手写适配并
刻意保留角色，所以先跑过 native 场景之后，同一张卡的 BrowserLive 用例会以 `BL_NATIVE_ADAPTATION`
失败。2026-09-10 复核 E1 时实际撞到这一条，需要用干净的应用数据才能重跑。

夹具可能临时，以下只记录现象：

- `BrowserLiveAndroidTest.inspectPage:331` 断言页面零运行时通知，因此在本机网络下，任何引用远程图片的卡都不可能通过，且失败只报 `BL_PAGE_METRICS`，需要另外读取私有诊断文件才能区分"渲染失败"与"资源被拦"。
- `BrowserLiveAndroidTest` 刻意不落盘 provider 错误体，`request-N.json` 只剩 `finishedReasons:["other"]`；本轮能定位到 E2 依赖的是 `NativeGameplayLiveAndroidTest` 内置的 SSE 拦截器。
- `CommunityCardCompatibilityTest:32-35` 把 `-DcommunityCard` 当作相对测试工作目录（模块目录）的路径，相对路径会退化为"夹具不可用"并跳过；该测试同时硬编码断言一张特定卡的名称、世界书条数与 Regex 数量。
- 设备侧测试的 opt-in 参数（`browserLive` / `surfaceLive` / `bindingLive` / `webRecoveryPhase` / `schemaRegression` 等）与 `cache/browser-live.env`、`cache/native-live.env` 的约定只存在于 `docs/archive/` 的历史记录，当前入口文档没有指引。

## 未覆盖

- `-DstDefaultPreset`：本机 `source/` 中没有对应的 ST 默认 Preset 夹具，未执行。
- 其余 opt-in 设备测试与 `NativeGameplayLiveAndroidTest` 的其它场景未执行；`NativeMemoryAndroidTest` 的进程重启用例需要手工准备的 checkpoint。
- 因 E2 未解除，真实模型下的整轮对话闭环、进程恢复与候选分支恢复均未获得通过证据。

## 复现

```powershell
# 1. 构建并安装
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t .\app\build\outputs\apk\debug\app-debug.apk
adb install -r -t .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk

# 2. 设备离线套件
adb shell am instrument -w -r io.github.zvensmoluya.tavernplayer.test/androidx.test.runner.AndroidJUnitRunner

# 3. 真实 Provider：配置写入应用私有 cache（需 debug 包），文件必须无 BOM
adb push <不带 BOM 的 env> /data/local/tmp/tavern-test.env
adb shell "run-as io.github.zvensmoluya.tavernplayer sh -c 'cp /data/local/tmp/tavern-test.env cache/browser-live.env'"
adb shell "run-as io.github.zvensmoluya.tavernplayer sh -c 'cp /data/local/tmp/tavern-test.env cache/native-live.env'"

# 4. 真实生成
adb shell am instrument -w -r -e browserLive 1 -e browserLivePhase conversation `
  -e browserSample C-03 -e browserLiveTurns 1 `
  -e class io.github.zvensmoluya.tavernplayer.conversation.web.BrowserLiveAndroidTest `
  io.github.zvensmoluya.tavernplayer.test/androidx.test.runner.AndroidJUnitRunner

# 5. 抓 provider 原始响应（NativeGameplayLiveAndroidTest 的拦截器会落盘 response-N.sse）
adb shell "run-as io.github.zvensmoluya.tavernplayer sh -c 'ls -1t files/native-gameplay-verification/ | head -1'"
```

产物位于应用私有目录 `files/browser-live-verification/<ts>/` 与 `files/native-gameplay-verification/<ts>/`，用 `run-as` 读取。测试会保留导入的角色与 Conversation，需手工清理。

## 修复复核（同日）

E1 / E2 修完后在同一天、同一台模拟器上复核，结果如下。

E1：清空应用数据（库内三张角色经 source.png 哈希核对，分别是 C-01 / C-02 / C-03 的导入结果，没有非夹具数据）后重跑
`-e browserLive 1 -e browserLivePhase opening -e browserSample C-02`，结果 `OK (1 test)`，`passed:true`、
`runtimeNotice:false`、私有诊断为空、0 次模型请求。同一用例在修复前是以 `BL_PAGE_METRICS` 失败并报
"图片下载失败"的。复核前已确认图片来源主机经本机代理可达（`www.xieka.icu` 与 `testingcf.jsdelivr.net` 均 200）。

E2：用之前触发过失败的同一条命令
（`NativeGameplayLiveAndroidTest#doctorFormPreservesInputsAcrossTwoTurns`）重跑，`doctor-first-result.txt` 里的
用户可见文案从"模型服务暂时不可用（HTTP 400）"变成 provider 原文：

```
模型服务返回 HTTP 400：{"error":{"message":"Unsupported parameter: 'top_p' is not supported with this model.","param":"top_p","type":"invalid_request_error"}}
```

E3 未处理。E4 有单元测试覆盖，但没有在真实 provider 上验证——手上两组凭据的基址在根路径与 `/v1` 上都可用，
构造不出"根路径 404"的真实场景。

复核完成后已从设备删除 `cache/browser-live.env`、`cache/native-live.env` 与 `/data/local/tmp/tavern-test.env`。
