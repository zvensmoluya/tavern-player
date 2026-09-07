package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.json.*

/** UI reads the selected checkpoint; it cannot write state or request a model inference. */
fun interface NativeStateReader {
    operator fun get(key: String): JsonElement?
}

data class PlayerStateReader(val values: Map<String, JsonElement>) : NativeStateReader {
    override fun get(key: String): JsonElement? = values[key]
}

object NativeStatePath {
    fun valid(path: String): Boolean = path.length <= 512 && (path.isEmpty() || path.startsWith('/')) &&
        path.count { it == '/' } <= 32 && !Regex("~(?![01])").containsMatchIn(path)

    fun read(root: JsonElement?, path: String): JsonElement? {
        if (!valid(path)) return null
        if (path.isEmpty()) return root?.takeUnless { it is JsonNull }
        var node = root
        for (encoded in path.drop(1).split('/')) {
            val key = encoded.replace("~1", "/").replace("~0", "~")
            node = when (val current = node) {
                is JsonObject -> current[key]
                is JsonArray -> key.takeIf { it == "0" || Regex("[1-9][0-9]*").matches(it) }
                    ?.toIntOrNull()?.let { current.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return node?.takeUnless { it is JsonNull }
    }

    fun matches(value: JsonElement?, type: ConversationStateValueType): Boolean = when (type) {
        ConversationStateValueType.STRING -> value is JsonPrimitive && value.isString
        ConversationStateValueType.NUMBER -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.isFinite() == true
        ConversationStateValueType.BOOLEAN -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        ConversationStateValueType.RECORD -> value is JsonObject
        ConversationStateValueType.COLLECTION -> value is JsonArray
    }
}

data class NativeCollectionRow(val key: String, val value: JsonElement) {
    fun text(field: NativeCollectionField): String = if (field.entryKey) key else {
        val value = if (field.path != null) NativeStatePath.read(value, field.path) else (value as? JsonObject)?.get(field.key)
        (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: "状态不可用"
    }
}

object NativeCollectionDisplay {
    /** null is an unavailable/malformed source, distinct from a valid empty inventory. */
    fun rows(view: NativeCollectionView, state: NativeStateReader): List<NativeCollectionRow>? = when (view.shape) {
        NativeCollectionShape.ARRAY -> (state[view.stateKey] as? JsonArray)?.mapIndexed { i, value -> NativeCollectionRow(i.toString(), value) }
        NativeCollectionShape.OBJECT -> (state[view.stateKey] as? JsonObject)?.map { (key, value) -> NativeCollectionRow(key, value) }
    }
}
