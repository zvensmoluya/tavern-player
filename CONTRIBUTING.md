# 参与 Tavern Player / Contributing

欢迎反馈问题、改进文档和提交代码。中文或英文均可。

Tavern Player 面向角色对话和剧情体验。请先阅读 [README](README.md)、[产品边界](docs/product-direction.md)和[实现架构](docs/architecture.md)。涉及产品流程或较大重构时，先用 Issue 说明场景与预期行为，便于讨论范围。

## 反馈问题

使用[问题反馈模板](https://github.com/zvensmoluya/tavern-player/issues/new?template=bug_report.yml)，提供应用版本、手机与系统、复现步骤，以及预期和实际表现。网页相关问题可以补充 WebView 版本。

截图和日志请先脱敏，不要公开 API Key、认证请求头、私人聊天或个人信息。兼容性问题尽量提供可分享的匿名最小样本，不要直接上传未经授权的社区角色卡、预设或完整素材。

## 提交改动

- 保持改动聚焦，说明用户可观察的变化；涉及行为边界时同步更新文档。
- 使用仓库内 Gradle Wrapper。构建与试验包说明见 [README](README.md#开发与验证)和[试验版指南](docs/experimental-build.md)。
- 执行与改动直接相关的最小验证，在 PR 中列出实际执行的命令与结果。设备、权限、生命周期和安装更新行为需注明设备验证情况。
- 不提交密钥、签名私钥、填入真实值的 `.env`、`local.properties`、本机绝对路径、构建产物或缓存。
- 测试与文档中的社区素材使用中性编号和匿名称谓，通过夹具路径、哈希与实验记录追溯。
- 提交标题使用 Conventional Commits，英文简述，例如 `fix(chat): Preserve scroll position while streaming`。

仓库仍在开发中，接口和数据结构可能调整。请避免为未确认的未来功能增加兼容层或抽象。代码贡献遵循项目的 [AGPL-3.0-only](LICENSE) 许可。

## English

Issues, documentation improvements, and focused pull requests are welcome in English or Chinese. Start with the [English README](readme-en.md); most technical documentation is currently in Chinese.

For bugs, include the app version, device and Android version, reproduction steps, and expected versus actual behavior. Remove credentials and private content. Use anonymized, shareable fixtures for compatibility reports.

For code changes, describe the resulting behavior, run relevant checks with the repository's Gradle Wrapper, and report what you actually tested. Mention device behavior that remains unverified. Keep credentials, signing keys, local configuration, build output, and unlicensed community content out of commits. Contributions follow the project's AGPL-3.0-only license.
