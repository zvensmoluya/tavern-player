# Tavern Player repository guidance

## Development stage

- 项目目前处于开发阶段，允许进行大范围、暴力的非兼容重构，不必为尚未发布或承诺的 API、数据结构、界面和内部实现保留兼容层。
- 进行此类重构时，应明确影响范围，删除过时路径，并同步更新相关文档和验证；一旦涉及真实用户数据或已发布版本，另行制定迁移方案。
- Text is the product. Everything else is optional.
- If it doesn't improve the conversation, it can wait.

## Product and architecture decisions

- Before making a product-flow, compatibility, or architecture change, read the relevant current documents under `docs/` and the README when useful.
- Prefer small, reversible decisions. Avoid abstractions for unconfirmed future features.
- When a compatibility assumption matters, record the relevant format or behavior and add a fixture or test when practical.
- Treat background documents as context rather than binding specifications. Follow the user's current request and explicit product decisions when they are more specific.
- If the current implementation and a project document disagree, preserve the current explicit decision and update the document when the direction is settled.

## General

- 默认使用中文沟通；代码、包名、标识符和提交标题使用英文，提交正文可使用中文。
- Android 工程建立后，优先使用仓库内的 Gradle Wrapper，不依赖全局 Gradle。
- 不提交密钥、凭据、本机绝对路径、`local.properties`、构建产物或缓存。
- 保留与当前任务无关的用户改动；修改尽量小而清晰。

## Verification

- 默认运行与改动直接相关、范围最小的验证；只有改动影响共享行为、构建或发布时才扩大范围。
- Android 工程建立后，按需使用 `gradlew.bat testDebugUnitTest`、`gradlew.bat lint` 或 `gradlew.bat assembleDebug`。
- 涉及设备、系统权限或生命周期的行为，应说明所需的真机验证。
- 无法运行某项检查时，明确说明原因和未覆盖的风险。

## Commits

- 只有在用户明确要求时才创建提交。
- 提交信息使用 Conventional Commits：`type(scope): summary`；无合适 scope 时省略。
- 常用类型：`feat`、`fix`、`docs`、`test`、`refactor`、`build`、`ci`、`chore`。
- summary 使用英文祈使语气，简短明确，不加句号。
- 非简单变更的提交正文应简要说明变更目的、关键行为或兼容性影响，以及实际执行的验证命令。
