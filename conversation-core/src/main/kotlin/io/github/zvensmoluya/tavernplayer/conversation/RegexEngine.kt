package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.CharacterRegexDefinition
import io.github.zvensmoluya.tavernplayer.content.RegexPlacement
import io.github.zvensmoluya.tavernplayer.content.RegexSubstitutionMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

enum class RegexProjection {
    STORAGE,
    PROMPT,
    DISPLAY,
}

data class RegexApplicationResult(
    val text: String,
    val diagnostics: List<CompilationDiagnostic>,
    val appliedRuleIds: List<String>,
)

class CharacterRegexEngine(
    private val macroEngine: MacroEngine = MacroEngine(),
) {
    private val disabledByScope = ConcurrentHashMap<String, MutableSet<String>>()

    fun apply(
        text: String,
        rules: List<CharacterRegexDefinition>,
        placement: RegexPlacement,
        projection: RegexProjection,
        depth: Int? = null,
        context: MacroContext,
        transaction: MacroTransaction,
        scopeId: String = context.conversationId,
        isEdit: Boolean = false,
    ): RegexApplicationResult {
        if (text.isEmpty() || rules.isEmpty()) return RegexApplicationResult(text, emptyList(), emptyList())
        if (text.length > MAX_INPUT_CHARS) {
            return RegexApplicationResult(
                text,
                listOf(warning("REGEX_INPUT_LIMIT", "Regex 输入超过 1 MiB，已跳过投影")),
                emptyList(),
            )
        }
        val diagnostics = mutableListOf<CompilationDiagnostic>()
        val applied = mutableListOf<String>()
        var current = text
        rules.forEach { rule ->
            if (!rule.applies(placement, projection, depth, isEdit)) return@forEach
            val disabled = disabledByScope.getOrPut(scopeId) { ConcurrentHashMap.newKeySet() }
            val runtimeKey = rule.runtimeKey()
            if (runtimeKey in disabled) {
                diagnostics += warning("REGEX_RULE_CIRCUIT_OPEN", "Regex“${rule.name}”已在当前会话熔断", rule.id)
                return@forEach
            }
            if (rule.findRegex.length > MAX_PATTERN_CHARS) {
                diagnostics += warning("REGEX_PATTERN_LIMIT", "Regex“${rule.name}”超过 64 KiB，已跳过", rule.id)
                disabled += runtimeKey
                return@forEach
            }
            val ruleTransaction = transaction.fork()
            val future: Future<RuleResult> = try {
                EXECUTOR.submit<RuleResult> {
                    runRule(current, rule, context, ruleTransaction)
                }
            } catch (_: Exception) {
                diagnostics += warning("REGEX_EXECUTOR_SATURATED", "Regex 执行器繁忙，已跳过“${rule.name}”", rule.id)
                return@forEach
            }
            try {
                val result = future.get(RULE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                diagnostics += result.diagnostics
                if (result.valid) {
                    current = result.text
                    transaction.commitFrom(ruleTransaction)
                    applied += rule.id
                } else {
                    disabled += runtimeKey
                }
            } catch (_: TimeoutException) {
                future.cancel(true)
                disabled += runtimeKey
                diagnostics += warning("REGEX_TIMEOUT", "Regex“${rule.name}”超过 ${RULE_TIMEOUT_MILLIS}ms，已在当前会话熔断", rule.id)
            } catch (error: Exception) {
                disabled += runtimeKey
                diagnostics += warning("REGEX_EXECUTION_FAILED", "Regex“${rule.name}”执行失败：${error.cause?.message ?: error.message}", rule.id)
            }
        }
        return RegexApplicationResult(current, diagnostics.distinctBy { it.code to it.sourceId }, applied)
    }

    private fun runRule(
        input: String,
        rule: CharacterRegexDefinition,
        context: MacroContext,
        transaction: MacroTransaction,
    ): RuleResult {
        val diagnostics = mutableListOf<CompilationDiagnostic>()
        val source = when (rule.substitutionMode) {
            RegexSubstitutionMode.NONE -> rule.findRegex
            RegexSubstitutionMode.RAW -> macroEngine.evaluate(rule.findRegex, context, transaction).also {
                diagnostics += it.diagnostics
            }.text
            RegexSubstitutionMode.ESCAPED -> MACRO_PATTERN.replace(rule.findRegex) { match ->
                val expanded = macroEngine.evaluate(match.value, context, transaction)
                diagnostics += expanded.diagnostics
                Pattern.quote(expanded.text)
            }
        }
        val compiled = try {
            compileJavascriptPattern(source, diagnostics, rule.id)
        } catch (error: PatternSyntaxException) {
            diagnostics += warning("INVALID_REGEX_PATTERN", "Regex“${rule.name}”无法编译：${error.description}", rule.id)
            return RuleResult(input, diagnostics, false)
        }
        if (compiled == null) return RuleResult(input, diagnostics, false)
        val matcher = compiled.pattern.matcher(InterruptibleCharSequence(input))
        val output = StringBuffer(input.length)
        while (matcher.find()) {
            val replacement = buildReplacement(rule, matcher, context, transaction, diagnostics)
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement))
            if (output.length > MAX_INPUT_CHARS) {
                diagnostics += warning("REGEX_OUTPUT_LIMIT", "Regex“${rule.name}”输出超过 1 MiB，已跳过该规则", rule.id)
                return RuleResult(input, diagnostics, false)
            }
            if (!compiled.global) break
        }
        matcher.appendTail(output)
        return RuleResult(output.toString(), diagnostics, true)
    }

    private fun buildReplacement(
        rule: CharacterRegexDefinition,
        matcher: Matcher,
        context: MacroContext,
        transaction: MacroTransaction,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): String {
        val template = MATCH_MACRO.replace(rule.replaceString) { "\$0" }
        val withGroups = CAPTURE_REFERENCE.replace(template) { reference ->
            val value = try {
                val number = reference.groupValues[1]
                if (number.isNotEmpty()) matcher.group(number.toInt()) else matcher.group(reference.groupValues[2])
            } catch (_: Exception) {
                null
            }.orEmpty()
            rule.trimStrings.fold(value) { current, trim ->
                val expanded = macroEngine.evaluate(trim, context, transaction)
                diagnostics += expanded.diagnostics
                current.replace(expanded.text, "")
            }
        }
        val expanded = macroEngine.evaluate(withGroups, context, transaction)
        diagnostics += expanded.diagnostics
        return expanded.text
    }

    private fun compileJavascriptPattern(
        value: String,
        diagnostics: MutableList<CompilationDiagnostic>,
        sourceId: String,
    ): CompiledRegex? {
        var pattern = value
        var flagsText = ""
        if (value.startsWith('/')) {
            val closing = value.lastUnescapedSlash()
            if (closing <= 0) {
                diagnostics += warning("INVALID_JS_REGEX_LITERAL", "JS Regex literal 缺少结束 /，已跳过", sourceId)
                return null
            }
            pattern = value.substring(1, closing)
            flagsText = value.substring(closing + 1)
        }
        val unsupported = flagsText.filterNot { it in "gimsu" }
        if (unsupported.isNotEmpty()) {
            diagnostics += warning("UNSUPPORTED_REGEX_FLAGS", "不支持 Regex flags“$unsupported”，已跳过规则", sourceId)
            return null
        }
        if (flagsText.toSet().size != flagsText.length) {
            diagnostics += warning("INVALID_REGEX_FLAGS", "Regex flags 含重复值，已跳过规则", sourceId)
            return null
        }
        var flags = 0
        if ('i' in flagsText) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        if ('m' in flagsText) flags = flags or Pattern.MULTILINE
        if ('s' in flagsText) flags = flags or Pattern.DOTALL
        // Java Pattern is already code-point aware for the supported constructs. JS /u does not
        // make \w or \d Unicode-wide, so UNICODE_CHARACTER_CLASS would be an incompatible widening.
        return CompiledRegex(Pattern.compile(pattern, flags), global = 'g' in flagsText)
    }

    private fun CharacterRegexDefinition.applies(
        placement: RegexPlacement,
        projection: RegexProjection,
        depth: Int?,
        isEdit: Boolean,
    ): Boolean {
        if (disabled || findRegex.isBlank() || placement !in placements) return false
        if (isEdit && !runOnEdit) return false
        if (depth != null) {
            val minimum = minDepth
            val maximum = maxDepth
            if (minimum != null && minimum >= -1 && depth < minimum) return false
            if (maximum != null && maximum >= 0 && depth > maximum) return false
        }
        return when (projection) {
            RegexProjection.DISPLAY -> markdownOnly
            RegexProjection.PROMPT -> promptOnly
            RegexProjection.STORAGE -> !markdownOnly && !promptOnly
        }
    }

    private fun CharacterRegexDefinition.runtimeKey(): String = buildString {
        append(id)
        append('\u0000')
        append(findRegex)
        append('\u0000')
        append(replaceString)
        append('\u0000')
        append(placements.joinToString(",") { it.wireValue.toString() })
    }

    private fun warning(code: String, message: String, sourceId: String? = null) = CompilationDiagnostic(
        DiagnosticSeverity.WARNING,
        code,
        message,
        sourceId,
    )

    private data class RuleResult(
        val text: String,
        val diagnostics: List<CompilationDiagnostic>,
        val valid: Boolean,
    )

    private data class CompiledRegex(val pattern: Pattern, val global: Boolean)

    companion object {
        private const val MAX_INPUT_CHARS = 1024 * 1024
        private const val MAX_PATTERN_CHARS = 64 * 1024
        private const val RULE_TIMEOUT_MILLIS = 250L
        private val THREAD_COUNTER = AtomicInteger()
        private val EXECUTOR = ThreadPoolExecutor(
            0,
            4,
            30,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            ThreadFactory { runnable ->
                Thread(runnable, "tavern-regex-${THREAD_COUNTER.incrementAndGet()}").apply { isDaemon = true }
            },
            ThreadPoolExecutor.AbortPolicy(),
        )
        // Android's ICU regex parser requires literal closing braces to be escaped.
        private val MACRO_PATTERN = Regex("\\{\\{([^{}]+)\\}\\}")
        private val MATCH_MACRO = Regex("\\{\\{match\\}\\}", RegexOption.IGNORE_CASE)
        private val CAPTURE_REFERENCE = Regex("\\$(\\d+)|\\$<([^>]+)>")
    }
}

internal class InterruptibleCharSequence(
    private val delegate: String,
) : CharSequence {
    override val length: Int
        get() = delegate.length

    override fun get(index: Int): Char {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Regex evaluation cancelled")
        return delegate[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        InterruptibleCharSequence(delegate.substring(startIndex, endIndex))

    override fun toString(): String = delegate
}

private fun String.lastUnescapedSlash(): Int {
    for (index in lastIndex downTo 1) {
        if (this[index] != '/') continue
        var escapes = 0
        var cursor = index - 1
        while (cursor >= 0 && this[cursor] == '\\') {
            escapes++
            cursor--
        }
        if (escapes % 2 == 0) return index
    }
    return -1
}
