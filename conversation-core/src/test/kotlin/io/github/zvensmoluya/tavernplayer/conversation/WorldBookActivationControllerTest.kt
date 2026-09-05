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

    @Test
    fun `applies a validated batch to conversation runtime state`() {
        val result = controller.apply(
            books,
            ConversationRuntimeState(),
            listOf(
                WorldBookActivationIntent.SetBookEnabled("book", false),
                WorldBookActivationIntent.SetEntryEnabled("book", "entry", true),
            ),
        ) as WorldBookActivationMutationResult.Applied

        assertEquals(false, result.runtimeState.worldBookActivationOverrides.books["book"])
        assertEquals(true, result.runtimeState.worldBookActivationOverrides.entries["book"]?.get("entry"))
    }

    @Test
    fun `rejects the entire batch before changing state`() {
        val original = ConversationRuntimeState()
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
        assertTrue(original.worldBookActivationOverrides.books.isEmpty())
    }

    @Test
    fun `an empty batch preserves the exact runtime state`() {
        val original = ConversationRuntimeState(generationIndex = 7)
        val result = controller.apply(books, original, emptyList()) as WorldBookActivationMutationResult.Applied

        assertSame(original, result.runtimeState)
    }
}
