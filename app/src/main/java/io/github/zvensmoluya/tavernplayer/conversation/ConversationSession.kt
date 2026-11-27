package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.connections.activeConnectionId
import io.github.zvensmoluya.tavernplayer.connections.credentialFailureText
import io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuMessageResult
import io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuConversationRuntime
import io.github.zvensmoluya.tavernplayer.conversation.ejs.QuickJsEjsRuntime
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.tavernplayer.connections.ConnectionRepository
import io.github.zvensmoluya.tavernplayer.connections.CredentialStatus
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.BrowserProgram
import io.github.zvensmoluya.tavernplayer.content.PresetAsset
import io.github.zvensmoluya.tavernplayer.content.mvuProgram
import io.github.zvensmoluya.tavernplayer.presets.ActivePresetSource
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

enum class ChatMessageStatus { COMPLETE, STREAMING, CANCELLED, ERROR, INTERRUPTED }

enum class GenerationPhase { IDLE, ACTIVE, STOPPING, FINISHING }

enum class MessageEditMode { TEXT_ONLY, RESTART }

data class AssistantGenerationMetadata(
    val presetId: String,
    val presetName: String,
    val presetContentSha256: String,
    val adapterId: String,
    val model: String,
    val usage: GenerationUsage? = null,
    val finishReason: String? = null,
)

data class ChatMessageState(
    val message: ConversationMessage,
    val status: ChatMessageStatus = ChatMessageStatus.COMPLETE,
    val metadata: AssistantGenerationMetadata? = null,
    val variantIndex: Int = 0,
    val variantCount: Int = 1,
    val displayContent: String = message.content,
    val displayReasoning: List<String> = message.reasoning.map(ReasoningBlock::text),
    val edited: Boolean = false,
)


data class GenerationTraceState(
    val plan: GenerationPlan? = null,
    val compileDiagnostics: List<CompilationDiagnostic> = emptyList(),
    val compileTrace: List<CompilationTraceEntry> = emptyList(),
    val providerPreview: ProviderRequestPreview? = null,
    val streamDiagnostics: List<String> = emptyList(),
    val usage: GenerationUsage? = null,
    val finishReason: String? = null,
    val error: String? = null,
)

data class ChatUiState(
    val readOnlyReason: String? = null,
    val storageFailed: Boolean = false,
    val executionMode: ConversationExecutionMode = ConversationExecutionMode.BROWSER,
    val browserSnapshot: JsonObject = JsonObject(emptyMap()),
    val browserOperationRunning: Boolean = false,
    val generationPhase: GenerationPhase = GenerationPhase.IDLE,
    val browserGeneration: JsonObject = JsonObject(emptyMap()),
    val conversationId: String? = null,
    val character: CharacterSnapshot = EMPTY_CHARACTER,
    val worldBookState: ConversationWorldBookState = ConversationWorldBookState(),
    val worldBookSaving: Boolean = false,
    val worldBookMessage: String? = null,
    val persona: Persona = Persona("traveler", "旅人"),
    val messages: List<ChatMessageState> = emptyList(),
    val input: String = "",
    val readyConnections: List<StoredConnection> = emptyList(),
    val selectedConnectionId: String? = null,
    val activePresetId: String = "",
    val activePresetName: String = "",
    val loadingConnections: Boolean = true,
    val loadingConversation: Boolean = false,
    val editingMessage: Boolean = false,
    val retryAvailable: Boolean = false,
    val regenerateAvailable: Boolean = false,
    val variantNavigationAvailable: Boolean = false,
    val message: String? = null,
    val lastTrace: GenerationTraceState? = null,
    val conversationState: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
) {
    val running: Boolean get() = !browserOperationRunning && generationPhase != GenerationPhase.IDLE
    val browserGenerating: Boolean get() = browserOperationRunning && generationPhase != GenerationPhase.IDLE
    val busy: Boolean get() = generationPhase != GenerationPhase.IDLE || storageFailed || editingMessage || worldBookSaving || loadingConversation || browserOperationRunning
    internal val eraScriptId: String? get() = browserSnapshot["era146ScriptId"]?.jsonPrimitive?.contentOrNull
    val canRequestTurn: Boolean get() = !busy || (browserOperationRunning && generationPhase == GenerationPhase.IDLE &&
        !storageFailed && !editingMessage && !worldBookSaving && !loadingConversation && eraScriptId != null)
    val selectedConnection: StoredConnection?
        get() = readyConnections.firstOrNull { it.id == selectedConnectionId }
}

class ConversationSession(
    private val repository: ConnectionRepository,
    private val compiler: PromptCompiler,
    private val generator: ConversationGenerator,
    private val conversationRepository: ConversationRepository? = null,
    private val presetSource: ActivePresetSource,
    characterAsset: CharacterAsset = DemoConversationContent.character,
    persona: Persona = DemoConversationContent.persona,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
    private val projectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val mvuRuntime: MvuConversationRuntime = MvuConversationRuntime(),
    private val ejsRuntime: QuickJsEjsRuntime = QuickJsEjsRuntime(),
    val browserEnvironment: io.github.zvensmoluya.tavernplayer.conversation.web.BrowserEnvironment? = null,
    private val globalWorldBooks: suspend () -> GlobalWorldBookSnapshot = { GlobalWorldBookSnapshot() },
) {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    private var currentPreset = presetSource.captureActive()
    private var record: ConversationRecord = fallbackRecord(characterAsset, persona, currentPreset)
    private val displayCache = record.selectedMessages().associate { it.id to it.content }.toMutableMap()
    private val displayReasoningCache = record.selectedMessages()
        .associate { message -> message.id to message.reasoning.map(ReasoningBlock::text) }
        .toMutableMap()
    private val _uiState = MutableStateFlow(
        record.toUiState(
            loadingConnections = true,
            activePreset = currentPreset,
            displayContents = displayCache,
            displayReasoning = displayReasoningCache,
        ),
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private val browserHostMutex = Mutex()
    private data class EraSelection(val conversation: String, val epoch: Long, val variant: String, val script: String)
    private val savedEraSelection = MutableStateFlow<EraSelection?>(null)
    private var pendingEraAction: Job? = null
    private var browserGenerationId: String? = null
    private var pendingBrowserDraft: String? = null
    private var generationJob: Job? = null
    private var persistenceJob: Job? = null
    private var persistenceDirty = false
    private var completedMvuResult: MvuMessageResult? = null
    private val assistantBuffer = StringBuilder()
    private var rawAssistant: String
        get() = assistantBuffer.toString()
        set(value) { assistantBuffer.setLength(0); assistantBuffer.append(value) }
    private var persistedRecord: ConversationRecord? = null
    private val commitMutex = Mutex()
    private var epoch = 0L
    private var closing = false
    private var retryingSave = false
    private var storageFailure: Exception? = null
    private var pendingCommit: Pair<ConversationRecord, String?>? = null
    private var journal: StreamJournal? = null
    private var deferPublication = false
    private var previewJob: Job? = null
    private var lastPreviewAt = 0L
    private var streamFinishSeen = false
    private var streamFinishReason: String? = null
    private var rawStateConfirmation: String? = null
    private class BufferedReasoning(val text: StringBuilder = StringBuilder(), var signature: String? = null)
    private val rawReasoning = mutableListOf<BufferedReasoning>()
    private var pendingPlanRuntime: ConversationRuntimeState? = null
    private var pendingAssistantRuntime: ConversationRuntimeState? = null

    init {
        if (record.character.mvuProgram != null) {
            _uiState.update { it.copy(loadingConversation = true) }
            scope.launch {
                try {
                    record = mvuRuntime.initialize(record, currentPreset, compiler)
                    syncRecord()
                } catch (error: Exception) {
                    _uiState.update { it.copy(message = "MVU 初始化失败：${error.userMessage()}") }
                } finally {
                    _uiState.update { it.copy(loadingConversation = false) }
                }
            }
        }
        scope.launch {
            repository.state.collect { gatewayState ->
                val ready = gatewayState.connections.filter { connection ->
                    connection.selectedModel.isNotBlank() && repository.credentialStatus(connection) in READY_STATUSES
                }.sortedBy { it.name.lowercase() }
                val current = _uiState.value.selectedConnectionId
                val selected = activeConnectionId(ready, gatewayState.recentConnectionId, current)
                _uiState.update {
                    it.copy(readyConnections = ready, selectedConnectionId = selected, loadingConnections = false)
                }
            }
        }
        scope.launch {
            presetSource.activePreset.collect { active ->
                currentPreset = active.snapshot()
                _uiState.update {
                    it.copy(activePresetId = currentPreset.id, activePresetName = currentPreset.name)
                }
                if (!_uiState.value.running) refreshDisplayCache(currentPreset)
            }
        }
    }

    fun loadConversation(conversationId: String) {
        val state = _uiState.value
        if (state.loadingConversation || state.browserOperationRunning || (state.busy && generationJob == null)) return
        _uiState.update { it.copy(loadingConversation = true) }
        scope.launch {
            try {
                stopGenerationAndJoin()
                if (persistedRecord != null) persistNow()
                val opened = conversationRepository?.openForPlay(conversationId, currentPreset, browserEnvironment?.fingerprint) ?: return@launch
                epoch++
                savedEraSelection.value = null
                record = opened.record
                persistedRecord = opened.record
                displayCache.clear(); displayReasoningCache.clear()
                storageFailure = null
                _uiState.update { it.copy(storageFailed = false, readOnlyReason = opened.readOnlyReason) }
                syncRecord(message = null)
                if (opened.readOnlyReason == null) refreshDisplayCache()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _uiState.update { it.copy(message = "对话未打开：${error.userMessage()}") }
            } finally { _uiState.update { it.copy(loadingConversation = false) } }
        }
    }

    fun updateInput(value: String) {
        if (_uiState.value.readOnlyReason != null) return
        if (_uiState.value.browserOperationRunning) {
            pendingBrowserDraft = value
            _uiState.update { it.copy(input = value) }
            return
        }
        if (_uiState.value.loadingConversation) return
        record = record.withDraft(value)
        _uiState.update { state ->
            state.copy(input = value, message = null, browserSnapshot = JsonObject(state.browserSnapshot + mapOf(
                "draft" to JsonPrimitive(value), "revision" to JsonPrimitive(BrowserConversation.revision(record)))))
        }
        schedulePersist()
    }

    /**
     * 玩家对单项世界书的使用方式与正文调整，单独保存到本对话。
     *
     * 结果落在会话级的 `worldBookState` 上——不改角色资产，也不随消息候选回退。
     * 一次保存成功后才发布，失败保留原状态并给出提示。
     */
    fun setWorldBookEntryMode(bookId: String, entryId: String, mode: WorldBookEntryMode?) = commitWorldBook {
        ConversationWorldBookController.setMode(it, bookId, entryId, mode)
    }

    fun setWorldBookEntryContent(bookId: String, entryId: String, content: String, onSaved: () -> Unit = {}) = commitWorldBook(onSaved) {
        ConversationWorldBookController.setContent(it, bookId, entryId, content)
    }

    private fun commitWorldBook(onSaved: () -> Unit = {}, edit: (ConversationRecord) -> ConversationRecord) {
        if (_uiState.value.readOnlyReason != null) return
        if (_uiState.value.busy || _uiState.value.browserGenerating) return
        val proposed = try {
            edit(record)
        } catch (error: Exception) {
            _uiState.update { it.copy(worldBookMessage = error.message ?: "世界书调整失败") }
            return
        }
        if (proposed == record) { onSaved(); return }
        _uiState.update { it.copy(worldBookSaving = true, worldBookMessage = null) }
        scope.launch {
            try {
                record = commit(proposed)
                syncRecord()
                onSaved()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _uiState.update { it.copy(worldBookMessage = "世界书调整未能保存，请重试") }
            } finally {
                _uiState.update { it.copy(worldBookSaving = false) }
            }
        }
    }

    private suspend fun <T> withGeneration(browserId: String? = null, stream: Boolean = false, block: suspend () -> T): T = coroutineScope {
        check(generationJob == null)
        generationJob = currentCoroutineContext().job
        browserGenerationId = browserId
        _uiState.update { state ->
            if (browserId == null) state.copy(generationPhase = GenerationPhase.ACTIVE, message = null)
            else state.copy(generationPhase = GenerationPhase.ACTIVE, browserOperationRunning = true,
                browserGeneration = buildJsonObject {
                    put("id", browserId); put("text", ""); put("stream", stream); put("status", "running")
                })
        }
        try {
            currentCoroutineContext().ensureActive()
            block()
        } finally {
            generationJob = null
            browserGenerationId = null
            _uiState.update { it.copy(generationPhase = GenerationPhase.IDLE, browserOperationRunning = false) }
        }
    }

    private fun launchChatOperation(generates: Boolean = true, block: suspend () -> Unit) {
        if (_uiState.value.readOnlyReason != null) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            suspend fun execute() {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    if (storageFailure == null) abortBeforeStreaming("已停止生成")
                    throw cancelled
                } catch (error: Exception) {
                    if (storageFailure == null) abortBeforeStreaming(error.userMessage())
                }
            }
            if (generates) withGeneration { execute() }
            else {
                _uiState.update { it.copy(editingMessage = true, message = null) }
                try { execute() } finally { _uiState.update { it.copy(editingMessage = false) } }
            }
        }
    }

    private fun eraSelection(state: ChatUiState): EraSelection? {
        val script = state.eraScriptId ?: return null
        val tail = record.turns.lastOrNull()?.selected ?: return null
        return EraSelection(record.id, epoch, tail.id, script)
    }

    /** Accept a player's tap while ERA is finishing. Leave the bridge writable until it acknowledges. */
    private fun waitForEra(draft: String? = null, action: () -> Unit): Boolean {
        val state = _uiState.value
        if (!state.canRequestTurn || state.readOnlyReason != null) return true
        val selection = eraSelection(state) ?: return state.busy
        if (pendingEraAction?.isActive == true) return true
        if (!state.busy && savedEraSelection.value == selection) return false
        pendingEraAction = scope.launch {
            _uiState.update { it.copy(message = "正在等待角色状态保存…") }
            val ready = withTimeoutOrNull(30_000) {
                uiState.combine(savedEraSelection) { ui, saved ->
                    val current = eraSelection(ui)
                    when {
                        current != selection || ui.storageFailed || (draft != null && ui.input != draft) -> false
                        saved == selection && !ui.busy -> true
                        else -> null
                    }
                }.first { it != null }
            }
            pendingEraAction = null
            if (eraSelection(_uiState.value) == selection) {
                if (ready == null) _uiState.update { it.copy(message = "ERA 尚未完成本轮保存，请检查网页运行提示后重试") }
                else if (_uiState.value.message == "正在等待角色状态保存…") _uiState.update { it.copy(message = null) }
                if (ready == true && (draft == null || _uiState.value.input == draft)) action()
            }
        }
        return true
    }

    fun send() {
        val state = _uiState.value
        val connection = state.selectedConnection
        if (state.input.isBlank() || waitForEra(state.input, ::send)) return
        if (connection == null) {
            _uiState.update { it.copy(message = "请先配置可用模型") }
            return
        }
        val inputText = state.input
        val capturedPreset = presetSource.captureActive()
        launchChatOperation {
            val generationId = idGenerator()
            record = mvuRuntime.initialize(record, capturedPreset, compiler)
            val historyBefore = record.promptMessages()
            val runtimeBeforeInput = record.runtimeState
            val projected = compiler.projectUserInput(
                text = inputText,
                character = record.character,
                persona = record.persona,
                preset = capturedPreset,
                runtimeState = record.runtimeState,
                history = historyBefore,
                conversationId = record.id,
                generationId = generationId,
                modelId = connection.selectedModel,
            ) as TextExpansionResult.Success
            val userMessage = ConversationMessage(
                id = idGenerator(),
                role = MessageRole.USER,
                content = projected.text,
                sourceText = inputText,
                authorName = record.persona.name,
                createdAtEpochMillis = now(),
            )
            val displayed = withContext(projectionDispatcher) {
                compiler.projectDisplayText(
                    text = userMessage.content,
                    role = MessageRole.USER,
                    character = record.character,
                    persona = record.persona,
                    preset = capturedPreset,
                    runtimeState = projected.runtimeState,
                    history = historyBefore,
                    conversationId = record.id,
                    generationId = generationId,
                    modelId = connection.selectedModel,
                ) as TextExpansionResult.Success
            }
            displayCache[userMessage.id] = displayed.text
            val userTurn = ConversationTurn(
                id = idGenerator(),
                role = MessageRole.USER,
                variants = listOf(
                    MessageVariant(
                        id = idGenerator(),
                        message = userMessage,
                        runtimeStateBefore = runtimeBeforeInput,
                        projectionRuntimeStateBefore = runtimeBeforeInput,
                        runtimeStateAfter = projected.runtimeState,
                    ),
                ),
            )
            record = record.copy(turns = record.turns + userTurn, runtimeState = projected.runtimeState)
            syncRecord(input = "", message = null, retryAvailable = false)
            withContext(NonCancellable) { persistNow() }
            generate(connection, generationId, appendAssistantTurn = true, preset = capturedPreset)
        }
    }

    fun retry() {
        val state = _uiState.value
        val connection = state.selectedConnection ?: return
        if (!state.retryAvailable || record.turns.lastOrNull()?.role != MessageRole.USER || waitForEra(action = ::retry)) return
        val capturedPreset = presetSource.captureActive()
        launchChatOperation {
            generate(connection, idGenerator(), appendAssistantTurn = true, preset = capturedPreset)
        }
    }

    fun regenerate() {
        val state = _uiState.value
        val connection = state.selectedConnection ?: return
        val last = record.turns.lastOrNull() ?: return
        if (last.role != MessageRole.ASSISTANT || record.turns.dropLast(1).lastOrNull()?.role != MessageRole.USER ||
            waitForEra(action = ::regenerate)) return
        val capturedPreset = presetSource.captureActive()
        launchChatOperation {
            generate(connection, idGenerator(), appendAssistantTurn = false, preset = capturedPreset)
        }
    }

    fun editMessage(messageId: String, sourceText: String, mode: MessageEditMode) {
        val state = _uiState.value
        if (sourceText.isBlank() || waitForEra { editMessage(messageId, sourceText, mode) }) return
        val turnIndex = record.turns.indexOfFirst { it.selected.message.id == messageId }
        if (turnIndex < 0) return
        val turn = record.turns[turnIndex]
        val selected = turn.selected
        if (selected.status == PersistedMessageStatus.STREAMING || turn.role == MessageRole.SYSTEM) return
        val capturedPreset = presetSource.captureActive()
        val connection = state.selectedConnection
        val shouldGenerate = mode == MessageEditMode.RESTART && turn.role == MessageRole.USER && connection != null
        launchChatOperation(generates = shouldGenerate) {
            val generationId = idGenerator()
            val historyBefore = record.turns.take(turnIndex).map { it.selected.message }
            val runtimeBefore = selected.runtimeStateBefore
                ?: record.turns.getOrNull(turnIndex - 1)?.selected?.nativeHead()
                ?: ConversationRuntimeState()
            val modelId = connection?.selectedModel ?: selected.model.orEmpty()
            val editedVariant = when (turn.role) {
                MessageRole.USER -> {
                    val projected = compiler.projectUserInput(
                        text = sourceText,
                        character = record.character,
                        persona = record.persona,
                        preset = capturedPreset,
                        runtimeState = runtimeBefore,
                        history = historyBefore,
                        conversationId = record.id,
                        generationId = generationId,
                        modelId = modelId,
                    ) as TextExpansionResult.Success
                    if (mode == MessageEditMode.TEXT_ONLY) {
                        selected.copy(
                            message = selected.message.copy(content = projected.text, sourceText = sourceText),
                            edited = true,
                        )
                    } else {
                        selected.copy(
                            message = selected.message.copy(
                                content = projected.text,
                                sourceText = sourceText,
                                reasoning = emptyList(),
                                adapterId = null,
                            ),
                            status = PersistedMessageStatus.COMPLETE,
                            presetId = null,
                            presetName = null,
                            presetContentSha256 = null,
                            adapterId = null,
                            model = null,
                            finishReason = null,
                            inputTokens = null,
                            outputTokens = null,
                            totalTokens = null,
                            cachedTokens = null,
                            reasoningTokens = null,
                            generationPlan = null,
                            edited = true,
                            runtimeStateBefore = runtimeBefore,
                            projectionRuntimeStateBefore = runtimeBefore,
                            runtimeStateAfter = projected.runtimeState,
                            nativeOperations = emptyList(),
                            browserHead = null,
                            browserVariables = JsonObject(emptyMap()),
                            browserOwnVariables = false,
                        )
                    }
                }
                MessageRole.ASSISTANT -> {
                    val projectionRuntime = selected.projectionRuntimeStateBefore
                        ?: selected.generationPlan?.runtimeState
                        ?: runtimeBefore
                    fun project(text: String) = compiler.projectAssistantOutput(
                        rawText = text,
                        rawReasoning = emptyList(),
                        character = record.character,
                        persona = record.persona,
                        preset = capturedPreset,
                        runtimeState = projectionRuntime,
                        history = historyBefore,
                        conversationId = record.id,
                        generationId = generationId,
                        modelId = modelId,
                    )
                    var projected = project(sourceText)
                    var projectedRuntime = projected.runtimeState
                    if (mode == MessageEditMode.RESTART) {
                        applyMvuUpdate(sourceText, projectedRuntime, selected.openingSourceIndex != null)?.let { result ->
                            projected = project(result.processedText)
                            projectedRuntime = result.applyTo(projected.runtimeState)
                        }
                    }
                    if (mode == MessageEditMode.TEXT_ONLY) {
                        selected.copy(
                            message = selected.message.copy(
                                content = projected.storageText,
                                sourceText = sourceText,
                            ),
                            edited = true,
                        )
                    } else {
                        selected.copy(
                            message = selected.message.copy(
                                content = projected.storageText,
                                sourceText = sourceText,
                                stateConfirmation = null,
                                reasoning = emptyList(),
                                adapterId = null,
                            ),
                            status = PersistedMessageStatus.COMPLETE,
                            presetId = capturedPreset.id,
                            presetName = capturedPreset.name,
                            presetContentSha256 = capturedPreset.contentSha256,
                            adapterId = null,
                            model = null,
                            finishReason = null,
                            inputTokens = null,
                            outputTokens = null,
                            totalTokens = null,
                            cachedTokens = null,
                            reasoningTokens = null,
                            generationPlan = null,
                            edited = true,
                            runtimeStateBefore = runtimeBefore,
                            projectionRuntimeStateBefore = projectionRuntime,
                            runtimeStateAfter = projectedRuntime,
                            playerChoiceCommits = emptyList(),
                            nativeOperations = emptyList(),
                            browserHead = null,
                            browserVariables = JsonObject(emptyMap()),
                            browserOwnVariables = false,
                        )
                    }
                }
                MessageRole.SYSTEM -> return@launchChatOperation
            }
            val editedTurn = if (mode == MessageEditMode.TEXT_ONLY) {
                turn.copy(
                    variants = turn.variants.mapIndexed { index, variant ->
                        if (index == turn.selectedVariantIndex) editedVariant else variant
                    },
                )
            } else {
                turn.copy(variants = listOf(editedVariant), selectedVariantIndex = 0)
            }
            record = if (mode == MessageEditMode.TEXT_ONLY) {
                record.copy(
                    turns = record.turns.mapIndexed { index, item -> if (index == turnIndex) editedTurn else item },
                )
            } else {
                record.copy(
                    turns = record.turns.take(turnIndex) + editedTurn,
                    runtimeState = editedVariant.runtimeStateAfter ?: runtimeBefore,
                )
            }
            val retainedMessageIds = record.turns
                .flatMap { item -> item.variants.map { it.message.id } }
                .toSet()
            displayCache.keys.retainAll(retainedMessageIds)
            displayReasoningCache.keys.retainAll(retainedMessageIds)
            displayCache.remove(editedVariant.message.id)
            displayReasoningCache.remove(editedVariant.message.id)
            val notice = if (mode == MessageEditMode.RESTART && turn.role == MessageRole.USER && connection == null) {
                "修改已保存；请先配置可用模型"
            } else null
            try { withContext(NonCancellable) { persistNow() } } catch (error: Exception) {
                record = conversationRepository?.get(record.id) ?: record
                throw error
            }
            syncRecord(
                retryAvailable = if (mode == MessageEditMode.TEXT_ONLY) {
                    state.retryAvailable
                } else {
                    turn.role == MessageRole.USER && !shouldGenerate
                },
                message = notice,
                trace = if (mode == MessageEditMode.TEXT_ONLY) state.lastTrace else null,
            )
            refreshDisplayCache(capturedPreset)
            if (shouldGenerate) {
                generate(checkNotNull(connection), idGenerator(), appendAssistantTurn = true, preset = capturedPreset)
            }
        }
    }

    fun previousVariant() = selectVariant(-1)

    fun nextVariant() = selectVariant(1)

    fun cancel() {
        val job = generationJob ?: return
        if (_uiState.value.generationPhase != GenerationPhase.ACTIVE) return
        _uiState.update { it.copy(generationPhase = GenerationPhase.STOPPING) }
        job.cancel()
    }

    private suspend fun stopGenerationAndJoin() {
        val job = generationJob ?: return
        cancel()
        job.join()
    }

    private var browserRegexRefreshJob: Job? = null

    private fun scheduleBrowserRegexRefresh() {
        browserRegexRefreshJob?.cancel()
        browserRegexRefreshJob = scope.launch {
            // Match the helper's trailing refresh: the saved write returns first so
            // author callbacks may finish (for example select an opening) before reload.
            delay(1000)
            browserHostMutex.withLock {
                refreshDisplayCache()
                syncRecord(input = record.draft)
            }
        }
    }

    suspend fun invokeBrowser(actor: BrowserActor, revision: String, method: String, args: JsonObject): JsonObject {
        _uiState.value.readOnlyReason?.let { error(it) }
        if (method == "generation.stop") {
            BrowserConversation.authorize(record, actor, BrowserConversation.revision(record))
            require(args.keys.all { it == "id" }) { "未支持的停止参数" }
            val requested = args["id"]?.jsonPrimitive?.contentOrNull
            if (requested == null || requested == browserGenerationId) cancel()
            return buildJsonObject { put("snapshot", BrowserConversation.snapshot(record)); put("value", JsonNull) }
        }
        return browserHostMutex.withLock {
            if (method == "era.settled") return@withLock finishEraWrite(actor, args)
            require(!_uiState.value.busy) { "会话正在处理其他操作" }
            // A player may type while the framework writes to a floor. Those operations
            // depend on the committed history but do not use or overwrite a draft.
            val eraWrite = actor.scriptId != null && actor.scriptId == _uiState.value.eraScriptId &&
                (method == "messages.set" || method == "variables.replace")
            val currentRevision = BrowserConversation.revision(record)
            val writeRevision = if (eraWrite && revision.substringBeforeLast(':') == currentRevision.substringBeforeLast(':'))
                currentRevision else revision
            BrowserConversation.authorize(record, actor, writeRevision)
            if (eraWrite && args["type"] == JsonPrimitive("chat") && args["data"] != record.runtimeState.browserChatVariables) {
                savedEraSelection.value = null
            }
            if (method == "chat.send") {
                require(args.isEmpty() && record.draft.isNotBlank()) { "发送需要非空草稿" }
                require(_uiState.value.selectedConnection != null) { "请先配置模型" }
                send()
                return@withLock buildJsonObject { put("snapshot", BrowserConversation.snapshot(record)); put("value", JsonNull) }
            }
            val result = try {
                if (method == "generation.generate" || method == "generation.raw") {
                    val id = args["generation_id"]?.jsonPrimitive?.contentOrNull ?: idGenerator()
                    withGeneration(id, args["should_stream"]?.jsonPrimitive?.booleanOrNull == true) {
                        try {
                            executeBrowserCommand(actor, revision, method, args)
                        } catch (cancelled: CancellationException) {
                            setBrowserGenerationStatus("cancelled")
                            throw cancelled
                        } catch (error: Exception) {
                            setBrowserGenerationStatus("error")
                            throw error
                        }
                    }
                } else {
                    _uiState.update { it.copy(browserOperationRunning = true) }
                    try {
                        executeBrowserCommand(actor, writeRevision, method, args)
                    } finally {
                        _uiState.update { it.copy(browserOperationRunning = false) }
                    }
                }
            } finally {
                syncRecord(input = record.draft)
                pendingBrowserDraft?.let { draft -> pendingBrowserDraft = null; updateInput(draft) }
            }
            browserCommandResult(method, args, result)
        }
    }

    private suspend fun finishEraWrite(actor: BrowserActor, args: JsonObject): JsonObject {
        val state = _uiState.value
        val selection = eraSelection(state)
        require(selection != null && actor.scriptId == selection.script && actor.turnId == null) { "当前脚本没有 ERA 写入归属" }
        val meta = record.runtimeState.browserChatVariables["ERAMetaData"] as? JsonObject
        val key = (meta?.get("SelectedMks") as? JsonArray)?.getOrNull(record.turns.lastIndex)
        // A replay or a result from a retired candidate cannot unlock the next generation.
        if (state.busy || args["variant"] != JsonPrimitive(selection.variant) || key !is JsonPrimitive ||
            !key.isString || key != args["mk"]) return JsonObject(emptyMap())
        val changes = args.getValue("changes").jsonObject
        require(changes.size <= 4096 && changes.toString().length <= 1024 * 1024) { "楼层显示文字超过限制" }
        val text = changes.mapValues { (_, value) -> value.jsonPrimitive.also { require(it.isString) }.content }
        val turn = record.turns.last()
        if (turn.selected.browserDisplayChanges != text) {
            _uiState.update { it.copy(browserOperationRunning = true) }
            try {
                val variants = turn.variants.toMutableList()
                variants[turn.selectedVariantIndex] = turn.selected.copy(browserDisplayChanges = text)
                withContext(NonCancellable) {
                    record = try { commit(record.copy(turns = record.turns.dropLast(1) + turn.copy(variants = variants))) }
                    catch (error: Exception) { throw BrowserPersistenceException(error) }
                    syncRecord()
                }
            } finally {
                _uiState.update { it.copy(browserOperationRunning = false) }
                pendingBrowserDraft?.let { draft -> pendingBrowserDraft = null; updateInput(draft) }
            }
        }
        val result = browserCommandResult("era.settled", args, JsonNull)
        savedEraSelection.value = selection
        return result
    }

    private suspend fun executeBrowserCommand(actor: BrowserActor, revision: String, method: String, args: JsonObject): JsonElement {
        var result: JsonElement = JsonNull
        try { persistNow() } catch (error: Exception) {
            if (error is CancellationException) throw error
            record = conversationRepository?.get(record.id) ?: record
            throw BrowserPersistenceException(error)
        }
        currentCoroutineContext().ensureActive()
        BrowserConversation.authorize(record, actor, revision)
        when (method) {
            "resources.reprepare" -> {
                require(actor.id == "native-resource-preparation" && args.isEmpty()) { "资源更新只能从原生界面操作" }
                result = JsonPrimitive(requireNotNull(browserEnvironment).resources.reprepare(record.id, record.character.sourceSha256))
            }
            "worldbook.entries.read" -> result = BrowserConversation.readWorldBookEntries(record, args)
            "generation.generate", "generation.raw" -> {
                result = JsonPrimitive(generateBrowserText(method, args))
                setBrowserGenerationStatus("complete")
            }
            else -> {
                val proposed = BrowserConversation.apply(record, actor, method, args)
                val regexChanged = method == "regex.replace" && proposed.character.regexScripts != record.character.regexScripts
                withContext(NonCancellable) {
                    record = try { commit(proposed) }
                    catch (error: Exception) { throw BrowserPersistenceException(error) }
                    if (method != "regex.replace" && (!method.startsWith("messages.") || args["refresh"]?.jsonPrimitive?.content != "none")) refreshDisplayCache()
                    if (regexChanged) scheduleBrowserRegexRefresh()
                    syncRecord(input = record.draft)
                }
            }
        }
        return result
    }

    private fun browserCommandResult(method: String, args: JsonObject, result: JsonElement): JsonObject = buildJsonObject {
        val snapshot = BrowserConversation.snapshot(record)
        val display = _uiState.value.messages.associateBy { it.message.id }
        put("snapshot", JsonObject(snapshot + ("messages" to JsonArray(snapshot.getValue("messages").jsonArray.map { raw ->
            val message = raw.jsonObject
            val projected = display[message.getValue("id").jsonPrimitive.content]
            JsonObject(message + mapOf("display" to JsonPrimitive(projected?.displayContent ?: message.getValue("message").jsonPrimitive.content),
                "reasoning" to JsonArray(projected?.displayReasoning.orEmpty().map(::JsonPrimitive))))
        }))))
        put("value", result)
        if (method.startsWith("messages.")) putJsonObject("refresh") {
            put("mode", args["refresh"] ?: JsonPrimitive("affected"))
            put("messageIds", if (method == "messages.set") JsonArray(args.getValue("messages").jsonArray.map {
                val index = it.jsonObject.getValue("message_id").jsonPrimitive.int
                JsonPrimitive(if (index < 0) record.turns.size + index else index)
            }) else JsonArray(emptyList()))
        }
    }

    private fun setBrowserGenerationStatus(status: String) {
        _uiState.update { it.copy(browserGeneration = JsonObject(it.browserGeneration + ("status" to JsonPrimitive(status)))) }
    }

    private suspend fun generateBrowserText(method: String, args: JsonObject): String {
        val allowed = if (method == "generation.raw") setOf("ordered_prompts", "should_stream", "generation_id")
            else setOf("user_input", "should_stream", "generation_id", "max_chat_history")
        require(args.keys.all { it in allowed }) { "生成参数超出 player-web-1 范围" }
        args["should_stream"]?.let { require(it.jsonPrimitive.booleanOrNull != null) { "should_stream 必须为布尔值" } }
        val connection = requireNotNull(_uiState.value.selectedConnection) { "请先配置模型" }
        val preset = presetSource.captureActive()
        val globalBooks = if (method == "generation.raw") GlobalWorldBookSnapshot() else globalWorldBooks()
        val limits = connection.effectiveTokenLimits()
        val plan = if (method == "generation.raw") {
            val prompts = args["ordered_prompts"] as? JsonArray ?: error("缺少 ordered_prompts")
            require(prompts.isNotEmpty() && prompts.size <= 128) { "提示词数量超出范围" }
            val messages = prompts.map { value ->
                val prompt = value as? JsonObject ?: error("首版 raw 生成只支持显式 role/content 提示词")
                require(prompt.keys == setOf("role", "content")) { "提示词字段不受支持" }
                val role = when (prompt["role"]?.jsonPrimitive?.content) { "system" -> MessageRole.SYSTEM; "user" -> MessageRole.USER; "assistant" -> MessageRole.ASSISTANT; else -> error("无效 role") }
                val text = prompt["content"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: error("content 必须为字符串")
                require(text.length <= 262_144) { "提示词超过限制" }
                PreparedMessage(role, text, PromptOrigin("browser-auxiliary", listOf(browserGenerationId.orEmpty())))
            }
            GenerationPlan(messages = messages, maxOutputTokens = minOf(2048L, limits.outputTokens ?: 2048L).toInt(),
                declaredContextTokens = limits.contextTokens?.toIntSafe(), assistantPrefill = "", presetId = "browser-raw",
                presetName = "网页辅助生成", presetContentSha256 = "", diagnostics = emptyList(), trace = emptyList(), runtimeState = record.runtimeState)
        } else {
            require(args["user_input"] == null || (args["user_input"] as? JsonPrimitive)?.isString == true) { "user_input 必须为字符串" }
            val input = args["user_input"]?.jsonPrimitive?.content.orEmpty()
            require(input.length <= 262_144) { "生成输入超过限制" }
            val countValue = args["max_chat_history"]
            require(countValue == null || countValue == JsonPrimitive("all") || (countValue as? JsonPrimitive)?.intOrNull != null) { "max_chat_history 必须为非负整数或 all" }
            val count = countValue?.jsonPrimitive?.intOrNull
            require(count == null || count >= 0) { "max_chat_history 必须为非负整数" }
            var history = record.promptMessages().let { if (count == null) it else it.takeLast(count) }
            if (input.isNotBlank()) history = history + ConversationMessage(idGenerator(), MessageRole.USER, input, record.persona.name)
            val compileInput = globalBooks.apply(NormalGenerationInput(
                character = record.character, persona = record.persona, history = history, preset = preset,
                worldBookState = record.worldBookState,
                runtimeState = record.runtimeState, conversationId = record.id, generationId = browserGenerationId.orEmpty(),
                modelId = connection.selectedModel, modelContextTokens = limits.contextTokens?.toIntSafe(),
                modelOutputTokens = limits.outputTokens?.toIntSafe(),
            ))
            val compiled = withContext(projectionDispatcher) { ejsRuntime.compile(compiler, compileInput, mutableMapOf()) }
            when (compiled) {
                is CompilationResult.Success -> compiled.plan
                is CompilationResult.Failure -> error(compiled.diagnostics.firstOrNull { it.severity == DiagnosticSeverity.ERROR }?.message ?: "辅助生成编排失败")
            }
        }
        val text = StringBuilder()
        var finished = false
        val budget = withContext(projectionDispatcher) { generator.validateTokens(connection, plan) }
        currentCoroutineContext().ensureActive()
        val declaredContext = plan.declaredContextTokens
        require(declaredContext == null || (budget != null && budget.inputTokens.toLong() + plan.maxOutputTokens <= declaredContext)) { "辅助生成超过上下文预算" }
        val prepared = BrowserConversation.withRuntime(record, plan.runtimeState)
        withContext(NonCancellable) {
            record = try { commit(prepared) }
            catch (error: Exception) { throw BrowserPersistenceException(error) }
            syncRecord()
        }
        currentCoroutineContext().ensureActive()
        generator.stream(connection, plan).collect { event -> when (event) {
            is GenerationEvent.TextDelta -> { require(text.length + event.text.length <= 2 * 1024 * 1024) { "生成正文超过限制" }; text.append(event.text); _uiState.update { it.copy(browserGeneration = JsonObject(it.browserGeneration + ("text" to JsonPrimitive(text.toString())))) } }
            is GenerationEvent.Finished -> finished = event.reason in setOf("completed", "stop", "end_turn", "STOP", "stop_sequence")
            else -> Unit
        } }
        currentCoroutineContext().ensureActive()
        require(finished) { "辅助生成未完整结束" }
        return text.toString()
    }

    fun selectConnection(connectionId: String) {
        val state = _uiState.value
        if (state.busy || state.readyConnections.none { it.id == connectionId }) return
        scope.launch {
            runCatching { repository.activate(connectionId) }
                .onSuccess { _uiState.update { it.copy(selectedConnectionId = connectionId, message = null) } }
                .onFailure { error -> _uiState.update { it.copy(message = error.userMessage()) } }
        }
    }

    fun resetConversation() {
        if (_uiState.value.readOnlyReason != null) return
        if (_uiState.value.editingMessage) return
        if (_uiState.value.browserOperationRunning || _uiState.value.loadingConversation) return
        _uiState.update { it.copy(loadingConversation = true) }
        scope.launch {
            try {
                stopGenerationAndJoin()
                persistenceJob?.cancelAndJoin()
                val previous = record
                val reset = fallbackRecord(
                    io.github.zvensmoluya.tavernplayer.content.CharacterAsset(
                        id = record.character.assetId,
                        sourceSha256 = record.character.sourceSha256,
                        name = record.character.name,
                        nickname = record.character.promptName.takeIf { it != record.character.name },
                        description = record.character.description,
                        personality = record.character.personality,
                        scenario = record.character.scenario,
                        firstMessage = record.character.firstMessage,
                        alternateFirstMessages = record.character.alternateFirstMessages,
                        rawMessageExamples = record.character.rawMessageExamples,
                        systemPrompt = record.character.systemPrompt,
                        postHistoryInstructions = record.character.postHistoryInstructions,
                        creatorNotes = record.character.creatorNotes,
                        creator = record.character.creator,
                        characterVersion = record.character.characterVersion,
                        depthPrompt = record.character.depthPrompt,
                        worldBooks = record.character.worldBooks,
                        regexScripts = record.character.regexScripts,
                        assets = record.character.assets,
                    ),
                    record.persona,
                    presetSource.captureActive(),
                ).copy(id = previous.id, createdAtEpochMillis = previous.createdAtEpochMillis, character = previous.character, executionMode = previous.executionMode)
                record = mvuRuntime.initialize(reset, currentPreset, compiler)
                displayCache.clear()
                displayReasoningCache.clear()
                val state = _uiState.value
                _uiState.value = record.toUiState(
                    loadingConnections = state.loadingConnections,
                    activePreset = currentPreset,
                    readyConnections = state.readyConnections,
                    selectedConnectionId = state.selectedConnectionId,
                    displayContents = displayCache,
                    displayReasoning = displayReasoningCache,
                ).copy(loadingConversation = true)
                persistNow()
                refreshDisplayCache()
            } catch (error: Exception) {
                _uiState.update { it.copy(message = "对话未重置：${error.userMessage()}") }
            } finally {
                _uiState.update { it.copy(loadingConversation = false) }
            }
        }
    }

    private suspend fun generate(
        connection: StoredConnection,
        generationId: String,
        appendAssistantTurn: Boolean,
        preset: PresetAsset,
    ) {
        currentCoroutineContext().ensureActive()
        val globalBooks = globalWorldBooks()
        record = mvuRuntime.initialize(record, preset, compiler)
        val evaluationInstant = Instant.ofEpochMilli(now())
        val evaluationZoneId = ZoneId.systemDefault()
        val history = if (appendAssistantTurn) record.promptMessages() else record.copy(turns = record.turns.dropLast(1)).promptMessages()
        val runtimeBeforeGeneration = if (appendAssistantTurn) {
            record.runtimeState
        } else {
            record.turns.lastOrNull()?.selected?.runtimeStateBefore ?: record.runtimeState
        }
        mvuRuntime.validateCheckpoint(record.character, runtimeBeforeGeneration)
        val modelTokenLimits = connection.effectiveTokenLimits()
        val priorRuntime = record.runtimeState
        val lastVisibleTurn = record.turns.lastOrNull()
        val baseInput = globalBooks.apply(NormalGenerationInput(
            character = record.character,
            worldBookState = record.worldBookState,
            persona = record.persona,
            history = history,
            preset = preset,
            runtimeState = runtimeBeforeGeneration.copy(
                lastGenerationType = if (appendAssistantTurn) "normal" else "regenerate",
            ),
            conversationId = record.id,
            generationId = generationId,
            modelId = connection.selectedModel,
            modelContextTokens = modelTokenLimits.contextTokens?.toIntSafe(),
            modelOutputTokens = modelTokenLimits.outputTokens?.toIntSafe(),
            firstDisplayedMessageId = 0,
            lastSwipeId = lastVisibleTurn?.variants?.size ?: 1,
            currentSwipeId = lastVisibleTurn?.selectedVariantIndex?.plus(1) ?: 1,
            allChatLastMessageId = record.turns.lastIndex.takeIf { it >= 0 },
            evaluationInstant = evaluationInstant,
            evaluationZoneId = evaluationZoneId,
        ))
        var localInputLimit: Int? = null
        val validationDiagnostics = mutableListOf<CompilationDiagnostic>()
        val validationTrace = mutableListOf<CompilationTraceEntry>()
        var plan: GenerationPlan? = null
        val ejsCache = mutableMapOf<EjsTemplateRequest, String>()
        for (attempt in 0..MAX_PROVIDER_RECLIPS) {
            val compilation = withContext(projectionDispatcher) {
                ejsRuntime.compile(compiler, baseInput.copy(maxInputTokens = localInputLimit), ejsCache)
            }
            if (compilation is CompilationResult.Failure) {
                showCompilationFailure(
                    compilation.diagnostics + validationDiagnostics,
                    compilation.trace + validationTrace,
                    retryAvailable = appendAssistantTurn,
                )
                return
            }
            val candidate = (compilation as CompilationResult.Success).plan
            val validation = try {
                withContext(projectionDispatcher) { generator.validateTokens(connection, candidate) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val diagnostic = CompilationDiagnostic(
                    DiagnosticSeverity.ERROR,
                    "PROVIDER_TOKEN_VALIDATION_FAILED",
                    error.userMessage(),
                )
                showCompilationFailure(candidate.diagnostics + validationDiagnostics + diagnostic, candidate.trace + validationTrace, appendAssistantTurn)
                return
            }
            currentCoroutineContext().ensureActive()
            val accounting = candidate.tokenAccounting
            if (validation == null || accounting == null) {
                plan = candidate
                break
            }
            val contextLimit = accounting.contextLimit
            val overflow = contextLimit?.let { validation.inputTokens.toLong() + candidate.maxOutputTokens - it } ?: 0L
            validationDiagnostics += CompilationDiagnostic(
                DiagnosticSeverity.WARNING,
                if (validation.quality == TokenCountQuality.EXACT) "PROVIDER_TOKEN_COUNT_EXACT" else "PROVIDER_TOKEN_COUNT_ESTIMATED",
                "最终 Provider 请求输入 ${validation.inputTokens} tokens；计数器 ${validation.counter}",
            )
            validationTrace += CompilationTraceEntry(
                stage = "provider-token-validation",
                sourceIds = emptyList(),
                decision = "attempt=${attempt + 1} input=${validation.inputTokens} output=${candidate.maxOutputTokens} context=$contextLimit",
            )
            if (overflow <= 0) {
                plan = candidate.copy(
                    diagnostics = (candidate.diagnostics + validationDiagnostics).distinctBy { Triple(it.code, it.sourceId, it.message) },
                    trace = candidate.trace + validationTrace,
                    tokenAccounting = accounting.copy(
                        inputTokens = validation.inputTokens,
                        quality = validation.quality,
                        tokenizer = validation.counter,
                    ),
                )
                break
            }
            if (attempt == MAX_PROVIDER_RECLIPS) {
                val diagnostic = CompilationDiagnostic(
                    DiagnosticSeverity.ERROR,
                    "FINAL_PROVIDER_CONTEXT_OVERFLOW",
                    "Provider 计数仍超出 context $contextLimit：输入 ${validation.inputTokens}，回复预留 ${candidate.maxOutputTokens}",
                )
                showCompilationFailure(candidate.diagnostics + validationDiagnostics + diagnostic, candidate.trace + validationTrace, appendAssistantTurn)
                return
            }
            localInputLimit = (
                accounting.inputTokens - overflow - PROVIDER_RECLIP_SAFETY_TOKENS
                ).coerceAtLeast(0).toInt()
        }
        currentCoroutineContext().ensureActive()
        val finalPlan = checkNotNull(plan)
        val adapterId = connection.protocol.name
        val assistantMessage = ConversationMessage(
            id = idGenerator(),
            role = MessageRole.ASSISTANT,
            content = "",
            sourceText = "",
            authorName = record.character.promptName,
            adapterId = adapterId,
            createdAtEpochMillis = now(),
        )
        val variant = MessageVariant(
            id = idGenerator(),
            message = assistantMessage,
            status = PersistedMessageStatus.STREAMING,
            presetId = finalPlan.presetId,
            presetName = finalPlan.presetName,
            presetContentSha256 = finalPlan.presetContentSha256,
            adapterId = adapterId,
            model = connection.selectedModel,
            generationPlan = finalPlan,
            runtimeStateBefore = runtimeBeforeGeneration,
            projectionRuntimeStateBefore = finalPlan.runtimeState,
            runtimeStateAfter = finalPlan.runtimeState,
        )
        record = if (appendAssistantTurn) {
            record.copy(
                turns = record.turns + ConversationTurn(idGenerator(), MessageRole.ASSISTANT, listOf(variant)),
            )
        } else {
            val last = record.turns.last()
            record.copy(
                turns = record.turns.dropLast(1) + last.copy(
                    variants = last.variants + variant,
                    selectedVariantIndex = last.variants.size,
                ),
            )
        }
        completedMvuResult = null
        rawAssistant = ""
        rawStateConfirmation = null
        rawReasoning.clear()
        pendingPlanRuntime = finalPlan.runtimeState
        pendingAssistantRuntime = null
        displayCache[assistantMessage.id] = ""
        displayReasoningCache[assistantMessage.id] = emptyList()
        syncRecord(
            retryAvailable = false,
            message = null,
            trace = GenerationTraceState(
                plan = finalPlan,
                compileDiagnostics = finalPlan.diagnostics,
                compileTrace = finalPlan.trace,
            ),
        )
        val activeJournal = StreamJournal(generationId, variant.id)
        try {
            withContext(NonCancellable) {
                persistNow()
                conversationRepository?.startStream(generationId, record, variant.id,
                    StreamContext(generationId, preset, connection.selectedModel, evaluationInstant.toString(), evaluationZoneId.id))
                journal = activeJournal
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            record = recoverConversation(record, emptyList(), compiler)
            pendingCommit = record to null
            reportStorageFailure(error)
            throw error
        }
        streamFinishSeen = false
        streamFinishReason = null
        lastPreviewAt = 0
        val priorSelection = if (appendAssistantTurn) null else lastVisibleTurn?.selectedVariantIndex
        suspend fun finish(cancelled: Boolean? = null, error: Exception? = null) {
            previewJob?.cancelAndJoin()
            previewJob = null
            persistNow() // Preserve raw input before executing terminal projection or MVU.
            deferPublication = true
            completedMvuResult = null
            reprojectAssistantOutput(variant.id, generationId, connection, preset, evaluationInstant, evaluationZoneId)
            if (cancelled == null) {
                if (record.character.mvuProgram != null) {
                    check(streamFinishSeen && streamFinishReason in setOf("completed", "stop", "end_turn", "STOP", "stop_sequence")) {
                        "回复未完整结束，MVU 变量未更新"
                    }
                }
                completedMvuResult = applyMvuUpdate(rawAssistant, pendingAssistantRuntime ?: record.runtimeState)
                if (completedMvuResult != null) reprojectAssistantOutput(variant.id, generationId, connection, preset,
                    evaluationInstant, evaluationZoneId)
                finishIfStreamEnded(variant.id, priorSelection)
                updateVariant(variant.id) { it.copy(finishReason = streamFinishReason) }
            } else finishFailure(variant.id, cancelled, error, priorSelection)
            if (priorSelection != null && record.findVariant(variant.id) == null) {
                record = record.copy(runtimeState = priorRuntime)
            }
            record = commit(record, finishStream = generationId)
            journal = null
            deferPublication = false
            syncRecord()
        }
        try {
            currentCoroutineContext().ensureActive()
            generator.stream(connection, finalPlan).collect { event ->
                activeJournal.receive(event)
                applyEvent(variant.id, generationId, connection, preset, evaluationInstant, evaluationZoneId, event)
                schedulePersist()
                if (activeJournal.pendingChars >= 64 * 1024 || activeJournal.pending.size >= 256) persistNow()
            }
            currentCoroutineContext().ensureActive()
            _uiState.update { it.copy(generationPhase = GenerationPhase.FINISHING) }
            withContext(NonCancellable) { finish() }
        } catch (cancelled: CancellationException) {
            if (storageFailure == null) withContext(NonCancellable) {
                try { finish(cancelled = true) } catch (error: Exception) { reportStorageFailure(error) }
            }
        } catch (error: Exception) {
            _uiState.update { it.copy(generationPhase = GenerationPhase.FINISHING) }
            if (storageFailure == null) {
                try { withContext(NonCancellable) { finish(cancelled = false, error = error) } } catch (failure: Exception) { reportStorageFailure(failure) }
            }
        } finally {
            deferPublication = false
            withContext(NonCancellable) {
                previewJob?.cancelAndJoin()
                previewJob = null
                if (storageFailure == null) try {
                    persistNow()
                    refreshDisplayCache(currentPreset)
                } catch (error: Exception) { reportStorageFailure(error) }
            }
            assistantBuffer.setLength(0)
            rawReasoning.clear()
            rawStateConfirmation = null
            completedMvuResult = null
            pendingAssistantRuntime = null
            if (storageFailure == null) {
                scope.launch {
                    delay(1000)
                    if (!_uiState.value.busy) runCatching { conversationRepository?.collectUnusedBlobs() }
                }
            }
        }
    }

    private fun reportStorageFailure(error: Exception) {
        storageFailure = error
        _uiState.update { it.copy(storageFailed = true, message = "对话保存失败，可重试保存：${error.userMessage()}") }
    }

    private suspend fun applyEvent(
        variantId: String,
        generationId: String,
        connection: StoredConnection,
        preset: PresetAsset,
        evaluationInstant: Instant,
        evaluationZoneId: ZoneId,
        event: GenerationEvent,
    ) {
        commitPreparedRuntime()
        when (event) {
            is GenerationEvent.RequestPrepared -> updateTrace { copy(providerPreview = event.preview) }
            is GenerationEvent.TextDelta -> {
                assistantBuffer.append(event.text)
                requestPreview(variantId, generationId, connection, preset, evaluationInstant, evaluationZoneId)
                schedulePersist()
            }
            GenerationEvent.ReasoningStarted -> {
                if (rawReasoning.lastOrNull()?.let { it.text.isEmpty() && it.signature == null } != true) {
                    rawReasoning += BufferedReasoning()
                    updateVariant(variantId) { variant ->
                        variant.copy(message = variant.message.copy(reasoning = variant.message.reasoning + ReasoningBlock()))
                    }
                }
                schedulePersist()
            }
            is GenerationEvent.ReasoningDelta -> {
                if (rawReasoning.isEmpty()) rawReasoning += BufferedReasoning()
                val lastIndex = rawReasoning.lastIndex
                rawReasoning[lastIndex].text.append(event.text)
                requestPreview(variantId, generationId, connection, preset, evaluationInstant, evaluationZoneId)
                schedulePersist()
            }
            is GenerationEvent.ReasoningSignature -> {
                if (rawReasoning.isEmpty()) rawReasoning += BufferedReasoning()
                val current = rawReasoning.last()
                if (current.signature == null) {
                    current.signature = event.signature
                } else {
                    rawReasoning += BufferedReasoning(signature = event.signature)
                }
                updateVariant(variantId) { variant ->
                    val reasoning = rawReasoning.mapIndexed { index, raw ->
                        variant.message.reasoning.getOrNull(index)?.copy(signature = raw.signature)
                            ?: ReasoningBlock(signature = raw.signature)
                    }
                    variant.copy(message = variant.message.copy(reasoning = reasoning))
                }
                schedulePersist()
            }
            GenerationEvent.ReasoningFinished -> Unit
            is GenerationEvent.Usage -> {
                updateVariant(variantId) { it.copy(inputTokens = event.value.inputTokens, outputTokens = event.value.outputTokens,
                    totalTokens = event.value.totalTokens, cachedTokens = event.value.cachedTokens, reasoningTokens = event.value.reasoningTokens) }
                updateTrace { copy(usage = event.value) }
            }
            is GenerationEvent.AssistantStateConfirmed -> {
                rawStateConfirmation = event.envelope
                requestPreview(variantId, generationId, connection, preset, evaluationInstant, evaluationZoneId)
                schedulePersist()
            }
            is GenerationEvent.Finished -> {
                if (!streamFinishSeen) {
                    streamFinishSeen = true
                    streamFinishReason = event.reason
                    updateTrace { copy(finishReason = event.reason) }
                }
            }
            is GenerationEvent.Diagnostic -> updateTrace {
                copy(streamDiagnostics = (streamDiagnostics + event.summary).takeLast(30))
            }
        }
    }

    private fun requestPreview(variantId: String, generationId: String, connection: StoredConnection,
        preset: PresetAsset, evaluationInstant: Instant, evaluationZoneId: ZoneId) {
        if (previewJob?.isActive == true) return
        val active = journal ?: return
        previewJob = scope.launch {
            try {
                while (journal === active && active.projectedThrough < active.seq) {
                    val waitMillis = ((50_000_000L - (System.nanoTime() - lastPreviewAt)) / 1_000_000).coerceAtLeast(0)
                    if (waitMillis > 0) delay(waitMillis)
                    val through = active.seq
                    reprojectAssistantOutput(variantId, generationId, connection, preset, evaluationInstant, evaluationZoneId)
                    active.projectedThrough = through
                    lastPreviewAt = System.nanoTime()
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                // Final projection runs in the generation job and reports a deterministic failure.
                updateTrace { copy(streamDiagnostics = (streamDiagnostics + "预览投影失败：${error.userMessage()}").takeLast(30)) }
            } finally { previewJob = null }
        }
    }

    private suspend fun applyMvuUpdate(
        source: String, previous: ConversationRuntimeState, opening: Boolean = false,
    ): MvuMessageResult? {
        val result = mvuRuntime.update(record.character, source, previous, opening, record.persona) ?: return null
        val diagnostics = result.diagnostics.filter { it.level in setOf("error", "warn", "warning") }
            .map { "MVU: ${it.text}" }
        if (diagnostics.isNotEmpty()) updateTrace {
            copy(streamDiagnostics = (streamDiagnostics + diagnostics).takeLast(30))
        }
        return result.messages.single()
    }

    private suspend fun commitPreparedRuntime() {
        val runtime = pendingPlanRuntime ?: return
        pendingPlanRuntime = null
        record = commit(record.copy(runtimeState = runtime))
        syncRecord()
    }

    private suspend fun reprojectAssistantOutput(
        variantId: String,
        generationId: String,
        connection: StoredConnection,
        preset: PresetAsset,
        evaluationInstant: Instant,
        evaluationZoneId: ZoneId,
    ) {
        val source = rawAssistant
        val blocks = rawReasoning.map { ReasoningBlock(it.text.toString(), it.signature) }
        val confirmation = rawStateConfirmation
        val snapshot = record
        val history = record.copy(turns = record.turns.dropLast(1)).promptMessages()
        val projectionRuntime = record.findVariant(variantId)?.projectionRuntimeStateBefore ?: record.runtimeState
        val projection = withContext(projectionDispatcher) {
            compiler.projectAssistantOutput(
                rawText = completedMvuResult?.processedText ?: source,
                rawReasoning = blocks.map { it.text },
                character = snapshot.character,
                persona = snapshot.persona,
                preset = preset,
                runtimeState = projectionRuntime,
                history = history,
                conversationId = snapshot.id,
                generationId = generationId,
                modelId = connection.selectedModel,
                evaluationInstant = evaluationInstant,
                evaluationZoneId = evaluationZoneId,
            )
        }
        val projectedRuntime = projection.runtimeState
        // Display reprojection (including cleanup after Finished) must preserve the committed MVU checkpoint.
        val finalRuntime = completedMvuResult?.applyTo(projectedRuntime)
            ?: projectedRuntime.copy(mvuState = record.findVariant(variantId)?.runtimeStateAfter?.mvuState
                ?: projectedRuntime.mvuState)
        pendingAssistantRuntime = finalRuntime
        val messageId = record.findVariant(variantId)?.message?.id
        if (messageId != null) {
            displayCache[messageId] = projection.displayText
            displayReasoningCache[messageId] = projection.displayReasoning
        }
        updateVariant(variantId) { variant ->
            val reasoning = projection.storageReasoning.mapIndexed { index, text ->
                ReasoningBlock(text = text, signature = blocks.getOrNull(index)?.signature)
            }
            val plan = variant.generationPlan?.let { plan ->
                plan.copy(
                    diagnostics = (plan.diagnostics + projection.diagnostics)
                        .distinctBy { Triple(it.code, it.sourceId, it.message) },
                )
            }
            variant.copy(
                message = variant.message.copy(
                    content = projection.storageText,
                    sourceText = source,
                    stateConfirmation = confirmation,
                    reasoning = reasoning,
                ),
                generationPlan = plan,
                runtimeStateAfter = finalRuntime,
            )
        }
        if (projection.diagnostics.isNotEmpty()) {
            updateTrace {
                copy(
                    compileDiagnostics = (compileDiagnostics + projection.diagnostics)
                        .distinctBy { Triple(it.code, it.sourceId, it.message) },
                )
            }
        }
    }

    private fun finishIfStreamEnded(variantId: String, priorSelection: Int?) {
        pendingPlanRuntime = null
        val variant = record.findVariant(variantId) ?: return
        if (variant.message.content.isBlank()) {
            variant.generationPlan?.runtimeState?.let { promptRuntime ->
                record = record.copy(runtimeState = promptRuntime)
            }
            pendingAssistantRuntime = null
            removeVariant(variantId, priorSelection)
            _uiState.update { it.copy(retryAvailable = true, message = "模型没有返回正文") }
            updateTrace { copy(error = "模型没有返回正文") }
        } else {
            pendingAssistantRuntime?.let { runtime -> record = record.copy(runtimeState = runtime) }
            pendingAssistantRuntime = null
            updateVariant(variantId) {
                if (it.status == PersistedMessageStatus.STREAMING) {
                    it.copy(status = PersistedMessageStatus.COMPLETE, runtimeStateAfter = record.runtimeState)
                } else {
                    it
                }
            }
            _uiState.update { it.copy(retryAvailable = false, regenerateAvailable = true) }
        }
    }

    private fun finishFailure(variantId: String, cancelled: Boolean, error: Exception?, priorSelection: Int?) {
        pendingPlanRuntime = null
        pendingAssistantRuntime = null
        val variant = record.findVariant(variantId)
        val hasPartial = variant?.message?.let { message ->
            message.content.isNotBlank() || (cancelled && (message.sourceText.isNotBlank() || message.reasoning.any { it.text.isNotBlank() }))
        } == true
        if (hasPartial) {
            updateVariant(variantId) {
                it.copy(
                    status = if (cancelled) PersistedMessageStatus.CANCELLED else PersistedMessageStatus.ERROR,
                    runtimeStateAfter = record.runtimeState,
                )
            }
        } else {
            removeVariant(variantId, priorSelection)
        }
        val userMessage = if (cancelled) "已停止生成" else error?.userMessage().orEmpty().ifBlank { "生成失败" }
        _uiState.update { it.copy(retryAvailable = record.turns.lastOrNull()?.role == MessageRole.USER, message = userMessage) }
        updateTrace { copy(error = userMessage) }
    }

    private fun selectVariant(delta: Int) {
        if (_uiState.value.readOnlyReason != null) return
        if (waitForEra { selectVariant(delta) }) return
        val lastIndex = record.turns.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastIndex < 0) return
        val turn = record.turns[lastIndex]
        val next = (turn.selectedVariantIndex + delta).coerceIn(0, turn.variants.lastIndex)
        if (next == turn.selectedVariantIndex) return
        val selected = turn.variants[next]
        val proposed = record.copy(
            turns = record.turns.mapIndexed { index, item -> if (index == lastIndex) item.copy(selectedVariantIndex = next) else item },
            runtimeState = selected.nativeHead() ?: record.runtimeState,
        )
        _uiState.update { it.copy(browserOperationRunning = true) }
        scope.launch {
            try {
                persistNow()
                withContext(NonCancellable) {
                    record = commit(proposed)
                    refreshDisplayCache()
                    syncRecord(trace = record.persistedTrace())
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(message = "候选未切换：${error.userMessage()}") }
            } finally {
                _uiState.update { it.copy(browserOperationRunning = false) }
                pendingBrowserDraft?.let { draft -> pendingBrowserDraft = null; updateInput(draft) }
            }
        }
    }

    private fun updateVariant(id: String, transform: (MessageVariant) -> MessageVariant) {
        val index = record.turns.indexOfFirst { turn -> turn.variants.any { it.id == id } }
        if (index < 0) return
        val turn = record.turns[index]
        val position = turn.variants.indexOfFirst { it.id == id }
        val variant = transform(turn.variants[position])
        if (variant === turn.variants[position]) return
        val variants = turn.variants.toMutableList().also { it[position] = variant }
        record = record.copy(turns = record.turns.toMutableList().also { it[index] = turn.copy(variants = variants) })
        syncRecord()
    }

    private fun removeVariant(id: String, priorSelection: Int?) {
        record = record.copy(
            turns = record.turns.mapNotNull { turn ->
                if (turn.variants.none { it.id == id }) return@mapNotNull turn
                val variants = turn.variants.filterNot { it.id == id }
                variants.takeIf(List<MessageVariant>::isNotEmpty)?.let {
                    turn.copy(variants = it, selectedVariantIndex = priorSelection ?: turn.selectedVariantIndex.coerceAtMost(it.lastIndex))
                }
            },
        )
        syncRecord()
    }

    private fun syncRecord(
        // 草稿归属会话：刷新只把它读回界面，只有 updateInput 才会用输入框内容改写它，
        // 否则网页刚写入的草稿会被尚未同步的旧输入框文本覆盖。
        input: String = record.draft,
        retryAvailable: Boolean = _uiState.value.retryAvailable,
        message: String? = _uiState.value.message,
        trace: GenerationTraceState? = _uiState.value.lastTrace,
    ) {
        if (deferPublication) return
        val current = _uiState.value
        if (savedEraSelection.value?.variant != record.turns.lastOrNull()?.selected?.id) savedEraSelection.value = null
        record = record.withDraft(input)
        _uiState.value = record.toUiState(
            input = record.draft,
            loadingConnections = current.loadingConnections,
            activePreset = currentPreset,
            readyConnections = current.readyConnections,
            selectedConnectionId = current.selectedConnectionId,
            retryAvailable = retryAvailable,
            message = message,
            lastTrace = trace,
            displayContents = displayCache,
            displayReasoning = displayReasoningCache,
        ).copy(readOnlyReason = current.readOnlyReason, loadingConversation = current.loadingConversation,
            worldBookSaving = current.worldBookSaving, worldBookMessage = current.worldBookMessage,
            generationPhase = current.generationPhase, editingMessage = current.editingMessage,
            browserOperationRunning = current.browserOperationRunning, browserGeneration = current.browserGeneration, storageFailed = storageFailure != null)
    }

    private fun showCompilationFailure(
        diagnostics: List<CompilationDiagnostic>,
        trace: List<CompilationTraceEntry>,
        retryAvailable: Boolean,
    ) {
        _uiState.update {
            it.copy(
                message = diagnostics.firstOrNull { item -> item.severity == DiagnosticSeverity.ERROR }?.message
                    ?: "Prompt 编排失败",
                retryAvailable = retryAvailable,
                lastTrace = GenerationTraceState(
                    compileDiagnostics = diagnostics,
                    compileTrace = trace,
                ),
            )
        }
        scope.launch { refreshDisplayCache(currentPreset) }
    }

    private fun abortBeforeStreaming(message: String) {
        _uiState.update { state ->
            state.copy(
                retryAvailable = record.turns.lastOrNull()?.role == MessageRole.USER,
                message = message,
            )
        }
        scope.launch { refreshDisplayCache(currentPreset) }
    }

    private suspend fun refreshDisplayCache(preset: PresetAsset = currentPreset) {
        val snapshot = record
        val messages = snapshot.selectedMessages()
        val modelId = _uiState.value.selectedConnection?.selectedModel.orEmpty()
        val rendered = withContext(projectionDispatcher) {
            messages.mapIndexed { index, message ->
                // 消息变量宏按当前楼层选中候选自己的变量取值；没有来源时沿用原路径。
                val messageVariables = snapshot.turns[index].selected.displayVariables()
                val content = compiler.projectDisplayText(
                    text = message.content,
                    role = message.role,
                    character = snapshot.character,
                    persona = snapshot.persona,
                    preset = preset,
                    runtimeState = snapshot.runtimeState,
                    history = messages,
                    conversationId = snapshot.id,
                    generationId = "${snapshot.id}-display",
                    modelId = modelId,
                    depth = messages.lastIndex - index,
                    sourceText = message.sourceText,
                    openingSourceIndex = snapshot.turns[index].selected.openingSourceIndex,
                    messageVariables = messageVariables,
                ) as TextExpansionResult.Success
                val reasoning = message.reasoning.map { block ->
                    (compiler.projectReasoningText(
                        text = block.text,
                        projection = RegexProjection.DISPLAY,
                        character = snapshot.character,
                        persona = snapshot.persona,
                        preset = preset,
                        runtimeState = snapshot.runtimeState,
                        history = messages,
                        conversationId = snapshot.id,
                        generationId = "${snapshot.id}-display",
                        modelId = modelId,
                        depth = messages.lastIndex - index,
                        messageVariables = messageVariables,
                    ) as TextExpansionResult.Success).text
                }
                RenderedMessage(message.id, content.text, reasoning)
            }
        }
        if (record.id != snapshot.id) return
        if (!_uiState.value.running && currentPreset.contentSha256 != preset.contentSha256) return
        rendered.forEach { message ->
            displayCache[message.id] = message.content
            displayReasoningCache[message.id] = message.reasoning
        }
        syncRecord()
    }

    private suspend fun commit(proposed: ConversationRecord, finishStream: String? = null, retry: Boolean = false): ConversationRecord = withContext(NonCancellable) { commitMutex.withLock {
        if (!retry) storageFailure?.let { throw it }
        val basisEpoch = epoch
        val before = persistedRecord?.takeIf { it.id == proposed.id }
        val candidate = proposed.copy(commitRevision = before?.commitRevision ?: proposed.commitRevision,
            draftSeq = if (before != null && (before.draft != proposed.draft || before.choiceDraft != proposed.choiceDraft ||
                before.nativeDraftOrigin != proposed.nativeDraftOrigin)) maxOf(proposed.draftSeq, before.draftSeq + 1) else proposed.draftSeq)
        try {
            val saved = conversationRepository?.save(candidate, before, finishStream = finishStream)
                ?: candidate.copy(commitRevision = candidate.commitRevision + 1)
            check(epoch == basisEpoch) { "会话在提交期间已切换" }
            persistedRecord = saved
            pendingCommit = null
            storageFailure = null
            if (record.id == saved.id && record.draftSeq > candidate.draftSeq) saved.copy(draft = record.draft,
                draftSeq = record.draftSeq, choiceDraft = record.choiceDraft, nativeDraftOrigin = record.nativeDraftOrigin) else saved
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            pendingCommit = candidate to finishStream
            storageFailure = error
            _uiState.update { it.copy(storageFailed = true, message = "对话保存失败，可重试保存：${error.userMessage()}") }
            throw error
        }
    } }

    private fun schedulePersist() {
        if (conversationRepository == null || closing || storageFailure != null) return
        persistenceDirty = true
        if (persistenceJob?.isActive == true) return
        persistenceJob = scope.launch {
            try {
                delay(STREAM_PERSIST_INTERVAL_MILLIS)
                persistenceDirty = false
                saveProgress()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                storageFailure = error
                _uiState.update { it.copy(storageFailed = true, message = "对话保存失败，可重试保存") }
                generationJob?.cancel()
            } finally {
                persistenceJob = null
                if (persistenceDirty && storageFailure == null) schedulePersist()
            }
        }
    }

    private val progressMutex = Mutex()

    private suspend fun saveProgress() = withContext(NonCancellable) { progressMutex.withLock {
        val active = journal
        if (active != null) {
            val batch = active.pending.toList()
            val through = batch.lastOrNull()?.seq ?: active.seq
            val preview = record.findVariant(active.variantId)
            conversationRepository?.appendStream(active.id, batch, active.projectedThrough, preview)
            active.persisted(through)
            conversationRepository?.saveDraft(record)
            return@withLock
        }
        val before = persistedRecord
        if (before != null && record.turns === before.turns && record.character === before.character &&
            record.runtimeState === before.runtimeState && record.worldBookState === before.worldBookState && record.persona === before.persona) {
            conversationRepository?.saveDraft(record)
        } else { record = commit(record); syncRecord() }
    } }

    private suspend fun persistNow() {
        storageFailure?.let { throw it }
        val scheduled = persistenceJob
        persistenceJob = null
        scheduled?.cancelAndJoin()
        persistenceDirty = false
        saveProgress()
    }

    fun retrySave() {
        if (storageFailure == null || closing || retryingSave) return
        retryingSave = true
        scope.launch {
            try {
                val pending = pendingCommit
                if (pending != null) {
                    record = commit(pending.first, pending.second, retry = true)
                    if (pending.second != null) journal = null
                } else {
                    storageFailure = null
                    saveProgress()
                }
                if (journal != null) {
                    saveProgress()
                    record = conversationRepository?.open(record.id) ?: recoverConversation(record, emptyList(), compiler)
                    persistedRecord = record
                    journal = null
                }
                conversationRepository?.saveDraft(record)
                syncRecord(message = "对话已保存")
            } catch (error: Exception) {
                storageFailure = error
                _uiState.update { it.copy(storageFailed = true, message = "保存仍未完成，请重试") }
            } finally { retryingSave = false }
        }
    }

    /** Repository owners can await the last save before closing the database. */
    fun close(): Job {
        val lifetime = scope.coroutineContext[Job]!!
        if (closing) return lifetime
        closing = true
        val jobs = lifetime.children.toList() + listOfNotNull(generationJob)
        jobs.forEach { it.cancel() }
        scope.launch {
            try {
                jobs.forEach { it.join() }
                if (persistedRecord != null && storageFailure == null) persistNow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("ConversationSession", "Failed to save conversation on close", error)
            } finally { scope.coroutineContext[Job]!!.cancel() }
        }
        return lifetime
    }

    private fun updateTrace(transform: GenerationTraceState.() -> GenerationTraceState) {
        _uiState.update { state -> state.lastTrace?.let { state.copy(lastTrace = it.transform()) } ?: state }
    }

    private fun fallbackRecord(
        characterAsset: CharacterAsset,
        persona: Persona,
        preset: PresetAsset,
    ): ConversationRecord {
        val snapshot = characterAsset.snapshot().copy(nativeAdaptation = null,
            browserProgram = io.github.zvensmoluya.tavernplayer.content.BrowserProgramReader.character(characterAsset))
        val timestamp = now()
        val initialRuntime = ConversationRuntimeState()
        val variants = (listOf(snapshot.firstMessage) + snapshot.alternateFirstMessages).mapIndexedNotNull { index, greeting ->
            if (greeting.isBlank()) return@mapIndexedNotNull null
            val expanded = compiler.projectAssistantText(
                text = greeting,
                projection = RegexProjection.STORAGE,
                character = snapshot,
                persona = persona,
                preset = preset,
                runtimeState = initialRuntime,
                history = emptyList(),
                conversationId = "fallback",
                generationId = "fallback-opening-$index",
                modelId = "",
            ) as TextExpansionResult.Success
            MessageVariant(
                id = idGenerator(),
                message = ConversationMessage(
                    idGenerator(),
                    MessageRole.ASSISTANT,
                    expanded.text,
                    snapshot.promptName,
                    sourceText = greeting,
                    createdAtEpochMillis = timestamp,
                ),
                presetId = preset.id,
                presetName = preset.name,
                presetContentSha256 = preset.contentSha256,
                runtimeStateBefore = initialRuntime,
                projectionRuntimeStateBefore = initialRuntime,
                runtimeStateAfter = expanded.runtimeState,
                openingSourceIndex = index,
            )
        }
        return ConversationRecord(
            id = "fallback",
            executionMode = ConversationExecutionMode.BROWSER,
            character = snapshot,
            persona = persona,
            turns = variants.takeIf(List<MessageVariant>::isNotEmpty)?.let {
                listOf(ConversationTurn(idGenerator(), MessageRole.ASSISTANT, it))
            }.orEmpty(),
            runtimeState = variants.firstOrNull()?.runtimeStateAfter ?: initialRuntime,
            createdAtEpochMillis = timestamp,
            updatedAtEpochMillis = timestamp,
        )
    }

    companion object {
        private val READY_STATUSES = setOf(CredentialStatus.READY, CredentialStatus.NOT_REQUIRED)
        private const val MAX_PROVIDER_RECLIPS = 3
        private const val PROVIDER_RECLIP_SAFETY_TOKENS = 16
        private const val STREAM_PERSIST_INTERVAL_MILLIS = 500L
    }
}

private fun ConversationRecord.selectedMessages(): List<ConversationMessage> = turns.map(ConversationTurn::selected).map(MessageVariant::message)

/**
 * 候选自己的变量来源：显式变量或该候选的最新 MVU 检查点。
 * 没有来源（MVU-less / 只有 legacy state read）时返回 null，展示投影保持旧路径。
 */
private fun MessageVariant.displayVariables(): JsonObject? =
    if (browserOwnVariables || nativeHead()?.mvuState != null) BrowserConversation.variables(this) else null

private fun ConversationRecord.findVariant(id: String): MessageVariant? = turns.asSequence()
    .flatMap { it.variants.asSequence() }
    .firstOrNull { it.id == id }

private fun ConversationRecord.toUiState(
    input: String = draft,
    loadingConnections: Boolean,
    activePreset: PresetAsset,
    readyConnections: List<StoredConnection> = emptyList(),
    selectedConnectionId: String? = null,
    retryAvailable: Boolean = false,
    message: String? = null,
    lastTrace: GenerationTraceState? = null,
    displayContents: Map<String, String> = emptyMap(),
    displayReasoning: Map<String, List<String>> = emptyMap(),
): ChatUiState = ChatUiState(
    executionMode = executionMode,
    browserSnapshot = buildJsonObject {
        BrowserConversation.snapshot(this@toUiState).forEach { (key, value) -> put(key, value) }
        put("program", Json.encodeToJsonElement(BrowserProgram.serializer(), character.browserProgram ?: BrowserProgram()))
        put("presetId", activePreset.id)
        put("presetHash", activePreset.contentSha256)
        val presetProgram = runCatching { io.github.zvensmoluya.tavernplayer.content.BrowserProgramReader.preset(activePreset.source) }
            .getOrElse { BrowserProgram(diagnostics = listOf("当前预设的脚本格式无法装载")) }
        put("presetProgram", Json.encodeToJsonElement(BrowserProgram.serializer(), presetProgram))
        val era = listOfNotNull(character.browserProgram, presetProgram).firstNotNullOfOrNull { program ->
            program.sources.firstOrNull { it.enabled && it.id !in program.blockedSourceIds &&
                it.sha256 == "a0768dd0c855dac07cc6e2da51abfdff733d8e2745dcec2f37ef392035b438ed" }
        }
        put("era146ScriptId", era?.id)
    },
    conversationId = id,
    character = character,
    worldBookState = worldBookState,
    persona = persona,
    messages = turns.map { turn ->
        val variant = turn.selected
        val presetId = variant.presetId
        val adapterId = variant.adapterId
        val model = variant.model
        ChatMessageState(
            message = variant.message,
            displayContent = displayContents[variant.message.id] ?: variant.message.content,
            displayReasoning = displayReasoning[variant.message.id] ?: variant.message.reasoning.map(ReasoningBlock::text),
            status = variant.status.toUiStatus(),
            metadata = if (presetId != null && adapterId != null && model != null) {
                AssistantGenerationMetadata(
                    presetId = presetId,
                    presetName = variant.presetName ?: variant.generationPlan?.presetName.orEmpty(),
                    presetContentSha256 = variant.presetContentSha256
                        ?: variant.generationPlan?.presetContentSha256.orEmpty(),
                    adapterId = adapterId,
                    model = model,
                    usage = GenerationUsage(variant.inputTokens, variant.outputTokens, variant.totalTokens, variant.cachedTokens, variant.reasoningTokens),
                    finishReason = variant.finishReason,
                )
            } else null,
            variantIndex = turn.selectedVariantIndex,
            variantCount = turn.variants.size,
            edited = variant.edited,
        )
    },
    input = input,
    readyConnections = readyConnections,
    selectedConnectionId = selectedConnectionId,
    activePresetId = activePreset.id,
    activePresetName = activePreset.name,
    loadingConnections = loadingConnections,
    retryAvailable = retryAvailable,
    regenerateAvailable = turns.lastOrNull()?.role == MessageRole.ASSISTANT &&
        turns.dropLast(1).lastOrNull()?.role == MessageRole.USER,
    variantNavigationAvailable = turns.lastOrNull()?.let { it.role == MessageRole.ASSISTANT && it.variants.size > 1 } == true,
    message = message,
    lastTrace = lastTrace ?: persistedTrace(),
    conversationState = runtimeState.conversationState.values,
)

private data class RenderedMessage(
    val id: String,
    val content: String,
    val reasoning: List<String>,
)

private fun ConversationRecord.persistedTrace(): GenerationTraceState? {
    val variant = turns.lastOrNull()
        ?.takeIf { it.role == MessageRole.ASSISTANT }
        ?.selected
        ?.takeIf { it.generationPlan != null }
        ?: return null
    val plan = variant.generationPlan ?: return null
    return GenerationTraceState(
        plan = plan,
        compileDiagnostics = plan.diagnostics,
        compileTrace = plan.trace,
        usage = GenerationUsage(variant.inputTokens, variant.outputTokens, variant.totalTokens, variant.cachedTokens, variant.reasoningTokens),
        finishReason = variant.finishReason,
    )
}

private fun PersistedMessageStatus.toUiStatus(): ChatMessageStatus = when (this) {
    PersistedMessageStatus.COMPLETE -> ChatMessageStatus.COMPLETE
    PersistedMessageStatus.STREAMING -> ChatMessageStatus.STREAMING
    PersistedMessageStatus.INTERRUPTED -> ChatMessageStatus.INTERRUPTED
    PersistedMessageStatus.CANCELLED -> ChatMessageStatus.CANCELLED
    PersistedMessageStatus.ERROR -> ChatMessageStatus.ERROR
}

private fun Long.toIntSafe(): Int? = takeIf { it in 1..Int.MAX_VALUE }?.toInt()

private fun Throwable.userMessage(): String = when (this) {
    is GatewayException.Authentication -> credentialFailureText(null)
    is GatewayException.AuthenticationFailure -> credentialFailureText(status)
    is GatewayException.RateLimited -> "请求过于频繁，请稍后再试"
    is GatewayException.Network -> "无法连接到模型服务"
    is GatewayException.Security -> "API 地址未获授权"
    is GatewayException.Configuration -> message ?: "当前模型无法表达这次请求"
    is GatewayException.HttpFailure ->
        io.github.zvensmoluya.tavernplayer.connections.providerHttpFailureText(status, diagnostic)
    is GatewayException.Protocol -> diagnostic
    else -> "生成失败"
}

private val EMPTY_CHARACTER = io.github.zvensmoluya.tavernplayer.content.CharacterAsset(
    id = "loading",
    name = "",
).snapshot()

internal fun ConversationRecord.promptMessages(): List<ConversationMessage> = turns.map { it.selected }.filterNot { it.browserHidden }.map { it.message }
