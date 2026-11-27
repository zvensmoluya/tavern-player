package io.github.zvensmoluya.tavernplayer.connections

import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.CredentialResolver
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.SecretValue
import io.github.zvensmoluya.modelgateway.ModelGateway
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.modelgateway.catalog.ModelCatalog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe used to request the stored endpoint only, so "test connection" disagreed with chat for a
 * provider that implements just one of the candidate routes. These tests drive the real transport
 * against a local HTTPS server: the probe falls back the same way, reports the route change, and
 * remembers the endpoint that answered.
 */
class ProbeServiceRouteFallbackTest {
    @Test
    fun `probe falls back to the versioned route and remembers it`() = runTest {
        ProbeServer().use { server ->
            val stateStore = FakeProbeStateStore()
            val connection = server.connection()
            stateStore.seed(GatewayAppState(connections = listOf(connection)))

            server.enqueueHttpFailure(404)
            server.enqueueSse(responsesStream("探测回答"))

            val events = server.probeService(stateStore)
                .stream(connection, ProbeInput(system = "你是测试助手。", user = "回答一个字。"))
                .toList()

            assertEquals("探测回答", events.filterIsInstance<ProbeEvent.Text>().single().delta)
            assertEquals("completed", events.filterIsInstance<ProbeEvent.Finished>().single().reason)
            assertTrue(
                events.filterIsInstance<ProbeEvent.Diagnostic>().any { "HTTP 404" in it.summary },
            )
            assertEquals(listOf("/responses", "/v1/responses"), server.takePaths(2))
            val remembered = stateStore.value.connections.single()
            assertEquals(server.url("/v1/responses"), remembered.streamEndpoint)
            assertEquals(server.url("/v1/models"), remembered.catalogEndpoint)
        }
    }

    @Test
    fun `probe reports a failure unchanged when no candidate answers`() = runTest {
        ProbeServer().use { server ->
            val stateStore = FakeProbeStateStore()
            val connection = server.connection()
            stateStore.seed(GatewayAppState(connections = listOf(connection)))

            server.enqueueHttpFailure(404)
            server.enqueueHttpFailure(404)

            val failure = runCatching {
                server.probeService(stateStore)
                    .stream(connection, ProbeInput(system = "你是测试助手。", user = "回答一个字。"))
                    .toList()
            }.exceptionOrNull()

            val httpFailure = failure as? GatewayException.HttpFailure
            assertEquals(404, httpFailure?.status ?: -1)
            assertEquals(listOf("/responses", "/v1/responses"), server.takePaths(2))
            assertEquals(connection, stateStore.value.connections.single())
        }
    }

    @Test
    fun `a denied probe retries the same saved key in the same process`() = runTest {
        ProbeServer().use { server ->
            val credentials = object : CredentialStore by NoProbeCredentials {
                override suspend fun getOrNull(credentialId: String) = "fixture-key"
                override suspend fun contains(credentialId: String) = true
            }
            val stateStore = FakeProbeStateStore()
            val connection = server.connection().copy(
                protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                streamEndpoint = server.url("/api/v1/chat/completions"),
                apiAddress = server.url("/api/v1"),
                authScheme = AuthScheme.BEARER,
                credentialRef = "fixture",
            ).let { it.copy(approvedOrigins = it.currentOrigins()) }
            stateStore.seed(GatewayAppState(connections = listOf(connection)))
            val service = server.probeService(stateStore, credentials)
            val input = ProbeInput("", "Hello")
            for (status in listOf(401, 403)) {
                server.enqueueHttpFailure(status)
                val error = runCatching { service.stream(connection, input).toList() }.exceptionOrNull()
                assertEquals(status, (error as GatewayException.AuthenticationFailure).status)
            }
            server.enqueueSse("""data: {"choices":[{"delta":{"content":"OK"},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n")
            assertEquals("OK", service.stream(connection, input).toList().filterIsInstance<ProbeEvent.Text>().single().delta)
            repeat(3) {
                val request = server.nextRequest()
                assertEquals("/api/v1/chat/completions", request.url.encodedPath)
                assertEquals("Bearer fixture-key", request.headers["Authorization"])
            }
        }
    }

    private fun responsesStream(text: String): String = buildString {
        append("event: response.output_text.delta\n")
        append("data: {\"type\":\"response.output_text.delta\",\"delta\":\"$text\"}\n\n")
        append("event: response.completed\n")
        append("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\"}}\n\n")
    }

    /** HTTPS server plus the probe wired to it, mirroring the production transport setup. */
    private class ProbeServer : AutoCloseable {
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
        private val localAddress = server.url("/").toString().trimEnd('/')
        private val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()

        fun url(path: String): String = server.url(path).toString()

        fun connection() = StoredConnection(
            id = "probe-fallback",
            name = "Probe fallback",
            templateId = "test",
            protocol = ModelProtocol.OPENAI_RESPONSES,
            apiAddress = localAddress,
            streamEndpoint = localAddress + "/responses",
            catalogEndpoint = localAddress + "/models",
            authScheme = AuthScheme.NONE,
            credentialRef = null,
            approvedOrigins = emptySet(),
            selectedModel = "test-model",
        )

        fun probeService(stateStore: ConnectionStateStore, credentials: CredentialStore = NoProbeCredentials) = ProbeService(
            gateway = ModelGateway(credentialResolver = CredentialResolver { credentials.getOrNull(it)?.let(::SecretValue) }, client = client),
            repository = ConnectionRepository(
                stateStore = stateStore,
                credentialStore = credentials,
                catalogLoader = { ModelCatalog(emptyList(), truncated = false) },
            ),
        )

        fun enqueueHttpFailure(status: Int) {
            server.enqueue(MockResponse(code = status, body = "route failure $status"))
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
        fun nextRequest() = requireNotNull(server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "等待请求超时，已收到 ${server.requestCount} 个请求"
        }

        fun takePaths(count: Int): List<String> = List(count) { nextRequest().url.encodedPath }

        override fun close() = server.close()

        private companion object {
            const val REQUEST_TIMEOUT_SECONDS = 5L
        }
    }
}

private class FakeProbeStateStore : ConnectionStateStore {
    private val mutable = MutableStateFlow(GatewayAppState())
    override val state: Flow<GatewayAppState> = mutable
    val value: GatewayAppState get() = mutable.value

    fun seed(initial: GatewayAppState) {
        mutable.value = initial
    }

    override suspend fun update(transform: (GatewayAppState) -> GatewayAppState) {
        mutable.value = transform(mutable.value)
    }
}

private object NoProbeCredentials : CredentialStore {
    override suspend fun put(credentialId: String, secret: String) = Unit
    override suspend fun getOrNull(credentialId: String): String? = null
    override suspend fun delete(credentialId: String) = Unit
    override suspend fun contains(credentialId: String): Boolean = false
}
