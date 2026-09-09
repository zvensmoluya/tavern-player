# 助手容器读取与世界书状态展开

日期：2026-09-09。

用户确认推进世界书读取当前状态的链路。本轮修复助手脚本容器提取，不新增世界书写入、创建条目或通用扩展执行权限。

## 上游依据

酒馆助手固定核对提交 `8e0f4324e7d051025a333831411f03bd3145fac8`：

- [角色设置读取](https://github.com/N0VI028/JS-Slash-Runner/blob/8e0f4324e7d051025a333831411f03bd3145fac8/src/store/settings/character.ts) 明确对数组调用 `Object.fromEntries`，对象直接读取。因此键值对数组不是本项目根据单卡猜测的新格式。
- [变量宏](https://github.com/N0VI028/JS-Slash-Runner/blob/8e0f4324e7d051025a333831411f03bd3145fac8/src/function/macro_like.ts) 中 `get_message_variable` 输出字符串或 JSON，`format_message_variable` 输出字符串或 YAML，并按前缀缩进；两者递归移除 `$` 开头的字段。

## 实现与边界

`NativeProgramExtractor` 接受对象和严格的二元素、字符串键数组，保留脚本原文、原始 JSON Pointer 和已有的父级启用状态传播。非 scripts 元数据继续随材料提供；原卡不改写。非容器值、畸形键值对、重复键和非数组 scripts 在模型请求前带来源路径拒绝，避免把遗漏材料交给模型继续编译。重复键不采用上游 `Object.fromEntries` 的后项覆盖行为，这是明确的拒绝边界。

已存在的 `PromptCompiler` 从本轮 `mvuState.data.stat_data` 取得状态，世界书条目中的 `{{get_message_variable::stat_data}}` 和 `{{format_message_variable::stat_data}}` 由 MacroEngine 展开。世界书启用、激活、位置和预算仍由原编排控制。改变所选检查点会改变下一次展开值，读取不修改 MVU。

后续按用户决定修正输出：`get_message_variable::stat_data` 输出字符串或紧凑 JSON，`format_message_variable::stat_data` 输出字符串或块式 YAML；两者递归过滤 `$` 开头的内部字段，原始检查点保持不变。格式化输出按原行已展开前缀的 UTF-16 长度缩进，同一行多个格式化宏会把前一个宏展开产生的换行计入后一个前缀长度，保留上游这一行为。

YAML 由原生 JVM 库 SnakeYAML 2.6 在本地输出，仅转换既有 JSON 数据，不执行作者脚本，不调用模型。采用块式集合、两空格缩进、多行字符串 literal 输出；控制字符转义，保留字符串与数值类型。SnakeYAML 与上游 npm yaml 的合法引号、数字表示等细节可能不同，不承诺逐字节相同。没有模型质量对照证据支持 YAML 比 JSON 更优秀；修改依据是作者调用接口的输出语义。下一次本地展开即生效，不需要重新模型编译。

SnakeYAML 为 Apache-2.0，许可证随 APK 的 `assets/licenses/snakeyaml-LICENSE.txt` 打包。参见[输出选项 API](https://www.javadoc.io/static/org.yaml/snakeyaml/2.6/org/yaml/snakeyaml/DumperOptions.html)。没有新增 YAML 导入或反序列化用户对象入口。

参数仍仅支持精确的 `stat_data`，没有扩展任意变量路径或其他作用域，不能宣称完整 Tavern Helper 宏语义兼容。JSON 与 YAML 差异不是 C-07 脚本遗漏的原因。

## C-07 与验证

C-07 原件 SHA-256：`0166ea69a6bdfa0e7559cc98e877d3d5b6b106bc12e1c712a45ba96585f359f0`。通过可选环境变量 `TAVERN_HELPER_AUDIT_CARD` 定位，不提交原件。测试覆盖实际 PNG 导入、两个启用脚本进入材料、Schema 来源选择可接受、状态宏来源保留及原卡不变；不把人工指定来源的选择校验当作模型识别成功。

中性测试覆盖对象／数组容器、嵌套停用脚本、元数据、原始路径、畸形及重复键拒绝。PromptCompiler 回归直接检查世界书展开进入最终消息，使用 3 → 8 → 3 的检查点序列验证读取当前值与恢复旧值，同时检查停用条目不进入请求、MVU 检查点不变。

模型重新编译、原 C-07 MVU 初始化、真实聊天、手机全流程及失败诊断留存不属于上述测试证据，尚未验证或实现。旧适配和旧会话不会被这次提取修复自动替换。

实际执行：

```powershell
.\gradlew.bat :content-core:test :conversation-core:test --tests '*NativeHelperContainerTest' --tests '*NativeAdaptationCompilerTest' --tests '*PromptCompilerTest' --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
# 设置 TAVERN_HELPER_AUDIT_CARD 指向本地 C-07 原件后：
.\gradlew.bat :content-core:test --tests '*NativeHelperContainerTest' --rerun --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
git diff --check
```

首条命令的过滤参数作用于最后一个 test 任务，实际内容层为全量 77 项（74 通过、3 项可选夹具跳过），会话层为 PromptCompiler 的 22 项全部通过。随后启用 C-07 原件，容器专项 3 项全部通过。差异检查通过；未运行 APK 构建或设备测试。

### YAML 输出后续验证

新增输出测试覆盖对象、列表、空容器、根字符串、null、大整数、容易误判为数值或布尔的字符串、控制字符、中文与 emoji、多行 literal 文本、字段过滤，以及同行多个宏和前置普通宏的缩进。基础嵌套对象样例与本地 npm yaml 2.9.0 的 `stringify(value, {blockQuote: 'literal'}).trimEnd()` 输出核对一致；特殊值另经 YAML 解析检查值和类型，未做整张卡提示词逐字差分。

常规构建首先因 Windows 占用旧 class 文件而失败，随后使用本地忽略的 init 脚本，将各模块构建目录设为 `build/variable-yaml`，并按此前已有隔离验证方式把模块 JAR 加入 JVM test classpath。中途修正了新增测试误引用私有辅助函数的问题。类路径修正后的会话核心 135 项测试全部通过，没有跳过项。

本轮实际验证命令：

```powershell
.\gradlew.bat :conversation-core:test --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
.\gradlew.bat -I build/variable-yaml-isolated.gradle --no-configuration-cache :conversation-core:test :app:assembleDebug :app:lintDebug --max-workers=1 --no-parallel --no-watch-fs '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

最终上述隔离验证全部通过：会话核心 135 项通过，Debug APK 构建成功，lint 为 0 错误、14 告警（依赖／构建工具版本、应用图标与 UseKtx 提示）。已逐字节核对 APK 内 SnakeYAML 许可证与源文件一致，`git diff --check` 通过。未执行物理设备验证，未调用模型编译或聊天；没有模型效果优劣结论。
