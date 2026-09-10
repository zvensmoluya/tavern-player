package io.github.zvensmoluya.tavernplayer.characters

import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class HttpCharacterImageFetcherTest {
    @Test fun downloadsRedirectedImagesWithoutCredentialsAndEnforcesByteLimits() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val fetcher = HttpCharacterImageFetcher(OkHttpClient(), verifyPublicDestinations = false) // Explicit local-only fixture transport.
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/image").build())
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/png").body("sample").build())
            assertEquals("sample", fetcher.fetch(server.url("/start").toString(), 8).decodeToString())
            val first = server.takeRequest()
            assertNull(first.headers["Authorization"])
            assertNull(first.headers["Cookie"])
            assertEquals("/image", server.takeRequest().url.encodedPath)
            server.enqueue(MockResponse.Builder().body("too-large").build())
            try { fetcher.fetch(server.url("/large").toString(), 3); fail("accepted oversized body") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("MiB")) }
        }
    }

    @Test fun rejectsHtmlAndRedirectLoops() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val fetcher = HttpCharacterImageFetcher(OkHttpClient(), verifyPublicDestinations = false)
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html").body("<html/>").build())
            try { fetcher.fetch(server.url("/html").toString(), 1024); fail("accepted HTML") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("不是")) }
            repeat(4) { server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/loop").build()) }
            try { fetcher.fetch(server.url("/loop").toString(), 1024); fail("accepted redirect loop") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("重定向")) }
        }
    }

    @Test fun defaultTransportRejectsPrivateDestinations() {
        for (address in listOf("127.0.0.1", "0.0.0.0", "10.1.2.3", "192.168.1.1", "169.254.1.1", "100.64.0.1", "::1", "fc00::1"))
            assertFalse(address, HttpCharacterImageFetcher.publicAddress(InetAddress.getByName(address)))
        assertTrue(HttpCharacterImageFetcher.publicAddress(InetAddress.getByName("8.8.8.8")))
    }

    @Test fun proxyFakeIpAddressesStayReachable() {
        // Clash/Mihomo-style proxies answer from the 198.18.0.0/15 fake-ip pool. Rejecting that
        // range breaks every user behind such a proxy while blocking nothing reachable otherwise.
        for (address in listOf("198.18.0.170", "198.19.255.1"))
            assertTrue(address, HttpCharacterImageFetcher.publicAddress(InetAddress.getByName(address)))
    }

    @Test fun blockedDestinationsExplainThemselves() = runBlocking {
        // No injected client: the guard lives in the production transport.
        val fetcher = HttpCharacterImageFetcher()
        try { fetcher.fetch("https://127.0.0.1/image.png", 1024); fail("accepted a loopback destination") }
        catch (expected: IOException) {
            assertTrue(expected.message.orEmpty(), "必须指向公共网络" in expected.message.orEmpty())
        }
    }
}
