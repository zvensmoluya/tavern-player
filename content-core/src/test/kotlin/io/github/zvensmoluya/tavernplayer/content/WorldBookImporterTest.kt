package io.github.zvensmoluya.tavernplayer.content

import org.junit.Assert.*
import org.junit.Test

class WorldBookImporterTest {
    @Test fun `standalone ST fields retain supported entry semantics and original data`() {
        val imported = WorldBookImporter().import("""{"custom":"keep","entries":{"7":{"uid":7,"key":["gate"],"keysecondary":["night"],"content":"RULE","disable":true,"order":42,"position":4,"role":1,"depth":3,"selective":true,"selectiveLogic":3,"probability":12,"useProbability":false,"group":"g","groupOverride":true,"groupWeight":9,"scanDepth":5,"matchWholeWords":true,"matchCharacterDescription":true,"sticky":2,"cooldown":3,"delay":4,"ignoreBudget":true,"extensions":{"unknown":1}}}}""".toByteArray(), "sample.json")
        val entry = imported.book.entries.single()
        assertEquals("sample", imported.book.name)
        assertFalse(entry.enabled)
        assertEquals(listOf("gate"), entry.keys)
        assertEquals(listOf("night"), entry.secondaryKeys)
        assertEquals(WorldBookSecondaryLogic.AND_ALL, entry.effectiveSecondaryLogic)
        assertEquals(WorldBookPosition.AT_DEPTH, entry.position)
        assertEquals(ContentRole.USER, entry.role)
        assertEquals(42, entry.insertionOrder)
        assertEquals(5, entry.scanDepth)
        assertEquals(2, entry.sticky)
        assertEquals(3, entry.cooldown)
        assertEquals(4, entry.delay)
        assertTrue(entry.ignoreBudget)
        assertTrue(entry.matchCharacterDescription)
        assertFalse(entry.useProbability)
        assertTrue(imported.source.containsKey("custom"))
        assertTrue(entry.extensions.containsKey("unknown"))
    }
    @Test fun `character book arrays use the same parser`() {
        val book = WorldBookImporter().import("""{"name":"Book","entries":[{"id":2,"keys":["k"],"content":"text","enabled":true,"extensions":{"position":1}}]}""".toByteArray(), "file.json").book
        assertEquals(WorldBookPosition.AFTER_CHARACTER, book.entries.single().position)
    }
    @Test fun `invalid entries and duplicate ids reject without partially importing`() {
        listOf("{}", "{\"entries\":[null]}", """{"entries":{"0":{"uid":1},"1":{"uid":1}}}""").forEach {
            assertThrows(IllegalArgumentException::class.java) { WorldBookImporter().import(it.toByteArray(), "sample.json") }
        }
    }
}
