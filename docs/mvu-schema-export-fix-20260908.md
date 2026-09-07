# MVU Schema 导出声明加载修复

手机实测出现 `SyntaxError: unsupported keyword: export`。私有样本以 SHA-256 `7df0b58b2a46ac9ae2169c45f715a58760ebdad63017c5a860222d808beabe32` 追溯，原件与设备导出数据不提交。

## 原因和修改

旧加载器仅用正则移除 `export const Schema`，把变量名及大小写写死。实际样本使用合法的 `export const schema`，导致 `Function` 执行时仍含模块导出语法。这属于 Player 加载器缺陷，不是角色卡内容错误。

应用与 Node 参照宿主现在共用 `schema-script.mjs`，通过固定版本 Acorn 8.15.0 解析声明位置，移除本地具名导出的模块外壳，不改变量名、初始化表达式或注册调用。注释和字符串中的 `export` 不参与替换；保留换行便于定位错误。Acorn 的 MIT 许可证随运行资源打包。

仍只映射原先允许的 `registerMvuSchema` 固定来源导入；默认导出、重新导出及未映射的模块依赖明确失败，不增加网络加载或浏览器宿主能力。该修复不要求重新生成模型适配结果。

## 验证方式

新增中性回归覆盖大小写与任意变量名、注释和字符串、具名导出列表、固定导入和不支持模块的拒绝；QuickJS 检查覆盖小写声明的初始化、更新及检查点。Android 增加显式开启的私有程序回归入口，素材通过测试缓存提供，不随 APK 打包。

适配报告中的“已恢复”是模型生成的能力说明，不代表该程序已经通过运行初始化。本次错误暴露了这一表述局限；本次修复不将已有报告升级为整卡实测结论。

## 本轮结果

- 实际私有脚本在旧处理逻辑下复现 `export` 语法错误，修复后 Node 初始化得到三组状态。
- `npm --prefix tools/mvu-probe test`：10 项通过。
- Gradle Wrapper 的 `:app:testDebugUnitTest --tests '*QuickJsMvuRuntimeTest'`：5 项通过；`:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` 通过。使用本机忽略目录中的 `bindings-isolated.gradle` 隔离输出，关闭配置缓存及 Kotlin 增量编译。
- 模拟器 `QuickJsMvuAndroidTest`（`schemaRegression=1`）：3 项通过，含实际程序初始化及无更新回复保留状态。
- 核对 APK 的运行包哈希、provenance、Acorn 许可证和私有回归素材排除；覆盖安装到用户手机成功，保留应用数据。
- `git diff --check` 通过。本轮未重新请求模型编译或真实聊天，未宣称整卡玩法验证完成。
