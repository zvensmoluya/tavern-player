package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.ModelGateway
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicMessage
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicMessagesEvent
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicMessagesRequest
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicOutputConfig
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicRole
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicThinking
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicThinkingType
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicUsage
import io.github.zvensmoluya.modelgateway.chat.ChatCompletionsEvent
import io.github.zvensmoluya.modelgateway.chat.ChatCompletionsRequest
import io.github.zvensmoluya.modelgateway.chat.ChatCompletionsUsage
import io.github.zvensmoluya.modelgateway.chat.ChatMessage
import io.github.zvensmoluya.modelgateway.chat.ChatRole
import io.github.zvensmoluya.modelgateway.gemini.GeminiContent
import io.github.zvensmoluya.modelgateway.gemini.GeminiContentRole
import io.github.zvensmoluya.modelgateway.gemini.GeminiGenerateContentEvent
import io.github.zvensmoluya.modelgateway.gemini.GeminiGenerateContentRequest
import io.github.zvensmoluya.modelgateway.gemini.GeminiGenerateContentUsage
import io.github.zvensmoluya.modelgateway.gemini.GeminiInteractionInputStep
import io.github.zvensmoluya.modelgateway.gemini.GeminiInteractionsEvent
import io.github.zvensmoluya.modelgateway.gemini.GeminiInteractionsRequest
import io.github.zvensmoluya.modelgateway.gemini.GeminiInteractionsUsage
import io.github.zvensmoluya.modelgateway.responses.ResponsesEvent
import io.github.zvensmoluya.modelgateway.responses.ResponsesInputMessage
import io.github.zvensmoluya.modelgateway.responses.ResponsesReasoning
import io.github.zvensmoluya.modelgateway.responses.ResponsesRequest
import io.github.zvensmoluya.modelgateway.responses.ResponsesRole
import io.github.zvensmoluya.modelgateway.responses.ResponsesTextConfig
import io.github.zvensmoluya.modelgateway.responses.ResponsesUsage
import io.github.zvensmoluya.tavernplayer.connections.ConnectionRepository
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationParameter
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationSettings
import io.github.zvensmoluya.tavernplayer.content.PresetReasoningEffort
import io.github.zvensmoluya.tavernplayer.content.PresetVerbosity
import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class GenerationUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val cachedTokens: Long? = null,
    val reasoningTokens: Long? = null,
)

data class ProviderPreviewMessage(
    val role: String,
    val content: String,
    val hasThoughtSignature: Boolean = false,
)

data class ProviderRequestPreview(
    val protocol: ModelProtocol,
    val model: String,
    val systemInstruction: String?,
    val messages: List<ProviderPreviewMessage>,
    val maxOutputTokens: Int?,
    val store: Boolean,
    val usesHostedState: Boolean,
    val assistantPrefillApplied: Boolean,
    val appliedPresetControls: List<String> = emptyList(),
    val omittedPresetControls: List<PresetControlOmission> = emptyList(),
)

data class PresetControlOmission(
    val control: String,
    val reason: String,
)

data class ProviderTokenValidation(
    val inputTokens: Int,
    val quality: TokenCountQuality,
    val counter: String,
)

sealed interface GenerationEvent {
    data class RequestPrepared(val preview: ProviderRequestPreview) : GenerationEvent
    data class TextDelta(val text: String) : GenerationEvent
    data object ReasoningStarted : GenerationEvent
    data class ReasoningDelta(val text: String) : GenerationEvent
    data class ReasoningSignature(val signature: String) : GenerationEvent
    data object ReasoningFinished : GenerationEvent
    data class Usage(val value: GenerationUsage) : GenerationEvent
    data class AssistantStateConfirmed(val envelope: String) : GenerationEvent
    data class Finished(val reason: String?) : GenerationEvent
    data class Diagnostic(val summary: String) : GenerationEvent
}

interface ConversationGenerator {
    suspend fun validateTokens(connection: StoredConnection, plan: GenerationPlan): ProviderTokenValidation? =
        plan.tokenAccounting?.let { ProviderTokenValidation(it.inputTokens, it.quality, it.tokenizer) }

    fun stream(connection: StoredConnection, plan: GenerationPlan): Flow<GenerationEvent>
}

class ModelGatewayConversationGenerator(
    private val gateway: ModelGateway,
    private val repository: ConnectionRepository,
) : ConversationGenerator {
    override suspend fun validateTokens(
        connection: StoredConnection,
        plan: GenerationPlan,
    ): ProviderTokenValidation? {
        repository.ensureReady(connection)
        val prepared = GenerationRequestMapper.map(connection, plan)
        val host = runCatching { URI(connection.streamEndpoint.replace("{model}", "model")).host.orEmpty() }
            .getOrDefault("")
        return when {
            prepared is PreparedGenerationRequest.Anthropic && host.equals("api.anthropic.com", ignoreCase = true) -> {
                val count = gateway.anthropicMessages.countTokens(connection.target(), prepared.request)
                ProviderTokenValidation(count.inputTokens.toIntSafeCount(), TokenCountQuality.EXACT, "anthropic-count-tokens")
            }
            prepared is PreparedGenerationRequest.GenerateContent &&
                host.equals("generativelanguage.googleapis.com", ignoreCase = true) -> {
                val count = gateway.geminiGenerateContent.countTokens(connection.target(), prepared.request)
                ProviderTokenValidation(count.totalTokens.toIntSafeCount(), TokenCountQuality.EXACT, "gemini-countTokens")
            }
            (prepared is PreparedGenerationRequest.Responses || prepared is PreparedGenerationRequest.Chat) &&
                host.equals("api.openai.com", ignoreCase = true) -> plan.tokenAccounting?.let {
                ProviderTokenValidation(it.inputTokens, it.quality, it.tokenizer)
            }
            else -> ProviderTokenValidation(
                inputTokens = prepared.conservativeInputTokens(),
                quality = TokenCountQuality.ESTIMATED,
                counter = "provider-request-utf8-upper-bound",
            )
        }
    }

    override fun stream(connection: StoredConnection, plan: GenerationPlan): Flow<GenerationEvent> = flow {
        repository.ensureReady(connection)
        val prepared = GenerationRequestMapper.map(connection, plan)
        emit(GenerationEvent.RequestPrepared(prepared.preview))
        prepared.preview.omittedPresetControls.forEach { omission ->
            emit(GenerationEvent.Diagnostic("Preset ${omission.control} 已省略：${omission.reason}"))
        }
        val target = connection.target()
        when (prepared) {
            is PreparedGenerationRequest.Responses -> gateway.responses.stream(target, prepared.request).collect { event ->
                when (event) {
                    is ResponsesEvent.TextDelta -> emit(GenerationEvent.TextDelta(event.text))
                    is ResponsesEvent.ReasoningDelta -> emit(GenerationEvent.ReasoningDelta(event.text))
                    is ResponsesEvent.Usage -> emit(GenerationEvent.Usage(event.usage.toGenerationUsage()))
                    is ResponsesEvent.Finished -> emit(GenerationEvent.Finished(event.status))
                    is ResponsesEvent.Failed -> throw GatewayException.Protocol(
                        event.message ?: "Responses generation failed",
                    )
                    is ResponsesEvent.Started -> emit(GenerationEvent.Diagnostic("Responses stream 已建立"))
                    is ResponsesEvent.Lifecycle -> emit(GenerationEvent.Diagnostic("Responses ${event.name}"))
                    is ResponsesEvent.Unknown -> emit(GenerationEvent.Diagnostic("收到未识别的 Responses 事件"))
                }
            }

            is PreparedGenerationRequest.Chat -> gateway.chatCompletions.stream(target, prepared.request).collect { event ->
                when (event) {
                    is ChatCompletionsEvent.TextDelta -> emit(GenerationEvent.TextDelta(event.text))
                    is ChatCompletionsEvent.ReasoningDelta -> emit(GenerationEvent.ReasoningDelta(event.text))
                    is ChatCompletionsEvent.Usage -> emit(GenerationEvent.Usage(event.usage.toGenerationUsage()))
                    is ChatCompletionsEvent.Finished -> emit(GenerationEvent.Finished(event.reason))
                    is ChatCompletionsEvent.Started -> emit(GenerationEvent.Diagnostic("Chat stream 已建立"))
                    is ChatCompletionsEvent.Unknown -> emit(GenerationEvent.Diagnostic("收到未识别的 Chat 事件"))
                }
            }

            is PreparedGenerationRequest.Anthropic -> {
                var reasoningOpen = false
                gateway.anthropicMessages.stream(target, prepared.request).collect { event ->
                    when (event) {
                        is AnthropicMessagesEvent.TextDelta -> emit(GenerationEvent.TextDelta(event.text))
                        is AnthropicMessagesEvent.ThinkingDelta -> {
                            if (!reasoningOpen) {
                                emit(GenerationEvent.ReasoningStarted)
                                reasoningOpen = true
                            }
                            emit(GenerationEvent.ReasoningDelta(event.text))
                        }
                        is AnthropicMessagesEvent.SignatureDelta -> emit(
                            GenerationEvent.ReasoningSignature(event.signature),
                        )
                        is AnthropicMessagesEvent.Usage -> emit(GenerationEvent.Usage(event.usage.toGenerationUsage()))
                        is AnthropicMessagesEvent.Finished -> {
                            if (reasoningOpen) emit(GenerationEvent.ReasoningFinished)
                            emit(GenerationEvent.Finished(event.reason))
                        }
                        is AnthropicMessagesEvent.Failed -> throw GatewayException.Protocol(
                            event.message ?: "Anthropic generation failed",
                        )
                        is AnthropicMessagesEvent.Started -> emit(GenerationEvent.Diagnostic("Anthropic stream 已建立"))
                        is AnthropicMessagesEvent.Lifecycle -> emit(GenerationEvent.Diagnostic("Anthropic ${event.name}"))
                        is AnthropicMessagesEvent.Unknown -> emit(GenerationEvent.Diagnostic("收到未识别的 Anthropic 事件"))
                    }
                }
            }

            is PreparedGenerationRequest.Interactions -> {
                var thoughtStep = false
                gateway.geminiInteractions.stream(target, prepared.request).collect { event ->
                    when (event) {
                        is GeminiInteractionsEvent.StepStarted -> {
                            thoughtStep = event.stepType?.contains("thought", ignoreCase = true) == true
                            if (thoughtStep) emit(GenerationEvent.ReasoningStarted)
                        }
                        is GeminiInteractionsEvent.StepFinished -> {
                            if (thoughtStep) emit(GenerationEvent.ReasoningFinished)
                            thoughtStep = false
                        }
                        is GeminiInteractionsEvent.TextDelta -> emit(GenerationEvent.TextDelta(event.text))
                        is GeminiInteractionsEvent.ThoughtDelta -> emit(GenerationEvent.ReasoningDelta(event.text))
                        is GeminiInteractionsEvent.Signature -> emit(GenerationEvent.ReasoningSignature(event.signature))
                        is GeminiInteractionsEvent.Usage -> emit(GenerationEvent.Usage(event.usage.toGenerationUsage()))
                        is GeminiInteractionsEvent.Finished -> emit(GenerationEvent.Finished(event.status))
                        is GeminiInteractionsEvent.Failed -> throw GatewayException.Protocol(
                            event.message ?: "Gemini Interactions generation failed",
                        )
                        is GeminiInteractionsEvent.Started -> emit(GenerationEvent.Diagnostic("Interactions stream 已建立"))
                        is GeminiInteractionsEvent.StatusUpdated -> emit(
                            GenerationEvent.Diagnostic("Interactions 状态：${event.status.orEmpty()}"),
                        )
                        is GeminiInteractionsEvent.Unknown -> emit(
                            GenerationEvent.Diagnostic("收到未识别的 Interactions 事件"),
                        )
                    }
                }
            }

            is PreparedGenerationRequest.GenerateContent -> {
                var reasoningOpen = false
                gateway.geminiGenerateContent.stream(target, prepared.request).collect { event ->
                    when (event) {
                        is GeminiGenerateContentEvent.TextDelta -> emit(GenerationEvent.TextDelta(event.text))
                        is GeminiGenerateContentEvent.ThoughtDelta -> {
                            if (!reasoningOpen) {
                                emit(GenerationEvent.ReasoningStarted)
                                reasoningOpen = true
                            }
                            emit(GenerationEvent.ReasoningDelta(event.text))
                        }
                        is GeminiGenerateContentEvent.Signature -> emit(
                            GenerationEvent.ReasoningSignature(event.signature),
                        )
                        is GeminiGenerateContentEvent.Usage -> emit(GenerationEvent.Usage(event.usage.toGenerationUsage()))
                        is GeminiGenerateContentEvent.Finished -> {
                            if (reasoningOpen) emit(GenerationEvent.ReasoningFinished)
                            emit(GenerationEvent.Finished(event.reason))
                        }
                        is GeminiGenerateContentEvent.Unknown -> emit(
                            GenerationEvent.Diagnostic("收到未识别的 GenerateContent 事件"),
                        )
                    }
                }
            }
        }
    }
}

private fun Long.toIntSafeCount(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

internal sealed interface PreparedGenerationRequest {
    val preview: ProviderRequestPreview

    data class Responses(
        val request: ResponsesRequest,
        override val preview: ProviderRequestPreview,
    ) : PreparedGenerationRequest

    data class Chat(
        val request: ChatCompletionsRequest,
        override val preview: ProviderRequestPreview,
    ) : PreparedGenerationRequest

    data class Anthropic(
        val request: AnthropicMessagesRequest,
        override val preview: ProviderRequestPreview,
    ) : PreparedGenerationRequest

    data class Interactions(
        val request: GeminiInteractionsRequest,
        override val preview: ProviderRequestPreview,
    ) : PreparedGenerationRequest

    data class GenerateContent(
        val request: GeminiGenerateContentRequest,
        override val preview: ProviderRequestPreview,
    ) : PreparedGenerationRequest
}

internal fun PreparedGenerationRequest.conservativeInputTokens(): Int {
    val payloads = when (this) {
        is PreparedGenerationRequest.Responses -> buildList {
            request.instructions?.let(::add)
            request.input.forEach { addAll(listOf(it.role.wire, it.text)) }
        }
        is PreparedGenerationRequest.Chat -> request.messages.flatMap { listOf(it.role.wire, it.content) }
        is PreparedGenerationRequest.Anthropic -> buildList {
            request.system?.let(::add)
            request.messages.forEach { message ->
                add(message.role.wire)
                add(message.text)
            }
        }
        is PreparedGenerationRequest.Interactions -> buildList {
            request.systemInstruction?.let(::add)
            request.input.forEach { step ->
                when (step) {
                    is GeminiInteractionInputStep.UserInput -> add(step.text)
                    is GeminiInteractionInputStep.ModelOutput -> add(step.text)
                    is GeminiInteractionInputStep.Thought -> {
                        add(step.signature)
                        step.summary?.let(::add)
                    }
                }
            }
        }
        is PreparedGenerationRequest.GenerateContent -> buildList {
            request.systemInstruction?.let(::add)
            request.contents.forEach { content ->
                add(content.role.wire)
                add(content.text)
                content.thoughtSignature?.let(::add)
            }
        }
    }
    return payloads.sumOf { value ->
        value.toByteArray(Charsets.UTF_8).size.toLong() + CONSERVATIVE_FIELD_OVERHEAD
    }
        .plus(CONSERVATIVE_REQUEST_OVERHEAD)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
}

private const val CONSERVATIVE_FIELD_OVERHEAD = 4
private const val CONSERVATIVE_REQUEST_OVERHEAD = 16

internal object GenerationRequestMapper {
    fun map(connection: StoredConnection, plan: GenerationPlan): PreparedGenerationRequest {
        val protocol = connection.protocol
        val report = PresetMappingReport().apply {
            if (plan.generationSettings.isEnabled(PresetGenerationParameter.OUTPUT_LIMIT)) {
                applied("output_limit")
            }
            recordUniversallyUnmapped(plan.generationSettings)
        }
        return when (protocol) {
            ModelProtocol.OPENAI_RESPONSES -> responses(connection, plan, report)
            ModelProtocol.OPENAI_CHAT_COMPLETIONS -> chat(connection, plan, report)
            ModelProtocol.ANTHROPIC_MESSAGES -> anthropic(connection, plan, report)
            ModelProtocol.GEMINI_INTERACTIONS -> interactions(connection, plan, report)
            ModelProtocol.GEMINI_GENERATE_CONTENT -> generateContent(connection, plan, report)
        }
    }

    private fun responses(
        connection: StoredConnection,
        plan: GenerationPlan,
        report: PresetMappingReport,
    ): PreparedGenerationRequest.Responses {
        val settings = plan.generationSettings
        val temperature = settings.enabledValue(PresetGenerationParameter.TEMPERATURE, settings.temperature)
            .validRange("temperature", 0.0..2.0, report)
        val topP = settings.enabledValue(PresetGenerationParameter.TOP_P, settings.topP)
            .validRange("top_p", 0.0..1.0, report)
        temperature?.let { report.applied("temperature") }
        topP?.let { report.applied("top_p") }
        settings.enabledValue(PresetGenerationParameter.TOP_K, settings.topK)?.takeIf { it > 0 }?.let {
            report.omitted("top_k", "Responses API 无对应字段")
        }
        report.omitIfNonDefault(
            "frequency_penalty",
            settings.enabledValue(PresetGenerationParameter.FREQUENCY_PENALTY, settings.frequencyPenalty),
            0.0,
            "Responses API 无对应字段",
        )
        report.omitIfNonDefault(
            "presence_penalty",
            settings.enabledValue(PresetGenerationParameter.PRESENCE_PENALTY, settings.presencePenalty),
            0.0,
            "Responses API 无对应字段",
        )
        settings.enabledValue(PresetGenerationParameter.SEED, settings.seed)
            ?.let { report.omitted("seed", "Responses API 无对应字段") }
        if (plan.assistantPrefill.isNotBlank()) report.omitted("assistant_prefill", "Responses API 无法表达 assistant prefill")
        if (plan.messages.any { !it.authorName.isNullOrBlank() }) {
            report.omitted("names_behavior", "Responses 文本消息未发送 author name")
        }
        val reasoning = settings.enabledValue(PresetGenerationParameter.REASONING_EFFORT, settings.reasoningEffort)
            ?.takeUnless { it == PresetReasoningEffort.AUTO }
            ?.let { effort ->
                report.applied("reasoning_effort")
                ResponsesReasoning(effort = effort.openAiWireValue())
            }
        val text = settings.enabledValue(PresetGenerationParameter.VERBOSITY, settings.verbosity)
            ?.takeUnless { it == PresetVerbosity.AUTO }
            ?.let { verbosity ->
                report.applied("verbosity")
                ResponsesTextConfig(verbosity.wireValue.lowercase())
            }
        val playerOwnedContract = plan.messages.singleOrNull { message ->
            message.role == MessageRole.SYSTEM && message.origin.stage == "assistant-state-contract"
        }
        val playerOwnedInstructions = if (playerOwnedContract != null) {
            plan.messages.filter { message ->
                message.role == MessageRole.SYSTEM &&
                    message.origin.stage in PLAYER_OWNED_RESPONSES_INSTRUCTION_STAGES
            }
        } else {
            emptyList()
        }
        val leadingSystem = plan.messages.takeWhile { it.role == MessageRole.SYSTEM }
        val instructions = playerOwnedInstructions.joinToString("\n\n") { it.content }.ifBlank { null }
            ?: leadingSystem.joinToString("\n\n") { it.content }.ifBlank { null }
        val transportMessages = if (playerOwnedContract != null) {
            plan.messages.filterNot { message -> playerOwnedInstructions.any { it === message } }
        } else {
            plan.messages.drop(leadingSystem.size)
        }
        val messages = transportMessages.map { message ->
            ResponsesInputMessage(
                role = when (message.role) {
                    MessageRole.SYSTEM -> if (playerOwnedContract != null) {
                        ResponsesRole.DEVELOPER
                    } else {
                        ResponsesRole.SYSTEM
                    }
                    MessageRole.USER -> ResponsesRole.USER
                    MessageRole.ASSISTANT -> ResponsesRole.ASSISTANT
                },
                text = message.content,
            )
        }
        return PreparedGenerationRequest.Responses(
            request = ResponsesRequest(
                model = connection.selectedModel,
                input = messages,
                instructions = instructions,
                maxOutputTokens = plan.maxOutputTokens.takeIf {
                    settings.isEnabled(PresetGenerationParameter.OUTPUT_LIMIT)
                },
                previousResponseId = null,
                reasoning = reasoning,
                text = text,
                temperature = temperature,
                topP = topP,
                store = false,
            ),
            preview = preview(connection, plan, system = instructions, messages.map { it.role.wire to it.text }, report = report),
        )
    }

    private val PLAYER_OWNED_RESPONSES_INSTRUCTION_STAGES = setOf(
        "conversation-state",
        "assistant-state-contract",
    )

    private fun chat(
        connection: StoredConnection,
        plan: GenerationPlan,
        report: PresetMappingReport,
    ): PreparedGenerationRequest.Chat {
        val settings = plan.generationSettings
        val temperature = settings.enabledValue(PresetGenerationParameter.TEMPERATURE, settings.temperature)
            .validRange("temperature", 0.0..2.0, report)
        val topP = settings.enabledValue(PresetGenerationParameter.TOP_P, settings.topP)
            .validRange("top_p", 0.0..1.0, report)
        val frequencyPenalty = settings
            .enabledValue(PresetGenerationParameter.FREQUENCY_PENALTY, settings.frequencyPenalty)
            .validRange("frequency_penalty", -2.0..2.0, report)
        val presencePenalty = settings
            .enabledValue(PresetGenerationParameter.PRESENCE_PENALTY, settings.presencePenalty)
            .validRange("presence_penalty", -2.0..2.0, report)
        val seed = settings.enabledValue(PresetGenerationParameter.SEED, settings.seed)
        temperature?.let { report.applied("temperature") }
        topP?.let { report.applied("top_p") }
        frequencyPenalty?.let { report.applied("frequency_penalty") }
        presencePenalty?.let { report.applied("presence_penalty") }
        seed?.let { report.applied("seed") }
        settings.enabledValue(PresetGenerationParameter.TOP_K, settings.topK)?.takeIf { it > 0 }?.let {
            report.omitted("top_k", "Chat Completions 无对应字段")
        }
        if (plan.assistantPrefill.isNotBlank()) {
            report.omitted("assistant_prefill", "Chat Completions 无法准确表达 assistant prefill")
        }
        val reasoning = settings.enabledValue(PresetGenerationParameter.REASONING_EFFORT, settings.reasoningEffort)
            ?.takeUnless { it == PresetReasoningEffort.AUTO }
            ?.let { effort ->
                report.applied("reasoning_effort")
                effort.openAiWireValue()
            }
        val verbosity = settings.enabledValue(PresetGenerationParameter.VERBOSITY, settings.verbosity)
            ?.takeUnless { it == PresetVerbosity.AUTO }
            ?.let { value ->
                report.applied("verbosity")
                value.wireValue.lowercase()
            }
        val messages = plan.messages.map { message ->
            val name = message.authorName?.toOpenAiMessageName()
            if (!message.authorName.isNullOrBlank() && name == null) {
                report.omitted("names_behavior", "author name 不符合 Chat Completions name 字符约束")
            } else if (name != null) {
                report.applied("names_behavior")
            }
            ChatMessage(
                role = when (message.role) {
                    MessageRole.SYSTEM -> ChatRole.SYSTEM
                    MessageRole.USER -> ChatRole.USER
                    MessageRole.ASSISTANT -> ChatRole.ASSISTANT
                },
                content = message.content,
                name = name,
            )
        }
        return PreparedGenerationRequest.Chat(
            request = ChatCompletionsRequest(
                model = connection.selectedModel,
                messages = messages,
                maxCompletionTokens = plan.maxOutputTokens.takeIf {
                    settings.isEnabled(PresetGenerationParameter.OUTPUT_LIMIT)
                },
                reasoningEffort = reasoning,
                verbosity = verbosity,
                temperature = temperature,
                topP = topP,
                frequencyPenalty = frequencyPenalty,
                presencePenalty = presencePenalty,
                seed = seed,
                store = false,
            ),
            preview = preview(connection, plan, system = null, messages.map { it.role.wire to it.content }, report = report),
        )
    }

    private fun anthropic(
        connection: StoredConnection,
        plan: GenerationPlan,
        report: PresetMappingReport,
    ): PreparedGenerationRequest.Anthropic {
        val settings = plan.generationSettings
        val capabilities = connection.selectedModel.anthropicCapabilities()
        val temperature = if (capabilities.samplers) {
            settings.enabledValue(PresetGenerationParameter.TEMPERATURE, settings.temperature)
                .validRange("temperature", 0.0..1.0, report)
                ?.also { report.applied("temperature") }
        } else {
            report.omitIfNonDefault(
                "temperature",
                settings.enabledValue(PresetGenerationParameter.TEMPERATURE, settings.temperature),
                1.0,
                "模型能力未知或该 Claude 版本拒绝采样参数",
            )
            null
        }
        val topP = if (capabilities.samplers) {
            settings.enabledValue(PresetGenerationParameter.TOP_P, settings.topP)
                .validRange("top_p", 0.0..1.0, report)
                ?.also { report.applied("top_p") }
        } else {
            report.omitIfNonDefault(
                "top_p",
                settings.enabledValue(PresetGenerationParameter.TOP_P, settings.topP),
                1.0,
                "模型能力未知或该 Claude 版本拒绝采样参数",
            )
            null
        }
        val topK = if (capabilities.samplers) {
            settings.enabledValue(PresetGenerationParameter.TOP_K, settings.topK)
                ?.takeIf { it > 0 }
                ?.also { report.applied("top_k") }
        } else {
            settings.enabledValue(PresetGenerationParameter.TOP_K, settings.topK)?.takeIf { it > 0 }?.let {
                report.omitted("top_k", "模型能力未知或该 Claude 版本拒绝采样参数")
            }
            null
        }
        settings.enabledValue(PresetGenerationParameter.SEED, settings.seed)
            ?.let { report.omitted("seed", "Anthropic Messages 无 seed 字段") }
        report.omitIfNonDefault(
            "frequency_penalty",
            settings.enabledValue(PresetGenerationParameter.FREQUENCY_PENALTY, settings.frequencyPenalty),
            0.0,
            "Anthropic Messages 无对应字段",
        )
        report.omitIfNonDefault(
            "presence_penalty",
            settings.enabledValue(PresetGenerationParameter.PRESENCE_PENALTY, settings.presencePenalty),
            0.0,
            "Anthropic Messages 无对应字段",
        )
        if (plan.messages.any { !it.authorName.isNullOrBlank() }) {
            report.omitted("names_behavior", "Anthropic Messages 无 author name 字段")
        }

        var thinking: AnthropicThinking? = null
        var outputConfig: AnthropicOutputConfig? = null
        if (
            settings.isEnabled(PresetGenerationParameter.REASONING_EFFORT) &&
            settings.reasoningEffort != PresetReasoningEffort.AUTO
        ) {
            when {
                capabilities.adaptiveThinking -> {
                    thinking = AnthropicThinking(AnthropicThinkingType.ADAPTIVE)
                    outputConfig = AnthropicOutputConfig(settings.reasoningEffort.anthropicEffort())
                    report.applied("reasoning_effort")
                }
                capabilities.manualThinking -> {
                    val budget = settings.reasoningEffort.anthropicThinkingBudget(plan.maxOutputTokens)
                    if (budget == null) {
                        report.omitted("reasoning_effort", "回复上限不足以容纳 Anthropic 最小 thinking budget")
                    } else {
                        thinking = AnthropicThinking(AnthropicThinkingType.ENABLED, budget)
                        report.applied("reasoning_effort")
                    }
                }
                else -> report.omitted("reasoning_effort", "模型 thinking 能力未知")
            }
        }
        if (
            settings.isEnabled(PresetGenerationParameter.VERBOSITY) &&
            settings.verbosity != PresetVerbosity.AUTO
        ) {
            report.omitted("verbosity", "Anthropic Messages 无 verbosity 字段")
        }
        val leadingSystem = plan.messages.takeWhile { it.role == MessageRole.SYSTEM }
        val system = leadingSystem.joinToString("\n\n") { it.content }.ifBlank { null }
        val mapped = plan.messages.drop(leadingSystem.size).map { message ->
            AnthropicMessage(
                role = if (message.role == MessageRole.ASSISTANT) AnthropicRole.ASSISTANT else AnthropicRole.USER,
                text = message.content,
            )
        }.mergeAnthropicRoles().toMutableList()
        val prefill = plan.assistantPrefill.takeIf(String::isNotBlank)?.takeIf {
            if (capabilities.assistantPrefill) {
                report.applied("assistant_prefill")
                true
            } else {
                report.omitted("assistant_prefill", "模型能力未知或该 Claude 版本不再支持最后一条 assistant prefill")
                false
            }
        }
        prefill?.let { value ->
            if (mapped.lastOrNull()?.role == AnthropicRole.ASSISTANT) {
                val last = mapped.removeAt(mapped.lastIndex)
                mapped += last.copy(text = last.text + "\n\n" + value)
            } else {
                mapped += AnthropicMessage(AnthropicRole.ASSISTANT, value)
            }
        }
        if (mapped.isEmpty()) throw GatewayException.Configuration("Anthropic request requires conversation messages")
        if (!settings.isEnabled(PresetGenerationParameter.OUTPUT_LIMIT)) {
            report.omitted(
                "output_limit",
                "Preset 已关闭回复上限；Anthropic Messages 要求 max_tokens，已使用播放器安全预算",
            )
        }
        return PreparedGenerationRequest.Anthropic(
            request = AnthropicMessagesRequest(
                model = connection.selectedModel,
                messages = mapped,
                maxTokens = plan.maxOutputTokens,
                system = system,
                thinking = thinking,
                outputConfig = outputConfig,
                temperature = temperature,
                topP = topP,
                topK = topK,
            ),
            preview = preview(
                connection,
                plan,
                system,
                mapped.map { it.role.wire to it.text },
                prefillApplied = prefill != null,
                requestOutputLimit = plan.maxOutputTokens,
                report = report,
            ),
        )
    }

    private fun interactions(
        connection: StoredConnection,
        plan: GenerationPlan,
        report: PresetMappingReport,
    ): PreparedGenerationRequest.Interactions {
        val settings = plan.generationSettings
        val seed = settings.enabledValue(PresetGenerationParameter.SEED, settings.seed)
        seed?.let { report.applied("seed") }
        report.omitIfNonDefault(
            "temperature",
            settings.enabledValue(PresetGenerationParameter.TEMPERATURE, settings.temperature),
            1.0,
            "计划约定 Interactions 仅映射 output、seed 与 thinking level",
        )
        report.omitIfNonDefault(
            "top_p",
            settings.enabledValue(PresetGenerationParameter.TOP_P, settings.topP),
            1.0,
            "计划约定 Interactions 不映射 top_p",
        )
        settings.enabledValue(PresetGenerationParameter.TOP_K, settings.topK)?.takeIf { it > 0 }?.let {
            report.omitted("top_k", "Interactions 映射边界不包含 top_k")
        }
        report.omitIfNonDefault(
            "frequency_penalty",
            settings.enabledValue(PresetGenerationParameter.FREQUENCY_PENALTY, settings.frequencyPenalty),
            0.0,
            "Interactions 映射边界不包含 frequency penalty",
        )
        report.omitIfNonDefault(
            "presence_penalty",
            settings.enabledValue(PresetGenerationParameter.PRESENCE_PENALTY, settings.presencePenalty),
            0.0,
            "Interactions 映射边界不包含 presence penalty",
        )
        if (plan.assistantPrefill.isNotBlank()) report.omitted("assistant_prefill", "Interactions 无法表达 assistant prefill")
        if (plan.messages.any { !it.authorName.isNullOrBlank() }) {
            report.omitted("names_behavior", "Interactions 无 author name 字段")
        }
        val thinkingLevel = settings.enabledValue(PresetGenerationParameter.REASONING_EFFORT, settings.reasoningEffort)
            ?.takeUnless { it == PresetReasoningEffort.AUTO }
            ?.let { effort ->
            val supported = connection.selectedModel.geminiThinkingLevels()
            if (supported.isEmpty()) {
                report.omitted("reasoning_effort", "模型 thinking level 能力未知")
                null
            } else {
                report.applied("reasoning_effort")
                effort.geminiThinkingLevel(supported, report)
            }
        }
        if (
            settings.isEnabled(PresetGenerationParameter.VERBOSITY) &&
            settings.verbosity != PresetVerbosity.AUTO
        ) {
            report.omitted("verbosity", "Interactions 映射边界不包含 verbosity")
        }
        val leadingSystem = plan.messages.takeWhile { it.role == MessageRole.SYSTEM }
        val system = leadingSystem.joinToString("\n\n") { it.content }.ifBlank { null }
        val steps = buildList {
            plan.messages.drop(leadingSystem.size).forEach { message ->
                when (message.role) {
                    MessageRole.SYSTEM, MessageRole.USER -> add(GeminiInteractionInputStep.UserInput(message.content))
                    MessageRole.ASSISTANT -> {
                        if (message.adapterId == ModelProtocol.GEMINI_INTERACTIONS.name) {
                            message.reasoning.filter { !it.signature.isNullOrBlank() }.forEach { block ->
                                add(GeminiInteractionInputStep.Thought(block.signature.orEmpty(), block.text.ifBlank { null }))
                            }
                        }
                        add(GeminiInteractionInputStep.ModelOutput(message.content))
                    }
                }
            }
        }
        return PreparedGenerationRequest.Interactions(
            request = GeminiInteractionsRequest(
                model = connection.selectedModel,
                input = steps,
                systemInstruction = system,
                maxOutputTokens = plan.maxOutputTokens.takeIf {
                    settings.isEnabled(PresetGenerationParameter.OUTPUT_LIMIT)
                },
                seed = seed,
                thinkingLevel = thinkingLevel,
                previousInteractionId = null,
                store = false,
            ),
            preview = preview(
                connection,
                plan,
                system,
                steps.map { step ->
                    when (step) {
                        is GeminiInteractionInputStep.UserInput -> "user_input" to step.text
                        is GeminiInteractionInputStep.ModelOutput -> "model_output" to step.text
                        is GeminiInteractionInputStep.Thought -> "thought" to "[opaque signature]"
                    }
                },
                report = report,
            ),
        )
    }

    private fun generateContent(
        connection: StoredConnection,
        plan: GenerationPlan,
        report: PresetMappingReport,
    ): PreparedGenerationRequest.GenerateContent {
        val settings = plan.generationSettings
        val temperature = settings.enabledValue(PresetGenerationParameter.TEMPERATURE, settings.temperature)
            .validMinimum("temperature", 0.0, report)
            ?.also { report.applied("temperature") }
        val topP = settings.enabledValue(PresetGenerationParameter.TOP_P, settings.topP)
            .validRange("top_p", 0.0..1.0, report)
            ?.also { report.applied("top_p") }
        val topK = settings.enabledValue(PresetGenerationParameter.TOP_K, settings.topK)
            ?.takeIf { it > 0 }
            ?.also { report.applied("top_k") }
        val frequencyPenalty = settings
            .enabledValue(PresetGenerationParameter.FREQUENCY_PENALTY, settings.frequencyPenalty)
            .validRange("frequency_penalty", -2.0..2.0, report)
            ?.also { report.applied("frequency_penalty") }
        val presencePenalty = settings
            .enabledValue(PresetGenerationParameter.PRESENCE_PENALTY, settings.presencePenalty)
            .validRange("presence_penalty", -2.0..2.0, report)
            ?.also { report.applied("presence_penalty") }
        val seed = settings.enabledValue(PresetGenerationParameter.SEED, settings.seed)
        seed?.let { report.applied("seed") }
        if (plan.assistantPrefill.isNotBlank()) report.omitted("assistant_prefill", "GenerateContent 无法表达 assistant prefill")
        if (plan.messages.any { !it.authorName.isNullOrBlank() }) {
            report.omitted("names_behavior", "GenerateContent 无 author name 字段")
        }
        val thinking = settings.enabledValue(PresetGenerationParameter.REASONING_EFFORT, settings.reasoningEffort)
            ?.takeUnless { it == PresetReasoningEffort.AUTO }
            ?.let { effort ->
            when (connection.selectedModel.geminiThinkingMode()) {
                GeminiThinkingMode.LEVEL -> {
                    report.applied("reasoning_effort")
                    io.github.zvensmoluya.modelgateway.gemini.GeminiThinkingConfig(
                        includeThoughts = true,
                        thinkingLevel = effort.geminiThinkingLevel(
                            connection.selectedModel.geminiThinkingLevels(),
                            report,
                        ),
                    )
                }
                GeminiThinkingMode.BUDGET -> {
                    report.applied("reasoning_effort")
                    io.github.zvensmoluya.modelgateway.gemini.GeminiThinkingConfig(
                        includeThoughts = true,
                        thinkingBudget = effort.geminiThinkingBudget(),
                    )
                }
                GeminiThinkingMode.NONE -> {
                    report.omitted("reasoning_effort", "模型 thinking config 能力未知")
                    null
                }
            }
        }
        if (
            settings.isEnabled(PresetGenerationParameter.VERBOSITY) &&
            settings.verbosity != PresetVerbosity.AUTO
        ) {
            report.omitted("verbosity", "GenerateContent 无 verbosity 字段")
        }
        val leadingSystem = plan.messages.takeWhile { it.role == MessageRole.SYSTEM }
        val system = leadingSystem.joinToString("\n\n") { it.content }.ifBlank { null }
        val raw = plan.messages.drop(leadingSystem.size).map { message ->
            val signatures = if (message.adapterId == ModelProtocol.GEMINI_GENERATE_CONTENT.name) {
                message.reasoning.mapNotNull { it.signature?.takeIf(String::isNotBlank) }
            } else {
                emptyList()
            }
            if (signatures.size > 1) {
                throw GatewayException.Configuration("Gemini GenerateContent history contains multiple thought signatures")
            }
            GeminiContent(
                role = if (message.role == MessageRole.ASSISTANT) GeminiContentRole.MODEL else GeminiContentRole.USER,
                text = message.content,
                thoughtSignature = signatures.singleOrNull(),
            )
        }
        val contents = raw.mergeGeminiRoles()
        return PreparedGenerationRequest.GenerateContent(
            request = GeminiGenerateContentRequest(
                model = connection.selectedModel,
                contents = contents,
                systemInstruction = system,
                maxOutputTokens = plan.maxOutputTokens.takeIf {
                    settings.isEnabled(PresetGenerationParameter.OUTPUT_LIMIT)
                },
                temperature = temperature,
                topP = topP,
                topK = topK,
                seed = seed,
                frequencyPenalty = frequencyPenalty,
                presencePenalty = presencePenalty,
                thinking = thinking,
            ),
            preview = ProviderRequestPreview(
                protocol = connection.protocol,
                model = connection.selectedModel,
                systemInstruction = system,
                messages = contents.map {
                    ProviderPreviewMessage(it.role.wire, it.text, it.thoughtSignature != null)
                },
                maxOutputTokens = plan.maxOutputTokens.takeIf {
                    settings.isEnabled(PresetGenerationParameter.OUTPUT_LIMIT)
                },
                store = false,
                usesHostedState = false,
                assistantPrefillApplied = false,
                appliedPresetControls = report.appliedControls(),
                omittedPresetControls = report.omissions(),
            ),
        )
    }

    private fun preview(
        connection: StoredConnection,
        plan: GenerationPlan,
        system: String?,
        messages: List<Pair<String, String>>,
        prefillApplied: Boolean = false,
        requestOutputLimit: Int? = plan.maxOutputTokens.takeIf {
            plan.generationSettings.isEnabled(PresetGenerationParameter.OUTPUT_LIMIT)
        },
        report: PresetMappingReport,
    ) = ProviderRequestPreview(
        protocol = connection.protocol,
        model = connection.selectedModel,
        systemInstruction = system,
        messages = messages.map { (role, content) -> ProviderPreviewMessage(role, content) },
        maxOutputTokens = requestOutputLimit,
        store = false,
        usesHostedState = false,
        assistantPrefillApplied = prefillApplied,
        appliedPresetControls = report.appliedControls(),
        omittedPresetControls = report.omissions(),
    )
}

private class PresetMappingReport {
    private val applied = linkedSetOf<String>()
    private val omitted = linkedMapOf<Pair<String, String>, PresetControlOmission>()

    fun applied(control: String) {
        applied += control
    }

    fun omitted(control: String, reason: String) {
        omitted[control to reason] = PresetControlOmission(control, reason)
    }

    fun omitIfNonDefault(control: String, value: Double?, default: Double, reason: String) {
        if (value != null && value != default) omitted(control, reason)
    }

    fun recordUniversallyUnmapped(settings: PresetGenerationSettings) {
        omitIfNonDefault(
            "top_a",
            settings.enabledValue(PresetGenerationParameter.TOP_A, settings.topA),
            0.0,
            "五种 Provider adapter 均无安全的等价字段",
        )
        omitIfNonDefault(
            "min_p",
            settings.enabledValue(PresetGenerationParameter.MIN_P, settings.minP),
            0.0,
            "五种 Provider adapter 均无安全的等价字段",
        )
        omitIfNonDefault(
            "repetition_penalty",
            settings.enabledValue(PresetGenerationParameter.REPETITION_PENALTY, settings.repetitionPenalty),
            1.0,
            "五种 Provider adapter 均无安全的等价字段",
        )
    }

    fun appliedControls(): List<String> = applied.toList()

    fun omissions(): List<PresetControlOmission> = omitted.values.toList()
}

private fun <T> PresetGenerationSettings.enabledValue(
    parameter: PresetGenerationParameter,
    value: T?,
): T? = value.takeIf { isEnabled(parameter) }

private fun Double?.validRange(
    control: String,
    range: ClosedFloatingPointRange<Double>,
    report: PresetMappingReport,
): Double? = when {
    this == null -> null
    this in range -> this
    else -> {
        report.omitted(control, "值 $this 超出 ${range.start}..${range.endInclusive} 的协议范围")
        null
    }
}

private fun Double?.validMinimum(
    control: String,
    minimum: Double,
    report: PresetMappingReport,
): Double? = when {
    this == null -> null
    this >= minimum -> this
    else -> {
        report.omitted(control, "值 $this 低于协议下限 $minimum")
        null
    }
}

private fun PresetReasoningEffort.openAiWireValue(): String = when (this) {
    PresetReasoningEffort.AUTO -> "medium"
    PresetReasoningEffort.MIN -> "minimal"
    PresetReasoningEffort.LOW -> "low"
    PresetReasoningEffort.MEDIUM -> "medium"
    PresetReasoningEffort.HIGH -> "high"
    PresetReasoningEffort.MAX -> "max"
}

private data class AnthropicModelCapabilities(
    val samplers: Boolean,
    val adaptiveThinking: Boolean,
    val manualThinking: Boolean,
    val assistantPrefill: Boolean,
)

private fun String.anthropicCapabilities(): AnthropicModelCapabilities {
    val value = lowercase()
    val claude3 = Regex("^claude-3(?:-[0-9]+)?-(?:sonnet|opus|haiku)(?:-|$)").containsMatchIn(value)
    val version4 = Regex("^claude-(sonnet|opus|haiku)-4-([0-9]+)(?:-|$)").find(value)
    val family4 = version4?.groupValues?.get(1)
    val minor4 = version4?.groupValues?.get(2)?.toIntOrNull()
    val adaptive = (family4 in setOf("sonnet", "opus") && minor4 != null && minor4 >= 6) ||
        Regex("^claude-(?:fable|mythos)-5(?:-|$)").containsMatchIn(value)
    val samplers = claude3 || (minor4 != null && minor4 <= 6)
    val manual = Regex("^claude-3-7-(?:sonnet|opus)(?:-|$)").containsMatchIn(value) ||
        (family4 in setOf("sonnet", "opus") && minor4 != null && minor4 <= 5)
    val prefill = claude3 || (minor4 != null && minor4 <= 5)
    return AnthropicModelCapabilities(
        samplers = samplers,
        adaptiveThinking = adaptive,
        manualThinking = manual,
        assistantPrefill = prefill,
    )
}

private fun PresetReasoningEffort.anthropicEffort(): String = when (this) {
    PresetReasoningEffort.AUTO, PresetReasoningEffort.MEDIUM -> "medium"
    PresetReasoningEffort.MIN, PresetReasoningEffort.LOW -> "low"
    PresetReasoningEffort.HIGH -> "high"
    PresetReasoningEffort.MAX -> "max"
}

private fun PresetReasoningEffort.anthropicThinkingBudget(maxOutputTokens: Int): Int? {
    if (maxOutputTokens <= 1_024) return null
    val fraction = when (this) {
        PresetReasoningEffort.AUTO -> return null
        PresetReasoningEffort.MIN, PresetReasoningEffort.LOW -> 0.25
        PresetReasoningEffort.MEDIUM -> 0.5
        PresetReasoningEffort.HIGH -> 0.7
        PresetReasoningEffort.MAX -> 0.85
    }
    return (maxOutputTokens * fraction).toInt().coerceIn(1_024, maxOutputTokens - 1)
}

private enum class GeminiThinkingMode { NONE, LEVEL, BUDGET }

private fun String.geminiThinkingMode(): GeminiThinkingMode = when {
    lowercase().contains("gemini-2.5") -> GeminiThinkingMode.BUDGET
    geminiThinkingLevels().isNotEmpty() -> GeminiThinkingMode.LEVEL
    else -> GeminiThinkingMode.NONE
}

private fun String.geminiThinkingLevels(): Set<String> {
    val value = lowercase().removePrefix("models/")
    return when {
        Regex("^gemini-3(?:\\.0)?-pro(?:-|$)").containsMatchIn(value) -> setOf("low", "high")
        Regex("^gemini-3\\.1-pro(?:-|$)").containsMatchIn(value) -> setOf("low", "medium", "high")
        value.startsWith("gemini-3") -> setOf("minimal", "low", "medium", "high")
        else -> emptySet()
    }
}

private fun PresetReasoningEffort.geminiThinkingLevel(
    supported: Set<String>,
    report: PresetMappingReport,
): String {
    val desired = when (this) {
        PresetReasoningEffort.AUTO, PresetReasoningEffort.MEDIUM -> "medium"
        PresetReasoningEffort.MIN -> "minimal"
        PresetReasoningEffort.LOW -> "low"
        PresetReasoningEffort.HIGH, PresetReasoningEffort.MAX -> "high"
    }
    if (desired in supported) return desired
    val fallback = when {
        desired == "minimal" && "low" in supported -> "low"
        desired == "medium" && "high" in supported -> "high"
        "low" in supported -> "low"
        else -> supported.first()
    }
    report.omitted("reasoning_effort=$desired", "模型不支持该 level，已安全降级为 $fallback")
    return fallback
}

private fun PresetReasoningEffort.geminiThinkingBudget(): Int = when (this) {
    PresetReasoningEffort.AUTO -> 0
    PresetReasoningEffort.MIN -> 512
    PresetReasoningEffort.LOW -> 1_024
    PresetReasoningEffort.MEDIUM -> 4_096
    PresetReasoningEffort.HIGH -> 8_192
    PresetReasoningEffort.MAX -> 16_384
}

private fun String.toOpenAiMessageName(): String? = takeIf { value ->
    value.isNotBlank() && value.length <= 64 && value.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }
}

private fun List<AnthropicMessage>.mergeAnthropicRoles(): List<AnthropicMessage> = fold(mutableListOf()) { result, item ->
    val previous = result.lastOrNull()
    if (previous?.role == item.role) {
        result[result.lastIndex] = previous.copy(text = previous.text + "\n\n" + item.text)
    } else {
        result += item
    }
    result
}

private fun List<GeminiContent>.mergeGeminiRoles(): List<GeminiContent> = fold(mutableListOf()) { result, item ->
    val previous = result.lastOrNull()
    if (previous?.role == item.role && previous.thoughtSignature == null && item.thoughtSignature == null) {
        result[result.lastIndex] = previous.copy(text = previous.text + "\n\n" + item.text)
    } else {
        result += item
    }
    result
}

private fun ResponsesUsage.toGenerationUsage() = GenerationUsage(
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    totalTokens = totalTokens,
    cachedTokens = cachedInputTokens,
    reasoningTokens = reasoningOutputTokens,
)

private fun ChatCompletionsUsage.toGenerationUsage() = GenerationUsage(
    inputTokens = promptTokens,
    outputTokens = completionTokens,
    totalTokens = totalTokens,
    cachedTokens = cachedPromptTokens,
    reasoningTokens = reasoningCompletionTokens,
)

private fun AnthropicUsage.toGenerationUsage() = GenerationUsage(
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    totalTokens = listOfNotNull(inputTokens, outputTokens).takeIf(List<Long>::isNotEmpty)?.sum(),
    cachedTokens = listOfNotNull(cacheCreationInputTokens, cacheReadInputTokens).takeIf(List<Long>::isNotEmpty)?.sum(),
)

private fun GeminiInteractionsUsage.toGenerationUsage() = GenerationUsage(
    inputTokens = totalInputTokens,
    outputTokens = totalOutputTokens,
    totalTokens = totalTokens,
    cachedTokens = totalCachedTokens,
    reasoningTokens = totalThoughtTokens,
)

private fun GeminiGenerateContentUsage.toGenerationUsage() = GenerationUsage(
    inputTokens = promptTokenCount,
    outputTokens = candidatesTokenCount,
    totalTokens = totalTokenCount,
    cachedTokens = cachedContentTokenCount,
    reasoningTokens = thoughtsTokenCount,
)
