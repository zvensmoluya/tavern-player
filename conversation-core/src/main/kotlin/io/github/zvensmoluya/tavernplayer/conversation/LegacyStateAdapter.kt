package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.AssistantStateMapping
import io.github.zvensmoluya.tavernplayer.content.ConversationStateDefinition
import io.github.zvensmoluya.tavernplayer.content.ConversationStateValueType
import io.github.zvensmoluya.tavernplayer.content.LegacyStateDialect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

data class LegacyStateDecodeResult(
    val patch: ConversationStatePatch,
    val appliedUpdates: Int,
    val rejection: String? = null,
) {
    val valid: Boolean get() = rejection == null
}

interface LegacyStateAdapter {
    val dialect: LegacyStateDialect

    fun decode(
        sourceText: String,
        mappings: List<AssistantStateMapping>,
        definitions: Map<String, ConversationStateDefinition>,
        maxUpdates: Int,
    ): LegacyStateDecodeResult
}

class UpdateVariableSetV1Adapter : LegacyStateAdapter {
    override val dialect: LegacyStateDialect = LegacyStateDialect.UPDATE_VARIABLE_SET_V1

    override fun decode(
        sourceText: String,
        mappings: List<AssistantStateMapping>,
        definitions: Map<String, ConversationStateDefinition>,
        maxUpdates: Int,
    ): LegacyStateDecodeResult {
        if (sourceText.isEmpty() || maxUpdates <= 0) return rejectedDecode("MISSING_STATE_ENVELOPE")
        val block = sourceText.singleTaggedContent(UPDATE_BLOCK_OPEN, UPDATE_BLOCK_CLOSE)
            ?.takeIf { it.length <= MAX_UPDATE_BLOCK_CHARS }
            ?.withoutLegacyAnalysis()
            ?: return rejectedDecode("INVALID_STATE_ENVELOPE")
        val lines = block.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("//") }.toList()
        if (lines.size > maxUpdates) return rejectedDecode("TOO_MANY_STATE_UPDATES")
        val mappingsBySource = mappings.filter { it.writable }.associateBy(AssistantStateMapping::sourcePath)
        val assignments = linkedMapOf<String, JsonPrimitive>()
        var applied = 0
        for (line in lines) {
            val update = parseUpdateLine(line) ?: return rejectedDecode("INVALID_STATE_UPDATE")
            val mapping = mappingsBySource[update.path] ?: return rejectedDecode("UNDECLARED_STATE_PATH")
            val definition = definitions[mapping.targetStateKey] ?: return rejectedDecode("UNKNOWN_STATE")
            val value = coerceMappedScalar(definition, update.value) ?: return rejectedDecode("STATE_TYPE_MISMATCH")
            assignments[definition.key] = value
            applied += 1
        }
        return LegacyStateDecodeResult(ConversationStatePatch(assignments), applied)
    }

    private fun parseUpdateLine(line: String): DialectUpdate? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(UPDATE_CALL_PREFIX)) return null
        val open = UPDATE_CALL_PREFIX.length
        var quote: Char? = null
        var escaped = false
        var close = -1
        for (index in open until trimmed.length) {
            val char = trimmed[index]
            if (escaped) {
                escaped = false
            } else if (char == '\\' && quote != null) {
                escaped = true
            } else if (quote != null) {
                if (char == quote) quote = null
            } else if (char == '\'' || char == '"') {
                quote = char
            } else if (char == ')') {
                close = index
                break
            }
        }
        if (close < 0 || quote != null) return null
        val suffix = trimmed.substring(close + 1).trim().removePrefix(";").trim()
        if (suffix.isNotEmpty() && !suffix.startsWith("//")) return null
        val arguments = splitScalarArguments(trimmed.substring(open, close)) ?: return null
        if (arguments.size != 3) return null
        val path = parseQuoted(arguments[0]) ?: return null
        if (!STATE_PATH.matches(path)) return null
        if (parseDialectScalar(arguments[1]) == null) return null
        val value = parseDialectScalar(arguments[2]) ?: return null
        return DialectUpdate(path, value)
    }

    private fun splitScalarArguments(source: String): List<String>? {
        val result = mutableListOf<String>()
        var quote: Char? = null
        var escaped = false
        var start = 0
        source.forEachIndexed { index, char ->
            if (escaped) {
                escaped = false
            } else if (char == '\\' && quote != null) {
                escaped = true
            } else if (quote != null) {
                if (char == quote) quote = null
            } else if (char == '\'' || char == '"') {
                quote = char
            } else if (char == ',') {
                result += source.substring(start, index).trim()
                start = index + 1
            } else if (char in "()[]{}") {
                return null
            }
        }
        if (quote != null || escaped) return null
        result += source.substring(start).trim()
        return result
    }

    private fun parseDialectScalar(source: String): JsonPrimitive? {
        if (source.length > MAX_DIALECT_SCALAR_CHARS) return null
        parseQuoted(source)?.let { return JsonPrimitive(it) }
        if (source == "true") return JsonPrimitive(true)
        if (source == "false") return JsonPrimitive(false)
        if (!DIALECT_NUMBER.matches(source)) return null
        return runCatching { Json.parseToJsonElement(source) as? JsonPrimitive }.getOrNull()
    }

    private fun parseQuoted(source: String): String? {
        if (source.length < 2) return null
        val quote = source.first()
        if ((quote != '\'' && quote != '"') || source.last() != quote) return null
        if (quote == '"') {
            return runCatching {
                (Json.parseToJsonElement(source) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            }.getOrNull()
        }
        val result = StringBuilder(source.length - 2)
        var index = 1
        while (index < source.lastIndex) {
            val char = source[index]
            if (char != '\\') {
                result.append(char)
                index += 1
                continue
            }
            if (index + 1 >= source.lastIndex) return null
            when (val escaped = source[index + 1]) {
                '\\', '\'' -> result.append(escaped)
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                else -> return null
            }
            index += 2
        }
        return result.toString()
    }

    private data class DialectUpdate(val path: String, val value: JsonPrimitive)

    private companion object {
        private const val MAX_UPDATE_BLOCK_CHARS = 128 * 1024
        private const val MAX_DIALECT_SCALAR_CHARS = 8_192
        private const val UPDATE_BLOCK_OPEN = "<UpdateVariable>"
        private const val UPDATE_BLOCK_CLOSE = "</UpdateVariable>"
        private const val UPDATE_CALL_PREFIX = "_.set("
        private val STATE_PATH = Regex("[^.\\s'\"(){}\\[\\]\$]+(?:\\.[^.\\s'\"(){}\\[\\]\$]+){0,15}")
        private val DIALECT_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}

/**
 * A deliberately small anti-corruption adapter for the legacy JSON Patch-shaped message block.
 *
 * This is not a JSON Patch runtime. It accepts one complete UpdateVariable/JSONPatch envelope,
 * reads only scalar `replace` operations, and maps exact whitelisted JSON pointers to flat Player
 * state. add/remove/move, arrays, objects, pointer traversal, and expressions are never executed.
 */
class UpdateVariableJsonPatchV1Adapter : LegacyStateAdapter {
    override val dialect: LegacyStateDialect = LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1

    override fun decode(
        sourceText: String,
        mappings: List<AssistantStateMapping>,
        definitions: Map<String, ConversationStateDefinition>,
        maxUpdates: Int,
    ): LegacyStateDecodeResult {
        if (sourceText.isEmpty() || maxUpdates <= 0) return rejectedDecode("MISSING_STATE_ENVELOPE")
        val updateBlock = sourceText.singleTaggedContent(UPDATE_BLOCK_OPEN, UPDATE_BLOCK_CLOSE)
            ?.takeIf { it.length <= MAX_UPDATE_BLOCK_CHARS }
            ?.withoutLegacyAnalysis()
            ?: return rejectedDecode("INVALID_STATE_ENVELOPE")
        val payload = updateBlock.singleTaggedContent(JSON_PATCH_OPEN, JSON_PATCH_CLOSE)
            ?.takeIf { it.length <= MAX_JSON_PATCH_CHARS }
            ?: return rejectedDecode("INVALID_JSON_PATCH_ENVELOPE")
        if (!updateBlock.trim().startsWith(JSON_PATCH_OPEN, ignoreCase = true) ||
            !updateBlock.trim().endsWith(JSON_PATCH_CLOSE, ignoreCase = true)
        ) return rejectedDecode("UNEXPECTED_STATE_CONTENT")
        val operations = runCatching { Json.parseToJsonElement(payload) as? JsonArray }.getOrNull()
            ?: return rejectedDecode("INVALID_JSON_PATCH")
        if (operations.size > minOf(MAX_JSON_PATCH_OPERATIONS, maxUpdates)) return rejectedDecode("TOO_MANY_STATE_UPDATES")
        val mappingsBySource = mappings.filter { it.writable }.associateBy(AssistantStateMapping::sourcePath)
        val updates = operations.map { element ->
            val operation = element as? JsonObject ?: return rejectedDecode("INVALID_STATE_UPDATE")
            if (operation.keys != setOf("op", "path", "value")) return rejectedDecode("INVALID_STATE_UPDATE")
            val op = operation.stringValue("op") ?: return rejectedDecode("INVALID_STATE_OPERATION")
            if (op != "replace") return rejectedDecode("UNSUPPORTED_STATE_OPERATION")
            val path = operation.stringValue("path") ?: return rejectedDecode("INVALID_STATE_PATH")
            val mapping = mappingsBySource[path] ?: return rejectedDecode("UNDECLARED_STATE_PATH")
            val definition = definitions[mapping.targetStateKey] ?: return rejectedDecode("UNKNOWN_STATE")
            val value = operation["value"] as? JsonPrimitive ?: return rejectedDecode("STATE_TYPE_MISMATCH")
            val coerced = coerceMappedScalar(definition, value) ?: return rejectedDecode("STATE_TYPE_MISMATCH")
            mapping.targetStateKey to coerced
        }
        val assignments = linkedMapOf<String, JsonPrimitive>()
        updates.forEach { (key, value) -> assignments[key] = value }
        return LegacyStateDecodeResult(
            patch = ConversationStatePatch(assignments),
            appliedUpdates = updates.size,
        )
    }

    private fun JsonObject.stringValue(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private companion object {
        private const val MAX_UPDATE_BLOCK_CHARS = 128 * 1024
        private const val MAX_JSON_PATCH_CHARS = 64 * 1024
        private const val MAX_JSON_PATCH_OPERATIONS = 256
        private const val UPDATE_BLOCK_OPEN = "<UpdateVariable>"
        private const val UPDATE_BLOCK_CLOSE = "</UpdateVariable>"
        private const val JSON_PATCH_OPEN = "<JSONPatch>"
        private const val JSON_PATCH_CLOSE = "</JSONPatch>"
    }
}

internal fun String.singleTaggedContent(open: String, close: String): String? {
    val start = indexOf(open, ignoreCase = true)
    if (start < 0 || indexOf(open, start + open.length, ignoreCase = true) >= 0) return null
    val contentStart = start + open.length
    val end = indexOf(close, contentStart, ignoreCase = true)
    if (end < 0 || indexOf(close, ignoreCase = true) != end || indexOf(close, end + close.length, ignoreCase = true) >= 0) return null
    return substring(contentStart, end)
}

private fun String.withoutLegacyAnalysis(): String? {
    val open = "<analysis>"
    val close = "</analysis>"
    val start = indexOf(open, ignoreCase = true)
    if (start < 0) return takeUnless { contains(close, ignoreCase = true) }
    singleTaggedContent(open, close) ?: return null
    val end = indexOf(close, start + open.length, ignoreCase = true) + close.length
    return substring(0, start) + substring(end)
}

private fun coerceMappedScalar(
    definition: ConversationStateDefinition,
    value: JsonPrimitive,
): JsonPrimitive? = when (definition.type) {
    ConversationStateValueType.STRING -> value.takeIf(JsonPrimitive::isString)
        ?.takeIf { definition.allowedStrings.isEmpty() || it.content in definition.allowedStrings }
        ?.let { JsonPrimitive(it.content) }
    ConversationStateValueType.NUMBER -> value.takeUnless(JsonPrimitive::isString)
        ?.takeIf { it.booleanOrNull == null }
        ?.doubleOrNull
        ?.takeIf(Double::isFinite)
        ?.let { number -> JsonPrimitive(definition.numberRange?.let { number.coerceIn(it.min, it.max) } ?: number) }
    ConversationStateValueType.BOOLEAN -> value.takeUnless(JsonPrimitive::isString)?.booleanOrNull?.let(::JsonPrimitive)
    ConversationStateValueType.RECORD,
    ConversationStateValueType.COLLECTION,
    -> null
}

private fun rejectedDecode(code: String) = LegacyStateDecodeResult(ConversationStatePatch(), 0, code)
