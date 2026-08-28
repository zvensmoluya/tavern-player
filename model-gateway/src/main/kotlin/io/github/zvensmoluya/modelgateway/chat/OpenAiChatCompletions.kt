package io.github.zvensmoluya.modelgateway.chat

import io.github.zvensmoluya.modelgateway.ConnectionTarget
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.modelgateway.StreamResult
import io.github.zvensmoluya.modelgateway.TokenUsage
import io.github.zvensmoluya.modelgateway.long
import io.github.zvensmoluya.modelgateway.obj
import io.github.zvensmoluya.modelgateway.string
import io.github.zvensmoluya.modelgateway.transport.GatewayTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ChatRole(val wire: String) {
    DEVELOPER("developer"),
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
}

data class ChatMessage(
    val role: ChatRole,
    val content: String,
)

data class ChatCompletionsRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val maxCompletionTokens: Int? = null,
    val reasoningEffort: String? = null,
    val store: Boolean? = null,
)

sealed interface ChatCompletionsEvent {
    val raw: JsonObject

    data class Started(val responseId: String?, override val raw: JsonObject) : ChatCompletionsEvent
    data class TextDelta(val text: String, override val raw: JsonObject) : ChatCompletionsEvent
    data class ReasoningDelta(val text: String, override val raw: JsonObject) : ChatCompletionsEvent
    data class Usage(val usage: TokenUsage, override val raw: JsonObject) : ChatCompletionsEvent
    data class Finished(val reason: String?, override val raw: JsonObject) : ChatCompletionsEvent
    data class Unknown(override val raw: JsonObject) : ChatCompletionsEvent
}

class OpenAiChatCompletionsClient internal constructor(
    private val transport: GatewayTransport,
) {
    fun stream(target: ConnectionTarget, request: ChatCompletionsRequest): Flow<ChatCompletionsEvent> = flow {
        if (target.protocol != ModelProtocol.OPENAI_CHAT_COMPLETIONS) {
            throw GatewayException.Configuration("Chat Completions client requires an OPENAI_CHAT_COMPLETIONS connection")
        }
        validateRequest(request)
        transport.postSse(
            target = target,
            url = target.resolveStreamUrl(request.model),
            jsonBody = request.toJson().toString(),
        ).collect { frame ->
            if (frame.data == "[DONE]") return@collect
            parse(frame.data).forEach { emit(it) }
        }
    }
}

class ChatCompletionsAccumulator {
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private var usage: TokenUsage? = null
    private var finishReason: String? = null
    private var responseId: String? = null
    private var unknown = 0

    fun accept(event: ChatCompletionsEvent) {
        when (event) {
            is ChatCompletionsEvent.Started -> responseId = event.responseId ?: responseId
            is ChatCompletionsEvent.TextDelta -> text.append(event.text)
            is ChatCompletionsEvent.ReasoningDelta -> reasoning.append(event.text)
            is ChatCompletionsEvent.Usage -> usage = event.usage
            is ChatCompletionsEvent.Finished -> finishReason = event.reason
            is ChatCompletionsEvent.Unknown -> unknown += 1
        }
    }

    fun result(): StreamResult = StreamResult(
        text = text.toString(),
        reasoning = reasoning.toString(),
        signatures = emptyList(),
        usage = usage,
        finishReason = finishReason,
        responseId = responseId,
        unknownEventCount = unknown,
    )
}

private val json = Json { ignoreUnknownKeys = true }

private fun validateRequest(request: ChatCompletionsRequest) {
    if (request.model.isBlank()) throw GatewayException.Configuration("Model id is required")
    if (request.messages.isEmpty()) throw GatewayException.Configuration("Chat messages cannot be empty")
    if (request.maxCompletionTokens != null && request.maxCompletionTokens <= 0) {
        throw GatewayException.Configuration("maxCompletionTokens must be positive")
    }
}

private fun ChatCompletionsRequest.toJson(): JsonObject = buildJsonObject {
    put("model", model)
    put("stream", true)
    put("stream_options", buildJsonObject { put("include_usage", true) })
    put("messages", buildJsonArray {
        messages.forEach { message ->
            add(buildJsonObject {
                put("role", message.role.wire)
                put("content", message.content)
            })
        }
    })
    maxCompletionTokens?.let { put("max_completion_tokens", it) }
    reasoningEffort?.let { put("reasoning_effort", it) }
    store?.let { put("store", it) }
}

private fun parse(data: String): List<ChatCompletionsEvent> {
    val raw = try {
        json.parseToJsonElement(data) as? JsonObject
            ?: throw GatewayException.Protocol("Chat stream event was not a JSON object")
    } catch (error: GatewayException) {
        throw error
    } catch (error: Exception) {
        throw GatewayException.Protocol("Malformed Chat Completions event JSON", error)
    }
    val choices = raw["choices"] as? JsonArray
    val firstChoice = choices?.firstOrNull() as? JsonObject
    val delta = firstChoice?.obj("delta")
    return buildList {
        raw.string("id")?.let { add(ChatCompletionsEvent.Started(it, raw)) }
        delta?.string("content")?.takeIf(String::isNotEmpty)?.let {
            add(ChatCompletionsEvent.TextDelta(it, raw))
        }
        (delta?.string("reasoning_content") ?: delta?.string("reasoning"))
            ?.takeIf(String::isNotEmpty)
            ?.let { add(ChatCompletionsEvent.ReasoningDelta(it, raw)) }
        firstChoice?.string("finish_reason")?.let {
            add(ChatCompletionsEvent.Finished(it, raw))
        }
        raw.obj("usage")?.let { usage ->
            add(
                ChatCompletionsEvent.Usage(
                    TokenUsage(
                        inputTokens = usage.long("prompt_tokens"),
                        outputTokens = usage.long("completion_tokens"),
                        totalTokens = usage.long("total_tokens"),
                        cachedTokens = usage.obj("prompt_tokens_details")?.long("cached_tokens"),
                        reasoningTokens = usage.obj("completion_tokens_details")?.long("reasoning_tokens"),
                        raw = usage,
                    ),
                    raw,
                ),
            )
        }
        if (isEmpty()) add(ChatCompletionsEvent.Unknown(raw))
    }
}
