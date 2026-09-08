package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.ContentRole
import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookEntryDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookPosition
import io.github.zvensmoluya.tavernplayer.content.WorldBookSecondaryLogic
import io.github.zvensmoluya.tavernplayer.content.RegexDefinition
import io.github.zvensmoluya.tavernplayer.content.RegexPlacement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldBookEngineTest {
    private val engine = WorldBookEngine(keyRegexExecutionStrategy = ImmediateRegexExecutionStrategy)

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

        val result = activate(book, listOf(message("a".repeat(20_000) + "!")), target = WorldBookEngine())

        assertTrue(result.activatedEntryIds.isEmpty())
        assertTrue(result.diagnostics.any { it.code == "WORLD_BOOK_REGEX_TIMEOUT" })
    }

    @Test
    fun `sticky and cooldown survive generation boundaries`() {
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
    fun `source ignore budget entries activate after exhaustion but still consume the reported budget`() {
        val exempt = entry("exempt", constant = true, content = "required source rule").copy(
            extensions = kotlinx.serialization.json.JsonObject(mapOf("ignore_budget" to kotlinx.serialization.json.JsonPrimitive(true))))
        val book = WorldBookDefinition("book", tokenBudget = 0,
            entries = listOf(entry("ordinary", constant = true, content = "ordinary"), exempt))
        val result = activate(book, emptyList(), budget = 0)
        assertEquals(listOf("exempt"), result.activatedEntryIds)
        assertTrue(result.usedBudgetTokens > result.budgetTokens)
        assertTrue(result.trace.any { it.sourceIds == listOf("exempt") && "bypassed" in it.decision })
        assertTrue(activate(book, emptyList(), overrides = WorldBookActivationOverrides(books = mapOf("book" to false)), budget = 0).injections.isEmpty())
        assertTrue(activate(book.copy(entries = listOf(exempt.copy(enabled = false))), emptyList(), budget = 0).injections.isEmpty())
        val stringFlag = exempt.copy(extensions = kotlinx.serialization.json.JsonObject(mapOf("ignore_budget" to kotlinx.serialization.json.JsonPrimitive("true"))))
        assertTrue(activate(book.copy(entries = listOf(stringFlag)), emptyList(), budget = 0).injections.isEmpty())
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

    @Test
    fun `character fields only participate when the individual entry opts in`() {
        val flags = listOf("match_character_description", "match_character_personality", "match_character_depth_prompt", "match_scenario", "match_creator_notes")
        val scan = WorldBookCharacterScan("description-key", "personality-key", "depth-key", "scenario-key", "notes-key")
        val terms = listOf("description-key", "personality-key", "depth-key", "scenario-key", "notes-key")
        val entries = flags.flatMapIndexed { index, flag ->
            val candidate = entry("enabled-$index", keys = listOf(terms[index]), content = "lore")
            listOf(candidate, candidate.copy(id = "opted-in-$index", extensions = JsonObject(mapOf(flag to JsonPrimitive(true)))),
                candidate.copy(id = "opted-out-$index", extensions = JsonObject(mapOf(flag to JsonPrimitive(false)))))
        }
        val book = WorldBookDefinition("book", entries = entries)
        assertEquals(flags.indices.map { "opted-in-$it" }, activate(book, listOf(message("unrelated chat")), characterScan = scan).activatedEntryIds)
        assertTrue(activate(book.copy(scanDepth = 0), listOf(message(terms.joinToString())), characterScan = scan).activatedEntryIds.isEmpty())
        // Opting out of character scanning does not disable ordinary history matching.
        assertEquals(15, activate(book, listOf(message(terms.joinToString()))).activatedEntryIds.size)
    }

    @Test
    fun `delay is a message threshold independent of first match retries or saved counters`() {
        val book = WorldBookDefinition("book", entries = listOf(entry("door", keys = listOf("door"), content = "open").copy(delay = 3)))
        val short = listOf(message("door"), message("door"))
        val gated = activate(book, short, turn = 40)
        assertTrue(gated.activatedEntryIds.isEmpty())
        assertTrue(activate(book, short, gated.runtimeState, turn = 41).activatedEntryIds.isEmpty())
        val threshold = short + message("door")
        val firstMatch = activate(book, threshold, turn = 0)
        assertEquals(listOf("door"), firstMatch.activatedEntryIds)
        assertEquals(firstMatch, activate(book, threshold, turn = 0))
        assertEquals(listOf("door"), activate(book, listOf(message("earlier"), message("earlier"), message("earlier"), message("door"))).activatedEntryIds)
        assertTrue(activate(book, short, firstMatch.runtimeState, turn = 0).activatedEntryIds.isEmpty())
        val legacy = Json { ignoreUnknownKeys = true }.decodeFromString<WorldBookEntryRuntimeState>(
            """{"stickyRemaining":0,"cooldownRemaining":0,"delayRemaining":99,"delayStartedTurn":12}""",
        )
        assertEquals(listOf("door"), activate(book, threshold, mapOf("book:door" to legacy)).activatedEntryIds)
        assertTrue(activate(book.copy(entries = book.entries.map { it.copy(constant = true) }), emptyList()).activatedEntryIds.isEmpty())
    }

    @Test
    fun `a group loser cannot seed recursion or displace an earlier winner`() {
        val book = WorldBookDefinition("book", recursiveScanning = true, entries = listOf(
            entry("winner", constant = true, content = "winner-key").copy(group = "route", groupOverride = true),
            entry("loser", constant = true, content = "loser-key").copy(group = "route"),
            entry("ghost", keys = listOf("loser-key"), content = "must never appear"),
            entry("late-group", keys = listOf("winner-key"), content = "late-key").copy(group = "route", groupOverride = true, insertionOrder = 999),
            entry("late-ghost", keys = listOf("late-key"), content = "must never appear either"),
            entry("followup", keys = listOf("winner-key"), content = "followup"),
        ))
        assertEquals(listOf("winner", "followup"), activate(book, emptyList()).activatedEntryIds)
    }

    @Test
    fun `probability follows group selection and a failed entry is not rerolled during recursion`() {
        val book = WorldBookDefinition("book", recursiveScanning = true, entries = listOf(
            entry("selected", constant = true, content = "forbidden-key").copy(group = "route", groupOverride = true, probability = 0),
            entry("fallback", constant = true, content = "forbidden-key").copy(group = "route"),
            entry("seed", constant = true, content = "next-key"),
            entry("next", keys = listOf("next-key"), content = "done"),
            entry("ghost", keys = listOf("forbidden-key"), content = "forbidden"),
        ))
        val firstPassOnly = activate(book.copy(recursiveScanning = false), emptyList())
        assertEquals(listOf("seed"), firstPassOnly.activatedEntryIds)
        val recursive = activate(book.copy(entries = book.entries.filterNot { it.id == "fallback" }), emptyList())
        assertEquals(listOf("seed", "next"), recursive.activatedEntryIds)
        assertEquals(1, recursive.trace.count { it.sourceIds == listOf("selected") && it.decision.startsWith("failed probability") })
    }

    @Test
    fun `overflow stops ordinary entries and recursion while budget exempt entries remain eligible`() {
        val book = WorldBookDefinition("book", tokenBudget = 12, recursiveScanning = true, entries = listOf(
            entry("seed", constant = true, content = "next-key").copy(insertionOrder = 900),
            entry("large", constant = true, content = "too large ".repeat(100)).copy(insertionOrder = 800),
            entry("small", constant = true, content = "x").copy(probability = 0),
            entry("exempt", constant = true, content = "required").copy(extensions = JsonObject(mapOf("ignore_budget" to JsonPrimitive(true)))),
            entry("recursive", keys = listOf("next-key"), content = "x"),
        ))
        val result = activate(book, emptyList())
        assertEquals(listOf("seed", "exempt"), result.activatedEntryIds)
        assertTrue(result.trace.any { it.sourceIds == listOf("small") && "overflow" in it.decision })
        assertFalse(result.trace.any { it.sourceIds == listOf("small") && "probability" in it.decision })
    }

    @Test
    fun `static macros are budgeted and seed recursion once before display regex`() {
        val book = WorldBookDefinition("book", recursiveScanning = true, entries = listOf(
            entry("seed", constant = true, content = "{{incvar::visits}}{{user}}"),
            entry("next", keys = listOf("Traveler"), content = "found"),
            entry("wrong", keys = listOf("DISPLAY_ONLY"), content = "wrong"),
        ))
        val transaction = MacroTransaction(seed = "macro-scan")
        val result = engine.activate(
            books = listOf(book), projectedHistory = emptyList(),
            regexRules = listOf(RegexDefinition("display", "display", "Traveler", "DISPLAY_ONLY", placements = setOf(RegexPlacement.WORLD_INFO), promptOnly = true)),
            macroContext = MacroContext(CharacterAsset("card", name = "Guide").snapshot(), Persona("p", "Traveler")),
            transaction = transaction, previousState = emptyMap(), turnIndex = 0, inputBudgetTokens = 10_000,
        )
        assertEquals(listOf("seed", "next"), result.activatedEntryIds)
        assertTrue(result.injections.single().content.contains("DISPLAY_ONLY"))
        assertEquals("1", transaction.snapshot()["visits"]?.text)
        val large = WorldBookDefinition("large", tokenBudget = 20, entries = listOf(entry("expanded", constant = true, content = "{{user}}")))
        val dropped = engine.activate(books = listOf(large), projectedHistory = emptyList(), regexRules = emptyList(),
            macroContext = MacroContext(CharacterAsset("card", name = "Guide").snapshot(), Persona("p", "name ".repeat(200))),
            transaction = MacroTransaction(seed = "large"), previousState = emptyMap(), turnIndex = 0, inputBudgetTokens = 10_000)
        assertTrue(dropped.activatedEntryIds.isEmpty())
    }

    private fun activate(
        book: WorldBookDefinition,
        history: List<ConversationMessage>,
        state: Map<String, WorldBookEntryRuntimeState> = emptyMap(),
        overrides: WorldBookActivationOverrides = WorldBookActivationOverrides(),
        turn: Int = 0,
        budget: Int = 10_000,
        characterScan: WorldBookCharacterScan = WorldBookCharacterScan(),
        target: WorldBookEngine = engine,
    ) = target.activate(
        books = listOf(book),
        characterScan = characterScan,
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
