package io.github.zvensmoluya.modelgateway.gemini

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface GeminiInteractionInputStep {
    data class UserInput(val text: String) : GeminiInteractionInputStep
    data class ModelOutput(val text: String) : GeminiInteractionInputStep
    data class Thought(val signature: String, val summary: String? = null) : GeminiInteractionInputStep
}

data class GeminiInteractionsRequest(
    val model: String,
    val input: List<GeminiInteractionInputStep>,
    val systemInstruction: String? = null,
    val maxOutputTokens: Int? = null,
    val thinkingLevel: String? = null,
    val previousInteractionId: String? = null,
    val store: Boolean? = null,
)

sealed interface GeminiInteractionsEvent {
    val raw: JsonObject

    data class Started(val interactionId: String?, override val raw: JsonObject) : GeminiInteractionsEvent
    data class StepStarted(val index: Int?, val stepType: String?, override val raw: JsonObject) : GeminiInteractionsEvent
    data class TextDelta(val text: String, override val raw: JsonObject) : GeminiInteractionsEvent
    data class ThoughtDelta(val text: String, override val raw: JsonObject) : GeminiInteractionsEvent
    data class Signature(val signature: String, override val raw: JsonObject) : GeminiInteractionsEvent
    data class StepFinished(val index: Int?, override val raw: JsonObject) : GeminiInteractionsEvent
    data class StatusUpdated(val status: String?, override val raw: JsonObject) : GeminiInteractionsEvent
    data class Usage(val usage: TokenUsage, override val raw: JsonObject) : GeminiInteractionsEvent
    data class Finished(val status: String?, val interactionId: String?, override val raw: JsonObject) : GeminiInteractionsEvent
    data class Failed(val message: String?, override val raw: JsonObject) : GeminiInteractionsEvent
    data class Unknown(override val raw: JsonObject) : GeminiInteractionsEvent
}

class GeminiInteractionsClient internal constructor(
    private val transport: GatewayTransport,
) {
    fun stream(target: ConnectionTarget, request: GeminiInteractionsRequest): Flow<GeminiInteractionsEvent> = flow {
        if (target.protocol != ModelProtocol.GEMINI_INTERACTIONS) {
            throw GatewayException.Configuration("Interactions client requires a GEMINI_INTERACTIONS connection")
        }
        validateRequest(request)
        transport.postSse(
            target = target,
            url = target.resolveStreamUrl(request.model),
            jsonBody = request.toJson().toString(),
        ).collect { frame ->
            parse(frame.data, frame.event).forEach { emit(it) }
        }
    }
}

class GeminiInteractionsAccumulator {
    private val text = StringBuilder()
    private val thought = StringBuilder()
    private val signatures = mutableListOf<String>()
    private var usage: TokenUsage? = null
    private var finishReason: String? = null
    private var interactionId: String? = null
    private var unknown = 0

    fun accept(event: GeminiInteractionsEvent) {
        when (event) {
            is GeminiInteractionsEvent.Started -> interactionId = event.interactionId ?: interactionId
            is GeminiInteractionsEvent.StepStarted,
            is GeminiInteractionsEvent.StepFinished,
            is GeminiInteractionsEvent.StatusUpdated,
            -> Unit
            is GeminiInteractionsEvent.TextDelta -> text.append(event.text)
            is GeminiInteractionsEvent.ThoughtDelta -> thought.append(event.text)
            is GeminiInteractionsEvent.Signature -> signatures += event.signature
            is GeminiInteractionsEvent.Usage -> usage = event.usage
            is GeminiInteractionsEvent.Finished -> {
                finishReason = event.status
                interactionId = event.interactionId ?: interactionId
            }
            is GeminiInteractionsEvent.Failed -> finishReason = "failed"
            is GeminiInteractionsEvent.Unknown -> unknown += 1
        }
    }

    fun result(): StreamResult = StreamResult(
        text = text.toString(),
        reasoning = thought.toString(),
        signatures = signatures.toList(),
        usage = usage,
        finishReason = finishReason,
        responseId = interactionId,
        unknownEventCount = unknown,
    )
}

private val json = Json { ignoreUnknownKeys = true }

private fun validateRequest(request: GeminiInteractionsRequest) {
    if (request.model.isBlank()) throw GatewayException.Configuration("Model id is required")
    if (request.input.isEmpty()) throw GatewayException.Configuration("Interaction input cannot be empty")
    if (request.maxOutputTokens != null && request.maxOutputTokens <= 0) {
        throw GatewayException.Configuration("maxOutputTokens must be positive")
    }
    if (request.store == false && request.previousInteractionId != null) {
        throw GatewayException.Configuration("store=false cannot be combined with previousInteractionId")
    }
}

private fun GeminiInteractionsRequest.toJson(): JsonObject = buildJsonObject {
    put("model", model)
    put("stream", true)
    put("input", buildJsonArray {
        input.forEach { step ->
            add(when (step) {
                is GeminiInteractionInputStep.UserInput -> buildJsonObject {
                    put("type", "user_input")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", step.text)
                        })
                    })
                }
                is GeminiInteractionInputStep.ModelOutput -> buildJsonObject {
                    put("type", "model_output")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", step.text)
                        })
                    })
                }
                is GeminiInteractionInputStep.Thought -> buildJsonObject {
                    put("type", "thought")
                    put("signature", step.signature)
                    step.summary?.let { put("summary", it) }
                }
            })
        }
    })
    systemInstruction?.let { put("system_instruction", it) }
    if (maxOutputTokens != null || thinkingLevel != null) {
        put("generation_config", buildJsonObject {
            maxOutputTokens?.let { put("max_output_tokens", it) }
            thinkingLevel?.let { put("thinking_level", it) }
        })
    }
    previousInteractionId?.let { put("previous_interaction_id", it) }
    store?.let { put("store", it) }
}

private fun parse(data: String, sseEvent: String?): List<GeminiInteractionsEvent> {
    val raw = try {
        json.parseToJsonElement(data) as? JsonObject
            ?: throw GatewayException.Protocol("Interactions event was not a JSON object")
    } catch (error: GatewayException) {
        throw error
    } catch (error: Exception) {
        throw GatewayException.Protocol("Malformed Interactions event JSON", error)
    }
    return when (raw.string("event_type") ?: sseEvent) {
        "interaction.created" -> listOf(
            GeminiInteractionsEvent.Started(raw.obj("interaction")?.string("id"), raw),
        )
        "step.start" -> listOf(
            GeminiInteractionsEvent.StepStarted(
                index = raw["index"]?.toString()?.toIntOrNull(),
                stepType = raw.obj("step")?.string("type"),
                raw = raw,
            ),
        )
        "step.delta" -> {
            val delta = raw.obj("delta")
            when (delta?.string("type")) {
                "text" -> listOf(GeminiInteractionsEvent.TextDelta(delta.string("text").orEmpty(), raw))
                "thought", "thought_summary" -> listOf(
                    GeminiInteractionsEvent.ThoughtDelta(delta.string("text").orEmpty(), raw),
                )
                "thought_signature" -> listOf(
                    GeminiInteractionsEvent.Signature(delta.string("signature").orEmpty(), raw),
                )
                else -> listOf(GeminiInteractionsEvent.Unknown(raw))
            }
        }
        "step.stop" -> buildList {
            raw.obj("step_usage")?.let { usage -> add(GeminiInteractionsEvent.Usage(usage.toUsage(), raw)) }
            add(GeminiInteractionsEvent.StepFinished(raw["index"]?.toString()?.toIntOrNull(), raw))
        }
        "interaction.status_update" -> listOf(
            GeminiInteractionsEvent.StatusUpdated(raw.string("status"), raw),
        )
        "interaction.completed" -> {
            val interaction = raw.obj("interaction")
            buildList {
                interaction?.obj("usage")?.let { usage ->
                    add(
                        GeminiInteractionsEvent.Usage(
                            usage.toUsage(),
                            raw,
                        ),
                    )
                }
                add(
                    GeminiInteractionsEvent.Finished(
                        status = interaction?.string("status"),
                        interactionId = interaction?.string("id"),
                        raw = raw,
                    ),
                )
            }
        }
        "interaction.failed", "error" -> listOf(
            GeminiInteractionsEvent.Failed(raw.obj("error")?.string("message"), raw),
        )
        else -> listOf(GeminiInteractionsEvent.Unknown(raw))
    }
}

private fun JsonObject.toUsage(): TokenUsage = TokenUsage(
    inputTokens = long("total_input_tokens"),
    outputTokens = long("total_output_tokens"),
    totalTokens = long("total_tokens"),
    cachedTokens = long("total_cached_tokens"),
    reasoningTokens = long("total_thought_tokens"),
    raw = this,
)
