package io.github.zvensmoluya.tavernplayer.connections

import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.ModelProtocol

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
        displayName = "OpenAI Chat Completions",
        protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
        streamEndpoint = "https://api.openai.com/v1/chat/completions",
        catalogEndpoint = "https://api.openai.com/v1/models",
        authScheme = AuthScheme.BEARER,
    )
    val anthropic = ConnectionTemplate(
        id = "anthropic-messages",
        displayName = "Anthropic Messages",
        protocol = ModelProtocol.ANTHROPIC_MESSAGES,
        streamEndpoint = "https://api.anthropic.com/v1/messages",
        catalogEndpoint = "https://api.anthropic.com/v1/models",
        authScheme = AuthScheme.X_API_KEY,
    )
    val geminiInteractions = ConnectionTemplate(
        id = "gemini-interactions",
        displayName = "Gemini Interactions",
        protocol = ModelProtocol.GEMINI_INTERACTIONS,
        streamEndpoint = "https://generativelanguage.googleapis.com/v1beta/interactions",
        catalogEndpoint = "https://generativelanguage.googleapis.com/v1beta/models",
        authScheme = AuthScheme.X_GOOG_API_KEY,
    )
    val geminiGenerateContent = ConnectionTemplate(
        id = "gemini-generate-content",
        displayName = "Gemini GenerateContent",
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
        geminiGenerateContent,
        vertexExpress,
    )

    /** Protocol choices shown by the product. Vertex Express is a preset of GenerateContent. */
    val protocols: List<ConnectionTemplate> = listOf(
        openAiResponses,
        openAiChat,
        anthropic,
        geminiInteractions,
        geminiGenerateContent,
    )

    fun forProtocol(protocol: ModelProtocol): ConnectionTemplate =
        protocols.first { it.protocol == protocol }

    fun require(id: String): ConnectionTemplate =
        all.firstOrNull { it.id == id }
            ?: throw GatewayException.Configuration("Unknown connection template: $id")
}
