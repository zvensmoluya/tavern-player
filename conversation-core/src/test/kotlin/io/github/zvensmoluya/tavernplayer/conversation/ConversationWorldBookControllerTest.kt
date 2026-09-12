package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.BuiltInPresets
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import io.github.zvensmoluya.tavernplayer.content.NativeWorldBookReference
import io.github.zvensmoluya.tavernplayer.content.NativeWorldBookTextSelectionValidator
import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookEntryDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookPosition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ConversationWorldBookControllerTest {
    private val entry = WorldBookEntryDefinition("entry", comment = "中性设定", content = "KEEP THIS SENTENCE. REMOVE THIS SENTENCE.", constant = true)
    private fun record(entries: List<WorldBookEntryDefinition> = listOf(entry)) = ConversationRecord(
        id = "conversation", character = io.github.zvensmoluya.tavernplayer.content.CharacterAsset("card", sourceSha256 = "a".repeat(64),
            name = "角色 A", worldBooks = listOf(WorldBookDefinition("book", entries = entries))).snapshot(),
        persona = Persona("p", "Traveler"), turns = emptyList(), createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
    )
    private fun input(record: ConversationRecord) = NormalGenerationInput(
        character = record.character, worldBookState = record.worldBookState, persona = record.persona,
        history = listOf(ConversationMessage("user", MessageRole.USER, "Continue.", "Traveler")),
        preset = BuiltInPresets.default, modelContextTokens = 32768, modelOutputTokens = 512,
    )
    private fun plan(record: ConversationRecord): GenerationPlan = when (val result = PromptCompiler().compile(input(record))) {
        is CompilationResult.Success -> result.plan
        is CompilationResult.Failure -> error(result.diagnostics.toString())
    }

    @Test fun `editing and restoring are local overlays and survive serialization`() {
        val original = record()
        val edited = ConversationWorldBookController.setContent(original, "book", "entry", "KEEP THIS SENTENCE.")
        assertEquals(original.character, edited.character)
        val decoded = Json.decodeFromString<ConversationRecord>(Json.encodeToString(edited))
        val messages = plan(decoded).messages.joinToString { it.content }
        assertTrue(messages.contains("KEEP THIS SENTENCE."))
        assertFalse(messages.contains("REMOVE THIS SENTENCE."))
        assertTrue(plan(original).messages.any { "REMOVE THIS SENTENCE." in it.content })
        val forced = ConversationWorldBookController.setMode(decoded, "book", "entry", WorldBookEntryMode.FORCED)
        val restored = ConversationWorldBookController.setContent(forced, "book", "entry", entry.content)
        assertNull(restored.worldBookState.playerOverrides["book"]?.get("entry")?.content)
        assertEquals(WorldBookEntryMode.FORCED, restored.worldBookState.entryMode("book", entry))
        val resetMode = ConversationWorldBookController.setMode(restored, "book", "entry", null)
        assertEquals(original, resetMode)
    }

    @Test fun `same entry id in another book is untouched and disabling releases forced injection`() {
        val initial = record().let { it.copy(character = it.character.copy(worldBooks = it.character.worldBooks + WorldBookDefinition("other", entries = listOf(entry)))) }
        val edited = ConversationWorldBookController.setContent(initial, "book", "entry", "LOCAL ONLY")
        assertEquals(entry.content, edited.worldBookState.entryContent("other", entry))
        val forced = ConversationWorldBookController.setMode(edited, "book", "entry", WorldBookEntryMode.FORCED)
        val disabled = ConversationWorldBookController.setMode(forced, "book", "entry", WorldBookEntryMode.DISABLED)
        assertTrue(disabled.worldBookState.forcedEntries().isEmpty())
        assertFalse(plan(disabled).messages.any { "LOCAL ONLY" in it.content })
        assertTrue(plan(disabled).messages.any { entry.content in it.content })
    }

    @Test fun `restoring player overrides preserves author program writes and activation`() {
        val edited = ConversationWorldBookController.setContent(record(), "book", "entry", "PLAYER TEXT")
        val authorChanged = BrowserWorldBook.setEntryContent(edited, "book", "entry", "AUTHOR UPDATE").let { it.copy(
            worldBookState = it.worldBookState.copy(activation = WorldBookActivationOverrides(entries = mapOf("book" to mapOf("entry" to false))))) }
        assertEquals("PLAYER TEXT", authorChanged.worldBookState.entryContent("book", authorChanged.character.worldBooks.single().entries.single()))
        val restored = ConversationWorldBookController.setContent(authorChanged, "book", "entry", "AUTHOR UPDATE")
        assertEquals(authorChanged.character, restored.character)
        assertEquals(authorChanged.worldBookState.activation, restored.worldBookState.activation)
        assertEquals(authorChanged.worldBookState.editedContent, restored.worldBookState.editedContent)
        assertTrue(restored.worldBookState.playerOverrides.isEmpty())
    }

    @Test fun `forced entry bypasses disabled author defaults timers probability groups and world budget`() {
        val forcedEntry = entry.copy(enabled = false, constant = false, keys = listOf("never"), delay = 999,
            delayUntilRecursion = true, probability = 0, useProbability = true, group = "choice")
        val rival = entry.copy(id = "rival", content = "AUTOMATIC RIVAL", group = "choice", groupOverride = true)
        val source = record(listOf(forcedEntry, rival)).let { it.copy(
            character = it.character.copy(worldBooks = it.character.worldBooks.map { book -> book.copy(tokenBudget = 0) }),
            worldBookState = ConversationWorldBookState(activation = WorldBookActivationOverrides(books = mapOf("book" to false))),
        ) }
        val changed = ConversationWorldBookController.setMode(source, "book", "entry", WorldBookEntryMode.FORCED)
        val compilation = PromptCompiler().compile(input(changed).copy(runtimeState = ConversationRuntimeState(
            worldBookEntries = mapOf("book:entry" to WorldBookEntryRuntimeState(cooldownRemaining = 100))))) as CompilationResult.Success
        assertTrue(compilation.plan.messages.any { it.required && entry.content in it.content })
        assertFalse(compilation.plan.messages.any { "AUTOMATIC RIVAL" in it.content })
        assertEquals(WorldBookInjectionStatus.INCLUDED, compilation.plan.worldBookInjections?.get("book")?.get("entry"))
    }

    @Test fun `manual auto enables only selected content in an author disabled book`() {
        val source = record(listOf(entry, entry.copy(id = "other", content = "OTHER CONTENT"))).copy(
            worldBookState = ConversationWorldBookState(activation = WorldBookActivationOverrides(books = mapOf("book" to false))))
        val changed = ConversationWorldBookController.setMode(source, "book", "entry", WorldBookEntryMode.AUTO)
        assertTrue(plan(changed).messages.any { entry.content in it.content })
        assertFalse(plan(changed).messages.any { "OTHER CONTENT" in it.content })
        assertFalse(plan(ConversationWorldBookController.setMode(changed, "book", "entry", null)).messages.any { entry.content in it.content })
    }

    @Test fun `all original positions still inject when their preset slots are missing`() {
        WorldBookPosition.entries.forEach { position ->
            val forced = ConversationWorldBookController.setMode(record(listOf(entry.copy(position = position, outletName = "unused"))),
                "book", "entry", WorldBookEntryMode.FORCED)
            val base = input(forced)
            val result = PromptCompiler().compile(base.copy(preset = base.preset.copy(
                promptOrder = base.preset.promptOrder.filter { it.identifier in setOf("main", "chatHistory") },
            ))) as CompilationResult.Success
            assertEquals(position.name, 1, result.plan.messages.count { entry.content in it.content })
            assertTrue(position.name, result.plan.messages.single { entry.content in it.content }.required)
            assertEquals(position.name, WorldBookInjectionStatus.INCLUDED, result.plan.worldBookInjections?.get("book")?.get("entry"))
        }
    }

    @Test fun `required text is protected through system squash and provider reclipping`() {
        val forced = ConversationWorldBookController.setMode(record(listOf(entry.copy(content = "large content ".repeat(400)))), "book", "entry", WorldBookEntryMode.FORCED)
        val base = input(forced)
        listOf(false, true).forEach { squash ->
            val result = PromptCompiler().compile(base.copy(maxInputTokens = 40, preset = base.preset.copy(
                controlSettings = base.preset.controlSettings.copy(squashSystemMessages = squash)))) as CompilationResult.Failure
            assertTrue(result.diagnostics.any { it.code == "MANDATORY_CONTEXT_OVERFLOW" && "始终注入" in it.message })
        }
    }

    @Test fun `empty forced content is an explicit failure and ordinary clipped entries are not reported injected`() {
        val empty = ConversationWorldBookController.setMode(ConversationWorldBookController.setContent(record(), "book", "entry", ""),
            "book", "entry", WorldBookEntryMode.FORCED)
        val failure = PromptCompiler().compile(input(empty)) as CompilationResult.Failure
        assertTrue(failure.diagnostics.any { it.code == "FORCED_WORLD_BOOK_EMPTY" })
        val source = record().let { it.copy(character = it.character.copy(worldBooks = it.character.worldBooks.map { book -> book.copy(tokenBudget = 0) })) }
        assertEquals(WorldBookInjectionStatus.NOT_INCLUDED, plan(source).worldBookInjections?.get("book")?.get("entry"))
    }

    @Test fun `forced templates execute once and user edits cannot execute stale template source`() {
        val template = entry.copy(content = "<%= 'setting' %>")
        val original = record(listOf(template)).let { it.copy(character = it.character.copy(nativeAdaptation = NativeAdaptation(
            sourceSha256 = it.character.sourceSha256, ejsTemplates = listOf(NativeWorldBookReference("book", "entry",
                NativeWorldBookTextSelectionValidator.sha256(template.content)))))) }
        val forced = ConversationWorldBookController.setMode(original, "book", "entry", WorldBookEntryMode.FORCED)
        var calls = 0
        val literal = "LITERAL {{setvar::unexpected::yes}}"
        val result = PromptCompiler().compile(input(forced).copy(ejsRenderer = { calls++; literal })) as CompilationResult.Success
        assertEquals(1, calls)
        assertTrue(result.plan.messages.any { literal in it.content })
        assertFalse(result.plan.runtimeState.localVariables.containsKey("unexpected"))
        val stale = ConversationWorldBookController.setContent(forced, "book", "entry", template.content + "changed")
        val failure = PromptCompiler().compile(input(stale).copy(ejsRenderer = { error("stale template executed") })) as CompilationResult.Failure
        assertTrue(failure.diagnostics.any { it.code == "FORCED_WORLD_BOOK_EMPTY" })
        val plain = ConversationWorldBookController.setContent(forced, "book", "entry", "PLAYER TEXT")
        assertTrue(plan(plain).messages.any { "PLAYER TEXT" in it.content })
    }
}
