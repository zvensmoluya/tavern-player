package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.AdaptationActionType
import io.github.zvensmoluya.tavernplayer.content.AdaptationArtifact
import io.github.zvensmoluya.tavernplayer.content.AdaptationFormField
import io.github.zvensmoluya.tavernplayer.content.AdaptationFormFieldType
import io.github.zvensmoluya.tavernplayer.content.AdaptationStateDefinition
import io.github.zvensmoluya.tavernplayer.content.AdaptationStateType
import io.github.zvensmoluya.tavernplayer.content.AdaptationTriggerType
import io.github.zvensmoluya.tavernplayer.content.AdaptationUiNode
import io.github.zvensmoluya.tavernplayer.content.AdaptationView
import kotlinx.serialization.json.JsonElement
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
        return IDENTITY_REFERENCE.replace(scoped) { match ->
            if (match.groupValues[1] == "user") userName else characterName
        }
    }

    private fun parseStateValue(definition: AdaptationStateDefinition, raw: String?): JsonPrimitive? = when (definition.type) {
        AdaptationStateType.STRING -> JsonPrimitive(raw.orEmpty())
        AdaptationStateType.NUMBER -> raw?.toDoubleOrNull()?.takeIf(Double::isFinite)?.let(::JsonPrimitive)
        AdaptationStateType.BOOLEAN -> raw?.toBooleanStrictOrNull()?.let(::JsonPrimitive)
    }

    companion object {
        private const val MAX_FIELD_CHARS = 8_192
        private const val MAX_DRAFT_CHARS = 16_384
        private val TEMPLATE_REFERENCE = Regex("\\{\\{(form|state)\\.([a-z][a-z0-9]*(?:[._-][a-z0-9]+){0,15})\\}\\}")
        private val IDENTITY_REFERENCE = Regex("\\{\\{(user|char)\\}\\}")
    }
}

fun AdaptationView.matchesMessage(sourceText: String): Boolean = when (trigger.type) {
    AdaptationTriggerType.MESSAGE_EXACT -> sourceText.trim() == trigger.value.trim()
    AdaptationTriggerType.MESSAGE_CONTAINS -> sourceText.contains(trigger.value)
    AdaptationTriggerType.ALWAYS -> true
}
