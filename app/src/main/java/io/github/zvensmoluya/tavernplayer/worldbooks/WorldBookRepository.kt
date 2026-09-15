package io.github.zvensmoluya.tavernplayer.worldbooks

import io.github.zvensmoluya.tavernplayer.content.*
import io.github.zvensmoluya.tavernplayer.conversation.*
import io.github.zvensmoluya.tavernplayer.storage.AtomicFileStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class GlobalWorldBook(
    val book: WorldBookDefinition,
    val source: JsonObject,
    val sourceHash: String,
    val enabled: Boolean = false,
    val overrides: Map<String, WorldBookEntryOverride> = emptyMap(),
    val diagnostics: List<CompatibilityDiagnostic> = emptyList(),
) {
    val effectiveOverrides get() = book.entries.associate { entry ->
        val importedMode = (entry.extensions["tavern_player_mode"] as? JsonPrimitive)?.contentOrNull
            ?.let { name -> WorldBookEntryMode.entries.find { it.name == name } }
        val override = overrides[entry.id] ?: WorldBookEntryOverride()
        entry.id to override.copy(mode = override.mode ?: importedMode)
    }
    val state get() = ConversationWorldBookState(playerOverrides = mapOf(book.id to effectiveOverrides))
}

/** Definitions and player intent are global; generation checkpoints remain in each conversation. */
class WorldBookRepository(filesDir: File) {
    private val file = File(filesDir, "tavern/worldbooks/library.json")
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow<List<GlobalWorldBook>>(emptyList())
    val library = mutable.asStateFlow()
    private var initialized = false

    suspend fun initialize() = withContext(Dispatchers.IO) { mutex.withLock { load() } }
    private fun load() {
        if (initialized) return
        mutable.value = if (file.exists()) json.decodeFromString<List<GlobalWorldBook>>(file.readText()) else emptyList()
        initialized = true
    }
    private suspend fun change(edit: (List<GlobalWorldBook>) -> List<GlobalWorldBook>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            val next = edit(mutable.value)
            AtomicFileStore.writeUtf8(file, json.encodeToString(next))
            mutable.value = next
        }
    }
    suspend fun import(bytes: ByteArray, name: String): GlobalWorldBook {
        val parsed = withContext(Dispatchers.IO) { WorldBookImporter().import(bytes, name) }
        val hash = BrowserProgramReader.sha256(parsed.source.toString())
        var saved: GlobalWorldBook? = null
        change { books ->
            val existing = books.firstOrNull { it.sourceHash == hash }
            if (existing != null) { saved = existing; books } else {
                val id = "global-${UUID.randomUUID()}"
                val book = parsed.book.copy(id = id, entries = parsed.book.entries.mapIndexed { index, entry -> entry.copy(id = "$id:entry:$index") })
                GlobalWorldBook(book, parsed.source, hash, diagnostics = parsed.diagnostics).also { saved = it }.let { books + it }
            }
        }
        return requireNotNull(saved)
    }
    suspend fun setEnabled(id: String, enabled: Boolean) = update(id) { it.copy(enabled = enabled) }
    suspend fun setMode(id: String, entryId: String, mode: WorldBookEntryMode?) = updateEntry(id, entryId) { it.copy(mode = mode) }
    suspend fun setContent(id: String, entryId: String, content: String) = update(id) { asset ->
        require(content.length <= 262_144) { "正文超过长度限制" }
        val entry = asset.book.entries.single { it.id == entryId }
        val old = asset.overrides[entryId] ?: WorldBookEntryOverride()
        asset.copy(overrides = asset.overrides + (entryId to old.copy(content = content.takeUnless { it == entry.content })))
    }
    private suspend fun updateEntry(id: String, entryId: String, edit: (WorldBookEntryOverride) -> WorldBookEntryOverride) = update(id) {
        require(it.book.entries.any { entry -> entry.id == entryId }) { "条目不存在" }
        it.copy(overrides = it.overrides + (entryId to edit(it.overrides[entryId] ?: WorldBookEntryOverride())))
    }
    private suspend fun update(id: String, edit: (GlobalWorldBook) -> GlobalWorldBook) = change { books ->
        require(books.any { it.book.id == id }) { "世界书不存在" }
        books.map { if (it.book.id == id) edit(it) else it }
    }
    suspend fun delete(id: String) = change { books -> books.filterNot { it.book.id == id } }
    suspend fun duplicate(id: String) = change { books ->
        val original = books.single { it.book.id == id }
        val newId = "global-${UUID.randomUUID()}"
        val exported = export(original)
        val parsed = WorldBookImporter().import(exported.toByteArray(Charsets.UTF_8), original.book.name)
        val entries = parsed.book.entries.mapIndexed { index, entry -> entry.copy(id = "$newId:entry:$index") }
        books + GlobalWorldBook(book = parsed.book.copy(id = newId, name = original.book.name + " 副本", entries = entries),
            source = parsed.source, sourceHash = BrowserProgramReader.sha256(parsed.source.toString()), diagnostics = parsed.diagnostics)
    }
    suspend fun capture(): GlobalWorldBookSnapshot {
        initialize()
        val active = mutable.value.filter { it.enabled }
        return GlobalWorldBookSnapshot(active.map { it.book }, active.associate { it.book.id to it.effectiveOverrides })
    }

    /** Preserve original source fields; Player forced mode is a namespaced extension, not ST constant. */
    fun export(asset: GlobalWorldBook): String {
        val rawEntries = asset.source.getValue("entries")
        fun entry(index: Int, raw: JsonElement): JsonElement {
            val source = raw as JsonObject
            val definition = asset.book.entries[index]
            val override = asset.overrides[definition.id]
            val ext = (source["extensions"] as? JsonObject).orEmpty().toMutableMap()
            override?.mode?.let { ext["tavern_player_mode"] = JsonPrimitive(it.name) }
            val fields = source.toMutableMap()
            override?.content?.let { fields["content"] = JsonPrimitive(it) }
            override?.mode?.let { mode ->
                fields[if (rawEntries is JsonObject) "disable" else "enabled"] = JsonPrimitive(if (rawEntries is JsonObject) mode == WorldBookEntryMode.DISABLED else mode != WorldBookEntryMode.DISABLED)
            }
            fields["extensions"] = JsonObject(ext)
            return JsonObject(fields)
        }
        val exported = if (rawEntries is JsonObject) JsonObject(rawEntries.entries.mapIndexed { index, pair -> pair.key to entry(index, pair.value) }.toMap())
            else JsonArray((rawEntries as JsonArray).mapIndexed(::entry))
        return JsonObject(asset.source + mapOf("name" to JsonPrimitive(asset.book.name), "entries" to exported)).toString()
    }
}
