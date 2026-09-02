package io.github.zvensmoluya.tavernplayer.transfer

import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShelfTransferClientTest {
    @Test
    fun `receives manifest and verifies original source`() = runTest {
        val source = """{"spec":"chara_card_v2","data":{"name":"Lantern"}}""".encodeToByteArray()
        MockWebServer().use { server ->
            server.start()
            val sourceUrl = server.url("/v1/transfers/token/source")
            server.enqueue(MockResponse(body = manifest(sourceUrl.toString(), source, kind = "character")))
            server.enqueue(MockResponse(body = source.decodeToString()))

            val transfer = ShelfTransferClient().receive(server.url("/v1/transfers/token").toString())

            assertEquals("character", transfer.manifest.kind)
            assertEquals("lantern.json", transfer.manifest.filename)
            assertArrayEquals(source, transfer.sourceBytes)
            assertEquals("/v1/transfers/token", server.takeRequest().url.encodedPath)
            assertEquals("/v1/transfers/token/source", server.takeRequest().url.encodedPath)
        }
    }

    @Test
    fun `receives and independently verifies optional adaptation`() = runTest {
        val source = "card".encodeToByteArray()
        val adaptation = """{"schemaVersion":1}""".encodeToByteArray()
        MockWebServer().use { server ->
            server.start()
            val sourceUrl = server.url("/v1/transfers/token/source").toString()
            val adaptationUrl = server.url("/v1/transfers/token/adaptation").toString()
            server.enqueue(MockResponse(body = manifest(sourceUrl, source, "character", adaptationUrl = adaptationUrl, adaptation = adaptation)))
            server.enqueue(MockResponse(body = source.decodeToString()))
            server.enqueue(MockResponse(body = adaptation.decodeToString()))

            val transfer = ShelfTransferClient().receive(server.url("/v1/transfers/token").toString())

            assertArrayEquals(adaptation, transfer.adaptationBytes)
            assertEquals(1, transfer.manifest.adaptation?.schemaVersion)
            assertEquals("/v1/transfers/token", server.takeRequest().url.encodedPath)
            assertEquals("/v1/transfers/token/source", server.takeRequest().url.encodedPath)
            assertEquals("/v1/transfers/token/adaptation", server.takeRequest().url.encodedPath)
        }
    }

    @Test
    fun `rejects attachment URL on another origin`() {
        assertThrows(ShelfTransferException::class.java) {
            kotlinx.coroutines.runBlocking {
                MockWebServer().use { server ->
                    server.start()
                    val source = "card".encodeToByteArray()
                    val adaptation = "{}".encodeToByteArray()
                    server.enqueue(
                        MockResponse(
                            body = manifest(
                                server.url("/source").toString(),
                                source,
                                "character",
                                adaptationUrl = "http://attacker.invalid/adaptation",
                                adaptation = adaptation,
                            ),
                        ),
                    )
                    ShelfTransferClient().receive(server.url("/transfer").toString())
                }
            }
        }
    }

    @Test
    fun `preserves preset subtype as descriptive metadata`() = runTest {
        val source = """{"name":"ChatML","input_sequence":"<user>"}""".encodeToByteArray()
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(
                    body = manifest(
                        server.url("/v1/transfers/preset/source").toString(),
                        source,
                        kind = "preset",
                        subtype = "instruct",
                    ),
                ),
            )
            server.enqueue(MockResponse(body = source.decodeToString()))

            val transfer = ShelfTransferClient().receive(server.url("/v1/transfers/preset").toString())

            assertEquals("preset", transfer.manifest.kind)
            assertEquals("instruct", transfer.manifest.subtype)
        }
    }

    @Test
    fun `rejects a source whose digest differs from the manifest`() {
        assertThrows(ShelfTransferException::class.java) {
            kotlinx.coroutines.runBlocking {
                MockWebServer().use { server ->
                    server.start()
                    val declared = "original".encodeToByteArray()
                    server.enqueue(
                        MockResponse(
                            body = manifest(server.url("/source").toString(), declared, kind = "worldbook"),
                        ),
                    )
                    server.enqueue(MockResponse(body = "changed"))
                    ShelfTransferClient().receive(server.url("/transfer").toString())
                }
            }
        }
    }

    private fun manifest(
        sourceUrl: String,
        source: ByteArray,
        kind: String,
        subtype: String? = null,
        adaptationUrl: String? = null,
        adaptation: ByteArray? = null,
    ): String {
        val subtypeField = subtype?.let { "\"subtype\":\"$it\"," }.orEmpty()
        val adaptationField = if (adaptationUrl != null && adaptation != null) {
            """
              "adaptation":{
                "schemaVersion":1,
                "filename":"adaptation-v1.json",
                "size":${adaptation.size},
                "sha256":"${sha256(adaptation)}",
                "mediaType":"application/vnd.tavern-player.adaptation+json",
                "url":"$adaptationUrl"
              },
            """.trimIndent()
        } else {
            ""
        }
        return """
            {
              "protocol":"tavern-shelf-transfer",
              "version":1,
              "kind":"$kind",
              $subtypeField
              "name":"Lantern",
              "filename":"lantern.json",
              "size":${source.size},
              "sha256":"${sha256(source)}",
              "mediaType":"application/json",
              "sourceUrl":"$sourceUrl",
              $adaptationField
              "expiresAt":"2026-09-02T12:10:00+08:00"
            }
        """.trimIndent()
    }

    private fun sha256(source: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(source)
        .joinToString("") { byte -> "%02x".format(byte) }
}
