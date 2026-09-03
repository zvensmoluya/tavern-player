package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.AdaptationActionType
import io.github.zvensmoluya.tavernplayer.content.AdaptationArtifact
import io.github.zvensmoluya.tavernplayer.content.AdaptationFormField
import io.github.zvensmoluya.tavernplayer.content.AdaptationFormFieldType
import io.github.zvensmoluya.tavernplayer.content.AdaptationTriggerType
import io.github.zvensmoluya.tavernplayer.content.AdaptationUiNode
import io.github.zvensmoluya.tavernplayer.content.AdaptationView
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

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
        val effects: List<AdaptationEffect>,
    ) : AdaptationExecutionResult

    data class Failure(
        val code: String,
        val message: String,
    ) : AdaptationExecutionResult
}

class AdaptationRuntime(
    legacyStateAdapters: List<LegacyStateAdapter> = listOf(UpdateVariableSetV1Adapter()),
) {
    private val legacyStateAdapters = legacyStateAdapters.associateBy(LegacyStateAdapter::dialect)

    fun initialState(artifact: AdaptationArtifact?): ConversationRuntimeState = ConversationRuntimeState(
        conversationState = ConversationStateSnapshot(
            artifact?.state.orEmpty().mapNotNull { definition ->
                (definition.initialValue as? JsonPrimitive)?.let { definition.key to it }
            }.toMap(),
        ),
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

        val state = runtimeState.conversationState.values
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
                AdaptationActionType.STATE_SET,
                AdaptationActionType.STATE_INCREMENT,
                AdaptationActionType.STATE_TOGGLE,
                -> return AdaptationExecutionResult.Failure(
                    "FORM_STATE_WRITE_UNSUPPORTED",
                    "Native 表单只能生成待确认草稿，不能脱离消息时间线直接修改状态",
                )
            }
        }
        return AdaptationExecutionResult.Success(effects)
    }

    fun ingestAssistantMessage(
        artifact: AdaptationArtifact,
        sourceText: String,
        runtimeState: ConversationRuntimeState,
    ): AdaptationMessageIngestionResult {
        if (artifact.messageStateRules.isEmpty() || sourceText.isEmpty()) {
            return AdaptationMessageIngestionResult(runtimeState, 0)
        }
        val definitions = artifact.state.associateBy { it.key }
        val assignments = linkedMapOf<String, JsonPrimitive>()
        var applied = 0
        artifact.messageStateRules.forEach { rule ->
            val remaining = MAX_MESSAGE_UPDATES - applied
            if (remaining <= 0) return@forEach
            val adapter = legacyStateAdapters[rule.dialect] ?: return@forEach
            val decoded = adapter.decode(sourceText, rule.mappings, definitions, remaining)
            assignments.putAll(decoded.patch.assignments)
            applied += decoded.appliedUpdates
        }
        val nextState = runtimeState.conversationState.applying(ConversationStatePatch(assignments))
        return AdaptationMessageIngestionResult(runtimeState.copy(conversationState = nextState), applied)
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

    companion object {
        private const val MAX_FIELD_CHARS = 8_192
        private const val MAX_DRAFT_CHARS = 16_384
        private const val MAX_MESSAGE_UPDATES = 128
        private val TEMPLATE_REFERENCE = Regex("\\{\\{(form|state)\\.([a-z][a-z0-9]*(?:[._-][a-z0-9]+){0,15})\\}\\}")
    }
}

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
