package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.LegacyStateDialect
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 从精确映射重建原卡读取的 stat_data；它是当前状态的只读投影，不是另一份变量存储。 */
object LegacyStateReadProjection {
    fun project(adaptation: NativeAdaptation?, snapshot: ConversationStateSnapshot): String? {
        val adapter = adaptation?.assistantStateAdapters?.singleOrNull() ?: return null
        var root = JsonObject(emptyMap())
        adapter.mappings.forEach { mapping ->
            val value = snapshot.values[mapping.targetStateKey] ?: return@forEach
            val path = when (adapter.dialect) {
                LegacyStateDialect.UPDATE_VARIABLE_SET_V1 -> mapping.sourcePath.split('.')
                LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1 -> mapping.sourcePath.removePrefix("/").split('/').map {
                    it.replace("~1", "/").replace("~0", "~")
                }
            }
            root = insert(root, path, value) ?: return null
        }
        return root.toString()
    }

    private fun insert(root: JsonObject, path: List<String>, value: JsonElement): JsonObject? {
        if (path.isEmpty() || path.size > 16 || path.first().isBlank()) return null
        val key = path.first()
        if (path.size == 1) {
            if (key in root) return null
            return JsonObject(root + (key to value))
        }
        val existing = root[key]
        if (existing != null && existing !is JsonObject) return null
        val child = insert(existing ?: JsonObject(emptyMap()), path.drop(1), value) ?: return null
        return JsonObject(root + (key to child))
    }
}
