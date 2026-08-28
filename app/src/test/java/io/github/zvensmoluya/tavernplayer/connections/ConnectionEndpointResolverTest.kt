package io.github.zvensmoluya.tavernplayer.connections

import io.github.zvensmoluya.modelgateway.ModelProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionEndpointResolverTest {
    @Test
    fun `unversioned OpenAI address keeps the entered base and offers a v1 fallback`() {
        val endpoints = ConnectionEndpointResolver.candidates(
            ModelProtocol.OPENAI_RESPONSES,
            "https://gateway.example.test",
        )

        assertEquals(
            listOf(
                "https://gateway.example.test/responses",
                "https://gateway.example.test/v1/responses",
            ),
            endpoints.map { it.streamEndpoint },
        )
        assertEquals(
            listOf(
                "https://gateway.example.test/models",
                "https://gateway.example.test/v1/models",
            ),
            endpoints.map { it.catalogEndpoint },
        )
    }

    @Test
    fun `explicit version or operation never creates speculative routes`() {
        assertEquals(
            1,
            ConnectionEndpointResolver.candidates(
                ModelProtocol.OPENAI_RESPONSES,
                "https://gateway.example.test/v1",
            ).size,
        )
        assertEquals(
            "https://gateway.example.test/responses",
            ConnectionEndpointResolver.resolve(
                ModelProtocol.OPENAI_RESPONSES,
                "https://gateway.example.test/responses",
            ).streamEndpoint,
        )
    }

    @Test
    fun `versioned and complete addresses normalize without duplicated paths`() {
        val chat = ConnectionEndpointResolver.resolve(
            ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            "https://gateway.example.test/v1/chat/completions",
        )
        val anthropic = ConnectionEndpointResolver.resolve(
            ModelProtocol.ANTHROPIC_MESSAGES,
            "https://gateway.example.test/v1",
        )

        assertEquals("https://gateway.example.test/v1/chat/completions", chat.streamEndpoint)
        assertEquals("https://gateway.example.test/v1/models", chat.catalogEndpoint)
        assertEquals("https://gateway.example.test/v1/messages", anthropic.streamEndpoint)
    }

    @Test
    fun `generate content keeps model placeholder and sse query`() {
        val endpoints = ConnectionEndpointResolver.resolve(
            ModelProtocol.GEMINI_GENERATE_CONTENT,
            "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        )

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse",
            endpoints.streamEndpoint,
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            endpoints.catalogEndpoint,
        )
    }

    @Test
    fun `display address hides protocol operation path`() {
        assertEquals(
            "https://api.openai.com/v1",
            ConnectionEndpointResolver.displayAddress(
                ModelProtocol.OPENAI_RESPONSES,
                "https://api.openai.com/v1/responses",
            ),
        )
    }
}
