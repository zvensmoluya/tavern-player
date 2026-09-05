package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

const val NATIVE_ADAPTATION_SCHEMA_VERSION: Int = 1

/**
 * Player-owned native content captured with a Character snapshot.
 *
 * This is intentionally not a component tree or an action graph. Each collection belongs to one
 * concrete Tavern Player surface. A setup form owns a single initialization before its chat draft.
 */
@Serializable
data class NativeAdaptation(
    val schemaVersion: Int = NATIVE_ADAPTATION_SCHEMA_VERSION,
    val sourceSha256: String,
    val state: List<ConversationStateDefinition> = emptyList(),
    val assistantStateAdapters: List<AssistantStateAdapterDefinition> = emptyList(),
    val status: NativeStatusView? = null,
    val scenes: List<NativeSceneView> = emptyList(),
    val collections: List<NativeCollectionView> = emptyList(),
    val forms: List<NativeFormView> = emptyList(),
    val progressions: List<NativeProgressionDefinition> = emptyList(),
    val messagePanels: List<NativeMessagePanelView> = emptyList(),
    val worldBookTextSelections: List<NativeWorldBookTextSelection> = emptyList(),
    val playerChoices: List<NativePlayerChoice> = emptyList(),
    val report: NativeCompatibilityReport = NativeCompatibilityReport(),
)

/** Player 的显式确认流程：一个枚举门槛、一个枚举事实和一份普通聊天草稿。 */
@Serializable
data class NativePlayerChoice(
    val id: String,
    val title: String,
    val description: String,
    val availabilityStateKey: String,
    val availableValues: List<String>,
    val unavailableLabel: String,
    val stateKey: String,
    val stateValue: String,
    val draft: String,
)

/** 世界书原文的有限分支：只读取一个枚举状态，不改变条目的启用、位置、角色或预算。 */
@Serializable
data class NativeWorldBookTextSelection(
    val bookId: String,
    val entryId: String,
    val stateKey: String,
    val sourceContentSha256: String,
    val cases: List<NativeWorldBookTextCase>,
)

/** 对原始 entry.content 的 UTF-16 半开区间引用，不允许适配提供新的 Prompt 文本。 */
@Serializable
data class NativeWorldBookTextCase(
    val stateValue: String,
    val sourceStart: Int,
    val sourceEndExclusive: Int,
)

@Serializable
enum class ConversationStateValueType {
    STRING,
    NUMBER,
    BOOLEAN,
    RECORD,
    COLLECTION,
}

@Serializable
enum class ConversationStateScalarType {
    STRING,
    NUMBER,
    BOOLEAN,
}

@Serializable
data class ConversationStateFieldDefinition(
    val key: String,
    val label: String = key,
    val type: ConversationStateScalarType,
    val description: String = "",
)

@Serializable
data class ConversationStateDefinition(
    val key: String,
    val label: String = key,
    val type: ConversationStateValueType,
    val description: String = "",
    val initialValue: JsonElement = JsonPrimitive(""),
    val fields: List<ConversationStateFieldDefinition> = emptyList(),
    val numberRange: NativeNumberRange? = null,
    val allowedStrings: List<String> = emptyList(),
)

@Serializable
data class NativeNumberRange(val min: Double, val max: Double)

/** 固定阶段表：一个数值决定所在阶段，一个可选事件标记决定该阶段是否解锁。 */
@Serializable
data class NativeProgressionDefinition(
    val valueStateKey: String,
    val stageStateKey: String,
    val levels: List<NativeProgressionLevel>,
)

@Serializable
data class NativeProgressionLevel(
    val minValue: Double,
    val label: String,
    val unlockStateKey: String? = null,
    val lockedLabel: String = "",
)

/** 从原消息的已知标签读取资料；布局、折叠和文字呈现由 Player 决定。 */
@Serializable
data class NativeMessagePanelView(
    val id: String,
    val title: String,
    val sourceTag: String,
    val fields: List<NativeMessagePanelField>,
)

@Serializable
data class NativeMessagePanelField(val tag: String, val label: String)

@Serializable
enum class LegacyStateDialect {
    UPDATE_VARIABLE_SET_V1,
    UPDATE_VARIABLE_JSON_PATCH_V1,
}

@Serializable
data class AssistantStateAdapterDefinition(
    val dialect: LegacyStateDialect,
    val mappings: List<AssistantStateMapping> = emptyList(),
)

@Serializable
data class AssistantStateMapping(
    val sourcePath: String,
    val targetStateKey: String,
    val writable: Boolean = true,
)

@Serializable
data class NativeStatusView(
    val title: String = "状态",
    val items: List<NativeStatusItem>,
)

@Serializable
data class NativeStatusItem(
    val stateKey: String,
    val label: String,
    val min: Double? = null,
    val max: Double? = null,
)

/** A fixed Player scene surface selected by one scalar Conversation State value. */
@Serializable
data class NativeSceneView(
    val id: String,
    val title: String = "",
    val stateKey: String,
    val assets: List<NativeSceneAsset>,
    val emptyLabel: String = "暂无场景图片",
)

@Serializable
data class NativeSceneAsset(
    val stateValue: String,
    val assetId: String,
    val contentDescription: String = "",
)

@Serializable
data class NativeCollectionView(
    val id: String,
    val title: String,
    val stateKey: String,
    val emptyLabel: String = "暂无内容",
    val fields: List<NativeCollectionField> = emptyList(),
)

@Serializable
data class NativeCollectionField(
    val key: String,
    val label: String,
)

@Serializable
data class NativeFormView(
    val id: String,
    val title: String,
    val marker: String = "",
    val description: String = "",
    val fields: List<NativeFormField>,
    val draftTemplate: String,
    val submitLabel: String = "写入草稿",
    val setup: NativeSetupContract? = null,
    val openingIndices: List<Int> = emptyList(),
    val replacedDisplayRegexIds: List<String> = emptyList(),
)

@Serializable
enum class NativeFormFieldType {
    TEXT,
    MULTILINE_TEXT,
    NUMBER,
    SINGLE_SELECT,
    MULTI_SELECT,
    TOGGLE,
}

@Serializable
data class NativeFormField(
    val id: String,
    val type: NativeFormFieldType,
    val label: String,
    val placeholder: String = "",
    val required: Boolean = false,
    val options: List<NativeFormOption> = emptyList(),
    val initialValues: List<String> = emptyList(),
    val emptyText: String = "",
)

@Serializable
data class NativeFormOption(
    val value: String,
    val label: String = value,
    val setup: NativeSetupPayload? = null,
)

/** 固定开局流程，只允许常量和标量字段的一对一复制。 */
@Serializable
data class NativeSetupContract(
    val values: NativeSetupPayload = NativeSetupPayload(),
    val stateFields: Map<String, String> = emptyMap(),
    val openingIndex: Int? = null,
)

@Serializable
data class NativeSetupPayload(
    val stateValues: Map<String, JsonElement> = emptyMap(),
    val worldBookOverrides: List<NativeSetupWorldBookOverride> = emptyList(),
)

@Serializable
data class NativeSetupWorldBookOverride(
    val bookId: String,
    val entryId: String? = null,
    val enabled: Boolean,
)

@Serializable
enum class NativeCompatibilityStatus {
    FULL,
    PARTIAL,
}

@Serializable
data class NativeCompatibilityReport(
    val status: NativeCompatibilityStatus = NativeCompatibilityStatus.PARTIAL,
    val summary: String = "",
    val restoredBehaviors: List<String> = emptyList(),
    val degradedPresentation: List<String> = emptyList(),
    val unsupportedBehaviors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class NativeAdaptationValidationIssue(
    val path: String,
    val code: String,
    val message: String,
)

data class NativeAdaptationValidationResult(
    val issues: List<NativeAdaptationValidationIssue>,
) {
    val valid: Boolean
        get() = issues.isEmpty()
}
