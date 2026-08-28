package io.github.zvensmoluya.modelgateway

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class TokenUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val cachedTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val raw: JsonObject,
)

data class StreamResult(
    val text: String,
    val reasoning: String,
    val signatures: List<String>,
    val usage: TokenUsage?,
    val finishReason: String?,
    val responseId: String?,
    val unknownEventCount: Int,
)

internal fun JsonObject.string(name: String): String? =
    this[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

internal fun JsonObject.long(name: String): Long? = this[name]?.toString()?.toLongOrNull()

internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
