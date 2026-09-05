package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NativeWorldBookTextProjectorTest {
    private val first = "清晨。FIRST_BRANCH_NEEDLE。{{user}}抵达。"
    private val second = "夜晚。SECOND_BRANCH_NEEDLE。{{user}}离开。"
    private val source = "<% if (mode) { %>$first<% } else { %>$second<% } %>"
    private val entry = WorldBookEntryDefinition("entry", content = source, constant = true)
    private val book = WorldBookDefinition("book", recursiveScanning = true, entries = listOf(entry,
        WorldBookEntryDefinition("day-only", keys = listOf("FIRST_BRANCH_NEEDLE"), content = "DAY_RECURSION_RESULT"),
        WorldBookEntryDefinition("night-only", keys = listOf("SECOND_BRANCH_NEEDLE"), content = "NIGHT_RECURSION_RESULT")))
    private val selection = NativeWorldBookTextSelection("book", "entry", "mode", NativeWorldBookTextSelectionValidator.sha256(source),
        listOf("day" to first, "night" to second).map { (value, text) ->
            NativeWorldBookTextCase(value, source.indexOf(text), source.indexOf(text) + text.length)
        })
    private val adaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), state = listOf(
        ConversationStateDefinition("mode", type = ConversationStateValueType.STRING,
            initialValue = JsonPrimitive("day"), allowedStrings = listOf("day", "night"))),
        worldBookTextSelections = listOf(selection))
    private val character = CharacterAsset("card", sourceSha256 = adaptation.sourceSha256, name = "向导",
        description = "带旅人观察沿途风景。", firstMessage = "准备出发。", worldBooks = listOf(book), nativeAdaptation = adaptation)
    private fun state(value: String) = NativeAdaptationRuntime().initialState(adaptation).copy(
        conversationState = ConversationStateSnapshot(mapOf("mode" to JsonPrimitive(value))))
    private fun compile(value: String, character: CharacterAsset = this.character,
        overrides: WorldBookActivationOverrides = WorldBookActivationOverrides(), contextTokens: Int = 32768): CompilationResult = PromptCompiler().compile(
        NormalGenerationInput(character = character.snapshot(), persona = Persona("p", "旅人"),
            history = listOf(ConversationMessage("u", MessageRole.USER, "继续", "旅人")), preset = BuiltInPresets.default,
            runtimeState = state(value).copy(worldBookActivationOverrides = overrides),
            conversationId = "test", generationId = "generation", modelId = "test", modelContextTokens = contextTokens))

    @Test fun `selects only current source before recursion and leaves original snapshot unchanged`() {
        listOf("day", "night", "day").forEach { value ->
            val result = compile(value)
            assertTrue(result.toString(), result is CompilationResult.Success)
            val plan = (result as CompilationResult.Success).plan
            val text = plan.messages.joinToString("\n") { it.content }
            assertFalse(text.contains("<%"))
            assertEquals(value == "day", text.contains("FIRST_BRANCH_NEEDLE"))
            assertEquals(value == "night", text.contains("SECOND_BRANCH_NEEDLE"))
            assertEquals(value == "day", text.contains("DAY_RECURSION_RESULT"))
            assertEquals(value == "night", text.contains("NIGHT_RECURSION_RESULT"))
            assertTrue(text.contains(if (value == "day") "旅人抵达" else "旅人离开"))
            assertEquals(state(value).conversationState, plan.runtimeState.conversationState)
            assertTrue(plan.trace.any { it.stage == "native-world-book-text" && it.sourceIds == listOf("book", "entry", "mode") })
        }
        assertEquals(source, character.worldBooks.single().entries.first().content)
    }

    @Test fun `selection preserves entry metadata and does not enable disabled books or entries`() {
        val selected = NativeWorldBookTextProjector.project(listOf(book), adaptation, adaptation.sourceSha256, state("day").conversationState)
        assertEquals(entry.copy(content = first), selected.books.single().entries.first())
        val disabledEntries = book.entries.map { it.copy(enabled = false) }
        val cases = listOf(
            character.copy(worldBooks = listOf(book.copy(entries = disabledEntries))) to WorldBookActivationOverrides(),
            character to WorldBookActivationOverrides(books = mapOf("book" to false)),
            character to WorldBookActivationOverrides(entries = mapOf("book" to mapOf("entry" to false))),
        )
        cases.forEach { (card, overrides) ->
            val plan = (compile("day", card, overrides) as CompilationResult.Success).plan
            val text = plan.messages.joinToString("\n") { it.content }
            assertFalse(text.contains("FIRST_BRANCH_NEEDLE"))
            assertFalse(text.contains("DAY_RECURSION_RESULT"))
        }
    }

    @Test fun `invalid source or current state fails generation rather than forwarding both branches`() {
        assertTrue(compile("unknown") is CompilationResult.Failure)
        val changed = character.copy(worldBooks = listOf(book.copy(entries = book.entries.map { it.copy(content = "changed") })))
        assertTrue(compile("day", changed) is CompilationResult.Failure)
        assertTrue(compile("day", character.copy(sourceSha256 = "b".repeat(64))) is CompilationResult.Failure)
        val missingState = NativeWorldBookTextProjector.project(listOf(book), adaptation, adaptation.sourceSha256, ConversationStateSnapshot())
        assertEquals("INVALID_TEXT_SELECTION_STATE", missingState.diagnostics.single().code)
    }

    @Test fun `worldbook budget counts selected text rather than discarded template content`() {
        val largeSource = source + "UNSELECTED_SOURCE ".repeat(2000)
        val selected = selection.copy(sourceContentSha256 = NativeWorldBookTextSelectionValidator.sha256(largeSource))
        val limitedBook = book.copy(tokenBudget = 128, entries = listOf(entry.copy(content = largeSource)))
        val card = character.copy(worldBooks = listOf(limitedBook),
            nativeAdaptation = adaptation.copy(worldBookTextSelections = listOf(selected)))
        val result = compile("day", card)
        assertTrue(result is CompilationResult.Success)
        val text = (result as CompilationResult.Success).plan.messages.joinToString("\n") { it.content }
        assertTrue(text.contains("FIRST_BRANCH_NEEDLE"))
        assertFalse(text.contains("UNSELECTED_SOURCE"))
    }

    @Test fun `cards without selections retain their original worldbook behavior`() {
        val projected = NativeWorldBookTextProjector.project(listOf(book), null, character.sourceSha256, ConversationStateSnapshot())
        assertEquals(listOf(book), projected.books)
        assertTrue(projected.diagnostics.isEmpty())
        assertTrue(projected.trace.isEmpty())
    }

    @Test fun `source budget exemption cannot exceed the full request context limit`() {
        val exempt = WorldBookEntryDefinition("large", constant = true, content = "unbounded lore ".repeat(8000),
            extensions = kotlinx.serialization.json.JsonObject(mapOf("ignore_budget" to JsonPrimitive(true))))
        val result = compile("day", character.copy(worldBooks = listOf(book.copy(entries = book.entries + exempt))), contextTokens = 8192)
        when (result) {
            is CompilationResult.Failure -> assertTrue(result.diagnostics.any { it.code == "MANDATORY_CONTEXT_OVERFLOW" })
            is CompilationResult.Success -> {
                val accounting = checkNotNull(result.plan.tokenAccounting)
                assertTrue(accounting.inputTokens + accounting.reservedOutputTokens <= accounting.contextLimit)
                assertTrue(result.plan.trace.any { it.stage == "context-budget" && "world-book" in it.decision })
            }
        }
    }
}
