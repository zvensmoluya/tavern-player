package io.github.zvensmoluya.tavernplayer.content

import org.junit.Assert.*
import org.junit.Test

class NativeMemoryValidatorTest {
    private val entry = WorldBookEntryDefinition("e", content = "概括共同完成的事情。", enabled = false)
    private val book = WorldBookDefinition("b", entries = listOf(entry))
    private val reference = NativeWorldBookReference("b", "e", NativeWorldBookTextSelectionValidator.sha256(entry.content))
    private val definition = NativeMemoryDefinition("journey", "旅行记忆", reference, firstReply = 5, everyReplies = 15)
    private val adaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), memories = listOf(definition))

    @Test fun `disabled source instructions are validated independently of normal activation`() {
        assertTrue(NativeMemoryValidator.validate(adaptation, listOf(book)).isEmpty())
        assertFalse(NativeMemoryValidator.validate(adaptation, null).isEmpty())
        assertFalse(NativeMemoryValidator.validate(adaptation, listOf(book, book)).isEmpty())
        assertFalse(NativeMemoryValidator.validate(adaptation, listOf(book.copy(entries = listOf(entry.copy(content = "修改"))))).isEmpty())
    }

    @Test fun `definitions cannot introduce arbitrary cadence ambiguous references or source code`() {
        listOf(definition.copy(firstReply = 1), definition.copy(firstReply = 16), definition.copy(everyReplies = 65),
            definition.copy(id = "../../other"), definition.copy(title = ""), definition.copy(references = listOf(reference, reference))).forEach {
            assertFalse(NativeMemoryValidator.validate(adaptation.copy(memories = listOf(it)), listOf(book)).isEmpty())
        }
        listOf("<% execute() %>", "x".repeat(32769), " ").forEach { text ->
            val source = entry.copy(content = text)
            val memory = definition.copy(instruction = reference.copy(sourceContentSha256 = NativeWorldBookTextSelectionValidator.sha256(text)))
            assertFalse(NativeMemoryValidator.validate(adaptation.copy(memories = listOf(memory)), listOf(book.copy(entries = listOf(source)))).isEmpty())
        }
        assertFalse(NativeMemoryValidator.validate(adaptation.copy(memories = List(5) { definition.copy(id = "memory-$it") }), listOf(book)).isEmpty())
    }
}
