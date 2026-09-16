# 本地试验版安装包

当前先提供本地构建的试验版，尚未配置 GitHub Actions 发布流程或正式发布签名。

分发入口为 [GitHub Releases](https://github.com/zvensmoluya/tavern-player/releases)。下载 `.apk` 附件即可，不需要下载源码压缩包；同页提供 SHA-256 校验文件和签名说明。

## 构建与安装

```powershell
./gradlew.bat :app:assembleExperimental
```

APK 位于 `app/build/outputs/apk/experimental/app-experimental.apk`。这是使用本机 Android 调试密钥签名的可安装包，不需要安装者自行签名。发送 APK 到 Android 8.0 及以上设备后，按系统提示允许安装来源，再安装应用。

| 项目 | 当前试验版 |
| --- | --- |
| 应用名 | Tavern Player 实验版 |
| 包名 | `io.github.zvensmoluya.tavernplayer.experimental` |
| 版本 | `0.1.0-experimental.1`，versionCode 1 |
| 构建类型 | `experimental`，继承 debug，可调试 |
| 签名 | 本机已有的 debug 签名，不是正式发布密钥 |

普通 debug 和 release 构建的包名不变。试验版使用独立包名，可以与已有开发安装或未来正式版并存；角色、连接、对话和文件保存在独立应用空间，当前没有试验版到正式版的自动数据迁移。

## 后续更新

- 本地签名本身可以用于正式分发，关键是长期保管同一份发布密钥，并使用正确的构建配置。
- 同包名且签名一致的新版本可以覆盖更新；发新版应递增 `versionCode`。
- 同包名但签名不同的 APK 通常不能覆盖安装。不要为绕过签名冲突直接卸载有重要数据的旧应用，卸载可能删除本地数据。
- 不同电脑或 GitHub runner 自动生成的 debug 密钥可能不同，因此不能保证各自构建的试验版能互相覆盖。不要将调试密钥提交到仓库；正式发布时在长期使用的电脑上单独生成、备份发布密钥。
- 独立包名解决与正式版并存的问题，不解决试验版换签名后的覆盖更新问题。

## 分发前验证

构建成功后，用 Android SDK Build Tools 的 `apksigner verify --verbose --print-certs` 验证签名，核对包名、版本、最低系统要求和原生库架构，并记录 APK 的 SHA-256。只分发 APK 和公开校验信息，不分发密钥。

还需在目标手机验证首次安装、启动、角色导入和聊天；后续版本需额外验证相同签名的覆盖升级。没有完成设备验证时应明确注明。签名规则见 [Android 官方说明](https://developer.android.com/studio/publish/app-signing)。
