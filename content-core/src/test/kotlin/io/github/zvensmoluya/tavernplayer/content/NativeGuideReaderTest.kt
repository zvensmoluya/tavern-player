package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NativeGuideReaderTest {
    private val source = "<style>.hidden{}</style><p>地图 🧭</p><p>{{user}} 先领取地图。</p><script>doNotRun()</script>"
    private val rule = RegexDefinition("map-guide", "Map", "[", source, markdownOnly = true)
    private fun range(text: String) = NativeSourceTextRange(source.indexOf(text), source.indexOf(text) + text.length)
    private val guide = NativeGuideView("旅行指南", rule.id, NativeWorldBookTextSelectionValidator.sha256(source),
        listOf(NativeGuideSection("start", "出发", listOf(range("地图 🧭"), range("{{user}} 先领取地图。")))))
    private val adaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), guide = guide)

    @Test fun `reads exact source excerpts after a snapshot round trip without executing regex or macros`() {
        val restored = Json.decodeFromString<NativeAdaptation>(Json.encodeToString(adaptation))
        assertTrue(NativeAdaptationValidator().validate(restored, regexScripts = listOf(rule)).valid)
        val reading = NativeGuideReader.read(restored, listOf(rule), adaptation.sourceSha256)
        assertNull(reading.error)
        assertEquals("地图 🧭\n\n{{user}} 先领取地图。", reading.content!!.sections.single().text)
        assertEquals(source, rule.replaceString)
        assertNull(NativeGuideReader.read(null, emptyList(), "other").content)
    }

    @Test fun `rejects stale ambiguous or non display sources without partial content`() {
        val invalidRules = listOf(emptyList(), listOf(rule, rule), listOf(rule.copy(markdownOnly = false)),
            listOf(rule.copy(promptOnly = true)), listOf(rule.copy(replaceString = source + "changed")))
        invalidRules.forEach { rules ->
            val result = NativeGuideReader.read(adaptation, rules, adaptation.sourceSha256)
            assertNull(result.content)
            assertNotNull(result.error)
            assertFalse(NativeAdaptationValidator().validate(adaptation, regexScripts = rules).valid)
        }
        assertNotNull(NativeGuideReader.read(adaptation, listOf(rule), "b".repeat(64)).error)
    }

    @Test fun `bounds ranges and rejects split characters and active source regions`() {
        val bad = listOf(NativeSourceTextRange(-1, 2), NativeSourceTextRange(0, 0), NativeSourceTextRange(1, Int.MAX_VALUE),
            NativeSourceTextRange(source.indexOf("🧭") + 1, source.indexOf("🧭") + 2), range("<script>doNotRun()</script>"),
            range("<style>.hidden{}</style>"))
        bad.forEach { excerpt ->
            assertFalse(NativeGuideReader.validate(guide.copy(sections = listOf(guide.sections.single().copy(excerpts = listOf(excerpt)))), listOf(rule)).isEmpty())
        }
        assertFalse(NativeGuideReader.validate(guide.copy(sections = guide.sections + guide.sections), listOf(rule)).isEmpty())
        assertFalse(NativeGuideReader.validate(guide.copy(sections = emptyList()), listOf(rule)).isEmpty())
        assertFalse(NativeGuideReader.validate(guide.copy(sourceContentSha256 = "BAD"), listOf(rule)).isEmpty())
        val giant = rule.copy(replaceString = "x".repeat(8192))
        val section = guide.sections.single().copy(excerpts = List(8) { NativeSourceTextRange(0, 8192) })
        assertFalse(NativeGuideReader.validate(guide.copy(sourceContentSha256 = NativeWorldBookTextSelectionValidator.sha256(giant.replaceString), sections = listOf(section)), listOf(giant)).isEmpty())
    }
}
