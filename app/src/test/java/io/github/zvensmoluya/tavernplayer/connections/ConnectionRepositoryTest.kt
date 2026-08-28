package io.github.zvensmoluya.tavernplayer.connections

import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.ConnectionTarget
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.modelgateway.catalog.ModelCatalog
import io.github.zvensmoluya.modelgateway.catalog.ModelDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRepositoryTest {
    @Test
    fun `api key authentication is inferred from the selected protocol`() = runBlocking {
        ConnectionTemplates.protocols.forEachIndexed { index, template ->
            val stateStore = FakeConnectionStateStore()
            val repository = repository(stateStore, FakeCredentialStore())

            val stored = repository.save(
                draft = ConnectionDraft(
                    id = "protocol-$index",
                    name = template.displayName,
                    templateId = template.id,
                    protocol = template.protocol,
                    apiAddress = address(template),
                    selectedModel = "test-model",
                ),
                newCredential = "secret-$index",
                confirmCredentialReuse = false,
            )

            assertEquals(template.authScheme, stored.authScheme)
            assertEquals(CredentialStatus.READY, repository.credentialStatus(stored))
        }
    }

    @Test
    fun `connection can be saved before a model is selected`() = runBlocking {
        val stateStore = FakeConnectionStateStore()
        val repository = repository(stateStore, FakeCredentialStore())
        val template = ConnectionTemplates.openAiResponses

        val stored = repository.save(
            draft = ConnectionDraft(
                id = "new",
                name = "OpenAI Responses",
                templateId = template.id,
                protocol = template.protocol,
                apiAddress = address(template),
                selectedModel = "",
            ),
            newCredential = "",
            confirmCredentialReuse = false,
        )

        assertEquals("", stored.selectedModel)
        assertEquals(AuthScheme.NONE, stored.authScheme)
        assertEquals(CredentialStatus.NOT_REQUIRED, repository.credentialStatus(stored))
        assertEquals(stored, stateStore.value.connections.single())
    }

    @Test
    fun `save update origin confirmation missing restore and delete clean up`() = runBlocking {
        val stateStore = FakeConnectionStateStore()
        val credentials = FakeCredentialStore()
        val repository = repository(stateStore, credentials)
        val template = ConnectionTemplates.openAiResponses
        val original = repository.save(
            draft = ConnectionDraft(
                id = "one",
                name = "OpenAI",
                templateId = template.id,
                protocol = template.protocol,
                apiAddress = address(template),
                selectedModel = "gpt-test",
            ),
            newCredential = "secret-1234",
            confirmCredentialReuse = false,
        )

        assertEquals(CredentialStatus.READY, repository.credentialStatus(original))
        assertEquals("•••• 1234", original.credentialMask)
        assertFalse(JsonConnectionDataStore.encodeState(stateStore.value).contains("secret-1234"))

        val moved = repository.save(
            draft = original.toDraft().copy(apiAddress = "https://gateway.example.test"),
            newCredential = "",
            confirmCredentialReuse = false,
        )
        assertEquals(CredentialStatus.ORIGIN_CONFIRMATION_REQUIRED, repository.credentialStatus(moved))
        assertEquals("secret-1234", credentials.getOrNull(requireNotNull(moved.credentialRef)))

        val approved = repository.save(moved.toDraft(), "", confirmCredentialReuse = true)
        assertEquals(CredentialStatus.READY, repository.credentialStatus(approved))

        credentials.delete(requireNotNull(approved.credentialRef))
        assertEquals(CredentialStatus.MISSING, repository.credentialStatus(approved))

        repository.delete(approved.id)
        assertTrue(stateStore.value.connections.isEmpty())
        assertFalse(credentials.contains(requireNotNull(approved.credentialRef)))
    }

    @Test
    fun `versioned json round trip keeps model cache but never needs raw provider json`() {
        val state = GatewayAppState(
            connections = listOf(
                StoredConnection(
                    id = "one",
                    name = "Gemini",
                    templateId = ConnectionTemplates.geminiInteractions.id,
                    protocol = ConnectionTemplates.geminiInteractions.protocol,
                    apiAddress = address(ConnectionTemplates.geminiInteractions),
                    streamEndpoint = ConnectionTemplates.geminiInteractions.streamEndpoint,
                    catalogEndpoint = ConnectionTemplates.geminiInteractions.catalogEndpoint,
                    authScheme = AuthScheme.X_GOOG_API_KEY,
                    credentialRef = "credential_one",
                    credentialMask = "•••• 1234",
                    approvedOrigins = setOf("https://generativelanguage.googleapis.com:443"),
                    selectedModel = "gemini-test",
                    modelCache = ModelCache(
                        models = listOf(
                            StoredModel(
                                id = "gemini-test",
                                name = "Gemini Test",
                                inputTokenLimit = 100,
                                outputTokenLimit = 20,
                                supportedOperations = setOf("generateContent"),
                            ),
                        ),
                        refreshedAtEpochMillis = 1234,
                    ),
                ),
            ),
            recentConnectionId = "one",
        )

        val decoded = JsonConnectionDataStore.decodeState(JsonConnectionDataStore.encodeState(state))

        assertEquals(state, decoded)
        assertEquals(1, decoded.schemaVersion)
        assertNull(decoded.connections.single().modelCache.models.single().asDescriptor().raw["unknown"])
    }

    @Test
    fun `failed catalog refresh keeps last successful cache`() = runBlocking {
        val cached = ModelCache(
            models = listOf(StoredModel("manual-model", "Cached")),
            refreshedAtEpochMillis = 42L,
        )
        val connection = StoredConnection(
            id = "offline",
            name = "Offline",
            templateId = "custom",
            protocol = ModelProtocol.OPENAI_RESPONSES,
            apiAddress = "https://127.0.0.1:1",
            streamEndpoint = "https://127.0.0.1:1/responses",
            catalogEndpoint = "https://127.0.0.1:1/models",
            authScheme = AuthScheme.NONE,
            credentialRef = null,
            credentialMask = null,
            approvedOrigins = emptySet(),
            selectedModel = "manual-model",
            modelCache = cached,
        )
        val stateStore = FakeConnectionStateStore(GatewayAppState(connections = listOf(connection)))
        val repository = repository(stateStore, FakeCredentialStore()) {
            throw GatewayException.Network(IOException("offline"))
        }

        val result = repository.refreshModels(connection.id)
        assertEquals(
            ModelDiscoveryFailureKind.UNREACHABLE,
            (result as ModelDiscoveryResult.Unavailable).failure.kind,
        )
        assertEquals(cached, repository.state.first().connections.single().modelCache)
    }

    @Test
    fun `model discovery retries only route failures and remembers the working route`() = runBlocking {
        val stateStore = FakeConnectionStateStore()
        val attempted = mutableListOf<String>()
        val repository = repository(stateStore, FakeCredentialStore()) { target ->
            val endpoint = requireNotNull(target.catalogEndpoint)
            attempted += endpoint
            if (endpoint.endsWith("/models") && !endpoint.endsWith("/v1/models")) {
                throw GatewayException.HttpFailure(404, null, null, "not found")
            }
            ModelCatalog(
                models = listOf(ModelDescriptor("model-a", raw = JsonObject(emptyMap()))),
                truncated = false,
            )
        }
        val stored = repository.save(
            draft = ConnectionDraft(
                id = "fallback",
                name = "Fallback",
                templateId = ConnectionTemplates.openAiResponses.id,
                protocol = ModelProtocol.OPENAI_RESPONSES,
                apiAddress = "https://gateway.example.test",
                selectedModel = "",
            ),
            newCredential = "",
            confirmCredentialReuse = false,
        )

        val result = repository.refreshModels(stored.id)

        assertTrue(result is ModelDiscoveryResult.Found)
        assertEquals(
            listOf(
                "https://gateway.example.test/models",
                "https://gateway.example.test/v1/models",
            ),
            attempted,
        )
        assertEquals(
            "https://gateway.example.test/v1/responses",
            stateStore.value.connections.single().streamEndpoint,
        )
    }

    @Test
    fun `authentication failure does not probe another route`() = runBlocking {
        val stateStore = FakeConnectionStateStore()
        val credentials = FakeCredentialStore()
        var attempts = 0
        val repository = repository(stateStore, credentials) {
            attempts += 1
            throw GatewayException.AuthenticationFailure(401, null, null, "unauthorized")
        }
        val stored = repository.save(
            draft = ConnectionDraft(
                id = "auth",
                name = "Auth",
                templateId = ConnectionTemplates.openAiResponses.id,
                protocol = ModelProtocol.OPENAI_RESPONSES,
                apiAddress = "https://gateway.example.test",
                selectedModel = "",
            ),
            newCredential = "secret",
            confirmCredentialReuse = false,
        )

        val result = repository.refreshModels(stored.id)

        assertEquals(1, attempts)
        assertEquals(
            ModelDiscoveryFailureKind.AUTHENTICATION,
            (result as ModelDiscoveryResult.Unavailable).failure.kind,
        )
    }

    private fun repository(
        stateStore: FakeConnectionStateStore,
        credentials: FakeCredentialStore,
        catalogLoader: suspend (ConnectionTarget) -> ModelCatalog = {
            ModelCatalog(emptyList(), truncated = false)
        },
    ): ConnectionRepository {
        return ConnectionRepository(stateStore, credentials, catalogLoader, clockMillis = { 99L })
    }
}

private class FakeConnectionStateStore(
    initial: GatewayAppState = GatewayAppState(),
) : ConnectionStateStore {
    private val mutable = MutableStateFlow(initial)
    override val state: Flow<GatewayAppState> = mutable
    val value: GatewayAppState get() = mutable.value

    override suspend fun update(transform: (GatewayAppState) -> GatewayAppState) {
        mutable.value = transform(mutable.value)
    }
}

private class FakeCredentialStore : CredentialStore {
    private val secrets = mutableMapOf<String, String>()
    override suspend fun put(credentialId: String, secret: String) { secrets[credentialId] = secret }
    override suspend fun getOrNull(credentialId: String): String? = secrets[credentialId]
    override suspend fun delete(credentialId: String) { secrets.remove(credentialId) }
    override suspend fun contains(credentialId: String): Boolean = credentialId in secrets
}

private fun StoredConnection.toDraft() = ConnectionDraft(
    id = id,
    name = name,
    templateId = templateId,
    protocol = protocol,
    apiAddress = apiAddress,
    selectedModel = selectedModel,
)

private fun address(template: ConnectionTemplate): String =
    ConnectionEndpointResolver.displayAddress(template.protocol, template.streamEndpoint)
