package io.github.zvensmoluya.modelgateway

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonObject.string(name: String): String? =
    this[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

internal fun JsonObject.long(name: String): Long? = this[name]?.toString()?.toLongOrNull()

internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
