package io.github.zvensmoluya.tavernplayer.conversation

import kotlinx.serialization.json.*
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/** Read-only projection of the supported stat_data macro; never changes the checkpoint. */
internal object MessageVariableFormatter {
    fun format(stateJson: String, yaml: Boolean, path: String = ""): String {
        val root = withoutPrivateFields(Json.parseToJsonElement(stateJson))
        val value = if (path.isEmpty()) root else select(root, path) ?: return "null"
        if (value is JsonPrimitive && value.isString) return value.content
        if (!yaml) return value.toString()
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
            indicatorIndent = 2
            indentWithIndicator = true
            splitLines = false
            nonPrintableStyle = DumperOptions.NonPrintableStyle.ESCAPE
        }
        // A fresh emitter per call avoids sharing mutable SnakeYAML state between compilations.
        return Yaml(options).dump(toValue(value)).trimEnd()
    }

    // The supported message macro addresses stat_data with dotted object keys and
    // bracketed numeric indices. Exact object keys take precedence over traversal.
    private fun select(root: JsonElement, path: String): JsonElement? {
        if (root is JsonObject && path in root) return root[path]
        val normalized = Regex("\\[(\\d+)]").replace(path) { "." + it.groupValues[1] }
        if ('[' in normalized || ']' in normalized) return null
        return normalized.removePrefix(".").split('.').fold(root as JsonElement?) { value, key -> when (value) {
            is JsonObject -> value[key]
            is JsonArray -> key.toIntOrNull()?.let(value::getOrNull)
            else -> null
        } }
    }

    private fun withoutPrivateFields(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterKeys { !it.startsWith('$') }.mapValues { withoutPrivateFields(it.value) })
        is JsonArray -> JsonArray(value.map(::withoutPrivateFields))
        else -> value
    }

    private fun toValue(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { toValue(it.value) }
        is JsonArray -> value.map(::toValue)
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.boolean
            else -> value.content.toBigIntegerOrNull() ?: value.content.toBigDecimal()
        }
    }
}
