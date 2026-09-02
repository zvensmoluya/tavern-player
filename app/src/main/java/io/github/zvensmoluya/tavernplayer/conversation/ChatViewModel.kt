package io.github.zvensmoluya.tavernplayer.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.tavernplayer.connections.ConnectionRepository
import io.github.zvensmoluya.tavernplayer.connections.CredentialStatus
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.PresetAsset
import io.github.zvensmoluya.tavernplayer.content.AdaptationView
import io.github.zvensmoluya.tavernplayer.content.AdaptationViewPlacement
import io.github.zvensmoluya.tavernplayer.presets.ActivePresetSource
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val adaptationViews: List<AdaptationView> = emptyList(),
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
    val conversationId: String? = null,
    val character: CharacterSnapshot = EMPTY_CHARACTER,
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
    val retryAvailable: Boolean = false,
    val regenerateAvailable: Boolean = false,
    val variantNavigationAvailable: Boolean = false,
    val message: String? = null,
    val lastTrace: GenerationTraceState? = null,
    val adaptationState: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    val headerAdaptationViews: List<AdaptationView> = emptyList(),
) {
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
    private val adaptationRuntime: AdaptationRuntime = AdaptationRuntime(),
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
    private var generationJob: Job? = null
    private var persistenceJob: Job? = null
    private var persistenceDirty = false
    private var rawAssistant = ""
    private val rawReasoning = mutableListOf<ReasoningBlock>()
    private var pendingPlanRuntime: ConversationRuntimeState? = null
    private var pendingAssistantRuntime: ConversationRuntimeState? = null

    init {
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
        val loaded = conversationRepository?.get(conversationId) ?: return
        generationJob?.cancel()
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
        _uiState.update { it.copy(input = value, message = null) }
    }

    fun submitAdaptation(viewId: String, values: Map<String, List<String>>) {
        if (_uiState.value.running) return
        val artifact = record.character.adaptation ?: return
        when (
            val result = adaptationRuntime.execute(
                artifact = artifact,
                submission = AdaptationFormSubmission(viewId, values),
                runtimeState = record.runtimeState,
                userName = record.persona.name,
                characterName = record.character.promptName,
            )
        ) {
            is AdaptationExecutionResult.Failure -> _uiState.update { it.copy(message = result.message) }
            is AdaptationExecutionResult.Success -> {
                record = record.copy(runtimeState = result.runtimeState)
                val draft = result.effects.lastOrNull { it.type == AdaptationEffectType.CHAT_SET_DRAFT }?.value
                syncRecord(input = draft ?: _uiState.value.input, message = null)
                schedulePersist()
            }
        }
    }

    fun send() {
        val state = _uiState.value
        val connection = state.selectedConnection
        if (state.running || state.input.isBlank()) return
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
                val historyBefore = record.selectedMessages()
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
        if (state.running || !state.retryAvailable || record.turns.lastOrNull()?.role != MessageRole.USER) return
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
        if (state.running || last.role != MessageRole.ASSISTANT || record.turns.dropLast(1).lastOrNull()?.role != MessageRole.USER) return
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
        if (state.running || sourceText.isBlank()) return
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
                    ?: record.turns.getOrNull(turnIndex - 1)?.selected?.runtimeStateAfter
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
                            )
                        }
                    }
                    MessageRole.ASSISTANT -> {
                        val projectionRuntime = selected.projectionRuntimeStateBefore
                            ?: selected.generationPlan?.runtimeState
                            ?: runtimeBefore
                        val projected = compiler.projectAssistantOutput(
                            rawText = sourceText,
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
                        val projectedRuntime = record.character.adaptation?.let { adaptation ->
                            adaptationRuntime.ingestAssistantMessage(adaptation, sourceText, projected.runtimeState).runtimeState
                        } ?: projected.runtimeState
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
                persistNow()
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

    fun cancel() {
        generationJob?.cancel()
    }

    fun selectConnection(connectionId: String) {
        val state = _uiState.value
        if (state.running || state.readyConnections.none { it.id == connectionId }) return
        _uiState.update { it.copy(selectedConnectionId = connectionId, message = null) }
        viewModelScope.launch {
            runCatching { repository.activate(connectionId) }
                .onFailure { error -> _uiState.update { it.copy(message = error.userMessage()) } }
        }
    }

    fun resetConversation() {
        generationJob?.cancel()
        val previous = record
        record = fallbackRecord(
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
                adaptation = record.character.adaptation,
            ),
            record.persona,
            presetSource.captureActive(),
        ).copy(id = previous.id, createdAtEpochMillis = previous.createdAtEpochMillis)
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
        )
        viewModelScope.launch {
            persistNow()
            refreshDisplayCache()
        }
    }

    private suspend fun generate(
        connection: StoredConnection,
        generationId: String,
        appendAssistantTurn: Boolean,
        preset: PresetAsset,
    ) {
        val evaluationInstant = Instant.ofEpochMilli(now())
        val evaluationZoneId = ZoneId.systemDefault()
        val history = if (appendAssistantTurn) record.selectedMessages() else record.turns.dropLast(1).map { it.selected.message }
        val runtimeBeforeGeneration = if (appendAssistantTurn) {
            record.runtimeState
        } else {
            record.turns.lastOrNull()?.selected?.runtimeStateBefore ?: record.runtimeState
        }
        val modelTokenLimits = connection.effectiveTokenLimits()
        val lastVisibleTurn = record.turns.lastOrNull()
        val baseInput = NormalGenerationInput(
            character = record.character,
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
        for (attempt in 0..MAX_PROVIDER_RECLIPS) {
            val compilation = compiler.compile(baseInput.copy(maxInputTokens = localInputLimit))
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
            val overflow = validation.inputTokens + candidate.maxOutputTokens - contextLimit
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
                ).coerceAtLeast(0)
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
        rawAssistant = ""
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
                }
                finishIfStreamEnded(variant.id)
            } catch (cancelled: CancellationException) {
                finishFailure(variant.id, cancelled = true, error = null)
                throw cancelled
            } catch (error: Exception) {
                finishFailure(variant.id, cancelled = false, error = error)
            } finally {
                generationJob = null
                persistNow()
                refreshDisplayCache(currentPreset)
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
            is GenerationEvent.Finished -> {
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
    ) {
        val history = record.selectedMessages().dropLast(1)
        val projection = withContext(projectionDispatcher) {
            compiler.projectAssistantOutput(
                rawText = rawAssistant,
                rawReasoning = rawReasoning.map(ReasoningBlock::text),
                character = record.character,
                persona = record.persona,
                preset = preset,
                runtimeState = record.runtimeState,
                history = history,
                conversationId = record.id,
                generationId = generationId,
                modelId = connection.selectedModel,
                evaluationInstant = evaluationInstant,
                evaluationZoneId = evaluationZoneId,
            )
        }
        val projectedRuntime = record.character.adaptation?.let { adaptation ->
            adaptationRuntime.ingestAssistantMessage(adaptation, rawAssistant, projection.runtimeState).runtimeState
        } ?: projection.runtimeState
        pendingAssistantRuntime = projectedRuntime
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
                    reasoning = reasoning,
                ),
                generationPlan = plan,
                runtimeStateAfter = projectedRuntime,
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
            _uiState.update { it.copy(running = false, retryAvailable = true, message = "模型没有返回正文") }
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
            _uiState.update { it.copy(running = false, retryAvailable = false, regenerateAvailable = true) }
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
        _uiState.update { it.copy(running = false, retryAvailable = !hasPartial, message = userMessage) }
        updateTrace { copy(error = userMessage) }
    }

    private fun selectVariant(delta: Int) {
        if (_uiState.value.running) return
        val lastIndex = record.turns.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastIndex < 0) return
        val turn = record.turns[lastIndex]
        val next = (turn.selectedVariantIndex + delta).coerceIn(0, turn.variants.lastIndex)
        if (next == turn.selectedVariantIndex) return
        val selected = turn.variants[next]
        record = record.copy(
            turns = record.turns.mapIndexed { index, item -> if (index == lastIndex) item.copy(selectedVariantIndex = next) else item },
            runtimeState = selected.runtimeStateAfter ?: record.runtimeState,
        )
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
        input: String = _uiState.value.input,
        running: Boolean = _uiState.value.running,
        retryAvailable: Boolean = _uiState.value.retryAvailable,
        message: String? = _uiState.value.message,
        trace: GenerationTraceState? = _uiState.value.lastTrace,
    ) {
        val current = _uiState.value
        _uiState.value = record.toUiState(
            input = input,
            loadingConnections = current.loadingConnections,
            activePreset = currentPreset,
            readyConnections = current.readyConnections,
            selectedConnectionId = current.selectedConnectionId,
            running = running,
            retryAvailable = retryAvailable,
            message = message,
            lastTrace = trace,
            displayContents = displayCache,
            displayReasoning = displayReasoningCache,
        )
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
        val initialRuntime = adaptationRuntime.initialState(snapshot.adaptation)
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
            val expandedRuntime = snapshot.adaptation?.let { adaptation ->
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(repository, compiler, generator, conversationRepository, presetSource) as T
    }

    companion object {
        private val READY_STATUSES = setOf(CredentialStatus.READY, CredentialStatus.NOT_REQUIRED)
        private const val MAX_PROVIDER_RECLIPS = 3
        private const val PROVIDER_RECLIP_SAFETY_TOKENS = 16
        private const val STREAM_PERSIST_INTERVAL_MILLIS = 500L
    }
}

private fun ConversationRecord.selectedMessages(): List<ConversationMessage> = turns.map(ConversationTurn::selected).map(MessageVariant::message)

private fun ConversationRecord.findVariant(id: String): MessageVariant? = turns.asSequence()
    .flatMap { it.variants.asSequence() }
    .firstOrNull { it.id == id }

private fun ConversationRecord.toUiState(
    input: String = "",
    loadingConnections: Boolean,
    activePreset: PresetAsset,
    readyConnections: List<StoredConnection> = emptyList(),
    selectedConnectionId: String? = null,
    running: Boolean = false,
    retryAvailable: Boolean = false,
    message: String? = null,
    lastTrace: GenerationTraceState? = null,
    displayContents: Map<String, String> = emptyMap(),
    displayReasoning: Map<String, List<String>> = emptyMap(),
): ChatUiState = ChatUiState(
    conversationId = id,
    character = character,
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
            adaptationViews = character.adaptation?.views.orEmpty().filter { view ->
                view.placement != AdaptationViewPlacement.CONVERSATION_HEADER &&
                    view.matchesMessage(variant.message.sourceText)
            },
        )
    },
    input = input,
    readyConnections = readyConnections,
    selectedConnectionId = selectedConnectionId,
    activePresetId = activePreset.id,
    activePresetName = activePreset.name,
    loadingConnections = loadingConnections,
    running = running,
    retryAvailable = retryAvailable,
    regenerateAvailable = turns.lastOrNull()?.role == MessageRole.ASSISTANT &&
        turns.dropLast(1).lastOrNull()?.role == MessageRole.USER,
    variantNavigationAvailable = turns.lastOrNull()?.let { it.role == MessageRole.ASSISTANT && it.variants.size > 1 } == true,
    message = message,
    lastTrace = lastTrace ?: persistedTrace(),
    adaptationState = runtimeState.adaptationState,
    headerAdaptationViews = character.adaptation?.views.orEmpty().filter { view ->
        view.placement == AdaptationViewPlacement.CONVERSATION_HEADER && view.matchesMessage("")
    },
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
    is GatewayException.HttpFailure -> "模型服务暂时不可用（HTTP $status）"
    is GatewayException.Protocol -> diagnostic
    else -> "生成失败"
}

private val EMPTY_CHARACTER = io.github.zvensmoluya.tavernplayer.content.CharacterAsset(
    id = "loading",
    name = "",
).snapshot()
