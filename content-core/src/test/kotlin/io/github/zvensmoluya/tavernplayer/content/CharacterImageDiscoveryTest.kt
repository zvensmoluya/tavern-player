package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CharacterImageDiscoveryTest {
    @Test fun findsLiteralImagesAndKeepsOriginsWithoutInterpretingPrograms() {
        val raw = buildJsonObject {
            put("description", "![portrait](https://images.example/a.png) ![photo](https://images.example/photo) <img src='https://images.example/image?id=1&amp;size=2'>")
            put("script", "const images = ['https://images.example/a.png', 'https://images.example/b.webp']; import 'https://images.example/run.js';")
            put("a/b~c", "https://images.example/a.png")
            put("font", "https://images.example/font.ttf")
            put("dynamic", "https://images.example/${'$'}{person}.jpg")
            put("encoded", "aHR0cHM6Ly9pbWFnZXMuZXhhbXBsZS9oaWRkZW4ucG5n")
        }
        val result = CharacterImageDiscovery.discover(CharacterAsset("sample", name = "Sample", rawCard = raw))
        assertEquals(setOf("https://images.example/a.png", "https://images.example/photo", "https://images.example/image?id=1&size=2", "https://images.example/b.webp"), result.references.map { it.uri }.toSet())
        assertEquals(listOf("/description", "/script", "/a~1b~0c"), result.references.single { it.uri.endsWith("a.png") }.sourcePaths)
        assertTrue(result.notices.any { it.contains("动态") })
        assertEquals(raw, CharacterAsset("sample", name = "Sample", rawCard = raw).rawCard)
    }

    @Test fun handlesDeclaredExtensionlessAndEmbeddedImagesButNotOtherAssetKinds() {
        val card = CharacterAsset("sample", name = "Sample", assets = listOf(
            CharacterAssetReference("cover", "icon", "ccdefault:", "main", "png"),
            CharacterAssetReference("photo", "image", "https://images.example/get?id=1", "photo", "png"),
            CharacterAssetReference("font", "font", "https://images.example/get?id=2", "font", "ttf"),
        ), rawCard = buildJsonObject { put("description", "<img src='data:image/png;base64,AAAA'> https://images.example/a.gif") })
        val result = CharacterImageDiscovery.discover(card)
        assertEquals(3, result.references.size)
        assertEquals("cover", result.references.single { it.uri == "ccdefault:" }.localAssetId)
        assertTrue(result.notices.any { it.contains("PNG") })
    }

    @Test fun refusesUnboundedImageInventoriesRatherThanTruncatingSilently() {
        val card = CharacterAsset("sample", name = "Sample", rawCard = buildJsonObject {
            put("description", (0..512).joinToString("\n") { "https://images.example/$it.png" })
        })
        try { CharacterImageDiscovery.discover(card); fail("accepted unbounded inventory") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("512")) }
    }
}
