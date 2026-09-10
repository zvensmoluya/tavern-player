package io.github.zvensmoluya.tavernplayer.connections

import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.ConnectionTarget
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.catalog.ModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.net.URI
import java.util.UUID

class ConnectionRepository(
    private val stateStore: ConnectionStateStore,
    private val credentialStore: CredentialStore,
    private val catalogLoader: suspend (ConnectionTarget) -> ModelCatalog,
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
        val currentState = state.first()
        val previous = currentState.connections.firstOrNull { it.id == draft.id }
        val apiAddress = draft.apiAddress.trim().trimEnd('/')
        // Saving a name, model or credential must not discard the route a fallback already learned:
        // the stored endpoints stay authoritative while the protocol and the address do not change.
        val endpoints = previous
            ?.takeIf { it.protocol == draft.protocol && it.apiAddress == apiAddress }
            ?.let { ResolvedConnectionEndpoints(it.streamEndpoint, it.catalogEndpoint.orEmpty()) }
            ?: ConnectionEndpointResolver.resolve(draft.protocol, draft.apiAddress)
        val selectedModel = draft.selectedModel.trim()
        val contextTokenLimitOverride = draft.contextTokenLimitOverride.optionalTokenLimit("context")
        val outputTokenLimitOverride = draft.outputTokenLimitOverride.optionalTokenLimit("output")
        if (selectedModel.isBlank() && (contextTokenLimitOverride != null || outputTokenLimitOverride != null)) {
            throw GatewayException.Configuration("A model is required before setting token limits")
        }
        val tokenLimitOverrides = previous?.modelTokenLimitOverrides.orEmpty().toMutableMap().apply {
            if (selectedModel.isNotBlank()) {
                val limits = ModelTokenLimits(contextTokenLimitOverride, outputTokenLimitOverride)
                if (limits.contextTokens == null && limits.outputTokens == null) remove(selectedModel)
                else put(selectedModel, limits)
            }
        }.toMap()
        val ref = previous?.credentialRef ?: "credential_${safeConnectionId(draft.id)}"
        val credential = newCredential.trim()
        val hasStoredCredential = previous?.credentialRef?.let { credentialStore.contains(it) } == true
        val authScheme = if (credential.isNotEmpty() || hasStoredCredential) {
            ConnectionTemplates.forProtocol(draft.protocol).authScheme
        } else {
            AuthScheme.NONE
        }
        val validationTarget = ConnectionTarget(
            protocol = draft.protocol,
            streamEndpoint = endpoints.streamEndpoint,
            catalogEndpoint = endpoints.catalogEndpoint,
            authScheme = authScheme,
            credentialRef = if (authScheme == AuthScheme.NONE) null else ref,
        ).validate()
        val candidate = StoredConnection(
            id = draft.id,
            name = draft.name.trim(),
            templateId = draft.templateId,
            protocol = draft.protocol,
            apiAddress = apiAddress,
            streamEndpoint = endpoints.streamEndpoint,
            catalogEndpoint = endpoints.catalogEndpoint,
            authScheme = authScheme,
            credentialRef = if (authScheme == AuthScheme.NONE) null else ref,
            credentialMask = previous?.credentialMask,
            approvedOrigins = previous?.approvedOrigins.orEmpty(),
            selectedModel = selectedModel,
            modelCache = previous?.modelCache ?: ModelCache(),
            modelTokenLimitOverrides = tokenLimitOverrides,
        )
        val currentOrigins = candidate.currentOrigins()
        val stored = when {
            authScheme == AuthScheme.NONE -> candidate.copy(
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
        if (authScheme == AuthScheme.NONE) {
            previous?.credentialRef?.let { credentialStore.delete(it) }
        }
        return stored
    }

    suspend fun refreshModels(connectionId: String): ModelDiscoveryResult {
        val connection = requireConnection(connectionId)
        runCatching { ensureReady(connection) }.exceptionOrNull()?.let { error ->
            return ModelDiscoveryResult.Unavailable(error.toDiscoveryFailure())
        }
        var lastRouteFailure: GatewayException.HttpFailure? = null
        ConnectionEndpointResolver.candidates(connection.protocol, connection.apiAddress).forEach { endpoints ->
            val routed = connection.copy(
                streamEndpoint = endpoints.streamEndpoint,
                catalogEndpoint = endpoints.catalogEndpoint,
            )
            val catalog = try {
                catalogLoader(routed.target())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: GatewayException.HttpFailure) {
                if (error.status in MODEL_ROUTE_FALLBACK_STATUSES) {
                    lastRouteFailure = error
                    return@forEach
                }
                return ModelDiscoveryResult.Unavailable(error.toDiscoveryFailure())
            } catch (error: Exception) {
                return ModelDiscoveryResult.Unavailable(error.toDiscoveryFailure())
            }
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
            var recorded = false
            stateStore.update { state ->
                state.copy(
                    connections = state.connections.map {
                        if (it.id == connectionId && it.matchesAddressOf(connection)) {
                            recorded = true
                            it.copy(
                                streamEndpoint = endpoints.streamEndpoint,
                                catalogEndpoint = endpoints.catalogEndpoint,
                                modelCache = cache,
                            )
                        } else it
                    },
                )
            }
            if (!recorded) {
                // The address changed while the catalog request was in flight: those endpoints and
                // that model list describe the previous host.
                return ModelDiscoveryResult.Unavailable(
                    ModelDiscoveryFailure(kind = ModelDiscoveryFailureKind.UNSUPPORTED),
                )
            }
            return if (cache.models.isEmpty()) {
                ModelDiscoveryResult.Empty(cache)
            } else {
                ModelDiscoveryResult.Found(cache)
            }
        }
        return ModelDiscoveryResult.Unavailable(
            ModelDiscoveryFailure(
                kind = ModelDiscoveryFailureKind.UNSUPPORTED,
                httpStatus = lastRouteFailure?.status,
                diagnostic = lastRouteFailure?.diagnostic,
            ),
        )
    }

    /**
     * Records the endpoints that answered for this connection so later requests skip the dead route.
     * [connection] is the snapshot the endpoints were learned for: when the stored connection has
     * already moved to another protocol or address, that route belongs to the previous host and is
     * dropped instead of resurrecting it. The model cache belongs to catalog discovery and stays
     * untouched.
     */
    suspend fun rememberEndpoints(connection: StoredConnection, endpoints: ResolvedConnectionEndpoints) {
        stateStore.update { state ->
            state.copy(
                connections = state.connections.map { stored ->
                    if (stored.id == connection.id && stored.matchesAddressOf(connection)) {
                        stored.copy(
                            streamEndpoint = endpoints.streamEndpoint,
                            catalogEndpoint = endpoints.catalogEndpoint,
                        )
                    } else {
                        stored
                    }
                },
            )
        }
    }

    /**
     * Runs [generate] against the remembered endpoint first and then against the remaining resolver
     * candidates. Only HTTP 404/405/501 mean "this route is not implemented"; every other failure and
     * the last candidate's failure are reported unchanged. A route that answers is remembered so later
     * requests start from it.
     */
    suspend fun <T> withRoute(
        connection: StoredConnection,
        onRouteChanged: suspend (String) -> Unit = {},
        generate: suspend (ConnectionTarget) -> T,
    ): T {
        val routes = routeCandidates(connection)
        for ((index, route) in routes.withIndex()) {
            val target = connection.copy(
                streamEndpoint = route.streamEndpoint,
                catalogEndpoint = route.catalogEndpoint.takeIf(String::isNotBlank),
            ).target()
            try {
                val result = generate(target)
                if (index > 0) rememberRoute(connection, route)
                return result
            } catch (error: GatewayException.HttpFailure) {
                if (error.status !in MODEL_ROUTE_FALLBACK_STATUSES) throw error
                if (index == routes.lastIndex) throw error
                onRouteChanged(
                    "端点 ${endpointLabel(route)} 返回 HTTP ${error.status}，" +
                        "改用 ${endpointLabel(routes[index + 1])}",
                )
            }
        }
        // routeCandidates always starts from the remembered endpoint, so this only means the
        // connection has no usable route at all.
        throw GatewayException.Configuration("Connection has no endpoint candidate to try")
    }

    private fun routeCandidates(connection: StoredConnection): List<ResolvedConnectionEndpoints> {
        val remembered = ResolvedConnectionEndpoints(
            streamEndpoint = connection.streamEndpoint,
            catalogEndpoint = connection.catalogEndpoint.orEmpty(),
        )
        // A malformed address must not break generation: the remembered endpoint stays authoritative.
        val discovered = runCatching {
            ConnectionEndpointResolver.candidates(connection.protocol, connection.apiAddress)
        }.getOrDefault(emptyList())
        return (listOf(remembered) + discovered).distinctBy { it.streamEndpoint }
    }

    /**
     * Remembering the route is bookkeeping: a failed write must not invalidate work that already
     * produced its events, the next request simply rediscovers the route.
     */
    private suspend fun rememberRoute(connection: StoredConnection, route: ResolvedConnectionEndpoints) {
        try {
            rememberEndpoints(connection, route)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Ignored on purpose.
        }
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

    suspend fun activate(connectionId: String) {
        requireConnection(connectionId)
        stateStore.update { state -> state.copy(recentConnectionId = connectionId) }
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
        internal val MODEL_ROUTE_FALLBACK_STATUSES = setOf(404, 405, 501)

        fun newConnectionId(): String = UUID.randomUUID().toString()

        private fun safeConnectionId(id: String): String =
            id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(96).ifBlank { "connection" }
    }
}

private fun String.optionalTokenLimit(name: String): Long? {
    if (isBlank()) return null
    val value = trim().toLongOrNull()
    if (value == null || value !in 1..Int.MAX_VALUE.toLong()) {
        throw GatewayException.Configuration("$name token limit must be a positive 32-bit integer")
    }
    return value
}

/** True while the stored connection still serves the same protocol and address as [snapshot]. */
private fun StoredConnection.matchesAddressOf(snapshot: StoredConnection): Boolean =
    protocol == snapshot.protocol && apiAddress == snapshot.apiAddress

/** Route diagnostics carry host and path only; the full endpoint URL never reaches the trace. */
private fun endpointLabel(endpoints: ResolvedConnectionEndpoints): String {
    // Gemini stream endpoints carry a {model} placeholder that java.net.URI refuses to parse.
    val parseable = endpoints.streamEndpoint.replace("{model}", "model")
    val url = runCatching { URI(parseable) }.getOrNull() ?: return "当前地址"
    return (url.host.orEmpty() + url.path.orEmpty()).ifBlank { "当前地址" }
}

private fun Throwable.toDiscoveryFailure(): ModelDiscoveryFailure = when (this) {
    is GatewayException.Authentication,
    is GatewayException.AuthenticationFailure,
    -> ModelDiscoveryFailure(
        kind = ModelDiscoveryFailureKind.AUTHENTICATION,
        httpStatus = (this as? GatewayException.HttpFailure)?.status,
        diagnostic = diagnostic,
    )
    is GatewayException.Security -> ModelDiscoveryFailure(
        kind = ModelDiscoveryFailureKind.CREDENTIAL_CONFIRMATION,
        diagnostic = diagnostic,
    )
    is GatewayException.RateLimited -> ModelDiscoveryFailure(
        kind = ModelDiscoveryFailureKind.RATE_LIMITED,
        httpStatus = status,
        diagnostic = diagnostic,
    )
    is GatewayException.Network -> ModelDiscoveryFailure(
        kind = ModelDiscoveryFailureKind.UNREACHABLE,
        diagnostic = diagnostic,
    )
    is GatewayException.Protocol,
    is GatewayException.ResponseTooLarge,
    -> ModelDiscoveryFailure(
        kind = ModelDiscoveryFailureKind.INVALID_RESPONSE,
        diagnostic = diagnostic,
    )
    is GatewayException.HttpFailure -> ModelDiscoveryFailure(
        kind = ModelDiscoveryFailureKind.SERVICE,
        httpStatus = status,
        diagnostic = diagnostic,
    )
    else -> ModelDiscoveryFailure(
        kind = ModelDiscoveryFailureKind.SERVICE,
        diagnostic = this::class.simpleName,
    )
}
