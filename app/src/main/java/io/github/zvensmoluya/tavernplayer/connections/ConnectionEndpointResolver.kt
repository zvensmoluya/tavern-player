package io.github.zvensmoluya.tavernplayer.connections

import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.ModelProtocol
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ResolvedConnectionEndpoints(
    val streamEndpoint: String,
    val catalogEndpoint: String,
)

/**
 * Converts the user-facing API address into the precise operation URLs required by the gateway.
 * The rules belong to protocols: service presets only provide an initial address.
 */
object ConnectionEndpointResolver {
    fun resolve(protocol: ModelProtocol, input: String): ResolvedConnectionEndpoints =
        candidates(protocol, input).first()

    fun candidates(protocol: ModelProtocol, input: String): List<ResolvedConnectionEndpoints> {
        val normalized = normalizeBase(input)
        val bases = when {
            normalized.explicitRoute -> listOf(normalized.base)
            protocol == ModelProtocol.GEMINI_INTERACTIONS ||
                protocol == ModelProtocol.GEMINI_GENERATE_CONTENT -> listOf(
                normalized.base.addPath(defaultVersion(protocol)),
            )
            else -> listOf(
                normalized.base,
                normalized.base.addPath(defaultVersion(protocol)),
            )
        }
        return bases.distinct().map { base -> resolveFromBase(protocol, base) }
    }

    private fun resolveFromBase(protocol: ModelProtocol, base: HttpUrl): ResolvedConnectionEndpoints {
        val catalog = base.addPath("models")
        val stream = when (protocol) {
            ModelProtocol.OPENAI_RESPONSES -> base.addPath("responses").toString()
            ModelProtocol.OPENAI_CHAT_COMPLETIONS -> base.addPath("chat/completions").toString()
            ModelProtocol.ANTHROPIC_MESSAGES -> base.addPath("messages").toString()
            ModelProtocol.GEMINI_INTERACTIONS -> base.addPath("interactions").toString()
            ModelProtocol.GEMINI_GENERATE_CONTENT ->
                base.addPath("models/{model}:streamGenerateContent").newBuilder()
                    .addQueryParameter("alt", "sse")
                    .build()
                    .toString()
                    .replace("%7Bmodel%7D", "{model}", ignoreCase = true)
        }
        return ResolvedConnectionEndpoints(
            streamEndpoint = stream,
            catalogEndpoint = catalog.toString(),
        )
    }

    fun displayAddress(protocol: ModelProtocol, streamEndpoint: String): String {
        val withoutQuery = streamEndpoint.substringBefore('?').trimEnd('/')
        val suffix = when (protocol) {
            ModelProtocol.OPENAI_RESPONSES -> "/responses"
            ModelProtocol.OPENAI_CHAT_COMPLETIONS -> "/chat/completions"
            ModelProtocol.ANTHROPIC_MESSAGES -> "/messages"
            ModelProtocol.GEMINI_INTERACTIONS -> "/interactions"
            ModelProtocol.GEMINI_GENERATE_CONTENT -> null
        }
        return when {
            protocol == ModelProtocol.GEMINI_GENERATE_CONTENT && "/models/" in withoutQuery ->
                withoutQuery.substringBeforeLast("/models/")
            suffix != null && withoutQuery.endsWith(suffix) -> withoutQuery.removeSuffix(suffix)
            else -> withoutQuery
        }
    }

    private fun normalizeBase(input: String): NormalizedBase {
        val raw = input.trim().removeSuffix("/")
        if (raw.isBlank()) throw GatewayException.Configuration("API address is required")
        val parseable = raw.replace("{model}", "placeholder-model")
        val parsed = parseable.toHttpUrlOrNull()
            ?: throw GatewayException.Configuration("API address is invalid")
        if (parsed.scheme != "https") {
            throw GatewayException.Configuration("Only HTTPS API addresses are supported")
        }
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.fragment != null) {
            throw GatewayException.Configuration("API address is invalid")
        }

        val path = parsed.encodedPath.trimEnd('/')
        val operation = when {
            path.endsWith("/chat/completions") -> "/chat/completions"
            path.endsWith("/responses") -> "/responses"
            path.endsWith("/messages") -> "/messages"
            path.endsWith("/interactions") -> "/interactions"
            "/models/" in path -> path.substring(path.lastIndexOf("/models/"))
            path.endsWith("/models") -> "/models"
            else -> null
        }
        val operationFreePath = operation?.let(path::removeSuffix) ?: path
        val base = parsed.newBuilder()
            .encodedPath(operationFreePath.ifBlank { "/" })
            .query(null)
            .build()
        return NormalizedBase(
            base = base,
            explicitRoute = operation != null || ApiVersionPattern.matches(base.encodedPath.trimEnd('/')),
        )
    }

    private fun defaultVersion(protocol: ModelProtocol): String = when (protocol) {
        ModelProtocol.GEMINI_INTERACTIONS,
        ModelProtocol.GEMINI_GENERATE_CONTENT,
        -> "v1beta"
        else -> "v1"
    }

    private fun HttpUrl.addPath(path: String): HttpUrl =
        newBuilder().addPathSegments(path.trim('/')).build()

    private data class NormalizedBase(
        val base: HttpUrl,
        val explicitRoute: Boolean,
    )

    private val ApiVersionPattern = Regex(".*/v\\d+[a-z]*$")
}
