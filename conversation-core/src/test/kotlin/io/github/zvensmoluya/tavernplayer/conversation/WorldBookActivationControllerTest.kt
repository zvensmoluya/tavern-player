package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookEntryDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldBookActivationControllerTest {
    private val controller = WorldBookActivationController()
    private val books = listOf(
        WorldBookDefinition(
            id = "book",
            entries = listOf(WorldBookEntryDefinition(id = "entry")),
        ),
    )

    private fun applied(
        state: ConversationWorldBookState,
        vararg intents: WorldBookActivationIntent,
    ) = controller.apply(books, state, intents.toList()) as WorldBookActivationMutationResult.Applied

    @Test
    fun `applies a validated batch to the conversation world book state`() {
        val result = applied(
            ConversationWorldBookState(),
            WorldBookActivationIntent.SetBookEnabled("book", false),
            WorldBookActivationIntent.SetEntryEnabled("book", "entry", true),
        )

        assertEquals(false, result.state.activation.books["book"])
        assertEquals(true, result.state.activation.entries["book"]?.get("entry"))
    }

    @Test
    fun `rejects the entire batch before changing state`() {
        val original = ConversationWorldBookState()
        val result = controller.apply(
            books,
            original,
            listOf(
                WorldBookActivationIntent.SetBookEnabled("book", false),
                WorldBookActivationIntent.SetEntryEnabled("book", "missing", true),
            ),
        ) as WorldBookActivationMutationResult.Rejected

        assertEquals(WorldBookActivationRejectionCode.UNKNOWN_WORLD_BOOK_ENTRY, result.code)
        assertEquals(1, result.intentIndex)
        assertTrue(original.activation.books.isEmpty())
    }

    @Test
    fun `an empty batch preserves the exact state`() {
        val original = ConversationWorldBookState(
            activation = WorldBookActivationOverrides(books = mapOf("book" to false)),
            forcedBooks = setOf("other"),
            editedContent = mapOf("entry" to "original"),
        )
        val result = controller.apply(books, original, emptyList()) as WorldBookActivationMutationResult.Applied

        assertSame(original, result.state)
    }

    @Test
    fun `forcing a book enables it and disabling the same book clears the flag afterwards`() {
        val forced = applied(
            ConversationWorldBookState(),
            WorldBookActivationIntent.SetBookForceEnabled("book", true),
        )

        assertEquals(setOf("book"), forced.state.forcedBooks)
        assertEquals(true, forced.state.activation.books["book"])

        val disabled = applied(forced.state, WorldBookActivationIntent.SetBookEnabled("book", false))

        assertEquals(false, disabled.state.activation.books["book"])
        assertTrue(disabled.state.forcedBooks.isEmpty())
    }

    @Test
    fun `disabling a book first and forcing it afterwards leaves it enabled and forced`() {
        val disabled = applied(ConversationWorldBookState(), WorldBookActivationIntent.SetBookEnabled("book", false))
        assertEquals(false, disabled.state.activation.books["book"])

        val forced = applied(disabled.state, WorldBookActivationIntent.SetBookForceEnabled("book", true))

        assertEquals(true, forced.state.activation.books["book"])
        assertEquals(setOf("book"), forced.state.forcedBooks)

        // 取消「必定生效」只结束后者的三态，不改变书级停用状态。
        val released = applied(forced.state, WorldBookActivationIntent.SetBookForceEnabled("book", false))
        assertTrue(released.state.forcedBooks.isEmpty())
        assertEquals(true, released.state.activation.books["book"])
    }

    @Test
    fun `an unknown book is rejected with its domain code`() {
        val result = controller.apply(
            books,
            ConversationWorldBookState(),
            listOf(WorldBookActivationIntent.SetBookEnabled("missing", false)),
        ) as WorldBookActivationMutationResult.Rejected

        assertEquals(WorldBookActivationRejectionCode.UNKNOWN_WORLD_BOOK, result.code)
        assertEquals(0, result.intentIndex)
    }
}
