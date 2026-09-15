package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.*
import org.junit.Assert.*
import org.junit.Test

class GlobalWorldBookSnapshotTest {
    private val entry = WorldBookEntryDefinition("global:entry", content = "GLOBAL RULE", enabled = false, keys = listOf("never"), probability = 0, delay = 999)
    private val book = WorldBookDefinition("global", tokenBudget = 0, entries = listOf(entry))
    private fun input() = NormalGenerationInput(
        character = CharacterAsset("card", name = "A", worldBooks = listOf(WorldBookDefinition("card-book", entries = listOf(
            WorldBookEntryDefinition("card-entry", content = "CHARACTER RULE", constant = true))))).snapshot(),
        persona = Persona("p", "Player"), history = listOf(ConversationMessage("u", MessageRole.USER, "continue", "Player")),
        preset = BuiltInPresets.default, modelContextTokens = 32768, modelOutputTokens = 512,
    )
    private fun plan(input: NormalGenerationInput) = when (val result = PromptCompiler().compile(input)) {
        is CompilationResult.Success -> result.plan
        is CompilationResult.Failure -> error(result.diagnostics.toString())
    }
    @Test fun `forced global content joins character content without changing the character snapshot`() {
        val original = input()
        val captured = GlobalWorldBookSnapshot(listOf(book), mapOf(book.id to mapOf(entry.id to WorldBookEntryOverride(WorldBookEntryMode.FORCED))))
        val effective = captured.apply(original)
        val plan = plan(effective)
        assertTrue(plan.messages.any { "GLOBAL RULE" in it.content && it.required })
        assertTrue(plan.messages.any { "CHARACTER RULE" in it.content })
        assertEquals(1, original.character.worldBooks.size)
        assertTrue(plan(captured.apply(original.copy(preset = BuiltInPresets.default.copy(name = "Other")))).messages.any { "GLOBAL RULE" in it.content })
        assertFalse(plan(original).messages.any { "GLOBAL RULE" in it.content })
    }
    @Test fun `automatic global timers are isolated by conversation and reused only in that checkpoint`() {
        val timed = book.copy(tokenBudget = null, entries = listOf(entry.copy(enabled = true, constant = true, probability = 100, delay = 0, cooldown = 3)))
        val captured = GlobalWorldBookSnapshot(listOf(timed))
        val first = plan(captured.apply(input().copy(conversationId = "one")))
        assertTrue(first.messages.any { "GLOBAL RULE" in it.content })
        val secondTurn = plan(captured.apply(input().copy(conversationId = "one", runtimeState = first.runtimeState)))
        assertFalse(secondTurn.messages.any { "GLOBAL RULE" in it.content })
        val other = plan(captured.apply(input().copy(conversationId = "two")))
        assertTrue(other.messages.any { "GLOBAL RULE" in it.content })
    }
    @Test fun `global browser templates share rendering and reject edited forced templates`() {
        val template = entry.copy(content = "<%= 'rendered' %>", enabled = true, constant = true, probability = 100, delay = 0)
        val source = book.copy(tokenBudget = null, entries = listOf(template))
        val original = input().let { it.copy(character = it.character.copy(browserProgram = BrowserProgram()), ejsRenderer = { "RENDERED GLOBAL" }) }
        val captured = GlobalWorldBookSnapshot(listOf(source))
        assertTrue(plan(captured.apply(original)).messages.any { "RENDERED GLOBAL" in it.content })
        val changed = captured.copy(overrides = mapOf(book.id to mapOf(entry.id to WorldBookEntryOverride(WorldBookEntryMode.FORCED, "<%= 'edited' %>"))))
        assertTrue(PromptCompiler().compile(changed.apply(original)) is CompilationResult.Failure)
    }

}
