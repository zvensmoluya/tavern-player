package io.github.zvensmoluya.tavernplayer.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderErrorTextTest {
    @Test fun `the provider body reaches the user unchanged`() {
        val body = """{"error":{"message":"Unsupported parameter: 'top_p' is not supported with this model."}}"""
        val text = providerHttpFailureText(400, body)
        assertEquals("模型服务返回 HTTP 400：$body", text)
    }

    @Test fun `a failure without a body still names the status`() {
        assertEquals("模型服务返回 HTTP 503", providerHttpFailureText(503, "   "))
    }

    @Test fun `long bodies are bounded`() {
        val text = providerHttpFailureText(400, "x".repeat(1_000))
        assertTrue(text.length.toString(), text.length <= 400 + "模型服务返回 HTTP 400：…".length)
        assertTrue(text.endsWith("…"))
    }

    @Test fun `a rejected request is not described as a temporary outage`() {
        assertFalse(providerHttpFailureText(400, "bad request").contains("暂时不可用"))
    }
}
