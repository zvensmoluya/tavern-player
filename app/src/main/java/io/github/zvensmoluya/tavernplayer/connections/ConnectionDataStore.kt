package io.github.zvensmoluya.tavernplayer.connections

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.ModelProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException

interface ConnectionStateStore {
    val state: Flow<GatewayAppState>
    suspend fun update(transform: (GatewayAppState) -> GatewayAppState)
}

class JsonConnectionDataStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) : ConnectionStateStore {
    override val state: Flow<GatewayAppState> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences ->
            preferences[STATE_JSON]
                ?.let { value -> runCatching { decodeState(value) }.getOrNull() }
                ?: GatewayAppState()
        }
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.Lazily, replay = 1)

    override suspend fun update(transform: (GatewayAppState) -> GatewayAppState) {
        dataStore.edit { preferences ->
            val current = preferences[STATE_JSON]?.let { runCatching { decodeState(it) }.getOrNull() } ?: GatewayAppState()
            preferences[STATE_JSON] = encodeState(transform(current))
        }
    }

    companion object {
        private val STATE_JSON = stringPreferencesKey("gateway_state_json_v1")
        private val json = Json { ignoreUnknownKeys = true }

        internal fun encodeState(state: GatewayAppState): String = buildJsonObject {
            put("schemaVersion", state.schemaVersion)
            state.recentConnectionId?.let { put("recentConnectionId", it) }
            put("connections", buildJsonArray {
                state.connections.forEach { add(it.toJson()) }
            })
        }.toString()

        internal fun decodeState(value: String): GatewayAppState {
            val root = try {
                json.parseToJsonElement(value).jsonObject
            } catch (error: Exception) {
                throw GatewayException.Configuration("Stored connection data is malformed")
            }
            val version = root.long("schemaVersion")?.toInt() ?: 1
            if (version > GatewayAppState.CURRENT_SCHEMA_VERSION) {
                throw GatewayException.Configuration("Connection data uses unsupported schema version $version")
            }
            return GatewayAppState(
                schemaVersion = GatewayAppState.CURRENT_SCHEMA_VERSION,
                recentConnectionId = root.string("recentConnectionId"),
                connections = root.array("connections").mapNotNull { element ->
                    runCatching { element.jsonObject.toConnection() }.getOrNull()
                },
            )
        }
    }
}

private fun StoredConnection.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("templateId", templateId)
    put("protocol", protocol.name)
    put("apiAddress", apiAddress)
    put("streamEndpoint", streamEndpoint)
    catalogEndpoint?.let { put("catalogEndpoint", it) }
    put("authScheme", authScheme.name)
    credentialRef?.let { put("credentialRef", it) }
    credentialMask?.let { put("credentialMask", it) }
    put("approvedOrigins", buildJsonArray { approvedOrigins.sorted().forEach { add(it) } })
    put("selectedModel", selectedModel)
    put("modelCache", buildJsonObject {
        modelCache.refreshedAtEpochMillis?.let { put("refreshedAtEpochMillis", it) }
        put("models", buildJsonArray {
            modelCache.models.forEach { model ->
                add(buildJsonObject {
                    put("id", model.id)
                    model.name?.let { put("name", it) }
                    model.inputTokenLimit?.let { put("inputTokenLimit", it) }
                    model.outputTokenLimit?.let { put("outputTokenLimit", it) }
                    put("supportedOperations", buildJsonArray {
                        model.supportedOperations.sorted().forEach { add(it) }
                    })
                })
            }
        })
    })
    put("modelTokenLimitOverrides", buildJsonArray {
        modelTokenLimitOverrides.toSortedMap().forEach { (modelId, limits) ->
            add(buildJsonObject {
                put("modelId", modelId)
                limits.contextTokens?.let { put("contextTokens", it) }
                limits.outputTokens?.let { put("outputTokens", it) }
            })
        }
    })
}

private fun JsonObject.toConnection(): StoredConnection {
    val cache = obj("modelCache")
    val protocol = ModelProtocol.valueOf(requireString("protocol"))
    val storedStreamEndpoint = requireString("streamEndpoint")
    val storedApiAddress = string("apiAddress")
    val apiAddress = storedApiAddress
        ?: ConnectionEndpointResolver.displayAddress(protocol, storedStreamEndpoint)
    val migratedEndpoints = if (storedApiAddress == null) {
        runCatching { ConnectionEndpointResolver.resolve(protocol, apiAddress) }.getOrNull()
    } else null
    val credentialRef = string("credentialRef")
    val connection = StoredConnection(
        id = requireString("id"),
        name = requireString("name"),
        templateId = requireString("templateId"),
        protocol = protocol,
        apiAddress = apiAddress,
        streamEndpoint = migratedEndpoints?.streamEndpoint ?: storedStreamEndpoint,
        catalogEndpoint = migratedEndpoints?.catalogEndpoint ?: string("catalogEndpoint"),
        authScheme = if (credentialRef == null) {
            AuthScheme.NONE
        } else {
            ConnectionTemplates.forProtocol(protocol).authScheme
        },
        credentialRef = credentialRef,
        credentialMask = string("credentialMask"),
        approvedOrigins = array("approvedOrigins").mapNotNull { it.jsonPrimitive.contentOrNull }.toSet(),
        selectedModel = string("selectedModel").orEmpty(),
        modelCache = ModelCache(
            refreshedAtEpochMillis = cache?.long("refreshedAtEpochMillis"),
            models = cache?.array("models").orEmpty().mapNotNull { element ->
                val model = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                val id = model.string("id") ?: return@mapNotNull null
                StoredModel(
                    id = id,
                    name = model.string("name"),
                    inputTokenLimit = model.long("inputTokenLimit"),
                    outputTokenLimit = model.long("outputTokenLimit"),
                    supportedOperations = model.array("supportedOperations")
                        .mapNotNull { it.jsonPrimitive.contentOrNull }.toSet(),
                )
            },
        ),
        modelTokenLimitOverrides = array("modelTokenLimitOverrides").mapNotNull { element ->
            val value = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val modelId = value.string("modelId")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val limits = ModelTokenLimits(
                contextTokens = value.long("contextTokens")?.takeIf { it in 1..Int.MAX_VALUE.toLong() },
                outputTokens = value.long("outputTokens")?.takeIf { it in 1..Int.MAX_VALUE.toLong() },
            )
            if (limits.contextTokens == null && limits.outputTokens == null) null else modelId to limits
        }.toMap(),
    )
    return connection.copy(
        approvedOrigins = connection.approvedOrigins.intersect(connection.currentOrigins()),
    )
}

private fun JsonObject.requireString(name: String): String =
    string(name) ?: throw GatewayException.Configuration("Stored connection is missing $name")

private fun JsonObject.string(name: String): String? =
    this[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

private fun JsonObject.long(name: String): Long? =
    this[name]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }

private fun JsonObject.array(name: String): JsonArray =
    this[name]?.let { runCatching { it.jsonArray }.getOrNull() } ?: JsonArray(emptyList())

private fun JsonObject.obj(name: String): JsonObject? =
    this[name]?.let { runCatching { it.jsonObject }.getOrNull() }
