package io.github.zvensmoluya.tavernplayer.conversation

enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

enum class InjectionPosition {
    RELATIVE,
    ABSOLUTE,
}

data class ExampleMessage(
    val role: MessageRole,
    val content: String,
)

data class DialogueExample(
    val messages: List<ExampleMessage>,
)

data class DepthPrompt(
    val content: String,
    val role: MessageRole = MessageRole.SYSTEM,
    val depth: Int = 4,
    val order: Int = 100,
)

data class CharacterAsset(
    val id: String,
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val alternateFirstMessages: List<String> = emptyList(),
    val examples: List<DialogueExample> = emptyList(),
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val depthPrompt: DepthPrompt? = null,
) {
    fun snapshot(): CharacterSnapshot = CharacterSnapshot(
        assetId = id,
        name = name,
        description = description,
        personality = personality,
        scenario = scenario,
        firstMessage = firstMessage,
        alternateFirstMessages = alternateFirstMessages.toList(),
        examples = examples.map { example ->
            DialogueExample(example.messages.map(ExampleMessage::copy))
        },
        systemPrompt = systemPrompt,
        postHistoryInstructions = postHistoryInstructions,
        depthPrompt = depthPrompt?.copy(),
    )
}

data class CharacterSnapshot(
    val assetId: String,
    val name: String,
    val description: String,
    val personality: String,
    val scenario: String,
    val firstMessage: String,
    val alternateFirstMessages: List<String>,
    val examples: List<DialogueExample>,
    val systemPrompt: String,
    val postHistoryInstructions: String,
    val depthPrompt: DepthPrompt?,
)

data class Persona(
    val id: String,
    val name: String,
    val avatar: String? = null,
)

data class ReasoningBlock(
    val text: String = "",
    val signature: String? = null,
)

data class ConversationMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val authorName: String,
    val reasoning: List<ReasoningBlock> = emptyList(),
    val adapterId: String? = null,
)

data class PromptDefinition(
    val identifier: String,
    val role: MessageRole,
    val content: String = "",
    val marker: Boolean = false,
    val systemPrompt: Boolean = false,
    val forbidOverrides: Boolean = false,
    val injectionPosition: InjectionPosition = InjectionPosition.RELATIVE,
    val injectionDepth: Int = 4,
    val injectionOrder: Int = 100,
    val injectionTriggers: Set<String> = emptySet(),
)

data class PromptOrderEntry(
    val identifier: String,
    val enabled: Boolean = true,
)

data class Preset(
    val id: String,
    val name: String,
    val prompts: List<PromptDefinition>,
    val promptOrder: List<PromptOrderEntry>,
    val newChatPrompt: String = "",
    val newExampleChatPrompt: String = "",
    val assistantPrefill: String = "",
    val maxOutputTokens: Int,
    val declaredContextTokens: Int? = null,
)

data class NormalGenerationInput(
    val character: CharacterSnapshot,
    val persona: Persona,
    val history: List<ConversationMessage>,
    val preset: Preset,
)

data class PromptOrigin(
    val stage: String,
    val sourceIds: List<String>,
)

data class PreparedMessage(
    val role: MessageRole,
    val content: String,
    val origin: PromptOrigin,
    val authorName: String? = null,
    val reasoning: List<ReasoningBlock> = emptyList(),
    val adapterId: String? = null,
)

enum class DiagnosticSeverity {
    WARNING,
    ERROR,
}

data class CompilationDiagnostic(
    val severity: DiagnosticSeverity,
    val code: String,
    val message: String,
    val sourceId: String? = null,
)

data class CompilationTraceEntry(
    val stage: String,
    val sourceIds: List<String>,
    val decision: String,
    val role: MessageRole? = null,
    val content: String? = null,
)

data class GenerationPlan(
    val messages: List<PreparedMessage>,
    val maxOutputTokens: Int,
    val declaredContextTokens: Int?,
    val assistantPrefill: String,
    val presetId: String,
    val presetName: String,
    val diagnostics: List<CompilationDiagnostic>,
    val trace: List<CompilationTraceEntry>,
)

sealed interface CompilationResult {
    data class Success(val plan: GenerationPlan) : CompilationResult

    data class Failure(
        val diagnostics: List<CompilationDiagnostic>,
        val trace: List<CompilationTraceEntry> = emptyList(),
    ) : CompilationResult
}

sealed interface TextExpansionResult {
    data class Success(val text: String) : TextExpansionResult
    data class Failure(val diagnostic: CompilationDiagnostic) : TextExpansionResult
}
