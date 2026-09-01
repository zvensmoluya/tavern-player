package io.github.zvensmoluya.modelgateway.responses

import io.github.zvensmoluya.modelgateway.ConnectionTarget
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.modelgateway.long
import io.github.zvensmoluya.modelgateway.obj
import io.github.zvensmoluya.modelgateway.string
import io.github.zvensmoluya.modelgateway.transport.GatewayTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ResponsesRole(val wire: String) {
    DEVELOPER("developer"),
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
}

data class ResponsesInputMessage(
    val role: ResponsesRole,
    val text: String,
)

data class ResponsesReasoning(
    val effort: String? = null,
    val summary: String? = null,
)

data class ResponsesTextConfig(
    val verbosity: String? = null,
)

data class ResponsesRequest(
    val model: String,
    val input: List<ResponsesInputMessage>,
    val instructions: String? = null,
    val maxOutputTokens: Int? = null,
    val previousResponseId: String? = null,
    val reasoning: ResponsesReasoning? = null,
    val text: ResponsesTextConfig? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val store: Boolean? = null,
)

data class ResponsesUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val reasoningOutputTokens: Long? = null,
    val raw: JsonObject,
)

data class ResponsesResult(
    val outputText: String,
    val reasoningSummary: String,
    val usage: ResponsesUsage?,
    val status: String?,
    val responseId: String?,
    val unknownEventCount: Int,
)

sealed interface ResponsesEvent {
    val raw: JsonObject

    data class Started(val responseId: String?, override val raw: JsonObject) : ResponsesEvent
    data class TextDelta(val text: String, override val raw: JsonObject) : ResponsesEvent
    data class ReasoningDelta(val text: String, override val raw: JsonObject) : ResponsesEvent
    data class Usage(val usage: ResponsesUsage, override val raw: JsonObject) : ResponsesEvent
    data class Finished(val status: String?, val responseId: String?, override val raw: JsonObject) : ResponsesEvent
    data class Failed(val message: String?, override val raw: JsonObject) : ResponsesEvent
    data class Lifecycle(val name: String, override val raw: JsonObject) : ResponsesEvent
    data class Unknown(override val raw: JsonObject) : ResponsesEvent
}

class OpenAiResponsesClient internal constructor(
    private val transport: GatewayTransport,
) {
    fun stream(target: ConnectionTarget, request: ResponsesRequest): Flow<ResponsesEvent> = flow {
        requireProtocol(target)
        validateRequest(request)
        val body = request.toJson().toString()
        transport.postSse(target, target.resolveStreamUrl(request.model), body).collect { frame ->
            if (frame.data == "[DONE]") return@collect
            parse(frame.data).forEach { emit(it) }
        }
    }

    private fun requireProtocol(target: ConnectionTarget) {
        if (target.protocol != ModelProtocol.OPENAI_RESPONSES) {
            throw GatewayException.Configuration("Responses client requires an OPENAI_RESPONSES connection")
        }
    }
}

class ResponsesAccumulator {
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private var usage: ResponsesUsage? = null
    private var finishReason: String? = null
    private var responseId: String? = null
    private var unknown = 0

    fun accept(event: ResponsesEvent) {
        when (event) {
            is ResponsesEvent.Started -> responseId = event.responseId ?: responseId
            is ResponsesEvent.TextDelta -> text.append(event.text)
            is ResponsesEvent.ReasoningDelta -> reasoning.append(event.text)
            is ResponsesEvent.Usage -> usage = event.usage
            is ResponsesEvent.Finished -> {
                finishReason = event.status
                responseId = event.responseId ?: responseId
            }
            is ResponsesEvent.Failed -> finishReason = "failed"
            is ResponsesEvent.Lifecycle -> Unit
            is ResponsesEvent.Unknown -> unknown += 1
        }
    }

    fun result(): ResponsesResult = ResponsesResult(
        outputText = text.toString(),
        reasoningSummary = reasoning.toString(),
        usage = usage,
        status = finishReason,
        responseId = responseId,
        unknownEventCount = unknown,
    )
}

private val json = Json { ignoreUnknownKeys = true }

private fun validateRequest(request: ResponsesRequest) {
    if (request.model.isBlank()) throw GatewayException.Configuration("Model id is required")
    if (request.input.isEmpty()) throw GatewayException.Configuration("Responses input cannot be empty")
    if (request.maxOutputTokens != null && request.maxOutputTokens <= 0) {
        throw GatewayException.Configuration("maxOutputTokens must be positive")
    }
    if (request.temperature != null && request.temperature !in 0.0..2.0) {
        throw GatewayException.Configuration("temperature must be between 0 and 2")
    }
    if (request.topP != null && request.topP !in 0.0..1.0) {
        throw GatewayException.Configuration("topP must be between 0 and 1")
    }
}

private fun ResponsesRequest.toJson(): JsonObject = buildJsonObject {
    put("model", model)
    put("stream", true)
    put("input", buildJsonArray {
        input.forEach { message ->
            add(buildJsonObject {
                put("role", message.role.wire)
                put("content", message.text)
            })
        }
    })
    instructions?.let { put("instructions", it) }
    maxOutputTokens?.let { put("max_output_tokens", it) }
    previousResponseId?.let { put("previous_response_id", it) }
    reasoning?.let { value ->
        put("reasoning", buildJsonObject {
            value.effort?.let { put("effort", it) }
            value.summary?.let { put("summary", it) }
        })
    }
    text?.let { value ->
        put("text", buildJsonObject {
            value.verbosity?.let { put("verbosity", it) }
        })
    }
    temperature?.let { put("temperature", it) }
    topP?.let { put("top_p", it) }
    store?.let { put("store", it) }
}

private fun parse(data: String): List<ResponsesEvent> {
    val raw = try {
        json.parseToJsonElement(data) as? JsonObject
            ?: throw GatewayException.Protocol("Responses event was not a JSON object")
    } catch (error: GatewayException) {
        throw error
    } catch (error: Exception) {
        throw GatewayException.Protocol("Malformed Responses event JSON", error)
    }
    return when (raw.string("type")) {
        "response.created", "response.in_progress" -> listOf(
            ResponsesEvent.Started(raw.obj("response")?.string("id"), raw),
        )
        "response.output_text.delta" -> listOf(
            ResponsesEvent.TextDelta(raw.string("delta").orEmpty(), raw),
        )
        "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> listOf(
            ResponsesEvent.ReasoningDelta(raw.string("delta").orEmpty(), raw),
        )
        "response.completed", "response.incomplete" -> {
            val response = raw.obj("response")
            buildList {
                response?.obj("usage")?.let { add(ResponsesEvent.Usage(it.toUsage(), raw)) }
                add(ResponsesEvent.Finished(response?.string("status"), response?.string("id"), raw))
            }
        }
        "response.failed", "error" -> listOf(
            ResponsesEvent.Failed(raw.obj("response")?.obj("error")?.string("message") ?: raw.obj("error")?.string("message"), raw),
        )
        "response.output_item.added",
        "response.output_item.done",
        "response.content_part.added",
        "response.content_part.done",
        "response.output_text.done",
        "response.reasoning_summary_text.done",
        "response.reasoning_text.done",
        -> listOf(ResponsesEvent.Lifecycle(raw.string("type").orEmpty(), raw))
        else -> listOf(ResponsesEvent.Unknown(raw))
    }
}

private fun JsonObject.toUsage(): ResponsesUsage = ResponsesUsage(
    inputTokens = long("input_tokens"),
    outputTokens = long("output_tokens"),
    totalTokens = long("total_tokens"),
    cachedInputTokens = obj("input_tokens_details")?.long("cached_tokens"),
    reasoningOutputTokens = obj("output_tokens_details")?.long("reasoning_tokens"),
    raw = this,
)
