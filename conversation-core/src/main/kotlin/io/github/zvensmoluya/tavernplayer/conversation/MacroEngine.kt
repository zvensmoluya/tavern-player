package io.github.zvensmoluya.tavernplayer.conversation

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.Random
import kotlin.math.max

data class MacroContext(
    val character: CharacterSnapshot,
    val persona: Persona,
    val history: List<ConversationMessage> = emptyList(),
    val inputText: String = "",
    val modelId: String = "",
    val original: String? = null,
    val conversationId: String = "preview",
    val generationId: String = "preview-0",
    val maxContextTokens: Int = 32_768,
    val maxResponseTokens: Int = 1_024,
    val firstIncludedMessageId: Int? = null,
    val firstDisplayedMessageId: Int? = 0,
    val lastSwipeId: Int = 1,
    val currentSwipeId: Int = 1,
    val allChatLastMessageId: Int? = history.lastIndex.takeIf { it >= 0 },
    val lastGenerationType: String = "normal",
    val outlets: Map<String, String> = emptyMap(),
    val now: Instant = Instant.now(),
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val legacyStateJson: String? = null,
)

data class MacroEvaluation(
    val text: String,
    val diagnostics: List<CompilationDiagnostic>,
)

class MacroTransaction(
    initial: Map<String, MacroValue> = emptyMap(),
    seed: String = "preview",
) {
    private val variables = initial.toMutableMap()
    private var seedHash = stableHash(seed)
    private var randomIndex = 0L

    private constructor(
        initial: Map<String, MacroValue>,
        seedHash: Long,
        randomIndex: Long,
    ) : this(initial, "") {
        this.seedHash = seedHash
        this.randomIndex = randomIndex
    }

    fun snapshot(): Map<String, MacroValue> = variables.toMap()

    internal fun get(name: String): MacroValue? = variables[name]

    internal fun set(name: String, value: MacroValue) {
        if (name.isNotBlank()) variables[name] = value
    }

    internal fun delete(name: String) {
        variables.remove(name)
    }

    internal fun nextInt(bound: Int): Int {
        val mixedSeed = seedHash + RANDOM_GAMMA * randomIndex++
        return Random(mixedSeed).nextInt(bound)
    }

    internal fun fork(): MacroTransaction = MacroTransaction(snapshot(), seedHash, randomIndex)

    internal fun commitFrom(other: MacroTransaction) {
        variables.clear()
        variables.putAll(other.variables)
        randomIndex = other.randomIndex
    }

    private fun stableHash(value: String): Long {
        var result = 1125899906842597L
        value.forEach { result = 31 * result + it.code }
        return result
    }

    companion object {
        private const val RANDOM_GAMMA = -7046029254386353131L
    }
}

class MacroEngine {
    fun evaluate(
        text: String,
        context: MacroContext,
        transaction: MacroTransaction,
    ): MacroEvaluation {
        if (text.isEmpty()) return MacroEvaluation(text, emptyList())
        val diagnostics = mutableListOf<CompilationDiagnostic>()
        val expanded = resolveDocument(text, context, transaction, diagnostics, 0)
        val postProcessed = TRIM_MARKER.replace(expanded, "")
        val bounded = if (postProcessed.length > MAX_OUTPUT_CHARS) {
            diagnostics += warning("MACRO_OUTPUT_LIMIT", "Macro 展开结果超过 1 MiB，已截断")
            postProcessed.take(MAX_OUTPUT_CHARS)
        } else {
            postProcessed
        }
        return MacroEvaluation(bounded, diagnostics.distinctBy { it.code to it.sourceId })
    }

    private fun resolveDocument(
        source: String,
        context: MacroContext,
        transaction: MacroTransaction,
        diagnostics: MutableList<CompilationDiagnostic>,
        depth: Int,
    ): String {
        if (depth >= MAX_DEPTH) {
            diagnostics += warning("MACRO_DEPTH_LIMIT", "Macro 嵌套超过 $MAX_DEPTH 层，剩余内容保持原文")
            return source
        }
        var current = resolveScopedIf(SCOPED_COMMENT.replace(source, ""), context, transaction, diagnostics, depth)
        repeat(MAX_DEPTH - depth) {
            var resolvedAny = false
            val next = buildString {
                var cursor = 0
                var sourceLineOutputStart = 0
                MACRO_PATTERN.findAll(current).forEach { match ->
                    append(current, cursor, match.range.first)
                    if (current.substring(cursor, match.range.first).contains('\n')) {
                        sourceLineOutputStart = lastIndexOf('\n') + 1
                    }
                    cursor = match.range.last + 1
                    val token = match.groupValues[1].trim()
                    val parsed = parseMacroToken(token)
                    if (token.startsWith("/if", true) || token.equals("else", true) ||
                        (parsed.name.equals("if", true) && parsed.arguments.size <= 1)) {
                        append(match.value)
                        return@forEach
                    }
                    val replacement = resolveToken(token, match.value, context, transaction, diagnostics, depth + 1)
                    if (replacement != match.value) resolvedAny = true
                    if (parsed.name.equals("format_message_variable", true) && replacement != match.value) {
                        // Upstream counts the whole expanded prefix on the original source line,
                        // including newlines produced by an earlier formatted macro.
                        val prefixLength = length - sourceLineOutputStart
                        append(replacement.replace("\n", "\n" + " ".repeat(prefixLength)))
                    } else append(replacement)
                }
                append(current, cursor, current.length)
            }
            val scoped = resolveScopedIf(next, context, transaction, diagnostics, depth)
            if (scoped != next) resolvedAny = true
            current = scoped
            if (!resolvedAny) return current
        }
        diagnostics += warning("MACRO_DEPTH_LIMIT", "Macro 展开未在 $MAX_DEPTH 轮内收敛，剩余内容保持原文")
        return current
    }

    private fun resolveScopedIf(
        source: String,
        context: MacroContext,
        transaction: MacroTransaction,
        diagnostics: MutableList<CompilationDiagnostic>,
        depth: Int,
    ): String {
        var current = source
        repeat(MAX_DEPTH - depth) {
            val opening = current.findScopedIfOpening() ?: return current
            if (opening.first > 0) {
                val prefix = current.substring(0, opening.first)
                val resolvedPrefix = resolveDocument(prefix, context, transaction, diagnostics, depth + 1)
                if (resolvedPrefix != prefix) {
                    current = resolvedPrefix + current.substring(opening.first)
                    return@repeat
                }
            }
            var cursor = opening.last + 1
            var nesting = 1
            var elseRange: IntRange? = null
            var closing: IntRange? = null
            while (cursor < current.length) {
                val start = current.indexOf("{{", cursor)
                if (start < 0) break
                val macroRange = current.macroRangeAt(start) ?: break
                val body = current.substring(start + 2, macroRange.last - 1).trim()
                when {
                    body.isScopedIfOpeningBody() -> nesting++
                    body.equals("/if", true) -> {
                        nesting--
                        if (nesting == 0) {
                            closing = macroRange
                            break
                        }
                    }
                    body.equals("else", true) && nesting == 1 && elseRange == null -> {
                        elseRange = macroRange
                    }
                }
                cursor = macroRange.last + 1
            }
            val close = closing
            if (close == null) {
                diagnostics += warning("UNCLOSED_IF_MACRO", "{{if}} 缺少对应的 {{/if}}，已保持原文")
                return current
            }
            val openingBody = current.substring(opening.first + 2, opening.last - 1).trim()
            val conditionRaw = openingBody.removePrefix("if").trim().removePrefix("::").trim()
            val thenStart = opening.last + 1
            val thenEnd = (elseRange?.first ?: close.first)
            val elseStart = elseRange?.last?.plus(1)
            var condition = resolveDocument(conditionRaw, context, transaction, diagnostics, depth + 1)
            var inverted = false
            if (condition.trimStart().startsWith('!')) {
                inverted = true
                condition = condition.trimStart().drop(1).trimStart()
            }
            if (containsGlobalVariableReference(condition)) {
                diagnostics += warning(
                    "UNSUPPORTED_MACRO",
                    "scoped if 引用了已排除的 global variable；条件块保持原文",
                    "if",
                )
                return current
            }
            condition = when {
                condition.startsWith('.') -> transaction.get(condition.drop(1))?.text.orEmpty()
                condition.lowercase(Locale.ROOT) in ZERO_ARGUMENT_CONDITIONS -> resolveDocument(
                    "{{${condition.trim()}}}",
                    context,
                    transaction,
                    diagnostics,
                    depth + 1,
                )
                else -> condition
            }
            val truthy = (!condition.isFalseLike()) xor inverted
            val chosen = if (truthy) {
                current.substring(thenStart, thenEnd)
            } else if (elseStart != null) {
                current.substring(elseStart, close.first)
            } else {
                ""
            }
            val replacement = resolveDocument(chosen, context, transaction, diagnostics, depth + 1).trim()
            current = current.replaceRange(opening.first, close.last + 1, replacement)
        }
        return current
    }

    private fun resolveToken(
        token: String,
        originalToken: String,
        context: MacroContext,
        transaction: MacroTransaction,
        diagnostics: MutableList<CompilationDiagnostic>,
        depth: Int,
    ): String {
        val parsed = parseMacroToken(token)
        val rawName = parsed.name.lowercase(Locale.ROOT)
        val name = MACRO_ALIASES[rawName] ?: rawName
        val legacyArgument = parsed.legacyArgument
        val rawArgs = parsed.arguments
        val args = rawArgs.map { resolveDocument(it, context, transaction, diagnostics, depth + 1) }

        if (name in setOf("get_message_variable", "format_message_variable") && args == listOf("stat_data") && context.legacyStateJson != null) {
            return MessageVariableFormatter.format(context.legacyStateJson, yaml = name == "format_message_variable")
        }
        if (name in BLOCKED_MACROS || name.contains("globalvar")) {
            diagnostics += warning("UNSUPPORTED_MACRO", "Macro {{$name}} 属于已排除能力，保持原文", name)
            return originalToken
        }
        if (name == "if" && args.firstOrNull()?.let(::containsGlobalVariableReference) == true) {
            diagnostics += warning("UNSUPPORTED_MACRO", "inline if 引用了已排除的 global variable；保持原文", "if")
            return originalToken
        }

        return when (name) {
            "char" -> context.character.promptName
            "user" -> context.persona.name
            "charprompt" -> context.character.systemPrompt
            "charinstruction" -> context.character.postHistoryInstructions
            "chardescription", "description" -> context.character.description
            "charpersonality", "personality" -> context.character.personality
            "charscenario", "scenario" -> context.character.scenario
            "persona" -> context.persona.description
            "mesexamplesraw", "mesexamples" -> context.character.rawMessageExamples
            "chardepthprompt" -> context.character.depthPrompt?.content.orEmpty()
            "charcreatornotes", "creatornotes" -> context.character.creatorNotes
            "charfirstmessage", "greeting" -> greeting(context, args.firstOrNull())
            "charversion", "version", "char_version" -> context.character.characterVersion
            "model" -> context.modelId
            "original" -> context.original ?: run {
                diagnostics += warning("ORIGINAL_MACRO_UNAVAILABLE", "{{original}} 只在 Character override 中可用", name)
                originalToken
            }
            "space" -> " ".repeat(args.firstOrNull()?.toIntOrNull()?.coerceIn(0, 10_000) ?: 1)
            "newline" -> "\n".repeat(args.firstOrNull()?.toIntOrNull()?.coerceIn(0, 10_000) ?: 1)
            "noop", "else" -> ""
            "trim" -> args.firstOrNull()?.trim() ?: originalToken
            "//" -> ""
            "reverse" -> args.joinToString("::").reverseCodePoints()
            "input" -> context.inputText
            "maxcontext" -> context.maxContextTokens.toString()
            "maxresponse" -> context.maxResponseTokens.toString()
            "maxprompt" -> max(0, context.maxContextTokens - context.maxResponseTokens).toString()
            "if" -> inlineIf(args)
            "random" -> randomChoice(args, legacyArgument, transaction)
            "pick" -> deterministicChoice(args, legacyArgument, context)
            "roll" -> roll(args.firstOrNull() ?: legacyArgument.orEmpty(), transaction)
            "outlet" -> context.outlets[args.firstOrNull().orEmpty()].orEmpty()
            "setvar" -> setVariable(args, transaction)
            "addvar" -> addVariable(args, transaction)
            "incvar" -> incrementVariable(args.firstOrNull(), 1, transaction)
            "decvar" -> incrementVariable(args.firstOrNull(), -1, transaction)
            "getvar" -> transaction.get(args.firstOrNull().orEmpty())?.text.orEmpty()
            "hasvar", "varexists" -> (transaction.get(args.firstOrNull().orEmpty()) != null).toString()
            "deletevar", "flushvar" -> {
                transaction.delete(args.firstOrNull().orEmpty())
                ""
            }
            "lastmessage" -> context.history.lastOrNull()?.content.orEmpty()
            "lastmessageid" -> context.history.lastIndex.takeIf { it >= 0 }?.toString().orEmpty()
            "lastusermessage" -> context.history.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
            "lastcharmessage" -> context.history.lastOrNull { it.role == MessageRole.ASSISTANT }?.content.orEmpty()
            "firstincludedmessageid" -> context.firstIncludedMessageId?.toString().orEmpty()
            "firstdisplayedmessageid" -> context.firstDisplayedMessageId?.toString().orEmpty()
            "lastswipeid" -> context.lastSwipeId.toString()
            "currentswipeid" -> context.currentSwipeId.toString()
            "allchatrange" -> context.allChatLastMessageId?.let { "0-$it" }.orEmpty()
            "time" -> formatTime(args.firstOrNull(), context)
            "date" -> context.now.atZone(context.zoneId).format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
            "weekday" -> context.now.atZone(context.zoneId).dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
            "isotime" -> context.now.atZone(context.zoneId).format(DateTimeFormatter.ofPattern("HH:mm"))
            "isodate" -> context.now.atZone(context.zoneId).format(DateTimeFormatter.ISO_LOCAL_DATE)
            "datetimeformat" -> formatDateTime(args.firstOrNull(), context)
            "idleduration" -> idleDuration(context)
            "timediff" -> timeDiff(args)
            "lastgenerationtype" -> context.lastGenerationType
            else -> {
                diagnostics += warning("UNSUPPORTED_MACRO", "未支持的 Macro {{$name}} 保持原文", name)
                originalToken
            }
        }
    }

    private fun greeting(context: MacroContext, rawIndex: String?): String {
        val index = rawIndex?.toIntOrNull() ?: 0
        return if (index == 0) context.character.firstMessage else context.character.alternateFirstMessages.getOrNull(index - 1).orEmpty()
    }

    private fun inlineIf(args: List<String>): String {
        if (args.isEmpty()) return ""
        return if (!args[0].isFalseLike()) args.getOrNull(1).orEmpty() else args.getOrNull(2).orEmpty()
    }

    private fun randomChoice(args: List<String>, legacy: String?, transaction: MacroTransaction): String {
        val choices = choices(args, legacy)
        return choices.takeIf(List<String>::isNotEmpty)?.let { it[transaction.nextInt(it.size)] }.orEmpty()
    }

    private fun deterministicChoice(args: List<String>, legacy: String?, context: MacroContext): String {
        val choices = choices(args, legacy)
        if (choices.isEmpty()) return ""
        val hash = (context.conversationId + choices.joinToString("\u0000")).fold(0) { acc, char -> 31 * acc + char.code }
        return choices[(hash and Int.MAX_VALUE) % choices.size]
    }

    private fun choices(args: List<String>, legacy: String?): List<String> = when {
        args.size > 1 -> args
        args.size == 1 && legacy != null -> args.single().splitEscapedCommas()
        else -> args
    }.map(String::trim).filter(String::isNotEmpty)

    private fun roll(formula: String, transaction: MacroTransaction): String {
        val normalizedFormula = formula.trim().let { if (it.all(Char::isDigit) && it.isNotEmpty()) "1d$it" else it }
        val match = DICE.matchEntire(normalizedFormula) ?: return ""
        val count = match.groupValues[1].toIntOrNull()?.coerceIn(1, 100) ?: 1
        val sides = match.groupValues[2].toIntOrNull()?.coerceIn(1, 1_000_000) ?: 20
        val modifier = match.groupValues[3].toIntOrNull() ?: 0
        return ((0 until count).sumOf { transaction.nextInt(sides) + 1 } + modifier).toString()
    }

    private fun setVariable(args: List<String>, transaction: MacroTransaction): String {
        val name = args.getOrNull(0).orEmpty()
        val value = args.getOrNull(1).orEmpty()
        transaction.set(name, value.asMacroValue())
        return ""
    }

    private fun addVariable(args: List<String>, transaction: MacroTransaction): String {
        val name = args.getOrNull(0).orEmpty()
        val value = args.getOrNull(1).orEmpty()
        val old = transaction.get(name)?.text.orEmpty()
        val result = if (old.toDoubleOrNull() != null && value.toDoubleOrNull() != null) {
            normalizeNumber(old.toDouble() + value.toDouble())
        } else {
            old + value
        }
        transaction.set(name, result.asMacroValue())
        return ""
    }

    private fun incrementVariable(name: String?, delta: Int, transaction: MacroTransaction): String {
        val key = name.orEmpty()
        val value = (transaction.get(key)?.text?.toDoubleOrNull() ?: 0.0) + delta
        val normalized = normalizeNumber(value)
        transaction.set(key, normalized.asMacroValue())
        return normalized
    }

    private fun formatTime(offsetSpec: String?, context: MacroContext): String {
        val zone = offsetSpec?.let { UTC_OFFSET.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
            ?.takeIf { it in -18..18 }
            ?.let(ZoneOffset::ofHours)
            ?: context.zoneId
        return context.now.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    private fun formatDateTime(pattern: String?, context: MacroContext): String = try {
        val javaPattern = pattern.orEmpty()
            .replace("YYYY", "yyyy")
            .replace("YY", "yy")
            .replace("DD", "dd")
            .replace("dddd", "EEEE")
            .replace("ddd", "EEE")
            .replace("A", "a")
        context.now.atZone(context.zoneId).format(DateTimeFormatter.ofPattern(javaPattern))
    } catch (_: IllegalArgumentException) {
        ""
    }

    private fun idleDuration(context: MacroContext): String {
        val userMessages = context.history.filter { it.role == MessageRole.USER && it.createdAtEpochMillis > 0 }
        val last = if (context.history.lastOrNull()?.role == MessageRole.USER) {
            userMessages.dropLast(1).lastOrNull()
        } else {
            userMessages.lastOrNull()
        }?.createdAtEpochMillis ?: return "just now"
        val seconds = Duration.between(Instant.ofEpochMilli(last), context.now).seconds.coerceAtLeast(0)
        return when {
            seconds >= 86_400 -> "${seconds / 86_400} days"
            seconds >= 3_600 -> "${seconds / 3_600} hours"
            seconds >= 60 -> "${seconds / 60} minutes"
            else -> "$seconds seconds"
        }
    }

    private fun timeDiff(args: List<String>): String {
        if (args.size < 2) return ""
        return try {
            val left = LocalDateTime.parse(args[0].replace(' ', 'T'))
            val right = LocalDateTime.parse(args[1].replace(' ', 'T'))
            val signedSeconds = Duration.between(right, left).seconds
            val seconds = kotlin.math.abs(signedSeconds)
            val human = when {
                seconds >= 86_400 -> "${seconds / 86_400} days"
                seconds >= 3_600 -> "${seconds / 3_600} hours"
                seconds >= 60 -> "${seconds / 60} minutes"
                else -> "$seconds seconds"
            }
            if (signedSeconds > 0) "in $human" else "$human ago"
        } catch (_: DateTimeParseException) {
            ""
        }
    }

    private fun containsGlobalVariableReference(value: String): Boolean =
        value.trimStart().startsWith('$') || GLOBAL_VARIABLE_REFERENCE.containsMatchIn(value)

    private fun warning(code: String, message: String, sourceId: String? = null) = CompilationDiagnostic(
        DiagnosticSeverity.WARNING,
        code,
        message,
        sourceId,
    )

    companion object {
        private const val MAX_DEPTH = 32
        private const val MAX_OUTPUT_CHARS = 1024 * 1024
        // Android's ICU regex parser treats an unescaped closing brace as a syntax error.
        private val MACRO_PATTERN = Regex("\\{\\{([^{}]+)\\}\\}")
        private val TRIM_MARKER = Regex("(?:\\r?\\n)*\\{\\{trim\\}\\}(?:\\r?\\n)*", RegexOption.IGNORE_CASE)
        private val SCOPED_COMMENT = Regex("(?s)\\{\\{//\\s*\\}\\}.*?\\{\\{///\\s*\\}\\}")
        private val DICE = Regex("(?i)(\\d*)d(\\d+)([+-]\\d+)?")
        private val UTC_OFFSET = Regex("(?i)UTC([+-]\\d+)")
        private val GLOBAL_VARIABLE_REFERENCE = Regex(
            "\\{\\{\\s*(?:get|has|set|add|inc|dec|delete)globalvar\\b",
            RegexOption.IGNORE_CASE,
        )
        private val ZERO_ARGUMENT_CONDITIONS = setOf(
            "char", "user", "description", "chardescription", "personality", "charpersonality",
            "scenario", "charscenario", "creatornotes", "charcreatornotes", "version", "charversion",
            "model", "lastmessage", "lastusermessage", "lastcharmessage",
        )
        private val MACRO_ALIASES = mapOf(
            "maxprompttokens" to "maxprompt",
            "maxcontexttokens" to "maxcontext",
            "maxresponsetokens" to "maxresponse",
            "comment" to "//",
            "idle_duration" to "idleduration",
        )
        private val BLOCKED_MACROS = setOf(
            "banned", "group", "charifnotgroup", "groupnotmuted", "notchar", "ismobile", "hasextension",
            "systemprompt", "get_message_variable", "set_message_variable",
        )
    }
}

private data class ParsedMacroToken(
    val name: String,
    val arguments: List<String>,
    val legacyArgument: String?,
)

private fun parseMacroToken(raw: String): ParsedMacroToken {
    val token = raw.trim()
    val nameEnd = if (token.startsWith("//")) 2 else token.indexOfFirst { it.isWhitespace() || it == ':' }
        .let { if (it < 0) token.length else it }
    val name = token.take(nameEnd)
    val remainder = token.drop(nameEnd)
    if (remainder.isEmpty()) return ParsedMacroToken(name, emptyList(), null)
    val (body, legacy) = when {
        remainder.startsWith("::") -> remainder.drop(2) to false
        remainder.startsWith(':') -> remainder.drop(1).trimStart() to true
        else -> remainder.trimStart() to true
    }
    if (body.isEmpty()) return ParsedMacroToken(name, emptyList(), null)
    val arguments = if (body.contains("::")) body.split("::") else listOf(body)
    return ParsedMacroToken(name, arguments, body.takeIf { legacy && arguments.size == 1 })
}

private fun String.macroRangeAt(start: Int): IntRange? {
    if (start < 0 || !startsWith("{{", start)) return null
    var nesting = 0
    var cursor = start
    while (cursor < length - 1) {
        when {
            startsWith("{{", cursor) -> {
                nesting++
                cursor += 2
            }
            startsWith("}}", cursor) -> {
                nesting--
                cursor += 2
                if (nesting == 0) return start until cursor
            }
            else -> cursor++
        }
    }
    return null
}

private fun String.findScopedIfOpening(): IntRange? {
    var offset = 0
    while (offset < length) {
        val prefix = SCOPED_IF_SEARCH.find(this, offset) ?: return null
        val range = macroRangeAt(prefix.range.first) ?: return null
        val body = substring(range.first + 2, range.last - 1).trim()
        if (body.isScopedIfOpeningBody()) return range
        offset = range.last + 1
    }
    return null
}

private fun String.topLevelArgumentCount(): Int {
    val firstDelimiter = indexOfFirst { it.isWhitespace() || it == ':' }
    if (firstDelimiter < 0) return 0
    var cursor = firstDelimiter
    while (cursor < length && (this[cursor].isWhitespace() || this[cursor] == ':')) cursor++
    if (cursor >= length) return 0
    var count = 1
    var nesting = 0
    while (cursor < length - 1) {
        when {
            startsWith("{{", cursor) -> {
                nesting++
                cursor += 2
            }
            startsWith("}}", cursor) && nesting > 0 -> {
                nesting--
                cursor += 2
            }
            startsWith("::", cursor) && nesting == 0 -> {
                count++
                cursor += 2
            }
            else -> cursor++
        }
    }
    return count
}

private fun String.isIfOpening(): Boolean {
    val lower = lowercase(Locale.ROOT)
    return lower.startsWith("if ") || lower.startsWith("if::")
}

private fun String.isScopedIfOpeningBody(): Boolean = isIfOpening() && topLevelArgumentCount() <= 1

private val SCOPED_IF_SEARCH = Regex("\\{\\{\\s*if(?:\\s+|::)", RegexOption.IGNORE_CASE)

private fun String.isFalseLike(): Boolean = trim().lowercase(Locale.ROOT) in setOf("", "false", "off", "0", "no", "null")

private fun String.splitEscapedCommas(): List<String> {
    val marker = "\u0000COMMA\u0000"
    return replace("\\,", marker).split(',').map { it.replace(marker, ",") }
}

private fun String.asMacroValue() = MacroValue(this, toDoubleOrNull() != null)

private fun String.reverseCodePoints(): String = buildString {
    this@reverseCodePoints.codePoints().toArray().reversedArray().forEach(::appendCodePoint)
}

private fun normalizeNumber(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
