package io.github.zvensmoluya.tavernplayer.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.tavernplayer.connections.ConnectionRepository
import io.github.zvensmoluya.tavernplayer.connections.CredentialStatus
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ChatMessageStatus {
    COMPLETE,
    STREAMING,
    CANCELLED,
    ERROR,
}

data class AssistantGenerationMetadata(
    val presetId: String,
    val adapterId: String,
    val model: String,
    val usage: GenerationUsage? = null,
    val finishReason: String? = null,
)

data class ChatMessageState(
    val message: ConversationMessage,
    val status: ChatMessageStatus = ChatMessageStatus.COMPLETE,
    val metadata: AssistantGenerationMetadata? = null,
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
    val character: CharacterSnapshot,
    val persona: Persona,
    val messages: List<ChatMessageState>,
    val input: String = "",
    val readyConnections: List<StoredConnection> = emptyList(),
    val selectedConnectionId: String? = null,
    val loadingConnections: Boolean = true,
    val running: Boolean = false,
    val retryAvailable: Boolean = false,
    val message: String? = null,
    val lastTrace: GenerationTraceState? = null,
) {
    val selectedConnection: StoredConnection?
        get() = readyConnections.firstOrNull { it.id == selectedConnectionId }
}

class ChatViewModel(
    private val repository: ConnectionRepository,
    private val compiler: PromptCompiler,
    private val generator: ConversationGenerator,
    private val characterAsset: CharacterAsset = DemoConversationContent.character,
    private val persona: Persona = DemoConversationContent.persona,
    private val preset: Preset = DemoConversationContent.preset,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val initialCharacter = characterAsset.snapshot()
    private val _uiState = MutableStateFlow(newState(initialCharacter))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private var generationJob: Job? = null

    init {
        viewModelScope.launch {
            repository.state.collect { gatewayState ->
                val ready = gatewayState.connections.filter { connection ->
                    connection.selectedModel.isNotBlank() && repository.credentialStatus(connection) in readyStatuses
                }.sortedBy { it.name.lowercase() }
                val current = _uiState.value.selectedConnectionId
                val selected = when {
                    ready.any { it.id == gatewayState.recentConnectionId } -> gatewayState.recentConnectionId
                    ready.any { it.id == current } -> current
                    else -> ready.firstOrNull()?.id
                }
                _uiState.update {
                    it.copy(
                        readyConnections = ready,
                        selectedConnectionId = selected,
                        loadingConnections = false,
                    )
                }
            }
        }
    }

    fun updateInput(value: String) {
        _uiState.update { it.copy(input = value, message = null) }
    }

    fun send() {
        val state = _uiState.value
        if (state.running || state.input.isBlank()) return
        val expanded = compiler.expandConversationText(state.input, state.character, state.persona)
        if (expanded is TextExpansionResult.Failure) {
            _uiState.update {
                it.copy(
                    message = expanded.diagnostic.message,
                    lastTrace = GenerationTraceState(compileDiagnostics = listOf(expanded.diagnostic)),
                )
            }
            return
        }
        val content = (expanded as TextExpansionResult.Success).text
        val userMessage = ConversationMessage(
            id = idGenerator(),
            role = MessageRole.USER,
            content = content,
            authorName = state.persona.name,
        )
        _uiState.update {
            it.copy(
                messages = it.messages + ChatMessageState(userMessage),
                input = "",
                retryAvailable = false,
                message = null,
            )
        }
        generateForCurrentHistory()
    }

    fun retry() {
        val state = _uiState.value
        if (state.running || !state.retryAvailable || state.messages.lastOrNull()?.message?.role != MessageRole.USER) return
        generateForCurrentHistory()
    }

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
        val snapshot = characterAsset.snapshot()
        val current = _uiState.value
        _uiState.value = newState(snapshot).copy(
            readyConnections = current.readyConnections,
            selectedConnectionId = current.selectedConnectionId,
            loadingConnections = current.loadingConnections,
        )
    }

    private fun generateForCurrentHistory() {
        val state = _uiState.value
        val connection = state.selectedConnection
        if (connection == null) {
            _uiState.update { it.copy(message = "请先配置可用模型", retryAvailable = true) }
            return
        }
        val compilation = compiler.compile(
            NormalGenerationInput(
                character = state.character,
                persona = state.persona,
                history = state.messages.map(ChatMessageState::message),
                preset = preset,
            ),
        )
        if (compilation is CompilationResult.Failure) {
            _uiState.update {
                it.copy(
                    message = compilation.diagnostics.firstOrNull()?.message ?: "Prompt 编排失败",
                    retryAvailable = true,
                    lastTrace = GenerationTraceState(
                        compileDiagnostics = compilation.diagnostics,
                        compileTrace = compilation.trace,
                    ),
                )
            }
            return
        }
        val plan = (compilation as CompilationResult.Success).plan
        val assistantId = idGenerator()
        val adapterId = connection.protocol.name
        val placeholder = ConversationMessage(
            id = assistantId,
            role = MessageRole.ASSISTANT,
            content = "",
            authorName = state.character.name,
            adapterId = adapterId,
        )
        _uiState.update {
            it.copy(
                messages = it.messages + ChatMessageState(
                    message = placeholder,
                    status = ChatMessageStatus.STREAMING,
                    metadata = AssistantGenerationMetadata(plan.presetId, adapterId, connection.selectedModel),
                ),
                running = true,
                retryAvailable = false,
                message = null,
                lastTrace = GenerationTraceState(
                    plan = plan,
                    compileDiagnostics = plan.diagnostics,
                    compileTrace = plan.trace,
                ),
            )
        }
        generationJob = viewModelScope.launch {
            try {
                generator.stream(connection, plan).collect { event -> applyEvent(assistantId, event) }
                finishIfStreamEnded(assistantId)
            } catch (cancelled: CancellationException) {
                finishFailure(assistantId, cancelled = true, error = null)
                throw cancelled
            } catch (error: Exception) {
                finishFailure(assistantId, cancelled = false, error = error)
            } finally {
                generationJob = null
            }
        }
    }

    private fun applyEvent(assistantId: String, event: GenerationEvent) {
        when (event) {
            is GenerationEvent.RequestPrepared -> updateTrace { copy(providerPreview = event.preview) }
            is GenerationEvent.TextDelta -> updateAssistant(assistantId) { state ->
                state.copy(message = state.message.copy(content = state.message.content + event.text))
            }
            GenerationEvent.ReasoningStarted -> updateAssistant(assistantId) { state ->
                val reasoning = state.message.reasoning
                if (reasoning.lastOrNull()?.let { it.text.isEmpty() && it.signature == null } == true) state
                else state.copy(message = state.message.copy(reasoning = reasoning + ReasoningBlock()))
            }
            is GenerationEvent.ReasoningDelta -> updateAssistant(assistantId) { state ->
                val reasoning = state.message.reasoning.ifEmpty { listOf(ReasoningBlock()) }.toMutableList()
                val last = reasoning.removeAt(reasoning.lastIndex)
                reasoning += last.copy(text = last.text + event.text)
                state.copy(message = state.message.copy(reasoning = reasoning))
            }
            is GenerationEvent.ReasoningSignature -> updateAssistant(assistantId) { state ->
                val reasoning = state.message.reasoning.ifEmpty { listOf(ReasoningBlock()) }.toMutableList()
                val last = reasoning.removeAt(reasoning.lastIndex)
                if (last.signature == null) {
                    reasoning += last.copy(signature = event.signature)
                } else {
                    reasoning += last
                    reasoning += ReasoningBlock(signature = event.signature)
                }
                state.copy(message = state.message.copy(reasoning = reasoning))
            }
            GenerationEvent.ReasoningFinished -> Unit
            is GenerationEvent.Usage -> {
                updateAssistant(assistantId) { state ->
                    state.copy(metadata = state.metadata?.copy(usage = event.value))
                }
                updateTrace { copy(usage = event.value) }
            }
            is GenerationEvent.Finished -> {
                updateAssistant(assistantId) { state ->
                    state.copy(
                        status = ChatMessageStatus.COMPLETE,
                        metadata = state.metadata?.copy(finishReason = event.reason),
                    )
                }
                updateTrace { copy(finishReason = event.reason) }
            }
            is GenerationEvent.Diagnostic -> updateTrace {
                copy(streamDiagnostics = (streamDiagnostics + event.summary).takeLast(30))
            }
        }
    }

    private fun finishIfStreamEnded(assistantId: String) {
        val assistant = _uiState.value.messages.firstOrNull { it.message.id == assistantId }
        if (assistant == null) return
        if (assistant.message.content.isBlank()) {
            removeAssistant(assistantId)
            _uiState.update {
                it.copy(running = false, retryAvailable = true, message = "模型没有返回正文")
            }
            updateTrace { copy(error = "模型没有返回正文") }
        } else {
            updateAssistant(assistantId) { state ->
                if (state.status == ChatMessageStatus.STREAMING) state.copy(status = ChatMessageStatus.COMPLETE) else state
            }
            _uiState.update { it.copy(running = false, retryAvailable = false) }
        }
    }

    private fun finishFailure(assistantId: String, cancelled: Boolean, error: Exception?) {
        val assistant = _uiState.value.messages.firstOrNull { it.message.id == assistantId }
        val hasPartial = assistant?.message?.content?.isNotBlank() == true
        if (hasPartial) {
            updateAssistant(assistantId) {
                it.copy(status = if (cancelled) ChatMessageStatus.CANCELLED else ChatMessageStatus.ERROR)
            }
        } else {
            removeAssistant(assistantId)
        }
        val userMessage = if (cancelled) "已停止生成" else error?.userMessage().orEmpty().ifBlank { "生成失败" }
        _uiState.update {
            it.copy(
                running = false,
                retryAvailable = !hasPartial,
                message = userMessage,
            )
        }
        updateTrace { copy(error = userMessage) }
    }

    private fun updateAssistant(id: String, transform: (ChatMessageState) -> ChatMessageState) {
        _uiState.update { state ->
            state.copy(messages = state.messages.map { if (it.message.id == id) transform(it) else it })
        }
    }

    private fun removeAssistant(id: String) {
        _uiState.update { state -> state.copy(messages = state.messages.filterNot { it.message.id == id }) }
    }

    private fun updateTrace(transform: GenerationTraceState.() -> GenerationTraceState) {
        _uiState.update { state ->
            state.lastTrace?.let { state.copy(lastTrace = it.transform()) } ?: state
        }
    }

    private fun newState(character: CharacterSnapshot): ChatUiState {
        val opening = when (val result = compiler.expandConversationText(character.firstMessage, character, persona)) {
            is TextExpansionResult.Success -> result.text
            is TextExpansionResult.Failure -> character.firstMessage
        }
        val messages = opening.takeIf(String::isNotBlank)?.let {
            listOf(
                ChatMessageState(
                    ConversationMessage(
                        id = idGenerator(),
                        role = MessageRole.ASSISTANT,
                        content = it,
                        authorName = character.name,
                    ),
                ),
            )
        }.orEmpty()
        return ChatUiState(character = character, persona = persona, messages = messages)
    }

    class Factory(
        private val repository: ConnectionRepository,
        private val compiler: PromptCompiler,
        private val generator: ConversationGenerator,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(repository, compiler, generator) as T
    }

    companion object {
        private val readyStatuses = setOf(CredentialStatus.READY, CredentialStatus.NOT_REQUIRED)
    }
}

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
