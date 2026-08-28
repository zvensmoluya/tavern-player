package io.github.zvensmoluya.tavernplayer.connections

import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.CredentialResolver
import io.github.zvensmoluya.modelgateway.ModelGateway
import io.github.zvensmoluya.modelgateway.SecretValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRepositoryTest {
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
                streamEndpoint = template.streamEndpoint,
                catalogEndpoint = template.catalogEndpoint,
                authScheme = template.authScheme,
                selectedModel = "gpt-test",
            ),
            newCredential = "secret-1234",
            confirmCredentialReuse = false,
        )

        assertEquals(CredentialStatus.READY, repository.credentialStatus(original))
        assertEquals("•••• 1234", original.credentialMask)
        assertFalse(JsonConnectionDataStore.encodeState(stateStore.value).contains("secret-1234"))

        val moved = repository.save(
            draft = original.toDraft().copy(streamEndpoint = "https://gateway.example.test/responses"),
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
            protocol = io.github.zvensmoluya.modelgateway.ModelProtocol.OPENAI_RESPONSES,
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
        val repository = repository(stateStore, FakeCredentialStore())

        assertTrue(runCatching { repository.refreshModels(connection.id) }.isFailure)
        assertEquals(cached, repository.state.first().connections.single().modelCache)
    }

    private fun repository(
        stateStore: FakeConnectionStateStore,
        credentials: FakeCredentialStore,
    ): ConnectionRepository {
        val gateway = ModelGateway(
            CredentialResolver { ref -> credentials.getOrNull(ref)?.let(::SecretValue) },
        )
        return ConnectionRepository(stateStore, credentials, gateway, clockMillis = { 99L })
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
    streamEndpoint = streamEndpoint,
    catalogEndpoint = catalogEndpoint,
    authScheme = authScheme,
    selectedModel = selectedModel,
)
