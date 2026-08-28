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
