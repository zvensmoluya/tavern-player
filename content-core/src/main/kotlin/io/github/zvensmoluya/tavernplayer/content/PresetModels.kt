package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class PresetInjectionPosition {
    RELATIVE,
    ABSOLUTE,
}

@Serializable
enum class PresetGenerationTrigger(val wireValue: String) {
    NORMAL("normal"),
    REGENERATE("regenerate"),
}

@Serializable
enum class PresetNamesBehavior(val wireValue: Int) {
    NONE(-1),
    DEFAULT(0),
    COMPLETION(1),
    CONTENT(2),
}

@Serializable
enum class PresetReasoningEffort(val wireValue: String) {
    AUTO("auto"),
    MIN("min"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    MAX("max"),
}

@Serializable
enum class PresetVerbosity(val wireValue: String) {
    AUTO("auto"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

/**
 * A prompt definition is deliberately separate from [PresetPromptOrderEntry]. SillyTavern presets
 * keep a definition pool and may leave definitions outside the active order; those unused entries
 * must survive an import/edit/export cycle.
 */
@Serializable
data class PresetPromptDefinition(
    val identifier: String,
    val name: String = identifier,
    val role: ContentRole = ContentRole.SYSTEM,
    val content: String = "",
    val marker: Boolean = false,
    val systemPrompt: Boolean = false,
    val forbidOverrides: Boolean = false,
    val injectionPosition: PresetInjectionPosition = PresetInjectionPosition.RELATIVE,
    val injectionDepth: Int = 4,
    val injectionOrder: Int = 100,
    val triggers: Set<PresetGenerationTrigger> = PresetGenerationTrigger.entries.toSet(),
    val unknownTriggers: Set<String> = emptySet(),
    val raw: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class PresetPromptOrderEntry(
    val identifier: String,
    val enabled: Boolean = true,
    val raw: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class PresetGenerationSettings(
    val maxContextTokens: Int? = null,
    val maxOutputTokens: Int = 1_024,
    val temperature: Double? = 1.0,
    val topP: Double? = 1.0,
    val topK: Int? = 0,
    val topA: Double? = 0.0,
    val minP: Double? = 0.0,
    val repetitionPenalty: Double? = 1.0,
    val frequencyPenalty: Double? = 0.0,
    val presencePenalty: Double? = 0.0,
    val seed: Int? = null,
    val reasoningEffort: PresetReasoningEffort = PresetReasoningEffort.AUTO,
    val verbosity: PresetVerbosity = PresetVerbosity.AUTO,
)

@Serializable
data class PresetControlSettings(
    val newChatPrompt: String = "[Start a new Chat]",
    val newExampleChatPrompt: String = "[Example Chat]",
    val assistantPrefill: String = "",
    val worldInfoFormat: String = "{0}",
    val scenarioFormat: String = "{{scenario}}",
    val personalityFormat: String = "{{personality}}",
    val namesBehavior: PresetNamesBehavior = PresetNamesBehavior.DEFAULT,
    val squashSystemMessages: Boolean = false,
    val showThoughts: Boolean = true,
)

@Serializable
data class PresetAsset(
    val id: String,
    val sourceSha256: String,
    val contentSha256: String,
    val name: String,
    val prompts: List<PresetPromptDefinition>,
    val promptOrder: List<PresetPromptOrderEntry>,
    val generationSettings: PresetGenerationSettings = PresetGenerationSettings(),
    val controlSettings: PresetControlSettings = PresetControlSettings(),
    val regexScripts: List<RegexDefinition> = emptyList(),
    val sanitizedSource: JsonObject = JsonObject(emptyMap()),
    val diagnostics: List<CompatibilityDiagnostic> = emptyList(),
    val builtIn: Boolean = false,
) {
    /** Return a transaction-safe value graph that cannot observe later editor changes. */
    fun snapshot(): PresetAsset = copy(
        prompts = prompts.map { prompt ->
            prompt.copy(
                triggers = prompt.triggers.toSet(),
                unknownTriggers = prompt.unknownTriggers.toSet(),
                raw = JsonObject(prompt.raw.toMap()),
            )
        },
        promptOrder = promptOrder.map { entry ->
            entry.copy(raw = JsonObject(entry.raw.toMap()))
        },
        generationSettings = generationSettings.copy(),
        controlSettings = controlSettings.copy(),
        regexScripts = regexScripts.map { regex ->
            regex.copy(
                trimStrings = regex.trimStrings.toList(),
                placements = regex.placements.toSet(),
                raw = JsonObject(regex.raw.toMap()),
            )
        },
        sanitizedSource = JsonObject(sanitizedSource.toMap()),
        diagnostics = diagnostics.toList(),
    )
}

enum class PresetImportStatus {
    READY,
    READY_WITH_WARNINGS,
    REJECTED,
}

sealed interface PresetImportResult {
    val status: PresetImportStatus
    val diagnostics: List<CompatibilityDiagnostic>

    data class Ready(
        val preset: PresetAsset,
        /** UTF-8 ST JSON after connection credentials and endpoint data have been removed. */
        val sanitizedSourceBytes: ByteArray,
        override val diagnostics: List<CompatibilityDiagnostic>,
    ) : PresetImportResult {
        override val status: PresetImportStatus = if (diagnostics.isEmpty()) {
            PresetImportStatus.READY
        } else {
            PresetImportStatus.READY_WITH_WARNINGS
        }
    }

    data class Rejected(
        override val diagnostics: List<CompatibilityDiagnostic>,
    ) : PresetImportResult {
        override val status: PresetImportStatus = PresetImportStatus.REJECTED
    }
}
