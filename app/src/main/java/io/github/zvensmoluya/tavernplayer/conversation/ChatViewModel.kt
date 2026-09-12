package io.github.zvensmoluya.tavernplayer.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuMessageResult
import io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuConversationRuntime
import io.github.zvensmoluya.tavernplayer.conversation.ejs.QuickJsEjsRuntime
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.tavernplayer.connections.ConnectionRepository
import io.github.zvensmoluya.tavernplayer.connections.CredentialStatus
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.PresetAsset
import io.github.zvensmoluya.tavernplayer.content.mvuProgram
import io.github.zvensmoluya.tavernplayer.content.NativeCollectionView
import io.github.zvensmoluya.tavernplayer.content.NativeFormView
import io.github.zvensmoluya.tavernplayer.content.NativeSceneView
import io.github.zvensmoluya.tavernplayer.content.NativeStatusView
import io.github.zvensmoluya.tavernplayer.content.NativeStateReader
import io.github.zvensmoluya.tavernplayer.content.PlayerStateReader
import io.github.zvensmoluya.tavernplayer.presets.ActivePresetSource
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.*
import io.github.zvensmoluya.tavernplayer.conversation.script.QuickJsNativeRuntime

enum class ChatMessageStatus { COMPLETE, STREAMING, CANCELLED, ERROR, INTERRUPTED }

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
    val nativeForms: List<NativeFormView> = emptyList(),
    val setupClosed: Boolean = false,
    val stateUnconfirmed: Boolean = false,
    val nativePanels: List<NativeMessagePanelContent> = emptyList(),
    val openingSourceIndex: Int? = null,
    val playerChoiceCommits: List<ConversationPlayerChoiceCommit> = emptyList(),
    val nativeStateAfter: NativeStateReader? = null,
    val memoriesAfter: Map<String, ConversationMemory> = emptyMap(),
)

data class NativeOpeningChoice(val sourceIndex: Int, val title: String, val selected: Boolean)

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
    val executionMode: ConversationExecutionMode = ConversationExecutionMode.LEGACY_NATIVE,
    val browserSnapshot: JsonObject = JsonObject(emptyMap()),
    val browserOperationRunning: Boolean = false,
    val browserGenerating: Boolean = false,
    val browserGeneration: JsonObject = JsonObject(emptyMap()),
    val memories: Map<String, ConversationMemory> = emptyMap(),
    val memorySaving: Boolean = false,
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
    val running: Boolean = false,
    val setupSaving: Boolean = false,
    val choiceSaving: Boolean = false,
    val nativeActionRunning: Boolean = false,
    val nativeSurfaces: List<NativeRenderedSurface> = emptyList(),
    val nativeSurfaceError: String? = null,
    val choicePreview: NativePlayerChoicePreview? = null,
    val nativeChoices: List<NativePlayerChoiceOption> = emptyList(),
    val retryAvailable: Boolean = false,
    val regenerateAvailable: Boolean = false,
    val variantNavigationAvailable: Boolean = false,
    val message: String? = null,
    val lastTrace: GenerationTraceState? = null,
    val conversationState: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    val nativeState: NativeStateReader = PlayerStateReader(conversationState),
    val nativeStatus: NativeStatusView? = null,
    val nativeScenes: List<NativeSceneView> = emptyList(),
    val nativeCollections: List<NativeCollectionView> = emptyList(),
) {
    val busy: Boolean get() = running || setupSaving || choiceSaving || memorySaving || worldBookSaving || nativeActionRunning || loadingConversation || browserOperationRunning
    val openingChoices: List<NativeOpeningChoice> get() {
        val opening = messages.singleOrNull()?.takeIf { !it.setupClosed } ?: return emptyList()
        return character.nativeAdaptation?.forms.orEmpty().mapNotNull { form ->
            form.openingIndices.firstOrNull()?.let { index ->
                NativeOpeningChoice(index, form.title, opening.openingSourceIndex in form.openingIndices)
            }
        }
    }
    val selectedConnection: StoredConnection?
        get() = readyConnections.firstOrNull { it.id == selectedConnectionId }
}

class ChatViewModel(
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
    private val adaptationRuntime: NativeAdaptationRuntime = NativeAdaptationRuntime(),
    private val mvuRuntime: MvuConversationRuntime = MvuConversationRuntime(),
    private val ejsRuntime: QuickJsEjsRuntime = QuickJsEjsRuntime(),
    private val nativeScriptRuntime: QuickJsNativeRuntime = QuickJsNativeRuntime(),
    val browserEnvironment: io.github.zvensmoluya.tavernplayer.conversation.web.BrowserEnvironment? = null,
) : ViewModel() {
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
    private var nativeActionJob: Job? = null
    private var nativeProjectionJob: Job? = null
    private var nativeProjectionRevision: String? = null
    private val nativeHostMutex = Mutex()
    private val browserHostMutex = Mutex()
    private var browserGenerationJob: Job? = null
    private var browserGenerationId: String? = null
    private var pendingBrowserDraft: String? = null
    private var generationJob: Job? = null
    private var persistenceJob: Job? = null
    private var persistenceDirty = false
    private var completedMvuResult: MvuMessageResult? = null
    private var rawAssistant = ""
    private var rawStateConfirmation: String? = null
    private val rawReasoning = mutableListOf<ReasoningBlock>()
    private var pendingPlanRuntime: ConversationRuntimeState? = null
    private var pendingAssistantRuntime: ConversationRuntimeState? = null

    init {
        if (record.character.mvuProgram != null) {
            _uiState.update { it.copy(loadingConversation = true) }
            viewModelScope.launch {
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
        viewModelScope.launch {
            repository.state.collect { gatewayState ->
                val ready = gatewayState.connections.filter { connection ->
                    connection.selectedModel.isNotBlank() && repository.credentialStatus(connection) in READY_STATUSES
                }.sortedBy { it.name.lowercase() }
                val current = _uiState.value.selectedConnectionId
                val selected = when {
                    ready.any { it.id == gatewayState.recentConnectionId } -> gatewayState.recentConnectionId
                    ready.any { it.id == current } -> current
                    else -> ready.firstOrNull()?.id
                }
                _uiState.update {
                    it.copy(readyConnections = ready, selectedConnectionId = selected, loadingConnections = false)
                }
            }
        }
        viewModelScope.launch {
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
        if (_uiState.value.running && generationJob == null) return
        if (_uiState.value.browserOperationRunning || _uiState.value.setupSaving || _uiState.value.choiceSaving || _uiState.value.memorySaving || _uiState.value.nativeActionRunning || _uiState.value.loadingConversation) return
        if (conversationRepository?.get(conversationId) == null) return
        if (generationJob != null || persistenceJob != null || persistenceDirty) {
            _uiState.update { it.copy(loadingConversation = true) }
            viewModelScope.launch {
                try {
                    generationJob?.cancelAndJoin()
                    persistNow()
                    loadSavedConversation(conversationId)
                } catch (error: Exception) {
                    _uiState.update { it.copy(message = "对话未切换：${error.userMessage()}") }
                } finally {
                    _uiState.update { it.copy(loadingConversation = false) }
                }
            }
        } else {
            loadSavedConversation(conversationId)
        }
    }

    private fun loadSavedConversation(conversationId: String) {
        val loaded = conversationRepository?.get(conversationId) ?: return
        record = loaded
        displayCache.clear()
        displayReasoningCache.clear()
        val current = _uiState.value
        _uiState.value = record.toUiState(
            loadingConnections = current.loadingConnections,
            activePreset = currentPreset,
            readyConnections = current.readyConnections,
            selectedConnectionId = current.selectedConnectionId,
            displayContents = displayCache,
            displayReasoning = displayReasoningCache,
        )
        viewModelScope.launch { refreshDisplayCache() }
    }

    fun updateInput(value: String) {
        if (_uiState.value.browserOperationRunning) {
            pendingBrowserDraft = value
            _uiState.update { it.copy(input = value) }
            return
        }
        if (_uiState.value.browserOperationRunning || _uiState.value.setupSaving || _uiState.value.choiceSaving || _uiState.value.memorySaving || _uiState.value.nativeActionRunning || _uiState.value.loadingConversation) return
        record = record.withDraft(value)
        _uiState.update { it.copy(input = value, message = null, nativeChoices = NativePlayerChoiceController().options(record)) }
        if (record.executionMode == ConversationExecutionMode.BROWSER) syncRecord(input = value)
        refreshNativeSurfaces()
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
        if (_uiState.value.busy || _uiState.value.browserGenerating) return
        val proposed = try {
            edit(record)
        } catch (error: Exception) {
            _uiState.update { it.copy(worldBookMessage = error.message ?: "世界书调整失败") }
            return
        }
        if (proposed == record) { onSaved(); return }
        _uiState.update { it.copy(worldBookSaving = true, worldBookMessage = null) }
        viewModelScope.launch {
            try {
                record = conversationRepository?.save(proposed) ?: proposed
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

    private fun refreshNativeSurfaces() {
        if (_uiState.value.running) {
            nativeProjectionJob?.cancel()
            nativeProjectionRevision = null
            _uiState.update { it.copy(nativeSurfaces = emptyList()) }
            return
        }
        val program = record.character.nativeAdaptation?.script
        val revision = if (program == null) null else record.nativeRevision()
        if (revision == nativeProjectionRevision) return
        nativeProjectionRevision = revision
        nativeProjectionJob?.cancel()
        _uiState.update { it.copy(nativeSurfaces = emptyList(), nativeSurfaceError = null) }
        if (program == null || revision == null) return
        val snapshot = record
        nativeProjectionJob = viewModelScope.launch {
            try {
                val surfaces = nativeScriptRuntime.present(program, snapshot.nativeContext(), revision)
                if (record.nativeRevision() == revision) _uiState.update { it.copy(nativeSurfaces = surfaces) }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                if (nativeProjectionRevision == revision) _uiState.update { it.copy(nativeSurfaceError = "界面计算超时") }
            } catch (_: Exception) {
                if (nativeProjectionRevision == revision) _uiState.update { it.copy(nativeSurfaceError = "原生界面计算失败，请检查适配程序") }
            }
        }
    }

    fun invokeNativeAction(invocation: NativeSurfaceInvocation) {
        if (_uiState.value.busy) return
        val program = record.character.nativeAdaptation?.script ?: return
        val rendered = _uiState.value.nativeSurfaces.singleOrNull { it.id == invocation.surfaceId } ?: return
        try { NativeOperations.authorize(record, rendered, invocation) }
        catch (error: IllegalArgumentException) { _uiState.update { it.copy(message = error.message) }; return }
        val operationId = idGenerator()
        val input = rendered.data.fields.associate { it.id to (invocation.input[it.id] ?: it.value) }
        val normalized = invocation.copy(input = input)
        val connection = _uiState.value.selectedConnection
        _uiState.update { it.copy(nativeActionRunning = true, message = null) }
        nativeActionJob = viewModelScope.launch {
            try {
                persistNow()
                currentCoroutineContext().ensureActive()
                saveNativeRecord(NativeOperations.begin(record, normalized, operationId))
                val context = record.nativeContext().let { JsonObject(it + ("draftText" to JsonPrimitive(record.draft))) }
                nativeScriptRuntime.invoke(program, normalized.action.handler, context, normalized.action.args, input) { method, value ->
                    withContext(Dispatchers.Main.immediate) {
                        nativeHostMutex.withLock {
                            currentCoroutineContext().ensureActive()
                            NativeOperations.active(record, operationId)
                            when (method) {
                                "variables.read" -> record.nativeContext().getValue("state")
                                "variables.replaceMvu" -> {
                                    val data = value as? JsonObject ?: error("MVU 数据必须为对象")
                                    val old = requireNotNull(record.runtimeState.mvuState)
                                    saveNativeRecord(NativeOperations.commit(record, operationId, record.runtimeState.copy(mvuState = old.withDirectReplacement(data))))
                                    JsonNull
                                }
                                "program.replace" -> {
                                    val data = value as? JsonObject ?: error("程序状态必须为对象")
                                    require(data.toString().length <= 65_536)
                                    saveNativeRecord(NativeOperations.commit(record, operationId, record.runtimeState.copy(scriptState = data)))
                                    JsonNull
                                }
                                "draft.replace" -> {
                                    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("草稿必须为文字")
                                    require(text.length <= 65_536)
                                    saveNativeRecord(NativeOperations.commit(record, operationId, record.runtimeState, text))
                                    JsonNull
                                }
                                "generation.text" -> {
                                    val selectedConnection = requireNotNull(connection) { "请先配置可用模型" }
                                    val request = value as? JsonObject ?: error("生成请求必须为对象")
                                    require(request.keys == setOf("prompt"))
                                    val prompt = (request["prompt"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("缺少生成提示词")
                                    require(prompt.isNotBlank() && prompt.length <= 65_536)
                                    val requestId = idGenerator()
                                    saveNativeRecord(NativeOperations.generation(record, operationId, requestId))
                                    val limits = selectedConnection.effectiveTokenLimits()
                                    val contextLimit = limits.contextTokens?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                                    val outputLimit = minOf(2048L, limits.outputTokens ?: 2048L, contextLimit?.toLong()?.minus(1) ?: Long.MAX_VALUE).toInt()
                                    require(outputLimit > 0)
                                    val plan = GenerationPlan(
                                        messages = listOf(PreparedMessage(MessageRole.USER, prompt, PromptOrigin("native-action", listOf(operationId, requestId)))),
                                        maxOutputTokens = outputLimit, declaredContextTokens = contextLimit,
                                        assistantPrefill = "", presetId = "native-auxiliary", presetName = "原生操作辅助生成",
                                        diagnostics = emptyList(), trace = emptyList(),
                                    )
                                    val budget = generator.validateTokens(selectedConnection, plan)
                                    require(contextLimit == null || (budget != null && budget.inputTokens.toLong() + outputLimit <= contextLimit)) { "辅助生成超过上下文预算" }
                                    val text = StringBuilder()
                                    var complete = false
                                    generator.stream(selectedConnection, plan).collect { event ->
                                        currentCoroutineContext().ensureActive()
                                        when (event) {
                                            is GenerationEvent.TextDelta -> { require(text.length + event.text.length <= 65_536); text.append(event.text); _uiState.update { it.copy(browserGeneration = JsonObject(it.browserGeneration + ("text" to JsonPrimitive(text.toString())))) } }
                                            is GenerationEvent.Finished -> complete = event.reason in setOf("completed", "stop", "end_turn", "STOP", "stop_sequence")
                                            else -> Unit
                                        }
                                    }
                                    currentCoroutineContext().ensureActive()
                                    NativeOperations.active(record, operationId)
                                    require(complete) { "辅助生成未完整结束" }
                                    JsonPrimitive(text.toString())
                                }
                                else -> error("未支持的宿主方法")
                            }
                        }
                    }
                }
                saveNativeRecord(NativeOperations.finish(record, operationId, NativeOperationStatus.COMPLETE))
            } catch (cancelled: CancellationException) {
                val saved = finishNativeFailure(operationId, if (cancelled is TimeoutCancellationException) NativeOperationStatus.FAILED else NativeOperationStatus.CANCELLED)
                _uiState.update { it.copy(message = if (saved) "操作已停止，已保存的变更保留" else "操作已停止，结束状态未能保存；请检查存储后重试") }
                if (cancelled !is TimeoutCancellationException) throw cancelled
            } catch (_: Exception) {
                val saved = finishNativeFailure(operationId, NativeOperationStatus.FAILED)
                _uiState.update { it.copy(message = if (saved) "操作未完成，已保存的变更保留；请检查适配和模型连接" else "操作未完成，结束状态未能保存；请检查存储后重试") }
            } finally {
                _uiState.update { it.copy(nativeActionRunning = false) }
                nativeActionJob = null
                viewModelScope.launch { refreshDisplayCache() }
            }
        }
    }

    fun cancelNativeAction() { nativeActionJob?.cancel() }

    private suspend fun saveNativeRecord(proposed: ConversationRecord) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val saved = conversationRepository?.save(proposed) ?: proposed
            record = saved
            syncRecord(input = saved.draft)
        }
    }

    private suspend fun finishNativeFailure(id: String, status: NativeOperationStatus) = withContext(NonCancellable) {
        if (record.turns.lastOrNull()?.selected?.nativeOperations?.any { it.id == id && it.status == NativeOperationStatus.RUNNING } == true) {
            val stopped = NativeOperations.finish(record, id, status)
            try { saveNativeRecord(stopped) }
            catch (_: Exception) {
                // Only the receipt changes in memory; no failed state write is published. The next
                // persistNow must save this stopped receipt before a subsequent action can begin.
                record = stopped
                persistenceDirty = true
                syncRecord()
                return@withContext false
            }
        }
        true
    }

    fun submitNativeForm(formId: String, values: Map<String, List<String>>) {
        if (_uiState.value.busy) return
        val adaptation = record.character.nativeAdaptation ?: return
        val form = adaptation.forms.firstOrNull { it.id == formId } ?: return
        if (record.turns.none { form.matchesMessage(it.selected.message.sourceText, it.selected.openingSourceIndex) }) return
        if (form.setup != null) {
            val result = NativeSetupController(adaptationRuntime).commit(record, NativeFormSubmission(formId, values))
            if (result is NativeSetupResult.Rejected) {
                _uiState.update { it.copy(message = result.message) }
                return
            }
            val prepared = (result as NativeSetupResult.Committed).record
            _uiState.update { it.copy(setupSaving = true) }
            viewModelScope.launch {
                try {
                    persistenceJob?.cancelAndJoin()
                    persistenceJob = null
                    persistenceDirty = false
                    val saved = conversationRepository?.save(prepared) ?: prepared
                    record = saved
                    syncRecord(input = saved.draft, message = "开局设定已保存，发送草稿即可开始")
                    refreshDisplayCache()
                } catch (error: Exception) {
                    _uiState.update { it.copy(message = "开局未保存：${error.userMessage()}") }
                } finally {
                    _uiState.update { it.copy(setupSaving = false) }
                }
            }
            return
        }
        when (
            val result = adaptationRuntime.submitForm(
                adaptation = adaptation,
                submission = NativeFormSubmission(formId, values),
                userName = record.persona.name,
                characterName = record.character.promptName,
            )
        ) {
            is NativeFormSubmissionResult.Rejected -> _uiState.update { it.copy(message = result.message) }
            is NativeFormSubmissionResult.Draft -> {
                syncRecord(input = result.text, message = null)
                schedulePersist()
            }
        }
    }

    fun previewPlayerChoice(choiceId: String) {
        if (_uiState.value.busy) return
        when (val prepared = NativePlayerChoiceController().prepare(record, choiceId)) {
            is NativePlayerChoicePreparation.Ready -> _uiState.update { it.copy(choicePreview = prepared.preview, message = null) }
            is NativePlayerChoicePreparation.Rejected -> _uiState.update { it.copy(message = prepared.message) }
        }
    }

    fun cancelPlayerChoice() { _uiState.update { it.copy(choicePreview = null) } }

    fun confirmPlayerChoice() {
        if (_uiState.value.busy) return
        val preview = _uiState.value.choicePreview ?: return
        val result = NativePlayerChoiceController().commit(record, preview)
        if (result is NativePlayerChoiceResult.Rejected) {
            _uiState.update { it.copy(choicePreview = null, message = result.message) }
            return
        }
        val prepared = (result as NativePlayerChoiceResult.Committed).record
        _uiState.update { it.copy(choicePreview = null, choiceSaving = true) }
        viewModelScope.launch {
            try {
                persistenceJob?.cancelAndJoin()
                persistenceJob = null
                persistenceDirty = false
                val saved = conversationRepository?.save(prepared) ?: prepared
                record = saved
                syncRecord(input = saved.draft, message = "选择已保存，草稿可修改后发送")
                refreshDisplayCache()
            } catch (error: Exception) {
                _uiState.update { it.copy(message = "选择未保存：${error.userMessage()}") }
            } finally {
                _uiState.update { it.copy(choiceSaving = false) }
            }
        }
    }

    fun send() {
        val state = _uiState.value
        val connection = state.selectedConnection
        if (state.busy || state.input.isBlank()) return
        if (connection == null) {
            _uiState.update { it.copy(message = "请先配置可用模型") }
            return
        }
        val inputText = state.input
        val capturedPreset = presetSource.captureActive()
        _uiState.update { it.copy(running = true, message = null) }
        viewModelScope.launch {
            try {
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
                syncRecord(input = "", message = null, retryAvailable = false, running = true)
                persistNow()
                generate(connection, generationId, appendAssistantTurn = true, preset = capturedPreset)
            } catch (cancelled: CancellationException) {
                abortBeforeStreaming("已停止生成")
                throw cancelled
            } catch (error: Exception) {
                abortBeforeStreaming(error.userMessage())
            }
        }
    }

    fun retry() {
        val state = _uiState.value
        val connection = state.selectedConnection ?: return
        if (state.busy || !state.retryAvailable || record.turns.lastOrNull()?.role != MessageRole.USER) return
        val capturedPreset = presetSource.captureActive()
        _uiState.update { it.copy(running = true, message = null) }
        viewModelScope.launch {
            try {
                generate(connection, idGenerator(), appendAssistantTurn = true, preset = capturedPreset)
            } catch (cancelled: CancellationException) {
                abortBeforeStreaming("已停止生成")
                throw cancelled
            } catch (error: Exception) {
                abortBeforeStreaming(error.userMessage())
            }
        }
    }

    fun regenerate() {
        val state = _uiState.value
        val connection = state.selectedConnection ?: return
        val last = record.turns.lastOrNull() ?: return
        if (state.busy || last.role != MessageRole.ASSISTANT || record.turns.dropLast(1).lastOrNull()?.role != MessageRole.USER) return
        val capturedPreset = presetSource.captureActive()
        _uiState.update { it.copy(running = true, message = null) }
        viewModelScope.launch {
            try {
                generate(connection, idGenerator(), appendAssistantTurn = false, preset = capturedPreset)
            } catch (cancelled: CancellationException) {
                abortBeforeStreaming("已停止生成")
                throw cancelled
            } catch (error: Exception) {
                abortBeforeStreaming(error.userMessage())
            }
        }
    }

    fun editMessage(messageId: String, sourceText: String, mode: MessageEditMode) {
        val state = _uiState.value
        if (state.busy || sourceText.isBlank()) return
        val turnIndex = record.turns.indexOfFirst { it.selected.message.id == messageId }
        if (turnIndex < 0) return
        val turn = record.turns[turnIndex]
        val selected = turn.selected
        if (selected.status == PersistedMessageStatus.STREAMING || turn.role == MessageRole.SYSTEM) return
        val capturedPreset = presetSource.captureActive()
        val connection = state.selectedConnection
        val shouldGenerate = mode == MessageEditMode.RESTART && turn.role == MessageRole.USER && connection != null
        _uiState.update { it.copy(running = true, message = null) }
        viewModelScope.launch {
            try {
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
                        val adaptation = record.character.nativeAdaptation
                        val projectionRuntime = selected.projectionRuntimeStateBefore
                            ?: selected.generationPlan?.runtimeState
                            ?: runtimeBefore
                        fun project(text: String) = compiler.projectAssistantOutput(
                            rawText = adaptationRuntime.projectAssistantMessage(adaptation, text).narrativeText,
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
                        var projectedRuntime = adaptation?.let {
                            adaptationRuntime.ingestAssistantMessage(adaptation, sourceText, projected.runtimeState).runtimeState
                        } ?: projected.runtimeState
                        if (mode == MessageEditMode.RESTART) {
                            applyMvuUpdate(sourceText, projectedRuntime, selected.openingSourceIndex != null)?.let { result ->
                                projected = project(result.processedText)
                                val runtime = adaptation?.let {
                                    adaptationRuntime.ingestAssistantMessage(it, sourceText, projected.runtimeState).runtimeState
                                } ?: projected.runtimeState
                                projectedRuntime = result.applyTo(runtime)
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
                    MessageRole.SYSTEM -> return@launch
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
                record = NativeMemoryController.invalidate(record, selected.id)
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
                if (record.executionMode == ConversationExecutionMode.BROWSER) {
                    try { persistNow() } catch (error: Exception) {
                        record = conversationRepository?.get(record.id) ?: record
                        throw error
                    }
                }
                syncRecord(
                    running = shouldGenerate,
                    retryAvailable = if (mode == MessageEditMode.TEXT_ONLY) {
                        state.retryAvailable
                    } else {
                        turn.role == MessageRole.USER && !shouldGenerate
                    },
                    message = notice,
                    trace = if (mode == MessageEditMode.TEXT_ONLY) state.lastTrace else null,
                )
                if (record.executionMode != ConversationExecutionMode.BROWSER) persistNow()
                refreshDisplayCache(capturedPreset)
                if (shouldGenerate) {
                    generate(checkNotNull(connection), idGenerator(), appendAssistantTurn = true, preset = capturedPreset)
                }
            } catch (cancelled: CancellationException) {
                abortBeforeStreaming("已停止生成")
                throw cancelled
            } catch (error: Exception) {
                abortBeforeStreaming(error.userMessage())
            }
        }
    }

    fun previousVariant() = selectVariant(-1)

    fun nextVariant() = selectVariant(1)

    fun selectOpening(sourceIndex: Int) {
        if (_uiState.value.busy || record.runtimeState.setupCommit != null) return
        val turn = record.turns.singleOrNull()?.takeIf { it.role == MessageRole.ASSISTANT } ?: return
        if (_uiState.value.openingChoices.none { it.sourceIndex == sourceIndex }) return
        val index = turn.variants.indexOfFirst { it.openingSourceIndex == sourceIndex }
        if (index >= 0) selectVariant(index - turn.selectedVariantIndex)
    }

    fun cancel() {
        browserGenerationJob?.cancel()
        generationJob?.cancel()
    }

    private var browserRegexRefreshJob: Job? = null

    private fun scheduleBrowserRegexRefresh() {
        browserRegexRefreshJob?.cancel()
        browserRegexRefreshJob = viewModelScope.launch {
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
        if (method == "generation.stop") {
            BrowserConversation.authorize(record, actor, BrowserConversation.revision(record))
            require(args.keys.all { it == "id" }) { "未支持的停止参数" }
            val requested = args["id"]?.jsonPrimitive?.contentOrNull
            if (requested == null || requested == browserGenerationId) browserGenerationJob?.cancel()
            if (requested == null) generationJob?.cancel()
            return buildJsonObject { put("snapshot", BrowserConversation.snapshot(record)); put("value", JsonNull) }
        }
        return browserHostMutex.withLock {
            require(!_uiState.value.busy) { "会话正在处理其他操作" }
            BrowserConversation.authorize(record, actor, revision)
            if (method == "chat.send") {
                require(args.isEmpty() && record.draft.isNotBlank()) { "发送需要非空草稿" }
                require(_uiState.value.selectedConnection != null) { "请先配置模型" }
                send()
                return@withLock buildJsonObject { put("snapshot", BrowserConversation.snapshot(record)); put("value", JsonNull) }
            }
            _uiState.update { it.copy(browserOperationRunning = true) }
            var result: JsonElement = JsonNull
            var regexChanged = false
            try {
                try { persistNow() } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    record = conversationRepository?.get(record.id) ?: record
                    throw BrowserPersistenceException(error)
                }
                BrowserConversation.authorize(record, actor, revision)
                when (method) {
                    "resources.reprepare" -> {
                        require(actor.id == "native-resource-preparation" && args.isEmpty()) { "资源更新只能从原生界面操作" }
                        result = JsonPrimitive(requireNotNull(browserEnvironment).resources.reprepare(record.id, record.character.sourceSha256))
                    }
                    "worldbook.entries.read" -> result = BrowserConversation.readWorldBookEntries(record, args)
                    "generation.generate", "generation.raw" -> {
                        browserGenerationJob = currentCoroutineContext()[Job]
                        browserGenerationId = args["generation_id"]?.jsonPrimitive?.contentOrNull ?: idGenerator()
                        _uiState.update { it.copy(browserGenerating = true, browserGeneration = buildJsonObject { put("id", browserGenerationId); put("text", ""); put("stream", args["should_stream"]?.jsonPrimitive?.booleanOrNull == true); put("status", "running") }) }
                        result = JsonPrimitive(generateBrowserText(method, args))
                        _uiState.update { it.copy(browserGeneration = JsonObject(it.browserGeneration + ("status" to JsonPrimitive("complete")))) }
                    }
                    else -> {
                        val proposed = BrowserConversation.apply(record, actor, method, args)
                        regexChanged = method == "regex.replace" && proposed.character.regexScripts != record.character.regexScripts
                        withContext(NonCancellable) {
                            record = try { conversationRepository?.save(proposed) ?: proposed }
                            catch (error: Exception) { throw BrowserPersistenceException(error) }
                            if (method != "regex.replace" && (!method.startsWith("messages.") || args["refresh"]?.jsonPrimitive?.content != "none")) refreshDisplayCache()
                            if (regexChanged) scheduleBrowserRegexRefresh()
                            syncRecord(input = record.draft)
                        }
                    }
                }
            } finally {
                browserGenerationJob = null; browserGenerationId = null
                _uiState.update { it.copy(browserOperationRunning = false, browserGenerating = false) }
                syncRecord(input = record.draft)
                pendingBrowserDraft?.let { draft -> pendingBrowserDraft = null; updateInput(draft) }
            }
            buildJsonObject {
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
        }
    }

    private suspend fun generateBrowserText(method: String, args: JsonObject): String {
        val allowed = if (method == "generation.raw") setOf("ordered_prompts", "should_stream", "generation_id")
            else setOf("user_input", "should_stream", "generation_id", "max_chat_history")
        require(args.keys.all { it in allowed }) { "生成参数超出 player-web-1 范围" }
        args["should_stream"]?.let { require(it.jsonPrimitive.booleanOrNull != null) { "should_stream 必须为布尔值" } }
        val connection = requireNotNull(_uiState.value.selectedConnection) { "请先配置模型" }
        val preset = presetSource.captureActive()
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
            val compiled = ejsRuntime.compile(compiler, NormalGenerationInput(
                character = record.character, persona = record.persona, history = history, preset = preset,
                worldBookState = record.worldBookState,
                runtimeState = record.runtimeState, conversationId = record.id, generationId = browserGenerationId.orEmpty(),
                modelId = connection.selectedModel, modelContextTokens = limits.contextTokens?.toIntSafe(),
                modelOutputTokens = limits.outputTokens?.toIntSafe(),
            ), mutableMapOf())
            when (compiled) {
                is CompilationResult.Success -> compiled.plan
                is CompilationResult.Failure -> error(compiled.diagnostics.firstOrNull { it.severity == DiagnosticSeverity.ERROR }?.message ?: "辅助生成编排失败")
            }
        }
        val text = StringBuilder()
        var finished = false
        val budget = generator.validateTokens(connection, plan)
        val declaredContext = plan.declaredContextTokens
        require(declaredContext == null || (budget != null && budget.inputTokens.toLong() + plan.maxOutputTokens <= declaredContext)) { "辅助生成超过上下文预算" }
        val prepared = BrowserConversation.withRuntime(record, plan.runtimeState)
        withContext(NonCancellable) {
            record = try { conversationRepository?.save(prepared) ?: prepared }
            catch (error: Exception) { throw BrowserPersistenceException(error) }
            syncRecord()
        }
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
        _uiState.update { it.copy(selectedConnectionId = connectionId, message = null) }
        viewModelScope.launch {
            runCatching { repository.activate(connectionId) }
                .onFailure { error -> _uiState.update { it.copy(message = error.userMessage()) } }
        }
    }

    fun resetConversation() {
        if (_uiState.value.running && generationJob == null) return
        if (_uiState.value.browserOperationRunning || _uiState.value.setupSaving || _uiState.value.choiceSaving || _uiState.value.memorySaving || _uiState.value.nativeActionRunning || _uiState.value.loadingConversation) return
        _uiState.update { it.copy(loadingConversation = true) }
        viewModelScope.launch {
            try {
                generationJob?.cancelAndJoin()
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
                        nativeAdaptation = record.character.nativeAdaptation,
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
        val lastVisibleTurn = record.turns.lastOrNull()
        val baseInput = NormalGenerationInput(
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
        )
        var localInputLimit: Int? = null
        val validationDiagnostics = mutableListOf<CompilationDiagnostic>()
        val validationTrace = mutableListOf<CompilationTraceEntry>()
        var plan: GenerationPlan? = null
        val ejsCache = mutableMapOf<EjsTemplateRequest, String>()
        for (attempt in 0..MAX_PROVIDER_RECLIPS) {
            val compilation = ejsRuntime.compile(compiler, baseInput.copy(maxInputTokens = localInputLimit), ejsCache)
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
                generator.validateTokens(connection, candidate)
            } catch (error: Exception) {
                val diagnostic = CompilationDiagnostic(
                    DiagnosticSeverity.ERROR,
                    "PROVIDER_TOKEN_VALIDATION_FAILED",
                    error.userMessage(),
                )
                showCompilationFailure(candidate.diagnostics + validationDiagnostics + diagnostic, candidate.trace + validationTrace, appendAssistantTurn)
                return
            }
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
            running = true,
            retryAvailable = false,
            message = null,
            trace = GenerationTraceState(
                plan = finalPlan,
                compileDiagnostics = finalPlan.diagnostics,
                compileTrace = finalPlan.trace,
            ),
        )
        persistNow()

        generationJob = viewModelScope.launch {
            var replyCompleted = false
            try {
                generator.stream(connection, finalPlan).collect { event ->
                    applyEvent(
                        variant.id,
                        generationId,
                        connection,
                        preset,
                        evaluationInstant,
                        evaluationZoneId,
                        event,
                    )
                    replyCompleted = record.findVariant(variant.id)?.status == PersistedMessageStatus.COMPLETE
                }
                if (record.character.mvuProgram != null && record.findVariant(variant.id)?.status == PersistedMessageStatus.STREAMING) {
                    error("回复未完整结束，MVU 变量未更新")
                }
                reprojectAssistantOutput(variant.id, generationId, connection, preset, evaluationInstant, evaluationZoneId, streaming = false)
                finishIfStreamEnded(variant.id)
                replyCompleted = record.findVariant(variant.id)?.status == PersistedMessageStatus.COMPLETE
                if (replyCompleted && record.character.nativeAdaptation?.memories.orEmpty().isNotEmpty()) {
                    persistNow()
                    updateMemories(connection)
                }
            } catch (cancelled: CancellationException) {
                if (!replyCompleted) {
                    completedMvuResult = null
                    withContext(NonCancellable) {
                        reprojectAssistantOutput(variant.id, generationId, connection, preset, evaluationInstant, evaluationZoneId, streaming = false)
                    }
                    finishFailure(variant.id, cancelled = true, error = null)
                } else {
                    _uiState.update { it.copy(message = "已停止记忆更新，正文已保留") }
                }
                throw cancelled
            } catch (error: Exception) {
                if (!replyCompleted) {
                    completedMvuResult = null
                    reprojectAssistantOutput(variant.id, generationId, connection, preset, evaluationInstant, evaluationZoneId, streaming = false)
                    finishFailure(variant.id, cancelled = false, error = error)
                } else _uiState.update { it.copy(message = "正文后的保存或记忆更新失败：${error.userMessage()}") }
            } finally {
                withContext(NonCancellable) {
                    try {
                        persistNow()
                        refreshDisplayCache(currentPreset)
                    } catch (error: Exception) {
                        val message = "对话保存失败：${error.userMessage()}"
                        _uiState.update { it.copy(message = message) }
                        updateTrace { copy(error = message) }
                    } finally {
                        generationJob = null
                        _uiState.update { it.copy(running = false) }
                        refreshNativeSurfaces()
                    }
                }
            }
        }
    }

    fun refreshMemories() {
        if (_uiState.value.busy) return
        val connection = _uiState.value.selectedConnection ?: return
        _uiState.update { it.copy(running = true) }
        generationJob = viewModelScope.launch {
            try {
                persistNow()
                updateMemories(connection, force = true)
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(message = "已停止记忆更新") }
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(message = "记忆更新失败：${error.userMessage()}") }
            } finally {
                withContext(NonCancellable) {
                    try { persistNow() } catch (_: Exception) { _uiState.update { it.copy(message = "对话保存失败") } }
                }
                generationJob = null
                _uiState.update { it.copy(running = false) }
                refreshNativeSurfaces()
            }
        }
    }

    private suspend fun updateMemories(connection: StoredConnection, force: Boolean = false) {
        val adaptation = record.character.nativeAdaptation ?: return
        if (adaptation.memories.isEmpty()) return
        for (definition in adaptation.memories) {
            currentCoroutineContext().ensureActive()
            try {
                val request = NativeMemoryController.prepare(record, definition.id, force) ?: continue
                _uiState.update { it.copy(message = "正在更新记忆：${definition.title}") }
                val validation = generator.validateTokens(connection, request.plan)
                val limit = request.plan.declaredContextTokens
                require(validation == null || limit == null || validation.inputTokens.toLong() + request.plan.maxOutputTokens <= limit) {
                    "记忆上下文超限"
                }
                val output = StringBuilder()
                var finished = false
                var usage: GenerationUsage? = null
                generator.stream(connection, request.plan).collect { event -> when (event) {
                    is GenerationEvent.TextDelta -> {
                        require(output.length.toLong() + event.text.length <= NativeMemoryController.MAX_CONTENT_CHARS)
                        output.append(event.text)
                    }
                    is GenerationEvent.Usage -> usage = event.value
                    is GenerationEvent.Finished -> finished = event.reason in setOf("completed", "stop", "end_turn", "STOP")
                    else -> Unit
                } }
                check(finished) { "记忆分析没有完整结束" }
                _uiState.update { it.copy(memorySaving = true) }
                try {
                    val scheduled = persistenceJob
                    persistenceJob = null
                    scheduled?.cancelAndJoin()
                    withContext(NonCancellable) {
                        val proposed = NativeMemoryController.commit(request, record, output.toString(), connection.selectedModel,
                            usage?.inputTokens, usage?.outputTokens)
                        record = conversationRepository?.save(proposed) ?: proposed
                        persistenceDirty = false
                        syncRecord(running = true, message = "记忆已更新：${definition.title}", trace = _uiState.value.lastTrace)
                    }
                } finally { _uiState.update { it.copy(memorySaving = false) } }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                val reason = if (error is IllegalArgumentException) "对话已变化、资料无效或上下文超限" else error.userMessage()
                _uiState.update { it.copy(message = "记忆更新失败（${definition.title}）：$reason；原记忆保留，可在详情中重试") }
                updateTrace { copy(streamDiagnostics = (streamDiagnostics + "记忆 ${definition.id} 更新失败：$reason").takeLast(30)) }
                return
            }
        }
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
                rawAssistant += event.text
                reprojectAssistantOutput(variantId, generationId, connection, preset, evaluationInstant, evaluationZoneId)
                schedulePersist()
            }
            GenerationEvent.ReasoningStarted -> {
                if (rawReasoning.lastOrNull()?.let { it.text.isEmpty() && it.signature == null } != true) {
                    rawReasoning += ReasoningBlock()
                    updateVariant(variantId) { variant ->
                        variant.copy(message = variant.message.copy(reasoning = variant.message.reasoning + ReasoningBlock()))
                    }
                }
                schedulePersist()
            }
            is GenerationEvent.ReasoningDelta -> {
                if (rawReasoning.isEmpty()) rawReasoning += ReasoningBlock()
                val lastIndex = rawReasoning.lastIndex
                rawReasoning[lastIndex] = rawReasoning[lastIndex].copy(text = rawReasoning[lastIndex].text + event.text)
                reprojectAssistantOutput(variantId, generationId, connection, preset, evaluationInstant, evaluationZoneId)
                schedulePersist()
            }
            is GenerationEvent.ReasoningSignature -> {
                if (rawReasoning.isEmpty()) rawReasoning += ReasoningBlock()
                val current = rawReasoning.last()
                if (current.signature == null) {
                    rawReasoning[rawReasoning.lastIndex] = current.copy(signature = event.signature)
                } else {
                    rawReasoning += ReasoningBlock(signature = event.signature)
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
                updateVariant(variantId) { it.copy(inputTokens = event.value.inputTokens, outputTokens = event.value.outputTokens) }
                updateTrace { copy(usage = event.value) }
            }
            is GenerationEvent.AssistantStateConfirmed -> {
                rawStateConfirmation = event.envelope
                reprojectAssistantOutput(variantId, generationId, connection, preset, evaluationInstant, evaluationZoneId)
                schedulePersist()
            }
            is GenerationEvent.Finished -> {
                if (record.findVariant(variantId)?.status == PersistedMessageStatus.COMPLETE) return
                if (record.character.mvuProgram != null && event.reason !in setOf("completed", "stop", "end_turn", "STOP", "stop_sequence")) {
                    error("回复被截断或未正常结束，MVU 变量未更新")
                }
                reprojectAssistantOutput(variantId, generationId, connection, preset, evaluationInstant, evaluationZoneId, streaming = false)
                completedMvuResult = applyMvuUpdate(rawAssistant, pendingAssistantRuntime ?: record.runtimeState)
                if (completedMvuResult != null) {
                    reprojectAssistantOutput(variantId, generationId, connection, preset, evaluationInstant, evaluationZoneId, streaming = false)
                }
                pendingAssistantRuntime?.let { runtime -> record = record.copy(runtimeState = runtime) }
                pendingAssistantRuntime = null
                updateVariant(variantId) {
                    it.copy(
                        status = PersistedMessageStatus.COMPLETE,
                        finishReason = event.reason,
                        runtimeStateAfter = record.runtimeState,
                    )
                }
                updateTrace { copy(finishReason = event.reason) }
            }
            is GenerationEvent.Diagnostic -> updateTrace {
                copy(streamDiagnostics = (streamDiagnostics + event.summary).takeLast(30))
            }
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
        record = record.copy(runtimeState = runtime)
        persistNow()
        syncRecord()
    }

    private suspend fun reprojectAssistantOutput(
        variantId: String,
        generationId: String,
        connection: StoredConnection,
        preset: PresetAsset,
        evaluationInstant: Instant,
        evaluationZoneId: ZoneId,
        streaming: Boolean = true,
    ) {
        val history = record.copy(turns = record.turns.dropLast(1)).promptMessages()
        val adaptation = record.character.nativeAdaptation
        val projectionRuntime = record.findVariant(variantId)?.projectionRuntimeStateBefore ?: record.runtimeState
        val narrativeSource = adaptationRuntime.projectAssistantMessage(
            adaptation = adaptation,
            sourceText = completedMvuResult?.processedText ?: rawAssistant,
            stateConfirmedSeparately = rawStateConfirmation != null,
            streaming = streaming,
        ).narrativeText
        val projection = withContext(projectionDispatcher) {
            compiler.projectAssistantOutput(
                rawText = narrativeSource,
                rawReasoning = rawReasoning.map(ReasoningBlock::text),
                character = record.character,
                persona = record.persona,
                preset = preset,
                runtimeState = projectionRuntime,
                history = history,
                conversationId = record.id,
                generationId = generationId,
                modelId = connection.selectedModel,
                evaluationInstant = evaluationInstant,
                evaluationZoneId = evaluationZoneId,
            )
        }
        val projectedRuntime = adaptation?.let {
            val stateSource = rawStateConfirmation ?: rawAssistant
            adaptationRuntime.ingestAssistantMessage(adaptation, stateSource, projection.runtimeState).runtimeState
        } ?: projection.runtimeState
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
                ReasoningBlock(text = text, signature = rawReasoning.getOrNull(index)?.signature)
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
                    sourceText = rawAssistant,
                    stateConfirmation = rawStateConfirmation,
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

    private fun finishIfStreamEnded(variantId: String) {
        pendingPlanRuntime = null
        val variant = record.findVariant(variantId) ?: return
        if (variant.message.content.isBlank()) {
            variant.generationPlan?.runtimeState?.let { promptRuntime ->
                record = record.copy(runtimeState = promptRuntime)
            }
            pendingAssistantRuntime = null
            removeVariant(variantId)
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

    private fun finishFailure(variantId: String, cancelled: Boolean, error: Exception?) {
        pendingPlanRuntime = null
        pendingAssistantRuntime = null
        val variant = record.findVariant(variantId)
        val hasPartial = variant?.message?.content?.isNotBlank() == true
        if (hasPartial) {
            updateVariant(variantId) {
                it.copy(
                    status = if (cancelled) PersistedMessageStatus.CANCELLED else PersistedMessageStatus.ERROR,
                    runtimeStateAfter = record.runtimeState,
                )
            }
        } else {
            removeVariant(variantId)
        }
        val userMessage = if (cancelled) "已停止生成" else error?.userMessage().orEmpty().ifBlank { "生成失败" }
        _uiState.update { it.copy(retryAvailable = !hasPartial, message = userMessage) }
        updateTrace { copy(error = userMessage) }
    }

    private fun selectVariant(delta: Int) {
        if (_uiState.value.busy) return
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
        if (record.executionMode == ConversationExecutionMode.BROWSER) {
            _uiState.update { it.copy(browserOperationRunning = true) }
            viewModelScope.launch {
                try {
                    persistNow()
                    withContext(NonCancellable) {
                        record = conversationRepository?.save(proposed) ?: proposed
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
            return
        }
        record = proposed
        syncRecord(trace = record.persistedTrace())
        schedulePersist()
        viewModelScope.launch { refreshDisplayCache() }
    }

    private fun updateVariant(id: String, transform: (MessageVariant) -> MessageVariant) {
        record = record.copy(
            turns = record.turns.map { turn ->
                turn.copy(variants = turn.variants.map { if (it.id == id) transform(it) else it })
            },
        )
        syncRecord()
    }

    private fun removeVariant(id: String) {
        record = record.copy(
            turns = record.turns.mapNotNull { turn ->
                val variants = turn.variants.filterNot { it.id == id }
                variants.takeIf(List<MessageVariant>::isNotEmpty)?.let {
                    turn.copy(variants = it, selectedVariantIndex = turn.selectedVariantIndex.coerceAtMost(it.lastIndex))
                }
            },
        )
        syncRecord()
    }

    private fun syncRecord(
        // 草稿归属会话：刷新只把它读回界面，只有 updateInput 才会用输入框内容改写它，
        // 否则网页刚写入的草稿会被尚未同步的旧输入框文本覆盖。
        input: String = record.draft,
        running: Boolean = _uiState.value.running,
        retryAvailable: Boolean = _uiState.value.retryAvailable,
        message: String? = _uiState.value.message,
        trace: GenerationTraceState? = _uiState.value.lastTrace,
    ) {
        val current = _uiState.value
        record = record.withDraft(input).reconcileChoiceDraft()
        _uiState.value = record.toUiState(
            input = record.draft,
            loadingConnections = current.loadingConnections,
            activePreset = currentPreset,
            readyConnections = current.readyConnections,
            selectedConnectionId = current.selectedConnectionId,
            running = running,
            setupSaving = current.setupSaving,
            retryAvailable = retryAvailable,
            message = message,
            lastTrace = trace,
            displayContents = displayCache,
            displayReasoning = displayReasoningCache,
        ).copy(loadingConversation = current.loadingConversation, choicePreview = current.choicePreview, choiceSaving = current.choiceSaving,
            memorySaving = current.memorySaving, nativeActionRunning = current.nativeActionRunning,
            worldBookSaving = current.worldBookSaving, worldBookMessage = current.worldBookMessage,
            nativeSurfaces = current.nativeSurfaces, nativeSurfaceError = current.nativeSurfaceError,
            browserOperationRunning = current.browserOperationRunning, browserGenerating = current.browserGenerating, browserGeneration = current.browserGeneration)
        refreshNativeSurfaces()
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
                running = false,
                retryAvailable = retryAvailable,
                lastTrace = GenerationTraceState(
                    compileDiagnostics = diagnostics,
                    compileTrace = trace,
                ),
            )
        }
        viewModelScope.launch { refreshDisplayCache(currentPreset) }
    }

    private fun abortBeforeStreaming(message: String) {
        _uiState.update { state ->
            state.copy(
                running = false,
                retryAvailable = record.turns.lastOrNull()?.role == MessageRole.USER,
                message = message,
            )
        }
        viewModelScope.launch { refreshDisplayCache(currentPreset) }
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

    private fun schedulePersist() {
        if (conversationRepository == null) return
        persistenceDirty = true
        if (persistenceJob?.isActive == true) return
        persistenceJob = viewModelScope.launch {
            delay(STREAM_PERSIST_INTERVAL_MILLIS)
            val snapshot = record
            persistenceDirty = false
            val saved = conversationRepository.save(snapshot)
            if (record == snapshot) {
                record = saved
            } else {
                persistenceDirty = true
            }
            persistenceJob = null
            if (persistenceDirty) schedulePersist()
        }
    }

    private suspend fun persistNow() {
        val scheduled = persistenceJob
        persistenceJob = null
        scheduled?.cancelAndJoin()
        persistenceDirty = false
        conversationRepository?.let { repository ->
            val snapshot = record
            val saved = repository.save(snapshot)
            if (record == snapshot) record = saved
        }
    }

    private fun updateTrace(transform: GenerationTraceState.() -> GenerationTraceState) {
        _uiState.update { state -> state.lastTrace?.let { state.copy(lastTrace = it.transform()) } ?: state }
    }

    private fun fallbackRecord(
        characterAsset: CharacterAsset,
        persona: Persona,
        preset: PresetAsset,
    ): ConversationRecord {
        val snapshot = characterAsset.snapshot()
        val timestamp = now()
        val initialRuntime = adaptationRuntime.initialState(snapshot.nativeAdaptation)
        val variants = (listOf(snapshot.firstMessage) + snapshot.alternateFirstMessages).mapIndexedNotNull { index, greeting ->
            if (greeting.isBlank()) return@mapIndexedNotNull null
            val narrativeSource = adaptationRuntime.projectAssistantMessage(snapshot.nativeAdaptation, greeting).narrativeText
            val expanded = compiler.projectAssistantText(
                text = narrativeSource,
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
            val expandedRuntime = snapshot.nativeAdaptation?.let { adaptation ->
                adaptationRuntime.ingestAssistantMessage(adaptation, greeting, expanded.runtimeState).runtimeState
            } ?: expanded.runtimeState
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
                runtimeStateAfter = expandedRuntime,
                openingSourceIndex = index,
            )
        }
        return ConversationRecord(
            id = "fallback",
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

    class Factory(
        private val repository: ConnectionRepository,
        private val compiler: PromptCompiler,
        private val generator: ConversationGenerator,
        private val conversationRepository: ConversationRepository? = null,
        private val presetSource: ActivePresetSource,
        private val mvuRuntime: MvuConversationRuntime = MvuConversationRuntime(),
        private val ejsRuntime: QuickJsEjsRuntime = QuickJsEjsRuntime(),
        private val browserEnvironment: io.github.zvensmoluya.tavernplayer.conversation.web.BrowserEnvironment? = null,
    private val nativeScriptRuntime: QuickJsNativeRuntime = QuickJsNativeRuntime(),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(repository, compiler, generator, conversationRepository, presetSource, mvuRuntime = mvuRuntime, ejsRuntime = ejsRuntime, browserEnvironment = browserEnvironment) as T
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
    running: Boolean = false,
    setupSaving: Boolean = false,
    retryAvailable: Boolean = false,
    message: String? = null,
    lastTrace: GenerationTraceState? = null,
    displayContents: Map<String, String> = emptyMap(),
    displayReasoning: Map<String, List<String>> = emptyMap(),
): ChatUiState = ChatUiState(
    executionMode = executionMode,
    browserSnapshot = if (executionMode == ConversationExecutionMode.BROWSER) buildJsonObject {
        BrowserConversation.snapshot(this@toUiState).forEach { (key, value) -> put(key, value) }
        put("program", Json.encodeToJsonElement(io.github.zvensmoluya.tavernplayer.content.BrowserProgram.serializer(), character.browserProgram ?: io.github.zvensmoluya.tavernplayer.content.BrowserProgram()))
        put("presetId", activePreset.id)
        put("presetHash", activePreset.contentSha256)
        val presetProgram = runCatching { io.github.zvensmoluya.tavernplayer.content.BrowserProgramReader.preset(activePreset.source) }
            .getOrElse { io.github.zvensmoluya.tavernplayer.content.BrowserProgram(diagnostics = listOf("当前预设的脚本格式无法装载")) }
        put("presetProgram", Json.encodeToJsonElement(io.github.zvensmoluya.tavernplayer.content.BrowserProgram.serializer(), presetProgram))
    } else JsonObject(emptyMap()),
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
                    usage = GenerationUsage(variant.inputTokens, variant.outputTokens),
                    finishReason = variant.finishReason,
                )
            } else null,
            variantIndex = turn.selectedVariantIndex,
            variantCount = turn.variants.size,
            edited = variant.edited,
            nativeForms = character.nativeAdaptation?.forms.orEmpty().filter { form ->
                form.matchesMessage(variant.message.sourceText, variant.openingSourceIndex)
            },
            setupClosed = runtimeState.setupCommit != null || turns.any { it.role == MessageRole.USER },
            nativePanels = NativeMessagePanels.project(character.nativeAdaptation, variant.message.sourceText).panels,
            openingSourceIndex = variant.openingSourceIndex,
            playerChoiceCommits = variant.playerChoiceCommits,
            nativeStateAfter = if (turn.role == MessageRole.ASSISTANT && variant.status != PersistedMessageStatus.STREAMING &&
                character.nativeAdaptation?.let { it.status != null || it.collections.isNotEmpty() || it.scenes.isNotEmpty() } == true)
                variant.runtimeStateAfter?.let { ConversationStateReader(character.nativeAdaptation, it) } else null,
            memoriesAfter = variant.runtimeStateAfter?.memories.orEmpty(),
            stateUnconfirmed = variant.generationPlan != null && variant.status == PersistedMessageStatus.COMPLETE &&
                character.nativeAdaptation?.assistantStateAdapters.orEmpty().isNotEmpty() &&
                NativeAdaptationRuntime().projectAssistantMessage(
                    character.nativeAdaptation, variant.message.stateConfirmation ?: variant.message.sourceText,
                ).envelopeStatus != AssistantStateEnvelopeStatus.STRIPPED,
        )
    },
    input = input,
    readyConnections = readyConnections,
    selectedConnectionId = selectedConnectionId,
    activePresetId = activePreset.id,
    activePresetName = activePreset.name,
    loadingConnections = loadingConnections,
    running = running,
    setupSaving = setupSaving,
    retryAvailable = retryAvailable,
    regenerateAvailable = turns.lastOrNull()?.role == MessageRole.ASSISTANT &&
        turns.dropLast(1).lastOrNull()?.role == MessageRole.USER,
    variantNavigationAvailable = turns.lastOrNull()?.let { it.role == MessageRole.ASSISTANT && it.variants.size > 1 } == true,
    message = message,
    lastTrace = lastTrace ?: persistedTrace(),
    conversationState = runtimeState.conversationState.values,
    nativeState = ConversationStateReader(character.nativeAdaptation, runtimeState),
    memories = runtimeState.memories,
    nativeStatus = character.nativeAdaptation?.status,
    nativeScenes = character.nativeAdaptation?.scenes.orEmpty(),
    nativeCollections = character.nativeAdaptation?.collections.orEmpty(),
    nativeChoices = NativePlayerChoiceController().options(this),
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
        usage = GenerationUsage(variant.inputTokens, variant.outputTokens),
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
    is GatewayException.Authentication,
    is GatewayException.AuthenticationFailure,
    -> "API Key 不可用"
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

private fun ConversationRecord.promptMessages(): List<ConversationMessage> = turns.map { it.selected }.filterNot { executionMode == ConversationExecutionMode.BROWSER && it.browserHidden }.map { it.message }
