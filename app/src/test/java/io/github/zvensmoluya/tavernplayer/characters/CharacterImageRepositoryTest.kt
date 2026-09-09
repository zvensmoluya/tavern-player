package io.github.zvensmoluya.tavernplayer.characters

import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class CharacterImageRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9H0AAAAASUVORK5CYII=")
    private val inspector = StaticImageInspector { if (it.contentEquals(png)) StaticImageInfo(1, 1, "image/png") else null }
    private fun card(vararg urls: String) = CharacterAsset("sample", name = "Sample", sourceSha256 = "a".repeat(64),
        rawCard = buildJsonObject { put("description", urls.joinToString("\n")) })
    private fun repository(root: File, card: CharacterAsset, fetcher: CharacterImageFetcher) =
        CharacterImageRepository(root, { if (it == card.id) card else null }, { _, _ -> null }, fetcher, inspector)

    @Test fun webRequestsReusePreparedImagesAndPersistDynamicReferences() = runBlocking {
        val root = temporary.newFolder()
        val card = card("https://images.example/a.png")
        var calls = 0
        val store = repository(root, card) { _, _ -> calls++; png }
        store.prepare(card.id)
        store.resolveForWeb(card.id, "https://images.example/a.png")
        assertEquals(1, calls)
        store.resolveForWeb(card.id, "https://images.example/dynamic.png")
        assertEquals(2, calls)
        val offline = repository(root, card) { _, _ -> error("Must reuse original bytes") }
        assertTrue(offline.resolveForWeb(card.id, "https://images.example/dynamic.png").saved)
        assertEquals(2, offline.load(card.id).savedCount)
    }

    @Test fun retainsDownloadsAcrossRestartAndDeduplicatesBytesWithoutRefreshingUrls() = runBlocking {
        val root = temporary.newFolder()
        val card = card("https://images.example/a.png", "https://images.example/b.png", "data:image/png;base64,${Base64.getEncoder().encodeToString(png)}")
        var requests = 0
        val store = repository(root, card) { _, _ -> requests++; png }
        assertEquals(0, store.load(card.id).savedCount)
        assertEquals(0, requests)
        store.prepare(card.id)
        val state = store.states.value.getValue(card.id)
        assertEquals(3, state.savedCount)
        assertEquals(2, requests)
        assertEquals(png.size.toLong(), state.savedBytes)
        assertEquals(1, root.walkTopDown().count { it.extension == "image" })
        val file = store.file(card.id, state.entries.first())!!
        assertTrue(file.canonicalPath.startsWith(File(root, "tavern/characters/sample/resources/images").canonicalPath))
        assertArrayEquals(png, file.readBytes())
        val offline = repository(root, card) { _, _ -> error("offline must not fetch saved images") }
        offline.prepare(card.id)
        assertEquals(3, offline.states.value.getValue(card.id).savedCount)
        assertEquals(state.entries.map { it.sha256 }, offline.states.value.getValue(card.id).entries.map { it.sha256 })
    }

    @Test fun retriesFailuresAndCorruptFilesWithoutLosingCompletedImages() = runBlocking {
        val root = temporary.newFolder()
        val card = card("https://images.example/a.png", "https://images.example/b.png")
        val requests = mutableListOf<String>()
        var failSecond = true
        val store = repository(root, card) { url, _ ->
            requests += url
            if (url.endsWith("b.png") && failSecond) error("temporary failure") else png
        }
        store.prepare(card.id)
        assertEquals(1, store.states.value.getValue(card.id).savedCount)
        assertTrue(store.load(card.id).entries.last().error != null)
        failSecond = false
        store.prepare(card.id)
        assertEquals(2, store.states.value.getValue(card.id).savedCount)
        assertEquals(1, requests.count { it.endsWith("a.png") })
        store.file(card.id, store.states.value.getValue(card.id).entries.first())!!.writeBytes(ByteArray(png.size))
        assertEquals(0, store.load(card.id).savedCount)
        store.prepare(card.id)
        assertEquals(2, store.states.value.getValue(card.id).savedCount)
    }

    @Test fun cancellationPreservesCompletedFilesAndLeavesUnfinishedItemsRetryable() = runBlocking {
        val root = temporary.newFolder()
        val card = card("https://images.example/a.png", "https://images.example/b.png")
        val waiting = CompletableDeferred<Unit>()
        val store = repository(root, card) { url, _ ->
            if (url.endsWith("a.png")) png else { waiting.complete(Unit); awaitCancellation() }
        }
        val task = launch { store.prepare(card.id) }
        withTimeout(5000) { waiting.await() }
        task.cancelAndJoin()
        assertFalse(store.states.value.getValue(card.id).busy)
        assertEquals(1, repository(root, card) { _, _ -> error("offline") }.load(card.id).savedCount)
        val retry = repository(root, card) { _, _ -> png }
        retry.prepare(card.id)
        assertEquals(2, retry.states.value.getValue(card.id).savedCount)
    }

    @Test fun rejectsNonImagesAndOversizedDownloadsBeforePersisting() = runBlocking {
        val root = temporary.newFolder()
        val card = card("https://images.example/a.png")
        for (bytes in listOf("<html>not an image</html>".toByteArray(), ByteArray(CharacterImageRepository.MAX_IMAGE_BYTES + 1))) {
            val store = repository(root, card) { _, _ -> bytes }
            store.prepare(card.id)
            assertEquals(0, store.states.value.getValue(card.id).savedCount)
            assertNotNull(store.states.value.getValue(card.id).entries.single().error)
            assertEquals(0, root.walkTopDown().count { it.extension == "image" })
        }
    }
}
