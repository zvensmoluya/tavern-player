package io.github.zvensmoluya.tavernplayer.presets

import io.github.zvensmoluya.tavernplayer.content.BuiltInPresets
import io.github.zvensmoluya.tavernplayer.content.CompatibilityDiagnostic
import io.github.zvensmoluya.tavernplayer.content.PresetAsset
import io.github.zvensmoluya.tavernplayer.content.PresetExporter
import io.github.zvensmoluya.tavernplayer.content.PresetImportResult
import io.github.zvensmoluya.tavernplayer.content.PresetImporter
import io.github.zvensmoluya.tavernplayer.content.PresetInitialState
import io.github.zvensmoluya.tavernplayer.storage.AtomicFileStore
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
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
private data class PresetLibraryManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val activePresetId: String = BuiltInPresets.DEFAULT_ID,
    val presets: List<PresetAsset> = emptyList(),
)

data class PresetLibraryState(
    val presets: List<PresetAsset>,
    val activePresetId: String,
) {
    val activePreset: PresetAsset
        get() = presets.firstOrNull { it.id == activePresetId } ?: BuiltInPresets.default
}

interface ActivePresetSource {
    val activePreset: StateFlow<PresetAsset>

    /** A deep immutable capture for one conversation or generation transaction. */
    fun captureActive(): PresetAsset = activePreset.value.snapshot()
}

sealed interface PresetLibraryImportResult {
    val diagnostics: List<CompatibilityDiagnostic>

    data class Saved(
        val preset: PresetAsset,
        val duplicate: Boolean,
        override val diagnostics: List<CompatibilityDiagnostic>,
    ) : PresetLibraryImportResult

    data class Rejected(
        override val diagnostics: List<CompatibilityDiagnostic>,
    ) : PresetLibraryImportResult
}

sealed class PresetRepositoryException(message: String) : IllegalArgumentException(message)

class PresetNotFoundException(id: String) : PresetRepositoryException("Preset 不存在：$id")

class BuiltInPresetMutationException : PresetRepositoryException("内置默认 Preset 不能删除")

class PresetNameConflictException(name: String) : PresetRepositoryException("Preset 名称已存在：$name")

class InvalidPresetNameException : PresetRepositoryException("Preset 名称不能为空")

/**
 * App-private global Preset library. The atomic manifest is the source of truth and contains the
 * active id, formal assets, and their complete parsed ST source trees. Imported connection fields
 * and extensions remain inert data unless a separate supported runtime explicitly consumes them.
 */
class PresetRepository(
    filesDir: File,
    private val importer: PresetImporter = PresetImporter(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ActivePresetSource {
    private val root = File(filesDir, "tavern/presets")
    private val manifestFile = File(root, MANIFEST_FILE)
    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val initial = run {
        root.mkdirs()
        AtomicFileStore.cleanupTemporaryFiles(root)
        loadState()
    }
    private val _library = MutableStateFlow(initial)
    val library: StateFlow<PresetLibraryState> = _library.asStateFlow()
    private val _activePreset = MutableStateFlow(initial.activePreset)
    override val activePreset: StateFlow<PresetAsset> = _activePreset.asStateFlow()

    fun get(presetId: String): PresetAsset? =
        _library.value.presets.firstOrNull { it.id == presetId }?.snapshot()

    suspend fun importPreset(sourceBytes: ByteArray, sourceName: String): PresetLibraryImportResult =
        withContext(ioDispatcher) {
            mutex.withLock {
                when (val result = importer.import(sourceBytes, sourceName)) {
                    is PresetImportResult.Rejected -> PresetLibraryImportResult.Rejected(result.diagnostics)
                    is PresetImportResult.Ready -> {
                        val duplicate = _library.value.presets.firstOrNull {
                            it.sourceSha256 == result.preset.sourceSha256 ||
                                it.contentSha256 == result.preset.contentSha256
                        }
                        if (duplicate != null) {
                            if (_library.value.activePresetId != duplicate.id) {
                                persistAndPublish(storedPresets(), duplicate.id)
                            }
                            PresetLibraryImportResult.Saved(
                                preset = duplicate.snapshot(),
                                duplicate = true,
                                diagnostics = result.diagnostics,
                            )
                        } else {
                            val users = storedPresets()
                            val name = allocateName(result.preset.name, _library.value.presets)
                            val preset = normalize(
                                result.preset.copy(
                                    id = uniqueId(result.preset.id),
                                    name = name,
                                    builtIn = false,
                                ),
                            )
                            persistAndPublish(users + preset, preset.id)
                            PresetLibraryImportResult.Saved(
                                preset = preset.snapshot(),
                                duplicate = false,
                                diagnostics = result.diagnostics,
                            )
                        }
                    }
                }
            }
        }

    suspend fun activate(presetId: String): PresetAsset = withContext(ioDispatcher) {
        mutex.withLock {
            val preset = findRequired(presetId)
            if (_library.value.activePresetId != presetId) {
                persistAndPublish(storedPresets(), presetId)
            }
            preset.snapshot()
        }
    }

    suspend fun save(preset: PresetAsset): PresetAsset = withContext(ioDispatcher) {
        mutex.withLock {
            val existing = findRequired(preset.id)
            val name = preset.name.trim().takeIf(String::isNotEmpty) ?: throw InvalidPresetNameException()
            if (hasNameConflict(name, excludingId = preset.id)) throw PresetNameConflictException(name)
            val saved = normalize(
                preset.copy(
                    id = existing.id,
                    sourceSha256 = existing.sourceSha256,
                    name = name,
                    initialState = existing.initialState,
                    builtIn = existing.builtIn,
                ),
            )
            val stored = storedPresets().map { current -> if (current.id == saved.id) saved else current }
            persistAndPublish(stored, _library.value.activePresetId)
            saved.snapshot()
        }
    }

    suspend fun rename(presetId: String, name: String): PresetAsset =
        save(findRequiredSnapshot(presetId).copy(name = name))

    suspend fun saveAs(preset: PresetAsset, requestedName: String): PresetAsset = withContext(ioDispatcher) {
        mutex.withLock {
            findRequired(preset.id)
            val requested = requestedName.trim().takeIf(String::isNotEmpty) ?: throw InvalidPresetNameException()
            if (hasNameConflict(requested)) throw PresetNameConflictException(requested)
            val source = PresetExporter.exportToJson(preset.copy(name = requested))
            val fingerprint = PresetExporter.fingerprint(preset.copy(name = requested, source = source))
            val copied = normalize(
                preset.snapshot().copy(
                    id = uniqueId(),
                    sourceSha256 = fingerprint,
                    contentSha256 = fingerprint,
                    name = requested,
                    source = source,
                    initialState = PresetInitialState(source),
                    builtIn = false,
                ),
            )
            persistAndPublish(storedPresets() + copied, copied.id)
            copied.snapshot()
        }
    }

    fun initialVersion(presetId: String): PresetAsset {
        val existing = findRequiredSnapshot(presetId)
        val initial = existing.initialState ?: PresetInitialState(existing.source)
        val restored = when (val result = importer.import(initial.source.toString().encodeToByteArray(), "${existing.name}.json")) {
            is PresetImportResult.Ready -> result.preset
            is PresetImportResult.Rejected -> error(result.diagnostics.firstOrNull()?.message ?: "无法恢复 Preset 初始设置")
        }
        return restored.copy(
            id = existing.id,
            sourceSha256 = existing.sourceSha256,
            contentSha256 = existing.contentSha256,
            name = existing.name,
            source = initial.source,
            initialState = initial,
            diagnostics = existing.diagnostics,
            builtIn = existing.builtIn,
        ).snapshot()
    }

    suspend fun delete(presetId: String): PresetAsset = withContext(ioDispatcher) {
        mutex.withLock {
            val preset = findRequired(presetId)
            if (preset.builtIn) throw BuiltInPresetMutationException()
            val users = storedPresets().filterNot { it.id == presetId }
            val nextActive = if (_library.value.activePresetId == presetId) {
                BuiltInPresets.DEFAULT_ID
            } else {
                _library.value.activePresetId
            }
            persistAndPublish(users, nextActive)
            preset.snapshot()
        }
    }

    fun exportPreset(presetId: String): ByteArray = PresetExporter.export(findRequiredSnapshot(presetId))

    private fun findRequiredSnapshot(presetId: String): PresetAsset =
        get(presetId) ?: throw PresetNotFoundException(presetId)

    private fun findRequired(presetId: String): PresetAsset =
        _library.value.presets.firstOrNull { it.id == presetId } ?: throw PresetNotFoundException(presetId)

    private fun storedPresets(): List<PresetAsset> = _library.value.presets.map(PresetAsset::snapshot)

    private fun hasNameConflict(name: String, excludingId: String? = null): Boolean {
        val key = name.nameKey()
        return _library.value.presets.any { it.id != excludingId && it.name.nameKey() == key }
    }

    private fun allocateName(requested: String, existing: List<PresetAsset>): String {
        val base = requested.trim().ifBlank { "未命名预设" }
        val keys = existing.mapTo(mutableSetOf()) { it.name.nameKey() }
        if (base.nameKey() !in keys) return base
        var suffix = 2
        while ("$base ($suffix)".nameKey() in keys) suffix += 1
        return "$base ($suffix)"
    }

    private fun uniqueId(preferred: String? = null): String {
        val existing = _library.value.presets.mapTo(mutableSetOf(), PresetAsset::id)
        preferred?.takeIf { it.isNotBlank() && it !in existing }?.let { return it }
        repeat(MAX_ID_ATTEMPTS) {
            idFactory().takeIf { it.isNotBlank() && it !in existing }?.let { return it }
        }
        error("无法生成唯一 Preset ID")
    }

    private fun persistAndPublish(presets: List<PresetAsset>, requestedActiveId: String) {
        val normalized = normalizeLoaded(presets)
        val knownIds = normalized.mapTo(mutableSetOf(), PresetAsset::id)
        val activeId = requestedActiveId.takeIf(knownIds::contains) ?: BuiltInPresets.DEFAULT_ID
        val manifest = PresetLibraryManifest(
            activePresetId = activeId,
            presets = normalized,
        )
        root.mkdirs()
        AtomicFileStore.writeUtf8(manifestFile, json.encodeToString(manifest))
        publish(normalized, activeId)
    }

    private fun publish(presets: List<PresetAsset>, activeId: String) {
        val builtIn = presets.firstOrNull { it.id == BuiltInPresets.DEFAULT_ID } ?: BuiltInPresets.default
        val users = presets.filter { it.id != BuiltInPresets.DEFAULT_ID }
        val state = PresetLibraryState(
            presets = listOf(builtIn) + users.sortedWith(PRESET_ORDER),
            activePresetId = activeId,
        )
        _library.value = state
        _activePreset.value = state.activePreset
    }

    private fun loadState(): PresetLibraryState {
        root.mkdirs()
        val manifest = manifestFile.takeIf(File::isFile)?.let { file ->
            runCatching { json.decodeFromString<PresetLibraryManifest>(file.readText()) }.getOrNull()
        }
        val presets = normalizeLoaded(manifest?.presets.orEmpty())
        val knownIds = presets.mapTo(mutableSetOf(), PresetAsset::id)
        val activeId = manifest?.activePresetId?.takeIf(knownIds::contains) ?: BuiltInPresets.DEFAULT_ID
        return PresetLibraryState(
            presets = listOf(presets.first { it.id == BuiltInPresets.DEFAULT_ID }) +
                presets.filter { it.id != BuiltInPresets.DEFAULT_ID }.sortedWith(PRESET_ORDER),
            activePresetId = activeId,
        )
    }

    private fun normalizeLoaded(presets: List<PresetAsset>): List<PresetAsset> {
        val accepted = mutableListOf<PresetAsset>()
        val ids = mutableSetOf<String>()
        val persistedBuiltIn = presets.firstOrNull { it.id == BuiltInPresets.DEFAULT_ID }
        val builtIn = persistedBuiltIn?.copy(
            id = BuiltInPresets.DEFAULT_ID,
            sourceSha256 = BuiltInPresets.default.sourceSha256,
            initialState = BuiltInPresets.default.initialState,
            builtIn = true,
        )?.let(::normalize) ?: BuiltInPresets.default
        accepted += builtIn
        ids += BuiltInPresets.DEFAULT_ID
        presets.forEach { candidate ->
            if (candidate.id == BuiltInPresets.DEFAULT_ID || candidate.id.isBlank() || !ids.add(candidate.id)) return@forEach
            val named = candidate.copy(
                name = allocateName(candidate.name, accepted),
                builtIn = false,
            )
            accepted += normalize(named)
        }
        return accepted
    }

    private fun normalize(preset: PresetAsset): PresetAsset {
        val source = PresetExporter.exportToJson(preset)
        val draft = preset.copy(
            source = source,
            initialState = preset.initialState ?: PresetInitialState(source),
        )
        return draft.copy(contentSha256 = PresetExporter.fingerprint(draft))
    }

    private companion object {
        const val MANIFEST_FILE = "library.json"
        const val MAX_ID_ATTEMPTS = 100
    }
}

private const val CURRENT_SCHEMA_VERSION = 3

private val PRESET_ORDER = compareBy<PresetAsset> { it.name.lowercase(Locale.ROOT) }.thenBy(PresetAsset::id)

private fun String.nameKey(): String = trim().lowercase(Locale.ROOT)
