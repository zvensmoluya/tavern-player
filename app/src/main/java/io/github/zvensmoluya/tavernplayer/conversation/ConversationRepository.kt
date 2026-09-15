package io.github.zvensmoluya.tavernplayer.conversation

import android.content.Context
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.conversation.storage.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConversationRepository(
    filesDir: File,
    private val compiler: PromptCompiler,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mvuRuntime: io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuConversationRuntime =
        io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuConversationRuntime(),
    private val prepareBrowser: suspend (io.github.zvensmoluya.tavernplayer.content.BrowserProgram) -> io.github.zvensmoluya.tavernplayer.content.BrowserProgram = { it },
    context: Context,
    databaseOverride: ConversationDatabase? = null,
) : AutoCloseable {
    private val legacyRoot = File(filesDir, "tavern/conversations")
    internal val store = RoomConversationStore(context, filesDir, databaseOverride)
    private val mutex = Mutex()
    private var initialized = false
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    val conversations: Flow<List<ConversationSummary>> = flow {
        ensureInitialized()
        emitAll(store.dao.observeSummaries().distinctUntilChanged())
    }.flowOn(ioDispatcher)

    private suspend fun ensureInitialized() = withContext(ioDispatcher) { mutex.withLock { initializeLocked() } }
    private fun initializeLocked() {
        if (initialized) return
        if (store.dao.imported("__active__") == null) {
            legacyRoot.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.sortedBy { it.name }.forEach { file ->
                val bytes = file.readBytes()
                val hash = RoomConversationStore.sha256(bytes)
                val marker = store.dao.imported(file.name)
                check(marker == null || marker.sha256 == hash) { "导入中的旧会话发生变化，原文件已保留" }
                if (marker == null) {
                    val record = try { json.decodeFromString<ConversationRecord>(bytes.toString(Charsets.UTF_8)) }
                    catch (error: Exception) { throw IllegalStateException("旧会话无法解析，导入未激活，原文件已保留", error) }
                    check(record.schemaVersion == 3) { "旧会话格式不受支持，导入未激活，原文件已保留" }
                    store.transaction {
                        val saved = store.save(record.copy(commitRevision = 0), null)
                        check(store.read(saved.id) == saved) { "旧会话导入校验失败，原文件已保留" }
                        store.dao.put(ImportRow(file.name, hash))
                    }
                }
            }
            store.transaction { store.dao.put(ImportRow("__active__", "1")) }
        }
        initialized = true
    }

    suspend fun create(
        character: CharacterAsset,
        persona: Persona,
        preset: Preset,
        executionMode: ConversationExecutionMode = ConversationExecutionMode.LEGACY_NATIVE,
    ): ConversationRecord = withContext(ioDispatcher) {
        mutex.withLock {
            initializeLocked()
            val timestamp = now()
            val conversationId = idFactory()
            val capturedPreset = preset.snapshot()
            val snapshot = if (executionMode == ConversationExecutionMode.BROWSER) character.snapshot().copy(
                nativeAdaptation = null,
                browserProgram = prepareBrowser(io.github.zvensmoluya.tavernplayer.content.BrowserProgramReader.character(character)),
            ) else character.snapshot()
            val greetings = listOf(snapshot.firstMessage) + snapshot.alternateFirstMessages
            val adaptationRuntime = NativeAdaptationRuntime()
            val initialRuntime = adaptationRuntime.initialState(snapshot.nativeAdaptation)
            var committedRuntime = initialRuntime
            val variants = greetings.mapIndexedNotNull { index, greeting ->
                if (greeting.isBlank()) return@mapIndexedNotNull null
                val narrativeSource = adaptationRuntime.projectAssistantMessage(snapshot.nativeAdaptation, greeting).narrativeText
                val projected = compiler.projectAssistantText(
                    text = narrativeSource,
                    projection = RegexProjection.STORAGE,
                    character = snapshot,
                    persona = persona,
                    preset = capturedPreset,
                    runtimeState = initialRuntime,
                    history = emptyList(),
                    conversationId = conversationId,
                    generationId = "$conversationId-opening-$index",
                    modelId = "",
                    depth = 0,
                ) as TextExpansionResult.Success
                val projectedRuntime = snapshot.nativeAdaptation?.let { adaptation ->
                    adaptationRuntime.ingestAssistantMessage(adaptation, greeting, projected.runtimeState).runtimeState
                } ?: projected.runtimeState
                if (index == 0) committedRuntime = projectedRuntime
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
                    runtimeStateBefore = initialRuntime,
                    projectionRuntimeStateBefore = initialRuntime,
                    runtimeStateAfter = projectedRuntime,
                    openingSourceIndex = index,
                )
            }
            val turns = variants.takeIf(List<MessageVariant>::isNotEmpty)?.let {
                listOf(ConversationTurn(idFactory(), MessageRole.ASSISTANT, it))
            }.orEmpty()
            val record = mvuRuntime.initialize(ConversationRecord(
                id = conversationId,
                character = snapshot,
                persona = persona,
                turns = turns,
                runtimeState = committedRuntime,
                createdAtEpochMillis = timestamp,
                updatedAtEpochMillis = timestamp,
                executionMode = executionMode,
            ), capturedPreset, compiler)
            store.save(record, null)
        }
    }

    suspend fun get(conversationId: String): ConversationRecord? = withContext(ioDispatcher) {
        ensureInitialized()
        store.read(conversationId)
    }

    suspend fun open(conversationId: String): ConversationRecord? = withContext(ioDispatcher) {
        mutex.withLock {
            initializeLocked()
            val loaded = store.read(conversationId) ?: return@withLock null
            val streams = store.streamData(conversationId)
            var recovered = recoverConversation(loaded, streams, compiler)
            if (recovered != loaded || streams.isNotEmpty()) {
                recovered = store.transaction {
                    check(store.dao.streams(conversationId).associateBy { it.id } == streams.associate { it.progress.id to it.progress }) {
                        "生成进度在恢复期间发生变化，请重新打开会话"
                    }
                    val result = store.save(recovered, loaded, activity = false)
                    streams.forEach { store.dao.deleteStream(it.progress.id) }
                    result
                }
            }
            recovered
        }
    }

    suspend fun contains(conversationId: String): Boolean = withContext(ioDispatcher) {
        ensureInitialized(); store.dao.head(conversationId) != null
    }

    suspend fun forCharacter(characterId: String): List<ConversationSummary> = withContext(ioDispatcher) {
        ensureInitialized(); store.dao.summaries().filter { it.assetId == characterId }
    }

    suspend fun save(record: ConversationRecord, previous: ConversationRecord? = null,
        activity: Boolean = true, finishStream: String? = null): ConversationRecord = withContext(ioDispatcher) {
        mutex.withLock {
            initializeLocked()
            val before = previous ?: store.read(record.id)
            check(before == null || record.commitRevision == before.commitRevision) { "会话已变化，拒绝过期写入" }
            val updated = if (activity) record.copy(updatedAtEpochMillis = now()) else record
            store.save(updated, before, activity, finishStream)
        }
    }

    suspend fun saveDraft(record: ConversationRecord) = withContext(ioDispatcher) {
        mutex.withLock { initializeLocked(); store.saveDraft(record) }
    }
    suspend fun startStream(id: String, record: ConversationRecord, variantId: String, context: StreamContext) = withContext(ioDispatcher) {
        mutex.withLock { initializeLocked(); store.startStream(id, record.id, variantId, json.encodeToString(context)) }
    }
    suspend fun appendStream(id: String, chunks: List<StreamChunk>, projectedThrough: Long, preview: MessageVariant?) = withContext(ioDispatcher) {
        mutex.withLock { store.appendStream(id, chunks, projectedThrough, preview) }
    }
    suspend fun readMessages(id: String, before: Int = Int.MAX_VALUE, limit: Int = 50) = withContext(ioDispatcher) {
        ensureInitialized(); store.page(id, before, limit)
    }
    suspend fun collectUnusedBlobs() = withContext(ioDispatcher) {
        mutex.withLock { initializeLocked(); store.dao.collectUnusedBlobs() }
    }
    override fun close() = store.close()
}
