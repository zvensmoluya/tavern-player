package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.storage.AtomicFileStore
import java.io.File
import java.util.UUID
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

class ConversationRepository(
    filesDir: File,
    private val compiler: PromptCompiler,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val root = File(filesDir, "tavern/conversations")
    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val _conversations = MutableStateFlow<List<ConversationRecord>>(emptyList())
    val conversations: StateFlow<List<ConversationRecord>> = _conversations.asStateFlow()

    init {
        root.mkdirs()
        AtomicFileStore.cleanupTemporaryFiles(root)
        val loaded = loadAll().map { record ->
            val recovered = record.recoverInterruptedStreams()
            if (recovered != record) writeRecord(recovered)
            recovered
        }
        _conversations.value = loaded.sortedByDescending(ConversationRecord::updatedAtEpochMillis)
    }

    suspend fun create(
        character: CharacterAsset,
        persona: Persona,
        preset: Preset,
    ): ConversationRecord = withContext(ioDispatcher) {
        mutex.withLock {
            val timestamp = now()
            val conversationId = idFactory()
            val capturedPreset = preset.snapshot()
            val snapshot = character.snapshot()
            val greetings = listOf(snapshot.firstMessage) + snapshot.alternateFirstMessages
            var committedRuntime = ConversationRuntimeState()
            val variants = greetings.mapIndexedNotNull { index, greeting ->
                if (greeting.isBlank()) return@mapIndexedNotNull null
                val projected = compiler.projectAssistantText(
                    text = greeting,
                    projection = RegexProjection.STORAGE,
                    character = snapshot,
                    persona = persona,
                    preset = capturedPreset,
                    runtimeState = ConversationRuntimeState(),
                    history = emptyList(),
                    conversationId = conversationId,
                    generationId = "$conversationId-opening-$index",
                    modelId = "",
                    depth = 0,
                ) as TextExpansionResult.Success
                if (index == 0) committedRuntime = projected.runtimeState
                val message = ConversationMessage(
                    id = idFactory(),
                    role = MessageRole.ASSISTANT,
                    content = projected.text,
                    sourceText = greeting,
                    authorName = snapshot.promptName,
                    createdAtEpochMillis = timestamp,
                )
                MessageVariant(
                    id = idFactory(),
                    message = message,
                    presetId = capturedPreset.id,
                    presetName = capturedPreset.name,
                    presetContentSha256 = capturedPreset.contentSha256,
                    runtimeStateBefore = ConversationRuntimeState(),
                    projectionRuntimeStateBefore = ConversationRuntimeState(),
                    runtimeStateAfter = projected.runtimeState,
                )
            }
            val turns = variants.takeIf(List<MessageVariant>::isNotEmpty)?.let {
                listOf(ConversationTurn(idFactory(), MessageRole.ASSISTANT, it))
            }.orEmpty()
            val record = ConversationRecord(
                id = conversationId,
                character = snapshot,
                persona = persona,
                turns = turns,
                runtimeState = committedRuntime,
                createdAtEpochMillis = timestamp,
                updatedAtEpochMillis = timestamp,
            )
            writeRecord(record)
            publish(record)
            record
        }
    }

    fun get(conversationId: String): ConversationRecord? =
        _conversations.value.firstOrNull { it.id == conversationId }

    fun forCharacter(characterId: String): List<ConversationRecord> =
        _conversations.value.filter { it.character.assetId == characterId }

    suspend fun save(record: ConversationRecord): ConversationRecord = withContext(ioDispatcher) {
        mutex.withLock {
            val updated = record.copy(updatedAtEpochMillis = now())
            writeRecord(updated)
            publish(updated)
            updated
        }
    }

    private fun publish(record: ConversationRecord) {
        _conversations.value = (_conversations.value.filterNot { it.id == record.id } + record)
            .sortedByDescending(ConversationRecord::updatedAtEpochMillis)
    }

    private fun loadAll(): List<ConversationRecord> = root.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "json" }
        .mapNotNull { file -> runCatching { json.decodeFromString<ConversationRecord>(file.readText()) }.getOrNull() }

    private fun writeRecord(record: ConversationRecord) {
        val file = File(root, "${record.id}.json")
        AtomicFileStore.writeUtf8(file, json.encodeToString(record))
    }
}

private fun ConversationRecord.recoverInterruptedStreams(): ConversationRecord {
    var changed = false
    val recovered = turns.map { turn ->
        turn.copy(
            variants = turn.variants.map { variant ->
                if (variant.status == PersistedMessageStatus.STREAMING) {
                    changed = true
                    variant.copy(status = PersistedMessageStatus.INTERRUPTED)
                } else {
                    variant
                }
            },
        )
    }
    return if (changed) copy(turns = recovered) else this
}
