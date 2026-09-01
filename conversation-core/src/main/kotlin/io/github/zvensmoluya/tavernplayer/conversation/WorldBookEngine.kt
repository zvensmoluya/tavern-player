package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.ContentRole
import io.github.zvensmoluya.tavernplayer.content.RegexPlacement
import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookEntryDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookPosition
import io.github.zvensmoluya.tavernplayer.content.WorldBookSecondaryLogic
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

data class WorldBookInjection(
    val position: WorldBookPosition,
    val depth: Int,
    val role: MessageRole,
    val outletName: String = "",
    val content: String,
    val entryIds: List<String>,
)

data class WorldBookActivationResult(
    val injections: List<WorldBookInjection>,
    val activatedEntryIds: List<String>,
    val runtimeState: Map<String, WorldBookEntryRuntimeState>,
    val diagnostics: List<CompilationDiagnostic>,
    val trace: List<CompilationTraceEntry>,
    val usedBudgetTokens: Int,
    val budgetTokens: Int,
)

class WorldBookEngine(
    private val macroEngine: MacroEngine = MacroEngine(),
    private val regexEngine: CharacterRegexEngine = CharacterRegexEngine(macroEngine),
    private val tokenAccounting: DefaultTokenAccounting = DefaultTokenAccounting(),
) {
    fun activate(
        books: List<WorldBookDefinition>,
        characterText: String,
        projectedHistory: List<ConversationMessage>,
        regexRules: List<io.github.zvensmoluya.tavernplayer.content.RegexDefinition>,
        macroContext: MacroContext,
        transaction: MacroTransaction,
        previousState: Map<String, WorldBookEntryRuntimeState>,
        turnIndex: Int,
        inputBudgetTokens: Int,
    ): WorldBookActivationResult {
        if (books.isEmpty()) {
            return WorldBookActivationResult(emptyList(), emptyList(), previousState, emptyList(), emptyList(), 0, 0)
        }
        val diagnostics = mutableListOf<CompilationDiagnostic>()
        val trace = mutableListOf<CompilationTraceEntry>()
        val states = previousState.mapValues { (_, state) -> state.advance() }.toMutableMap()
        val globalBudget = (inputBudgetTokens * DEFAULT_BUDGET_PERCENT / 100).coerceAtLeast(0)
        var remainingGlobal = globalBudget
        val activated = mutableListOf<WorldBookEntryDefinition>()

        books.forEach { book ->
            if (remainingGlobal <= 0) return@forEach
            val candidates = activateBook(
                book,
                characterText,
                projectedHistory,
                states,
                turnIndex,
                transaction,
                diagnostics,
                trace,
            )
            val grouped = selectGroups(book.id, candidates, states, transaction, trace)
            val bookBudget = (book.tokenBudget ?: remainingGlobal).coerceAtMost(remainingGlobal).coerceAtLeast(0)
            var remainingBook = bookBudget
            grouped.sortedWith(
                compareByDescending<WorldBookEntryDefinition> { it.priority ?: it.insertionOrder }
                    .thenBy { it.id },
            ).forEach { entry ->
                val cost = estimateTokens(entry.content, macroContext.modelId)
                if (cost <= remainingBook) {
                    activated += entry
                    remainingBook -= cost
                    remainingGlobal -= cost
                    val stateKey = runtimeStateKey(book.id, entry.id)
                    val old = states[stateKey] ?: WorldBookEntryRuntimeState()
                    states[stateKey] = if (old.stickyRemaining > 0) {
                        old.copy(lastActivatedTurn = turnIndex)
                    } else {
                        old.copy(
                            stickyRemaining = if (entry.sticky > 0) entry.sticky + 1 else 0,
                            cooldownRemaining = entry.cooldown + if (entry.sticky > 0 || entry.cooldown == 0) 0 else 1,
                            delayRemaining = 0,
                            delayStartedTurn = null,
                            lastActivatedTurn = turnIndex,
                        )
                    }
                    trace += CompilationTraceEntry(
                        stage = "world-book",
                        sourceIds = listOf(entry.id),
                        decision = "activated cost=$cost remaining=$remainingGlobal",
                    )
                } else {
                    trace += CompilationTraceEntry(
                        stage = "world-book",
                        sourceIds = listOf(entry.id),
                        decision = "dropped by world-book budget cost=$cost remaining=$remainingBook",
                    )
                }
            }
        }

        val evaluated = activated.mapNotNull { entry ->
            val regexed = regexEngine.apply(
                text = entry.content,
                rules = regexRules,
                placement = RegexPlacement.WORLD_INFO,
                projection = RegexProjection.PROMPT,
                depth = entry.depth,
                context = macroContext,
                transaction = transaction,
            )
            diagnostics += regexed.diagnostics
            val expanded = macroEngine.evaluate(regexed.text, macroContext, transaction)
            diagnostics += expanded.diagnostics
            expanded.text.takeIf(String::isNotBlank)?.let { entry to it }
        }
        val injections = evaluated
            .groupBy { (entry, _) -> InjectionKey(entry.position, entry.depth, entry.role, entry.outletName) }
            .map { (key, values) ->
                WorldBookInjection(
                    position = key.position,
                    depth = key.depth,
                    role = key.role.toMessageRole(),
                    outletName = key.outletName,
                    content = values.sortedBy { it.first.insertionOrder }.joinToString("\n") { it.second },
                    entryIds = values.map { it.first.id },
                )
            }

        return WorldBookActivationResult(
            injections = injections,
            activatedEntryIds = activated.map { it.id },
            runtimeState = states,
            diagnostics = diagnostics.distinctBy { it.code to it.sourceId },
            trace = trace,
            usedBudgetTokens = globalBudget - remainingGlobal,
            budgetTokens = globalBudget,
        )
    }

    private fun activateBook(
        book: WorldBookDefinition,
        characterText: String,
        projectedHistory: List<ConversationMessage>,
        states: MutableMap<String, WorldBookEntryRuntimeState>,
        turnIndex: Int,
        transaction: MacroTransaction,
        diagnostics: MutableList<CompilationDiagnostic>,
        trace: MutableList<CompilationTraceEntry>,
    ): BookActivationCandidates {
        val result = linkedMapOf<String, WorldBookEntryDefinition>()
        val scores = mutableMapOf<String, Int>()
        var recursiveScan = ""
        var recursion = 0
        while (true) {
            val newlyActivated = book.entries.filter { entry ->
                if (entry.id in result || !entry.enabled) return@filter false
                val stateKey = runtimeStateKey(book.id, entry.id)
                val state = states[stateKey] ?: WorldBookEntryRuntimeState()
                if (state.stickyRemaining > 0) return@filter true
                if (state.cooldownRemaining > 0) {
                    trace += trace(entry, "cooldown=${state.cooldownRemaining}")
                    return@filter false
                }
                if (entry.delayUntilRecursion && recursion == 0) return@filter false
                if (entry.excludeRecursion && recursion > 0) return@filter false
                val scanDepth = (entry.scanDepth ?: book.scanDepth ?: DEFAULT_SCAN_DEPTH).coerceAtLeast(0)
                val historyText = projectedHistory.takeLast(scanDepth).joinToString("\n") { it.content }
                val scan = listOf(characterText, historyText, recursiveScan)
                    .filter(String::isNotBlank)
                    .joinToString("\n")
                val matched = entry.constant || matches(entry, scan, diagnostics)
                if (!matched) return@filter false
                scores[entry.id] = matchScore(entry, scan, diagnostics)
                if (entry.delay > 0 && state.delayStartedTurn == null) {
                    states[stateKey] = state.copy(delayRemaining = entry.delay, delayStartedTurn = turnIndex)
                    trace += trace(entry, "delay armed=${entry.delay}")
                    return@filter false
                }
                if (state.delayRemaining > 0) {
                    trace += trace(entry, "delay=${state.delayRemaining}")
                    return@filter false
                }
                !entry.useProbability || entry.probability >= 100 || transaction.nextInt(100) < entry.probability
            }
            newlyActivated.forEach { result[it.id] = it }
            if (newlyActivated.isEmpty() || book.recursiveScanning != true) break
            val recursiveContent = newlyActivated.filterNot { it.preventRecursion }.joinToString("\n") { it.content }
            if (recursiveContent.isBlank()) break
            recursiveScan += "\n$recursiveContent"
            recursion++
            if (recursion >= book.entries.size.coerceAtLeast(1)) {
                diagnostics += warning("WORLD_BOOK_RECURSION_LIMIT", "World Book“${book.name}”递归达到条目数量上限", book.id)
                break
            }
        }
        return BookActivationCandidates(result.values.toList(), scores)
    }

    private fun matches(
        entry: WorldBookEntryDefinition,
        scan: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): Boolean {
        if (entry.keys.isEmpty()) return false
        val primary = entry.keys.map { key -> keyMatches(key, scan, entry, diagnostics) }
        if (primary.none { it }) return false
        if (!entry.selective || entry.secondaryKeys.isEmpty()) return true
        val secondary = entry.secondaryKeys.map { key -> keyMatches(key, scan, entry, diagnostics) }
        return when (entry.secondaryLogic) {
            WorldBookSecondaryLogic.AND_ANY -> secondary.any { it }
            WorldBookSecondaryLogic.AND_ALL -> secondary.all { it }
            WorldBookSecondaryLogic.NOT_ANY -> secondary.none { it }
            WorldBookSecondaryLogic.NOT_ALL -> !secondary.all { it }
        }
    }

    private fun matchScore(
        entry: WorldBookEntryDefinition,
        scan: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): Int {
        if (entry.keys.isEmpty()) return 0
        val primary = entry.keys.count { key -> keyMatches(key, scan, entry, diagnostics) }
        if (entry.secondaryKeys.isEmpty()) return primary
        val secondary = entry.secondaryKeys.count { key -> keyMatches(key, scan, entry, diagnostics) }
        return when (entry.secondaryLogic) {
            WorldBookSecondaryLogic.AND_ANY -> primary + secondary
            WorldBookSecondaryLogic.AND_ALL -> if (secondary == entry.secondaryKeys.size) primary + secondary else primary
            WorldBookSecondaryLogic.NOT_ANY,
            WorldBookSecondaryLogic.NOT_ALL,
            -> primary
        }
    }

    private fun keyMatches(
        key: String,
        scan: String,
        entry: WorldBookEntryDefinition,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): Boolean {
        if (key.isEmpty()) return false
        val literal = key.parseJavascriptRegexLiteral()
        if (entry.useRegex || literal != null) {
            return try {
                val unsupported = literal?.flags.orEmpty().filterNot { it in "gimsu" }
                if (unsupported.isNotEmpty()) {
                    diagnostics += warning(
                        "UNSUPPORTED_WORLD_BOOK_REGEX_FLAGS",
                        "World Book key Regex flags“$unsupported”无法等价执行，已跳过",
                        entry.id,
                    )
                    return false
                }
                if (literal?.flags?.toSet()?.size != literal?.flags?.length) {
                    diagnostics += warning(
                        "INVALID_WORLD_BOOK_REGEX_FLAGS",
                        "World Book key Regex flags 含重复值，已跳过",
                        entry.id,
                    )
                    return false
                }
                var flags = 0
                if (literal != null) {
                    if ('i' in literal.flags) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
                    if ('m' in literal.flags) flags = flags or Pattern.MULTILINE
                    if ('s' in literal.flags) flags = flags or Pattern.DOTALL
                    // Do not map JS /u to UNICODE_CHARACTER_CLASS: JS keeps \w and \d ASCII-only.
                } else if (entry.caseSensitive != true) {
                    flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
                }
                matchesPatternWithinBudget(Pattern.compile(literal?.source ?: key, flags), scan, entry, diagnostics)
            } catch (error: PatternSyntaxException) {
                diagnostics += warning("INVALID_WORLD_BOOK_REGEX", "World Book key Regex 无法编译：${error.description}", entry.id)
                false
            }
        }
        val ignoreCase = entry.caseSensitive != true
        if (entry.matchWholeWords == true) {
            val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
            return Pattern.compile("(?<![\\p{L}\\p{N}_])${Pattern.quote(key)}(?![\\p{L}\\p{N}_])", flags)
                .matcher(scan)
                .find()
        }
        return scan.contains(key, ignoreCase = ignoreCase)
    }

    private fun selectGroups(
        bookId: String,
        candidates: BookActivationCandidates,
        states: Map<String, WorldBookEntryRuntimeState>,
        transaction: MacroTransaction,
        trace: MutableList<CompilationTraceEntry>,
    ): List<WorldBookEntryDefinition> {
        val entries = candidates.entries
        val ungrouped = entries.filter { it.group.isBlank() }
        val grouped = linkedMapOf<String, MutableList<WorldBookEntryDefinition>>()
        entries.filter { it.group.isNotBlank() }.forEach { entry ->
            entry.group.split(GROUP_SEPARATOR).map(String::trim).filter(String::isNotEmpty).forEach { group ->
                grouped.getOrPut(group) { mutableListOf() } += entry
            }
        }
        val remaining = entries.toMutableSet()
        grouped.forEach { (groupName, original) ->
            var group = original.filter { it in remaining }
            if (group.size <= 1) return@forEach
            val sticky = group.filter {
                states[runtimeStateKey(bookId, it.id)]?.stickyRemaining?.let { remaining -> remaining > 0 } == true
            }
            if (sticky.isNotEmpty()) {
                group.filterNot { it in sticky }.forEach { loser ->
                    remaining -= loser
                    trace += groupTrace(loser, groupName, "lost to sticky entry")
                }
                return@forEach
            }
            if (group.any(WorldBookEntryDefinition::useGroupScoring)) {
                val maxScore = group.maxOf { candidates.scores[it.id] ?: 0 }
                group.filter { it.useGroupScoring && (candidates.scores[it.id] ?: 0) < maxScore }.forEach { loser ->
                    remaining -= loser
                    trace += groupTrace(loser, groupName, "lost group score=${candidates.scores[loser.id] ?: 0} max=$maxScore")
                }
                group = group.filter { it in remaining }
                if (group.size <= 1) return@forEach
            }
            val overrideWinner = group.filter(WorldBookEntryDefinition::groupOverride)
                .maxByOrNull { it.priority ?: it.insertionOrder }
            val winner = overrideWinner ?: run {
                val total = group.sumOf { it.groupWeight.coerceAtLeast(0) }
                if (total <= 0) group.firstOrNull() else {
                    var ticket = transaction.nextInt(total)
                    group.firstOrNull { entry ->
                        ticket -= entry.groupWeight.coerceAtLeast(0)
                        ticket < 0
                    }
                }
            }
            group.filterNot { it == winner }.forEach { loser ->
                remaining -= loser
                trace += groupTrace(loser, groupName, "lost inclusion group")
            }
            winner?.let { trace += groupTrace(it, groupName, if (overrideWinner != null) "won group override" else "won weighted group") }
        }
        return ungrouped + entries.filter { it.group.isNotBlank() && it in remaining }
    }

    private fun groupTrace(entry: WorldBookEntryDefinition, group: String, decision: String) = CompilationTraceEntry(
        stage = "world-book-group",
        sourceIds = listOf(entry.id),
        decision = "group=$group $decision",
    )

    private fun WorldBookEntryRuntimeState.advance(): WorldBookEntryRuntimeState = when {
        stickyRemaining > 0 -> copy(stickyRemaining = stickyRemaining - 1)
        cooldownRemaining > 0 -> copy(cooldownRemaining = cooldownRemaining - 1)
        delayRemaining > 0 -> copy(delayRemaining = delayRemaining - 1)
        else -> this
    }

    private fun estimateTokens(text: String, modelId: String): Int = tokenAccounting.countText(text, modelId).tokens

    private fun matchesPatternWithinBudget(
        pattern: Pattern,
        scan: String,
        entry: WorldBookEntryDefinition,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): Boolean {
        val future = try {
            REGEX_EXECUTOR.submit<Boolean> { pattern.matcher(InterruptibleCharSequence(scan)).find() }
        } catch (_: Exception) {
            diagnostics += warning("WORLD_BOOK_REGEX_EXECUTOR_SATURATED", "World Book Regex 执行器繁忙，已跳过", entry.id)
            return false
        }
        return try {
            future.get(REGEX_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            diagnostics += warning("WORLD_BOOK_REGEX_TIMEOUT", "World Book key Regex 超时，已跳过", entry.id)
            false
        } catch (error: Exception) {
            diagnostics += warning(
                "WORLD_BOOK_REGEX_EXECUTION_FAILED",
                "World Book key Regex 执行失败：${error.cause?.message ?: error.message}",
                entry.id,
            )
            false
        }
    }

    private fun runtimeStateKey(bookId: String, entryId: String): String = "$bookId:$entryId"

    private fun trace(entry: WorldBookEntryDefinition, decision: String) = CompilationTraceEntry(
        stage = "world-book",
        sourceIds = listOf(entry.id),
        decision = decision,
    )

    private fun warning(code: String, message: String, sourceId: String? = null) = CompilationDiagnostic(
        DiagnosticSeverity.WARNING,
        code,
        message,
        sourceId,
    )

    companion object {
        private const val DEFAULT_SCAN_DEPTH = 2
        private const val DEFAULT_BUDGET_PERCENT = 25
        private const val REGEX_TIMEOUT_MILLIS = 250L
        private val REGEX_THREAD_COUNTER = AtomicInteger()
        private val REGEX_EXECUTOR = ThreadPoolExecutor(
            0,
            2,
            30,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            ThreadFactory { runnable ->
                Thread(runnable, "tavern-world-regex-${REGEX_THREAD_COUNTER.incrementAndGet()}").apply { isDaemon = true }
            },
            ThreadPoolExecutor.AbortPolicy(),
        )
        private val GROUP_SEPARATOR = Regex(",\\s*")
    }

    private data class InjectionKey(
        val position: WorldBookPosition,
        val depth: Int,
        val role: ContentRole,
        val outletName: String,
    )

    private data class BookActivationCandidates(
        val entries: List<WorldBookEntryDefinition>,
        val scores: Map<String, Int>,
    )

}

private data class JavascriptRegexLiteral(val source: String, val flags: String)

private fun String.parseJavascriptRegexLiteral(): JavascriptRegexLiteral? {
    if (!startsWith('/')) return null
    var closing = -1
    for (index in lastIndex downTo 1) {
        if (this[index] != '/') continue
        var escapes = 0
        var cursor = index - 1
        while (cursor >= 0 && this[cursor] == '\\') {
            escapes++
            cursor--
        }
        if (escapes % 2 == 0) {
            closing = index
            break
        }
    }
    return closing.takeIf { it > 0 }?.let { JavascriptRegexLiteral(substring(1, it), substring(it + 1)) }
}
