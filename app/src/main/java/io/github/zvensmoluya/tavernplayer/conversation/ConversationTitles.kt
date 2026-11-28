package io.github.zvensmoluya.tavernplayer.conversation

private val genericReplies = setOf("你好", "您好", "嗨", "哈喽", "嗯", "嗯嗯", "好", "好的", "继续", "继续吧",
    "请继续", "谢谢", "ok", "okay", "hi", "hello", "continue", "yes")
private val sentenceBreak = Regex("(?<=[。！？!?])|(?<=\\.)\\s+|[\\r\\n]+")
private val clauseBreak = Regex("[，,；;]")
private val space = Regex("\\s+")
private val punctuation = Regex("[\\p{P}\\p{Z}\\s]+")
private val decision = Regex("^(?:我)?(?:决定|选择|打算|宁愿)")
private val fencedCode = Regex("(?s)(?:`{3}.*?(?:`{3}|$)|~{3}.*?(?:~{3}|$))")

internal const val TITLE_WINDOW = 6
internal const val TITLE_SOURCE_LENGTH = 2048
internal const val MAX_TITLE_LENGTH = 80

/** Returns excerpts in preference order without inventing words or removing negation. */
internal fun titleExcerpts(source: String): List<String> {
    val plain = sanitizeCardText(fencedCode.replace(source.take(TITLE_SOURCE_LENGTH), "\n"))
    val excerpts = linkedSetOf<String>()
    fun meaningful(text: String): Boolean {
        val key = punctuation.replace(text, "").lowercase()
        return key !in genericReplies && key.any { it.isLetterOrDigit() }
    }
    fun add(raw: String) {
        val text = raw.trim().trim('*', '_', '#', '>', '。', '，', ',', ';', '；').trim()
        if (!meaningful(text)) return
        excerpts += if (text.codePointCount(0, text.length) <= MAX_TITLE_LENGTH) text
            else text.substring(0, text.offsetByCodePoints(0, MAX_TITLE_LENGTH - 1)).trimEnd() + "…"
    }
    for (sentence in plain.split(sentenceBreak)) {
        val line = sentence.trim().replace(space, " ")
        val clauses = line.split(clauseBreak).map { it.trim() }.filter(String::isNotEmpty)
        if (clauses.none(::meaningful)) continue
        clauses.firstOrNull { decision.containsMatchIn(it) }?.let(::add)
        if (meaningful(clauses.first()) && (line.length <= 36 || clauses.size < 2)) add(line)
        clauses.forEach(::add)
        if (excerpts.size >= 3) break
    }
    return excerpts.take(3)
}

internal fun suggestedTitles(opening: List<String>, recent: List<String>): List<String> {
    val first = opening.map(::titleExcerpts)
    val last = recent.map(::titleExcerpts)
    // Reserve room for both ends even when an early message has several usable clauses.
    return (first.mapNotNull { it.firstOrNull() }.take(2) + last.mapNotNull { it.firstOrNull() }.take(3) +
        first.flatten() + last.flatten()).distinct().take(7)
}

internal fun cleanConversationTitle(value: String) = value.trim().replace(space, " ")
