package io.github.zvensmoluya.modelgateway

import io.github.zvensmoluya.modelgateway.anthropic.AnthropicMessage
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicMessagesRequest
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicRole
import io.github.zvensmoluya.modelgateway.gemini.GeminiContent
import io.github.zvensmoluya.modelgateway.gemini.GeminiContentRole
import io.github.zvensmoluya.modelgateway.gemini.GeminiGenerateContentRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenCountClientTest {
    @Test
    fun `anthropic count tokens uses the non-streaming count endpoint`() = runBlocking {
        HttpsTestServer().use { test ->
            test.server.enqueue(MockResponse(body = """{"input_tokens":37}"""))
            val target = test.target(ModelProtocol.ANTHROPIC_MESSAGES, "/v1/messages", AuthScheme.X_API_KEY)

            val result = test.gateway().anthropicMessages.countTokens(
                target,
                AnthropicMessagesRequest(
                    model = "claude-test",
                    messages = listOf(AnthropicMessage(AnthropicRole.USER, "hello")),
                    maxTokens = 100,
                    system = "system",
                ),
            )

            assertEquals(37L, result.inputTokens)
            val request = test.server.takeRequest()
            assertEquals("/v1/messages/count_tokens", request.url.encodedPath)
            val body = Json.parseToJsonElement(request.body?.utf8().orEmpty()).jsonObject
            assertTrue("messages" in body)
            assertFalse("stream" in body)
            assertFalse("max_tokens" in body)
        }
    }

    @Test
    fun `gemini count tokens replaces stream method and removes alt query`() = runBlocking {
        HttpsTestServer().use { test ->
            test.server.enqueue(MockResponse(body = """{"totalTokens":42}"""))
            val target = test.target(
                ModelProtocol.GEMINI_GENERATE_CONTENT,
                "/v1beta/models/{model}:streamGenerateContent?alt=sse",
                AuthScheme.X_GOOG_API_KEY,
            )

            val result = test.gateway().geminiGenerateContent.countTokens(
                target,
                GeminiGenerateContentRequest(
                    model = "gemini-test",
                    contents = listOf(GeminiContent(GeminiContentRole.USER, "hello")),
                    systemInstruction = "system",
                    maxOutputTokens = 100,
                ),
            )

            assertEquals(42L, result.totalTokens)
            val request = test.server.takeRequest()
            assertEquals("/v1beta/models/gemini-test:countTokens", request.url.encodedPath)
            assertEquals(null, request.url.queryParameter("alt"))
            assertFalse(request.body?.utf8().orEmpty().contains("generationConfig"))
        }
    }
}
