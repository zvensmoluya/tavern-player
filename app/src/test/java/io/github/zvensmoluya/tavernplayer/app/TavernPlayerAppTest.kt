package io.github.zvensmoluya.tavernplayer.app

import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.tavernplayer.connections.ModelCache
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.conversation.ChatUiState
import io.github.zvensmoluya.tavernplayer.conversation.DemoConversationContent
import org.junit.Assert.assertEquals
import org.junit.Test

class TavernPlayerAppTest {
    @Test
    fun `loading connection state shows loading surface`() {
        assertEquals(AppSurface.LOADING, selectAppSurface(state(loading = true), managingModels = false))
    }

    @Test
    fun `no ready model enters configuration`() {
        assertEquals(AppSurface.MODEL_CONFIGURATION, selectAppSurface(state(), managingModels = false))
    }

    @Test
    fun `ready model enters chat unless management was opened`() {
        val state = state(connections = listOf(connection()))

        assertEquals(AppSurface.CHAT, selectAppSurface(state, managingModels = false))
        assertEquals(AppSurface.MODEL_CONFIGURATION, selectAppSurface(state, managingModels = true))
    }

    private fun state(
        loading: Boolean = false,
        connections: List<StoredConnection> = emptyList(),
    ) = ChatUiState(
        character = DemoConversationContent.character.snapshot(),
        persona = DemoConversationContent.persona,
        messages = emptyList(),
        readyConnections = connections,
        selectedConnectionId = connections.firstOrNull()?.id,
        loadingConnections = loading,
    )

    private fun connection() = StoredConnection(
        id = "ready",
        name = "Ready",
        templateId = "test",
        protocol = ModelProtocol.OPENAI_RESPONSES,
        apiAddress = "https://example.com/v1",
        streamEndpoint = "https://example.com/v1/responses",
        catalogEndpoint = null,
        authScheme = AuthScheme.NONE,
        credentialRef = null,
        credentialMask = null,
        approvedOrigins = emptySet(),
        selectedModel = "model",
        modelCache = ModelCache(),
    )
}
