package io.github.zvensmoluya.tavernplayer.connections

import io.github.zvensmoluya.modelgateway.anthropic.AnthropicUsage
import io.github.zvensmoluya.modelgateway.chat.ChatCompletionsUsage
import io.github.zvensmoluya.modelgateway.chat.ChatRole
import io.github.zvensmoluya.modelgateway.gemini.GeminiGenerateContentUsage
import io.github.zvensmoluya.modelgateway.gemini.GeminiInteractionInputStep
import io.github.zvensmoluya.modelgateway.gemini.GeminiInteractionsUsage
import io.github.zvensmoluya.modelgateway.responses.ResponsesUsage
import io.github.zvensmoluya.modelgateway.responses.ResponsesRole
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeRequestMapperTest {
    @Test
    fun `probe maps five native requests with fixed output limit and explicit storage policy`() {
        val input = ProbeInput(system = "system", user = "user")

        val responses = ProbeRequestMapper.responses("model", input)
        assertEquals(512, responses.maxOutputTokens)
        assertEquals(false, responses.store)
        assertEquals(ResponsesRole.USER, responses.input.single().role)

        val chat = ProbeRequestMapper.chat("model", input)
        assertEquals(512, chat.maxCompletionTokens)
        assertEquals(false, chat.store)
        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER), chat.messages.map { it.role })

        val anthropic = ProbeRequestMapper.anthropic("model", input)
        assertEquals(512, anthropic.maxTokens)
        assertEquals("system", anthropic.system)
        assertNull(anthropic.thinking)

        val interactions = ProbeRequestMapper.interactions("model", input)
        assertEquals(512, interactions.maxOutputTokens)
        assertEquals(false, interactions.store)
        assertEquals("user", (interactions.input.single() as GeminiInteractionInputStep.UserInput).text)

        val generateContent = ProbeRequestMapper.generateContent("models/model", input)
        assertEquals(512, generateContent.maxOutputTokens)
        assertEquals("system", generateContent.systemInstruction)
        assertNull(generateContent.thinking)
        assertFalse(generateContent.contents.isEmpty())
    }

    @Test
    fun `probe projects protocol native usage only at the app boundary`() {
        val raw = buildJsonObject {}

        assertEquals(
            ProbeUsage(1, 2, 3, 4, 5),
            ResponsesUsage(1, 2, 3, 4, 5, raw).toProbeUsage(),
        )
        assertEquals(
            ProbeUsage(6, 7, 13, 8, 9),
            ChatCompletionsUsage(6, 7, 13, 8, 9, raw).toProbeUsage(),
        )
        assertEquals(
            ProbeUsage(inputTokens = 10, outputTokens = 11, totalTokens = 21, cachedTokens = 25),
            AnthropicUsage(10, 11, 12, 13, raw).toProbeUsage(),
        )
        assertEquals(
            ProbeUsage(14, 15, 29, 16, 17),
            GeminiInteractionsUsage(14, 15, 29, 16, 17, 18, raw).toProbeUsage(),
        )
        assertEquals(
            ProbeUsage(19, 20, 39, 21, 22),
            GeminiGenerateContentUsage(19, 20, 39, 21, 22, 23, raw).toProbeUsage(),
        )
    }
}
