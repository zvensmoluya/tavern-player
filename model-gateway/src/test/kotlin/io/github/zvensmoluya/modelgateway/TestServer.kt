package io.github.zvensmoluya.modelgateway

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

internal class HttpsTestServer : AutoCloseable {
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
    val server = MockWebServer().apply {
        useHttps(serverCertificates.sslSocketFactory())
        start()
    }
    val client: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .build()

    fun enqueueSse(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse(
                code = code,
                headers = headersOf("Content-Type", "text/event-stream; charset=utf-8"),
                body = body.trimIndent() + "\n",
            ),
        )
    }

    fun target(
        protocol: ModelProtocol,
        path: String,
        authScheme: AuthScheme = AuthScheme.BEARER,
        catalogPath: String? = null,
    ): ConnectionTarget {
        val endpoint = server.url(path.replace("{model}", "placeholder-model"))
        val endpointTemplate = endpoint.toString().replace("placeholder-model", "{model}")
        val catalog = catalogPath?.let(server::url)
        return ConnectionTarget(
            protocol = protocol,
            streamEndpoint = endpointTemplate,
            catalogEndpoint = catalog?.toString(),
            authScheme = authScheme,
            credentialRef = if (authScheme == AuthScheme.NONE) null else "credential",
            authorizedOrigins = buildSet {
                add(EndpointRules.origin(endpoint))
                catalog?.let { add(EndpointRules.origin(it)) }
            },
        )
    }

    fun gateway(secret: String = "test-secret") = ModelGateway(
        credentialResolver = CredentialResolver { SecretValue(secret) },
        client = client,
    )

    override fun close() = server.close()
}
