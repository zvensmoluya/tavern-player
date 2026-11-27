package io.github.zvensmoluya.tavernplayer.conversation

private val fenceLine = Regex("(?m)^ {0,3}(`{3,}|~{3,})")
private val documentStart = Regex("""(?is)^\s*(?:<!--.*?-->\s*)*(?:<!doctype\s+html\b|<html\b|<head\b|<body\b)""")

/** Keep fences inserted by captures or macros inside the authored Markdown document. */
internal fun protectDisplayPageFence(template: String, expanded: String): String {
    if (template == expanded) return expanded
    val authored = template.trim()
    val firstLine = authored.substringBefore('\n').trimEnd()
    val delimiter = firstLine.takeWhile { it == '`' || it == '~' }
    if (delimiter.length < 3 || delimiter.any { it != delimiter.first() }) return expanded
    if (firstLine.removePrefix(delimiter).trim().lowercase() !in setOf("", "html", "text")) return expanded
    if (authored.substringAfterLast('\n').trim() != delimiter) return expanded
    val body = authored.substringAfter('\n').substringBeforeLast('\n')
    if (!documentStart.containsMatchIn(body)) return expanded
    // A template containing multiple independent pages already owns its boundaries.
    fun longestFence(text: String): Int = fenceLine.findAll(text)
        .map { it.groupValues[1] }.filter { it.first() == delimiter.first() }.maxOfOrNull { it.length } ?: 0
    if (longestFence(body) >= delimiter.length) return expanded

    val opening = expanded.indexOf(delimiter)
    val closing = expanded.lastIndexOf(delimiter)
    if (opening < 0 || closing <= opening + delimiter.length) return expanded
    val longest = longestFence(expanded.substring(opening + delimiter.length, closing))
    if (longest < delimiter.length) return expanded
    val wider = delimiter.first().toString().repeat(longest + 1)
    return expanded.replaceRange(closing, closing + delimiter.length, wider)
        .replaceRange(opening, opening + delimiter.length, wider)
}
