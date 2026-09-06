package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.Serializable

/** Model-authored native behavior; original large text is resolved on the device. */
@Serializable
data class NativeCompilationDraft(
    val summary: String,
    val state: List<ConversationStateDefinition> = emptyList(),
    val assistantStateAdapters: List<AssistantStateAdapterDefinition> = emptyList(),
    val status: NativeStatusView? = null,
    val collections: List<NativeCollectionView> = emptyList(),
    val forms: List<NativeCompilationForm> = emptyList(),
    val progressions: List<NativeCompilationProgression> = emptyList(),
    val messagePanels: List<NativeMessagePanelView> = emptyList(),
    val worldBookTextSelections: List<NativeCompilationTextSelection> = emptyList(),
    val playerChoices: List<NativePlayerChoice> = emptyList(),
    val assessments: List<NativeCompilationAssessment> = emptyList(),
)
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
data class NativeCompilationProgression(
    val valueStateKey: String, val stageStateKey: String, val levels: List<NativeCompilationLevel>,
)
@Serializable
data class NativeCompilationLevel(val minValue: Double, val label: String, val exclusive: Boolean = false)
@Serializable
data class NativeCompilationTextSelection(
    val sourceId: String, val stateKey: String, val cases: List<NativeCompilationTextCase>,
    val prefixRef: String? = null, val suffixRef: String? = null,
)
@Serializable
data class NativeCompilationTextCase(val stateValue: String, val textRef: String)
@Serializable
data class NativeCompilationAssessment(
    val sourceId: String, val disposition: NativeCompilationDisposition, val reason: String,
    /** JSON pointers into assembled adaptation; checked for mapped claims. */
    val targets: List<String> = emptyList(),
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
