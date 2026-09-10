package io.github.zvensmoluya.tavernplayer.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zvensmoluya.modelgateway.GatewayException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectionEditorState(
    val draft: ConnectionDraft,
    val credentialInput: String = "",
    val existingCredentialMask: String? = null,
    val credentialStatus: CredentialStatus = CredentialStatus.MISSING,
    val confirmCredentialReuse: Boolean = false,
    val saving: Boolean = false,
    val refreshingModels: Boolean = false,
    val message: String? = null,
    val modelMessage: String? = null,
)

data class ProbeUiState(
    val system: String = "You are a helpful assistant.",
    val user: String = "Reply with a short greeting.",
    val running: Boolean = false,
    val text: String = "",
    val reasoning: String = "",
    val diagnostics: List<String> = emptyList(),
    val usage: ProbeUsage? = null,
    val finishReason: String? = null,
    val error: String? = null,
)

data class ConnectionsUiState(
    val connections: List<StoredConnection> = emptyList(),
    val credentialStatuses: Map<String, CredentialStatus> = emptyMap(),
    val editor: ConnectionEditorState? = null,
    val probe: ProbeUiState = ProbeUiState(),
    val loading: Boolean = true,
)

class ModelConnectionsViewModel(
    private val repository: ConnectionRepository,
    private val probeService: ProbeService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ConnectionsUiState())
    val uiState: StateFlow<ConnectionsUiState> = _uiState.asStateFlow()
    private var probeJob: Job? = null
    private var modelDiscoveryJob: Job? = null

    init {
        viewModelScope.launch {
            repository.state.collect { state ->
                val statuses = state.connections.associate { it.id to repository.credentialStatus(it) }
                _uiState.update { current ->
                    val editingId = current.editor?.draft?.id
                    val refreshedEditor = editingId?.let { id ->
                        state.connections.firstOrNull { it.id == id }?.let { stored ->
                            current.editor.copy(
                                draft = stored.toDraft(),
                                existingCredentialMask = stored.credentialMask,
                                credentialStatus = statuses[stored.id] ?: CredentialStatus.MISSING,
                            )
                        }
                    } ?: current.editor
                    current.copy(
                        connections = state.connections.sortedBy { it.name.lowercase() },
                        credentialStatuses = statuses,
                        editor = refreshedEditor,
                        loading = false,
                    )
                }
            }
        }
    }

    fun startNew(templateId: String = ConnectionTemplates.openAiResponses.id) {
        cancelProbe()
        cancelModelDiscovery()
        val template = ConnectionTemplates.require(templateId)
        _uiState.update {
            it.copy(
                editor = ConnectionEditorState(
                    draft = ConnectionDraft(
                        id = ConnectionRepository.newConnectionId(),
                        name = template.displayName,
                        templateId = template.id,
                        protocol = template.protocol,
                        apiAddress = ConnectionEndpointResolver.displayAddress(
                            template.protocol,
                            template.streamEndpoint,
                        ),
                        selectedModel = "",
                    ),
                    credentialStatus = CredentialStatus.NOT_REQUIRED,
                ),
                probe = ProbeUiState(),
            )
        }
    }

    fun edit(connectionId: String) {
        cancelProbe()
        cancelModelDiscovery()
        val connection = _uiState.value.connections.firstOrNull { it.id == connectionId } ?: return
        _uiState.update {
            it.copy(
                editor = ConnectionEditorState(
                    draft = connection.toDraft(),
                    existingCredentialMask = connection.credentialMask,
                    credentialStatus = it.credentialStatuses[connection.id] ?: CredentialStatus.MISSING,
                ),
                probe = ProbeUiState(),
            )
        }
    }

    fun closeEditor() {
        cancelProbe()
        cancelModelDiscovery()
        _uiState.update { it.copy(editor = null, probe = ProbeUiState()) }
    }

    fun chooseTemplate(templateId: String) {
        val template = ConnectionTemplates.require(templateId)
        updateEditor { editor ->
            val previousTemplate = runCatching { ConnectionTemplates.require(editor.draft.templateId) }.getOrNull()
            val previousDefaultAddress = previousTemplate?.let {
                ConnectionEndpointResolver.displayAddress(it.protocol, it.streamEndpoint)
            }
            val nextDefaultAddress = ConnectionEndpointResolver.displayAddress(
                template.protocol,
                template.streamEndpoint,
            )
            editor.copy(
                draft = editor.draft.copy(
                    templateId = template.id,
                    protocol = template.protocol,
                    apiAddress = if (editor.draft.apiAddress == previousDefaultAddress) {
                        nextDefaultAddress
                    } else editor.draft.apiAddress,
                ),
                confirmCredentialReuse = false,
                message = null,
                modelMessage = null,
            )
        }
    }

    fun updateName(value: String) = updateDraft { copy(name = value) }
    fun updateApiAddress(value: String) = updateDraft { copy(apiAddress = value) }
    fun updateModel(value: String) = updateEditor { editor ->
        val limits = _uiState.value.connections
            .firstOrNull { it.id == editor.draft.id }
            ?.modelTokenLimitOverrides
            ?.get(value.trim())
        editor.copy(
            draft = editor.draft.copy(
                selectedModel = value,
                contextTokenLimitOverride = limits?.contextTokens?.toString().orEmpty(),
                outputTokenLimitOverride = limits?.outputTokens?.toString().orEmpty(),
            ),
            message = null,
            modelMessage = null,
        )
    }
    fun updateContextTokenLimit(value: String) = updateDraft {
        copy(contextTokenLimitOverride = value.filter(Char::isDigit))
    }
    fun updateOutputTokenLimit(value: String) = updateDraft {
        copy(outputTokenLimitOverride = value.filter(Char::isDigit))
    }
    fun updateCredential(value: String) = updateEditor {
        it.copy(credentialInput = value, message = null, modelMessage = null)
    }
    fun setConfirmCredentialReuse(value: Boolean) = updateEditor { it.copy(confirmCredentialReuse = value) }

    fun save() {
        val editor = _uiState.value.editor ?: return
        viewModelScope.launch {
            updateEditor { it.copy(saving = true, message = null, modelMessage = null) }
            try {
                val preparedDraft = editor.draft
                val previous = _uiState.value.connections.firstOrNull { it.id == preparedDraft.id }
                val shouldDiscover = previous == null ||
                    previous.protocol != preparedDraft.protocol ||
                    previous.apiAddress != preparedDraft.apiAddress.trim().trimEnd('/') ||
                    editor.credentialInput.isNotBlank() ||
                    editor.confirmCredentialReuse ||
                    previous.modelCache.models.isEmpty()
                updateEditor { it.copy(draft = preparedDraft) }
                val stored = repository.save(
                    draft = preparedDraft,
                    newCredential = editor.credentialInput,
                    confirmCredentialReuse = editor.confirmCredentialReuse,
                )
                val status = repository.credentialStatus(stored)
                updateEditor {
                    it.copy(
                        draft = stored.toDraft(),
                        credentialInput = "",
                        existingCredentialMask = stored.credentialMask,
                        credentialStatus = status,
                        saving = false,
                        refreshingModels = false,
                        message = if (status == CredentialStatus.ORIGIN_CONFIRMATION_REQUIRED) {
                            "请确认向新地址发送已保存的 API Key"
                        } else null,
                    )
                }
                if (shouldDiscover && status in readyCredentialStatuses) {
                    startModelDiscovery(stored.id)
                }
            } catch (error: Exception) {
                updateEditor { it.copy(saving = false, message = error.userMessage()) }
            }
        }
    }

    fun refreshModels() {
        val editor = _uiState.value.editor ?: return
        startModelDiscovery(editor.draft.id)
    }

    fun delete(connectionId: String) {
        cancelProbe()
        cancelModelDiscovery()
        viewModelScope.launch {
            repository.delete(connectionId)
            if (_uiState.value.editor?.draft?.id == connectionId) closeEditor()
        }
    }

    fun runProbe() {
        val editor = _uiState.value.editor ?: return
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            val currentProbe = _uiState.value.probe
            _uiState.update {
                it.copy(probe = currentProbe.copy(
                    running = true,
                    text = "",
                    reasoning = "",
                    diagnostics = emptyList(),
                    usage = null,
                    finishReason = null,
                    error = null,
                ))
            }
            try {
                repository.selectModel(editor.draft.id, editor.draft.selectedModel)
                val connection = repository.requireConnection(editor.draft.id)
                probeService.stream(connection, ProbeInput(currentProbe.system, currentProbe.user)).collect { event ->
                    updateProbe {
                        when (event) {
                            is ProbeEvent.Text -> copy(text = text + event.delta)
                            is ProbeEvent.Reasoning -> copy(reasoning = reasoning + event.delta)
                            is ProbeEvent.Usage -> copy(usage = event.value)
                            is ProbeEvent.Finished -> copy(finishReason = event.reason)
                            is ProbeEvent.Diagnostic -> copy(diagnostics = (diagnostics + event.summary).takeLast(20))
                        }
                    }
                }
                updateProbe { copy(running = false) }
            } catch (cancelled: CancellationException) {
                updateProbe { copy(running = false, finishReason = "cancelled") }
                throw cancelled
            } catch (error: Exception) {
                updateProbe { copy(running = false, error = error.userMessage()) }
            }
        }
    }

    fun cancelProbe() {
        probeJob?.cancel()
        probeJob = null
    }

    private fun updateDraft(transform: ConnectionDraft.() -> ConnectionDraft) = updateEditor {
        it.copy(draft = it.draft.transform(), message = null, modelMessage = null)
    }

    private fun updateEditor(transform: (ConnectionEditorState) -> ConnectionEditorState) {
        _uiState.update { state -> state.editor?.let { state.copy(editor = transform(it)) } ?: state }
    }

    private fun updateProbe(transform: ProbeUiState.() -> ProbeUiState) {
        _uiState.update { it.copy(probe = it.probe.transform()) }
    }

    private fun startModelDiscovery(connectionId: String) {
        cancelModelDiscovery()
        updateEditor { it.copy(refreshingModels = true, modelMessage = null) }
        modelDiscoveryJob = viewModelScope.launch {
            val result = repository.refreshModels(connectionId)
            if (_uiState.value.editor?.draft?.id == connectionId) {
                updateEditor {
                    it.copy(
                        refreshingModels = false,
                        modelMessage = result.userMessage(),
                    )
                }
            }
        }
    }

    private fun cancelModelDiscovery() {
        modelDiscoveryJob?.cancel()
        modelDiscoveryJob = null
    }

    class Factory(
        private val repository: ConnectionRepository,
        private val probeService: ProbeService,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ModelConnectionsViewModel(repository, probeService) as T
    }
}

private fun StoredConnection.toDraft() = ConnectionDraft(
    id = id,
    name = name,
    templateId = templateId,
    protocol = protocol,
    apiAddress = apiAddress,
    selectedModel = selectedModel,
    contextTokenLimitOverride = modelTokenLimitOverrides[selectedModel]?.contextTokens?.toString().orEmpty(),
    outputTokenLimitOverride = modelTokenLimitOverrides[selectedModel]?.outputTokens?.toString().orEmpty(),
)

private fun ModelDiscoveryResult.userMessage(): String? = when (this) {
    is ModelDiscoveryResult.Found,
    is ModelDiscoveryResult.Empty,
    -> null
    is ModelDiscoveryResult.Unavailable -> when (failure.kind) {
        ModelDiscoveryFailureKind.UNSUPPORTED -> null
        ModelDiscoveryFailureKind.AUTHENTICATION -> "API Key 不可用"
        ModelDiscoveryFailureKind.CREDENTIAL_CONFIRMATION -> "请确认 API 地址"
        ModelDiscoveryFailureKind.RATE_LIMITED -> "请求过于频繁，请稍后再试"
        ModelDiscoveryFailureKind.UNREACHABLE -> "无法连接到模型服务"
        ModelDiscoveryFailureKind.INVALID_RESPONSE -> "模型列表不可识别"
        ModelDiscoveryFailureKind.SERVICE -> failure.httpStatus?.let { "模型服务暂时不可用（HTTP $it）" }
            ?: "模型服务暂时不可用"
    }
}

private fun Throwable.userMessage(): String = when (this) {
    is GatewayException.Authentication,
    is GatewayException.AuthenticationFailure,
    -> "API Key 不可用"
    is GatewayException.RateLimited -> "请求过于频繁，请稍后再试"
    is GatewayException.HttpFailure -> when (status) {
        404 -> "API 地址或协议不匹配"
        else -> providerHttpFailureText(status, diagnostic)
    }
    is GatewayException.Network -> "无法连接到模型服务"
    is GatewayException.Security -> "API 地址未获授权"
    is GatewayException.Configuration -> when {
        message.orEmpty().contains("token limit", ignoreCase = true) -> "Token 上限必须是正整数"
        message.orEmpty().contains("model", ignoreCase = true) -> "请选择模型"
        message.orEmpty().contains("HTTPS", ignoreCase = true) -> "API 地址必须使用 HTTPS"
        else -> "请检查连接信息"
    }
    is GatewayException -> "模型服务返回了无法识别的数据"
    else -> "操作失败"
}

private val readyCredentialStatuses = setOf(
    CredentialStatus.READY,
    CredentialStatus.NOT_REQUIRED,
)
