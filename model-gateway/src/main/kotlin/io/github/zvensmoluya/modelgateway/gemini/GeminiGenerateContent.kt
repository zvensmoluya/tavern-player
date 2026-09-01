package io.github.zvensmoluya.modelgateway.gemini

import io.github.zvensmoluya.modelgateway.ConnectionTarget
import io.github.zvensmoluya.modelgateway.EndpointRules
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
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val seed: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val thinking: GeminiThinkingConfig? = null,
)

data class GeminiGenerateContentUsage(
    val promptTokenCount: Long? = null,
    val candidatesTokenCount: Long? = null,
    val totalTokenCount: Long? = null,
    val cachedContentTokenCount: Long? = null,
    val thoughtsTokenCount: Long? = null,
    val toolUsePromptTokenCount: Long? = null,
    val raw: JsonObject,
)

data class GeminiGenerateContentResult(
    val text: String,
    val thoughts: String,
    val thoughtSignatures: List<String>,
    val usage: GeminiGenerateContentUsage?,
    val finishReason: String?,
    val unknownEventCount: Int,
)

data class GeminiTokenCount(val totalTokens: Long, val raw: JsonObject)

sealed interface GeminiGenerateContentEvent {
    val raw: JsonObject

    data class TextDelta(val text: String, override val raw: JsonObject) : GeminiGenerateContentEvent
    data class ThoughtDelta(val text: String, override val raw: JsonObject) : GeminiGenerateContentEvent
    data class Signature(val signature: String, override val raw: JsonObject) : GeminiGenerateContentEvent
    data class Usage(val usage: GeminiGenerateContentUsage, override val raw: JsonObject) : GeminiGenerateContentEvent
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

    suspend fun countTokens(target: ConnectionTarget, request: GeminiGenerateContentRequest): GeminiTokenCount {
        if (target.protocol != ModelProtocol.GEMINI_GENERATE_CONTENT) {
            throw GatewayException.Configuration("GenerateContent client requires a GEMINI_GENERATE_CONTENT connection")
        }
        validateRequest(request)
        val streamUrl = target.resolveStreamUrl(request.model)
        val countPath = streamUrl.encodedPath
            .replace(":streamGenerateContent", ":countTokens")
            .replace(":generateContent", ":countTokens")
        if (countPath == streamUrl.encodedPath) {
            throw GatewayException.Configuration("GenerateContent endpoint cannot derive the countTokens endpoint")
        }
        val countUrl = streamUrl.newBuilder()
            .encodedPath(countPath)
            .removeAllQueryParameters("alt")
            .build()
        val body = buildJsonObject {
            put("contents", request.toJson().getValue("contents"))
            request.systemInstruction?.let {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", it) }) })
                })
            }
        }
        val raw = parseTokenCountJson(transport.postJson(target, countUrl, body.toString()))
        val count = raw.long("totalTokens")
            ?: throw GatewayException.Protocol("Gemini token count response omitted totalTokens")
        return GeminiTokenCount(count, raw)
    }
}

class GeminiGenerateContentAccumulator {
    private val text = StringBuilder()
    private val thought = StringBuilder()
    private val signatures = mutableListOf<String>()
    private var usage: GeminiGenerateContentUsage? = null
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

    fun result(): GeminiGenerateContentResult = GeminiGenerateContentResult(
        text = text.toString(),
        thoughts = thought.toString(),
        thoughtSignatures = signatures.toList(),
        usage = usage,
        finishReason = finishReason,
        unknownEventCount = unknown,
    )
}

private val json = Json { ignoreUnknownKeys = true }

private fun parseTokenCountJson(value: String): JsonObject = try {
    json.parseToJsonElement(value) as? JsonObject
        ?: throw GatewayException.Protocol("Gemini token count response was not a JSON object")
} catch (error: GatewayException) {
    throw error
} catch (error: Exception) {
    throw GatewayException.Protocol("Malformed Gemini token count response JSON", error)
}

private fun validateRequest(request: GeminiGenerateContentRequest) {
    if (EndpointRules.normalizeGeminiModelId(request.model).isBlank()) {
        throw GatewayException.Configuration("Model id is required")
    }
    if (request.contents.isEmpty()) throw GatewayException.Configuration("Gemini contents cannot be empty")
    if (request.maxOutputTokens != null && request.maxOutputTokens <= 0) {
        throw GatewayException.Configuration("maxOutputTokens must be positive")
    }
    if (request.temperature != null && request.temperature < 0.0) {
        throw GatewayException.Configuration("temperature must not be negative")
    }
    if (request.topP != null && request.topP !in 0.0..1.0) {
        throw GatewayException.Configuration("topP must be between 0 and 1")
    }
    if (request.topK != null && request.topK < 0) {
        throw GatewayException.Configuration("topK must not be negative")
    }
    if (request.frequencyPenalty != null && request.frequencyPenalty !in -2.0..2.0) {
        throw GatewayException.Configuration("frequencyPenalty must be between -2 and 2")
    }
    if (request.presencePenalty != null && request.presencePenalty !in -2.0..2.0) {
        throw GatewayException.Configuration("presencePenalty must be between -2 and 2")
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
    if (
        maxOutputTokens != null || temperature != null || topP != null || topK != null || seed != null ||
        frequencyPenalty != null || presencePenalty != null || thinking != null
    ) {
        put("generationConfig", buildJsonObject {
            maxOutputTokens?.let { put("maxOutputTokens", it) }
            temperature?.let { put("temperature", it) }
            topP?.let { put("topP", it) }
            topK?.let { put("topK", it) }
            seed?.let { put("seed", it) }
            frequencyPenalty?.let { put("frequencyPenalty", it) }
            presencePenalty?.let { put("presencePenalty", it) }
            thinking?.let { config ->
                put("thinkingConfig", buildJsonObject {
                    config.includeThoughts?.let { put("includeThoughts", it) }
                    config.thinkingBudget?.let { put("thinkingBudget", it) }
                    config.thinkingLevel?.let { put("thinkingLevel", it) }
                })
            }
        })
    }
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
                    GeminiGenerateContentUsage(
                        promptTokenCount = usage.long("promptTokenCount"),
                        candidatesTokenCount = usage.long("candidatesTokenCount"),
                        totalTokenCount = usage.long("totalTokenCount"),
                        cachedContentTokenCount = usage.long("cachedContentTokenCount"),
                        thoughtsTokenCount = usage.long("thoughtsTokenCount"),
                        toolUsePromptTokenCount = usage.long("toolUsePromptTokenCount"),
                        raw = usage,
                    ),
                    raw,
                ),
            )
        }
        if (isEmpty()) add(GeminiGenerateContentEvent.Unknown(raw))
    }
}
