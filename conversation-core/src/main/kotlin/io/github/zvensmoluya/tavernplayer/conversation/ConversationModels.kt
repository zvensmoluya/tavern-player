package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.CharacterDepthPrompt
import io.github.zvensmoluya.tavernplayer.content.ContentRole
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import io.github.zvensmoluya.tavernplayer.content.PresetAsset
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationSettings
import io.github.zvensmoluya.tavernplayer.content.PresetInjectionPosition
import io.github.zvensmoluya.tavernplayer.content.PresetPromptDefinition
import io.github.zvensmoluya.tavernplayer.content.PresetPromptOrderEntry
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

typealias CharacterAsset = io.github.zvensmoluya.tavernplayer.content.CharacterAsset
typealias CharacterSnapshot = io.github.zvensmoluya.tavernplayer.content.CharacterSnapshot

@Serializable
enum class MessageRole { SYSTEM, USER, ASSISTANT }

typealias InjectionPosition = PresetInjectionPosition
typealias PromptDefinition = PresetPromptDefinition
typealias PromptOrderEntry = PresetPromptOrderEntry
typealias Preset = PresetAsset

@Serializable
data class ExampleMessage(val role: MessageRole, val content: String)

@Serializable
data class DialogueExample(val messages: List<ExampleMessage>)

data class DepthPrompt(
    val content: String,
    val role: MessageRole = MessageRole.SYSTEM,
    val depth: Int = 4,
    val order: Int = 100,
)

@Suppress("FunctionName")
fun CharacterAsset(
    id: String,
    name: String,
    description: String = "",
    personality: String = "",
    scenario: String = "",
    firstMessage: String = "",
    alternateFirstMessages: List<String> = emptyList(),
    examples: List<DialogueExample> = emptyList(),
    systemPrompt: String = "",
    postHistoryInstructions: String = "",
    depthPrompt: DepthPrompt? = null,
): CharacterAsset = io.github.zvensmoluya.tavernplayer.content.CharacterAsset(
    id = id,
    name = name,
    description = description,
    personality = personality,
    scenario = scenario,
    firstMessage = firstMessage,
    alternateFirstMessages = alternateFirstMessages.toList(),
    rawMessageExamples = encodeDialogueExamples(examples),
    systemPrompt = systemPrompt,
    postHistoryInstructions = postHistoryInstructions,
    depthPrompt = depthPrompt?.let {
        CharacterDepthPrompt(it.content, it.role.toContentRole(), it.depth, it.order)
    },
)

val CharacterSnapshot.examples: List<DialogueExample>
    get() = parseDialogueExamples(rawMessageExamples, promptName, "User")

@Serializable
data class Persona(
    val id: String,
    val name: String,
    val avatar: String? = null,
    val description: String = "",
)

@Serializable
data class ReasoningBlock(val text: String = "", val signature: String? = null)

@Serializable
data class ConversationMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val authorName: String,
    val sourceText: String = content,
    val stateConfirmation: String? = null,
    val reasoning: List<ReasoningBlock> = emptyList(),
    val adapterId: String? = null,
    val createdAtEpochMillis: Long = 0,
)

@Serializable
data class MacroValue(val text: String, val numeric: Boolean = false)

@Serializable
data class WorldBookEntryRuntimeState(
    val stickyRemaining: Int = 0,
    val cooldownRemaining: Int = 0,
    val lastActivatedTurn: Int? = null,
)

@Serializable
data class WorldBookActivationOverrides(
    val books: Map<String, Boolean> = emptyMap(),
    val entries: Map<String, Map<String, Boolean>> = emptyMap(),
) {
    fun isBookEnabled(bookId: String): Boolean = books[bookId] ?: true

    fun isEntryEnabled(bookId: String, entryId: String, definitionEnabled: Boolean): Boolean =
        isBookEnabled(bookId) && (entries[bookId]?.get(entryId) ?: definitionEnabled)
}

@Serializable
data class ConversationStateSnapshot(
    val values: Map<String, JsonElement> = emptyMap(),
) {
    fun applying(patch: ConversationStatePatch): ConversationStateSnapshot =
        if (patch.assignments.isEmpty()) this else copy(values = values + patch.assignments)
}

data class ConversationStatePatch(
    val assignments: Map<String, JsonElement> = emptyMap(),
)

/** Opaque upstream state, bound to the exact engine bundle and card program that produced it. */
@Serializable
data class MvuStateSnapshot(
    val bundleSha256: String,
    val programSha256: String,
    val data: kotlinx.serialization.json.JsonObject,
)

@Serializable
data class ConversationRuntimeState(
    val localVariables: Map<String, MacroValue> = emptyMap(),
    val worldBookEntries: Map<String, WorldBookEntryRuntimeState> = emptyMap(),
    val worldBookActivationOverrides: WorldBookActivationOverrides = WorldBookActivationOverrides(),
    val conversationState: ConversationStateSnapshot = ConversationStateSnapshot(),
    val setupCommit: ConversationSetupCommit? = null,
    val memories: Map<String, ConversationMemory> = emptyMap(),
    val generationIndex: Int = 0,
    val lastGenerationType: String = "normal",
    val mvuState: MvuStateSnapshot? = null,
    val scriptState: kotlinx.serialization.json.JsonObject? = null,
    val nativeCommitId: String? = null,
)

@Serializable
data class ConversationMemory(
    val content: String,
    val sourceVariantIds: List<String>,
    val assistantReplyCount: Int,
    val model: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
)

@Serializable
data class ConversationSetupCommit(val formId: String)

data class NormalGenerationInput(
    val character: CharacterSnapshot,
    val persona: Persona,
    val history: List<ConversationMessage>,
    val preset: Preset,
    val runtimeState: ConversationRuntimeState = ConversationRuntimeState(),
    val conversationId: String = "preview",
    val generationId: String = "preview-0",
    val inputText: String = history.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty(),
    val modelId: String = "",
    val modelContextTokens: Int? = null,
    val modelOutputTokens: Int? = null,
    val maxInputTokens: Int? = null,
    val firstIncludedMessageId: Int? = null,
    val firstDisplayedMessageId: Int? = 0,
    val lastSwipeId: Int = 1,
    val currentSwipeId: Int = 1,
    val allChatLastMessageId: Int? = history.lastIndex.takeIf { it >= 0 },
    val evaluationInstant: Instant = Instant.now(),
    val evaluationZoneId: ZoneId = ZoneId.systemDefault(),
    val chatRangeResolutionPass: Int = 0,
    val ejsRenderer: (EjsTemplateRequest) -> String = { throw EjsRenderRequired(it) },
)

@Serializable
data class PromptOrigin(val stage: String, val sourceIds: List<String>)

@Serializable
data class PreparedMessage(
    val role: MessageRole,
    val content: String,
    val origin: PromptOrigin,
    val authorName: String? = null,
    val reasoning: List<ReasoningBlock> = emptyList(),
    val adapterId: String? = null,
)

@Serializable
enum class DiagnosticSeverity { WARNING, ERROR }

@Serializable
data class CompilationDiagnostic(
    val severity: DiagnosticSeverity,
    val code: String,
    val message: String,
    val sourceId: String? = null,
)

@Serializable
data class CompilationTraceEntry(
    val stage: String,
    val sourceIds: List<String>,
    val decision: String,
    val role: MessageRole? = null,
    val content: String? = null,
)

@Serializable
enum class TokenCountQuality { EXACT, ESTIMATED }

@Serializable
data class TokenAccountingReport(
    val inputTokens: Int,
    val contextLimit: Int? = null,
    val reservedOutputTokens: Int,
    val quality: TokenCountQuality,
    val tokenizer: String,
)

@Serializable
data class GenerationPlan(
    val messages: List<PreparedMessage>,
    val maxOutputTokens: Int,
    val declaredContextTokens: Int?,
    val assistantPrefill: String,
    val presetId: String,
    val presetName: String,
    val presetContentSha256: String = "",
    val generationSettings: PresetGenerationSettings = PresetGenerationSettings(
        maxContextTokens = declaredContextTokens,
        maxOutputTokens = maxOutputTokens,
    ),
    val diagnostics: List<CompilationDiagnostic>,
    val trace: List<CompilationTraceEntry>,
    val runtimeState: ConversationRuntimeState = ConversationRuntimeState(),
    val tokenAccounting: TokenAccountingReport? = null,
    val activatedWorldBookEntries: List<String> = emptyList(),
    @Transient val nativeAdaptation: NativeAdaptation? = null,
)

sealed interface CompilationResult {
    data class Success(val plan: GenerationPlan) : CompilationResult
    data class Failure(
        val diagnostics: List<CompilationDiagnostic>,
        val trace: List<CompilationTraceEntry> = emptyList(),
    ) : CompilationResult
}

sealed interface TextExpansionResult {
    data class Success(
        val text: String,
        val runtimeState: ConversationRuntimeState = ConversationRuntimeState(),
        val diagnostics: List<CompilationDiagnostic> = emptyList(),
    ) : TextExpansionResult
    data class Failure(val diagnostic: CompilationDiagnostic) : TextExpansionResult
}

@Serializable
enum class PersistedMessageStatus { COMPLETE, STREAMING, INTERRUPTED, CANCELLED, ERROR }

@Serializable
data class MessageVariant(
    val id: String,
    val message: ConversationMessage,
    val status: PersistedMessageStatus = PersistedMessageStatus.COMPLETE,
    val presetId: String? = null,
    val presetName: String? = null,
    val presetContentSha256: String? = null,
    val adapterId: String? = null,
    val model: String? = null,
    val finishReason: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val generationPlan: GenerationPlan? = null,
    val edited: Boolean = false,
    val runtimeStateBefore: ConversationRuntimeState? = null,
    val projectionRuntimeStateBefore: ConversationRuntimeState? = null,
    val runtimeStateAfter: ConversationRuntimeState? = null,
    // 原件中的开场位置：0 为 firstMessage，后续为 alternateFirstMessages；普通生成没有此值。
    val openingSourceIndex: Int? = null,
    val playerChoiceCommits: List<ConversationPlayerChoiceCommit> = emptyList(),
    val nativeOperations: List<NativeOperationRecord> = emptyList(),
)

@Serializable
data class ConversationTurn(
    val id: String,
    val role: MessageRole,
    val variants: List<MessageVariant>,
    val selectedVariantIndex: Int = 0,
) {
    val selected: MessageVariant
        get() = variants[selectedVariantIndex.coerceIn(0, variants.lastIndex)]
}

@Serializable
data class ConversationRecord(
    val schemaVersion: Int = 3,
    val id: String,
    val character: CharacterSnapshot,
    val persona: Persona,
    val turns: List<ConversationTurn>,
    val runtimeState: ConversationRuntimeState = ConversationRuntimeState(),
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val draft: String = "",
    val choiceDraft: ConversationChoiceDraft? = null,
    val nativeDraftOrigin: NativeDraftOrigin? = null,
)

@Serializable
data class ConversationPlayerChoiceCommit(
    val id: String,
    val choiceId: String,
    val title: String,
    val stateKey: String,
    val previousValue: String,
    val value: String,
    val draft: String,
)

@Serializable
data class ConversationChoiceDraft(val variantId: String, val commitId: String, val text: String)

/** 用户改写草稿后，它成为普通输入；未改写的选择草稿不能悄悄带到另一候选。 */
fun ConversationRecord.withDraft(value: String): ConversationRecord =
    copy(draft = value, choiceDraft = choiceDraft?.takeIf { it.text == value }, nativeDraftOrigin = nativeDraftOrigin?.takeIf { it.text == value })

fun ConversationRecord.reconcileChoiceDraft(): ConversationRecord {
    nativeDraftOrigin?.let { origin ->
        if (draft != origin.text) return copy(nativeDraftOrigin = null).reconcileChoiceDraft()
        if (turns.none { it.selected.id == origin.variantId && it.selected.nativeOperations.any { op -> op.id == origin.operationId } })
            return copy(draft = "", nativeDraftOrigin = null).reconcileChoiceDraft()
    }
    val origin = choiceDraft ?: return this
    if (draft != origin.text) return copy(choiceDraft = null)
    val stillSelected = turns.any { turn ->
        turn.selected.id == origin.variantId && turn.selected.playerChoiceCommits.any { it.id == origin.commitId }
    }
    return if (stillSelected) this else copy(draft = "", choiceDraft = null)
}

fun ContentRole.toMessageRole(): MessageRole = when (this) {
    ContentRole.SYSTEM -> MessageRole.SYSTEM
    ContentRole.USER -> MessageRole.USER
    ContentRole.ASSISTANT -> MessageRole.ASSISTANT
}

fun MessageRole.toContentRole(): ContentRole = when (this) {
    MessageRole.SYSTEM -> ContentRole.SYSTEM
    MessageRole.USER -> ContentRole.USER
    MessageRole.ASSISTANT -> ContentRole.ASSISTANT
}

fun parseDialogueExamples(raw: String, characterName: String, userName: String): List<DialogueExample> {
    if (raw.isBlank()) return emptyList()
    val normalized = raw
        .replace("{{char}}", characterName, ignoreCase = true)
        .replace("<char>", characterName, ignoreCase = true)
        .replace("<bot>", characterName, ignoreCase = true)
        .replace("{{user}}", userName, ignoreCase = true)
        .replace("<user>", userName, ignoreCase = true)
    return normalized.split(Regex("(?im)^\\s*<START>\\s*$"))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { block ->
            val messages = mutableListOf<ExampleMessage>()
            var role: MessageRole? = null
            val content = StringBuilder()
            fun flush() {
                val currentRole = role ?: return
                messages += ExampleMessage(currentRole, content.toString().trim())
                content.clear()
            }
            block.lineSequence().forEach { line ->
                val userPrefix = "$userName:"
                val charPrefix = "$characterName:"
                when {
                    line.startsWith(userPrefix, ignoreCase = true) -> {
                        flush()
                        role = MessageRole.USER
                        content.append(line.substring(userPrefix.length).trimStart())
                    }
                    line.startsWith(charPrefix, ignoreCase = true) -> {
                        flush()
                        role = MessageRole.ASSISTANT
                        content.append(line.substring(charPrefix.length).trimStart())
                    }
                    role != null -> {
                        if (content.isNotEmpty()) content.append('\n')
                        content.append(line)
                    }
                }
            }
            flush()
            messages.takeIf(List<ExampleMessage>::isNotEmpty)?.let(::DialogueExample)
        }
}

private fun encodeDialogueExamples(examples: List<DialogueExample>): String = examples.joinToString("\n") { example ->
    buildString {
        append("<START>\n")
        example.messages.forEach { message ->
            append(if (message.role == MessageRole.USER) "{{user}}: " else "{{char}}: ")
            append(message.content)
            append('\n')
        }
    }.trimEnd()
}
