package io.github.zvensmoluya.tavernplayer.characters

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.content.CharacterCardImporter
import io.github.zvensmoluya.tavernplayer.content.CharacterImportResult
import io.github.zvensmoluya.tavernplayer.content.CharacterSourceFormat
import io.github.zvensmoluya.tavernplayer.content.CompatibilityDiagnostic
import io.github.zvensmoluya.tavernplayer.storage.AtomicFileStore
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class CharacterManifest(
    val schemaVersion: Int = 1,
    val character: CharacterAsset,
    val originalFileName: String,
    val sourceFileName: String,
    val avatarFileName: String? = null,
    val importedAtEpochMillis: Long,
)

sealed interface CharacterSaveResult {
    data class Saved(
        val character: CharacterAsset,
        val duplicate: Boolean,
        val diagnostics: List<CompatibilityDiagnostic>,
    ) : CharacterSaveResult

    data class Rejected(val diagnostics: List<CompatibilityDiagnostic>) : CharacterSaveResult
}

class CharacterRepository(
    filesDir: File,
    private val importer: CharacterCardImporter = CharacterCardImporter(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val root = File(filesDir, "tavern/characters")
    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val _characters = MutableStateFlow<List<CharacterAsset>>(emptyList())
    val characters: StateFlow<List<CharacterAsset>> = _characters.asStateFlow()

    init {
        root.mkdirs()
        cleanupIncompleteImports()
        _characters.value = loadAll()
    }

    suspend fun import(bytes: ByteArray, originalFileName: String): CharacterSaveResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            when (val decoded = importer.import(bytes, originalFileName)) {
                is CharacterImportResult.Rejected -> CharacterSaveResult.Rejected(decoded.diagnostics)
                is CharacterImportResult.Ready -> {
                    val duplicate = _characters.value.firstOrNull { it.sourceSha256 == decoded.character.sourceSha256 }
                    if (duplicate != null) {
                        return@withLock CharacterSaveResult.Saved(duplicate, true, duplicate.diagnostics)
                    }
                    val character = decoded.character
                    val temporary = File(root, "${character.id}.importing")
                    val destination = File(root, character.id)
                    if (temporary.exists()) temporary.deleteRecursivelySafely(root)
                    check(temporary.mkdirs()) { "无法创建角色导入目录" }
                    try {
                        val sourceName = if (character.sourceFormat == CharacterSourceFormat.PNG) "source.png" else "source.json"
                        File(temporary, sourceName).writeBytes(decoded.sourceBytes)
                        val avatarName = createAvatar(temporary, character, decoded.sourceBytes)
                        val manifest = CharacterManifest(
                            character = character,
                            originalFileName = originalFileName,
                            sourceFileName = sourceName,
                            avatarFileName = avatarName,
                            importedAtEpochMillis = now(),
                        )
                        writeAtomic(File(temporary, MANIFEST_FILE), json.encodeToString(manifest))
                        if (!temporary.renameTo(destination)) error("无法完成角色卡原子导入")
                    } catch (error: Exception) {
                        temporary.deleteRecursivelySafely(root)
                        throw error
                    }
                    _characters.value = (_characters.value + character).sortedWith(CHARACTER_ORDER)
                    CharacterSaveResult.Saved(character, false, decoded.diagnostics)
                }
            }
        }
    }

    fun get(characterId: String): CharacterAsset? = _characters.value.firstOrNull { it.id == characterId }

    fun avatarFile(characterId: String): File? {
        val manifest = readManifest(File(root, characterId)) ?: return null
        return manifest.avatarFileName?.let { name -> File(File(root, characterId), name).takeIf(File::isFile) }
    }

    fun sourceFile(characterId: String): File? {
        val manifest = readManifest(File(root, characterId)) ?: return null
        return File(File(root, characterId), manifest.sourceFileName).takeIf(File::isFile)
    }

    private fun loadAll(): List<CharacterAsset> = root.listFiles()
        .orEmpty()
        .filter { it.isDirectory && !it.name.endsWith(".importing") }
        .mapNotNull(::readManifest)
        .map(CharacterManifest::character)
        .sortedWith(CHARACTER_ORDER)

    private fun readManifest(directory: File): CharacterManifest? {
        val file = File(directory, MANIFEST_FILE)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<CharacterManifest>(file.readText()) }.getOrNull()
    }

    private fun cleanupIncompleteImports() {
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.endsWith(".importing") }
            .forEach { it.deleteRecursivelySafely(root) }
    }

    private fun createAvatar(directory: File, character: CharacterAsset, sourceBytes: ByteArray): String? {
        val imageBytes = when (character.sourceFormat) {
            CharacterSourceFormat.PNG -> sourceBytes
            CharacterSourceFormat.JSON -> character.assets
                .firstOrNull { it.type == "icon" && it.name == "main" && it.uri.startsWith("data:") }
                ?.uri
                ?.decodeDataUri()
        } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > AVATAR_MAX_SIZE * 2 || bounds.outHeight / sample > AVATAR_MAX_SIZE * 2) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            imageBytes,
            0,
            imageBytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val scaled = if (bitmap.width > AVATAR_MAX_SIZE || bitmap.height > AVATAR_MAX_SIZE) {
            val ratio = minOf(AVATAR_MAX_SIZE.toFloat() / bitmap.width, AVATAR_MAX_SIZE.toFloat() / bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
                .also { if (it !== bitmap) bitmap.recycle() }
        } else {
            bitmap
        }
        val file = File(directory, AVATAR_FILE)
        file.outputStream().use { output -> scaled.compress(Bitmap.CompressFormat.PNG, 100, output) }
        scaled.recycle()
        return AVATAR_FILE
    }

    private fun String.decodeDataUri(): ByteArray? {
        val comma = indexOf(',')
        if (comma < 0 || !substring(0, comma).contains(";base64", ignoreCase = true)) return null
        val encoded = substring(comma + 1)
        if (encoded.length > MAX_INLINE_AVATAR_BASE64) return null
        return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
    }

    private fun writeAtomic(file: File, value: String) {
        AtomicFileStore.writeUtf8(file, value)
    }

    companion object {
        private const val MANIFEST_FILE = "manifest.json"
        private const val AVATAR_FILE = "avatar.png"
        private const val AVATAR_MAX_SIZE = 512
        private const val MAX_INLINE_AVATAR_BASE64 = 6 * 1024 * 1024
        private val CHARACTER_ORDER = compareBy<CharacterAsset> { it.name.lowercase() }.thenBy(CharacterAsset::id)
    }
}

private fun File.deleteRecursivelySafely(root: File) {
    val targetPath = canonicalFile.toPath()
    val rootPath = root.canonicalFile.toPath()
    check(targetPath.startsWith(rootPath) && targetPath != rootPath) { "拒绝删除角色仓库之外的目录" }
    deleteRecursively()
}
