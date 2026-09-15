package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.PresetAsset
import io.github.zvensmoluya.tavernplayer.conversation.storage.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId

@Serializable
data class StreamContext(val generationId: String, val preset: PresetAsset, val modelId: String,
    val evaluationInstant: String, val evaluationZone: String, val projectionVersion: Int = 1)

/** Ordered wire events and a latest projection have independent durable watermarks. */
internal class StreamJournal(val id: String, val variantId: String) {
    val pending = ArrayList<StreamChunk>()
    var seq = 0L; private set
    var projectedThrough = 0L
    var pendingChars = 0; private set
    fun receive(event: GenerationEvent) {
        val (kind, payload) = encodeStreamEvent(event)
        pending += StreamChunk(++seq, kind, payload)
        pendingChars += payload.length
    }
    fun persisted(through: Long) {
        pending.removeAll { it.seq <= through }
        pendingChars = pending.sumOf { it.payload.length }
    }
}

private fun encodeStreamEvent(event: GenerationEvent): Pair<String, String> = when (event) {
    is GenerationEvent.TextDelta -> "text" to event.text
    GenerationEvent.ReasoningStarted -> "reasoning-start" to ""
    is GenerationEvent.ReasoningDelta -> "reasoning" to event.text
    is GenerationEvent.ReasoningSignature -> "signature" to event.signature
    GenerationEvent.ReasoningFinished -> "reasoning-end" to ""
    is GenerationEvent.AssistantStateConfirmed -> "state" to event.envelope
    is GenerationEvent.Finished -> "finish" to (event.reason?.let(::JsonPrimitive) ?: JsonNull).toString()
    is GenerationEvent.Diagnostic -> "diagnostic" to event.summary
    is GenerationEvent.Usage -> "usage" to buildJsonObject {
        event.value.inputTokens?.let { put("input", it) }; event.value.outputTokens?.let { put("output", it) }
        event.value.totalTokens?.let { put("total", it) }; event.value.cachedTokens?.let { put("cached", it) }
        event.value.reasoningTokens?.let { put("reasoning", it) }
    }.toString()
    is GenerationEvent.RequestPrepared -> "request" to buildJsonObject {
        put("protocol", event.preview.protocol.name); put("model", event.preview.model)
        put("systemInstruction", event.preview.systemInstruction?.let(::JsonPrimitive) ?: JsonNull)
        put("maxOutputTokens", event.preview.maxOutputTokens?.let(::JsonPrimitive) ?: JsonNull)
        put("store", event.preview.store); put("usesHostedState", event.preview.usesHostedState)
        put("assistantPrefillApplied", event.preview.assistantPrefillApplied)
        putJsonArray("appliedPresetControls") { event.preview.appliedPresetControls.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("omittedPresetControls") { event.preview.omittedPresetControls.forEach { omission ->
            add(buildJsonObject { put("control", omission.control); put("reason", omission.reason) })
        } }
        putJsonArray("messages") { event.preview.messages.forEach { m -> add(buildJsonObject {
            put("role", m.role); put("content", m.content); put("hasThoughtSignature", m.hasThoughtSignature)
        }) } }
    }.toString()
}

internal fun recoverConversation(record: ConversationRecord, streams: List<RecoveredStream>, compiler: PromptCompiler): ConversationRecord {
    val byVariant = streams.associateBy { it.progress.variantId }
    val turns = record.turns.map { turn -> turn.copy(variants = turn.variants.map { variant ->
        val saved = byVariant[variant.id]
        if (saved == null) {
            if (variant.status == PersistedMessageStatus.STREAMING) variant.copy(status = PersistedMessageStatus.INTERRUPTED) else variant
        } else {
            val context = Json.decodeFromString<StreamContext>(saved.context)
            check(context.projectionVersion == 1) { "旧生成的投影版本不可用，原始事件和已保存正文仍保留" }
            val raw = StringBuilder()
            val reasoning = mutableListOf<Pair<StringBuilder, String?>>()
            var confirmation: String? = null
            var input = variant.inputTokens; var output = variant.outputTokens
            var total = variant.totalTokens; var cached = variant.cachedTokens; var reasoningTokens = variant.reasoningTokens
            var finishReason = variant.finishReason
            var expected = 1L
            saved.chunks.forEach { event ->
                check(event.seq == expected++) { "已保存生成事件不连续，停止恢复" }
                when (event.kind) {
                    "text" -> raw.append(event.payload)
                    "reasoning-start" -> if (reasoning.lastOrNull()?.let { it.first.isEmpty() && it.second == null } != true) reasoning += StringBuilder() to null
                    "reasoning" -> { if (reasoning.isEmpty()) reasoning += StringBuilder() to null; reasoning.last().first.append(event.payload) }
                    "signature" -> {
                        if (reasoning.isEmpty() || reasoning.last().second != null) reasoning += StringBuilder() to null
                        reasoning[reasoning.lastIndex] = reasoning.last().first to event.payload
                    }
                    "state" -> confirmation = event.payload
                    "finish" -> finishReason = Json.parseToJsonElement(event.payload).jsonPrimitive.contentOrNull
                    "usage" -> Json.parseToJsonElement(event.payload).jsonObject.let {
                        input = it["input"]?.jsonPrimitive?.longOrNull ?: input
                        output = it["output"]?.jsonPrimitive?.longOrNull ?: output
                        total = it["total"]?.jsonPrimitive?.longOrNull ?: total
                        cached = it["cached"]?.jsonPrimitive?.longOrNull ?: cached
                        reasoningTokens = it["reasoning"]?.jsonPrimitive?.longOrNull ?: reasoningTokens
                    }
                }
            }
            check(saved.progress.persistedThrough == expected - 1)
            val before = variant.projectionRuntimeStateBefore ?: record.runtimeState
            val narrative = NativeAdaptationRuntime().projectAssistantMessage(record.character.nativeAdaptation, raw.toString(),
                stateConfirmedSeparately = confirmation != null, streaming = false).narrativeText
            val projected = compiler.projectAssistantOutput(rawText = narrative, rawReasoning = reasoning.map { it.first.toString() },
                character = record.character, persona = record.persona, preset = context.preset, runtimeState = before,
                history = record.copy(turns = record.turns.takeWhile { it.id != turn.id }).promptMessages(),
                conversationId = record.id, generationId = context.generationId, modelId = context.modelId,
                evaluationInstant = Instant.parse(context.evaluationInstant), evaluationZoneId = ZoneId.of(context.evaluationZone))
            variant.copy(status = PersistedMessageStatus.INTERRUPTED, inputTokens = input, outputTokens = output,
                totalTokens = total, cachedTokens = cached, reasoningTokens = reasoningTokens, finishReason = finishReason,
                message = variant.message.copy(content = projected.storageText, sourceText = raw.toString(), stateConfirmation = confirmation,
                    reasoning = projected.storageReasoning.mapIndexed { index, value -> ReasoningBlock(value, reasoning.getOrNull(index)?.second) }),
                runtimeStateAfter = record.runtimeState)
        }
    }) }
    return NativeOperations.recover(record.copy(turns = turns))
}
