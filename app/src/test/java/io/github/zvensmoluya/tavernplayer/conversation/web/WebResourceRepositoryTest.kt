package io.github.zvensmoluya.tavernplayer.conversation.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebResourceRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun largeConversationResourcesSurviveRefreshAndOfflineReuse() = runBlocking {
        val root = folder.newFolder()
        val repo = WebResourceRepository(root, fetcher = { url ->
            WebDownload(ByteArray(9 * 1024 * 1024) { url.last().code.toByte() }, url, "application/javascript")
        })
        // Eight distinct 9 MiB bodies exceed both former byte limits.
        repeat(8) { repo.resolve("large", "hash", "https://cdn.example/$it") }
        assertEquals(8, repo.reprepare("large", "hash"))
        assertFalse(File(root, "tavern/web-resources").listFiles()!!.any { it.name.startsWith("refresh-") })
        val offline = WebResourceRepository(root, fetcher = { error("Offline") })
        repeat(8) {
            val resolved = offline.resolve("large", "hash", "https://cdn.example/$it")
            assertEquals(9L * 1024 * 1024, resolved.first.bytes)
            assertEquals(('0'.code + it).toByte(), resolved.second.last())
        }
    }

    @Test fun conversationAcceptsMoreThan512Bindings() = runBlocking {
        val root = folder.newFolder()
        val repo = WebResourceRepository(root, fetcher = { url -> WebDownload(byteArrayOf(1), url, "text/css") })
        repeat(513) { repo.resolve("many", "hash", "https://cdn.example/$it") }
        assertEquals(513, repo.reprepare("many", "hash"))
    }

    @Test fun explicitPreparationReplacesAllBindingsOnlyAfterEveryDownloadSucceeds() = runBlocking {
        val root = folder.newFolder()
        var version = "old"
        var failDownload = false
        val repo = WebResourceRepository(root, fetcher = { url ->
            if (failDownload && url.endsWith("b.js")) error("Unavailable")
            WebDownload(version.toByteArray(), url, "application/javascript")
        })
        val first = repo.resolve("c", "hash", "https://cdn.example/a.js")
        repo.resolve("c", "hash", "https://cdn.example/b.js")
        version = "new"; failDownload = true
        try { repo.reprepare("c", "hash"); fail("Partial update published") } catch (_: IllegalStateException) {}
        assertEquals(first.first.sha256, repo.resolve("c", "hash", first.first.url).first.sha256)
        assertFalse(File(root, "tavern/web-resources").listFiles()!!.any { it.name.startsWith("refresh-") })
        failDownload = false
        assertEquals(2, repo.reprepare("c", "hash"))
        val offline = WebResourceRepository(root, fetcher = { error("Offline") })
        assertEquals("new", offline.resolve("c", "hash", first.first.url).second.toString(Charsets.UTF_8))
    }
    @Test fun offlineReusePinsOriginalBytesAndGlobalIndexReusesAcrossConversations() = runBlocking {
        val root = folder.newFolder()
        var calls = 0
        val repo = WebResourceRepository(root, fetcher = { calls++; WebDownload("original".toByteArray(), "https://cdn.example/v1/main.js", "application/javascript") })
        val first = repo.resolve("c", "hash", "https://cdn.example/main.js")
        assertEquals("https://cdn.example/v1/main.js", first.first.finalUrl)
        val offline = WebResourceRepository(root, fetcher = { error("offline") })
        assertArrayEquals(first.second, offline.resolve("c", "hash", first.first.url).second)
        assertEquals(1, calls)
        File(root, "tavern/web-resources/blobs/${first.first.sha256}").writeText("corrupt")
        var changedCalls = 0
        val changed = WebResourceRepository(root, fetcher = { changedCalls++; WebDownload("changed".toByteArray(), first.first.finalUrl, "application/javascript") })
        try { changed.resolve("c", "hash", first.first.url); fail("Must retain the old version") } catch (_: IllegalArgumentException) {}
        assertEquals(1, changedCalls)
        assertEquals(first.first.sha256, repo.resolve("c", "hash", first.first.url).first.sha256)
        val reused = changed.resolve("new", "hash", first.first.url)
        assertEquals(first.first.sha256, reused.first.sha256)
        assertArrayEquals(first.second, reused.second)
        assertEquals(1, changedCalls)
    }
    @Test fun invalidRedirectAndEmptyResourceNeverPublishAnIndex() = runBlocking {
        val root = folder.newFolder()
        val repo = WebResourceRepository(root, fetcher = { WebDownload(byteArrayOf(1), "http://cdn.example/file", "text/css") })
        try { repo.resolve("c", "hash", "https://cdn.example/file"); fail("Unsafe redirect") } catch (_: IllegalArgumentException) {}
        assertFalse(File(root, "tavern/web-resources/sessions/c/index.json").exists())
        try { repo.resolve("../escape", "hash", "https://cdn.example/file"); fail("Invalid session") } catch (_: IllegalArgumentException) {}
    }
    @Test fun globalIndexReusesAcrossConversationsWithoutDownload() = runBlocking {
        val root = folder.newFolder()
        val bytes = "shared".toByteArray()
        val first = WebResourceRepository(root, fetcher = { WebDownload(bytes, "https://cdn.example/app.js", "application/javascript") })
            .resolve("a", "hash", "https://cdn.example/app.js")
        var calls = 0
        val second = WebResourceRepository(root, fetcher = { calls++; WebDownload("other".toByteArray(), "https://cdn.example/app.js", "application/javascript") })
        val reused = second.resolve("b", "hash", "https://cdn.example/app.js")
        assertEquals(0, calls)
        assertEquals(first.first.sha256, reused.first.sha256)
        assertArrayEquals(first.second, reused.second)
        assertTrue(File(root, "tavern/web-resources/sessions/b/index.json").readText().contains("https://cdn.example/app.js"))
        assertTrue(File(root, "tavern/web-resources/index.json").readText().contains("https://cdn.example/app.js"))
    }
    @Test fun globalHitServesTheLocalBlobWithoutDownload() = runBlocking {
        val root = folder.newFolder()
        val bytes = "shared".toByteArray()
        WebResourceRepository(root, fetcher = { WebDownload(bytes, "https://cdn.example/app.js", "application/javascript") })
            .resolve("a", "hash", "https://cdn.example/app.js")
        File(root, "tavern/web-resources/sessions/a/index.json").delete()
        var calls = 0
        val offline = WebResourceRepository(root, fetcher = { calls++; error("Offline") })
        val resolved = offline.resolve("a", "hash", "https://cdn.example/app.js")
        assertEquals(0, calls)
        assertArrayEquals(bytes, resolved.second)
        assertTrue(File(root, "tavern/web-resources/sessions/a/index.json").isFile)
    }
    @Test fun firstLoadMigratesSessionBindingsIntoTheGlobalIndex() = runBlocking {
        val root = folder.newFolder()
        val webRoot = File(root, "tavern/web-resources")
        val bytes = "shared".toByteArray()
        val sha = WebResourceRepository.hash(bytes)
        File(webRoot, "blobs").mkdirs()
        File(webRoot, "blobs/$sha").writeBytes(bytes)
        val index = """{"version":1,"characterHash":"hash","entries":{"https://cdn.example/app.js":{"url":"https://cdn.example/app.js","finalUrl":"https://cdn.example/app.js","sha256":"$sha","bytes":${bytes.size},"mimeType":"application/javascript"}}}"""
        for (id in listOf("a", "z")) {
            File(webRoot, "sessions/$id").mkdirs()
            File(webRoot, "sessions/$id/index.json").writeText(index)
        }
        File(webRoot, "sessions/broken").mkdirs()
        File(webRoot, "sessions/broken/index.json").writeText("{not json")
        File(webRoot, "sessions/bad").mkdirs()
        File(webRoot, "sessions/bad/index.json").writeText("""{"version":1,"characterHash":"hash","entries":{"https://cdn.example/bad.js":{"url":"https://cdn.example/bad.js","finalUrl":"https://cdn.example/bad.js","sha256":"not-a-hash","bytes":1,"mimeType":"application/javascript"}}}""")
        var calls = 0
        val repo = WebResourceRepository(root, fetcher = { calls++; error("Offline") })
        val resolved = repo.resolve("b", "hash", "https://cdn.example/app.js")
        assertEquals(0, calls)
        assertEquals(sha, resolved.first.sha256)
        assertArrayEquals(bytes, resolved.second)
        val global = File(webRoot, "index.json").readText()
        assertTrue(global.contains(sha))
        assertFalse(global.contains("bad.js"))
    }
    @Test fun cacheQuotaEvictsOnlyUnreferencedBlobs() = runBlocking {
        val root = folder.newFolder()
        val webRoot = File(root, "tavern/web-resources")
        File(webRoot, "blobs").mkdirs()
        val held = ByteArray(40) { 1 }
        val reused = ByteArray(40) { 2 }
        val older = ByteArray(30) { 3 }
        val newer = ByteArray(30) { 4 }
        val repo = WebResourceRepository(root, fetcher = { url ->
            WebDownload(if (url.endsWith("a.js")) reused else held, url, "application/javascript")
        }, maxCacheBytes = 160, evictionGraceMillis = 0)
        val protectedBlob = File(webRoot, "blobs/${WebResourceRepository.hash(reused)}")
        runCatching { repo.resolve("c", "hash", "https://cdn.example/a.js") }
        File(webRoot, "blobs/${WebResourceRepository.hash(held)}").apply { writeBytes(held) }
        val olderBlob = File(webRoot, "blobs/${WebResourceRepository.hash(older)}").apply { writeBytes(older); setLastModified(System.currentTimeMillis() - 86_400_000L) }
        val newerBlob = File(webRoot, "blobs/${WebResourceRepository.hash(newer)}").apply { writeBytes(newer) }
        assertArrayEquals(held, repo.resolve("b", "hash", "https://cdn.example/b.js").second)
        assertFalse(olderBlob.exists())
        assertTrue(newerBlob.exists())
        assertTrue(protectedBlob.exists())
        val offline = WebResourceRepository(root, fetcher = { error("offline") })
        assertArrayEquals(reused, offline.resolve("c", "hash", "https://cdn.example/a.js").second)
        assertArrayEquals(held, offline.resolve("b", "hash", "https://cdn.example/b.js").second)
    }
    @Test fun reprepareKeepsBindingsRegisteredDuringItsDownloadWindow() = runBlocking {
        val root = folder.newFolder()
        val contents = mutableMapOf("https://cdn.example/a.js" to "a1")
        lateinit var repo: WebResourceRepository
        var injecting = false
        var injected = false
        repo = WebResourceRepository(root, fetcher = { url ->
            if (injecting && !injected) {
                injected = true
                repo.resolve("c", "hash", "https://cdn.example/during.js")
            }
            WebDownload((contents[url] ?: "during").toByteArray(), url, "application/javascript")
        })
        repo.resolve("c", "hash", "https://cdn.example/a.js")
        contents["https://cdn.example/a.js"] = "a2"
        injecting = true
        assertEquals(1, repo.reprepare("c", "hash"))
        val index = File(root, "tavern/web-resources/sessions/c/index.json").readText()
        assertTrue(index.contains("during.js"))
        assertTrue(index.contains("a2"))
    }
    @Test fun corruptGlobalIndexIsRebuiltFromSessionBindings() = runBlocking {
        val root = folder.newFolder()
        val webRoot = File(root, "tavern/web-resources")
        val bytes = "shared".toByteArray()
        val repo = WebResourceRepository(root, fetcher = { WebDownload(bytes, "https://cdn.example/app.js", "application/javascript") })
        val first = repo.resolve("a", "hash", "https://cdn.example/app.js")
        File(webRoot, "index.json").writeText("{not json")
        var calls = 0
        val offline = WebResourceRepository(root, fetcher = { calls++; error("Offline") })
        assertArrayEquals(bytes, offline.resolve("b", "hash", "https://cdn.example/app.js").second)
        assertEquals(0, calls)
        assertTrue(File(webRoot, "index.json.corrupt").isFile)
        assertTrue(File(webRoot, "index.json").readText().contains(first.first.sha256))
    }
    @Test fun evictionIsSkippedWhenAnySessionIndexCannotBeRead() = runBlocking {
        val root = folder.newFolder()
        val webRoot = File(root, "tavern/web-resources")
        File(webRoot, "blobs").mkdirs()
        val held = ByteArray(30) { 7 }
        val repo = WebResourceRepository(root, fetcher = { url ->
            WebDownload(if (url.endsWith("a.js")) held else ByteArray(30) { 8 }, url, "application/javascript")
        }, maxCacheBytes = 40, evictionGraceMillis = 0)
        repo.resolve("a", "hash", "https://cdn.example/a.js")
        val unreferenced = File(webRoot, "blobs/${"9".repeat(64)}").apply { writeBytes(ByteArray(30) { 9 }); setLastModified(System.currentTimeMillis() - 86_400_000L) }
        File(webRoot, "sessions/broken").mkdirs()
        File(webRoot, "sessions/broken/index.json").writeText("{not json")
        repo.resolve("b", "hash", "https://cdn.example/b.js")
        assertTrue(unreferenced.isFile)
    }
    @Test fun globalHitWithoutItsBlobAcceptsTheChangedUpstream() = runBlocking {
        val root = folder.newFolder()
        val webRoot = File(root, "tavern/web-resources")
        val first = WebResourceRepository(root, fetcher = { WebDownload("v1".toByteArray(), "https://cdn.example/app.js", "application/javascript") })
            .resolve("a", "hash", "https://cdn.example/app.js")
        File(webRoot, "blobs/${first.first.sha256}").delete()
        var calls = 0
        val repo = WebResourceRepository(root, fetcher = { calls++; WebDownload("v2".toByteArray(), "https://cdn.example/app.js", "application/javascript") })
        val recovered = repo.resolve("b", "hash", "https://cdn.example/app.js")
        assertEquals(1, calls)
        assertEquals("v2", recovered.second.toString(Charsets.UTF_8))
        assertEquals("v2", repo.resolve("b", "hash", "https://cdn.example/app.js").second.toString(Charsets.UTF_8))
        assertEquals(1, calls)
        assertTrue(File(webRoot, "index.json").readText().contains(recovered.first.sha256))
    }
    @Test fun invalidBlobDirectoryEntriesAreNeitherCountedNorDeleted() = runBlocking {
        val root = folder.newFolder()
        val webRoot = File(root, "tavern/web-resources")
        File(webRoot, "blobs").mkdirs()
        val stale = File(webRoot, "blobs/not-a-hash").apply { writeBytes(ByteArray(200) { 1 }) }
        val directory = File(webRoot, "blobs/${"a".repeat(64)}").apply { mkdirs() }
        val held = ByteArray(50) { 2 }
        val repo = WebResourceRepository(root, fetcher = { url ->
            WebDownload(if (url.endsWith("a.js")) held else ByteArray(20) { 3 }, url, "application/javascript")
        }, maxCacheBytes = 80, evictionGraceMillis = 0)
        repo.resolve("a", "hash", "https://cdn.example/a.js")
        File(webRoot, "blobs/${WebResourceRepository.hash(held)}").writeBytes(held)
        val unreferenced = File(webRoot, "blobs/${WebResourceRepository.hash(ByteArray(40) { 4 })}")
            .apply { writeBytes(ByteArray(40) { 4 }); setLastModified(System.currentTimeMillis() - 86_400_000L) }
        // The 40-byte unreferenced blob is the only candidate over the 80-byte budget; had either invalid entry been
        // counted or the recent blob been treated as disposable, this eviction would not have picked it.
        repo.resolve("b", "hash", "https://cdn.example/b.js")
        assertFalse(unreferenced.exists())
        assertTrue(stale.isFile)
        assertEquals(200L, stale.length())
        assertTrue(directory.isDirectory)
    }
}
