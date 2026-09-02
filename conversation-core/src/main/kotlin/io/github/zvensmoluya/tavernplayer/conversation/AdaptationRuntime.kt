package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.AdaptationActionType
import io.github.zvensmoluya.tavernplayer.content.AdaptationArtifact
import io.github.zvensmoluya.tavernplayer.content.AdaptationFormField
import io.github.zvensmoluya.tavernplayer.content.AdaptationFormFieldType
import io.github.zvensmoluya.tavernplayer.content.AdaptationMessageStateDialect
import io.github.zvensmoluya.tavernplayer.content.AdaptationStateDefinition
import io.github.zvensmoluya.tavernplayer.content.AdaptationStateType
import io.github.zvensmoluya.tavernplayer.content.AdaptationTriggerType
import io.github.zvensmoluya.tavernplayer.content.AdaptationUiNode
import io.github.zvensmoluya.tavernplayer.content.AdaptationView
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

data class AdaptationFormSubmission(
    val viewId: String,
    val values: Map<String, List<String>>,
)

enum class AdaptationEffectType {
    CHAT_SET_DRAFT,
}

data class AdaptationEffect(
    val type: AdaptationEffectType,
    val value: String,
)

data class AdaptationMessageIngestionResult(
    val runtimeState: ConversationRuntimeState,
    val appliedUpdates: Int,
)

sealed interface AdaptationExecutionResult {
    data class Success(
        val runtimeState: ConversationRuntimeState,
        val effects: List<AdaptationEffect>,
    ) : AdaptationExecutionResult

    data class Failure(
        val code: String,
        val message: String,
    ) : AdaptationExecutionResult
}

class AdaptationRuntime {
    fun initialState(artifact: AdaptationArtifact?): ConversationRuntimeState = ConversationRuntimeState(
        adaptationState = artifact?.state.orEmpty().associate { it.key to it.initialValue },
    )

    fun execute(
        artifact: AdaptationArtifact,
        submission: AdaptationFormSubmission,
        runtimeState: ConversationRuntimeState,
        userName: String = "",
        characterName: String = "",
    ): AdaptationExecutionResult {
        val view = artifact.views.firstOrNull { it.id == submission.viewId }
            ?: return AdaptationExecutionResult.Failure("UNKNOWN_VIEW", "找不到 Native 适配视图")
        val fields = view.nodes.flatMap(::collectFields)
        val fieldsById = fields.associateBy(AdaptationFormField::id)
        val unknown = submission.values.keys - fieldsById.keys
        if (unknown.isNotEmpty()) {
            return AdaptationExecutionResult.Failure("UNKNOWN_FIELD", "表单包含未知字段：${unknown.sorted().joinToString()}")
        }
        fields.forEach { field ->
            val values = submission.values[field.id].orEmpty()
            validateField(field, values)?.let { return it }
        }

        val definitions = artifact.state.associateBy(AdaptationStateDefinition::key)
        val state = runtimeState.adaptationState.toMutableMap()
        val effects = mutableListOf<AdaptationEffect>()
        for (action in view.submitActions) {
            when (action.type) {
                AdaptationActionType.CHAT_SET_DRAFT -> {
                    val template = action.template
                        ?: return AdaptationExecutionResult.Failure("MISSING_TEMPLATE", "草稿动作缺少模板")
                    val rendered = renderTemplate(template, submission.values, state, userName, characterName)
                    if (rendered.length > MAX_DRAFT_CHARS) {
                        return AdaptationExecutionResult.Failure("DRAFT_TOO_LONG", "生成的草稿超过 $MAX_DRAFT_CHARS 字符")
                    }
                    effects += AdaptationEffect(AdaptationEffectType.CHAT_SET_DRAFT, rendered)
                }
                AdaptationActionType.STATE_SET -> {
                    val definition = definitions[action.target]
                        ?: return AdaptationExecutionResult.Failure("UNKNOWN_STATE", "动作引用了未知状态")
                    val raw = action.template?.let { renderTemplate(it, submission.values, state, userName, characterName) } ?: action.value
                    state[definition.key] = parseStateValue(definition, raw)
                        ?: return AdaptationExecutionResult.Failure("INVALID_STATE_VALUE", "状态 ${definition.key} 的值类型不正确")
                }
                AdaptationActionType.STATE_INCREMENT -> {
                    val definition = definitions[action.target]
                        ?: return AdaptationExecutionResult.Failure("UNKNOWN_STATE", "动作引用了未知状态")
                    if (definition.type != AdaptationStateType.NUMBER) {
                        return AdaptationExecutionResult.Failure("INVALID_STATE_TYPE", "increment 只能修改数值状态")
                    }
                    val raw = action.template?.let { renderTemplate(it, submission.values, state, userName, characterName) } ?: action.value
                    val delta = raw?.toDoubleOrNull()?.takeIf(Double::isFinite)
                        ?: return AdaptationExecutionResult.Failure("INVALID_STATE_VALUE", "increment 需要有限数值")
                    val current = (state[definition.key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                    state[definition.key] = JsonPrimitive(current + delta)
                }
                AdaptationActionType.STATE_TOGGLE -> {
                    val definition = definitions[action.target]
                        ?: return AdaptationExecutionResult.Failure("UNKNOWN_STATE", "动作引用了未知状态")
                    if (definition.type != AdaptationStateType.BOOLEAN) {
                        return AdaptationExecutionResult.Failure("INVALID_STATE_TYPE", "toggle 只能修改布尔状态")
                    }
                    val current = (state[definition.key] as? JsonPrimitive)?.booleanOrNull ?: false
                    state[definition.key] = JsonPrimitive(!current)
                }
            }
        }
        return AdaptationExecutionResult.Success(runtimeState.copy(adaptationState = state.toMap()), effects)
    }

    fun ingestAssistantMessage(
        artifact: AdaptationArtifact,
        sourceText: String,
        runtimeState: ConversationRuntimeState,
    ): AdaptationMessageIngestionResult {
        if (artifact.messageStateRules.isEmpty() || sourceText.isEmpty()) {
            return AdaptationMessageIngestionResult(runtimeState, 0)
        }
        val definitions = artifact.state.associateBy(AdaptationStateDefinition::key)
        val state = runtimeState.adaptationState.toMutableMap()
        var applied = 0
        artifact.messageStateRules.forEach { rule ->
            val mappings = rule.mappings.associateBy { it.sourcePath }
            val updates = when (rule.dialect) {
                AdaptationMessageStateDialect.UPDATE_VARIABLE_SET_V1 -> parseUpdateVariableBlocks(sourceText)
            }
            updates.forEach { update ->
                if (applied >= MAX_MESSAGE_UPDATES) return@forEach
                val mapping = mappings[update.path] ?: return@forEach
                val definition = definitions[mapping.target] ?: return@forEach
                val value = coerceMessageValue(definition, update.value) ?: return@forEach
                state[definition.key] = value
                applied += 1
            }
        }
        return AdaptationMessageIngestionResult(runtimeState.copy(adaptationState = state.toMap()), applied)
    }

    private fun collectFields(node: AdaptationUiNode): List<AdaptationFormField> =
        node.fields + node.children.flatMap(::collectFields)

    private fun validateField(
        field: AdaptationFormField,
        values: List<String>,
    ): AdaptationExecutionResult.Failure? {
        if (values.any { it.length > MAX_FIELD_CHARS }) {
            return AdaptationExecutionResult.Failure("FIELD_TOO_LONG", "字段 ${field.label} 超过 $MAX_FIELD_CHARS 字符")
        }
        if (field.required && values.none(String::isNotBlank)) {
            return AdaptationExecutionResult.Failure("REQUIRED_FIELD", "请填写${field.label}")
        }
        if (field.type != AdaptationFormFieldType.MULTI_SELECT && values.size > 1) {
            return AdaptationExecutionResult.Failure("MULTIPLE_VALUES", "字段 ${field.label} 只接受一个值")
        }
        return when (field.type) {
            AdaptationFormFieldType.NUMBER -> values.firstOrNull()?.takeIf(String::isNotBlank)?.let { raw ->
                if (raw.toDoubleOrNull()?.isFinite() != true) {
                    AdaptationExecutionResult.Failure("INVALID_NUMBER", "${field.label}必须是数值")
                } else null
            }
            AdaptationFormFieldType.SINGLE_SELECT,
            AdaptationFormFieldType.MULTI_SELECT,
            -> {
                val allowed = field.options.mapTo(mutableSetOf()) { it.value }
                if (values.any { it !in allowed }) {
                    AdaptationExecutionResult.Failure("INVALID_OPTION", "${field.label}包含无效选项")
                } else null
            }
            AdaptationFormFieldType.TOGGLE -> if (values.any { it != "true" && it != "false" }) {
                AdaptationExecutionResult.Failure("INVALID_BOOLEAN", "${field.label}必须是布尔值")
            } else null
            else -> null
        }
    }

    private fun renderTemplate(
        template: String,
        form: Map<String, List<String>>,
        state: Map<String, JsonElement>,
        userName: String,
        characterName: String,
    ): String {
        val scoped = TEMPLATE_REFERENCE.replace(template) { match ->
            when (match.groupValues[1]) {
                "form" -> form[match.groupValues[2]].orEmpty().joinToString("、")
                "state" -> (state[match.groupValues[2]] as? JsonPrimitive)?.content.orEmpty()
                else -> match.value
            }
        }
        return renderAdaptationText(scoped, state, userName, characterName)
    }

    private fun parseStateValue(definition: AdaptationStateDefinition, raw: String?): JsonPrimitive? = when (definition.type) {
        AdaptationStateType.STRING -> JsonPrimitive(raw.orEmpty())
        AdaptationStateType.NUMBER -> raw?.toDoubleOrNull()?.takeIf(Double::isFinite)?.let(::JsonPrimitive)
        AdaptationStateType.BOOLEAN -> raw?.toBooleanStrictOrNull()?.let(::JsonPrimitive)
    }

    private fun coerceMessageValue(definition: AdaptationStateDefinition, value: JsonPrimitive): JsonPrimitive? =
        when (definition.type) {
            AdaptationStateType.STRING -> value.takeIf(JsonPrimitive::isString)?.let { JsonPrimitive(it.content) }
            AdaptationStateType.NUMBER -> value.takeUnless(JsonPrimitive::isString)
                ?.takeIf { it.booleanOrNull == null }
                ?.doubleOrNull
                ?.takeIf(Double::isFinite)
                ?.let(::JsonPrimitive)
            AdaptationStateType.BOOLEAN -> value.takeUnless(JsonPrimitive::isString)?.booleanOrNull?.let(::JsonPrimitive)
        }

    private fun parseUpdateVariableBlocks(source: String): List<DialectUpdate> {
        val bounded = source.takeLast(MAX_MESSAGE_SOURCE_CHARS)
        val result = mutableListOf<DialectUpdate>()
        var offset = 0
        while (result.size < MAX_MESSAGE_UPDATES) {
            val start = bounded.indexOf(UPDATE_BLOCK_OPEN, offset)
            if (start < 0) break
            val contentStart = start + UPDATE_BLOCK_OPEN.length
            val end = bounded.indexOf(UPDATE_BLOCK_CLOSE, contentStart)
            if (end < 0) break
            bounded.substring(contentStart, end).lineSequence().forEach { line ->
                if (result.size < MAX_MESSAGE_UPDATES) parseUpdateLine(line)?.let(result::add)
            }
            offset = end + UPDATE_BLOCK_CLOSE.length
        }
        return result
    }

    private fun parseUpdateLine(line: String): DialectUpdate? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(UPDATE_CALL_PREFIX)) return null
        val open = UPDATE_CALL_PREFIX.length
        var quote: Char? = null
        var escaped = false
        var close = -1
        for (index in open until trimmed.length) {
            val char = trimmed[index]
            if (escaped) {
                escaped = false
            } else if (char == '\\' && quote != null) {
                escaped = true
            } else if (quote != null) {
                if (char == quote) quote = null
            } else if (char == '\'' || char == '"') {
                quote = char
            } else if (char == ')') {
                close = index
                break
            }
        }
        if (close < 0 || quote != null) return null
        val suffix = trimmed.substring(close + 1).trim().removePrefix(";").trim()
        if (suffix.isNotEmpty() && !suffix.startsWith("//")) return null
        val arguments = splitScalarArguments(trimmed.substring(open, close)) ?: return null
        if (arguments.size != 3) return null
        val path = parseQuoted(arguments[0]) ?: return null
        if (!STATE_PATH.matches(path)) return null
        if (parseDialectScalar(arguments[1]) == null) return null
        val value = parseDialectScalar(arguments[2]) ?: return null
        return DialectUpdate(path, value)
    }

    private fun splitScalarArguments(source: String): List<String>? {
        val result = mutableListOf<String>()
        var quote: Char? = null
        var escaped = false
        var start = 0
        source.forEachIndexed { index, char ->
            if (escaped) {
                escaped = false
            } else if (char == '\\' && quote != null) {
                escaped = true
            } else if (quote != null) {
                if (char == quote) quote = null
            } else if (char == '\'' || char == '"') {
                quote = char
            } else if (char == ',') {
                result += source.substring(start, index).trim()
                start = index + 1
            } else if (char in "()[]{}") {
                return null
            }
        }
        if (quote != null || escaped) return null
        result += source.substring(start).trim()
        return result
    }

    private fun parseDialectScalar(source: String): JsonPrimitive? {
        if (source.length > MAX_DIALECT_SCALAR_CHARS) return null
        parseQuoted(source)?.let { return JsonPrimitive(it) }
        if (source == "true") return JsonPrimitive(true)
        if (source == "false") return JsonPrimitive(false)
        if (!DIALECT_NUMBER.matches(source)) return null
        return runCatching { Json.parseToJsonElement(source) as? JsonPrimitive }.getOrNull()
    }

    private fun parseQuoted(source: String): String? {
        if (source.length < 2) return null
        val quote = source.first()
        if ((quote != '\'' && quote != '"') || source.last() != quote) return null
        if (quote == '"') {
            return runCatching { (Json.parseToJsonElement(source) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                .getOrNull()
        }
        val result = StringBuilder(source.length - 2)
        var index = 1
        while (index < source.lastIndex) {
            val char = source[index]
            if (char != '\\') {
                result.append(char)
                index += 1
                continue
            }
            if (index + 1 >= source.lastIndex) return null
            when (val escaped = source[index + 1]) {
                '\\', '\'' -> result.append(escaped)
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                else -> return null
            }
            index += 2
        }
        return result.toString()
    }

    companion object {
        private const val MAX_FIELD_CHARS = 8_192
        private const val MAX_DRAFT_CHARS = 16_384
        private const val MAX_MESSAGE_SOURCE_CHARS = 128 * 1024
        private const val MAX_MESSAGE_UPDATES = 128
        private const val MAX_DIALECT_SCALAR_CHARS = 8_192
        private const val UPDATE_BLOCK_OPEN = "<UpdateVariable>"
        private const val UPDATE_BLOCK_CLOSE = "</UpdateVariable>"
        private const val UPDATE_CALL_PREFIX = "_.set("
        private val TEMPLATE_REFERENCE = Regex("\\{\\{(form|state)\\.([a-z][a-z0-9]*(?:[._-][a-z0-9]+){0,15})\\}\\}")
        private val STATE_PATH = Regex("[^.\\s'\"(){}\\[\\]\$]+(?:\\.[^.\\s'\"(){}\\[\\]\$]+){0,15}")
        private val DIALECT_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}

private data class DialectUpdate(val path: String, val value: JsonPrimitive)

fun renderAdaptationText(
    template: String,
    state: Map<String, JsonElement>,
    userName: String = "",
    characterName: String = "",
): String {
    val stateRendered = DISPLAY_STATE_REFERENCE.replace(template) { match ->
        (state[match.groupValues[1]] as? JsonPrimitive)?.content.orEmpty()
    }
    return DISPLAY_IDENTITY_REFERENCE.replace(stateRendered) { match ->
        if (match.groupValues[1] == "user") userName else characterName
    }
}

private val DISPLAY_STATE_REFERENCE = Regex("\\{\\{state\\.([a-z][a-z0-9]*(?:[._-][a-z0-9]+){0,15})\\}\\}")
private val DISPLAY_IDENTITY_REFERENCE = Regex("\\{\\{(user|char)\\}\\}")

fun AdaptationView.matchesMessage(sourceText: String): Boolean = when (trigger.type) {
    AdaptationTriggerType.MESSAGE_EXACT -> sourceText.trim() == trigger.value.trim()
    AdaptationTriggerType.MESSAGE_CONTAINS -> sourceText.contains(trigger.value)
    AdaptationTriggerType.ALWAYS -> true
}
