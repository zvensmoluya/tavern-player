package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

class AdaptationValidator {
    fun validate(
        artifact: AdaptationArtifact,
        expectedSourceSha256: String? = null,
    ): AdaptationValidationResult {
        val issues = mutableListOf<AdaptationValidationIssue>()

        fun issue(path: String, code: String, message: String) {
            issues += AdaptationValidationIssue(path, code, message)
        }

        if (artifact.schemaVersion != ADAPTATION_SCHEMA_VERSION) {
            issue("schemaVersion", "UNSUPPORTED_SCHEMA", "仅支持 adaptation schema v$ADAPTATION_SCHEMA_VERSION")
        }
        if (!SHA256.matches(artifact.sourceSha256)) {
            issue("sourceSha256", "INVALID_SOURCE_HASH", "sourceSha256 必须是小写 SHA-256")
        }
        if (expectedSourceSha256 != null && artifact.sourceSha256 != expectedSourceSha256) {
            issue("sourceSha256", "SOURCE_HASH_MISMATCH", "适配产物与原始角色卡不匹配")
        }
        validateText("compiler.id", artifact.compiler.id, MAX_ID_CHARS, issues)
        validateText("compiler.version", artifact.compiler.version, MAX_ID_CHARS, issues)
        if (artifact.views.size > MAX_VIEWS) issue("views", "TOO_MANY_VIEWS", "视图数量超过 $MAX_VIEWS")
        if (artifact.state.size > MAX_STATE_VALUES) issue("state", "TOO_MANY_STATE_VALUES", "状态数量超过 $MAX_STATE_VALUES")

        val stateKeys = mutableSetOf<String>()
        artifact.state.forEachIndexed { index, definition ->
            val path = "state[$index]"
            validateId("$path.key", definition.key, issues)
            if (!stateKeys.add(definition.key)) issue("$path.key", "DUPLICATE_ID", "状态 key 重复")
            val primitive = definition.initialValue as? JsonPrimitive
            val validType = when (definition.type) {
                AdaptationStateType.STRING -> primitive?.isString == true
                AdaptationStateType.NUMBER -> primitive?.takeUnless(JsonPrimitive::isString)
                    ?.doubleOrNull
                    ?.isFinite() == true
                AdaptationStateType.BOOLEAN -> primitive?.takeUnless(JsonPrimitive::isString)?.booleanOrNull != null
            }
            if (!validType) issue("$path.initialValue", "STATE_TYPE_MISMATCH", "初始值与状态类型不匹配")
        }

        fun validateTemplateReferences(path: String, template: String, fieldIds: Set<String> = emptySet()) {
            TEMPLATE_REFERENCE.findAll(template).forEach { match ->
                val scope = match.groupValues[1]
                val key = match.groupValues[2]
                val known = when (scope) {
                    "form" -> key in fieldIds
                    "state" -> key in stateKeys
                    else -> false
                }
                if (!known) issue(path, "UNKNOWN_TEMPLATE_REFERENCE", "模板引用了未知值 $scope.$key")
            }
            ANY_TEMPLATE_REFERENCE.findAll(template).forEach { match ->
                val reference = match.groupValues[1].trim()
                val allowed = reference == "user" || reference == "char" ||
                    TEMPLATE_REFERENCE.matches(match.value)
                if (!allowed) issue(path, "UNKNOWN_TEMPLATE_REFERENCE", "模板引用了不允许的值 $reference")
            }
        }

        if (artifact.messageStateRules.size > MAX_MESSAGE_STATE_RULES) {
            issue("messageStateRules", "TOO_MANY_STATE_RULES", "消息状态规则数量超过 $MAX_MESSAGE_STATE_RULES")
        }
        artifact.messageStateRules.forEachIndexed { ruleIndex, rule ->
            val path = "messageStateRules[$ruleIndex]"
            if (rule.mappings.isEmpty()) issue("$path.mappings", "EMPTY_MAPPINGS", "消息状态规则必须包含映射")
            if (rule.mappings.size > MAX_STATE_MAPPINGS) {
                issue("$path.mappings", "TOO_MANY_STATE_MAPPINGS", "消息状态映射数量超过 $MAX_STATE_MAPPINGS")
            }
            val sourcePaths = mutableSetOf<String>()
            val targets = mutableSetOf<String>()
            rule.mappings.forEachIndexed { mappingIndex, mapping ->
                val mappingPath = "$path.mappings[$mappingIndex]"
                validateText("$mappingPath.sourcePath", mapping.sourcePath, MAX_STATE_PATH_CHARS, issues)
                if (!STATE_PATH.matches(mapping.sourcePath)) {
                    issue("$mappingPath.sourcePath", "INVALID_STATE_PATH", "状态来源路径格式无效")
                }
                if (!sourcePaths.add(mapping.sourcePath)) {
                    issue("$mappingPath.sourcePath", "DUPLICATE_STATE_PATH", "同一规则中的状态来源路径重复")
                }
                if (mapping.target !in stateKeys) {
                    issue("$mappingPath.target", "UNKNOWN_STATE", "消息状态映射引用了未知状态")
                }
                if (!targets.add(mapping.target)) {
                    issue("$mappingPath.target", "DUPLICATE_STATE_TARGET", "同一规则中的目标状态重复")
                }
            }
        }

        val viewIds = mutableSetOf<String>()
        var totalNodes = 0
        artifact.views.forEachIndexed { viewIndex, view ->
            val path = "views[$viewIndex]"
            validateId("$path.id", view.id, issues)
            if (!viewIds.add(view.id)) issue("$path.id", "DUPLICATE_ID", "视图 id 重复")
            validateText("$path.title", view.title, MAX_TEXT_CHARS, issues)
            when (view.trigger.type) {
                AdaptationTriggerType.ALWAYS -> if (view.trigger.value.isNotEmpty()) {
                    issue("$path.trigger.value", "UNUSED_VALUE", "ALWAYS trigger 不接受 value")
                }
                else -> {
                    if (view.trigger.value.isBlank()) issue("$path.trigger.value", "EMPTY_TRIGGER", "消息 trigger 不能为空")
                    validateText("$path.trigger.value", view.trigger.value, MAX_TRIGGER_CHARS, issues)
                }
            }
            val fieldIds = mutableSetOf<String>()
            val nodeIds = mutableSetOf<String>()
            fun validateNode(node: AdaptationUiNode, nodePath: String, depth: Int) {
                totalNodes += 1
                if (depth > MAX_NODE_DEPTH) issue(nodePath, "UI_TOO_DEEP", "UI 嵌套深度超过 $MAX_NODE_DEPTH")
                validateId("$nodePath.id", node.id, issues)
                if (!nodeIds.add(node.id)) issue("$nodePath.id", "DUPLICATE_ID", "同一视图中的 UI 节点 id 重复")
                validateText("$nodePath.title", node.title, MAX_TEXT_CHARS, issues)
                validateText("$nodePath.text", node.text, MAX_TEXT_CHARS, issues)
                validateTemplateReferences("$nodePath.text", node.text)
                if (node.type == AdaptationUiNodeType.STATUS) {
                    if (node.stateKey !in stateKeys) issue("$nodePath.stateKey", "UNKNOWN_STATE", "状态组件引用了未知状态")
                    if (node.min != null && node.max != null && node.min >= node.max) {
                        issue(nodePath, "INVALID_RANGE", "状态组件的 min 必须小于 max")
                    }
                } else if (node.stateKey != null) {
                    issue("$nodePath.stateKey", "UNUSED_VALUE", "只有 STATUS 组件可以绑定 stateKey")
                }
                if (node.type != AdaptationUiNodeType.FORM && node.fields.isNotEmpty()) {
                    issue("$nodePath.fields", "UNEXPECTED_FIELDS", "只有 FORM 组件可以包含 fields")
                }
                if (node.fields.size > MAX_FIELDS_PER_FORM) {
                    issue("$nodePath.fields", "TOO_MANY_FIELDS", "单个表单字段数量超过 $MAX_FIELDS_PER_FORM")
                }
                node.fields.forEachIndexed { fieldIndex, field ->
                    val fieldPath = "$nodePath.fields[$fieldIndex]"
                    validateId("$fieldPath.id", field.id, issues)
                    if (!fieldIds.add(field.id)) issue("$fieldPath.id", "DUPLICATE_ID", "同一视图中的表单字段 id 重复")
                    validateText("$fieldPath.label", field.label, MAX_TEXT_CHARS, issues)
                    validateText("$fieldPath.placeholder", field.placeholder, MAX_TEXT_CHARS, issues)
                    validateText("$fieldPath.initialValue", field.initialValue, MAX_INPUT_CHARS, issues)
                    val needsOptions = field.type == AdaptationFormFieldType.SINGLE_SELECT ||
                        field.type == AdaptationFormFieldType.MULTI_SELECT
                    if (needsOptions && field.options.isEmpty()) issue("$fieldPath.options", "MISSING_OPTIONS", "选择字段必须包含选项")
                    if (!needsOptions && field.options.isNotEmpty()) issue("$fieldPath.options", "UNEXPECTED_OPTIONS", "该字段类型不接受选项")
                    if (field.options.size > MAX_OPTIONS) issue("$fieldPath.options", "TOO_MANY_OPTIONS", "选项数量超过 $MAX_OPTIONS")
                    field.options.forEachIndexed { optionIndex, option ->
                        validateText("$fieldPath.options[$optionIndex].value", option.value, MAX_TEXT_CHARS, issues)
                        validateText("$fieldPath.options[$optionIndex].label", option.label, MAX_TEXT_CHARS, issues)
                    }
                }
                node.children.forEachIndexed { childIndex, child -> validateNode(child, "$nodePath.children[$childIndex]", depth + 1) }
            }
            view.nodes.forEachIndexed { nodeIndex, node -> validateNode(node, "$path.nodes[$nodeIndex]", 1) }
            if (view.submitActions.size > MAX_ACTIONS) issue("$path.submitActions", "TOO_MANY_ACTIONS", "动作数量超过 $MAX_ACTIONS")
            view.submitActions.forEachIndexed { actionIndex, action ->
                val actionPath = "$path.submitActions[$actionIndex]"
                when (action.type) {
                    AdaptationActionType.CHAT_SET_DRAFT -> {
                        if (action.template.isNullOrBlank()) issue("$actionPath.template", "MISSING_TEMPLATE", "写入草稿需要 template")
                        if (action.target != null || action.value != null) issue(actionPath, "UNUSED_VALUE", "写入草稿不接受 target/value")
                    }
                    AdaptationActionType.STATE_SET,
                    AdaptationActionType.STATE_INCREMENT,
                    -> {
                        if (action.target !in stateKeys) issue("$actionPath.target", "UNKNOWN_STATE", "动作引用了未知状态")
                        if (action.value == null && action.template == null) issue(actionPath, "MISSING_VALUE", "状态动作需要 value 或 template")
                    }
                    AdaptationActionType.STATE_TOGGLE -> {
                        val target = artifact.state.firstOrNull { it.key == action.target }
                        if (target?.type != AdaptationStateType.BOOLEAN) issue("$actionPath.target", "STATE_TYPE_MISMATCH", "toggle 只能用于布尔状态")
                        if (action.value != null || action.template != null) issue(actionPath, "UNUSED_VALUE", "toggle 不接受 value/template")
                    }
                }
                action.template?.let { template ->
                    validateText("$actionPath.template", template, MAX_TEMPLATE_CHARS, issues)
                    EXTERNAL_IO.find(template)?.let {
                        issue("$actionPath.template", "EXTERNAL_IO_FORBIDDEN", "模板不能包含外部 URL、data URI 或本地文件 URI")
                    }
                    validateTemplateReferences("$actionPath.template", template, fieldIds)
                }
            }
        }
        if (totalNodes > MAX_NODES) issue("views", "TOO_MANY_NODES", "UI 节点总数超过 $MAX_NODES")

        val inferredCapabilities = buildSet {
            if (artifact.messageStateRules.isNotEmpty()) add("state.ingest")
            artifact.views.forEach { view ->
                add("ui.native")
                view.submitActions.forEach { action ->
                    add(
                        when (action.type) {
                            AdaptationActionType.CHAT_SET_DRAFT -> "chat.setDraft"
                            AdaptationActionType.STATE_SET,
                            AdaptationActionType.STATE_INCREMENT,
                            AdaptationActionType.STATE_TOGGLE,
                            -> "state.write"
                        },
                    )
                }
            }
        }
        artifact.requiredCapabilities.forEachIndexed { index, capability ->
            if (capability !in SUPPORTED_CAPABILITIES) {
                issue("requiredCapabilities[$index]", "UNSUPPORTED_CAPABILITY", "Player 不支持 capability: $capability")
            }
        }
        inferredCapabilities.filterNot(artifact.requiredCapabilities::contains).forEach { capability ->
            issue("requiredCapabilities", "MISSING_CAPABILITY", "产物未声明实际使用的 capability: $capability")
        }
        artifact.requiredCapabilities.filter { it in SUPPORTED_CAPABILITIES && it !in inferredCapabilities }.forEach { capability ->
            issue("requiredCapabilities", "UNUSED_CAPABILITY", "产物声明了未使用的 capability: $capability")
        }

        return AdaptationValidationResult(issues.distinct())
    }

    fun validateAgainstProgramView(
        artifact: AdaptationArtifact,
        programView: ProgramView,
    ): AdaptationValidationResult {
        val issues = validate(artifact, programView.sourceSha256).issues.toMutableList()

        fun issue(path: String, code: String, message: String) {
            issues += AdaptationValidationIssue(path, code, message)
        }

        val observedTriggers = programView.programBlocks
            .filter(ProgramBlock::enabled)
            .mapNotNull(ProgramBlock::triggerPattern)
            .filter(String::isNotEmpty)
            .toSet()
        artifact.views.forEachIndexed { index, view ->
            if (view.trigger.type != AdaptationTriggerType.ALWAYS && view.trigger.value !in observedTriggers) {
                issue("views[$index].trigger.value", "UNOBSERVED_TRIGGER", "消息视图 trigger 未在 Program View 中出现")
            }
        }

        val observedDialects = programView.stateProtocolHints.map(ProgramStateProtocolHint::dialect).toSet()
        val observedValues = programView.stateProtocolHints.flatMap { hint ->
            hint.values.map { value -> (hint.dialect to value.path) to value }
        }.toMap()
        val definitions = artifact.state.associateBy(AdaptationStateDefinition::key)
        artifact.messageStateRules.forEachIndexed { ruleIndex, rule ->
            if (rule.dialect.name !in observedDialects) {
                issue(
                    "messageStateRules[$ruleIndex].dialect",
                    "UNOBSERVED_STATE_DIALECT",
                    "消息状态方言未在 Program View 中出现",
                )
            }
            rule.mappings.forEachIndexed mappingLoop@ { mappingIndex, mapping ->
                val path = "messageStateRules[$ruleIndex].mappings[$mappingIndex]"
                val hint = observedValues[rule.dialect.name to mapping.sourcePath]
                if (hint == null) {
                    issue("$path.sourcePath", "UNOBSERVED_STATE_PATH", "消息状态路径未在 Program View 中出现")
                    return@mappingLoop
                }
                val definition = definitions[mapping.target] ?: return@mappingLoop
                if (definition.type != hint.type) {
                    issue("$path.target", "STATE_HINT_TYPE_MISMATCH", "目标状态类型与 Program View 提示不一致")
                } else if (!sameInitialValue(definition, hint)) {
                    issue("$path.target", "STATE_HINT_INITIAL_MISMATCH", "目标状态初始值与 Program View 提示不一致")
                }
            }
        }
        return AdaptationValidationResult(issues.distinct())
    }

    private fun validateId(path: String, value: String, issues: MutableList<AdaptationValidationIssue>) {
        if (!ID.matches(value)) issues += AdaptationValidationIssue(path, "INVALID_ID", "id 必须匹配 ${ID.pattern}")
    }

    private fun validateText(
        path: String,
        value: String,
        maxChars: Int,
        issues: MutableList<AdaptationValidationIssue>,
    ) {
        if (value.length > maxChars) issues += AdaptationValidationIssue(path, "TEXT_TOO_LONG", "文本长度超过 $maxChars")
        if (EXTERNAL_IO.containsMatchIn(value)) {
            issues += AdaptationValidationIssue(path, "EXTERNAL_IO_FORBIDDEN", "适配产物不能包含外部 URL、data URI 或本地文件 URI")
        }
    }

    private fun sameInitialValue(
        definition: AdaptationStateDefinition,
        hint: ProgramStateValueHint,
    ): Boolean {
        val actual = definition.initialValue as? JsonPrimitive ?: return false
        val expected = hint.initialValue as? JsonPrimitive ?: return false
        return when (definition.type) {
            AdaptationStateType.STRING -> actual.isString && expected.isString && actual.content == expected.content
            AdaptationStateType.NUMBER -> {
                val actualNumber = actual.takeUnless(JsonPrimitive::isString)?.doubleOrNull
                val expectedNumber = expected.takeUnless(JsonPrimitive::isString)?.doubleOrNull
                actualNumber?.isFinite() == true && expectedNumber?.isFinite() == true && actualNumber == expectedNumber
            }
            AdaptationStateType.BOOLEAN -> {
                val actualBoolean = actual.takeUnless(JsonPrimitive::isString)?.booleanOrNull
                val expectedBoolean = expected.takeUnless(JsonPrimitive::isString)?.booleanOrNull
                actualBoolean != null && expectedBoolean != null && actualBoolean == expectedBoolean
            }
        }
    }

    companion object {
        val SUPPORTED_CAPABILITIES: Set<String> = setOf("ui.native", "chat.setDraft", "state.write", "state.ingest")
        private const val MAX_VIEWS = 16
        private const val MAX_STATE_VALUES = 128
        private const val MAX_MESSAGE_STATE_RULES = 4
        private const val MAX_STATE_MAPPINGS = 128
        private const val MAX_STATE_PATH_CHARS = 256
        private const val MAX_NODES = 256
        private const val MAX_NODE_DEPTH = 8
        private const val MAX_FIELDS_PER_FORM = 32
        private const val MAX_OPTIONS = 64
        private const val MAX_ACTIONS = 16
        private const val MAX_ID_CHARS = 80
        private const val MAX_TEXT_CHARS = 4_096
        private const val MAX_INPUT_CHARS = 8_192
        private const val MAX_TRIGGER_CHARS = 256
        private const val MAX_TEMPLATE_CHARS = 16_384
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val ID = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+){0,15}")
        private val STATE_PATH = Regex("[^.\\s'\"(){}\\[\\]\$]+(?:\\.[^.\\s'\"(){}\\[\\]\$]+){0,15}")
        private val EXTERNAL_IO = Regex("(?i)(?:https?://|data:|file:|content:)")
        private val TEMPLATE_REFERENCE = Regex("\\{\\{(form|state)\\.([a-z][a-z0-9]*(?:[._-][a-z0-9]+){0,15})\\}\\}")
        private val ANY_TEMPLATE_REFERENCE = Regex("\\{\\{([^{}]+)\\}\\}")
    }
}
