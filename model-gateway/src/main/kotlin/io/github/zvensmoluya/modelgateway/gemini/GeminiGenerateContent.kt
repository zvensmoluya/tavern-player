package io.github.zvensmoluya.modelgateway.gemini

import io.github.zvensmoluya.modelgateway.ConnectionTarget
import io.github.zvensmoluya.modelgateway.EndpointRules
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class GeminiContentRole(val wire: String) {
    USER("user"),
    MODEL("model"),
}

data class GeminiContent(
    val role: GeminiContentRole,
    val text: String,
    val thoughtSignature: String? = null,
)

data class GeminiThinkingConfig(
    val includeThoughts: Boolean? = null,
    val thinkingBudget: Int? = null,
    val thinkingLevel: String? = null,
)

data class GeminiGenerateContentRequest(
    val model: String,
    val contents: List<GeminiContent>,
    val systemInstruction: String? = null,
    val maxOutputTokens: Int? = null,
    val thinking: GeminiThinkingConfig? = null,
    val store: Boolean? = null,
)

sealed interface GeminiGenerateContentEvent {
    val raw: JsonObject

    data class TextDelta(val text: String, override val raw: JsonObject) : GeminiGenerateContentEvent
    data class ThoughtDelta(val text: String, override val raw: JsonObject) : GeminiGenerateContentEvent
    data class Signature(val signature: String, override val raw: JsonObject) : GeminiGenerateContentEvent
    data class Usage(val usage: TokenUsage, override val raw: JsonObject) : GeminiGenerateContentEvent
    data class Finished(val reason: String?, override val raw: JsonObject) : GeminiGenerateContentEvent
    data class Unknown(override val raw: JsonObject) : GeminiGenerateContentEvent
}

class GeminiGenerateContentClient internal constructor(
    private val transport: GatewayTransport,
) {
    fun stream(target: ConnectionTarget, request: GeminiGenerateContentRequest): Flow<GeminiGenerateContentEvent> = flow {
        if (target.protocol != ModelProtocol.GEMINI_GENERATE_CONTENT) {
            throw GatewayException.Configuration("GenerateContent client requires a GEMINI_GENERATE_CONTENT connection")
        }
        validateRequest(request)
        transport.postSse(
            target = target,
            url = target.resolveStreamUrl(request.model),
            jsonBody = request.toJson().toString(),
        ).collect { frame ->
            parse(frame.data).forEach { emit(it) }
        }
    }
}

class GeminiGenerateContentAccumulator {
    private val text = StringBuilder()
    private val thought = StringBuilder()
    private val signatures = mutableListOf<String>()
    private var usage: TokenUsage? = null
    private var finishReason: String? = null
    private var unknown = 0

    fun accept(event: GeminiGenerateContentEvent) {
        when (event) {
            is GeminiGenerateContentEvent.TextDelta -> text.append(event.text)
            is GeminiGenerateContentEvent.ThoughtDelta -> thought.append(event.text)
            is GeminiGenerateContentEvent.Signature -> signatures += event.signature
            is GeminiGenerateContentEvent.Usage -> usage = event.usage
            is GeminiGenerateContentEvent.Finished -> finishReason = event.reason
            is GeminiGenerateContentEvent.Unknown -> unknown += 1
        }
    }

    fun result(): StreamResult = StreamResult(
        text = text.toString(),
        reasoning = thought.toString(),
        signatures = signatures.toList(),
        usage = usage,
        finishReason = finishReason,
        responseId = null,
        unknownEventCount = unknown,
    )
}

private val json = Json { ignoreUnknownKeys = true }

private fun validateRequest(request: GeminiGenerateContentRequest) {
    if (EndpointRules.normalizeModelId(request.model).isBlank()) {
        throw GatewayException.Configuration("Model id is required")
    }
    if (request.contents.isEmpty()) throw GatewayException.Configuration("Gemini contents cannot be empty")
    if (request.maxOutputTokens != null && request.maxOutputTokens <= 0) {
        throw GatewayException.Configuration("maxOutputTokens must be positive")
    }
}

private fun GeminiGenerateContentRequest.toJson(): JsonObject = buildJsonObject {
    put("contents", buildJsonArray {
        contents.forEach { content ->
            add(buildJsonObject {
                put("role", content.role.wire)
                put("parts", buildJsonArray {
                    add(buildJsonObject {
                        put("text", content.text)
                        content.thoughtSignature?.let { put("thoughtSignature", it) }
                    })
                })
            })
        }
    })
    systemInstruction?.let {
        put("systemInstruction", buildJsonObject {
            put("parts", buildJsonArray { add(buildJsonObject { put("text", it) }) })
        })
    }
    if (maxOutputTokens != null || thinking != null) {
        put("generationConfig", buildJsonObject {
            maxOutputTokens?.let { put("maxOutputTokens", it) }
            thinking?.let { config ->
                put("thinkingConfig", buildJsonObject {
                    config.includeThoughts?.let { put("includeThoughts", it) }
                    config.thinkingBudget?.let { put("thinkingBudget", it) }
                    config.thinkingLevel?.let { put("thinkingLevel", it) }
                })
            }
        })
    }
    store?.let { put("store", it) }
}

private fun parse(data: String): List<GeminiGenerateContentEvent> {
    val raw = try {
        json.parseToJsonElement(data) as? JsonObject
            ?: throw GatewayException.Protocol("GenerateContent event was not a JSON object")
    } catch (error: GatewayException) {
        throw error
    } catch (error: Exception) {
        throw GatewayException.Protocol("Malformed GenerateContent event JSON", error)
    }
    return buildList {
        val candidates = raw["candidates"] as? JsonArray
        candidates?.forEach { candidateElement ->
            val candidate = candidateElement as? JsonObject ?: return@forEach
            val parts = candidate.obj("content")?.get("parts") as? JsonArray
            parts?.forEach { partElement ->
                val part = partElement as? JsonObject ?: return@forEach
                val signature = part.string("thoughtSignature")
                if (!signature.isNullOrEmpty()) add(GeminiGenerateContentEvent.Signature(signature, raw))
                val partText = part.string("text")
                if (!partText.isNullOrEmpty()) {
                    val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
                    add(
                        if (isThought) GeminiGenerateContentEvent.ThoughtDelta(partText, raw)
                        else GeminiGenerateContentEvent.TextDelta(partText, raw),
                    )
                }
                if (signature.isNullOrEmpty() && partText.isNullOrEmpty()) {
                    add(GeminiGenerateContentEvent.Unknown(raw))
                }
            }
            candidate.string("finishReason")?.let { add(GeminiGenerateContentEvent.Finished(it, raw)) }
        }
        raw.obj("usageMetadata")?.let { usage ->
            add(
                GeminiGenerateContentEvent.Usage(
                    TokenUsage(
                        inputTokens = usage.long("promptTokenCount"),
                        outputTokens = usage.long("candidatesTokenCount"),
                        totalTokens = usage.long("totalTokenCount"),
                        cachedTokens = usage.long("cachedContentTokenCount"),
                        reasoningTokens = usage.long("thoughtsTokenCount"),
                        raw = usage,
                    ),
                    raw,
                ),
            )
        }
        if (isEmpty()) add(GeminiGenerateContentEvent.Unknown(raw))
    }
}
