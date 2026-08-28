package io.github.zvensmoluya.modelgateway

import io.github.zvensmoluya.modelgateway.anthropic.AnthropicMessagesClient
import io.github.zvensmoluya.modelgateway.catalog.ModelCatalogClient
import io.github.zvensmoluya.modelgateway.chat.OpenAiChatCompletionsClient
import io.github.zvensmoluya.modelgateway.gemini.GeminiGenerateContentClient
import io.github.zvensmoluya.modelgateway.gemini.GeminiInteractionsClient
import io.github.zvensmoluya.modelgateway.responses.OpenAiResponsesClient
import io.github.zvensmoluya.modelgateway.transport.GatewayTransport
import okhttp3.OkHttpClient

class ModelGateway(
    credentialResolver: CredentialResolver,
    client: OkHttpClient = GatewayTransport.defaultClient(),
) {
    private val transport = GatewayTransport(credentialResolver, client)

    val responses = OpenAiResponsesClient(transport)
    val chatCompletions = OpenAiChatCompletionsClient(transport)
    val anthropicMessages = AnthropicMessagesClient(transport)
    val geminiInteractions = GeminiInteractionsClient(transport)
    val geminiGenerateContent = GeminiGenerateContentClient(transport)
    val modelCatalog = ModelCatalogClient(transport)
}
