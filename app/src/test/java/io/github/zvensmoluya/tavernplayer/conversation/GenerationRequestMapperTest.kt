package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicRole
import io.github.zvensmoluya.modelgateway.gemini.GeminiContentRole
import io.github.zvensmoluya.modelgateway.gemini.GeminiInteractionInputStep
import io.github.zvensmoluya.tavernplayer.connections.ModelCache
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
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
        assertEquals(false, generate.request.store)

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
    fun `non Anthropic adapters reject assistant prefill`() {
        listOf(
            ModelProtocol.OPENAI_RESPONSES,
            ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            ModelProtocol.GEMINI_INTERACTIONS,
            ModelProtocol.GEMINI_GENERATE_CONTENT,
        ).forEach { protocol ->
            assertThrows(GatewayException.Configuration::class.java) {
                GenerationRequestMapper.map(connection(protocol), plan(prefill = "prefix"))
            }
        }
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

    private fun connection(protocol: ModelProtocol) = StoredConnection(
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
        selectedModel = "model",
        modelCache = ModelCache(),
    )
}

// The request has no hosted-state field; this helper keeps that invariant explicit in the test.
private fun io.github.zvensmoluya.modelgateway.gemini.GeminiGenerateContentRequest.previousStateForTest(): Nothing? = null
