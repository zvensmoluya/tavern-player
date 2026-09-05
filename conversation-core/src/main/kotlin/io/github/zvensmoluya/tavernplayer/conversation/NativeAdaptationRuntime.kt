package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.LegacyStateDialect
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import io.github.zvensmoluya.tavernplayer.content.NativeFormField
import io.github.zvensmoluya.tavernplayer.content.NativeFormFieldType
import io.github.zvensmoluya.tavernplayer.content.NativeFormView

data class NativeFormSubmission(
    val formId: String,
    val values: Map<String, List<String>>,
)

sealed interface NativeFormSubmissionResult {
    data class Draft(val text: String) : NativeFormSubmissionResult

    data class Rejected(
        val code: String,
        val message: String,
    ) : NativeFormSubmissionResult
}

data class AssistantStateIngestionResult(
    val runtimeState: ConversationRuntimeState,
    val appliedUpdates: Int,
    val rejection: String? = null,
)

enum class AssistantStateEnvelopeStatus {
    NONE,
    PENDING,
    STRIPPED,
    RECOVERED,
    INVALID,
}

data class NativeAssistantMessageProjection(
    val narrativeText: String,
    val envelopeStatus: AssistantStateEnvelopeStatus,
    val stateEnvelope: String? = null,
)

class NativeAdaptationRuntime(
    legacyStateAdapters: List<LegacyStateAdapter> = listOf(
        UpdateVariableSetV1Adapter(),
        UpdateVariableJsonPatchV1Adapter(),
    ),
) {
    private val legacyStateAdapters = legacyStateAdapters.associateBy(LegacyStateAdapter::dialect)

    fun initialState(adaptation: NativeAdaptation?): ConversationRuntimeState = ConversationRuntimeState(
        conversationState = ConversationStateSnapshot(
            adaptation?.state.orEmpty().associate { definition -> definition.key to definition.initialValue },
        ),
    )

    fun submitForm(
        adaptation: NativeAdaptation,
        submission: NativeFormSubmission,
        userName: String = "",
        characterName: String = "",
    ): NativeFormSubmissionResult {
        val form = adaptation.forms.firstOrNull { it.id == submission.formId }
            ?: return NativeFormSubmissionResult.Rejected("UNKNOWN_FORM", "找不到 Native Form")
        val fieldsById = form.fields.associateBy(NativeFormField::id)
        val unknown = submission.values.keys - fieldsById.keys
        if (unknown.isNotEmpty()) {
            return NativeFormSubmissionResult.Rejected("UNKNOWN_FIELD", "表单包含未知字段：${unknown.sorted().joinToString()}")
        }
        form.fields.forEach { field ->
            validateField(field, submission.values[field.id].orEmpty())?.let { return it }
        }
        val draftValues = form.fields.associate { field ->
            field.id to submission.values[field.id].orEmpty().filter(String::isNotBlank).ifEmpty { listOf(field.emptyText) }
        }
        val draft = renderDraft(form.draftTemplate, draftValues, userName, characterName)
        if (draft.length > MAX_DRAFT_CHARS) {
            return NativeFormSubmissionResult.Rejected("DRAFT_TOO_LONG", "生成的草稿超过 $MAX_DRAFT_CHARS 字符")
        }
        return NativeFormSubmissionResult.Draft(draft)
    }

    fun ingestAssistantMessage(
        adaptation: NativeAdaptation,
        sourceText: String,
        runtimeState: ConversationRuntimeState,
    ): AssistantStateIngestionResult {
        if (adaptation.assistantStateAdapters.isEmpty() || sourceText.isEmpty()) {
            return AssistantStateIngestionResult(runtimeState, 0)
        }
        val decoded = decodeAssistantMessage(adaptation, sourceText)
        if (!decoded.valid) return AssistantStateIngestionResult(runtimeState, 0, decoded.rejection)
        val nextState = runtimeState.conversationState.applying(decoded.patch)
        return AssistantStateIngestionResult(runtimeState.copy(conversationState = nextState), decoded.appliedUpdates)
    }

    fun decodeAssistantMessage(adaptation: NativeAdaptation, sourceText: String): LegacyStateDecodeResult {
        val definition = adaptation.assistantStateAdapters.singleOrNull()
        val adapter = definition?.let { legacyStateAdapters[it.dialect] }
            ?: return LegacyStateDecodeResult(ConversationStatePatch(), 0, "INVALID_STATE_ADAPTER")
        return adapter.decode(sourceText, definition.mappings, adaptation.state.associateBy { it.key }, MAX_MESSAGE_UPDATES)
    }

    /**
     * Separates a declared legacy state envelope from assistant prose without destroying sourceText.
     * During streaming, a partial envelope suffix is withheld so machine syntax never flashes in UI.
     * Complete but unrecognized/ambiguous envelopes remain visible for diagnosis and are never repaired.
     */
    fun projectAssistantMessage(
        adaptation: NativeAdaptation?,
        sourceText: String,
        stateConfirmedSeparately: Boolean = false,
        streaming: Boolean = false,
    ): NativeAssistantMessageProjection {
        val dialects = adaptation?.assistantStateAdapters.orEmpty().mapTo(linkedSetOf()) { it.dialect }
        if (dialects.isEmpty() || sourceText.isEmpty()) {
            return NativeAssistantMessageProjection(sourceText, AssistantStateEnvelopeStatus.NONE)
        }
        val open = sourceText.indexOf(UPDATE_BLOCK_OPEN, ignoreCase = true)
        if (open < 0) {
            val partial = sourceText.partialTagSuffixStart(UPDATE_BLOCK_OPEN)
            return if (partial >= 0) {
                NativeAssistantMessageProjection(
                    narrativeText = if (streaming) sourceText.substring(0, partial).trimEnd() else sourceText,
                    envelopeStatus = AssistantStateEnvelopeStatus.PENDING,
                )
            } else {
                NativeAssistantMessageProjection(sourceText, AssistantStateEnvelopeStatus.NONE)
            }
        }
        val contentStart = open + UPDATE_BLOCK_OPEN.length
        val close = sourceText.indexOf(UPDATE_BLOCK_CLOSE, contentStart, ignoreCase = true)
        if (close < 0) {
            if (!streaming) {
                // A closed inner payload gives a deterministic display boundary. Never execute the
                // incomplete outer envelope; otherwise preserve the whole suffix for diagnosis.
                val innerClose = sourceText.indexOf(JSON_PATCH_CLOSE, contentStart, ignoreCase = true)
                val unambiguous = sourceText.indexOf(UPDATE_BLOCK_OPEN, contentStart, ignoreCase = true) < 0 &&
                    sourceText.substring(contentStart).singleTaggedContent(JSON_PATCH_OPEN, JSON_PATCH_CLOSE) != null
                return NativeAssistantMessageProjection(
                    narrativeText = if (innerClose >= 0 && unambiguous) {
                        sourceText.withoutEnvelope(open, innerClose + JSON_PATCH_CLOSE.length)
                    } else sourceText,
                    envelopeStatus = if (stateConfirmedSeparately && unambiguous) {
                        AssistantStateEnvelopeStatus.RECOVERED
                    } else AssistantStateEnvelopeStatus.INVALID,
                )
            }
            return NativeAssistantMessageProjection(
                narrativeText = sourceText.substring(0, open).trimEnd(),
                envelopeStatus = AssistantStateEnvelopeStatus.PENDING,
            )
        }
        val envelopeEnd = close + UPDATE_BLOCK_CLOSE.length
        val ambiguous = sourceText.indexOf(UPDATE_BLOCK_OPEN, contentStart, ignoreCase = true) >= 0 ||
            sourceText.indexOf(UPDATE_BLOCK_CLOSE, envelopeEnd, ignoreCase = true) >= 0
        val envelope = sourceText.substring(open, envelopeEnd)
        if (ambiguous || envelope.length > MAX_UPDATE_BLOCK_CHARS ||
            !decodeAssistantMessage(checkNotNull(adaptation), envelope).valid
        ) {
            if (!ambiguous && stateConfirmedSeparately) {
                return NativeAssistantMessageProjection(
                    narrativeText = sourceText.withoutEnvelope(open, envelopeEnd),
                    envelopeStatus = AssistantStateEnvelopeStatus.RECOVERED,
                )
            }
            return NativeAssistantMessageProjection(sourceText, AssistantStateEnvelopeStatus.INVALID)
        }
        return NativeAssistantMessageProjection(
            narrativeText = sourceText.withoutEnvelope(open, envelopeEnd),
            envelopeStatus = AssistantStateEnvelopeStatus.STRIPPED,
            stateEnvelope = envelope,
        )
    }

    private fun validateField(field: NativeFormField, values: List<String>): NativeFormSubmissionResult.Rejected? {
        if (values.any { it.length > MAX_FIELD_CHARS }) {
            return NativeFormSubmissionResult.Rejected("FIELD_TOO_LONG", "字段 ${field.label} 超过 $MAX_FIELD_CHARS 字符")
        }
        if (field.required && values.none(String::isNotBlank)) {
            return NativeFormSubmissionResult.Rejected("REQUIRED_FIELD", "请填写${field.label}")
        }
        if (field.type != NativeFormFieldType.MULTI_SELECT && values.size > 1) {
            return NativeFormSubmissionResult.Rejected("MULTIPLE_VALUES", "字段 ${field.label} 只接受一个值")
        }
        return when (field.type) {
            NativeFormFieldType.NUMBER -> values.firstOrNull()?.takeIf(String::isNotBlank)?.let { raw ->
                if (raw.toDoubleOrNull()?.isFinite() != true) {
                    NativeFormSubmissionResult.Rejected("INVALID_NUMBER", "${field.label}必须是数值")
                } else null
            }
            NativeFormFieldType.SINGLE_SELECT,
            NativeFormFieldType.MULTI_SELECT,
            -> {
                val allowed = field.options.mapTo(mutableSetOf()) { it.value }
                if (values.any { it !in allowed }) {
                    NativeFormSubmissionResult.Rejected("INVALID_OPTION", "${field.label}包含无效选项")
                } else null
            }
            NativeFormFieldType.TOGGLE -> if (values.any { it != "true" && it != "false" }) {
                NativeFormSubmissionResult.Rejected("INVALID_BOOLEAN", "${field.label}必须是布尔值")
            } else null
            else -> null
        }
    }

    private fun renderDraft(
        template: String,
        form: Map<String, List<String>>,
        userName: String,
        characterName: String,
    ): String {
        return DRAFT_REFERENCE.replace(template) { match ->
            when (val reference = match.groupValues[1]) {
                "user" -> userName
                "char" -> characterName
                else -> form[reference.removePrefix("form.")].orEmpty().joinToString("、")
            }
        }
    }

    companion object {
        private const val MAX_FIELD_CHARS = 8_192
        private const val MAX_DRAFT_CHARS = 16_384
        private const val MAX_MESSAGE_UPDATES = 128
        private val DRAFT_REFERENCE = Regex("\\{\\{(user|char|form\\.[a-z][a-z0-9]*(?:[._-][a-z0-9]+){0,15})\\}\\}")
        private const val UPDATE_BLOCK_OPEN = "<UpdateVariable>"
        private const val UPDATE_BLOCK_CLOSE = "</UpdateVariable>"
        private const val JSON_PATCH_OPEN = "<JSONPatch>"
        private const val JSON_PATCH_CLOSE = "</JSONPatch>"
        private const val MAX_UPDATE_BLOCK_CHARS = 128 * 1024
    }
}

private fun String.withoutEnvelope(start: Int, endExclusive: Int): String {
    val before = substring(0, start).trimEnd()
    val after = substring(endExclusive).trimStart()
    return when {
        before.isEmpty() -> after
        after.isEmpty() -> before
        else -> "$before\n\n$after"
    }
}

private fun String.partialTagSuffixStart(tag: String): Int {
    val max = minOf(length, tag.length - 1)
    for (size in max downTo 1) {
        if (regionMatches(length - size, tag, 0, size, ignoreCase = true)) return length - size
    }
    return -1
}

fun NativeFormView.matchesMessage(sourceText: String): Boolean = marker.isNotEmpty() && sourceText.contains(marker)
