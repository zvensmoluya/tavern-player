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
