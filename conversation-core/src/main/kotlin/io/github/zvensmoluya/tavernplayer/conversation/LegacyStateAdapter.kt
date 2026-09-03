package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.AdaptationMessageStateDialect
import io.github.zvensmoluya.tavernplayer.content.AdaptationMessageStateMapping
import io.github.zvensmoluya.tavernplayer.content.AdaptationStateDefinition
import io.github.zvensmoluya.tavernplayer.content.AdaptationStateType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

data class LegacyStateDecodeResult(
    val patch: ConversationStatePatch,
    val appliedUpdates: Int,
)

interface LegacyStateAdapter {
    val dialect: AdaptationMessageStateDialect

    fun decode(
        sourceText: String,
        mappings: List<AdaptationMessageStateMapping>,
        definitions: Map<String, AdaptationStateDefinition>,
        maxUpdates: Int,
    ): LegacyStateDecodeResult
}

class UpdateVariableSetV1Adapter : LegacyStateAdapter {
    override val dialect: AdaptationMessageStateDialect = AdaptationMessageStateDialect.UPDATE_VARIABLE_SET_V1

    override fun decode(
        sourceText: String,
        mappings: List<AdaptationMessageStateMapping>,
        definitions: Map<String, AdaptationStateDefinition>,
        maxUpdates: Int,
    ): LegacyStateDecodeResult {
        if (sourceText.isEmpty() || maxUpdates <= 0) {
            return LegacyStateDecodeResult(ConversationStatePatch(), 0)
        }
        val mappingsBySource = mappings.associateBy(AdaptationMessageStateMapping::sourcePath)
        val assignments = linkedMapOf<String, JsonPrimitive>()
        var applied = 0
        for (update in parseUpdateVariableBlocks(sourceText, maxUpdates)) {
            val mapping = mappingsBySource[update.path] ?: continue
            val definition = definitions[mapping.target] ?: continue
            val value = coerceMessageValue(definition, update.value) ?: continue
            assignments[definition.key] = value
            applied += 1
            if (applied >= maxUpdates) break
        }
        return LegacyStateDecodeResult(ConversationStatePatch(assignments), applied)
    }

    private fun coerceMessageValue(
        definition: AdaptationStateDefinition,
        value: JsonPrimitive,
    ): JsonPrimitive? = when (definition.type) {
        AdaptationStateType.STRING -> value.takeIf(JsonPrimitive::isString)?.let { JsonPrimitive(it.content) }
        AdaptationStateType.NUMBER -> value.takeUnless(JsonPrimitive::isString)
            ?.takeIf { it.booleanOrNull == null }
            ?.doubleOrNull
            ?.takeIf(Double::isFinite)
            ?.let(::JsonPrimitive)
        AdaptationStateType.BOOLEAN -> value.takeUnless(JsonPrimitive::isString)?.booleanOrNull?.let(::JsonPrimitive)
    }

    private fun parseUpdateVariableBlocks(source: String, maxUpdates: Int): List<DialectUpdate> {
        val bounded = source.takeLast(MAX_MESSAGE_SOURCE_CHARS)
        val result = mutableListOf<DialectUpdate>()
        var offset = 0
        while (result.size < maxUpdates) {
            val start = bounded.indexOf(UPDATE_BLOCK_OPEN, offset)
            if (start < 0) break
            val contentStart = start + UPDATE_BLOCK_OPEN.length
            val end = bounded.indexOf(UPDATE_BLOCK_CLOSE, contentStart)
            if (end < 0) break
            bounded.substring(contentStart, end).lineSequence().forEach { line ->
                if (result.size < maxUpdates) parseUpdateLine(line)?.let(result::add)
            }
            offset = end + UPDATE_BLOCK_CLOSE.length
        }
        return result
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
        private const val MAX_MESSAGE_SOURCE_CHARS = 128 * 1024
        private const val MAX_DIALECT_SCALAR_CHARS = 8_192
        private const val UPDATE_BLOCK_OPEN = "<UpdateVariable>"
        private const val UPDATE_BLOCK_CLOSE = "</UpdateVariable>"
        private const val UPDATE_CALL_PREFIX = "_.set("
        private val STATE_PATH = Regex("[^.\\s'\"(){}\\[\\]\$]+(?:\\.[^.\\s'\"(){}\\[\\]\$]+){0,15}")
        private val DIALECT_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}
