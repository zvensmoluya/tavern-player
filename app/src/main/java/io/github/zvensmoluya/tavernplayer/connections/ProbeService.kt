package io.github.zvensmoluya.tavernplayer.connections

import io.github.zvensmoluya.modelgateway.ModelGateway
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.modelgateway.TokenUsage
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicMessage
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicMessagesEvent
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicMessagesRequest
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicRole
import io.github.zvensmoluya.modelgateway.chat.ChatCompletionsEvent
import io.github.zvensmoluya.modelgateway.chat.ChatCompletionsRequest
import io.github.zvensmoluya.modelgateway.chat.ChatMessage
import io.github.zvensmoluya.modelgateway.chat.ChatRole
import io.github.zvensmoluya.modelgateway.gemini.GeminiContent
import io.github.zvensmoluya.modelgateway.gemini.GeminiContentRole
import io.github.zvensmoluya.modelgateway.gemini.GeminiGenerateContentEvent
import io.github.zvensmoluya.modelgateway.gemini.GeminiGenerateContentRequest
import io.github.zvensmoluya.modelgateway.gemini.GeminiInteractionInputStep
import io.github.zvensmoluya.modelgateway.gemini.GeminiInteractionsEvent
import io.github.zvensmoluya.modelgateway.gemini.GeminiInteractionsRequest
import io.github.zvensmoluya.modelgateway.responses.ResponsesEvent
import io.github.zvensmoluya.modelgateway.responses.ResponsesInputMessage
import io.github.zvensmoluya.modelgateway.responses.ResponsesRequest
import io.github.zvensmoluya.modelgateway.responses.ResponsesRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class ProbeInput(
    val system: String,
    val user: String,
)

sealed interface ProbeEvent {
    data class Text(val delta: String) : ProbeEvent
    data class Reasoning(val delta: String) : ProbeEvent
    data class Usage(val value: TokenUsage) : ProbeEvent
    data class Finished(val reason: String?) : ProbeEvent
    data class Diagnostic(val summary: String) : ProbeEvent
}

class ProbeService(
    private val gateway: ModelGateway,
    private val repository: ConnectionRepository,
) {
    fun stream(connection: StoredConnection, input: ProbeInput): Flow<ProbeEvent> = flow {
        repository.ensureReady(connection)
        if (input.user.isBlank()) throw IllegalArgumentException("User text cannot be blank")
        val target = connection.target()
        val model = connection.selectedModel
        when (connection.protocol) {
            ModelProtocol.OPENAI_RESPONSES -> gateway.responses.stream(
                target,
                ProbeRequestMapper.responses(model, input),
            ).collect { event -> emit(event.toProbeEvent()) }

            ModelProtocol.OPENAI_CHAT_COMPLETIONS -> gateway.chatCompletions.stream(
                target,
                ProbeRequestMapper.chat(model, input),
            ).collect { event -> emit(event.toProbeEvent()) }

            ModelProtocol.ANTHROPIC_MESSAGES -> gateway.anthropicMessages.stream(
                target,
                ProbeRequestMapper.anthropic(model, input),
            ).collect { event -> emit(event.toProbeEvent()) }

            ModelProtocol.GEMINI_INTERACTIONS -> gateway.geminiInteractions.stream(
                target,
                ProbeRequestMapper.interactions(model, input),
            ).collect { event -> emit(event.toProbeEvent()) }

            ModelProtocol.GEMINI_GENERATE_CONTENT -> gateway.geminiGenerateContent.stream(
                target,
                ProbeRequestMapper.generateContent(model, input),
            ).collect { event -> emit(event.toProbeEvent()) }
        }
    }

    companion object {
        const val PROBE_OUTPUT_LIMIT = 512
    }
}

internal object ProbeRequestMapper {
    fun responses(model: String, input: ProbeInput) = ResponsesRequest(
        model = model,
        instructions = input.system.takeIf(String::isNotBlank),
        input = listOf(ResponsesInputMessage(ResponsesRole.USER, input.user)),
        maxOutputTokens = ProbeService.PROBE_OUTPUT_LIMIT,
        store = false,
    )

    fun chat(model: String, input: ProbeInput) = ChatCompletionsRequest(
        model = model,
        messages = buildList {
            input.system.takeIf(String::isNotBlank)?.let { add(ChatMessage(ChatRole.SYSTEM, it)) }
            add(ChatMessage(ChatRole.USER, input.user))
        },
        maxCompletionTokens = ProbeService.PROBE_OUTPUT_LIMIT,
        store = false,
    )

    fun anthropic(model: String, input: ProbeInput) = AnthropicMessagesRequest(
        model = model,
        system = input.system.takeIf(String::isNotBlank),
        messages = listOf(AnthropicMessage(AnthropicRole.USER, input.user)),
        maxTokens = ProbeService.PROBE_OUTPUT_LIMIT,
    )

    fun interactions(model: String, input: ProbeInput) = GeminiInteractionsRequest(
        model = model,
        systemInstruction = input.system.takeIf(String::isNotBlank),
        input = listOf(GeminiInteractionInputStep.UserInput(input.user)),
        maxOutputTokens = ProbeService.PROBE_OUTPUT_LIMIT,
        store = false,
    )

    fun generateContent(model: String, input: ProbeInput) = GeminiGenerateContentRequest(
        model = model,
        systemInstruction = input.system.takeIf(String::isNotBlank),
        contents = listOf(GeminiContent(GeminiContentRole.USER, input.user)),
        maxOutputTokens = ProbeService.PROBE_OUTPUT_LIMIT,
    )
}

private fun ResponsesEvent.toProbeEvent(): ProbeEvent = when (this) {
    is ResponsesEvent.TextDelta -> ProbeEvent.Text(text)
    is ResponsesEvent.ReasoningDelta -> ProbeEvent.Reasoning(text)
    is ResponsesEvent.Usage -> ProbeEvent.Usage(usage)
    is ResponsesEvent.Finished -> ProbeEvent.Finished(status)
    is ResponsesEvent.Failed -> ProbeEvent.Diagnostic("Responses 请求失败${message?.let { ": $it" }.orEmpty()}")
    is ResponsesEvent.Started -> ProbeEvent.Diagnostic("Responses stream 已建立")
    is ResponsesEvent.Lifecycle -> ProbeEvent.Diagnostic("Responses ${name}")
    is ResponsesEvent.Unknown -> ProbeEvent.Diagnostic("收到未识别的 Responses 事件")
}

private fun ChatCompletionsEvent.toProbeEvent(): ProbeEvent = when (this) {
    is ChatCompletionsEvent.TextDelta -> ProbeEvent.Text(text)
    is ChatCompletionsEvent.ReasoningDelta -> ProbeEvent.Reasoning(text)
    is ChatCompletionsEvent.Usage -> ProbeEvent.Usage(usage)
    is ChatCompletionsEvent.Finished -> ProbeEvent.Finished(reason)
    is ChatCompletionsEvent.Started -> ProbeEvent.Diagnostic("Chat stream 已建立")
    is ChatCompletionsEvent.Unknown -> ProbeEvent.Diagnostic("收到未识别的 Chat 事件")
}

private fun AnthropicMessagesEvent.toProbeEvent(): ProbeEvent = when (this) {
    is AnthropicMessagesEvent.TextDelta -> ProbeEvent.Text(text)
    is AnthropicMessagesEvent.ThinkingDelta -> ProbeEvent.Reasoning(text)
    is AnthropicMessagesEvent.SignatureDelta -> ProbeEvent.Diagnostic("收到 Anthropic thinking signature")
    is AnthropicMessagesEvent.Usage -> ProbeEvent.Usage(usage)
    is AnthropicMessagesEvent.Finished -> ProbeEvent.Finished(reason)
    is AnthropicMessagesEvent.Failed -> ProbeEvent.Diagnostic("Anthropic 请求失败${message?.let { ": $it" }.orEmpty()}")
    is AnthropicMessagesEvent.Started -> ProbeEvent.Diagnostic("Anthropic stream 已建立")
    is AnthropicMessagesEvent.Lifecycle -> ProbeEvent.Diagnostic("Anthropic ${name}")
    is AnthropicMessagesEvent.Unknown -> ProbeEvent.Diagnostic("收到未识别的 Anthropic 事件")
}

private fun GeminiInteractionsEvent.toProbeEvent(): ProbeEvent = when (this) {
    is GeminiInteractionsEvent.TextDelta -> ProbeEvent.Text(text)
    is GeminiInteractionsEvent.ThoughtDelta -> ProbeEvent.Reasoning(text)
    is GeminiInteractionsEvent.Signature -> ProbeEvent.Diagnostic("收到 Gemini thought signature")
    is GeminiInteractionsEvent.Usage -> ProbeEvent.Usage(usage)
    is GeminiInteractionsEvent.Finished -> ProbeEvent.Finished(status)
    is GeminiInteractionsEvent.Failed -> ProbeEvent.Diagnostic("Interactions 请求失败${message?.let { ": $it" }.orEmpty()}")
    is GeminiInteractionsEvent.Started -> ProbeEvent.Diagnostic("Interactions stream 已建立")
    is GeminiInteractionsEvent.StepStarted,
    is GeminiInteractionsEvent.StepFinished,
    is GeminiInteractionsEvent.StatusUpdated,
    -> ProbeEvent.Diagnostic("Interactions step 状态已更新")
    is GeminiInteractionsEvent.Unknown -> ProbeEvent.Diagnostic("收到未识别的 Interactions 事件")
}

private fun GeminiGenerateContentEvent.toProbeEvent(): ProbeEvent = when (this) {
    is GeminiGenerateContentEvent.TextDelta -> ProbeEvent.Text(text)
    is GeminiGenerateContentEvent.ThoughtDelta -> ProbeEvent.Reasoning(text)
    is GeminiGenerateContentEvent.Signature -> ProbeEvent.Diagnostic("收到 Gemini thought signature")
    is GeminiGenerateContentEvent.Usage -> ProbeEvent.Usage(usage)
    is GeminiGenerateContentEvent.Finished -> ProbeEvent.Finished(reason)
    is GeminiGenerateContentEvent.Unknown -> ProbeEvent.Diagnostic("收到未识别的 GenerateContent 事件")
}
