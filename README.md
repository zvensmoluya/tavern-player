<p align="center">
  <img src="assets/branding/exports/avatar-circle-512.png" width="180" height="180" alt="Tavern Player：白发红瞳的看板娘">
</p>

<h1 align="center">Tavern Player</h1>

<p align="center">
  <strong>Just characters. Just chat.</strong><br>
  在 Android 上，走进角色的故事。
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B-782C40?style=flat-square" alt="Android 8.0 及以上">
  <img src="https://img.shields.io/badge/Status-In_Development-54434B?style=flat-square" alt="开发中">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-AGPL--3.0--only-782C40?style=flat-square" alt="AGPL-3.0-only"></a>
</p>

<p align="center">
  <strong>简体中文</strong> · <a href="readme-en.md">English</a><br>
  <a href="https://github.com/zvensmoluya/tavern-player/releases">下载 APK</a> · <a href="#开始使用">开始使用</a> · <a href="docs/README.md">项目文档</a> · <a href="https://github.com/zvensmoluya/tavern-player/issues">反馈问题</a>
</p>

---

Tavern Player 是一个面向 Android 的原生角色扮演客户端。

SillyTavern 和社区已经积累了非常丰富的内容生态：角色卡、世界书、预设、Regex，以及各种长期演化出来的玩法。

我们想保留这些能力，但不希望玩家必须先学会管理它们。

## 角色、预设、模型，然后聊天

大多数时候，你只需要理解三件事：

| 角色 | 预设 | 模型 |
| --- | --- | --- |
| 你想体验谁 | 你希望它怎么写 | 由谁来生成 |
| 带上角色卡和它的故事 | 使用内置默认，或导入熟悉的预设 | 连接你使用的模型服务 |

复杂的东西可以继续存在于底层。

只是没有必要全部变成你的设置项。

> **复杂留在内部，其余保持简单。**

## 现在可以做什么

| | 已有能力 |
| --- | --- |
| **带上你的角色** | 导入 V1 / V2 / V3 的 JSON、PNG / APNG 角色卡；也可以扫描 Tavern Shelf，从同一局域网接收角色卡、世界书或受支持的预设。 |
| **阅读角色的世界** | 浏览角色定义、作者说明和世界书；准备角色图片后离线查看。全局世界书可独立导入、编辑、启停和导出。 |
| **用自己的身份入场** | 设置名字、描述和头像。新对话记录当时的身份，同一角色可以开启多场独立对话。 |
| **调整回复风格** | 使用内置默认预设，或导入 ST OpenAI / Chat Completion Preset；支持调整、恢复、另存为和无损导出。连接支持 OpenAI、Anthropic、Gemini 协议的模型服务。 |
| **体验作者的玩法** | 原生输入栏搭配网页消息区，在已声明的宿主范围内运行作者 HTML/JS，并免编译准备 MVU/EJS。 |
| **让对话继续下去** | 选择开场、重新生成、切换候选、修正历史文字；保存角色快照和运行状态，进程重启后恢复。 |

项目仍在开发中。单角色对话闭环已可用，具体兼容范围见[产品边界](docs/product-direction.md)和[网页运行契约](docs/web-runtime.md)。

## 开始使用

先从 [GitHub Releases](https://github.com/zvensmoluya/tavern-player/releases) 下载试验版 `.apk`，在 Android 8.0 及以上手机上打开并按提示允许安装来源。请下载 APK 附件，不是页面自动提供的 Source code 压缩包。

当前试验版使用独立包名和调试签名，可与未来正式版并存，数据不自动迁移；换签名不能直接覆盖更新。首版已通过构建、lint 和签名检查，尚未完成真机安装与聊天验收。详见[试验版说明](docs/experimental-build.md)。

1. **导入角色**：在角色库选择 PNG / JSON 角色卡，或扫描 Tavern Shelf。没有模型连接也能先浏览角色内容。
2. **连接模型**：在“模型”中配置你的服务连接并选择模型。预设可以先使用内置“默认”。
3. **开始对话**：进入角色详情，选择“开始新对话”，从角色提供的开场进入故事。

“我的身份”可以按需填写；全局世界书和导入预设也都是可选项。需要从源码构建时，见下方[开发与验证](#开发与验证)。

## 能力与兼容细节

<details>
<summary><strong>完整能力清单</strong></summary>

当前 Android 版本已经打通单角色 Character Card 对话闭环：

- 导入并保存 V1 / V2 / V3 JSON 或 PNG / APNG 角色卡；
- 扫描 Tavern Shelf 二维码，从同一局域网接收并校验角色卡、世界书或受支持的 Preset；
- 独立导入、启停、阅读、编辑、另存为和导出全局世界书；与预设独立，可同时启用多本，逐项选择按原条件、始终注入或停用；
- 浏览角色定义、creator notes、附带世界书与兼容性报告；世界书直接按内容浏览，包括停用内容；对话内可逐项选择停用、按原条件或始终注入，并仅为当前对话修改、恢复正文；
- 在角色详情的“角色资源”中识别并准备静态图片，持久保存在应用私有目录，支持离线查看、暂停与失败重试；无需配置模型，不参与 Native 编译；
- 编辑一份全局默认用户身份，包括名字、描述和可选头像；新对话会捕获当时的身份；
- 导入、切换、调整、恢复、另存为、删除和无损导出 ST OpenAI / Chat Completion Preset；
- 使用卡片开场和备用开场创建独立 Conversation；新对话默认使用原生输入栏与单个 WebView 消息区，角色详情同时提供原生模式入口，旧对话保留原执行模式；
- 在声明的宿主范围内直接运行作者 HTML/JS，免编译准备 MVU/EJS，按需保存网页依赖并复用角色原图；
- 在发送时执行卡片 World Book、Character Regex、Macro、Prompt 编排与 context 预算；
- 将当前全局 Preset 捕获到单次生成，并向五种 OpenAI、Anthropic 或 Gemini 协议安全映射参数；
- 在模型目录未声明能力时，按 Preset 声明的未验证预算运行，并允许按模型 ID 覆盖 context / output token 上限；
- 在聊天气泡内修正用户或 AI 历史文字，或显式从修改处截断旧未来并恢复 Macro 与 World Book 运行状态；
- 保存消息候选、角色快照和运行状态，并在进程重启后恢复。

</details>

<details>
<summary><strong>预设与用户身份的作用范围</strong></summary>

内置“默认”Preset 不可删除，但可以直接调整并随时恢复内置状态。导入或另存为的 Preset 也保留不可变初始版本。Preset 不绑定 Conversation：运行中的请求使用开始时的快照，切换只影响下一次生成；历史展示则使用当前 Preset 的 display Regex 与 `show_thoughts`。

角色库中的“我的身份”是单一默认身份，不提供身份列表或自动绑定。名字用于 `{{user}}` 和用户消息署名；描述提供给 `{{persona}}` 与 `personaDescription` marker，是否进入请求以及所在位置仍完全由 Preset 决定。已有 Conversation 保留创建时的身份快照。

Preset 列表选择的就是当前正在使用和编辑的 Preset。详情以实际 `prompt_order` 中的普通 Prompt 与 Regex 开关为主；开关只改变启用状态，不插入、删除或移动队列。Prompt 详情、格式结构与模型请求参数使用独立页面；请求参数可逐项关闭，关闭后保留本地值，但不再进入兼容 Provider 请求或 ST 导出。Provider 必填字段仍由播放器提供安全值。

</details>

<details>
<summary><strong>网页运行、Shelf 与实验性原生适配</strong></summary>

导入与 Shelf 接收不执行程序或调用编译模型。进入默认网页对话后，启用的助手脚本和符合渲染规则的作者页面按 `player-web-1` 运行；普通 HTML 禁止脚本，缺失能力明确报告。原生模式入口对每张卡保留，不要求先完成 Native 适配。原程序范围、资源版本及恢复限制见[网页运行契约](docs/web-runtime.md)。Preset 中 Provider、endpoint、自定义 headers/body 和凭据形字段仍是惰性内容，不会改变播放器连接。

Shelf 接收入口位于角色库首页。Android 17 会在首次接收前请求本地网络权限；独立 World Book 导入全局世界书列表，默认不启用。

Native 适配提供实验性的“准备游玩”入口：模型理解相关源码，复用 MVU/EJS 公共程序，并生成 JS 动态投影和受控的高层原生 Surface。自定义操作通过声明的宿主接口执行，持久变更保存后更新界面；操作检查点与消息结束状态分开，可恢复所属候选分支。简单状态绑定和原草稿表单继续可用，手工适配导入保留。可安装不代表整卡行为等价，当前接口与取消语义见[实现架构](docs/architecture.md)，阶段验证记录由[文档导航](docs/README.md)索引。

</details>

## 项目文档

| 你想了解 | 从这里开始 |
| --- | --- |
| 文档结构与当前进展 | [文档导航](docs/README.md) |
| 产品定位与支持范围 | [产品与兼容性边界](docs/product-direction.md) |
| 模块、数据与运行方式 | [当前实现架构](docs/architecture.md) |
| 作者页面、脚本与恢复限制 | [网页运行契约](docs/web-runtime.md) |
| 人物头像、应用图标与导出方式 | [视觉资源](assets/branding/README.md) |

## 开发与验证

想先打一个可直接安装的试验包，可以使用 `./gradlew.bat :app:assembleExperimental`。试验版使用独立包名和本机调试签名，可与正式版并存；数据独立，换签名不能直接覆盖。详见[本地试验版说明](docs/experimental-build.md)。

使用仓库内的 Gradle Wrapper 构建 Android 工程。需要配置 Android SDK，并让 Node.js 与 npm 在命令行可用；构建会准备网页与 MVU 运行资源。

```powershell
.\gradlew.bat :app:assembleDebug
```

生成的调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

<details>
<summary><strong>回归检查与本地兼容性材料</strong></summary>

核心回归检查：

```powershell
.\gradlew.bat :content-core:test :conversation-core:test :model-gateway:test :app:testDebugUnitTest
.\gradlew.bat lint assembleDebug
```

可选的本地社区角色卡兼容测试可通过 `-DcommunityCard=<path>` 指定文件。ST 默认 Preset 的导入、导出和再导入验收可通过 `-DstDefaultPreset=<Default.json path>` 执行；`source/` 仅作为本机验收材料，不进入版本库。

</details>

## 开源

欢迎通过 [Issues](https://github.com/zvensmoluya/tavern-player/issues) 反馈问题或建议，贡献方式见 [CONTRIBUTING](CONTRIBUTING.md)。

SillyTavern 建立了这个生态。我们想做一个更适合消费它的 Player。

这个生态本来就是开放的。

**播放器也应该如此。**

Copyright (C) 2026 Zven. 本项目仅依据 AGPL-3.0-only 授权。

[AGPL-3.0-only](LICENSE)
