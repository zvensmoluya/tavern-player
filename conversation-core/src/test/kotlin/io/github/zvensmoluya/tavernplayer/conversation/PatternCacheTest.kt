package io.github.zvensmoluya.tavernplayer.conversation

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import org.junit.Assert.*
import org.junit.Test

class PatternCacheTest {
    @Test
    fun `cache separates flags and evicts least recently used patterns`() {
        val cache = PatternCache(maxEntries = 2)
        val sensitive = cache.compile("hello")
        val insensitive = cache.compile("hello", Pattern.CASE_INSENSITIVE)
        assertFalse(sensitive.matcher("HELLO").matches())
        assertTrue(insensitive.matcher("HELLO").matches())
        assertSame(sensitive, cache.compile("hello"))
        cache.compile("other")
        assertSame(sensitive, cache.compile("hello"))
        assertNotSame(insensitive, cache.compile("hello", Pattern.CASE_INSENSITIVE))
    }

    @Test
    fun `source budget bounds retained patterns without rejecting large rules`() {
        val cache = PatternCache(maxEntries = 10, maxSourceChars = 5)
        val first = cache.compile("abc")
        cache.compile("def")
        assertNotSame(first, cache.compile("abc"))
        val large = cache.compile("abcdef")
        assertTrue(large.matcher("abcdef").matches())
        assertNotSame(large, cache.compile("abcdef"))
        assertThrows(PatternSyntaxException::class.java) { cache.compile("[") }
    }
}
