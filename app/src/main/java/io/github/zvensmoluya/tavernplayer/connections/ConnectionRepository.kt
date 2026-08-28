package io.github.zvensmoluya.tavernplayer.connections

import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.ConnectionTarget
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.ModelGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

class ConnectionRepository(
    private val stateStore: ConnectionStateStore,
    private val credentialStore: CredentialStore,
    private val gateway: ModelGateway,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    val state: Flow<GatewayAppState> = stateStore.state

    suspend fun save(
        draft: ConnectionDraft,
        newCredential: String,
        confirmCredentialReuse: Boolean,
    ): StoredConnection {
        if (draft.id.isBlank()) throw GatewayException.Configuration("Connection id is required")
        if (draft.name.isBlank()) throw GatewayException.Configuration("Connection name is required")
        if (draft.selectedModel.isBlank()) throw GatewayException.Configuration("Model id is required")
        val currentState = state.first()
        val previous = currentState.connections.firstOrNull { it.id == draft.id }
        val ref = previous?.credentialRef ?: "credential_${safeConnectionId(draft.id)}"
        val validationTarget = ConnectionTarget(
            protocol = draft.protocol,
            streamEndpoint = draft.streamEndpoint,
            catalogEndpoint = draft.catalogEndpoint?.takeIf(String::isNotBlank),
            authScheme = draft.authScheme,
            credentialRef = if (draft.authScheme == AuthScheme.NONE) null else ref,
        ).validate()
        val candidate = StoredConnection(
            id = draft.id,
            name = draft.name.trim(),
            templateId = draft.templateId,
            protocol = draft.protocol,
            streamEndpoint = draft.streamEndpoint.trim(),
            catalogEndpoint = draft.catalogEndpoint?.trim()?.takeIf(String::isNotBlank),
            authScheme = draft.authScheme,
            credentialRef = if (draft.authScheme == AuthScheme.NONE) null else ref,
            credentialMask = previous?.credentialMask,
            approvedOrigins = previous?.approvedOrigins.orEmpty(),
            selectedModel = draft.selectedModel.trim(),
            modelCache = previous?.modelCache ?: ModelCache(),
        )
        val currentOrigins = candidate.currentOrigins()
        val credential = newCredential.trim()
        val stored = when {
            draft.authScheme == AuthScheme.NONE -> candidate.copy(
                credentialRef = null,
                credentialMask = null,
                approvedOrigins = emptySet(),
            )
            credential.isNotEmpty() -> {
                credentialStore.put(ref, credential)
                candidate.copy(
                    credentialRef = ref,
                    credentialMask = maskCredential(credential),
                    approvedOrigins = currentOrigins,
                )
            }
            previous?.credentialRef == null || !credentialStore.contains(previous.credentialRef) -> {
                throw GatewayException.Authentication("需要重新输入密钥")
            }
            currentOrigins == previous.approvedOrigins -> candidate
            confirmCredentialReuse -> candidate.copy(approvedOrigins = currentOrigins)
            else -> candidate
        }
        validationTarget.copy(authorizedOrigins = stored.approvedOrigins).validate()
        stateStore.update { state ->
            state.copy(
                connections = state.connections.filterNot { it.id == stored.id } + stored,
                recentConnectionId = stored.id,
            )
        }
        if (draft.authScheme == AuthScheme.NONE) {
            previous?.credentialRef?.let { credentialStore.delete(it) }
        }
        return stored
    }

    suspend fun refreshModels(connectionId: String): ModelCache {
        val connection = requireConnection(connectionId)
        ensureReady(connection)
        val catalog = gateway.modelCatalog.list(connection.target())
        val cache = ModelCache(
            models = catalog.models.map { model ->
                StoredModel(
                    id = model.id,
                    name = model.name,
                    inputTokenLimit = model.inputTokenLimit,
                    outputTokenLimit = model.outputTokenLimit,
                    supportedOperations = model.supportedOperations,
                )
            },
            refreshedAtEpochMillis = clockMillis(),
        )
        stateStore.update { state ->
            state.copy(
                connections = state.connections.map {
                    if (it.id == connectionId) it.copy(modelCache = cache) else it
                },
            )
        }
        return cache
    }

    suspend fun selectModel(connectionId: String, modelId: String) {
        if (modelId.isBlank()) throw GatewayException.Configuration("Model id is required")
        stateStore.update { state ->
            state.copy(
                connections = state.connections.map {
                    if (it.id == connectionId) it.copy(selectedModel = modelId.trim()) else it
                },
                recentConnectionId = connectionId,
            )
        }
    }

    suspend fun delete(connectionId: String) {
        val connection = state.first().connections.firstOrNull { it.id == connectionId }
        stateStore.update { state ->
            state.copy(
                connections = state.connections.filterNot { it.id == connectionId },
                recentConnectionId = state.recentConnectionId.takeUnless { it == connectionId },
            )
        }
        connection?.credentialRef?.let { credentialStore.delete(it) }
    }

    suspend fun credentialStatus(connection: StoredConnection): CredentialStatus = when {
        connection.authScheme == AuthScheme.NONE -> CredentialStatus.NOT_REQUIRED
        connection.credentialRef == null || !credentialStore.contains(connection.credentialRef) -> CredentialStatus.MISSING
        !connection.approvedOrigins.containsAll(connection.currentOrigins()) -> CredentialStatus.ORIGIN_CONFIRMATION_REQUIRED
        else -> CredentialStatus.READY
    }

    suspend fun ensureReady(connection: StoredConnection) {
        when (credentialStatus(connection)) {
            CredentialStatus.READY, CredentialStatus.NOT_REQUIRED -> Unit
            CredentialStatus.MISSING -> throw GatewayException.Authentication("需要重新输入密钥")
            CredentialStatus.ORIGIN_CONFIRMATION_REQUIRED -> {
                throw GatewayException.Security("endpoint origin 已改变，需要确认继续使用原密钥")
            }
        }
    }

    suspend fun requireConnection(id: String): StoredConnection =
        state.first().connections.firstOrNull { it.id == id }
            ?: throw GatewayException.Configuration("Connection does not exist")

    companion object {
        fun newConnectionId(): String = UUID.randomUUID().toString()

        private fun safeConnectionId(id: String): String =
            id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(96).ifBlank { "connection" }
    }
}
