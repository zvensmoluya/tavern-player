package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.CredentialResolver
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.ModelGateway
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.modelgateway.catalog.ModelCatalog
import io.github.zvensmoluya.tavernplayer.connections.ConnectionEndpointResolver
import io.github.zvensmoluya.tavernplayer.connections.ConnectionRepository
import io.github.zvensmoluya.tavernplayer.connections.ConnectionStateStore
import io.github.zvensmoluya.tavernplayer.connections.CredentialStore
import io.github.zvensmoluya.tavernplayer.connections.GatewayAppState
import io.github.zvensmoluya.tavernplayer.connections.ModelCache
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.connections.StoredModel
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Generation only ever used the endpoint stored at save time, so a provider that implements just one
 * of the candidate routes stayed broken forever. These tests drive the real transport against a local
 * HTTPS server: the remembered endpoint is tried first, HTTP 404 advances to the next candidate, each
 * candidate is tried at most once, the route that answered is remembered without touching the model
 * cache, a snapshot from a moved connection never writes its route back, and no other failure
 * triggers a second request.
 */
class ConversationRouteFallbackTest {
    @Test
    fun `generation falls back to the versioned route and reuses it afterwards`() = runTest {
        RouteServer().use { server ->
            val stateStore = FakeConnectionStateStore()
            val cache = ModelCache(
                models = listOf(
                    StoredModel(
                        id = "test-model",
                        name = "Test Model",
                        inputTokenLimit = 128_000,
                        outputTokenLimit = 8_000,
                    ),
                ),
                refreshedAtEpochMillis = 7L,
            )
            val connection = server.connection(modelCache = cache)
            stateStore.seed(GatewayAppState(connections = listOf(connection)))
            val generator = server.generator(stateStore)

            server.enqueueHttpFailure(404)
            server.enqueueSse(responsesStream("第一次回答"))

            val first = generator.stream(connection, plan()).toList()

            assertEquals("第一次回答", first.filterIsInstance<GenerationEvent.TextDelta>().single().text)
            assertEquals("completed", first.filterIsInstance<GenerationEvent.Finished>().single().reason)
            val routeDiagnostic = first.filterIsInstance<GenerationEvent.Diagnostic>()
                .single { "HTTP 404" in it.summary }
            assertTrue(routeDiagnostic.summary.contains("/v1/responses"))
            assertFalse(routeDiagnostic.summary.contains("https://"))
            assertEquals(listOf("/responses", "/v1/responses"), server.takePaths(2))

            val remembered = stateStore.value.connections.single()
            assertEquals(server.url("/v1/responses"), remembered.streamEndpoint)
            assertEquals(server.url("/v1/models"), remembered.catalogEndpoint)
            assertEquals(cache, remembered.modelCache)
            assertEquals(connection.selectedModel, remembered.selectedModel)

            server.enqueueSse(responsesStream("第二次回答"))

            val second = generator.stream(remembered, plan()).toList()

            assertEquals("第二次回答", second.filterIsInstance<GenerationEvent.TextDelta>().single().text)
            assertEquals(listOf("/v1/responses"), server.takePaths(1))
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `status 400 and 500 are reported unchanged without probing another route`() = runTest {
        for (status in listOf(400, 500)) {
            RouteServer().use { server ->
                val stateStore = FakeConnectionStateStore()
                val connection = server.connection()
                stateStore.seed(GatewayAppState(connections = listOf(connection)))

                server.enqueueHttpFailure(status)
                server.enqueueSse(responsesStream("不应该到达"))

                val failure = runCatching {
                    server.generator(stateStore).stream(connection, plan()).toList()
                }.exceptionOrNull()
                val httpFailure = failure as? GatewayException.HttpFailure

                assertEquals(status, httpFailure?.status ?: -1)
                assertEquals("route failure $status", httpFailure?.diagnostic)
                assertEquals(1, server.requestCount)
                assertEquals(listOf("/responses"), server.takePaths(1))
                assertEquals(connection, stateStore.value.connections.single())
            }
        }
    }

    @Test
    fun `rate limit and authentication failures never advance to another candidate`() = runTest {
        for (status in listOf(429, 401, 403)) {
            RouteServer().use { server ->
                val stateStore = FakeConnectionStateStore()
                val connection = server.connection()
                stateStore.seed(GatewayAppState(connections = listOf(connection)))

                server.enqueueHttpFailure(status)
                server.enqueueSse(responsesStream("不应该到达"))

                val failure = runCatching {
                    server.generator(stateStore).stream(connection, plan()).toList()
                }.exceptionOrNull()

                val expectedType = if (status == 429) {
                    GatewayException.RateLimited::class
                } else {
                    GatewayException.AuthenticationFailure::class
                }
                assertEquals(expectedType, failure?.let { it::class })
                assertEquals(status, (failure as? GatewayException.HttpFailure)?.status ?: -1)
                assertEquals(1, server.requestCount)
                assertEquals(listOf("/responses"), server.takePaths(1))
                assertEquals(connection, stateStore.value.connections.single())
            }
        }
    }

    @Test
    fun `address that already carries a version never probes another route`() = runTest {
        RouteServer().use { server ->
            val stateStore = FakeConnectionStateStore()
            val connection = server.connection(
                apiAddress = server.url("/v1"),
                streamPath = "/v1/responses",
                catalogPath = "/v1/models",
            )
            stateStore.seed(GatewayAppState(connections = listOf(connection)))
            assertEquals(
                1,
                ConnectionEndpointResolver
                    .candidates(ModelProtocol.OPENAI_RESPONSES, connection.apiAddress)
                    .size,
            )

            server.enqueueHttpFailure(404)
            server.enqueueSse(responsesStream("不应该到达"))

            val failure = runCatching {
                server.generator(stateStore).stream(connection, plan()).toList()
            }.exceptionOrNull()

            assertEquals(404, (failure as? GatewayException.HttpFailure)?.status ?: -1)
            assertEquals(1, server.requestCount)
            assertEquals(listOf("/v1/responses"), server.takePaths(1))
            assertEquals(connection, stateStore.value.connections.single())
        }
    }

    @Test
    fun `the exhausted candidate list reports the last failure unchanged`() = runTest {
        RouteServer().use { server ->
            val stateStore = FakeConnectionStateStore()
            val connection = server.connection()
            stateStore.seed(GatewayAppState(connections = listOf(connection)))

            server.enqueueHttpFailure(404)
            server.enqueueHttpFailure(404)

            val failure = runCatching {
                server.generator(stateStore).stream(connection, plan()).toList()
            }.exceptionOrNull()

            assertEquals(404, (failure as? GatewayException.HttpFailure)?.status ?: -1)
            assertEquals("route failure 404", (failure as? GatewayException.HttpFailure)?.diagnostic)
            assertEquals(listOf("/responses", "/v1/responses"), server.takePaths(2))
            assertEquals(connection, stateStore.value.connections.single())
        }
    }

    @Test
    fun `a failing remembered route walks the remaining candidates once`() = runTest {
        RouteServer().use { server ->
            val stateStore = FakeConnectionStateStore()
            // The connection already learned the versioned route, while the resolver still offers the
            // unversioned candidate first: the remembered route must not be queued a second time.
            val connection = server.connection(streamPath = "/v1/responses", catalogPath = "/v1/models")
            stateStore.seed(GatewayAppState(connections = listOf(connection)))

            server.enqueueHttpFailure(404)
            server.enqueueHttpFailure(404)
            server.enqueueHttpFailure(404)

            val failure = runCatching {
                server.generator(stateStore).stream(connection, plan()).toList()
            }.exceptionOrNull()

            assertEquals(404, (failure as? GatewayException.HttpFailure)?.status ?: -1)
            val deadRoutes = server.takePaths(2)
            assertEquals(listOf("/v1/responses", "/responses"), deadRoutes)
            assertEquals(deadRoutes.distinct(), deadRoutes)
            assertEquals(2, server.requestCount)
            assertEquals(connection, stateStore.value.connections.single())
        }
    }

    @Test
    fun `the candidate that answers after a dead remembered route becomes the route`() = runTest {
        RouteServer().use { server ->
            val stateStore = FakeConnectionStateStore()
            val connection = server.connection(streamPath = "/v1/responses", catalogPath = "/v1/models")
            stateStore.seed(GatewayAppState(connections = listOf(connection)))

            server.enqueueHttpFailure(404)
            server.enqueueSse(responsesStream("回退成功"))

            val events = server.generator(stateStore).stream(connection, plan()).toList()

            assertEquals("回退成功", events.filterIsInstance<GenerationEvent.TextDelta>().single().text)
            val routes = server.takePaths(2)
            assertEquals(listOf("/v1/responses", "/responses"), routes)
            assertEquals(routes.distinct(), routes)
            assertEquals(2, server.requestCount)
            val learned = stateStore.value.connections.single()
            assertEquals(server.url("/responses"), learned.streamEndpoint)
            assertEquals(server.url("/models"), learned.catalogEndpoint)
        }
    }

    @Test
    fun `a failed route write never fails a generation that already answered`() = runTest {
        RouteServer().use { server ->
            val stateStore = FakeConnectionStateStore()
            val connection = server.connection()
            stateStore.seed(GatewayAppState(connections = listOf(connection)))

            server.enqueueHttpFailure(404)
            server.enqueueSse(responsesStream("回答已到达"))

            stateStore.failUpdates = true

            val events = server.generator(stateStore).stream(connection, plan()).toList()

            assertEquals("回答已到达", events.filterIsInstance<GenerationEvent.TextDelta>().single().text)
            assertEquals(listOf("/responses", "/v1/responses"), server.takePaths(2))
            // The route stays unremembered; the next request simply rediscovers it.
            assertEquals(connection, stateStore.value.connections.single())
        }
    }

    @Test
    fun `a snapshot taken before the address moved never writes the learned route back`() = runTest {
        RouteServer().use { server ->
            val stateStore = FakeConnectionStateStore()
            val snapshot = server.connection()
            val moved = snapshot.copy(
                apiAddress = "https://moved.example.test",
                streamEndpoint = "https://moved.example.test/responses",
                catalogEndpoint = "https://moved.example.test/models",
            )
            stateStore.seed(GatewayAppState(connections = listOf(moved)))

            // A generation that started before the move still falls back on the old host.
            server.enqueueHttpFailure(404)
            server.enqueueSse(responsesStream("旧地址的回答"))

            val events = server.generator(stateStore).stream(snapshot, plan()).toList()

            assertEquals("旧地址的回答", events.filterIsInstance<GenerationEvent.TextDelta>().single().text)
            assertEquals(listOf("/responses", "/v1/responses"), server.takePaths(2))
            // The stored connection now points at another host: the route learned from the stale
            // snapshot must not resurrect the previous one.
            assertEquals(moved, stateStore.value.connections.single())
        }
    }

    @Test
    fun `token validation walks the same candidates and remembers the route`() = runTest {
        RouteServer(forwardHost = "api.anthropic.com").use { server ->
            val stateStore = FakeConnectionStateStore()
            val connection = server.connection(
                protocol = ModelProtocol.ANTHROPIC_MESSAGES,
                apiAddress = "https://api.anthropic.com",
                endpointBase = "https://api.anthropic.com",
                streamPath = "/messages",
                catalogPath = "/models",
                model = "claude-test",
            )
            stateStore.seed(GatewayAppState(connections = listOf(connection)))

            server.enqueueHttpFailure(404)
            server.enqueueJson("""{"input_tokens":37}""")

            val validation = server.generator(stateStore).validateTokens(connection, plan())

            assertEquals(37, validation?.inputTokens)
            assertEquals(TokenCountQuality.EXACT, validation?.quality)
            assertEquals("anthropic-count-tokens", validation?.counter)
            assertEquals(
                listOf("/messages/count_tokens", "/v1/messages/count_tokens"),
                server.takePaths(2),
            )
            val remembered = stateStore.value.connections.single()
            assertEquals("https://api.anthropic.com/v1/messages", remembered.streamEndpoint)
            assertEquals("https://api.anthropic.com/v1/models", remembered.catalogEndpoint)

            server.enqueueJson("""{"input_tokens":31}""")

            val second = server.generator(stateStore).validateTokens(remembered, plan())

            assertEquals(31, second?.inputTokens)
            assertEquals(listOf("/v1/messages/count_tokens"), server.takePaths(1))
            assertEquals(3, server.requestCount)
        }
    }

    private fun plan() = GenerationPlan(
        messages = listOf(
            PreparedMessage(MessageRole.SYSTEM, "你在测试中扮演向导。", PromptOrigin("chat-history", listOf("system"))),
            PreparedMessage(MessageRole.USER, "继续吧。", PromptOrigin("chat-history", listOf("user"))),
        ),
        maxOutputTokens = 512,
        declaredContextTokens = 4_096,
        assistantPrefill = "",
        presetId = "preset",
        presetName = "Preset",
        diagnostics = emptyList(),
        trace = emptyList(),
    )

    private fun responsesStream(text: String): String = buildString {
        append("event: response.output_text.delta\n")
        append("data: {\"type\":\"response.output_text.delta\",\"delta\":\"$text\"}\n\n")
        append("event: response.completed\n")
        append("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\"}}\n\n")
    }

    /**
     * HTTPS server plus the gateway wired to it. [forwardHost] rewrites requests aimed at a provider
     * host to the local server, so provider-specific paths such as count_tokens stay reachable.
     */
    private class RouteServer(private val forwardHost: String? = null) : AutoCloseable {
        private val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        private val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        private val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        private val server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory())
            start()
        }
        private val localBase = server.url("/")
        private val localAddress = localBase.toString().trimEnd('/')
        private val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .apply { forwardHost?.let { addInterceptor(::forwardToServer) } }
            .build()

        private fun forwardToServer(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (forwardHost == null || request.url.host != forwardHost) return chain.proceed(request)
            val forwarded = request.url.newBuilder()
                .scheme(localBase.scheme)
                .host(localBase.host)
                .port(localBase.port)
                .build()
            return chain.proceed(request.newBuilder().url(forwarded).build())
        }

        fun url(path: String): String = server.url(path).toString()

        fun connection(
            protocol: ModelProtocol = ModelProtocol.OPENAI_RESPONSES,
            apiAddress: String = localAddress,
            endpointBase: String = localAddress,
            streamPath: String = "/responses",
            catalogPath: String = "/models",
            model: String = "test-model",
            modelCache: ModelCache = ModelCache(),
        ) = StoredConnection(
            id = "route-fallback",
            name = "Route fallback",
            templateId = "test",
            protocol = protocol,
            apiAddress = apiAddress,
            streamEndpoint = endpointBase + streamPath,
            catalogEndpoint = endpointBase + catalogPath,
            authScheme = AuthScheme.NONE,
            credentialRef = null,
            credentialMask = null,
            approvedOrigins = emptySet(),
            selectedModel = model,
            modelCache = modelCache,
        )

        fun generator(stateStore: ConnectionStateStore) = ModelGatewayConversationGenerator(
            gateway = ModelGateway(credentialResolver = CredentialResolver { null }, client = client),
            repository = ConnectionRepository(
                stateStore = stateStore,
                credentialStore = NoCredentials,
                catalogLoader = { ModelCatalog(emptyList(), truncated = false) },
            ),
        )

        fun enqueueHttpFailure(status: Int) {
            server.enqueue(MockResponse(code = status, body = "route failure $status"))
        }

        fun enqueueJson(body: String) {
            server.enqueue(
                MockResponse(code = 200, headers = headersOf("Content-Type", "application/json"), body = body),
            )
        }

        fun enqueueSse(body: String) {
            server.enqueue(
                MockResponse(
                    code = 200,
                    headers = headersOf("Content-Type", "text/event-stream; charset=utf-8"),
                    body = body,
                ),
            )
        }

        /** Fails with the queue size instead of blocking forever when a request never arrives. */
        fun takePaths(count: Int): List<String> = List(count) { index ->
            val request = server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertNotNull(
                "第 ${index + 1} 个请求在 ${REQUEST_TIMEOUT_SECONDS}s 内没有到达" +
                    "（已收到 $requestCount 个请求）",
                request,
            )
            request!!.url.encodedPath
        }

        val requestCount: Int get() = server.requestCount

        override fun close() = server.close()

        private companion object {
            const val REQUEST_TIMEOUT_SECONDS = 5L
        }
    }
}

private class FakeConnectionStateStore : ConnectionStateStore {
    private val mutable = MutableStateFlow(GatewayAppState())
    override val state: Flow<GatewayAppState> = mutable
    val value: GatewayAppState get() = mutable.value

    /** Simulates a state store that cannot be written, the way a failing file write behaves. */
    var failUpdates = false

    fun seed(initial: GatewayAppState) {
        mutable.value = initial
    }

    override suspend fun update(transform: (GatewayAppState) -> GatewayAppState) {
        if (failUpdates) throw IOException("state write failed")
        mutable.value = transform(mutable.value)
    }
}

private object NoCredentials : CredentialStore {
    override suspend fun put(credentialId: String, secret: String) = Unit
    override suspend fun getOrNull(credentialId: String): String? = null
    override suspend fun delete(credentialId: String) = Unit
    override suspend fun contains(credentialId: String): Boolean = false
}
