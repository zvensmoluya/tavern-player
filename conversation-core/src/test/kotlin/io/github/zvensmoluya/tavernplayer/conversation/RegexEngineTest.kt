package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.CharacterRegexDefinition
import io.github.zvensmoluya.tavernplayer.content.RegexPlacement
import io.github.zvensmoluya.tavernplayer.content.RegexSubstitutionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexEngineTest {
    private val engine = CharacterRegexEngine()

    @Test
    fun `preset then character order capture trim and macro replacement are preserved`() {
        val rules = listOf(
            rule("preset", "/(hello) (world)/gi", "[$1]-$2", trim = listOf("l")),
            rule("character", "/\\[heo\\]-word/i", "{{char}}"),
        )

        val result = engine.apply(
            "hello world",
            rules,
            RegexPlacement.AI_OUTPUT,
            RegexProjection.STORAGE,
            context = context(),
            transaction = MacroTransaction(),
        )

        assertEquals("Ash", result.text)
        assertEquals(listOf("preset", "character"), result.appliedRuleIds)
    }

    @Test
    fun `storage prompt and display projections execute only their own rules`() {
        val rules = listOf(
            rule("storage", "x", "S"),
            rule("prompt", "x", "P", promptOnly = true),
            rule("display", "x", "D", markdownOnly = true),
        )

        fun apply(projection: RegexProjection) = engine.apply(
            "x",
            rules,
            RegexPlacement.AI_OUTPUT,
            projection,
            context = context(),
            transaction = MacroTransaction(),
        ).text

        assertEquals("S", apply(RegexProjection.STORAGE))
        assertEquals("P", apply(RegexProjection.PROMPT))
        assertEquals("D", apply(RegexProjection.DISPLAY))
    }

    @Test
    fun `global flag controls whether one or all matches are replaced`() {
        val single = engine.apply(
            "x x", listOf(rule("single", "x", "S")), RegexPlacement.AI_OUTPUT, RegexProjection.STORAGE,
            context = context(), transaction = MacroTransaction(),
        )
        val global = engine.apply(
            "x x", listOf(rule("global", "/x/g", "G")), RegexPlacement.AI_OUTPUT, RegexProjection.STORAGE,
            context = context(), transaction = MacroTransaction(),
        )

        assertEquals("S x", single.text)
        assertEquals("G G", global.text)
    }

    @Test
    fun `javascript unicode flag does not widen ascii word classes`() {
        val result = engine.apply(
            "é",
            listOf(rule("unicode-word", "/\\w+/u", "matched")),
            RegexPlacement.AI_OUTPUT,
            RegexProjection.STORAGE,
            context = context(),
            transaction = MacroTransaction(),
        )

        assertEquals("é", result.text)
    }

    @Test
    fun `depth run on edit and placement filters are honored`() {
        val rule = rule("depth", "x", "y").copy(minDepth = 2, maxDepth = 3, runOnEdit = false)

        assertEquals(
            "x",
            engine.apply("x", listOf(rule), RegexPlacement.AI_OUTPUT, RegexProjection.STORAGE, 1, context(), MacroTransaction()).text,
        )
        assertEquals(
            "y",
            engine.apply("x", listOf(rule), RegexPlacement.AI_OUTPUT, RegexProjection.STORAGE, 2, context(), MacroTransaction()).text,
        )
        assertEquals(
            "x",
            engine.apply(
                "x", listOf(rule), RegexPlacement.AI_OUTPUT, RegexProjection.STORAGE, 2, context(), MacroTransaction(), isEdit = true,
            ).text,
        )
    }

    @Test
    fun `invalid Android translation is skipped and circuit opened for the conversation`() {
        val invalid = rule("bad", "/x/v", "y")
        val first = engine.apply(
            "x", listOf(invalid), RegexPlacement.AI_OUTPUT, RegexProjection.STORAGE,
            context = context(), transaction = MacroTransaction(), scopeId = "chat",
        )
        val second = engine.apply(
            "x", listOf(invalid), RegexPlacement.AI_OUTPUT, RegexProjection.STORAGE,
            context = context(), transaction = MacroTransaction(), scopeId = "chat",
        )

        assertEquals("x", first.text)
        assertTrue(first.diagnostics.any { it.code == "UNSUPPORTED_REGEX_FLAGS" })
        assertTrue(second.diagnostics.any { it.code == "REGEX_RULE_CIRCUIT_OPEN" })
    }

    @Test
    fun `find regex can use escaped macro substitution`() {
        val dynamic = rule("dynamic", "{{char}}", "found").copy(substitutionMode = RegexSubstitutionMode.ESCAPED)
        val result = engine.apply(
            "Ash", listOf(dynamic), RegexPlacement.AI_OUTPUT, RegexProjection.STORAGE,
            context = context(), transaction = MacroTransaction(),
        )

        assertEquals("found", result.text)
    }

    @Test
    fun `catastrophic rule times out and opens the conversation circuit`() {
        val catastrophic = rule("slow", "^(a+)+$", "matched")
        val input = "a".repeat(20_000) + "!"

        val first = engine.apply(
            input, listOf(catastrophic), RegexPlacement.AI_OUTPUT, RegexProjection.STORAGE,
            context = context(), transaction = MacroTransaction(), scopeId = "slow-chat",
        )
        val second = engine.apply(
            "a", listOf(catastrophic), RegexPlacement.AI_OUTPUT, RegexProjection.STORAGE,
            context = context(), transaction = MacroTransaction(), scopeId = "slow-chat",
        )

        assertEquals(input, first.text)
        assertTrue(first.diagnostics.any { it.code == "REGEX_TIMEOUT" })
        assertTrue(second.diagnostics.any { it.code == "REGEX_RULE_CIRCUIT_OPEN" })
    }

    private fun rule(
        id: String,
        find: String,
        replace: String,
        trim: List<String> = emptyList(),
        promptOnly: Boolean = false,
        markdownOnly: Boolean = false,
    ) = CharacterRegexDefinition(
        id = id,
        name = id,
        findRegex = find,
        replaceString = replace,
        trimStrings = trim,
        placements = setOf(RegexPlacement.AI_OUTPUT),
        promptOnly = promptOnly,
        markdownOnly = markdownOnly,
    )

    private fun context() = MacroContext(
        character = CharacterAsset(id = "card", name = "Aster", nickname = "Ash").snapshot(),
        persona = Persona("persona", "Traveler"),
        conversationId = "chat",
    )
}
