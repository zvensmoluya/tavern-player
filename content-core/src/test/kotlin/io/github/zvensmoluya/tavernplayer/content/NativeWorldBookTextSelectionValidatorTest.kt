package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NativeWorldBookTextSelectionValidatorTest {
    @Test fun `common context must remain ordered bounded literal and character aligned`() {
        val prefix = "<speaker>向导🌙：\n"
        val suffix = "\n</speaker>"
        val wrapped = prefix + content + suffix
        val selected = selection.copy(sourceContentSha256 = NativeWorldBookTextSelectionValidator.sha256(wrapped),
            cases = selection.cases.map { it.copy(sourceStart = it.sourceStart + prefix.length, sourceEndExclusive = it.sourceEndExclusive + prefix.length) },
            sourcePrefix = NativeSourceTextRange(0, prefix.length),
            sourceSuffix = NativeSourceTextRange(wrapped.length - suffix.length, wrapped.length))
        val sourceBooks = listOf(books.single().copy(entries = listOf(books.single().entries.single().copy(content = wrapped))))
        fun valid(candidate: NativeWorldBookTextSelection) = NativeWorldBookTextSelectionValidator.validate(
            adaptation.copy(worldBookTextSelections = listOf(candidate)), sourceBooks).isEmpty()
        assertTrue(valid(selected))
        listOf(
            selected.copy(sourcePrefix = NativeSourceTextRange(-1, prefix.length)),
            selected.copy(sourcePrefix = NativeSourceTextRange(0, prefix.indexOf("🌙") + 1)),
            selected.copy(sourceSuffix = NativeSourceTextRange(wrapped.length, Int.MAX_VALUE)),
            selected.copy(sourcePrefix = NativeSourceTextRange(0, selected.cases.first().sourceEndExclusive)),
            selected.copy(sourceSuffix = NativeSourceTextRange(0, prefix.length)),
            selected.copy(sourcePrefix = NativeSourceTextRange(0, prefix.length + 3)),
        ).forEach { assertFalse(it.toString(), valid(it)) }
    }

    @Test fun `joined pieces cannot construct EJS markers or bypass total length limit`() {
        listOf("< discarded %payload" to 12, "a".repeat(8192) + "x" to 8192).forEach { (source, start) ->
            val selected = selection.copy(sourceContentSha256 = NativeWorldBookTextSelectionValidator.sha256(source),
                cases = listOf("day", "night").map { NativeWorldBookTextCase(it, start, source.length) },
                sourcePrefix = NativeSourceTextRange(0, if (source.startsWith('<')) 1 else 8192))
            val sourceBooks = listOf(books.single().copy(entries = listOf(books.single().entries.single().copy(content = source))))
            assertFalse(NativeWorldBookTextSelectionValidator.validate(adaptation.copy(worldBookTextSelections = listOf(selected)), sourceBooks).isEmpty())
        }
    }

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
