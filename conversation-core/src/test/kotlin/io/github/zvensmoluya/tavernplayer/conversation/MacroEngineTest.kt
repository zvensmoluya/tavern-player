package io.github.zvensmoluya.tavernplayer.conversation

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroEngineTest {
    private val engine = MacroEngine()

    @Test
    fun `nested scoped condition resolves only its selected branch`() {
        val transaction = MacroTransaction(seed = "generation-1")
        val result = engine.evaluate(
            "{{setvar::mood::warm}}{{if::{{getvar::mood}}}}Hello {{char}}{{else}}{{setvar::bad::yes}}No{{/if}}",
            context(),
            transaction,
        )

        assertEquals("Hello Ash", result.text)
        assertEquals("warm", transaction.snapshot()["mood"]?.text)
        assertEquals(null, transaction.snapshot()["bad"])
    }

    @Test
    fun `inline condition is not consumed as a scoped block`() {
        val result = engine.evaluate(
            "{{if::1::warm::cold}}/{{if::0::wrong::right}}",
            context(),
            MacroTransaction(),
        )

        assertEquals("warm/right", result.text)
    }

    @Test
    fun `aliases legacy separators and local mutations match the selected macro set`() {
        val transaction = MacroTransaction(seed = "generation-1")
        val result = engine.evaluate(
            "{{setvar::points::2}}{{addvar::points::3}}{{incvar points}}/" +
                "{{getvar::points}}/{{maxContextTokens}}/{{comment hidden}}/{{roll: 6}}",
            context(),
            transaction,
        )

        val pieces = result.text.split('/')
        assertEquals("6", pieces[0])
        assertEquals("6", pieces[1])
        assertEquals("32768", pieces[2])
        assertEquals("", pieces[3])
        assertTrue(pieces[4].toInt() in 1..6)
    }

    @Test
    fun `unknown and global variable macros remain literal`() {
        val result = engine.evaluate(
            "{{getglobalvar::shared}} {{third_party::x}}",
            context(),
            MacroTransaction(),
        )

        assertEquals("{{getglobalvar::shared}} {{third_party::x}}", result.text)
        assertEquals(2, result.diagnostics.count { it.code == "UNSUPPORTED_MACRO" })
    }

    @Test
    fun `persona name and description remain distinct macro sources`() {
        val context = context().copy(
            persona = Persona(
                id = "persona",
                name = "Traveler",
                description = "A patient archivist from the coast.",
            ),
        )

        val result = engine.evaluate("{{user}} / {{persona}}", context, MacroTransaction())

        assertEquals("Traveler / A patient archivist from the coast.", result.text)
    }

    @Test
    fun `global variable conditions remain literal instead of becoming truthy local conditions`() {
        val scopedText = "{{if \$mood}}visible{{else}}hidden{{/if}}"
        val inlineText = "{{if::{{getglobalvar::mood}}::visible::hidden}}"
        val scoped = engine.evaluate(scopedText, context(), MacroTransaction())
        val inline = engine.evaluate(inlineText, context(), MacroTransaction())

        assertEquals(scopedText, scoped.text)
        assertEquals(inlineText, inline.text)
        assertTrue(scoped.diagnostics.any { it.code == "UNSUPPORTED_MACRO" })
        assertTrue(inline.diagnostics.any { it.code == "UNSUPPORTED_MACRO" })
    }

    @Test
    fun `random is stable per generation and pick is stable per conversation`() {
        val text = "{{random::a::b::c}}/{{roll::1d20}}/{{pick::red::blue}}"
        val first = engine.evaluate(text, context(conversationId = "chat"), MacroTransaction(seed = "attempt")).text
        val repeated = engine.evaluate(text, context(conversationId = "chat"), MacroTransaction(seed = "attempt")).text
        val nextAttempt = engine.evaluate(text, context(conversationId = "chat"), MacroTransaction(seed = "other")).text
        val otherConversation = engine.evaluate(text, context(conversationId = "other"), MacroTransaction(seed = "attempt")).text

        assertEquals(first, repeated)
        assertEquals(first.substringAfterLast('/'), nextAttempt.substringAfterLast('/'))
        // A different attempt is allowed to collide, but both random values remain valid.
        assertTrue(nextAttempt.substringBefore('/') in setOf("a", "b", "c"))
        assertTrue(otherConversation.substringAfterLast('/') in setOf("red", "blue"))
    }

    @Test
    fun `clock based macros use the supplied transaction context instant`() {
        val result = engine.evaluate(
            "{{isodate}} {{isotime}} {{datetimeformat::YYYY-MM-DD HH:mm}}",
            context(now = Instant.parse("2026-08-31T12:34:56Z")),
            MacroTransaction(),
        )

        assertEquals("2026-08-31 12:34 2026-08-31 12:34", result.text)
    }

    @Test
    fun `trim comments and reverse preserve new engine text semantics`() {
        val result = engine.evaluate(
            "before\n{{trim}}\nafter/{{// hidden}}/{{//}}multiline\ncomment{{///}}{{reverse::A😀B}}",
            context(),
            MacroTransaction(),
        )

        assertEquals("beforeafter//B😀A", result.text)
    }

    private fun context(
        conversationId: String = "chat",
        now: Instant = Instant.parse("2026-08-31T12:34:56Z"),
    ) = MacroContext(
        character = CharacterAsset(id = "card", name = "Aster", nickname = "Ash").snapshot(),
        persona = Persona("persona", "Traveler"),
        conversationId = conversationId,
        generationId = "attempt",
        now = now,
        zoneId = ZoneOffset.UTC,
    )
}
