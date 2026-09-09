# 角色图片资源

> 归档说明（2026-09-09）：保留阶段设计、源码审计与验证证据；文中的“当前”“下一步”和版本号指记录当时，不作为现行产品决策或完整运行契约。现行入口见[文档导航](../README.md)、[产品边界](../product-direction.md)和[实现架构](../architecture.md)。

角色详情 → 角色资源 → 准备图片。打开页面只在本地整理引用；用户点击准备后才访问图片来源。无需模型配置，不产生模型请求或编译成本。页面展示已保存数量、占用空间、每项状态和本地缩略预览，可暂停并继续未完成项。

## 来源与边界

- 识别 Character assets、HTML 图片 src、Markdown 图片地址，以及原始 JSON 字符串中的固定 PNG / JPEG / WebP URL 和这三类 base64 内嵌图片；已有本地卡图可直接读取。
- 同一 URL 合并引用位置，索引以 URI 哈希标识，保留原卡 JSON Pointer 来源；同一角色内相同图片字节只保存一份。
- 扫描包含停用内容中的固定引用，不表示启用对应条目或执行脚本。不会执行 JavaScript、模板或解码嵌入程序来推导图片地址；动态地址、相对地址、其他格式和无法发现的资源不属于本轮完整性承诺。
- 不修改原卡、编译输入、适配协议、聊天图片绑定或玩法；不还原画廊选图、翻面或解锁逻辑。

## 持久保存

原图位于 `filesDir/tavern/characters/{id}/resources/images/{sha256}.image`，独立 `index.json` 记录结果并关联原卡哈希。先原子保存图片，再保存索引，成功落盘后才报告已保存。重新打开会核对文件大小和哈希，缺失或损坏项可重新准备；完好资源不重新联网。

文件属于角色数据，不使用公共图片目录、MediaStore、cacheDir 或 noBackupFilesDir，不申请相册权限，不提供原图缓存清理或自动淘汰。此轮不增加角色删除功能；今后删除需同时处理会话引用。备份、迁移与资源打包导出仍未实现，沿用应用现有备份设置不等于完整备份保证。

按角色串行处理，单项失败保留原因并继续，其余完成项不回滚。暂停会取消当前 HTTP 请求；已完成项跨进程保留。进程终止后用户再次点击继续，不依赖后台调度自动恢复；不支持单文件断点续传。

## 取得与限制

独立 HTTP 客户端不携带模型凭据、Cookie 或自定义模型请求头。仅 HTTP(S) 公网来源，拒绝地址内凭据及 HTTPS 降级；最多三次重定向。实际响应需通过图片解码信息检查。最多 512 个引用，单图 8 MiB、边长 8192、3200 万像素，单角色原图总量 256 MiB；达到容量上限停止本轮，不清理已有原图。解码预览按 512 像素采样。

测试使用中性合成图片和本地模拟 HTTP 服务，不下载社区卡图库。设备上的相册不可见、断网预览及进程终止恢复仍需真机验收。

## 本轮验证

使用仓库 Gradle Wrapper 与本机忽略目录中的隔离输出 init script，避免此前 Windows 默认输出目录锁定问题。执行 `:content-core:test --tests '*CharacterImageDiscoveryTest'`，以及 `:app:testDebugUnitTest` 下的 `CharacterImageRepositoryTest`、`HttpCharacterImageFetcherTest`、`CharacterResourcesScreenTest`、`TavernPlayerAppTest` 和 `CharacterAndConversationRepositoryTest`，共 20 项通过；`:app:assembleDebug` 与 `:app:lintDebug` 通过。`git diff --check` 通过。`adb devices` 未发现设备，未执行真机验收。
