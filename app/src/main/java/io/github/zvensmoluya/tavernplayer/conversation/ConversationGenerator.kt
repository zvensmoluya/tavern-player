package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.ModelGateway
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicMessage
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicMessagesEvent
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicMessagesRequest
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicRole
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
import io.github.zvensmoluya.modelgateway.responses.ResponsesRequest
import io.github.zvensmoluya.modelgateway.responses.ResponsesRole
import io.github.zvensmoluya.modelgateway.responses.ResponsesUsage
import io.github.zvensmoluya.tavernplayer.connections.ConnectionRepository
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
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
    val maxOutputTokens: Int,
    val store: Boolean,
    val usesHostedState: Boolean,
    val assistantPrefillApplied: Boolean,
)

sealed interface GenerationEvent {
    data class RequestPrepared(val preview: ProviderRequestPreview) : GenerationEvent
    data class TextDelta(val text: String) : GenerationEvent
    data object ReasoningStarted : GenerationEvent
    data class ReasoningDelta(val text: String) : GenerationEvent
    data class ReasoningSignature(val signature: String) : GenerationEvent
    data object ReasoningFinished : GenerationEvent
    data class Usage(val value: GenerationUsage) : GenerationEvent
    data class Finished(val reason: String?) : GenerationEvent
    data class Diagnostic(val summary: String) : GenerationEvent
}

fun interface ConversationGenerator {
    fun stream(connection: StoredConnection, plan: GenerationPlan): Flow<GenerationEvent>
}

class ModelGatewayConversationGenerator(
    private val gateway: ModelGateway,
    private val repository: ConnectionRepository,
) : ConversationGenerator {
    override fun stream(connection: StoredConnection, plan: GenerationPlan): Flow<GenerationEvent> = flow {
        repository.ensureReady(connection)
        val prepared = GenerationRequestMapper.map(connection, plan)
        emit(GenerationEvent.RequestPrepared(prepared.preview))
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

internal object GenerationRequestMapper {
    fun map(connection: StoredConnection, plan: GenerationPlan): PreparedGenerationRequest {
        val protocol = connection.protocol
        if (plan.assistantPrefill.isNotBlank() && protocol != ModelProtocol.ANTHROPIC_MESSAGES) {
            throw GatewayException.Configuration("当前 Provider 无法准确表达 assistant prefill")
        }
        return when (protocol) {
            ModelProtocol.OPENAI_RESPONSES -> responses(connection, plan)
            ModelProtocol.OPENAI_CHAT_COMPLETIONS -> chat(connection, plan)
            ModelProtocol.ANTHROPIC_MESSAGES -> anthropic(connection, plan)
            ModelProtocol.GEMINI_INTERACTIONS -> interactions(connection, plan)
            ModelProtocol.GEMINI_GENERATE_CONTENT -> generateContent(connection, plan)
        }
    }

    private fun responses(connection: StoredConnection, plan: GenerationPlan): PreparedGenerationRequest.Responses {
        val messages = plan.messages.map { message ->
            ResponsesInputMessage(
                role = when (message.role) {
                    MessageRole.SYSTEM -> ResponsesRole.SYSTEM
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
                instructions = null,
                maxOutputTokens = plan.maxOutputTokens,
                previousResponseId = null,
                store = false,
            ),
            preview = preview(connection, plan, system = null, messages.map { it.role.wire to it.text }),
        )
    }

    private fun chat(connection: StoredConnection, plan: GenerationPlan): PreparedGenerationRequest.Chat {
        val messages = plan.messages.map { message ->
            ChatMessage(
                role = when (message.role) {
                    MessageRole.SYSTEM -> ChatRole.SYSTEM
                    MessageRole.USER -> ChatRole.USER
                    MessageRole.ASSISTANT -> ChatRole.ASSISTANT
                },
                content = message.content,
            )
        }
        return PreparedGenerationRequest.Chat(
            request = ChatCompletionsRequest(
                model = connection.selectedModel,
                messages = messages,
                maxCompletionTokens = plan.maxOutputTokens,
                store = false,
            ),
            preview = preview(connection, plan, system = null, messages.map { it.role.wire to it.content }),
        )
    }

    private fun anthropic(connection: StoredConnection, plan: GenerationPlan): PreparedGenerationRequest.Anthropic {
        val leadingSystem = plan.messages.takeWhile { it.role == MessageRole.SYSTEM }
        val system = leadingSystem.joinToString("\n\n") { it.content }.ifBlank { null }
        val mapped = plan.messages.drop(leadingSystem.size).map { message ->
            AnthropicMessage(
                role = if (message.role == MessageRole.ASSISTANT) AnthropicRole.ASSISTANT else AnthropicRole.USER,
                text = message.content,
            )
        }.mergeAnthropicRoles().toMutableList()
        plan.assistantPrefill.takeIf(String::isNotBlank)?.let { prefill ->
            if (mapped.lastOrNull()?.role == AnthropicRole.ASSISTANT) {
                val last = mapped.removeAt(mapped.lastIndex)
                mapped += last.copy(text = last.text + "\n\n" + prefill)
            } else {
                mapped += AnthropicMessage(AnthropicRole.ASSISTANT, prefill)
            }
        }
        if (mapped.isEmpty()) throw GatewayException.Configuration("Anthropic request requires conversation messages")
        return PreparedGenerationRequest.Anthropic(
            request = AnthropicMessagesRequest(
                model = connection.selectedModel,
                messages = mapped,
                maxTokens = plan.maxOutputTokens,
                system = system,
            ),
            preview = preview(
                connection,
                plan,
                system,
                mapped.map { it.role.wire to it.text },
                prefillApplied = plan.assistantPrefill.isNotBlank(),
            ),
        )
    }

    private fun interactions(connection: StoredConnection, plan: GenerationPlan): PreparedGenerationRequest.Interactions {
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
                maxOutputTokens = plan.maxOutputTokens,
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
            ),
        )
    }

    private fun generateContent(connection: StoredConnection, plan: GenerationPlan): PreparedGenerationRequest.GenerateContent {
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
                maxOutputTokens = plan.maxOutputTokens,
                store = false,
            ),
            preview = ProviderRequestPreview(
                protocol = connection.protocol,
                model = connection.selectedModel,
                systemInstruction = system,
                messages = contents.map {
                    ProviderPreviewMessage(it.role.wire, it.text, it.thoughtSignature != null)
                },
                maxOutputTokens = plan.maxOutputTokens,
                store = false,
                usesHostedState = false,
                assistantPrefillApplied = false,
            ),
        )
    }

    private fun preview(
        connection: StoredConnection,
        plan: GenerationPlan,
        system: String?,
        messages: List<Pair<String, String>>,
        prefillApplied: Boolean = false,
    ) = ProviderRequestPreview(
        protocol = connection.protocol,
        model = connection.selectedModel,
        systemInstruction = system,
        messages = messages.map { (role, content) -> ProviderPreviewMessage(role, content) },
        maxOutputTokens = plan.maxOutputTokens,
        store = false,
        usesHostedState = false,
        assistantPrefillApplied = prefillApplied,
    )
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
