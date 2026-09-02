package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

const val ADAPTATION_SCHEMA_VERSION: Int = 1
const val PROGRAM_VIEW_SCHEMA_VERSION: Int = 1

@Serializable
data class ProgramView(
    val schemaVersion: Int = PROGRAM_VIEW_SCHEMA_VERSION,
    val sourceSha256: String,
    val programBlocks: List<ProgramBlock> = emptyList(),
    val worldBookHandles: List<WorldBookHandle> = emptyList(),
    val stateProtocolHints: List<ProgramStateProtocolHint> = emptyList(),
    val dependencies: List<ProgramDependency> = emptyList(),
    val observedCapabilities: List<String> = emptyList(),
    val referencedVariables: List<String> = emptyList(),
    val omittedContent: List<OmittedContent> = emptyList(),
    val redactions: List<ProgramRedaction> = emptyList(),
)

@Serializable
enum class ProgramBlockKind {
    SCRIPT,
    ACTIVE_MARKUP,
}

@Serializable
data class ProgramBlock(
    val id: String,
    val kind: ProgramBlockKind,
    val sourcePath: String,
    val name: String = "",
    val language: String,
    val content: String,
    val originalSha256: String,
    val enabled: Boolean = true,
    val triggerPattern: String? = null,
    val triggerMatchMode: String? = null,
    val placements: List<Int> = emptyList(),
)

@Serializable
data class WorldBookHandle(
    val handle: String,
    val name: String = "",
    val enabled: Boolean,
    val contentChars: Int,
    val contentSha256: String,
)

@Serializable
data class ProgramStateProtocolHint(
    val dialect: String,
    val variableName: String,
    val values: List<ProgramStateValueHint> = emptyList(),
)

@Serializable
data class ProgramStateValueHint(
    val path: String,
    val type: AdaptationStateType,
    val initialValue: JsonElement,
)

@Serializable
data class ProgramDependency(
    val id: String,
    val kind: String,
    val locator: String,
)

@Serializable
data class OmittedContent(
    val field: String,
    val chars: Int,
    val sha256: String,
)

@Serializable
data class ProgramRedaction(
    val kind: String,
    val count: Int,
)

@Serializable
data class AdaptationArtifact(
    val schemaVersion: Int = ADAPTATION_SCHEMA_VERSION,
    val sourceSha256: String,
    val compiler: AdaptationCompiler,
    val status: AdaptationStatus,
    val requiredCapabilities: List<String> = emptyList(),
    val state: List<AdaptationStateDefinition> = emptyList(),
    val messageStateRules: List<AdaptationMessageStateRule> = emptyList(),
    val views: List<AdaptationView> = emptyList(),
    val report: AdaptationReport = AdaptationReport(),
)

@Serializable
data class AdaptationCompiler(
    val id: String,
    val version: String,
    val model: String? = null,
)

@Serializable
enum class AdaptationStatus {
    FULL,
    PARTIAL,
}

@Serializable
enum class AdaptationStateType {
    STRING,
    NUMBER,
    BOOLEAN,
}

@Serializable
data class AdaptationStateDefinition(
    val key: String,
    val type: AdaptationStateType,
    val initialValue: JsonElement = JsonPrimitive(""),
)

@Serializable
enum class AdaptationMessageStateDialect {
    UPDATE_VARIABLE_SET_V1,
}

@Serializable
data class AdaptationMessageStateRule(
    val dialect: AdaptationMessageStateDialect,
    val mappings: List<AdaptationMessageStateMapping> = emptyList(),
)

@Serializable
data class AdaptationMessageStateMapping(
    val sourcePath: String,
    val target: String,
)

@Serializable
enum class AdaptationViewPlacement {
    MESSAGE_REPLACEMENT,
    MESSAGE_ATTACHMENT,
    CONVERSATION_HEADER,
}

@Serializable
data class AdaptationView(
    val id: String,
    val title: String = "",
    val placement: AdaptationViewPlacement,
    val trigger: AdaptationViewTrigger,
    val nodes: List<AdaptationUiNode>,
    val submitLabel: String? = null,
    val submitActions: List<AdaptationAction> = emptyList(),
)

@Serializable
enum class AdaptationTriggerType {
    MESSAGE_EXACT,
    MESSAGE_CONTAINS,
    ALWAYS,
}

@Serializable
data class AdaptationViewTrigger(
    val type: AdaptationTriggerType,
    val value: String = "",
)

@Serializable
enum class AdaptationUiNodeType {
    SECTION,
    TEXT,
    STATUS,
    FORM,
}

@Serializable
data class AdaptationUiNode(
    val id: String,
    val type: AdaptationUiNodeType,
    val title: String = "",
    val text: String = "",
    val stateKey: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val children: List<AdaptationUiNode> = emptyList(),
    val fields: List<AdaptationFormField> = emptyList(),
)

@Serializable
enum class AdaptationFormFieldType {
    TEXT,
    MULTILINE_TEXT,
    NUMBER,
    SINGLE_SELECT,
    MULTI_SELECT,
    TOGGLE,
}

@Serializable
data class AdaptationFormField(
    val id: String,
    val type: AdaptationFormFieldType,
    val label: String,
    val placeholder: String = "",
    val required: Boolean = false,
    val options: List<AdaptationFormOption> = emptyList(),
    val initialValue: String = "",
)

@Serializable
data class AdaptationFormOption(
    val value: String,
    val label: String = value,
)

@Serializable
enum class AdaptationActionType {
    CHAT_SET_DRAFT,
    STATE_SET,
    STATE_INCREMENT,
    STATE_TOGGLE,
}

@Serializable
data class AdaptationAction(
    val type: AdaptationActionType,
    val target: String? = null,
    val value: String? = null,
    val template: String? = null,
)

@Serializable
data class AdaptationReport(
    val summary: String = "",
    val restoredBehaviors: List<String> = emptyList(),
    val unsupportedBehaviors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class AdaptationValidationIssue(
    val path: String,
    val code: String,
    val message: String,
)

data class AdaptationValidationResult(
    val issues: List<AdaptationValidationIssue>,
) {
    val valid: Boolean
        get() = issues.isEmpty()
}
