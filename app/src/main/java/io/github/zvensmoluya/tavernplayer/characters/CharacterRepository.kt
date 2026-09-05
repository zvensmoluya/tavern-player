package io.github.zvensmoluya.tavernplayer.characters

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.content.CharacterAssetReference
import io.github.zvensmoluya.tavernplayer.content.CharacterCardImporter
import io.github.zvensmoluya.tavernplayer.content.CharacterImportResult
import io.github.zvensmoluya.tavernplayer.content.CharacterSourceFormat
import io.github.zvensmoluya.tavernplayer.content.CompatibilityDiagnostic
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptationValidationIssue
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptationValidator
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
    val schemaVersion: Int = 2,
    val character: CharacterAsset,
    val originalFileName: String,
    val sourceFileName: String,
    val avatarFileName: String? = null,
    val localAssets: List<LocalCharacterAsset> = emptyList(),
    val importedAtEpochMillis: Long,
)

@Serializable
private data class LocalCharacterAsset(
    val assetId: String,
    val fileName: String,
    val mediaType: String,
    val width: Int,
    val height: Int,
)

sealed interface CharacterSaveResult {
    data class Saved(
        val character: CharacterAsset,
        val duplicate: Boolean,
        val diagnostics: List<CompatibilityDiagnostic>,
    ) : CharacterSaveResult

    data class Rejected(val diagnostics: List<CompatibilityDiagnostic>) : CharacterSaveResult
}

sealed interface NativeAdaptationInstallResult {
    data class Installed(val character: CharacterAsset) : NativeAdaptationInstallResult
    data class Rejected(val issues: List<NativeAdaptationValidationIssue>) : NativeAdaptationInstallResult
}

internal data class StaticImageInfo(val width: Int, val height: Int, val mediaType: String)

internal fun interface StaticImageInspector {
    fun inspect(bytes: ByteArray): StaticImageInfo?
}

private object AndroidStaticImageInspector : StaticImageInspector {
    override fun inspect(bytes: ByteArray): StaticImageInfo? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val mediaType = bounds.outMimeType?.lowercase() ?: return null
        return StaticImageInfo(bounds.outWidth, bounds.outHeight, mediaType)
    }
}

class CharacterRepository internal constructor(
    filesDir: File,
    private val importer: CharacterCardImporter = CharacterCardImporter(),
    private val adaptationValidator: NativeAdaptationValidator = NativeAdaptationValidator(),
    private val now: () -> Long = System::currentTimeMillis,
    private val imageInspector: StaticImageInspector = AndroidStaticImageInspector,
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
                        val localAssets = materializeAssets(temporary, character, decoded.sourceBytes, sourceName)
                        val manifest = CharacterManifest(
                            character = character,
                            originalFileName = originalFileName,
                            sourceFileName = sourceName,
                            avatarFileName = avatarName,
                            localAssets = localAssets,
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

    suspend fun installNativeAdaptation(
        characterId: String,
        adaptation: NativeAdaptation,
    ): NativeAdaptationInstallResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = File(root, characterId)
            val manifest = readManifest(directory)
                ?: return@withLock NativeAdaptationInstallResult.Rejected(
                    listOf(NativeAdaptationValidationIssue("characterId", "UNKNOWN_CHARACTER", "角色不存在")),
                )
            val validation = adaptationValidator.validate(
                adaptation = adaptation,
                expectedSourceSha256 = manifest.character.sourceSha256,
                availableAssetIds = manifest.localAssets.mapTo(mutableSetOf(), LocalCharacterAsset::assetId),
                worldBooks = manifest.character.worldBooks,
                openingCount = 1 + manifest.character.alternateFirstMessages.size,
                regexScripts = manifest.character.regexScripts,
            )
            if (!validation.valid) return@withLock NativeAdaptationInstallResult.Rejected(validation.issues)
            val updated = manifest.character.copy(nativeAdaptation = adaptation)
            writeAtomic(File(directory, MANIFEST_FILE), json.encodeToString(manifest.copy(character = updated)))
            _characters.value = _characters.value.map { if (it.id == characterId) updated else it }.sortedWith(CHARACTER_ORDER)
            NativeAdaptationInstallResult.Installed(updated)
        }
    }

    fun assetFile(characterId: String, assetId: String): File? {
        val directory = File(root, characterId)
        val manifest = readManifest(directory) ?: return null
        val fileName = manifest.localAssets.firstOrNull { it.assetId == assetId }?.fileName ?: return null
        val file = File(directory, fileName)
        val rootPath = directory.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        return file.takeIf { filePath.startsWith(rootPath) && filePath != rootPath && it.isFile }
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

    private fun materializeAssets(
        directory: File,
        character: CharacterAsset,
        sourceBytes: ByteArray,
        sourceFileName: String,
    ): List<LocalCharacterAsset> = character.assets.mapNotNull { asset ->
        when {
            asset.uri == "ccdefault:" && character.sourceFormat == CharacterSourceFormat.PNG -> {
                inspectImage(asset, sourceBytes, sourceFileName, "image/png")
            }
            asset.uri.startsWith("data:") -> {
                val mediaType = asset.uri.substringAfter("data:").substringBefore(';').lowercase()
                val extension = SAFE_IMAGE_TYPES[mediaType] ?: return@mapNotNull null
                val bytes = asset.uri.decodeDataUri(MAX_INLINE_ASSET_BASE64) ?: return@mapNotNull null
                if (bytes.size > MAX_INLINE_ASSET_BYTES) return@mapNotNull null
                val relativeName = "assets/${asset.id}.$extension"
                val inspected = inspectImage(asset, bytes, relativeName, mediaType) ?: return@mapNotNull null
                val file = File(directory, relativeName)
                check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true) {
                    "无法创建角色资产目录"
                }
                file.writeBytes(bytes)
                inspected
            }
            else -> null
        }
    }

    private fun inspectImage(
        asset: CharacterAssetReference,
        bytes: ByteArray,
        fileName: String,
        expectedMediaType: String,
    ): LocalCharacterAsset? {
        val info = imageInspector.inspect(bytes) ?: return null
        val width = info.width
        val height = info.height
        val mediaType = IMAGE_MIME_ALIASES[info.mediaType.lowercase()] ?: return null
        if (mediaType != expectedMediaType || width <= 0 || height <= 0) return null
        if (width > MAX_ASSET_EDGE || height > MAX_ASSET_EDGE || width.toLong() * height > MAX_ASSET_PIXELS) return null
        return LocalCharacterAsset(asset.id, fileName, mediaType, width, height)
    }

    private fun String.decodeDataUri(maxBase64Chars: Int = MAX_INLINE_AVATAR_BASE64): ByteArray? {
        val comma = indexOf(',')
        if (comma < 0 || !substring(0, comma).contains(";base64", ignoreCase = true)) return null
        val encoded = substring(comma + 1)
        if (encoded.length > maxBase64Chars) return null
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
        private const val MAX_INLINE_ASSET_BASE64 = 12 * 1024 * 1024
        private const val MAX_INLINE_ASSET_BYTES = 8 * 1024 * 1024
        private const val MAX_ASSET_EDGE = 8_192
        private const val MAX_ASSET_PIXELS = 32_000_000L
        private val SAFE_IMAGE_TYPES = mapOf(
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "image/webp" to "webp",
        )
        private val IMAGE_MIME_ALIASES = mapOf(
            "image/png" to "image/png",
            "image/x-png" to "image/png",
            "image/jpeg" to "image/jpeg",
            "image/jpg" to "image/jpeg",
            "image/webp" to "image/webp",
        )
        private val CHARACTER_ORDER = compareBy<CharacterAsset> { it.name.lowercase() }.thenBy(CharacterAsset::id)
    }
}

private fun File.deleteRecursivelySafely(root: File) {
    val targetPath = canonicalFile.toPath()
    val rootPath = root.canonicalFile.toPath()
    check(targetPath.startsWith(rootPath) && targetPath != rootPath) { "拒绝删除角色仓库之外的目录" }
    deleteRecursively()
}
