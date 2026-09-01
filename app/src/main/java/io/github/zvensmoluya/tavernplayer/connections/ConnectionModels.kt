package io.github.zvensmoluya.tavernplayer.connections

import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.ConnectionTarget
import io.github.zvensmoluya.modelgateway.EndpointRules
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.modelgateway.catalog.ModelDescriptor
import kotlinx.serialization.json.JsonObject

data class StoredModel(
    val id: String,
    val name: String? = null,
    val inputTokenLimit: Long? = null,
    val outputTokenLimit: Long? = null,
    val supportedOperations: Set<String> = emptySet(),
) {
    fun asDescriptor(raw: JsonObject = JsonObject(emptyMap())) = ModelDescriptor(
        id = id,
        name = name,
        inputTokenLimit = inputTokenLimit,
        outputTokenLimit = outputTokenLimit,
        supportedOperations = supportedOperations,
        raw = raw,
    )
}

data class ModelCache(
    val models: List<StoredModel> = emptyList(),
    val refreshedAtEpochMillis: Long? = null,
)

data class ModelTokenLimits(
    val contextTokens: Long? = null,
    val outputTokens: Long? = null,
)

data class StoredConnection(
    val id: String,
    val name: String,
    val templateId: String,
    val protocol: ModelProtocol,
    val apiAddress: String,
    val streamEndpoint: String,
    val catalogEndpoint: String?,
    val authScheme: AuthScheme,
    val credentialRef: String?,
    val credentialMask: String?,
    val approvedOrigins: Set<String>,
    val selectedModel: String,
    val modelCache: ModelCache = ModelCache(),
    val modelTokenLimitOverrides: Map<String, ModelTokenLimits> = emptyMap(),
) {
    fun target(): ConnectionTarget = ConnectionTarget(
        protocol = protocol,
        streamEndpoint = streamEndpoint,
        catalogEndpoint = catalogEndpoint,
        authScheme = authScheme,
        credentialRef = credentialRef,
        authorizedOrigins = approvedOrigins,
    )

    fun currentOrigins(): Set<String> = buildSet {
        add(EndpointRules.origin(EndpointRules.validate(streamEndpoint, protocol)))
        catalogEndpoint?.takeIf(String::isNotBlank)?.let {
            add(EndpointRules.origin(EndpointRules.validate(it, protocol, isCatalog = true)))
        }
    }

    fun effectiveTokenLimits(modelId: String = selectedModel): ModelTokenLimits {
        val normalizedId = modelId.trim()
        val discovered = modelCache.models.firstOrNull { it.id == normalizedId }
        val override = modelTokenLimitOverrides[normalizedId]
        return ModelTokenLimits(
            contextTokens = override?.contextTokens ?: discovered?.inputTokenLimit,
            outputTokens = override?.outputTokens ?: discovered?.outputTokenLimit,
        )
    }
}

data class GatewayAppState(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val connections: List<StoredConnection> = emptyList(),
    val recentConnectionId: String? = null,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

data class ConnectionDraft(
    val id: String,
    val name: String,
    val templateId: String,
    val protocol: ModelProtocol,
    val apiAddress: String,
    val selectedModel: String,
    val contextTokenLimitOverride: String = "",
    val outputTokenLimitOverride: String = "",
)

sealed interface ModelDiscoveryResult {
    data class Found(val cache: ModelCache) : ModelDiscoveryResult
    data class Empty(val cache: ModelCache) : ModelDiscoveryResult
    data class Unavailable(val failure: ModelDiscoveryFailure) : ModelDiscoveryResult
}

data class ModelDiscoveryFailure(
    val kind: ModelDiscoveryFailureKind,
    val httpStatus: Int? = null,
    val diagnostic: String? = null,
)

enum class ModelDiscoveryFailureKind {
    UNSUPPORTED,
    AUTHENTICATION,
    CREDENTIAL_CONFIRMATION,
    RATE_LIMITED,
    UNREACHABLE,
    INVALID_RESPONSE,
    SERVICE,
}

enum class CredentialStatus {
    READY,
    MISSING,
    ORIGIN_CONFIRMATION_REQUIRED,
    NOT_REQUIRED,
}
