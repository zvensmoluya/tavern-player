package io.github.zvensmoluya.tavernplayer.conversation

import kotlinx.serialization.json.*
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/** Read-only projection of the supported stat_data macro; never changes the checkpoint. */
internal object MessageVariableFormatter {
    fun format(stateJson: String, yaml: Boolean): String {
        val value = withoutPrivateFields(Json.parseToJsonElement(stateJson))
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
