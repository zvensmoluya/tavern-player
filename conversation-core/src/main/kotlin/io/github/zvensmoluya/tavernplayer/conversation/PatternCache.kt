package io.github.zvensmoluya.tavernplayer.conversation

import java.util.regex.Pattern

/** Only immutable, already-expanded patterns are shared; every execution creates its own matcher. */
internal class PatternCache(
    private val maxEntries: Int = 256,
    private val maxSourceChars: Int = 512 * 1024,
) {
    private data class Key(val source: String, val flags: Int)
    private val entries = LinkedHashMap<Key, Pattern>(16, 0.75f, true)
    private var sourceChars = 0

    @Synchronized
    fun compile(source: String, flags: Int = 0): Pattern {
        val key = Key(source, flags)
        entries[key]?.let { return it }
        val pattern = Pattern.compile(source, flags)
        if (maxEntries <= 0 || source.length > maxSourceChars) return pattern
        entries[key] = pattern
        sourceChars += source.length
        while (entries.size > maxEntries || sourceChars > maxSourceChars) {
            val iterator = entries.entries.iterator()
            sourceChars -= iterator.next().key.source.length
            iterator.remove()
        }
        return pattern
    }
}

internal val compiledPatterns = PatternCache()
