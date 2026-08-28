package io.github.zvensmoluya.tavernplayer.connections

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.zvensmoluya.modelgateway.AuthScheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsonConnectionDataStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `legacy connection derives its user facing address`() {
        val decoded = JsonConnectionDataStore.decodeState(
            """
            {
              "schemaVersion": 1,
              "connections": [{
                "id": "legacy",
                "name": "Legacy",
                "templateId": "openai-responses",
                "protocol": "OPENAI_RESPONSES",
                "streamEndpoint": "https://gateway.example.test/v1/responses",
                "catalogEndpoint": "https://wrong.example.test/v1/models",
                "authScheme": "X_API_KEY",
                "credentialRef": "credential_legacy",
                "approvedOrigins": [
                  "https://gateway.example.test:443",
                  "https://wrong.example.test:443"
                ],
                "selectedModel": "model-a"
              }]
            }
            """.trimIndent(),
        )

        val connection = decoded.connections.single()
        assertEquals("https://gateway.example.test/v1", connection.apiAddress)
        assertEquals("https://gateway.example.test/v1/models", connection.catalogEndpoint)
        assertEquals(AuthScheme.BEARER, connection.authScheme)
        assertEquals(setOf("https://gateway.example.test:443"), connection.approvedOrigins)
    }

    @Test
    fun `data store persists versioned connection state`() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            java.io.File(temporaryFolder.root, "connections.preferences_pb")
        }
        try {
            val store = JsonConnectionDataStore(dataStore)
            val template = ConnectionTemplates.openAiChat
            val connection = StoredConnection(
                id = "one",
                name = "Chat",
                templateId = template.id,
                protocol = template.protocol,
                apiAddress = ConnectionEndpointResolver.displayAddress(template.protocol, template.streamEndpoint),
                streamEndpoint = template.streamEndpoint,
                catalogEndpoint = template.catalogEndpoint,
                authScheme = AuthScheme.BEARER,
                credentialRef = "credential_one",
                credentialMask = "•••• 1234",
                approvedOrigins = setOf("https://api.openai.com:443"),
                selectedModel = "chat-model",
            )

            store.update { it.copy(connections = listOf(connection), recentConnectionId = connection.id) }

            assertEquals(GatewayAppState(connections = listOf(connection), recentConnectionId = "one"), store.state.first())
        } finally {
            scope.cancel()
        }
    }
}
