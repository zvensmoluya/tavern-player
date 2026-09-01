package io.github.zvensmoluya.modelgateway.anthropic

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class AnthropicRole(val wire: String) {
    USER("user"),
    ASSISTANT("assistant"),
}

data class AnthropicMessage(
    val role: AnthropicRole,
    val text: String,
)

enum class AnthropicThinkingType(val wire: String) {
    ENABLED("enabled"),
    ADAPTIVE("adaptive"),
}

data class AnthropicThinking(
    val type: AnthropicThinkingType,
    val budgetTokens: Int? = null,
)

data class AnthropicOutputConfig(
    val effort: String? = null,
)

data class AnthropicMessagesRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val maxTokens: Int,
    val system: String? = null,
    val thinking: AnthropicThinking? = null,
    val outputConfig: AnthropicOutputConfig? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
)

data class AnthropicUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheCreationInputTokens: Long? = null,
    val cacheReadInputTokens: Long? = null,
    val raw: JsonObject,
)

data class AnthropicMessagesResult(
    val text: String,
    val thinking: String,
    val thinkingSignatures: List<String>,
    val usage: AnthropicUsage?,
    val stopReason: String?,
    val messageId: String?,
    val unknownEventCount: Int,
)

data class AnthropicTokenCount(val inputTokens: Long, val raw: JsonObject)

sealed interface AnthropicMessagesEvent {
    val raw: JsonObject

    data class Started(val messageId: String?, override val raw: JsonObject) : AnthropicMessagesEvent
    data class TextDelta(val text: String, override val raw: JsonObject) : AnthropicMessagesEvent
    data class ThinkingDelta(val text: String, override val raw: JsonObject) : AnthropicMessagesEvent
    data class SignatureDelta(val signature: String, override val raw: JsonObject) : AnthropicMessagesEvent
    data class Usage(val usage: AnthropicUsage, override val raw: JsonObject) : AnthropicMessagesEvent
    data class Finished(val reason: String?, override val raw: JsonObject) : AnthropicMessagesEvent
    data class Failed(val message: String?, override val raw: JsonObject) : AnthropicMessagesEvent
    data class Lifecycle(val name: String, override val raw: JsonObject) : AnthropicMessagesEvent
    data class Unknown(override val raw: JsonObject) : AnthropicMessagesEvent
}

class AnthropicMessagesClient internal constructor(
    private val transport: GatewayTransport,
) {
    fun stream(target: ConnectionTarget, request: AnthropicMessagesRequest): Flow<AnthropicMessagesEvent> = flow {
        if (target.protocol != ModelProtocol.ANTHROPIC_MESSAGES) {
            throw GatewayException.Configuration("Anthropic client requires an ANTHROPIC_MESSAGES connection")
        }
        validateRequest(request)
        transport.postSse(
            target = target,
            url = target.resolveStreamUrl(request.model),
            jsonBody = request.toJson().toString(),
            headers = mapOf("anthropic-version" to ANTHROPIC_VERSION),
        ).collect { frame ->
            parse(frame.data).forEach { emit(it) }
        }
    }

    suspend fun countTokens(target: ConnectionTarget, request: AnthropicMessagesRequest): AnthropicTokenCount {
        if (target.protocol != ModelProtocol.ANTHROPIC_MESSAGES) {
            throw GatewayException.Configuration("Anthropic client requires an ANTHROPIC_MESSAGES connection")
        }
        validateRequest(request)
        val streamUrl = target.resolveStreamUrl(request.model)
        val countUrl = streamUrl.newBuilder()
            .encodedPath(streamUrl.encodedPath.trimEnd('/') + "/count_tokens")
            .query(null)
            .build()
        val body = buildJsonObject {
            put("model", request.model)
            put("messages", buildJsonArray {
                request.messages.forEach { message ->
                    add(buildJsonObject {
                        put("role", message.role.wire)
                        put("content", message.text)
                    })
                }
            })
            request.system?.let { put("system", it) }
        }
        val raw = parseJsonObject(
            transport.postJson(
                target,
                countUrl,
                body.toString(),
                headers = mapOf("anthropic-version" to ANTHROPIC_VERSION),
            ),
            "Anthropic token count",
        )
        val count = raw.long("input_tokens")
            ?: throw GatewayException.Protocol("Anthropic token count response omitted input_tokens")
        return AnthropicTokenCount(count, raw)
    }

    companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

class AnthropicMessagesAccumulator {
    private val text = StringBuilder()
    private val thinking = StringBuilder()
    private val signatures = mutableListOf<String>()
    private var usage: AnthropicUsage? = null
    private var finishReason: String? = null
    private var messageId: String? = null
    private var unknown = 0

    fun accept(event: AnthropicMessagesEvent) {
        when (event) {
            is AnthropicMessagesEvent.Started -> messageId = event.messageId ?: messageId
            is AnthropicMessagesEvent.TextDelta -> text.append(event.text)
            is AnthropicMessagesEvent.ThinkingDelta -> thinking.append(event.text)
            is AnthropicMessagesEvent.SignatureDelta -> signatures += event.signature
            is AnthropicMessagesEvent.Usage -> usage = mergeUsage(usage, event.usage)
            is AnthropicMessagesEvent.Finished -> finishReason = event.reason ?: finishReason
            is AnthropicMessagesEvent.Failed -> finishReason = "failed"
            is AnthropicMessagesEvent.Lifecycle -> Unit
            is AnthropicMessagesEvent.Unknown -> unknown += 1
        }
    }

    fun result(): AnthropicMessagesResult = AnthropicMessagesResult(
        text = text.toString(),
        thinking = thinking.toString(),
        thinkingSignatures = signatures.toList(),
        usage = usage,
        stopReason = finishReason,
        messageId = messageId,
        unknownEventCount = unknown,
    )
}

private val json = Json { ignoreUnknownKeys = true }

private fun parseJsonObject(value: String, label: String): JsonObject = try {
    json.parseToJsonElement(value) as? JsonObject
        ?: throw GatewayException.Protocol("$label response was not a JSON object")
} catch (error: GatewayException) {
    throw error
} catch (error: Exception) {
    throw GatewayException.Protocol("Malformed $label response JSON", error)
}

private fun validateRequest(request: AnthropicMessagesRequest) {
    if (request.model.isBlank()) throw GatewayException.Configuration("Model id is required")
    if (request.messages.isEmpty()) throw GatewayException.Configuration("Anthropic messages cannot be empty")
    if (request.maxTokens <= 0) throw GatewayException.Configuration("maxTokens must be positive")
    if (request.thinking?.type == AnthropicThinkingType.ENABLED &&
        (request.thinking.budgetTokens == null || request.thinking.budgetTokens <= 0)
    ) {
        throw GatewayException.Configuration("Enabled thinking requires a positive budget")
    }
    if (request.thinking?.type == AnthropicThinkingType.ADAPTIVE && request.thinking.budgetTokens != null) {
        throw GatewayException.Configuration("Adaptive thinking cannot include a token budget")
    }
    if (request.temperature != null && request.temperature !in 0.0..1.0) {
        throw GatewayException.Configuration("temperature must be between 0 and 1")
    }
    if (request.topP != null && request.topP !in 0.0..1.0) {
        throw GatewayException.Configuration("topP must be between 0 and 1")
    }
    if (request.topK != null && request.topK < 0) {
        throw GatewayException.Configuration("topK must not be negative")
    }
}

private fun AnthropicMessagesRequest.toJson(): JsonObject = buildJsonObject {
    put("model", model)
    put("stream", true)
    put("max_tokens", maxTokens)
    put("messages", buildJsonArray {
        messages.forEach { message ->
            add(buildJsonObject {
                put("role", message.role.wire)
                put("content", message.text)
            })
        }
    })
    system?.let { put("system", it) }
    thinking?.let {
        put("thinking", buildJsonObject {
            put("type", it.type.wire)
            it.budgetTokens?.let { budget -> put("budget_tokens", budget) }
        })
    }
    outputConfig?.let { config ->
        put("output_config", buildJsonObject {
            config.effort?.let { put("effort", it) }
        })
    }
    temperature?.let { put("temperature", it) }
    topP?.let { put("top_p", it) }
    topK?.let { put("top_k", it) }
}

private fun parse(data: String): List<AnthropicMessagesEvent> {
    val raw = try {
        json.parseToJsonElement(data) as? JsonObject
            ?: throw GatewayException.Protocol("Anthropic event was not a JSON object")
    } catch (error: GatewayException) {
        throw error
    } catch (error: Exception) {
        throw GatewayException.Protocol("Malformed Anthropic event JSON", error)
    }
    return when (raw.string("type")) {
        "message_start" -> buildList {
            val message = raw.obj("message")
            add(AnthropicMessagesEvent.Started(message?.string("id"), raw))
            message?.obj("usage")?.let { add(AnthropicMessagesEvent.Usage(it.toUsage(), raw)) }
        }
        "content_block_delta" -> when (val delta = raw.obj("delta")) {
            null -> listOf(AnthropicMessagesEvent.Unknown(raw))
            else -> when (delta.string("type")) {
                "text_delta" -> listOf(AnthropicMessagesEvent.TextDelta(delta.string("text").orEmpty(), raw))
                "thinking_delta" -> listOf(AnthropicMessagesEvent.ThinkingDelta(delta.string("thinking").orEmpty(), raw))
                "signature_delta" -> listOf(AnthropicMessagesEvent.SignatureDelta(delta.string("signature").orEmpty(), raw))
                else -> listOf(AnthropicMessagesEvent.Unknown(raw))
            }
        }
        "message_delta" -> buildList {
            raw.obj("usage")?.let { add(AnthropicMessagesEvent.Usage(it.toUsage(), raw)) }
            raw.obj("delta")?.string("stop_reason")?.let { add(AnthropicMessagesEvent.Finished(it, raw)) }
            if (isEmpty()) add(AnthropicMessagesEvent.Unknown(raw))
        }
        "message_stop" -> listOf(AnthropicMessagesEvent.Finished(null, raw))
        "error" -> listOf(AnthropicMessagesEvent.Failed(raw.obj("error")?.string("message"), raw))
        "ping", "content_block_start", "content_block_stop" -> listOf(
            AnthropicMessagesEvent.Lifecycle(raw.string("type").orEmpty(), raw),
        )
        else -> listOf(AnthropicMessagesEvent.Unknown(raw))
    }
}

private fun JsonObject.toUsage(): AnthropicUsage = AnthropicUsage(
    inputTokens = long("input_tokens"),
    outputTokens = long("output_tokens"),
    cacheCreationInputTokens = long("cache_creation_input_tokens"),
    cacheReadInputTokens = long("cache_read_input_tokens"),
    raw = this,
)

private fun mergeUsage(previous: AnthropicUsage?, next: AnthropicUsage): AnthropicUsage = AnthropicUsage(
    inputTokens = next.inputTokens ?: previous?.inputTokens,
    outputTokens = next.outputTokens ?: previous?.outputTokens,
    cacheCreationInputTokens = next.cacheCreationInputTokens ?: previous?.cacheCreationInputTokens,
    cacheReadInputTokens = next.cacheReadInputTokens ?: previous?.cacheReadInputTokens,
    raw = next.raw,
)
