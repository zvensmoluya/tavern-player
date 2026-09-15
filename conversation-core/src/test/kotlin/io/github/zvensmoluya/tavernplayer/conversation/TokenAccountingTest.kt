package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.PresetAsset
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenAccountingTest {
    @Test
    fun `additive budgeting counts messages once and verifies the final request`() {
        var partCalls = 0
        var fullCalls = 0
        val accounting = object : TokenAccounting {
            override fun countParts(messages: List<PreparedMessage>, modelId: String): MessageTokenCounts {
                partCalls++
                return MessageTokenCounts(messages.map { 10 }, 3, TokenCountQuality.ESTIMATED, "fixture")
            }
            override fun count(messages: List<PreparedMessage>, modelId: String): TokenCount {
                fullCalls++
                return TokenCount(messages.size * 10 + 3, TokenCountQuality.ESTIMATED, "fixture")
            }
        }
        val messages = listOf(prepared("system", "main")) + (0 until 1000).map {
            prepared("history $it", "chat-history", MessageRole.ASSISTANT, "history-$it")
        } + prepared("latest", "chat-history", MessageRole.USER, "latest")
        val result = ContextBudgeter(accounting).budget(messages, input(context = 38, output = 5))
        assertEquals(listOf("system", "history 999", "latest"), result.messages.map { it.content })
        assertEquals(33, result.report.inputTokens)
        assertEquals(1, partCalls)
        assertEquals(1, fullCalls)
        assertNull(result.failure)
    }

    @Test
    fun `mapped OpenAI model uses exact tokenizer and unknown model uses byte upper bound`() {
        val accounting = DefaultTokenAccounting()
        val messages = listOf(prepared("你好 world", "main"))

        val known = accounting.count(messages, "gpt-4o")
        val unknown = accounting.count(messages, "custom-model")

        assertEquals(TokenCountQuality.EXACT, known.quality)
        assertEquals("o200k_base", known.tokenizer)
        assertEquals(TokenCountQuality.ESTIMATED, unknown.quality)
        assertEquals("utf8-byte-upper-bound", unknown.tokenizer)
        assertNotEquals(known.tokens, unknown.tokens)
    }

    @Test
    fun `legacy tokenizer families are used without claiming unknown message framing is exact`() {
        val accounting = DefaultTokenAccounting()

        val p50k = accounting.count(listOf(prepared("hello", "main")), "text-davinci-003")
        val r50k = accounting.countText("hello", "davinci")
        val futureUnknown = accounting.count(listOf(prepared("hello", "main")), "gpt-50")

        assertEquals(TokenCountQuality.ESTIMATED, p50k.quality)
        assertEquals("p50k_base+estimated-framing", p50k.tokenizer)
        assertEquals(TokenCountQuality.EXACT, r50k.quality)
        assertEquals("r50k_base", r50k.tokenizer)
        assertEquals("utf8-byte-upper-bound", futureUnknown.tokenizer)
    }

    @Test
    fun `budget removes oldest history first and preserves latest user message`() {
        val accounting = object : TokenAccounting {
            override fun count(messages: List<PreparedMessage>, modelId: String) =
                TokenCount(messages.size * 10, TokenCountQuality.ESTIMATED, "fixture")
        }
        val budgeter = ContextBudgeter(accounting)
        val messages = listOf(
            prepared("system", "main"),
            prepared("old-user", "chat-history", MessageRole.USER, "old-user"),
            prepared("old-assistant", "chat-history", MessageRole.ASSISTANT, "old-assistant"),
            prepared("latest", "chat-history", MessageRole.USER, "latest"),
        )
        val result = budgeter.budget(messages, input(context = 35, output = 5))

        assertEquals(listOf("main", "old-assistant", "latest"), result.messages.map { it.origin.sourceIds.single() })
        assertTrue(result.trace.single().sourceIds == listOf("old-user"))
        assertEquals(null, result.failure)
    }

    @Test
    fun `forced local input budget supports provider reclip without changing effective context`() {
        val accounting = object : TokenAccounting {
            override fun count(messages: List<PreparedMessage>, modelId: String) =
                TokenCount(messages.size * 10, TokenCountQuality.ESTIMATED, "fixture")
        }
        val result = ContextBudgeter(accounting).budget(
            listOf(
                prepared("system", "main"),
                prepared("old", "chat-history", MessageRole.ASSISTANT, "old"),
                prepared("latest", "chat-history", MessageRole.USER, "latest"),
            ),
            input(context = 100, output = 10).copy(maxInputTokens = 20),
        )

        assertEquals(100, result.report.contextLimit)
        assertEquals(20, result.report.inputTokens)
        assertEquals(listOf("main", "latest"), result.messages.map { it.origin.sourceIds.single() })
    }

    @Test
    fun `mandatory content overflow fails before network`() {
        val accounting = object : TokenAccounting {
            override fun count(messages: List<PreparedMessage>, modelId: String) =
                TokenCount(messages.size * 50, TokenCountQuality.ESTIMATED, "fixture")
        }
        val result = ContextBudgeter(accounting).budget(
            listOf(prepared("required", "main")),
            input(context = 40, output = 10),
        )

        assertEquals("MANDATORY_CONTEXT_OVERFLOW", result.failure?.code)
    }

    @Test
    fun `unknown model uses explicit preset budgets without inventing model limits`() {
        val accounting = object : TokenAccounting {
            override fun count(messages: List<PreparedMessage>, modelId: String) =
                TokenCount(11_268, TokenCountQuality.ESTIMATED, "fixture")
        }
        val result = ContextBudgeter(accounting).budget(
            listOf(prepared("required", "main")),
            input(context = 2_000_000, output = 65_535).copy(
                modelId = "custom-model",
                modelContextTokens = null,
                modelOutputTokens = null,
            ),
        )

        assertEquals(2_000_000, result.report.contextLimit)
        assertEquals(65_535, result.report.reservedOutputTokens)
        assertEquals(null, result.failure)
        assertTrue(result.diagnostics.any { it.code == "MODEL_CONTEXT_BUDGET_UNVERIFIED" })
        assertTrue(result.diagnostics.any { it.code == "MODEL_OUTPUT_BUDGET_UNVERIFIED" })
    }

    @Test
    fun `unknown model marks a conservative preset output request as unverified`() {
        val result = ContextBudgeter().budget(
            listOf(prepared("required", "main")),
            input(context = 2_000_000, output = 1_024).copy(
                modelId = "custom-model",
                modelContextTokens = null,
                modelOutputTokens = null,
            ),
        )

        assertEquals(1_024, result.report.reservedOutputTokens)
        assertTrue(result.diagnostics.any { it.code == "MODEL_OUTPUT_BUDGET_UNVERIFIED" })
    }

    @Test
    fun `missing model and preset context preserves requests beyond two million tokens`() {
        val base = input(context = 2_000_000, output = 1_024)
        val messages = listOf(prepared("x".repeat(2_100_000), "chat-history"), prepared("required", "main"))
        val result = ContextBudgeter().budget(
            messages,
            base.copy(
                preset = base.preset.copy(
                    generationSettings = base.preset.generationSettings.copy(maxContextTokens = null),
                ),
                modelId = "custom-model",
                modelContextTokens = null,
                modelOutputTokens = null,
            ),
        )

        assertNull(result.report.contextLimit)
        assertNull(result.failure)
        assertEquals(messages, result.messages)
        assertTrue(result.report.inputTokens > 2_000_000)
        assertEquals(1_024, result.report.reservedOutputTokens)
        assertTrue(result.diagnostics.any { it.code == "MODEL_CONTEXT_BUDGET_UNKNOWN" })
    }

    @Test
    fun `catalog model limits take priority over fallback limits`() {
        val result = ContextBudgeter().budget(
            listOf(prepared("required", "main")),
            input(context = 2_000_000, output = 65_535).copy(
                modelId = "custom-model",
                modelContextTokens = 128_000,
                modelOutputTokens = 32_000,
            ),
        )

        assertEquals(128_000, result.report.contextLimit)
        assertEquals(32_000, result.report.reservedOutputTokens)
        assertTrue(result.diagnostics.any { it.code == "MODEL_OUTPUT_LIMIT_CLAMPED" })
        assertTrue(result.diagnostics.none { it.code == "MODEL_CONTEXT_BUDGET_UNVERIFIED" })
        assertTrue(result.diagnostics.none { it.code == "MODEL_OUTPUT_BUDGET_UNVERIFIED" })
    }

    @Test
    fun `output budget never exceeds effective context even when model output limit is larger`() {
        val result = ContextBudgeter().budget(
            listOf(prepared("required", "main")),
            input(context = 16_000, output = 65_535).copy(
                modelContextTokens = null,
                modelOutputTokens = 128_000,
            ),
        )

        assertEquals(16_000, result.report.contextLimit)
        assertEquals(16_000, result.report.reservedOutputTokens)
        assertTrue(result.diagnostics.any { it.code == "OUTPUT_BUDGET_CONTEXT_CLAMPED" })
    }

    private fun prepared(
        content: String,
        stage: String,
        role: MessageRole = MessageRole.SYSTEM,
        id: String = stage,
    ) = PreparedMessage(role, content, PromptOrigin(stage, listOf(id)))

    private fun input(context: Int, output: Int) = NormalGenerationInput(
        character = CharacterAsset(id = "card", name = "Ash").snapshot(),
        persona = Persona("persona", "Traveler"),
        history = emptyList(),
        preset = PresetAsset(
            id = "preset",
            sourceSha256 = "preset",
            contentSha256 = "preset-content",
            name = "Preset",
            prompts = emptyList(),
            promptOrder = emptyList(),
            generationSettings = PresetGenerationSettings(
                maxOutputTokens = output,
                maxContextTokens = context,
            ),
        ),
        modelId = "custom",
        modelContextTokens = context,
        modelOutputTokens = output,
    )
}
