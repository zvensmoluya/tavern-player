package io.github.zvensmoluya.tavernplayer.conversation.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebResourceRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun explicitPreparationReplacesAllBindingsOnlyAfterEveryDownloadSucceeds() = runBlocking {
        val root = folder.newFolder()
        var version = "old"
        var failDownload = false
        val repo = WebResourceRepository(root) { url ->
            if (failDownload && url.endsWith("b.js")) error("Unavailable")
            WebDownload(version.toByteArray(), url, "application/javascript")
        }
        val first = repo.resolve("c", "hash", "https://cdn.example/a.js")
        repo.resolve("c", "hash", "https://cdn.example/b.js")
        version = "new"; failDownload = true
        try { repo.reprepare("c", "hash"); fail("Partial update published") } catch (_: IllegalStateException) {}
        assertEquals(first.first.sha256, repo.resolve("c", "hash", first.first.url).first.sha256)
        failDownload = false
        assertEquals(2, repo.reprepare("c", "hash"))
        val offline = WebResourceRepository(root) { error("Offline") }
        assertEquals("new", offline.resolve("c", "hash", first.first.url).second.toString(Charsets.UTF_8))
    }
    @Test fun offlineReusePinsOriginalBytesAndRejectsChangedRecovery() = runBlocking {
        val root = folder.newFolder()
        var calls = 0
        val repo = WebResourceRepository(root) { calls++; WebDownload("original".toByteArray(), "https://cdn.example/v1/main.js", "application/javascript") }
        val first = repo.resolve("c", "hash", "https://cdn.example/main.js")
        assertEquals("https://cdn.example/v1/main.js", first.first.finalUrl)
        val offline = WebResourceRepository(root) { error("offline") }
        assertArrayEquals(first.second, offline.resolve("c", "hash", first.first.url).second)
        assertEquals(1, calls)
        File(root, "tavern/web-resources/blobs/${first.first.sha256}").writeText("corrupt")
        val changed = WebResourceRepository(root) { WebDownload("changed".toByteArray(), first.first.finalUrl, "application/javascript") }
        try { changed.resolve("c", "hash", first.first.url); fail("Must retain the old version") } catch (_: IllegalArgumentException) {}
        assertEquals(first.first.sha256, repo.resolve("c", "hash", first.first.url).first.sha256)
        assertNotEquals(first.first.sha256, changed.resolve("new", "hash", first.first.url).first.sha256)
    }
    @Test fun invalidRedirectAndEmptyResourceNeverPublishAnIndex() = runBlocking {
        val root = folder.newFolder()
        val repo = WebResourceRepository(root) { WebDownload(byteArrayOf(1), "http://cdn.example/file", "text/css") }
        try { repo.resolve("c", "hash", "https://cdn.example/file"); fail("Unsafe redirect") } catch (_: IllegalArgumentException) {}
        assertFalse(File(root, "tavern/web-resources/sessions/c/index.json").exists())
        try { repo.resolve("../escape", "hash", "https://cdn.example/file"); fail("Invalid session") } catch (_: IllegalArgumentException) {}
    }
}
