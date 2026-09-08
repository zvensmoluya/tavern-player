package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.Serializable

/** The model selects ownership from source evidence before seeing a branch-specific contract. */
@Serializable
data class NativeCompilationSelection(val runtime: NativeStateSource, val schemaSourceId: String? = null)

sealed interface NativeCompilationSelectionResult {
    data class Ready(val selection: NativeCompilationSelection) : NativeCompilationSelectionResult
    data class Rejected(val issues: List<NativeAdaptationValidationIssue>) : NativeCompilationSelectionResult
}

/** Model-authored native behavior; original large text is resolved on the device. */
@Serializable
data class NativeCompilationDraft(
    val summary: String,
    val state: List<ConversationStateDefinition> = emptyList(),
    val assistantStateAdapters: List<AssistantStateAdapterDefinition> = emptyList(),
    val status: NativeStatusView? = null,
    val collections: List<NativeCollectionView> = emptyList(),
    val forms: List<NativeCompilationForm> = emptyList(),
    val messagePanels: List<NativeMessagePanelView> = emptyList(),
    val playerChoices: List<NativePlayerChoice> = emptyList(),
    val limitations: List<String> = emptyList(),
    val mvu: NativeCompilationMvu? = null,
    val ejsSourceIds: List<String> = emptyList(),
    val stateBindings: List<NativeStateBinding> = emptyList(),
    val script: NativeScriptProgram? = null,
)
@Serializable
data class NativeCompilationMvu(val schemaSourceId: String)
@Serializable
data class NativeCompilationForm(
    val id: String, val title: String, val sourceId: String, val marker: String,
    val description: String = "", val fields: List<NativeFormField>,
    val draft: NativeCompilationTemplate, val submitLabel: String = "写入草稿",
)
/** Exact unique source anchors excluding the anchors. No code is evaluated. */
@Serializable
data class NativeCompilationTemplate(
    val after: String, val before: String,
    /** Whole interpolation spelling -> Native form field ID; model analyzes its meaning. */
    val bindings: Map<String, String> = emptyMap(),
)
@Serializable
enum class NativeCompilationDisposition { RESTORED, PRESENTATION_ONLY, UNSUPPORTED, UNCERTAIN }
@Serializable
data class NativeCompilationEvidence(
    val sourcePath: String, val behavior: String, val disposition: NativeCompilationDisposition, val impact: String,
)
sealed interface NativeCompilationResult {
    data class Ready(val adaptation: NativeAdaptation, val evidence: List<NativeCompilationEvidence>) : NativeCompilationResult
    data class Rejected(val issues: List<NativeAdaptationValidationIssue>) : NativeCompilationResult
}
