package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicRole
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicThinkingType
import io.github.zvensmoluya.modelgateway.gemini.GeminiContentRole
import io.github.zvensmoluya.modelgateway.gemini.GeminiInteractionInputStep
import io.github.zvensmoluya.tavernplayer.connections.ModelCache
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationParameter
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationSettings
import io.github.zvensmoluya.tavernplayer.content.PresetReasoningEffort
import io.github.zvensmoluya.tavernplayer.content.PresetVerbosity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationRequestMapperTest {
    @Test
    fun `OpenAI adapters preserve structured roles and disable hosted state`() {
        val responses = GenerationRequestMapper.map(connection(ModelProtocol.OPENAI_RESPONSES), plan())
            as PreparedGenerationRequest.Responses
        assertEquals(listOf("system", "system", "user", "system", "assistant", "user"), responses.request.input.map { it.role.wire })
        assertNull(responses.request.instructions)
        assertNull(responses.request.previousResponseId)
        assertEquals(false, responses.request.store)
        assertFalse(responses.preview.usesHostedState)

        val chat = GenerationRequestMapper.map(connection(ModelProtocol.OPENAI_CHAT_COMPLETIONS), plan())
            as PreparedGenerationRequest.Chat
        assertEquals(listOf("system", "system", "user", "system", "assistant", "user"), chat.request.messages.map { it.role.wire })
        assertEquals(false, chat.request.store)
        assertEquals(512, chat.request.maxCompletionTokens)
    }

    @Test
    fun `Anthropic extracts leading system converts later system and applies prefill`() {
        val prepared = GenerationRequestMapper.map(
            connection(ModelProtocol.ANTHROPIC_MESSAGES),
            plan(prefill = "米拉："),
        ) as PreparedGenerationRequest.Anthropic

        assertEquals("system one\n\nsystem two", prepared.request.system)
        assertEquals(
            listOf(AnthropicRole.USER, AnthropicRole.ASSISTANT, AnthropicRole.USER, AnthropicRole.ASSISTANT),
            prepared.request.messages.map { it.role },
        )
        assertEquals("user one\n\nlate system", prepared.request.messages[0].text)
        assertEquals("米拉：", prepared.request.messages.last().text)
        assertTrue(prepared.preview.assistantPrefillApplied)
    }

    @Test
    fun `Gemini adapters extract system and replay only matching signatures`() {
        val generate = GenerationRequestMapper.map(
            connection(ModelProtocol.GEMINI_GENERATE_CONTENT),
            plan(reasoningAdapter = ModelProtocol.GEMINI_GENERATE_CONTENT.name),
        ) as PreparedGenerationRequest.GenerateContent
        assertEquals("system one\n\nsystem two", generate.request.systemInstruction)
        assertEquals(listOf(GeminiContentRole.USER, GeminiContentRole.MODEL, GeminiContentRole.USER), generate.request.contents.map { it.role })
        assertEquals("sig", generate.request.contents[1].thoughtSignature)
        assertNull(generate.request.previousStateForTest())

        val interactions = GenerationRequestMapper.map(
            connection(ModelProtocol.GEMINI_INTERACTIONS),
            plan(reasoningAdapter = ModelProtocol.GEMINI_INTERACTIONS.name),
        ) as PreparedGenerationRequest.Interactions
        assertEquals("system one\n\nsystem two", interactions.request.systemInstruction)
        assertTrue(interactions.request.input.any { it is GeminiInteractionInputStep.Thought && it.signature == "sig" })
        assertNull(interactions.request.previousInteractionId)
        assertEquals(false, interactions.request.store)

        val switched = GenerationRequestMapper.map(
            connection(ModelProtocol.GEMINI_INTERACTIONS),
            plan(reasoningAdapter = ModelProtocol.GEMINI_GENERATE_CONTENT.name),
        ) as PreparedGenerationRequest.Interactions
        assertFalse(switched.request.input.any { it is GeminiInteractionInputStep.Thought })
    }

    @Test
    fun `unsupported assistant prefill is omitted and diagnosed without blocking generation`() {
        listOf(
            ModelProtocol.OPENAI_RESPONSES,
            ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            ModelProtocol.GEMINI_INTERACTIONS,
            ModelProtocol.GEMINI_GENERATE_CONTENT,
        ).forEach { protocol ->
            val prepared = GenerationRequestMapper.map(connection(protocol), plan(prefill = "prefix"))
            assertFalse(prepared.preview.assistantPrefillApplied)
            assertTrue(prepared.preview.omittedPresetControls.any { it.control == "assistant_prefill" })
        }
    }

    @Test
    fun `five protocol adapters map supported controls and diagnose every meaningful omission`() {
        val tuned = plan().copy(
            maxOutputTokens = 4_096,
            generationSettings = PresetGenerationSettings(
                maxContextTokens = 8_192,
                maxOutputTokens = 4_096,
                temperature = 0.7,
                topP = 0.8,
                topK = 32,
                topA = 0.2,
                minP = 0.1,
                repetitionPenalty = 1.1,
                frequencyPenalty = 0.2,
                presencePenalty = -0.1,
                seed = 42,
                reasoningEffort = PresetReasoningEffort.HIGH,
                verbosity = PresetVerbosity.HIGH,
            ),
        )

        val responses = GenerationRequestMapper.map(connection(ModelProtocol.OPENAI_RESPONSES), tuned)
            as PreparedGenerationRequest.Responses
        assertEquals(0.7, responses.request.temperature)
        assertEquals(0.8, responses.request.topP)
        assertEquals("high", responses.request.reasoning?.effort)
        assertEquals("high", responses.request.text?.verbosity)
        assertTrue(responses.preview.omittedPresetControls.any { it.control == "seed" })

        val chat = GenerationRequestMapper.map(connection(ModelProtocol.OPENAI_CHAT_COMPLETIONS), tuned)
            as PreparedGenerationRequest.Chat
        assertEquals(0.2, chat.request.frequencyPenalty)
        assertEquals(-0.1, chat.request.presencePenalty)
        assertEquals(42, chat.request.seed)
        assertEquals("high", chat.request.reasoningEffort)
        assertEquals("high", chat.request.verbosity)

        val anthropic = GenerationRequestMapper.map(
            connection(ModelProtocol.ANTHROPIC_MESSAGES, "claude-opus-4-7"),
            tuned,
        ) as PreparedGenerationRequest.Anthropic
        assertEquals(AnthropicThinkingType.ADAPTIVE, anthropic.request.thinking?.type)
        assertEquals("high", anthropic.request.outputConfig?.effort)
        assertNull(anthropic.request.temperature)
        assertTrue(anthropic.preview.omittedPresetControls.any { it.control == "temperature" })

        val interactions = GenerationRequestMapper.map(connection(ModelProtocol.GEMINI_INTERACTIONS), tuned)
            as PreparedGenerationRequest.Interactions
        assertEquals(42, interactions.request.seed)
        assertEquals("high", interactions.request.thinkingLevel)
        assertTrue(interactions.preview.omittedPresetControls.any { it.control == "temperature" })

        val generate = GenerationRequestMapper.map(connection(ModelProtocol.GEMINI_GENERATE_CONTENT), tuned)
            as PreparedGenerationRequest.GenerateContent
        assertEquals(0.7, generate.request.temperature)
        assertEquals(32, generate.request.topK)
        assertEquals(42, generate.request.seed)
        assertEquals("high", generate.request.thinking?.thinkingLevel)

        listOf(responses, chat, anthropic, interactions, generate).forEach { prepared ->
            assertTrue(prepared.preview.appliedPresetControls.contains("output_limit"))
            assertTrue(prepared.preview.omittedPresetControls.any { it.control == "top_a" })
            assertTrue(prepared.preview.omittedPresetControls.any { it.control == "min_p" })
            assertTrue(prepared.preview.omittedPresetControls.any { it.control == "repetition_penalty" })
            assertFalse(prepared.preview.usesHostedState)
            assertEquals(false, prepared.preview.store)
        }
    }

    @Test
    fun `unknown model capability conservatively omits reasoning and verbosity`() {
        val tuned = plan().copy(
            generationSettings = plan().generationSettings.copy(
                reasoningEffort = PresetReasoningEffort.MAX,
                verbosity = PresetVerbosity.LOW,
            ),
        )

        val prepared = GenerationRequestMapper.map(
            connection(ModelProtocol.OPENAI_CHAT_COMPLETIONS, "custom-model"),
            tuned,
        ) as PreparedGenerationRequest.Chat

        assertNull(prepared.request.reasoningEffort)
        assertNull(prepared.request.verbosity)
        assertTrue(prepared.preview.omittedPresetControls.any { it.control == "reasoning_effort" })
        assertTrue(prepared.preview.omittedPresetControls.any { it.control == "verbosity" })
    }

    @Test
    fun `disabled preset request controls are unplugged before provider capability mapping`() {
        val settings = plan().generationSettings.copy(
            temperature = 0.4,
            topP = 0.7,
            seed = 42,
            reasoningEffort = PresetReasoningEffort.HIGH,
            topA = 0.2,
            disabledParameters = setOf(
                PresetGenerationParameter.OUTPUT_LIMIT,
                PresetGenerationParameter.TEMPERATURE,
                PresetGenerationParameter.TOP_P,
                PresetGenerationParameter.SEED,
                PresetGenerationParameter.REASONING_EFFORT,
                PresetGenerationParameter.TOP_A,
            ),
        )
        val disabled = plan().copy(generationSettings = settings)

        val responses = GenerationRequestMapper.map(connection(ModelProtocol.OPENAI_RESPONSES), disabled)
            as PreparedGenerationRequest.Responses
        assertNull(responses.request.maxOutputTokens)
        assertNull(responses.request.temperature)
        assertNull(responses.request.topP)
        assertNull(responses.request.reasoning)
        assertNull(responses.preview.maxOutputTokens)
        assertFalse(responses.preview.appliedPresetControls.contains("output_limit"))
        assertFalse(responses.preview.omittedPresetControls.any { it.control == "top_a" })

        val chat = GenerationRequestMapper.map(connection(ModelProtocol.OPENAI_CHAT_COMPLETIONS), disabled)
            as PreparedGenerationRequest.Chat
        assertNull(chat.request.maxCompletionTokens)
        assertNull(chat.request.temperature)
        assertNull(chat.request.topP)
        assertNull(chat.request.seed)
        assertNull(chat.request.reasoningEffort)

        val anthropic = GenerationRequestMapper.map(connection(ModelProtocol.ANTHROPIC_MESSAGES), disabled)
            as PreparedGenerationRequest.Anthropic
        assertEquals(disabled.maxOutputTokens, anthropic.request.maxTokens)
        assertEquals(disabled.maxOutputTokens, anthropic.preview.maxOutputTokens)
        assertTrue(anthropic.preview.omittedPresetControls.any { it.control == "output_limit" })

        val interactions = GenerationRequestMapper.map(connection(ModelProtocol.GEMINI_INTERACTIONS), disabled)
            as PreparedGenerationRequest.Interactions
        assertNull(interactions.request.maxOutputTokens)
        assertNull(interactions.request.seed)

        val generate = GenerationRequestMapper.map(connection(ModelProtocol.GEMINI_GENERATE_CONTENT), disabled)
            as PreparedGenerationRequest.GenerateContent
        assertNull(generate.request.maxOutputTokens)
        assertNull(generate.request.temperature)
        assertNull(generate.request.topP)
        assertNull(generate.request.seed)
        assertNull(generate.request.thinking)
    }

    @Test
    fun `GenerateContent rejects multiple matching signatures`() {
        val message = plan(reasoningAdapter = ModelProtocol.GEMINI_GENERATE_CONTENT.name).messages[4]
        val invalid = plan().copy(
            messages = plan().messages.toMutableList().also {
                it[4] = message.copy(
                    reasoning = listOf(ReasoningBlock(signature = "one"), ReasoningBlock(signature = "two")),
                )
            },
        )

        assertThrows(GatewayException.Configuration::class.java) {
            GenerationRequestMapper.map(connection(ModelProtocol.GEMINI_GENERATE_CONTENT), invalid)
        }
    }

    private fun plan(
        prefill: String = "",
        reasoningAdapter: String? = ModelProtocol.GEMINI_INTERACTIONS.name,
    ) = GenerationPlan(
        messages = listOf(
            message(MessageRole.SYSTEM, "system one"),
            message(MessageRole.SYSTEM, "system two"),
            message(MessageRole.USER, "user one"),
            message(MessageRole.SYSTEM, "late system"),
            message(
                role = MessageRole.ASSISTANT,
                content = "assistant one",
                reasoning = listOf(ReasoningBlock("summary", "sig")),
                adapterId = reasoningAdapter,
            ),
            message(MessageRole.USER, "user two"),
        ),
        maxOutputTokens = 512,
        declaredContextTokens = 8_192,
        assistantPrefill = prefill,
        presetId = "preset",
        presetName = "Preset",
        presetContentSha256 = "fingerprint",
        generationSettings = PresetGenerationSettings(
            maxContextTokens = 8_192,
            maxOutputTokens = 512,
        ),
        diagnostics = emptyList(),
        trace = emptyList(),
    )

    private fun message(
        role: MessageRole,
        content: String,
        reasoning: List<ReasoningBlock> = emptyList(),
        adapterId: String? = null,
    ) = PreparedMessage(
        role = role,
        content = content,
        origin = PromptOrigin("test", listOf(content)),
        reasoning = reasoning,
        adapterId = adapterId,
    )

    private fun connection(
        protocol: ModelProtocol,
        model: String = when (protocol) {
            ModelProtocol.OPENAI_RESPONSES, ModelProtocol.OPENAI_CHAT_COMPLETIONS -> "gpt-5.6"
            ModelProtocol.ANTHROPIC_MESSAGES -> "claude-sonnet-4-5-20250929"
            ModelProtocol.GEMINI_INTERACTIONS, ModelProtocol.GEMINI_GENERATE_CONTENT -> "gemini-3-flash-preview"
        },
    ) = StoredConnection(
        id = protocol.name,
        name = protocol.name,
        templateId = protocol.name,
        protocol = protocol,
        apiAddress = "https://example.com",
        streamEndpoint = "https://example.com/stream",
        catalogEndpoint = null,
        authScheme = AuthScheme.NONE,
        credentialRef = null,
        credentialMask = null,
        approvedOrigins = emptySet(),
        selectedModel = model,
        modelCache = ModelCache(),
    )
}

// The request has no hosted-state field; this helper keeps that invariant explicit in the test.
private fun io.github.zvensmoluya.modelgateway.gemini.GeminiGenerateContentRequest.previousStateForTest(): Nothing? = null
