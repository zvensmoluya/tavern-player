package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.ContentRole
import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookEntryDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookPosition
import io.github.zvensmoluya.tavernplayer.content.WorldBookSecondaryLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldBookEngineTest {
    private val engine = WorldBookEngine()

    @Test
    fun `primary secondary regex and recursion activate in stable order`() {
        val book = WorldBookDefinition(
            id = "book",
            recursiveScanning = true,
            entries = listOf(
                entry("primary", keys = listOf("dragon"), secondary = listOf("cave"), content = "hidden rune", selective = true),
                entry("recursive", keys = listOf("hidden\\s+rune"), content = "second", regex = true),
                entry("negative", keys = listOf("dragon"), secondary = listOf("sun"), content = "dark", selective = true)
                    .copy(secondaryLogic = WorldBookSecondaryLogic.NOT_ANY),
            ),
        )

        val result = activate(book, listOf(message("A dragon waits in the cave.")))

        assertEquals(setOf("primary", "recursive", "negative"), result.activatedEntryIds.toSet())
        assertTrue(result.injections.single().content.contains("second"))
    }

    @Test
    fun `entry scan depth overrides the book default`() {
        val book = WorldBookDefinition(
            id = "book",
            scanDepth = 1,
            entries = listOf(
                entry("shallow", keys = listOf("old-key"), content = "no"),
                entry("deep", keys = listOf("old-key"), content = "yes").copy(scanDepth = 2),
            ),
        )
        val result = activate(book, listOf(message("old-key"), message("new")))

        assertEquals(listOf("deep"), result.activatedEntryIds)
    }

    @Test
    fun `regex key literals preserve supported flags and skip unsupported semantics`() {
        val book = WorldBookDefinition(
            id = "book",
            entries = listOf(
                entry("literal", keys = listOf("/DRAGON/i"), content = "yes").copy(caseSensitive = true),
                entry("unsupported", keys = listOf("/dragon/y"), content = "no"),
            ),
        )

        val result = activate(book, listOf(message("dragon")))

        assertEquals(listOf("literal"), result.activatedEntryIds)
        assertTrue(result.diagnostics.any { it.code == "UNSUPPORTED_WORLD_BOOK_REGEX_FLAGS" })
    }

    @Test
    fun `catastrophic regex key is bounded`() {
        val book = WorldBookDefinition(
            id = "book",
            entries = listOf(entry("slow", keys = listOf("^(a+)+$"), content = "no").copy(useRegex = true)),
        )

        val result = activate(book, listOf(message("a".repeat(20_000) + "!")))

        assertTrue(result.activatedEntryIds.isEmpty())
        assertTrue(result.diagnostics.any { it.code == "WORLD_BOOK_REGEX_TIMEOUT" })
    }

    @Test
    fun `sticky cooldown and delay survive generation boundaries`() {
        val stickyBook = WorldBookDefinition(
            id = "sticky-book",
            entries = listOf(entry("sticky", keys = listOf("key"), content = "active").copy(sticky = 1, cooldown = 1)),
        )
        val first = activate(stickyBook, listOf(message("key")), turn = 0)
        val sticky = activate(stickyBook, listOf(message("none")), state = first.runtimeState, turn = 1)
        val cooldown = activate(stickyBook, listOf(message("none")), state = sticky.runtimeState, turn = 2)

        assertTrue("sticky" in first.activatedEntryIds)
        assertTrue("sticky" in sticky.activatedEntryIds)
        assertFalse("sticky" in cooldown.activatedEntryIds)

        val cooldownOnlyBook = WorldBookDefinition(
            id = "cooldown-book",
            entries = listOf(entry("cooldown", keys = listOf("key"), content = "active").copy(cooldown = 1)),
        )
        val cooldownFirst = activate(cooldownOnlyBook, listOf(message("key")), turn = 0)
        val cooldownSuppressed = activate(
            cooldownOnlyBook,
            listOf(message("key")),
            state = cooldownFirst.runtimeState,
            turn = 1,
        )
        val cooldownReleased = activate(
            cooldownOnlyBook,
            listOf(message("key")),
            state = cooldownSuppressed.runtimeState,
            turn = 2,
        )
        assertTrue("cooldown" in cooldownFirst.activatedEntryIds)
        assertFalse("cooldown" in cooldownSuppressed.activatedEntryIds)
        assertTrue("cooldown" in cooldownReleased.activatedEntryIds)

        val delayBook = WorldBookDefinition(
            id = "delay-book",
            entries = listOf(entry("delay", keys = listOf("door"), content = "open").copy(delay = 1)),
        )
        val armed = activate(delayBook, listOf(message("door")), turn = 0)
        val released = activate(delayBook, listOf(message("door")), state = armed.runtimeState, turn = 1)
        assertTrue(armed.activatedEntryIds.isEmpty())
        assertEquals(listOf("delay"), released.activatedEntryIds)
    }

    @Test
    fun `timed state keys include the book id`() {
        val books = listOf(
            WorldBookDefinition(
                id = "first-book",
                entries = listOf(entry("shared", constant = true, content = "first").copy(sticky = 1)),
            ),
            WorldBookDefinition(
                id = "second-book",
                entries = listOf(entry("shared", constant = true, content = "second").copy(cooldown = 2)),
            ),
        )

        val result = engine.activate(
            books = books,
            characterText = "",
            projectedHistory = emptyList(),
            regexRules = emptyList(),
            macroContext = MacroContext(
                character = CharacterAsset(id = "card", name = "Ash").snapshot(),
                persona = Persona("persona", "Traveler"),
            ),
            transaction = MacroTransaction(seed = "attempt"),
            previousState = emptyMap(),
            turnIndex = 0,
            inputBudgetTokens = 10_000,
        )

        assertEquals(setOf("first-book:shared", "second-book:shared"), result.runtimeState.keys)
    }

    @Test
    fun `conversation overrides disable books and can enable disabled entries`() {
        val book = WorldBookDefinition(
            id = "book",
            entries = listOf(
                entry("default-on", constant = true, content = "on"),
                entry("default-off", constant = true, content = "off").copy(enabled = false),
            ),
        )

        val bookDisabled = activate(
            book,
            emptyList(),
            overrides = WorldBookActivationOverrides(books = mapOf("book" to false)),
        )
        val entryEnabled = activate(
            book,
            emptyList(),
            overrides = WorldBookActivationOverrides(
                entries = mapOf("book" to mapOf("default-on" to false, "default-off" to true)),
            ),
        )

        assertTrue(bookDisabled.activatedEntryIds.isEmpty())
        assertTrue(bookDisabled.trace.any { it.decision.contains("book disabled") })
        assertEquals(listOf("default-off"), entryEnabled.activatedEntryIds)
        assertTrue(
            entryEnabled.trace.any {
                it.sourceIds == listOf("book", "default-on") &&
                    it.decision == "entry disabled by conversation override"
            },
        )
        assertTrue(
            entryEnabled.trace.any {
                it.sourceIds == listOf("book", "default-off") &&
                    it.decision == "entry enabled by conversation override"
            },
        )
    }

    @Test
    fun `timed state advances while its book is disabled`() {
        val book = WorldBookDefinition(
            id = "book",
            entries = listOf(entry("entry", constant = true, content = "active")),
        )
        val result = activate(
            book,
            emptyList(),
            state = mapOf("book:entry" to WorldBookEntryRuntimeState(cooldownRemaining = 2)),
            overrides = WorldBookActivationOverrides(books = mapOf("book" to false)),
        )

        assertEquals(1, result.runtimeState.getValue("book:entry").cooldownRemaining)
    }

    @Test
    fun `outlet names and placements remain available to prompt macros`() {
        val book = WorldBookDefinition(
            id = "book",
            entries = listOf(
                entry("outlet", constant = true, content = "outlet text").copy(
                    position = WorldBookPosition.OUTLET,
                    outletName = "memory",
                    role = ContentRole.SYSTEM,
                ),
            ),
        )
        val result = activate(book, emptyList())
        val injection = result.injections.single()

        assertEquals(WorldBookPosition.OUTLET, injection.position)
        assertEquals("memory", injection.outletName)
        assertEquals("outlet text", injection.content)
    }

    @Test
    fun `world book budget drops entries without blocking activation diagnostics`() {
        val book = WorldBookDefinition(
            id = "book",
            tokenBudget = 5,
            entries = listOf(
                entry("small", constant = true, content = "a"),
                entry("large", constant = true, content = "this is too large"),
            ),
        )
        val result = activate(book, emptyList(), budget = 100)

        assertEquals(listOf("small"), result.activatedEntryIds)
        assertTrue(result.trace.any { it.sourceIds == listOf("large") && it.decision.contains("dropped") })
    }

    @Test
    fun `inclusion groups support scoring overrides and comma separated membership`() {
        val book = WorldBookDefinition(
            id = "book",
            entries = listOf(
                entry("low", keys = listOf("dragon", "missing"), content = "low").copy(
                    group = "creature, scene",
                    useGroupScoring = true,
                ),
                entry("high", keys = listOf("dragon", "cave"), content = "high").copy(
                    group = "creature",
                    useGroupScoring = true,
                ),
                entry("override", keys = listOf("cave"), content = "override").copy(
                    group = "scene",
                    groupOverride = true,
                    insertionOrder = 900,
                ),
            ),
        )

        val result = activate(book, listOf(message("A dragon waits in a cave.")))

        assertEquals(setOf("high", "override"), result.activatedEntryIds.toSet())
        assertTrue(result.trace.any { it.stage == "world-book-group" && it.sourceIds == listOf("low") })
    }

    private fun activate(
        book: WorldBookDefinition,
        history: List<ConversationMessage>,
        state: Map<String, WorldBookEntryRuntimeState> = emptyMap(),
        overrides: WorldBookActivationOverrides = WorldBookActivationOverrides(),
        turn: Int = 0,
        budget: Int = 10_000,
    ) = engine.activate(
        books = listOf(book),
        characterText = "",
        projectedHistory = history,
        regexRules = emptyList(),
        macroContext = MacroContext(
            character = CharacterAsset(id = "card", name = "Ash").snapshot(),
            persona = Persona("persona", "Traveler"),
            history = history,
        ),
        transaction = MacroTransaction(seed = "attempt-$turn"),
        previousState = state,
        activationOverrides = overrides,
        turnIndex = turn,
        inputBudgetTokens = budget,
    )

    private fun entry(
        id: String,
        keys: List<String> = emptyList(),
        secondary: List<String> = emptyList(),
        content: String,
        selective: Boolean = false,
        regex: Boolean = false,
        constant: Boolean = false,
    ) = WorldBookEntryDefinition(
        id = id,
        keys = keys,
        secondaryKeys = secondary,
        content = content,
        selective = selective,
        useRegex = regex,
        constant = constant,
    )

    private fun message(content: String) = ConversationMessage(
        id = content,
        role = MessageRole.USER,
        content = content,
        authorName = "Traveler",
    )
}
