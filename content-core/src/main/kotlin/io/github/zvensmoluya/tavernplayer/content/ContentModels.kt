package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
enum class CharacterSourceFormat {
    PNG,
    JSON,
}

@Serializable
enum class CharacterCardGeneration {
    V1,
    V2,
    V3,
}

@Serializable
enum class ContentRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

@Serializable
enum class CompatibilitySeverity {
    WARNING,
    ERROR,
}

@Serializable
data class CompatibilityDiagnostic(
    val severity: CompatibilitySeverity,
    val code: String,
    val message: String,
    val source: String? = null,
)

@Serializable
data class CharacterDepthPrompt(
    val content: String,
    val role: ContentRole = ContentRole.SYSTEM,
    val depth: Int = 4,
    val order: Int = 100,
)

@Serializable
data class CharacterAssetReference(
    val id: String,
    val type: String,
    val uri: String,
    val name: String,
    val extension: String,
) {
    val isLocallyMaterializableImage: Boolean
        get() = uri == "ccdefault:" || SAFE_INLINE_IMAGE_PREFIX.matches(uri.substringBefore(','))

    private companion object {
        val SAFE_INLINE_IMAGE_PREFIX = Regex("(?i)data:image/(?:png|jpeg|webp);base64")
    }
}

@Serializable
enum class WorldBookPosition {
    BEFORE_CHARACTER,
    AFTER_CHARACTER,
    AUTHOR_NOTE_TOP,
    AUTHOR_NOTE_BOTTOM,
    AT_DEPTH,
    EXAMPLES_TOP,
    EXAMPLES_BOTTOM,
    OUTLET,
}

@Serializable
enum class WorldBookSecondaryLogic {
    AND_ANY,
    AND_ALL,
    NOT_ANY,
    NOT_ALL,
}

@Serializable
data class WorldBookDefinition(
    val id: String,
    val name: String = "",
    val description: String = "",
    val scanDepth: Int? = null,
    val tokenBudget: Int? = null,
    val recursiveScanning: Boolean? = null,
    val entries: List<WorldBookEntryDefinition> = emptyList(),
    val extensions: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class WorldBookEntryDefinition(
    val id: String,
    val sourceId: String? = null,
    val name: String = "",
    val comment: String = "",
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val content: String = "",
    val enabled: Boolean = true,
    val constant: Boolean = false,
    val selective: Boolean = false,
    val secondaryLogic: WorldBookSecondaryLogic = WorldBookSecondaryLogic.AND_ANY,
    val insertionOrder: Int = 100,
    val priority: Int? = null,
    val position: WorldBookPosition = WorldBookPosition.BEFORE_CHARACTER,
    val depth: Int = 4,
    val role: ContentRole = ContentRole.SYSTEM,
    val outletName: String = "",
    val useRegex: Boolean = false,
    val caseSensitive: Boolean? = null,
    val matchWholeWords: Boolean? = null,
    val probability: Int = 100,
    val useProbability: Boolean = true,
    val group: String = "",
    val groupOverride: Boolean = false,
    val groupWeight: Int = 100,
    val useGroupScoring: Boolean = false,
    val excludeRecursion: Boolean = false,
    val preventRecursion: Boolean = false,
    val delayUntilRecursion: Boolean = false,
    val scanDepth: Int? = null,
    val sticky: Int = 0,
    val cooldown: Int = 0,
    val delay: Int = 0,
    val extensions: JsonObject = JsonObject(emptyMap()),
) {
    /** ST 原生条目选项。保留在源 extensions 中，旧快照也可读取，无第二份可分歧的值。 */
    val ignoreBudget: Boolean get() = extensions["ignore_budget"] == JsonPrimitive(true)
}

@Serializable
enum class RegexPlacement(val wireValue: Int) {
    MARKDOWN_DISPLAY(0),
    USER_INPUT(1),
    AI_OUTPUT(2),
    SLASH_COMMAND(3),
    WORLD_INFO(5),
    REASONING(6),
}

@Serializable
enum class RegexSubstitutionMode {
    NONE,
    RAW,
    ESCAPED,
}

@Serializable
data class RegexDefinition(
    val id: String,
    val name: String,
    val findRegex: String,
    val replaceString: String,
    val trimStrings: List<String> = emptyList(),
    val placements: Set<RegexPlacement> = emptySet(),
    val disabled: Boolean = false,
    val markdownOnly: Boolean = false,
    val promptOnly: Boolean = false,
    val runOnEdit: Boolean = false,
    val substitutionMode: RegexSubstitutionMode = RegexSubstitutionMode.NONE,
    val minDepth: Int? = null,
    val maxDepth: Int? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class CharacterAsset(
    val id: String,
    val sourceSha256: String = id,
    val sourceFormat: CharacterSourceFormat = CharacterSourceFormat.JSON,
    val cardGeneration: CharacterCardGeneration = CharacterCardGeneration.V3,
    val spec: String? = null,
    val specVersion: String? = null,
    val name: String,
    val nickname: String? = null,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val alternateFirstMessages: List<String> = emptyList(),
    val groupOnlyGreetings: List<String> = emptyList(),
    val rawMessageExamples: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val creatorNotes: String = "",
    val creatorNotesMultilingual: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
    val creator: String = "",
    val characterVersion: String = "",
    val sourceLinks: List<String> = emptyList(),
    val creationDateEpochSeconds: Long? = null,
    val modificationDateEpochSeconds: Long? = null,
    val depthPrompt: CharacterDepthPrompt? = null,
    val worldBooks: List<WorldBookDefinition> = emptyList(),
    val regexScripts: List<RegexDefinition> = emptyList(),
    val assets: List<CharacterAssetReference> = emptyList(),
    val extensions: JsonObject = JsonObject(emptyMap()),
    val rawCard: JsonObject = JsonObject(emptyMap()),
    val diagnostics: List<CompatibilityDiagnostic> = emptyList(),
    val nativeAdaptation: NativeAdaptation? = null,
) {
    val promptName: String
        get() = nickname?.takeIf(String::isNotBlank) ?: name

    fun snapshot(): CharacterSnapshot = CharacterSnapshot(
        assetId = id,
        sourceSha256 = sourceSha256,
        name = name,
        promptName = promptName,
        description = description,
        personality = personality,
        scenario = scenario,
        firstMessage = firstMessage,
        alternateFirstMessages = alternateFirstMessages.toList(),
        rawMessageExamples = rawMessageExamples,
        systemPrompt = systemPrompt,
        postHistoryInstructions = postHistoryInstructions,
        creatorNotes = creatorNotes,
        creator = creator,
        characterVersion = characterVersion,
        depthPrompt = depthPrompt?.copy(),
        worldBooks = worldBooks.map { book ->
            book.copy(
                entries = book.entries.map { entry ->
                    entry.copy(
                        keys = entry.keys.toList(),
                        secondaryKeys = entry.secondaryKeys.toList(),
                        extensions = JsonObject(entry.extensions.toMap()),
                    )
                },
                extensions = JsonObject(book.extensions.toMap()),
            )
        },
        regexScripts = regexScripts.map { regex ->
            regex.copy(
                trimStrings = regex.trimStrings.toList(),
                placements = regex.placements.toSet(),
                raw = JsonObject(regex.raw.toMap()),
            )
        },
        assets = assets.map { it.copy() },
        diagnostics = diagnostics.toList(),
        nativeAdaptation = nativeAdaptation,
    )
}

@Serializable
data class CharacterSnapshot(
    val assetId: String,
    val sourceSha256: String,
    val name: String,
    val promptName: String,
    val description: String,
    val personality: String,
    val scenario: String,
    val firstMessage: String,
    val alternateFirstMessages: List<String>,
    val rawMessageExamples: String,
    val systemPrompt: String,
    val postHistoryInstructions: String,
    val creatorNotes: String,
    val creator: String,
    val characterVersion: String,
    val depthPrompt: CharacterDepthPrompt?,
    val worldBooks: List<WorldBookDefinition>,
    val regexScripts: List<RegexDefinition>,
    val assets: List<CharacterAssetReference> = emptyList(),
    val diagnostics: List<CompatibilityDiagnostic>,
    val nativeAdaptation: NativeAdaptation? = null,
)

enum class CharacterImportStatus {
    READY,
    READY_WITH_WARNINGS,
    REJECTED,
}


sealed interface CharacterImportResult {
    val status: CharacterImportStatus
    val diagnostics: List<CompatibilityDiagnostic>

    data class Ready(
        val character: CharacterAsset,
        val sourceBytes: ByteArray,
        override val diagnostics: List<CompatibilityDiagnostic>,
    ) : CharacterImportResult {
        override val status: CharacterImportStatus = if (diagnostics.isEmpty()) {
            CharacterImportStatus.READY
        } else {
            CharacterImportStatus.READY_WITH_WARNINGS
        }
    }

    data class Rejected(
        override val diagnostics: List<CompatibilityDiagnostic>,
    ) : CharacterImportResult {
        override val status: CharacterImportStatus = CharacterImportStatus.REJECTED
    }
}
