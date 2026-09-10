package io.github.zvensmoluya.tavernplayer.conversation.web

import io.github.zvensmoluya.tavernplayer.characters.HttpCharacterImageFetcher
import io.github.zvensmoluya.tavernplayer.storage.AtomicFileStore
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class WebDownload(val bytes: ByteArray, val finalUrl: String, val mimeType: String)
fun interface WebResourceFetcher { suspend fun fetch(url: String): WebDownload }

@Serializable
data class WebResourceEntry(
    val url: String,
    val finalUrl: String,
    val sha256: String,
    val bytes: Long,
    val mimeType: String,
    val lastUsedAt: Long = 0L,
)
@Serializable
private data class WebResourceIndex(val version: Int = 1, val characterHash: String, val entries: Map<String, WebResourceEntry> = emptyMap())
@Serializable
private data class WebResourceGlobalIndex(val version: Int = 1, val entries: Map<String, WebResourceEntry> = emptyMap())

/**
 * Blobs are content addressed and shared by the whole app; a conversation keeps a snapshot of the
 * URL bindings it pinned. Cached bytes are not browser cache and are evicted only when no
 * conversation references them.
 */
class WebResourceRepository(
    filesDir: File,
    private val fetcher: WebResourceFetcher = PublicWebResourceFetcher(),
    private val maxCacheBytes: Long = WEB_RESOURCE_CACHE_BYTES,
    private val evictionGraceMillis: Long = FRESH_BLOB_GRACE_MILLIS,
) {
    private val root = File(filesDir, "tavern/web-resources")
    private val globalIndexFile = File(root, "index.json")
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val globalMutex = Mutex()
    private val downloads = Semaphore(DOWNLOAD_CONCURRENCY)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** Blobs written but not yet referenced by any published index; eviction must leave them alone. */
    private val publishingHashes = mutableMapOf<String, Int>()

    /** Last-use times kept in memory; the global index is only rewritten once enough of them changed. */
    private val usageUpdates = LinkedHashMap<String, Long>()
    private var persistedUsage = emptyMap<String, Long>()

    private sealed interface Lookup {
        class Bound(val entry: WebResourceEntry, val bytes: ByteArray) : Lookup
        class Pinned(val entry: WebResourceEntry) : Lookup
        data object Unbound : Lookup
    }

    /** Explicit user action: publish a complete new binding set, or keep the old set unchanged. */
    suspend fun reprepare(conversationId: String, characterHash: String): Int = withContext(Dispatchers.IO) {
        val sessionMutex = mutexes.getOrPut(conversationId) { Mutex() }
        val indexFile = File(directory(conversationId), "index.json")
        val urls = sessionMutex.withLock {
            if (indexFile.exists()) readSessionIndex(indexFile, characterHash).entries.keys.toList() else emptyList()
        }
        if (urls.isEmpty()) return@withContext 0
        val downloads = linkedMapOf<String, WebDownload>()
        for (url in urls) {
            currentCoroutineContext().ensureActive()
            checkedUrl(url)
            val downloaded = withDownloadPermit { fetcher.fetch(url) }
            require(downloaded.bytes.size in 1..MAX_RESOURCE_BYTES) { "网页资源超过 8 MiB 或为空" }
            checkedUrl(downloaded.finalUrl)
            downloads[url] = downloaded
        }
        currentCoroutineContext().ensureActive()
        val refreshed = sessionMutex.withLock {
            val entries = linkedMapOf<String, WebResourceEntry>()
            for (url in urls) {
                val downloaded = downloads.getValue(url)
                entries[url] = WebResourceEntry(url, downloaded.finalUrl, hash(downloaded.bytes), downloaded.bytes.size.toLong(), downloaded.mimeType, System.currentTimeMillis())
                require(entries.size <= 512) { "会话网页资源超过 512 项" }
                require(entries.values.distinctBy { it.sha256 }.sumOf { it.bytes } <= MAX_CONVERSATION_BYTES) { "会话网页资源达到 64 MiB 上限" }
            }
            currentCoroutineContext().ensureActive()
            // Bindings registered by a request that completed during the download window keep their version.
            val index = readSessionIndex(indexFile, characterHash)
            val added = index.entries.filterKeys { it !in entries }
            require((entries.values + added.values).distinctBy { it.sha256 }.sumOf { it.bytes } <= MAX_CONVERSATION_BYTES) { "会话网页资源达到 64 MiB 上限" }
            val published = entries + added
            val pendingBytes = entries.values.filter { entry ->
                val file = blob(entry.sha256).takeIf { it.isFile }
                file == null || file.length() != entry.bytes
            }.sumOf { it.bytes }
            withLiveHashes(entries.values.map { it.sha256 }) {
                syncCacheQuota(entries.values.map { it.sha256 }.toSet(), pendingBytes)
                entries.values.forEach { entry -> saveBlob(entry.sha256, downloads.getValue(entry.url).bytes) }
                writeSessionIndexSafely(indexFile, WebResourceIndex(characterHash = characterHash, entries = published))
                writeGlobalIndexSafely { it.copy(entries = it.entries + entries) }
            }
            entries.size
        }
        return@withContext refreshed
    }

    suspend fun resolve(conversationId: String, characterHash: String, url: String): Pair<WebResourceEntry, ByteArray> = withContext(Dispatchers.IO) {
        checkedUrl(url)
        val sessionMutex = mutexes.getOrPut(conversationId) { Mutex() }
        val indexFile = File(directory(conversationId), "index.json")
        when (val lookup = sessionMutex.withLock { inspect(indexFile, characterHash, url) }) {
            is Lookup.Bound -> lookup.entry to lookup.bytes
            is Lookup.Pinned -> recoverPinned(sessionMutex, indexFile, characterHash, url, lookup)
            Lookup.Unbound -> registerNew(sessionMutex, indexFile, characterHash, url)
        }
    }

    private suspend fun inspect(indexFile: File, characterHash: String, url: String): Lookup {
        val index = readSessionIndex(indexFile, characterHash)
        index.entries[url]?.let { entry ->
            val bytes = readBlob(entry)
            if (bytes != null) {
                globalMutex.withLock { touchGlobalLocked(url) }
                return Lookup.Bound(entry, bytes)
            }
            return Lookup.Pinned(entry)
        }
        require(index.entries.size < 512) { "会话网页资源超过 512 项" }
        val shared = globalMutex.withLock { loadGlobalIndexLocked().entries[url] } ?: return Lookup.Unbound
        val bytes = readBlob(shared)
        if (bytes == null) {
            // This conversation has no binding for the URL yet, so a changed upstream is simply the new version.
            return Lookup.Unbound
        }
        require((index.entries.values + shared).distinctBy { it.sha256 }.sumOf { it.bytes } <= MAX_CONVERSATION_BYTES) { "会话网页资源达到 64 MiB 上限" }
        // Hold the inherited hash while its snapshot is written, so eviction cannot take it in between.
        withLiveHashes(listOf(shared.sha256)) {
            writeSessionIndexSafely(indexFile, index.copy(entries = index.entries + (url to shared.copy())))
        }
        globalMutex.withLock { touchGlobalLocked(url) }
        return Lookup.Bound(shared, bytes)
    }

    private suspend fun recoverPinned(sessionMutex: Mutex, indexFile: File, characterHash: String, url: String, pinned: Lookup.Pinned): Pair<WebResourceEntry, ByteArray> {
        val downloaded = withDownloadPermit { fetcher.fetch(url) }
        require(hash(downloaded.bytes) == pinned.entry.sha256) { "原版本资源已缺失，远端内容已变化；请重新准备并新建会话" }
        return sessionMutex.withLock {
            saveBlob(pinned.entry.sha256, downloaded.bytes)
            pinned.entry to downloaded.bytes
        }
    }

    private suspend fun registerNew(sessionMutex: Mutex, indexFile: File, characterHash: String, url: String): Pair<WebResourceEntry, ByteArray> {
        val downloaded = withDownloadPermit { fetcher.fetch(url) }
        require(downloaded.bytes.size in 1..MAX_RESOURCE_BYTES) { "网页资源超过 8 MiB 或为空" }
        checkedUrl(downloaded.finalUrl)
        val sha = hash(downloaded.bytes)
        return sessionMutex.withLock {
            val index = readSessionIndex(indexFile, characterHash)
            val raced = index.entries[url]
            if (raced != null) {
                val bytes = readBlob(raced)
                if (bytes != null) {
                    globalMutex.withLock { touchGlobalLocked(url) }
                    return@withLock raced to bytes
                }
                require(sha == raced.sha256) { "原版本资源已缺失，远端内容已变化；请重新准备并新建会话" }
                saveBlob(raced.sha256, downloaded.bytes)
                return@withLock raced to downloaded.bytes
            }
            require(index.entries.size < 512) { "会话网页资源超过 512 项" }
            val entry = WebResourceEntry(url, downloaded.finalUrl, sha, downloaded.bytes.size.toLong(), downloaded.mimeType, System.currentTimeMillis())
            require((index.entries.values + entry).distinctBy { it.sha256 }.sumOf { it.bytes } <= MAX_CONVERSATION_BYTES) { "会话网页资源达到 64 MiB 上限" }
            withLiveHashes(listOf(sha)) {
                syncCacheQuota(setOf(sha), downloaded.bytes.size.toLong())
                saveBlob(sha, downloaded.bytes)
                writeSessionIndexSafely(indexFile, index.copy(entries = index.entries + (url to entry)))
                writeGlobalIndexSafely { it.copy(entries = it.entries + (url to entry)) }
            }
            entry to downloaded.bytes
        }
    }

    private suspend fun withDownloadPermit(block: suspend () -> WebDownload): WebDownload = downloads.withPermit { block() }

    private suspend fun <T> withLiveHashes(hashes: Collection<String>, block: suspend () -> T): T {
        if (hashes.isEmpty()) return block()
        globalMutex.withLock { hashes.forEach { publishingHashes.merge(it, 1, Int::plus) } }
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                globalMutex.withLock { hashes.forEach { publishingHashes.computeIfPresent(it) { _, count -> count - 1 } } }
            }
        }
    }

    /** Session bindings are the source of truth; a failed global index write only costs cross-conversation reuse. */
    private fun writeSessionIndexSafely(indexFile: File, index: WebResourceIndex) {
        AtomicFileStore.writeUtf8(indexFile, json.encodeToString(index))
    }

    private suspend fun writeGlobalIndexSafely(update: (WebResourceGlobalIndex) -> WebResourceGlobalIndex) {
        runCatching {
            globalMutex.withLock {
                val global = loadGlobalIndexLocked()
                AtomicFileStore.writeUtf8(globalIndexFile, json.encodeToString(update(global)))
            }
        }
    }

    private suspend fun syncCacheQuota(protect: Set<String>, pendingBytes: Long) {
        val blobs = File(root, "blobs").listFiles().orEmpty().filter { it.isFile && BLOB_NAME.matches(it.name) }
        if (blobs.isEmpty()) return
        var total = blobs.sumOf { it.length() } + pendingBytes
        if (total <= maxCacheBytes) return
        val live = globalMutex.withLock { referencedBlobs()?.plus(publishingHashes.keys) } ?: return
        val lastUsed = globalMutex.withLock { globalLastUsedAt() }
        val cutoff = System.currentTimeMillis() - evictionGraceMillis
        val candidates = blobs.filter { it.name !in protect && it.name !in live && it.lastModified() < cutoff }
            .sortedWith(compareBy<File>({ lastUsed[it.name] ?: it.lastModified() }, { it.name }))
        for (candidate in candidates) {
            if (total <= maxCacheBytes) break
            val size = candidate.length()
            if (candidate.delete()) total -= size
        }
    }

    /** Every hash any session index still points at. Returns null when an index cannot be read, so eviction gives up. */
    private fun referencedBlobs(): Set<String>? {
        val shas = mutableSetOf<String>()
        File(root, "sessions").listFiles().orEmpty().filter { it.isDirectory }.forEach { session ->
            val file = File(session, "index.json")
            if (!file.isFile) return@forEach
            val index = runCatching { json.decodeFromString<WebResourceIndex>(file.readText()) }.getOrNull() ?: return null
            index.entries.values.forEach { shas += it.sha256 }
        }
        return shas
    }

    private fun globalLastUsedAt(): Map<String, Long> {
        if (!globalIndexFile.isFile) return emptyMap()
        val index = runCatching { json.decodeFromString<WebResourceGlobalIndex>(globalIndexFile.readText()) }.getOrNull() ?: return emptyMap()
        val merged = lastUsedAt()
        val times = mutableMapOf<String, Long>()
        index.entries.forEach { (url, entry) ->
            val time = maxOf(entry.lastUsedAt, merged[url] ?: 0L)
            if (time > (times[entry.sha256] ?: 0L)) times[entry.sha256] = time
        }
        return times
    }

    private fun readSessionIndex(indexFile: File, characterHash: String): WebResourceIndex {
        val index = if (indexFile.exists()) json.decodeFromString<WebResourceIndex>(indexFile.readText()) else WebResourceIndex(characterHash = characterHash)
        require(index.version == 1 && index.characterHash == characterHash) { "网页资源与会话角色不匹配" }
        return index
    }

    private fun readBlob(entry: WebResourceEntry): ByteArray? {
        val file = blob(entry.sha256).takeIf { it.isFile } ?: return null
        val bytes = file.readBytes()
        return bytes.takeIf { it.size.toLong() == entry.bytes && hash(it) == entry.sha256 }
    }

    private fun touchGlobalLocked(url: String) {
        usageUpdates[url] = System.currentTimeMillis()
        val changed = usageUpdates.count { (key, time) -> persistedUsage[key] != time }
        if (changed < USAGE_FLUSH_THRESHOLD) return
        val global = loadGlobalIndexLocked()
        val entries = global.entries.mapValues { (key, entry) -> usageUpdates[key]?.let { entry.copy(lastUsedAt = it) } ?: entry }
        // Last-use times only order eviction, so a failed write must never fail a cache hit.
        runCatching { AtomicFileStore.writeUtf8(globalIndexFile, json.encodeToString(global.copy(entries = entries))) }
            .onSuccess { persistedUsage = usageUpdates.toMap() }
    }

    private fun loadGlobalIndexLocked(): WebResourceGlobalIndex {
        if (globalIndexFile.isFile) {
            val existing = runCatching { json.decodeFromString<WebResourceGlobalIndex>(globalIndexFile.readText()) }.getOrNull()
            if (existing != null && existing.version == 1) {
                if (persistedUsage.isEmpty()) persistedUsage = existing.entries.mapValues { (_, entry) -> entry.lastUsedAt }
                return existing
            }
            globalIndexFile.renameTo(File(root, "index.json.corrupt"))
        }
        val migrated = migrateGlobalIndex()
        // A failed rebuild must not turn a local cache hit into an error; the next request retries.
        runCatching { AtomicFileStore.writeUtf8(globalIndexFile, json.encodeToString(migrated)) }
        persistedUsage = migrated.entries.mapValues { (_, entry) -> entry.lastUsedAt }
        return migrated
    }

    private fun lastUsedAt(): Map<String, Long> = persistedUsage.toMutableMap().also { merged -> usageUpdates.forEach { (url, time) -> if ((merged[url] ?: 0L) < time) merged[url] = time } }

    private fun migrateGlobalIndex(): WebResourceGlobalIndex {
        val entries = linkedMapOf<String, WebResourceEntry>()
        File(root, "sessions").listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.forEach { session ->
            val file = File(session, "index.json")
            if (!file.isFile) return@forEach
            val index = runCatching { json.decodeFromString<WebResourceIndex>(file.readText()) }.getOrNull() ?: return@forEach
            if (index.version != 1) return@forEach
            index.entries.forEach { (url, entry) -> if (url !in entries && validEntry(url, entry)) entries[url] = entry }
        }
        return WebResourceGlobalIndex(entries = entries)
    }

    private fun validEntry(url: String, entry: WebResourceEntry): Boolean =
        entry.url == url && entry.bytes in 1..MAX_RESOURCE_BYTES.toLong() && entry.mimeType.isNotBlank() && BLOB_NAME.matches(entry.sha256) && validUrl(url) && validUrl(entry.finalUrl)

    private fun validUrl(value: String): Boolean = runCatching { checkedUrl(value) }.isSuccess

    private fun directory(id: String): File {
        require(id.isNotBlank() && id !in setOf(".", "..") && '/' !in id && '\\' !in id)
        return File(root, "sessions/$id").also { require(it.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())); check(it.mkdirs() || it.isDirectory) }
    }
    private fun blob(sha: String): File {
        require(BLOB_NAME.matches(sha))
        return File(root, "blobs/$sha")
    }
    private fun saveBlob(sha: String, bytes: ByteArray) {
        val target = blob(sha)
        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
        if (target.isFile && hash(target.readBytes()) == sha) return
        val temp = File(target.parentFile, ".$sha.${java.util.UUID.randomUUID()}.tmp")
        try {
            java.io.FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            try { java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        } finally { temp.delete() }
    }
    companion object {
        const val MAX_RESOURCE_BYTES = 8 * 1024 * 1024
        const val MAX_CONVERSATION_BYTES = 64L * 1024 * 1024
        const val WEB_RESOURCE_CACHE_BYTES = 512L * 1024 * 1024
        private const val DOWNLOAD_CONCURRENCY = 6
        private const val USAGE_FLUSH_THRESHOLD = 32
        private const val FRESH_BLOB_GRACE_MILLIS = 10 * 60 * 1000L
        private val BLOB_NAME = Regex("[a-f0-9]{64}")
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        internal fun checkedUrl(value: String): HttpUrl {
            val url = value.toHttpUrlOrNull() ?: error("网页资源地址无效")
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) { "网页资源仅支持无凭据的 HTTPS 地址" }
            return url
        }
    }
}

class PublicWebResourceFetcher(private val client: OkHttpClient = defaultClient()) : WebResourceFetcher {
    override suspend fun fetch(url: String): WebDownload = withContext(Dispatchers.IO) {
        var next = WebResourceRepository.checkedUrl(url)
        repeat(4) { hop ->
            currentCoroutineContext().ensureActive()
            val call = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
                .newCall(Request.Builder().url(next).get().build())
            // Tie cancellation to the blocking HTTP call as well as to reads.
            val cancellation = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        require(hop < 3) { "网页资源重定向次数过多" }
                        next = WebResourceRepository.checkedUrl(next.resolve(response.header("Location") ?: error("重定向缺少地址"))?.toString() ?: error("重定向地址无效"))
                    } else {
                        require(response.isSuccessful) { "网页资源服务器返回 HTTP ${response.code}" }
                        val body = response.body
                        require(body.contentLength() <= WebResourceRepository.MAX_RESOURCE_BYTES) { "网页资源超过 8 MiB" }
                        val bytes = java.io.ByteArrayOutputStream()
                        body.byteStream().use { stream ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = stream.read(buffer); if (count < 0) break
                                require(bytes.size() + count <= WebResourceRepository.MAX_RESOURCE_BYTES) { "网页资源超过 8 MiB" }
                                bytes.write(buffer, 0, count)
                            }
                        }
                        val type = body.contentType()?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream"
                        return@withContext WebDownload(bytes.toByteArray(), next.toString(), type)
                    }
                }
            } finally { cancellation.cancel() }
        }
        error("网页资源获取失败")
    }
    companion object {
        private fun defaultClient() = OkHttpClient.Builder().dns { host ->
            Dns.SYSTEM.lookup(host).also { addresses ->
                require(addresses.isNotEmpty() && addresses.all(HttpCharacterImageFetcher::publicAddress)) { "网页资源地址必须指向公网" }
            }
        }.connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    }
}
