package io.github.zvensmoluya.tavernplayer.conversation

import android.content.Context
import io.github.zvensmoluya.tavernplayer.content.PresetAsset
import io.github.zvensmoluya.tavernplayer.content.mvuProgram
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class OpenedConversation(val record: ConversationRecord, val readOnlyReason: String? = null)

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
                if (marker?.sha256 == hash) return@forEach
                val record = try {
                    val fields = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                    val mode = fields["executionMode"]?.jsonPrimitive?.content
                    if (mode == null || mode == ConversationExecutionMode.LEGACY_NATIVE.name) return@forEach
                    json.decodeFromJsonElement(ConversationRecord.serializer(), fields)
                } catch (error: Exception) {
                    throw IllegalStateException("旧会话无法解析，导入未激活，原文件已保留", error)
                }
                check(marker == null) { "导入中的旧会话发生变化，原文件已保留" }
                check(record.schemaVersion in 1..3) { "旧会话格式不受支持，导入未激活，原文件已保留" }
                store.transaction {
                    val saved = store.save(record.copy(commitRevision = 0), null)
                    check(store.read(saved.id) == saved) { "旧会话导入校验失败，原文件已保留" }
                    store.dao.put(ImportRow(file.name, hash))
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
    ): ConversationRecord = withContext(ioDispatcher) {
        mutex.withLock {
            initializeLocked()
            val timestamp = now()
            val conversationId = idFactory()
            val capturedPreset = preset.snapshot()
            val snapshot = character.snapshot().copy(
                nativeAdaptation = null,
                browserProgram = prepareBrowser(io.github.zvensmoluya.tavernplayer.content.BrowserProgramReader.character(character)),
            )
            val greetings = listOf(snapshot.firstMessage) + snapshot.alternateFirstMessages
            val initialRuntime = ConversationRuntimeState()
            var committedRuntime = initialRuntime
            val variants = greetings.mapIndexedNotNull { index, greeting ->
                if (greeting.isBlank()) return@mapIndexedNotNull null
                val projected = compiler.projectAssistantText(
                    text = greeting,
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
                    runtimeStateBefore = initialRuntime,
                    projectionRuntimeStateBefore = initialRuntime,
                    runtimeStateAfter = projected.runtimeState,
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
                executionMode = ConversationExecutionMode.BROWSER,
            ), capturedPreset, compiler)
            store.save(record, null)
        }
    }

    suspend fun get(conversationId: String): ConversationRecord? = withContext(ioDispatcher) {
        ensureInitialized()
        if (store.dao.containsBrowserConversation(conversationId)) store.read(conversationId) else null
    }

    suspend fun open(conversationId: String): ConversationRecord? = open(conversationId, RoomConversationStore.PlanRead.FULL)

    private suspend fun open(conversationId: String, plans: RoomConversationStore.PlanRead): ConversationRecord? = withContext(ioDispatcher) {
        mutex.withLock {
            initializeLocked()
            if (!store.dao.containsBrowserConversation(conversationId)) return@withLock null
            val loaded = store.read(conversationId, plans) ?: return@withLock null
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

    /** Opening history never requires replaying it. Only adopt a newly recognized MVU
     * loader after its saved state can be continued or a pristine opening initialized. */
    internal suspend fun openForPlay(id: String, preset: PresetAsset,
        webFingerprint: String?): OpenedConversation? {
        val saved = open(id, RoomConversationStore.PlanRead.HEADER) ?: return null
        var ready = saved
        try {
            val program = saved.character.browserProgram
            if (program != null && saved.character.mvuProgram == null && webFingerprint != null &&
                program.runtimeFingerprint != webFingerprint) {
                val candidates = program.sources.filter { it.enabled && it.id !in program.blockedSourceIds && it.id !in program.mvuSourceIds }
                if (candidates.isNotEmpty()) {
                    val checked = prepareBrowser(program.copy(sources = candidates))
                    if (checked.mvu != null) {
                        val upgraded = program.copy(mvu = checked.mvu, mvuSourceIds = program.mvuSourceIds + checked.mvuSourceIds,
                            blockedSourceIds = program.blockedSourceIds + checked.blockedSourceIds,
                            diagnostics = checked.diagnostics)
                        ready = saved.copy(character = saved.character.copy(browserProgram = upgraded))
                    }
                }
            }
            if (ready.character.mvuProgram != null) {
                ready = mvuRuntime.initialize(ready, preset, compiler)
                mvuRuntime.validateCheckpoint(ready.character, ready.runtimeState)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return OpenedConversation(saved, error.message ?: "无法恢复角色程序的状态")
        }
        return OpenedConversation(if (ready === saved) saved else save(ready, saved, activity = false))
    }

    suspend fun contains(conversationId: String): Boolean = withContext(ioDispatcher) {
        ensureInitialized(); store.dao.containsBrowserConversation(conversationId)
    }

    internal suspend fun readPlan(variantId: String) = withContext(ioDispatcher) { store.readPlan(variantId) }

    suspend fun titleSuggestions(id: String): List<String> = withContext(ioDispatcher) {
        ensureInitialized()
        store.database.runInTransaction(java.util.concurrent.Callable {
            suggestedTitles(store.dao.openingTitleSources(id), store.dao.recentTitleSources(id))
        })
    }

    suspend fun rename(id: String, value: String) = withContext(ioDispatcher) {
        val title = cleanConversationTitle(value)
        require(title.isNotEmpty()) { "请输入对话名" }
        require(title.codePointCount(0, title.length) <= MAX_TITLE_LENGTH) { "对话名最多 $MAX_TITLE_LENGTH 个字" }
        ensureInitialized()
        check(store.dao.rename(id, title) == 1) { "找不到这场对话" }
    }

    suspend fun forCharacter(characterId: String): List<ConversationSummary> = withContext(ioDispatcher) {
        ensureInitialized(); store.dao.summaries().filter { it.assetId == characterId }
    }

    suspend fun save(record: ConversationRecord, previous: ConversationRecord? = null,
        activity: Boolean = true, finishStream: String? = null): ConversationRecord = withContext(ioDispatcher) {
        mutex.withLock {
            initializeLocked()
            require(record.executionMode == ConversationExecutionMode.BROWSER) { "仅支持保存网页会话" }
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
