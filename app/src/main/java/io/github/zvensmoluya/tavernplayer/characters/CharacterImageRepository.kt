package io.github.zvensmoluya.tavernplayer.characters

import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.content.CharacterImageDiscovery
import io.github.zvensmoluya.tavernplayer.content.CharacterImageReference
import io.github.zvensmoluya.tavernplayer.storage.AtomicFileStore
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class CharacterImageEntry(
    val reference: CharacterImageReference,
    val sha256: String? = null,
    val bytes: Long = 0,
    val mediaType: String? = null,
    val error: String? = null,
) { val saved: Boolean get() = sha256 != null }

data class CharacterImageState(
    val entries: List<CharacterImageEntry> = emptyList(),
    val notices: List<String> = emptyList(),
    val busy: Boolean = false,
    val currentId: String? = null,
) {
    val savedCount: Int get() = entries.count { it.saved }
    val savedBytes: Long get() = entries.filter { it.saved }.distinctBy { it.sha256 }.sumOf { it.bytes }
}

@Serializable private data class StoredCharacterImage(val id: String, val sha256: String? = null, val bytes: Long = 0,
    val mediaType: String? = null, val error: String? = null)
@Serializable private data class CharacterImageIndex(val sourceSha256: String, val version: Int = 1,
    val images: List<StoredCharacterImage> = emptyList())

/** Original files are persistent character data under filesDir, never MediaStore or cacheDir. */
class CharacterImageRepository internal constructor(
    filesDir: File,
    private val character: (String) -> CharacterAsset?,
    private val localAsset: (String, String) -> File?,
    private val fetcher: CharacterImageFetcher = HttpCharacterImageFetcher(),
    private val inspector: StaticImageInspector = AndroidStaticImageInspector,
) {
    private val root = File(filesDir, "tavern/characters")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val _states = MutableStateFlow<Map<String, CharacterImageState>>(emptyMap())
    val states: StateFlow<Map<String, CharacterImageState>> = _states.asStateFlow()

    suspend fun load(characterId: String) = withContext(Dispatchers.IO) {
        locks.getOrPut(characterId) { Mutex() }.withLock { loadLocked(characterId) }
    }

    suspend fun prepare(characterId: String) = withContext(Dispatchers.IO) {
        locks.getOrPut(characterId) { Mutex() }.withLock {
            var state = loadLocked(characterId).copy(busy = true)
            publish(characterId, state)
            try {
                for (entry in state.entries.filterNot { it.saved }) {
                    currentCoroutineContext().ensureActive()
                    state = state.copy(currentId = entry.reference.id)
                    publish(characterId, state)
                    var capacityReached = false
                    val next = try {
                        val bytes = obtain(characterId, entry.reference)
                        currentCoroutineContext().ensureActive()
                        require(bytes.size in 1..MAX_IMAGE_BYTES) { "图片超过 8 MiB 或内容为空" }
                        val info = inspector.inspect(bytes) ?: error("响应不是可读取的图片")
                        require(info.mediaType in setOf("image/png", "image/jpeg", "image/webp")) { "暂不支持这种图片格式" }
                        require(info.width in 1..8192 && info.height in 1..8192 && info.width.toLong() * info.height <= 32_000_000L) { "图片尺寸超过限制" }
                        val hash = hash(bytes)
                        val folder = directory(characterId)
                        val used = folder.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".image") }.sumOf { it.length() }
                        val replaced = File(folder, "$hash.image").takeIf { it.isFile }?.length() ?: 0L
                        if (used - replaced + bytes.size > MAX_CHARACTER_BYTES) {
                            capacityReached = true
                            error("角色图片已达到 256 MiB 上限")
                        }
                        saveImage(directory(characterId), hash, bytes)
                        entry.copy(sha256 = hash, bytes = bytes.size.toLong(), mediaType = info.mediaType, error = null)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        entry.copy(error = error.message?.take(160) ?: "图片准备失败")
                    }
                    val updated = state.copy(entries = state.entries.map { if (it.reference.id == next.reference.id) next else it })
                    // Publish success only after the index is durable. A failed save leaves an unreferenced blob, never a false success.
                    persist(characterId, updated)
                    state = updated
                    publish(characterId, state)
                    if (capacityReached) break
                }
            } finally {
                publish(characterId, state.copy(busy = false, currentId = null))
            }
        }
    }

    fun file(characterId: String, entry: CharacterImageEntry): File? {
        val sha = entry.sha256 ?: return null
        if (!SHA.matches(sha)) return null
        return File(directory(characterId), "$sha.image").takeIf { it.isFile }
    }

    private fun loadLocked(characterId: String): CharacterImageState {
        val card = requireNotNull(character(characterId)) { "角色不存在" }
        val discovery = CharacterImageDiscovery.discover(card)
        val directory = directory(characterId)
        val indexFile = File(directory, "index.json")
        val index = if (indexFile.exists()) {
            val decoded = runCatching { json.decodeFromString<CharacterImageIndex>(indexFile.readText()) }
                .getOrElse { error("角色资源记录无法读取，原图已保留") }
            require(decoded.version == 1 && decoded.sourceSha256 == card.sourceSha256) { "角色资源记录与原卡不匹配" }
            decoded.images.associateBy { it.id }
        } else emptyMap()
        AtomicFileStore.cleanupTemporaryFiles(directory)
        val verified = mutableMapOf<String, Boolean>()
        val state = CharacterImageState(discovery.references.map { ref ->
            val old = index[ref.id]
            val sha = old?.sha256
            val valid = sha != null && SHA.matches(sha) && verified.getOrPut(sha) {
                val file = File(directory, "$sha.image")
                file.isFile && file.length() in 1..MAX_IMAGE_BYTES.toLong() && file.length() == old.bytes && hash(file.readBytes()) == sha
            }
            if (valid) CharacterImageEntry(ref, sha, old.bytes, old.mediaType)
            else CharacterImageEntry(ref, error = if (sha != null) "本地文件缺失或损坏，可重新准备" else old?.error)
        }, discovery.notices)
        publish(characterId, state)
        return state
    }

    private suspend fun obtain(characterId: String, ref: CharacterImageReference): ByteArray {
        ref.localAssetId?.let { localAsset(characterId, it) }?.let {
            require(it.length() in 1..MAX_IMAGE_BYTES.toLong()) { "本地图片超过 8 MiB" }
            return it.readBytes()
        }
        if (ref.uri.startsWith("data:", ignoreCase = true)) {
            val encoded = ref.uri.substringAfter(',')
            require(encoded.length <= 12 * 1024 * 1024) { "内嵌图片过大" }
            return Base64.getDecoder().decode(encoded)
        }
        require(ref.uri != "ccdefault:") { "原卡图片不可用" }
        return fetcher.fetch(ref.uri, MAX_IMAGE_BYTES)
    }

    private fun directory(id: String): File {
        require(id.isNotBlank() && '/' !in id && '\\' !in id && id != "." && id != "..") { "角色标识无效" }
        val dir = File(root, "$id/resources/images")
        check(dir.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "角色资源路径无效" }
        return dir
    }

    private fun persist(id: String, state: CharacterImageState) {
        val card = requireNotNull(character(id)) { "角色不存在" }
        val index = CharacterImageIndex(card.sourceSha256, images = state.entries.map {
            StoredCharacterImage(it.reference.id, it.sha256, it.bytes, it.mediaType, it.error)
        })
        AtomicFileStore.writeUtf8(File(directory(id), "index.json"), json.encodeToString(index))
    }

    private fun saveImage(directory: File, sha: String, bytes: ByteArray) {
        check(directory.mkdirs() || directory.isDirectory) { "无法创建角色资源目录" }
        val destination = File(directory, "$sha.image")
        if (destination.isFile && destination.length() == bytes.size.toLong() && hash(destination.readBytes()) == sha) return
        val temporary = File(directory, ".$sha.tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            try {
                java.nio.file.Files.move(temporary.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(temporary.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }

    private fun publish(id: String, state: CharacterImageState) { _states.update { it + (id to state) } }

    companion object {
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        private const val MAX_CHARACTER_BYTES = 256L * 1024 * 1024
        private val SHA = Regex("[a-f0-9]{64}")
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
