package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EjsPromptIntegrationTest {
    private val source = "{{user}} BEFORE <% if (getvar('stat_data.score') > 0) { %>YES<% } else { %>NO<% } %>"
    private val hash = "a".repeat(64)
    private fun input(template: String = source, budget: Int = 1000): NormalGenerationInput {
        val entry = WorldBookEntryDefinition("template", content = template, constant = true)
        val adaptation = NativeAdaptation(sourceSha256 = hash, ejsTemplates = listOf(
            NativeWorldBookReference("book", entry.id, NativeWorldBookTextSelectionValidator.sha256(template))))
        return NormalGenerationInput(
            character = CharacterAsset("card", sourceSha256 = hash, name = "Guide", description = "A guide.", firstMessage = "Go.",
                nativeAdaptation = adaptation, worldBooks = listOf(WorldBookDefinition("book", tokenBudget = budget, entries = listOf(entry)))).snapshot(),
            persona = Persona("p", "Traveler"),
            history = listOf(ConversationMessage("u", MessageRole.USER, "RAW_TEXT", "Traveler")),
            preset = BuiltInPresets.default,
            runtimeState = ConversationRuntimeState(mvuState = MvuStateSnapshot(hash, hash,
                buildJsonObject { putJsonObject("stat_data") { put("score", 2) } })),
            modelContextTokens = 32768, modelOutputTokens = 512, generationId = "ejs-test",
        )
    }

    @Test fun `regex and macros precede EJS and returned data is never interpreted again`() {
        val base = input().let { it.copy(character = it.character.copy(regexScripts = listOf(
            RegexDefinition("world", "World", findRegex = "BEFORE", replaceString = "AFTER", placements = setOf(RegexPlacement.WORLD_INFO), promptOnly = true),
            RegexDefinition("history", "History", findRegex = "RAW_TEXT", replaceString = "PROJECTED_TEXT", placements = setOf(RegexPlacement.USER_INPUT), promptOnly = true),
        ))) }
        val literal = "RESULT {{setvar::evil::yes}} <% throw new Error('data') %>"
        val result = PromptCompiler().compile(base.copy(ejsRenderer = { request ->
            assertTrue(request.template.startsWith("Traveler AFTER"))
            assertEquals("PROJECTED_TEXT", request.history.single().content)
            assertEquals("user", request.history.single().role)
            assertEquals(base.runtimeState.mvuState!!.data, request.variables)
            literal
        })) as CompilationResult.Success
        assertTrue(result.plan.messages.any { literal in it.content })
        assertFalse(result.plan.runtimeState.localVariables.containsKey("evil"))
        assertEquals(base.runtimeState.mvuState, result.plan.runtimeState.mvuState)
        assertEquals(source, base.character.worldBooks.single().entries.single().content)
    }

    @Test fun `only enabled and triggered entries execute and source checks cannot be bypassed`() {
        val base = input()
        val book = base.character.worldBooks.single()
        val inactive = listOf(
            base.copy(worldBookState = ConversationWorldBookState(activation = WorldBookActivationOverrides(books = mapOf("book" to false)))),
            base.copy(character = base.character.copy(worldBooks = listOf(book.copy(entries = book.entries.map { it.copy(enabled = false) })))) ,
            base.copy(character = base.character.copy(worldBooks = listOf(book.copy(entries = book.entries.map { it.copy(constant = false, keys = listOf("never-match")) })))),
        )
        inactive.forEach { value ->
            assertTrue(PromptCompiler().compile(value.copy(ejsRenderer = { error("Inactive entry executed") })) is CompilationResult.Success)
        }
        val changed = base.copy(character = base.character.copy(worldBooks = listOf(book.copy(entries = book.entries.map { it.copy(content = source + "changed") })) ))
        assertTrue(PromptCompiler().compile(changed) is CompilationResult.Failure)
        assertTrue(PromptCompiler().compile(base.copy(character = base.character.copy(sourceSha256 = "b".repeat(64)))) is CompilationResult.Failure)
    }

    @Test fun `world budget measures rendered output and does not commit dropped entry macros`() {
        val small = input("<% if (false) { %>" + "discard ".repeat(2000) + "<% } %>small", budget = 50)
        val accepted = PromptCompiler().compile(small.copy(ejsRenderer = { "small" })) as CompilationResult.Success
        assertTrue("template" in accepted.plan.activatedWorldBookEntries)
        val large = input("{{setvar::dropped::yes}}<%= 'expand' %>", budget = 10)
        val dropped = PromptCompiler().compile(large.copy(ejsRenderer = { "expanded ".repeat(2000) })) as CompilationResult.Success
        assertFalse("template" in dropped.plan.activatedWorldBookEntries)
        assertFalse("dropped" in dropped.plan.runtimeState.localVariables)
        assertFalse(dropped.plan.messages.any { "expanded" in it.content })
    }

    @Test fun `macro-generated EJS is rejected before execution`() {
        val base = input().copy(persona = Persona("p", "<% throw new Error('injected') %>"))
        val rejected = PromptCompiler().compile(base.copy(ejsRenderer = { error("Injected template executed") })) as CompilationResult.Failure
        assertTrue(rejected.diagnostics.any { it.code == "EJS_CODE_TRANSFORMED" })
    }

    @Test fun `identical entry ids in different books keep their own preparation behavior`() {
        val base = input("<%= 'name' %>")
        val staticEntry = base.character.worldBooks.single().entries.single().copy(content = "{{user}}")
        val result = PromptCompiler().compile(base.copy(character = base.character.copy(worldBooks =
            base.character.worldBooks + WorldBookDefinition("other", entries = listOf(staticEntry))),
            ejsRenderer = { "{{user}}" })) as CompilationResult.Success
        val world = result.plan.messages.filter { "template" in it.origin.sourceIds }.joinToString("\n") { it.content }
        assertTrue(world.contains("{{user}}"))
        assertTrue(world.contains("Traveler"))
    }

    @Test fun `group losers do not execute templates or seed recursion and rendered output stays literal`() {
        val base = input("<%= 'rendered-key' %>")
        val original = base.character.worldBooks.single().entries.single()
        val loser = original.copy(group = "route", content = original.content + " loser-key")
        val adaptation = base.character.nativeAdaptation!!.copy(ejsTemplates = listOf(
            NativeWorldBookReference("book", loser.id, NativeWorldBookTextSelectionValidator.sha256(loser.content))))
        val winner = WorldBookEntryDefinition("winner", content = "winner-key", constant = true, group = "route", groupOverride = true)
        val ghost = WorldBookEntryDefinition("ghost", content = "ghost", keys = listOf("loser-key"))
        val result = PromptCompiler().compile(base.copy(character = base.character.copy(nativeAdaptation = adaptation,
            worldBooks = listOf(WorldBookDefinition("book", recursiveScanning = true, entries = listOf(loser, winner, ghost)))),
            ejsRenderer = { error("Losing template must not execute") })) as CompilationResult.Success
        assertEquals(listOf("winner"), result.plan.activatedWorldBookEntries)

        val noOutputRecursion = base.copy(character = base.character.copy(worldBooks = listOf(WorldBookDefinition("book", recursiveScanning = true,
            entries = listOf(original, ghost.copy(keys = listOf("output-only-key")))))))
        var evaluations = 0
        val literal = PromptCompiler().compile(noOutputRecursion.copy(ejsRenderer = { evaluations++; "output-only-key" })) as CompilationResult.Success
        assertEquals(listOf("template"), literal.plan.activatedWorldBookEntries)
        assertEquals(1, evaluations)
    }
}
