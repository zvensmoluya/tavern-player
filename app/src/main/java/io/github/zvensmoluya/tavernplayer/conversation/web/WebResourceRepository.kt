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
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class WebDownload(val bytes: ByteArray, val finalUrl: String, val mimeType: String)
fun interface WebResourceFetcher { suspend fun fetch(url: String): WebDownload }

@Serializable
data class WebResourceEntry(val url: String, val finalUrl: String, val sha256: String, val bytes: Long, val mimeType: String)
@Serializable
private data class WebResourceIndex(val version: Int = 1, val characterHash: String, val entries: Map<String, WebResourceEntry> = emptyMap())

/** An immutable URL binding per conversation. Cached bytes are not browser cache and are not evicted. */
class WebResourceRepository(
    filesDir: File,
    private val fetcher: WebResourceFetcher = PublicWebResourceFetcher(),
) {
    private val root = File(filesDir, "tavern/web-resources")
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** Explicit user action: publish a complete new binding set, or keep the old set unchanged. */
    suspend fun reprepare(conversationId: String, characterHash: String): Int = withContext(Dispatchers.IO) {
        mutexes.getOrPut(conversationId) { Mutex() }.withLock {
            val indexFile = File(directory(conversationId), "index.json")
            if (!indexFile.exists()) return@withLock 0
            val index = json.decodeFromString<WebResourceIndex>(indexFile.readText())
            require(index.version == 1 && index.characterHash == characterHash) { "网页资源与会话角色不匹配" }
            val entries = linkedMapOf<String, WebResourceEntry>()
            for (url in index.entries.keys) {
                currentCoroutineContext().ensureActive()
                checkedUrl(url)
                val downloaded = fetcher.fetch(url)
                require(downloaded.bytes.size in 1..MAX_RESOURCE_BYTES) { "网页资源超过 8 MiB 或为空" }
                checkedUrl(downloaded.finalUrl)
                val entry = WebResourceEntry(url, downloaded.finalUrl, hash(downloaded.bytes), downloaded.bytes.size.toLong(), downloaded.mimeType)
                entries[url] = entry
                require(entries.values.distinctBy { it.sha256 }.sumOf { it.bytes } <= MAX_CONVERSATION_BYTES) { "会话网页资源达到 64 MiB 上限" }
                saveBlob(entry.sha256, downloaded.bytes)
            }
            currentCoroutineContext().ensureActive()
            AtomicFileStore.writeUtf8(indexFile, json.encodeToString(index.copy(entries = entries)))
            entries.size
        }
    }

    suspend fun resolve(conversationId: String, characterHash: String, url: String): Pair<WebResourceEntry, ByteArray> = withContext(Dispatchers.IO) {
        checkedUrl(url)
        mutexes.getOrPut(conversationId) { Mutex() }.withLock {
            val directory = directory(conversationId)
            val indexFile = File(directory, "index.json")
            val index = if (indexFile.exists()) json.decodeFromString<WebResourceIndex>(indexFile.readText()) else WebResourceIndex(characterHash = characterHash)
            require(index.version == 1 && index.characterHash == characterHash) { "网页资源与会话角色不匹配" }
            index.entries[url]?.let { entry ->
                val bytes = blob(entry.sha256).takeIf { it.isFile }?.readBytes()
                if (bytes != null && bytes.size.toLong() == entry.bytes && hash(bytes) == entry.sha256) return@withLock entry to bytes
                val downloaded = fetcher.fetch(url)
                require(hash(downloaded.bytes) == entry.sha256) { "原版本资源已缺失，远端内容已变化；请重新准备并新建会话" }
                saveBlob(entry.sha256, downloaded.bytes)
                return@withLock entry to downloaded.bytes
            }
            require(index.entries.size < 512) { "会话网页资源超过 512 项" }
            val downloaded = fetcher.fetch(url)
            require(downloaded.bytes.size in 1..MAX_RESOURCE_BYTES) { "网页资源超过 8 MiB 或为空" }
            checkedUrl(downloaded.finalUrl)
            val entry = WebResourceEntry(url, downloaded.finalUrl, hash(downloaded.bytes), downloaded.bytes.size.toLong(), downloaded.mimeType)
            require((index.entries.values + entry).distinctBy { it.sha256 }.sumOf { it.bytes } <= MAX_CONVERSATION_BYTES) { "会话网页资源达到 64 MiB 上限" }
            saveBlob(entry.sha256, downloaded.bytes)
            AtomicFileStore.writeUtf8(indexFile, json.encodeToString(index.copy(entries = index.entries + (url to entry))))
            entry to downloaded.bytes
        }
    }

    private fun directory(id: String): File {
        require(id.isNotBlank() && id !in setOf(".", "..") && '/' !in id && '\\' !in id)
        return File(root, "sessions/$id").also { require(it.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())); check(it.mkdirs() || it.isDirectory) }
    }
    private fun blob(sha: String): File {
        require(Regex("[a-f0-9]{64}").matches(sha))
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
