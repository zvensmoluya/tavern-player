package io.github.zvensmoluya.modelgateway

import io.github.zvensmoluya.modelgateway.anthropic.AnthropicMessage
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicMessagesAccumulator
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicMessagesEvent
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicMessagesRequest
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicRole
import io.github.zvensmoluya.modelgateway.anthropic.AnthropicThinking
import io.github.zvensmoluya.modelgateway.chat.ChatCompletionsAccumulator
import io.github.zvensmoluya.modelgateway.chat.ChatCompletionsEvent
import io.github.zvensmoluya.modelgateway.chat.ChatCompletionsRequest
import io.github.zvensmoluya.modelgateway.chat.ChatMessage
import io.github.zvensmoluya.modelgateway.chat.ChatRole
import io.github.zvensmoluya.modelgateway.gemini.GeminiContent
import io.github.zvensmoluya.modelgateway.gemini.GeminiContentRole
import io.github.zvensmoluya.modelgateway.gemini.GeminiGenerateContentAccumulator
import io.github.zvensmoluya.modelgateway.gemini.GeminiGenerateContentEvent
import io.github.zvensmoluya.modelgateway.gemini.GeminiGenerateContentRequest
import io.github.zvensmoluya.modelgateway.gemini.GeminiInteractionInputStep
import io.github.zvensmoluya.modelgateway.gemini.GeminiInteractionsAccumulator
import io.github.zvensmoluya.modelgateway.gemini.GeminiInteractionsEvent
import io.github.zvensmoluya.modelgateway.gemini.GeminiInteractionsRequest
import io.github.zvensmoluya.modelgateway.gemini.GeminiThinkingConfig
import io.github.zvensmoluya.modelgateway.responses.ResponsesAccumulator
import io.github.zvensmoluya.modelgateway.responses.ResponsesEvent
import io.github.zvensmoluya.modelgateway.responses.ResponsesInputMessage
import io.github.zvensmoluya.modelgateway.responses.ResponsesReasoning
import io.github.zvensmoluya.modelgateway.responses.ResponsesRequest
import io.github.zvensmoluya.modelgateway.responses.ResponsesRole
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolClientsTest {
    @Test
    fun `responses preserves text reasoning usage finish and unknown events`() = runBlocking {
        HttpsTestServer().use { test ->
            test.enqueueSse(
                """
                event: response.created
                data: {"type":"response.created","response":{"id":"resp_1"}}

                event: response.reasoning_summary_text.delta
                data: {"type":"response.reasoning_summary_text.delta","delta":"think "}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"hello"}

                event: response.future
                data: {"type":"response.future","value":1}

                event: response.completed
                data: {"type":"response.completed","response":{"id":"resp_1","status":"completed","usage":{"input_tokens":2,"output_tokens":3,"total_tokens":5,"output_tokens_details":{"reasoning_tokens":1}}}}

                """,
            )
            val gateway = test.gateway()
            val target = test.target(ModelProtocol.OPENAI_RESPONSES, "/v1/responses")
            val events = gateway.responses.stream(
                target,
                ResponsesRequest(
                    model = "gpt-test",
                    input = listOf(ResponsesInputMessage(ResponsesRole.USER, "hi")),
                    instructions = "system",
                    maxOutputTokens = 512,
                    previousResponseId = "resp_0",
                    reasoning = ResponsesReasoning(effort = "high", summary = "auto"),
                    store = false,
                ),
            ).toList()

            val accumulator = ResponsesAccumulator().also { value -> events.forEach(value::accept) }
            val result = accumulator.result()
            assertEquals("hello", result.text)
            assertEquals("think ", result.reasoning)
            assertEquals(5L, result.usage?.totalTokens)
            assertEquals("completed", result.finishReason)
            assertEquals(1, result.unknownEventCount)
            assertTrue(events.filterIsInstance<ResponsesEvent.Unknown>().single().raw.containsKey("value"))

            val request = test.server.takeRequest()
            assertEquals("Bearer test-secret", request.headers["Authorization"])
            val body = Json.parseToJsonElement(requireNotNull(request.body).utf8()).jsonObject
            assertEquals("false", body["store"].toString())
            assertEquals("512", body["max_output_tokens"].toString())
            assertFalse(body.containsKey("temperature"))
            assertFalse(body.containsKey("top_p"))
        }
    }

    @Test
    fun `chat completions sends native fields and accumulates chunks`() = runBlocking {
        HttpsTestServer().use { test ->
            test.enqueueSse(
                """
                data: {"id":"chat_1","choices":[{"delta":{"content":"hel","reasoning_content":"why "},"finish_reason":null}]}

                data: {"id":"chat_1","choices":[{"delta":{"content":"lo"},"finish_reason":"stop"}]}

                data: {"id":"chat_1","choices":[],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}

                data: [DONE]

                """,
            )
            val gateway = test.gateway()
            val events = gateway.chatCompletions.stream(
                test.target(ModelProtocol.OPENAI_CHAT_COMPLETIONS, "/v1/chat/completions"),
                ChatCompletionsRequest(
                    model = "chat-model",
                    messages = listOf(
                        ChatMessage(ChatRole.SYSTEM, "system"),
                        ChatMessage(ChatRole.USER, "hi"),
                    ),
                    maxCompletionTokens = 512,
                    store = false,
                ),
            ).toList()
            val result = ChatCompletionsAccumulator().also { a -> events.forEach(a::accept) }.result()
            assertEquals("hello", result.text)
            assertEquals("why ", result.reasoning)
            assertEquals("stop", result.finishReason)
            assertEquals(6L, result.usage?.totalTokens)
            assertTrue(events.any { it is ChatCompletionsEvent.Usage })
            val body = Json.parseToJsonElement(requireNotNull(test.server.takeRequest().body).utf8()).jsonObject
            assertEquals("true", body["stream"].toString())
            assertTrue(body.containsKey("max_completion_tokens"))
            assertFalse(body.containsKey("max_tokens"))
        }
    }

    @Test
    fun `anthropic keeps thinking signature usage and protocol header separate`() = runBlocking {
        HttpsTestServer().use { test ->
            test.enqueueSse(
                """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":8,"output_tokens":0}}}

                event: content_block_delta
                data: {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"reason"}}

                event: content_block_delta
                data: {"type":"content_block_delta","delta":{"type":"signature_delta","signature":"sig"}}

                event: content_block_delta
                data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"answer"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}

                event: message_stop
                data: {"type":"message_stop"}

                """,
            )
            val gateway = test.gateway()
            val events = gateway.anthropicMessages.stream(
                test.target(ModelProtocol.ANTHROPIC_MESSAGES, "/v1/messages", AuthScheme.X_API_KEY),
                AnthropicMessagesRequest(
                    model = "claude-test",
                    system = "system",
                    messages = listOf(AnthropicMessage(AnthropicRole.USER, "hi")),
                    maxTokens = 512,
                    thinking = AnthropicThinking(128),
                ),
            ).toList()
            val result = AnthropicMessagesAccumulator().also { a -> events.forEach(a::accept) }.result()
            assertEquals("answer", result.text)
            assertEquals("reason", result.reasoning)
            assertEquals(listOf("sig"), result.signatures)
            assertEquals(8L, result.usage?.inputTokens)
            assertEquals(3L, result.usage?.outputTokens)
            assertEquals("end_turn", result.finishReason)
            val request = test.server.takeRequest()
            assertEquals("test-secret", request.headers["x-api-key"])
            assertEquals("2023-06-01", request.headers["anthropic-version"])
        }
    }

    @Test
    fun `gemini interactions uses native steps and event types`() = runBlocking {
        HttpsTestServer().use { test ->
            test.enqueueSse(
                """
                event: interaction.created
                data: {"interaction":{"id":"int_1","status":"in_progress"},"event_type":"interaction.created"}

                event: step.start
                data: {"index":0,"step":{"type":"thought"},"event_type":"step.start"}

                event: step.delta
                data: {"index":0,"delta":{"text":"summary","type":"thought_summary"},"event_type":"step.delta"}

                event: step.delta
                data: {"index":0,"delta":{"signature":"sig","type":"thought_signature"},"event_type":"step.delta"}

                event: step.delta
                data: {"index":1,"delta":{"text":"answer","type":"text"},"event_type":"step.delta"}

                event: interaction.completed
                data: {"interaction":{"id":"int_1","status":"completed","usage":{"total_input_tokens":5,"total_output_tokens":2,"total_tokens":7}},"event_type":"interaction.completed"}

                """,
            )
            val gateway = test.gateway()
            val events = gateway.geminiInteractions.stream(
                test.target(ModelProtocol.GEMINI_INTERACTIONS, "/v1beta/interactions", AuthScheme.X_GOOG_API_KEY),
                GeminiInteractionsRequest(
                    model = "gemini-test",
                    input = listOf(
                        GeminiInteractionInputStep.UserInput("hi"),
                        GeminiInteractionInputStep.Thought("previous-signature"),
                    ),
                    systemInstruction = "system",
                    maxOutputTokens = 512,
                    store = false,
                ),
            ).toList()
            val result = GeminiInteractionsAccumulator().also { a -> events.forEach(a::accept) }.result()
            assertEquals("answer", result.text)
            assertEquals("summary", result.reasoning)
            assertEquals(listOf("sig"), result.signatures)
            assertEquals("int_1", result.responseId)
            assertEquals(7L, result.usage?.totalTokens)
            assertTrue(events.any { it is GeminiInteractionsEvent.StepStarted })
            val request = test.server.takeRequest()
            assertEquals("test-secret", request.headers["x-goog-api-key"])
            val body = Json.parseToJsonElement(requireNotNull(request.body).utf8()).jsonObject
            assertTrue(body.containsKey("generation_config"))
            assertEquals("false", body["store"].toString())
        }
    }

    @Test
    fun `generate content resolves model placeholder and separates thoughts signatures`() = runBlocking {
        HttpsTestServer().use { test ->
            test.enqueueSse(
                """
                data: {"candidates":[{"content":{"parts":[{"text":"reason","thought":true},{"thoughtSignature":"sig"},{"text":"answer"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":6,"candidatesTokenCount":4,"thoughtsTokenCount":2,"totalTokenCount":10}}

                """,
            )
            val gateway = test.gateway()
            val base = test.server.url("/v1beta/models/placeholder:streamGenerateContent?alt=sse").toString()
                .replace("placeholder", "{model}")
            val target = ConnectionTarget(
                protocol = ModelProtocol.GEMINI_GENERATE_CONTENT,
                streamEndpoint = base,
                authScheme = AuthScheme.QUERY_KEY,
                credentialRef = "credential",
                authorizedOrigins = setOf(EndpointRules.origin(test.server.url("/"))),
            )
            val events = gateway.geminiGenerateContent.stream(
                target,
                GeminiGenerateContentRequest(
                    model = "models/gemini-test",
                    contents = listOf(GeminiContent(GeminiContentRole.USER, "hi", "old-sig")),
                    systemInstruction = "system",
                    maxOutputTokens = 512,
                    thinking = GeminiThinkingConfig(includeThoughts = true),
                ),
            ).toList()
            val result = GeminiGenerateContentAccumulator().also { a -> events.forEach(a::accept) }.result()
            assertEquals("answer", result.text)
            assertEquals("reason", result.reasoning)
            assertEquals(listOf("sig"), result.signatures)
            assertEquals("STOP", result.finishReason)
            assertEquals(2L, result.usage?.reasoningTokens)
            assertTrue(events.any { it is GeminiGenerateContentEvent.Signature })
            val request = test.server.takeRequest()
            assertTrue(request.target.startsWith("/v1beta/models/gemini-test:streamGenerateContent?"))
            assertTrue(request.target.contains("key=test-secret"))
            assertFalse(requireNotNull(request.body).utf8().contains("temperature"))
        }
    }
}
