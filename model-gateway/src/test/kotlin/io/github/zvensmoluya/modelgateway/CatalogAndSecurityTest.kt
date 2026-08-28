package io.github.zvensmoluya.modelgateway

import io.github.zvensmoluya.modelgateway.responses.ResponsesInputMessage
import io.github.zvensmoluya.modelgateway.responses.ResponsesRequest
import io.github.zvensmoluya.modelgateway.responses.ResponsesRole
import io.github.zvensmoluya.modelgateway.transport.GatewayTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class CatalogAndSecurityTest {
    @Test
    fun `gemini catalog paginates normalizes ids and records only explicit capabilities`() = runBlocking {
        HttpsTestServer().use { test ->
            test.server.enqueue(
                MockResponse(
                    body = """{"models":[{"name":"models/gemini-a","displayName":"A","inputTokenLimit":100,"outputTokenLimit":20,"supportedGenerationMethods":["generateContent"]}],"nextPageToken":"next"}""",
                ),
            )
            test.server.enqueue(
                MockResponse(
                    body = """{"models":[{"name":"models/gemini-b","displayName":"B"}]}""",
                ),
            )

            val catalog = test.gateway().modelCatalog.list(
                test.target(
                    protocol = ModelProtocol.GEMINI_GENERATE_CONTENT,
                    path = "/v1beta/models/{model}:streamGenerateContent?alt=sse",
                    authScheme = AuthScheme.X_GOOG_API_KEY,
                    catalogPath = "/v1beta/models?pageSize=1000",
                ),
            )

            assertEquals(listOf("gemini-a", "gemini-b"), catalog.models.map { it.id })
            assertEquals(100L, catalog.models.first().inputTokenLimit)
            assertEquals(setOf("generateContent"), catalog.models.first().supportedOperations)
            assertTrue(catalog.models.last().supportedOperations.isEmpty())
            assertFalse(catalog.truncated)
            test.server.takeRequest()
            assertTrue(test.server.takeRequest().target.contains("pageToken=next"))
        }
    }

    @Test
    fun `all authentication schemes use only their controlled location`() = runBlocking {
        val cases = listOf(
            AuthScheme.BEARER to "Authorization",
            AuthScheme.X_API_KEY to "x-api-key",
            AuthScheme.X_GOOG_API_KEY to "x-goog-api-key",
            AuthScheme.QUERY_KEY to null,
            AuthScheme.NONE to null,
        )
        cases.forEach { (scheme, expectedHeader) ->
            HttpsTestServer().use { test ->
                test.enqueueSse("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n")
                val target = test.target(ModelProtocol.OPENAI_RESPONSES, "/responses", scheme)
                test.gateway().responses.stream(
                    target,
                    ResponsesRequest("model", listOf(ResponsesInputMessage(ResponsesRole.USER, "hi"))),
                ).toList()
                val request = test.server.takeRequest()
                when (scheme) {
                    AuthScheme.BEARER -> assertEquals("Bearer test-secret", request.headers[expectedHeader!!])
                    AuthScheme.X_API_KEY, AuthScheme.X_GOOG_API_KEY -> assertEquals("test-secret", request.headers[expectedHeader!!])
                    AuthScheme.QUERY_KEY -> assertEquals("test-secret", request.url.queryParameter("key"))
                    AuthScheme.NONE -> {
                        assertEquals(null, request.headers["Authorization"])
                        assertEquals(null, request.url.queryParameter("key"))
                    }
                }
            }
        }
        Unit
    }

    @Test
    fun `endpoint rules reject insecure or secret bearing urls and enforce placeholder`() {
        assertFails<GatewayException.Configuration> {
            EndpointRules.validate("http://example.com/v1/responses", ModelProtocol.OPENAI_RESPONSES)
        }
        assertFails<GatewayException.Configuration> {
            EndpointRules.validate("https://user:pass@example.com/v1/responses", ModelProtocol.OPENAI_RESPONSES)
        }
        assertFails<GatewayException.Configuration> {
            EndpointRules.validate("https://example.com/v1/responses?key=secret", ModelProtocol.OPENAI_RESPONSES)
        }
        assertFails<GatewayException.Configuration> {
            EndpointRules.validate("https://example.com/v1/responses#fragment", ModelProtocol.OPENAI_RESPONSES)
        }
        assertFails<GatewayException.Configuration> {
            EndpointRules.validate("https://example.com/v1/models/model:streamGenerateContent", ModelProtocol.GEMINI_GENERATE_CONTENT)
        }
        assertFails<GatewayException.Configuration> {
            EndpointRules.validate("https://example.com/v1/{model}", ModelProtocol.OPENAI_RESPONSES)
        }
        assertEquals(
            "gemini-test",
            EndpointRules.normalizeModelId(" models/gemini-test "),
        )
    }

    @Test
    fun `same origin redirect is followed but cross origin redirect is rejected`() = runBlocking {
        HttpsTestServer().use { first ->
            first.server.enqueue(
                MockResponse(
                    code = 307,
                    headers = headersOf("Location", first.server.url("/next").toString()),
                ),
            )
            first.enqueueSse("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n")
            first.gateway().responses.stream(
                first.target(ModelProtocol.OPENAI_RESPONSES, "/start"),
                ResponsesRequest("model", listOf(ResponsesInputMessage(ResponsesRole.USER, "hi"))),
            ).toList()
            assertEquals("/start", first.server.takeRequest().target)
            assertEquals("/next", first.server.takeRequest().target)
        }

        HttpsTestServer().use { first ->
            HttpsTestServer().use { second ->
                first.server.enqueue(
                    MockResponse(
                        code = 307,
                        headers = headersOf("Location", second.server.url("/stolen").toString()),
                    ),
                )
                assertFails<GatewayException.Security> {
                    runBlocking {
                        first.gateway().responses.stream(
                            first.target(ModelProtocol.OPENAI_RESPONSES, "/start"),
                            ResponsesRequest("model", listOf(ResponsesInputMessage(ResponsesRole.USER, "hi"))),
                        ).toList()
                    }
                }
                assertEquals(0, second.server.requestCount)
            }
        }
    }

    @Test
    fun `malformed json and response size are typed protocol failures`() = runBlocking {
        HttpsTestServer().use { test ->
            test.enqueueSse("data: not-json\n")
            assertFails<GatewayException.Protocol> {
                runBlocking {
                    test.gateway().responses.stream(
                        test.target(ModelProtocol.OPENAI_RESPONSES, "/responses"),
                        ResponsesRequest("model", listOf(ResponsesInputMessage(ResponsesRole.USER, "hi"))),
                    ).toList()
                }
            }
        }
        HttpsTestServer().use { test ->
            test.enqueueSse("data: {\"type\":\"${"x".repeat(200)}\"}\n")
            val transport = GatewayTransport(
                credentialResolver = CredentialResolver { SecretValue("secret") },
                client = test.client,
                maxResponseBytes = 64,
            )
            assertFails<GatewayException.ResponseTooLarge> {
                runBlocking {
                    transport.postSse(
                        target = test.target(ModelProtocol.OPENAI_RESPONSES, "/responses"),
                        url = test.server.url("/responses"),
                        jsonBody = "{}",
                    ).toList()
                }
            }
        }
        Unit
    }

    @Test
    fun `http authentication failure retains metadata and redacts echoed credential`() = runBlocking {
        HttpsTestServer().use { test ->
            test.server.enqueue(
                MockResponse(
                    code = 401,
                    headers = headersOf("x-request-id", "req_123"),
                    body = """{"error":{"message":"bad test-secret"}}""",
                ),
            )
            val error = assertFails<GatewayException.AuthenticationFailure> {
                runBlocking {
                    test.gateway().responses.stream(
                        test.target(ModelProtocol.OPENAI_RESPONSES, "/responses"),
                        ResponsesRequest("model", listOf(ResponsesInputMessage(ResponsesRole.USER, "hi"))),
                    ).toList()
                }
            }
            assertEquals(401, error.status)
            assertEquals("req_123", error.requestId)
            assertFalse(error.diagnostic.contains("test-secret"))
            assertTrue(error.diagnostic.contains("[redacted]"))
        }
    }

    @Test
    fun `coroutine cancellation propagates without becoming a gateway error`() = runBlocking {
        HttpsTestServer().use { test ->
            test.server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "text/event-stream")
                    .body("data: {\"type\":\"response.output_text.delta\",\"delta\":\"a\"}\n\n")
                    .bodyDelay(30, TimeUnit.SECONDS)
                    .build(),
            )
            val job = async {
                test.gateway().responses.stream(
                    test.target(ModelProtocol.OPENAI_RESPONSES, "/responses"),
                    ResponsesRequest("model", listOf(ResponsesInputMessage(ResponsesRole.USER, "hi"))),
                ).toList()
            }
            while (test.server.requestCount == 0) yield()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
    }

    @Test
    fun `partial stream parse failure is never retried`() = runBlocking {
        HttpsTestServer().use { test ->
            test.enqueueSse(
                """
                data: {"type":"response.output_text.delta","delta":"partial"}

                data: malformed

                """,
            )
            assertFails<GatewayException.Protocol> {
                runBlocking {
                    test.gateway().responses.stream(
                        test.target(ModelProtocol.OPENAI_RESPONSES, "/responses"),
                        ResponsesRequest("model", listOf(ResponsesInputMessage(ResponsesRole.USER, "hi"))),
                    ).toList()
                }
            }
            assertEquals(1, test.server.requestCount)
        }
    }

    @Test
    fun `read timeout is a typed network failure`() = runBlocking {
        HttpsTestServer().use { test ->
            test.server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "text/event-stream")
                    .body("data: {}\n\n")
                    .bodyDelay(5, TimeUnit.SECONDS)
                    .build(),
            )
            val shortClient = test.client.newBuilder().readTimeout(100, TimeUnit.MILLISECONDS).build()
            val gateway = ModelGateway(CredentialResolver { SecretValue("test-secret") }, shortClient)
            assertFails<GatewayException.Network> {
                runBlocking {
                    gateway.responses.stream(
                        test.target(ModelProtocol.OPENAI_RESPONSES, "/responses"),
                        ResponsesRequest("model", listOf(ResponsesInputMessage(ResponsesRole.USER, "hi"))),
                    ).toList()
                }
            }
        }
        Unit
    }
}

private inline fun <reified T : Throwable> assertFails(block: () -> Unit): T {
    val error = runCatching(block).exceptionOrNull()
    assertTrue("Expected ${T::class.simpleName}, got $error", error is T)
    return error as T
}
