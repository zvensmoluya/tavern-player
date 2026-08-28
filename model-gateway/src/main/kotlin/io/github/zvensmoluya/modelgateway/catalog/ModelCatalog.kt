package io.github.zvensmoluya.modelgateway.catalog

import io.github.zvensmoluya.modelgateway.ConnectionTarget
import io.github.zvensmoluya.modelgateway.EndpointRules
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.modelgateway.long
import io.github.zvensmoluya.modelgateway.string
import io.github.zvensmoluya.modelgateway.transport.GatewayTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ModelDescriptor(
    val id: String,
    val name: String? = null,
    val inputTokenLimit: Long? = null,
    val outputTokenLimit: Long? = null,
    val supportedOperations: Set<String> = emptySet(),
    val raw: JsonObject,
)

data class ModelCatalog(
    val models: List<ModelDescriptor>,
    val truncated: Boolean,
)

class ModelCatalogClient internal constructor(
    private val transport: GatewayTransport,
) {
    suspend fun list(target: ConnectionTarget, limit: Int = MAX_MODELS): ModelCatalog {
        if (limit !in 1..MAX_MODELS) {
            throw GatewayException.Configuration("Catalog limit must be between 1 and $MAX_MODELS")
        }
        var nextUrl = target.catalogUrl()
            ?: throw GatewayException.Configuration("This connection has no model catalog endpoint")
        val catalogOrigin = EndpointRules.origin(nextUrl)
        val models = LinkedHashMap<String, ModelDescriptor>()
        var truncated = false
        while (true) {
            val headers = if (target.protocol == ModelProtocol.ANTHROPIC_MESSAGES) {
                mapOf("anthropic-version" to "2023-06-01")
            } else emptyMap()
            val rawBody = transport.getJson(target, nextUrl, headers)
            val page = parsePage(target.protocol, rawBody)
            page.models.forEach { model ->
                if (models.size >= limit && model.id !in models) {
                    truncated = true
                    return@forEach
                }
                models[model.id] = model
            }
            if (models.size >= limit) {
                truncated = truncated || page.nextToken != null
                break
            }
            val token = page.nextToken ?: break
            nextUrl = when (page.pagination) {
                Pagination.PAGE_TOKEN -> nextUrl.newBuilder().setQueryParameter("pageToken", token).build()
                Pagination.AFTER_ID -> nextUrl.newBuilder().setQueryParameter("after_id", token).build()
                Pagination.AFTER -> nextUrl.newBuilder().setQueryParameter("after", token).build()
            }
            if (EndpointRules.origin(nextUrl) != catalogOrigin) {
                throw GatewayException.Security("Catalog pagination attempted to change origin")
            }
        }
        return ModelCatalog(models.values.toList(), truncated)
    }

    companion object {
        const val MAX_MODELS = 1000
    }
}

private enum class Pagination { PAGE_TOKEN, AFTER_ID, AFTER }

private data class CatalogPage(
    val models: List<ModelDescriptor>,
    val nextToken: String?,
    val pagination: Pagination,
)

private val json = Json { ignoreUnknownKeys = true }

private fun parsePage(protocol: ModelProtocol, body: String): CatalogPage {
    val root = try {
        json.parseToJsonElement(body) as? JsonObject
            ?: throw GatewayException.Protocol("Model catalog response was not a JSON object")
    } catch (error: GatewayException) {
        throw error
    } catch (error: Exception) {
        throw GatewayException.Protocol("Malformed model catalog JSON", error)
    }
    return when (protocol) {
        ModelProtocol.GEMINI_INTERACTIONS, ModelProtocol.GEMINI_GENERATE_CONTENT -> parseGemini(root)
        ModelProtocol.ANTHROPIC_MESSAGES -> parseAnthropic(root)
        ModelProtocol.OPENAI_RESPONSES, ModelProtocol.OPENAI_CHAT_COMPLETIONS -> parseOpenAi(root)
    }
}

private fun parseGemini(root: JsonObject): CatalogPage {
    val values = root["models"] as? JsonArray ?: JsonArray(emptyList())
    return CatalogPage(
        models = values.mapNotNull { element ->
            val raw = element as? JsonObject ?: return@mapNotNull null
            val serviceId = raw.string("name") ?: return@mapNotNull null
            ModelDescriptor(
                id = EndpointRules.normalizeModelId(serviceId),
                name = raw.string("displayName"),
                inputTokenLimit = raw.long("inputTokenLimit"),
                outputTokenLimit = raw.long("outputTokenLimit"),
                supportedOperations = raw.stringSet("supportedGenerationMethods"),
                raw = raw,
            )
        },
        nextToken = root.string("nextPageToken"),
        pagination = Pagination.PAGE_TOKEN,
    )
}

private fun parseAnthropic(root: JsonObject): CatalogPage {
    val values = root["data"] as? JsonArray ?: JsonArray(emptyList())
    val hasMore = root["has_more"]?.jsonPrimitive?.booleanOrNull == true
    return CatalogPage(
        models = values.mapNotNull { element ->
            val raw = element as? JsonObject ?: return@mapNotNull null
            val id = raw.string("id") ?: return@mapNotNull null
            ModelDescriptor(
                id = id,
                name = raw.string("display_name"),
                inputTokenLimit = raw.long("input_token_limit") ?: raw.long("context_window"),
                outputTokenLimit = raw.long("output_token_limit") ?: raw.long("max_output_tokens"),
                supportedOperations = raw.stringSet("supported_operations"),
                raw = raw,
            )
        },
        nextToken = root.string("last_id").takeIf { hasMore },
        pagination = Pagination.AFTER_ID,
    )
}

private fun parseOpenAi(root: JsonObject): CatalogPage {
    val values = root["data"] as? JsonArray ?: JsonArray(emptyList())
    val hasMore = root["has_more"]?.jsonPrimitive?.booleanOrNull == true
    val models = values.mapNotNull { element ->
        val raw = element as? JsonObject ?: return@mapNotNull null
        val id = raw.string("id") ?: return@mapNotNull null
        ModelDescriptor(
            id = id,
            name = raw.string("name") ?: raw.string("display_name"),
            inputTokenLimit = raw.long("input_token_limit") ?: raw.long("context_window"),
            outputTokenLimit = raw.long("output_token_limit") ?: raw.long("max_output_tokens"),
            supportedOperations = raw.stringSet("supported_operations"),
            raw = raw,
        )
    }
    return CatalogPage(
        models = models,
        nextToken = (root.string("last_id") ?: models.lastOrNull()?.id).takeIf { hasMore },
        pagination = Pagination.AFTER,
    )
}

private fun JsonObject.stringSet(name: String): Set<String> =
    (this[name] as? JsonArray)
        ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        ?.toSet()
        .orEmpty()
