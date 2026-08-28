package io.github.zvensmoluya.modelgateway

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class ModelProtocol {
    OPENAI_RESPONSES,
    OPENAI_CHAT_COMPLETIONS,
    ANTHROPIC_MESSAGES,
    GEMINI_INTERACTIONS,
    GEMINI_GENERATE_CONTENT,
}

enum class AuthScheme {
    BEARER,
    X_API_KEY,
    X_GOOG_API_KEY,
    QUERY_KEY,
    NONE,
}

@JvmInline
value class SecretValue(val reveal: String) {
    init {
        require(reveal.isNotBlank()) { "Credential cannot be blank" }
    }

    override fun toString(): String = "[redacted]"
}

fun interface CredentialResolver {
    suspend fun resolve(credentialRef: String): SecretValue?
}

data class ConnectionTarget(
    val protocol: ModelProtocol,
    val streamEndpoint: String,
    val catalogEndpoint: String? = null,
    val authScheme: AuthScheme,
    val credentialRef: String? = null,
    val authorizedOrigins: Set<String> = emptySet(),
) {
    fun validate(): ConnectionTarget {
        EndpointRules.validate(streamEndpoint, protocol, isCatalog = false)
        catalogEndpoint?.takeIf(String::isNotBlank)?.let {
            EndpointRules.validate(it, protocol, isCatalog = true)
        }
        if (authScheme != AuthScheme.NONE && credentialRef.isNullOrBlank()) {
            throw GatewayException.Configuration("A credential is required for $authScheme")
        }
        return this
    }

    fun resolveStreamUrl(model: String): HttpUrl {
        validate()
        return EndpointRules.resolve(streamEndpoint, protocol, model)
    }

    fun catalogUrl(): HttpUrl? {
        validate()
        return catalogEndpoint?.takeIf(String::isNotBlank)?.toHttpUrlOrNull()
            ?: catalogEndpoint?.takeIf(String::isNotBlank)?.let {
                throw GatewayException.Configuration("Invalid catalog endpoint")
            }
    }
}

data class ConnectionTemplate(
    val id: String,
    val displayName: String,
    val protocol: ModelProtocol,
    val streamEndpoint: String,
    val catalogEndpoint: String?,
    val authScheme: AuthScheme,
)

object ConnectionTemplates {
    val openAiResponses = ConnectionTemplate(
        id = "openai-responses",
        displayName = "OpenAI Responses",
        protocol = ModelProtocol.OPENAI_RESPONSES,
        streamEndpoint = "https://api.openai.com/v1/responses",
        catalogEndpoint = "https://api.openai.com/v1/models",
        authScheme = AuthScheme.BEARER,
    )
    val openAiChat = ConnectionTemplate(
        id = "openai-chat",
        displayName = "OpenAI Chat",
        protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
        streamEndpoint = "https://api.openai.com/v1/chat/completions",
        catalogEndpoint = "https://api.openai.com/v1/models",
        authScheme = AuthScheme.BEARER,
    )
    val anthropic = ConnectionTemplate(
        id = "anthropic-messages",
        displayName = "Anthropic",
        protocol = ModelProtocol.ANTHROPIC_MESSAGES,
        streamEndpoint = "https://api.anthropic.com/v1/messages",
        catalogEndpoint = "https://api.anthropic.com/v1/models",
        authScheme = AuthScheme.X_API_KEY,
    )
    val geminiInteractions = ConnectionTemplate(
        id = "gemini-interactions",
        displayName = "Gemini",
        protocol = ModelProtocol.GEMINI_INTERACTIONS,
        streamEndpoint = "https://generativelanguage.googleapis.com/v1beta/interactions",
        catalogEndpoint = "https://generativelanguage.googleapis.com/v1beta/models",
        authScheme = AuthScheme.X_GOOG_API_KEY,
    )
    val geminiLegacy = ConnectionTemplate(
        id = "gemini-generate-content",
        displayName = "Gemini Legacy",
        protocol = ModelProtocol.GEMINI_GENERATE_CONTENT,
        streamEndpoint = "https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse",
        catalogEndpoint = "https://generativelanguage.googleapis.com/v1beta/models",
        authScheme = AuthScheme.X_GOOG_API_KEY,
    )
    val vertexExpress = ConnectionTemplate(
        id = "vertex-express",
        displayName = "Vertex Express",
        protocol = ModelProtocol.GEMINI_GENERATE_CONTENT,
        streamEndpoint = "https://aiplatform.googleapis.com/v1/publishers/google/models/{model}:streamGenerateContent?alt=sse",
        catalogEndpoint = null,
        authScheme = AuthScheme.X_GOOG_API_KEY,
    )

    val all: List<ConnectionTemplate> = listOf(
        openAiResponses,
        openAiChat,
        anthropic,
        geminiInteractions,
        geminiLegacy,
        vertexExpress,
    )

    fun require(id: String): ConnectionTemplate =
        all.firstOrNull { it.id == id }
            ?: throw GatewayException.Configuration("Unknown connection template: $id")
}

object EndpointRules {
    private val forbiddenSecretParameters = setOf(
        "key",
        "api_key",
        "apikey",
        "access_token",
        "token",
    )

    fun validate(endpoint: String, protocol: ModelProtocol, isCatalog: Boolean = false): HttpUrl {
        val placeholderCount = "{model}".toRegex(RegexOption.LITERAL).findAll(endpoint).count()
        if (isCatalog && placeholderCount != 0) {
            throw GatewayException.Configuration("Catalog endpoint cannot contain {model}")
        }
        if (!isCatalog) {
            val expectsModel = protocol == ModelProtocol.GEMINI_GENERATE_CONTENT
            if (expectsModel && placeholderCount != 1) {
                throw GatewayException.Configuration("GenerateContent endpoint must contain one {model} placeholder")
            }
            if (!expectsModel && placeholderCount != 0) {
                throw GatewayException.Configuration("Only GenerateContent endpoints may contain {model}")
            }
        }

        val parseable = endpoint.replace("{model}", "placeholder-model")
        val url = parseable.toHttpUrlOrNull()
            ?: throw GatewayException.Configuration("Endpoint is not a valid URL")
        if (url.scheme != "https") {
            throw GatewayException.Configuration("Only HTTPS endpoints are allowed")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw GatewayException.Configuration("Endpoint userinfo is not allowed")
        }
        if (url.fragment != null) {
            throw GatewayException.Configuration("Endpoint fragments are not allowed")
        }
        if (url.queryParameterNames.any { it.lowercase() in forbiddenSecretParameters }) {
            throw GatewayException.Configuration("Credentials must not be embedded in endpoint URLs")
        }
        return url
    }

    fun resolve(endpoint: String, protocol: ModelProtocol, model: String): HttpUrl {
        validate(endpoint, protocol)
        val normalizedModel = normalizeModelId(model)
        if (protocol == ModelProtocol.GEMINI_GENERATE_CONTENT && normalizedModel.isBlank()) {
            throw GatewayException.Configuration("A model id is required")
        }
        val encoded = URLEncoder.encode(normalizedModel, StandardCharsets.UTF_8)
            .replace("+", "%20")
        return endpoint.replace("{model}", encoded).toHttpUrlOrNull()
            ?: throw GatewayException.Configuration("Resolved endpoint is invalid")
    }

    fun normalizeModelId(model: String): String = model.trim().removePrefix("models/")

    fun origin(url: HttpUrl): String = "${url.scheme}://${url.host}:${url.port}"
}
