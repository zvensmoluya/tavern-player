package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NativeWorldBookTextSelectionValidatorTest {
    private val content = "<% if (mode) { %>白昼☀<% } else { %>夜晚🌙<% } %>"
    private val books = listOf(WorldBookDefinition("book", entries = listOf(WorldBookEntryDefinition("entry", content = content))))
    private fun case(value: String, text: String): NativeWorldBookTextCase {
        val start = content.indexOf(text)
        return NativeWorldBookTextCase(value, start, start + text.length)
    }
    private val selection = NativeWorldBookTextSelection("book", "entry", "mode",
        NativeWorldBookTextSelectionValidator.sha256(content), listOf(case("day", "白昼☀"), case("night", "夜晚🌙")))
    private val adaptation = NativeAdaptation(sourceSha256 = "a".repeat(64),
        state = listOf(ConversationStateDefinition("mode", type = ConversationStateValueType.STRING,
            initialValue = JsonPrimitive("day"), allowedStrings = listOf("day", "night"))),
        worldBookTextSelections = listOf(selection))

    @Test fun `accepts source bound literal cases including whole surrogate pairs`() {
        val result = NativeAdaptationValidator().validate(adaptation, worldBooks = books)
        assertTrue(result.issues.toString(), result.valid)
    }

    @Test fun `requires exact complete enum and a unique entry selection`() {
        val invalid = listOf(
            adaptation.copy(worldBookTextSelections = listOf(selection.copy(cases = selection.cases.take(1)))),
            adaptation.copy(worldBookTextSelections = listOf(selection.copy(cases = listOf(selection.cases.first(), selection.cases.first())))),
            adaptation.copy(worldBookTextSelections = listOf(selection, selection)),
            adaptation.copy(state = adaptation.state.map { it.copy(allowedStrings = emptyList()) }),
        )
        invalid.forEach { assertFalse(NativeAdaptationValidator().validate(it, worldBooks = books).valid) }
    }

    @Test fun `rejects missing ambiguous or changed sources even with the same card hash`() {
        val invalid = listOf(null, emptyList(), books + books, books.map { it.copy(entries = it.entries + it.entries) },
            books.map { it.copy(entries = it.entries.map { entry -> entry.copy(content = entry.content + "changed") }) })
        invalid.forEach { assertFalse(NativeAdaptationValidator().validate(adaptation, worldBooks = it).valid) }
    }

    @Test fun `rejects out of bounds empty templated and split character ranges`() {
        val original = selection.cases.last()
        val invalid = listOf(
            original.copy(sourceStart = -1),
            original.copy(sourceEndExclusive = Int.MAX_VALUE),
            original.copy(sourceEndExclusive = original.sourceStart),
            original.copy(sourceStart = 0),
            original.copy(sourceEndExclusive = original.sourceEndExclusive - 1),
        )
        invalid.forEach { case ->
            val candidate = adaptation.copy(worldBookTextSelections = listOf(selection.copy(cases = listOf(selection.cases.first(), case))))
            assertFalse(case.toString(), NativeAdaptationValidator().validate(candidate, worldBooks = books).valid)
        }
    }
}
