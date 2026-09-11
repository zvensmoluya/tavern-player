package io.github.zvensmoluya.tavernplayer.personas

import io.github.zvensmoluya.tavernplayer.conversation.Persona
import io.github.zvensmoluya.tavernplayer.storage.AtomicFileStore
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface DefaultPersonaSource {
    val persona: StateFlow<Persona>
    suspend fun initialize() = Unit

    fun captureDefault(): Persona = persona.value.copy()
}

class PersonaRepository(
    filesDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    loadOnInit: Boolean = true,
) : DefaultPersonaSource {
    private val root = File(filesDir, "tavern/persona")
    private val stateFile = File(root, STATE_FILE)
    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    @Volatile
    private var initialized = false
    private val _persona = MutableStateFlow(defaultPersona())
    override val persona: StateFlow<Persona> = _persona.asStateFlow()

    init {
        if (loadOnInit) loadStorage()
    }

    override suspend fun initialize() = withContext(ioDispatcher) {
        mutex.withLock {
            if (!initialized) loadStorage()
        }
    }

    suspend fun save(name: String, description: String, avatar: String?): Persona = withContext(ioDispatcher) {
        initialize()
        mutex.withLock {
            val normalizedName = name.trim().takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException("身份名称不能为空")
            val saved = Persona(
                id = DEFAULT_ID,
                name = normalizedName,
                avatar = avatar?.trim()?.takeIf(String::isNotEmpty),
                description = description,
            )
            root.mkdirs()
            AtomicFileStore.writeUtf8(stateFile, json.encodeToString(saved))
            _persona.value = saved
            saved.copy()
        }
    }

    private fun loadStorage() {
        root.mkdirs()
        AtomicFileStore.cleanupTemporaryFiles(root)
        _persona.value = stateFile.takeIf(File::isFile)
            ?.let { file -> runCatching { json.decodeFromString<Persona>(file.readText()) }.getOrNull() }
            ?.copy(id = DEFAULT_ID)
            ?: defaultPersona()
        initialized = true
    }

    companion object {
        const val DEFAULT_ID: String = "default-persona"
        private const val STATE_FILE = "default.json"

        fun defaultPersona(): Persona = Persona(id = DEFAULT_ID, name = "旅人")
    }
}
