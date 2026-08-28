package io.github.zvensmoluya.tavernplayer.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zvensmoluya.modelgateway.AuthScheme
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
        val template = ConnectionTemplates.require(templateId)
        _uiState.update {
            it.copy(
                editor = ConnectionEditorState(
                    draft = ConnectionDraft(
                        id = ConnectionRepository.newConnectionId(),
                        name = template.displayName,
                        templateId = template.id,
                        protocol = template.protocol,
                        streamEndpoint = template.streamEndpoint,
                        catalogEndpoint = template.catalogEndpoint,
                        authScheme = template.authScheme,
                        selectedModel = "",
                    ),
                    credentialStatus = if (template.authScheme == AuthScheme.NONE) {
                        CredentialStatus.NOT_REQUIRED
                    } else CredentialStatus.MISSING,
                ),
                probe = ProbeUiState(),
            )
        }
    }

    fun edit(connectionId: String) {
        cancelProbe()
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
        _uiState.update { it.copy(editor = null, probe = ProbeUiState()) }
    }

    fun chooseTemplate(templateId: String) {
        val template = ConnectionTemplates.require(templateId)
        updateEditor { editor ->
            editor.copy(
                draft = editor.draft.copy(
                    templateId = template.id,
                    protocol = template.protocol,
                    streamEndpoint = template.streamEndpoint,
                    catalogEndpoint = template.catalogEndpoint,
                    authScheme = template.authScheme,
                ),
                confirmCredentialReuse = false,
                message = null,
            )
        }
    }

    fun updateName(value: String) = updateDraft { copy(name = value) }
    fun updateStreamEndpoint(value: String) = updateDraft { copy(streamEndpoint = value) }
    fun updateCatalogEndpoint(value: String) = updateDraft { copy(catalogEndpoint = value) }
    fun updateAuthScheme(value: AuthScheme) = updateDraft { copy(authScheme = value) }
    fun updateModel(value: String) = updateDraft { copy(selectedModel = value) }
    fun updateCredential(value: String) = updateEditor { it.copy(credentialInput = value, message = null) }
    fun setConfirmCredentialReuse(value: Boolean) = updateEditor { it.copy(confirmCredentialReuse = value) }
    fun updateProbeSystem(value: String) = updateProbe { copy(system = value) }
    fun updateProbeUser(value: String) = updateProbe { copy(user = value) }

    fun save() {
        val editor = _uiState.value.editor ?: return
        viewModelScope.launch {
            updateEditor { it.copy(saving = true, message = null) }
            try {
                val stored = repository.save(
                    draft = editor.draft,
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
                        message = if (status == CredentialStatus.ORIGIN_CONFIRMATION_REQUIRED) {
                            "endpoint origin 已改变。勾选确认复用原密钥，或输入新密钥。"
                        } else "已保存",
                    )
                }
            } catch (error: Exception) {
                updateEditor { it.copy(saving = false, message = error.userMessage()) }
            }
        }
    }

    fun refreshModels() {
        val editor = _uiState.value.editor ?: return
        viewModelScope.launch {
            updateEditor { it.copy(refreshingModels = true, message = null) }
            try {
                val cache = repository.refreshModels(editor.draft.id)
                updateEditor {
                    it.copy(
                        refreshingModels = false,
                        message = "已刷新 ${cache.models.size} 个模型",
                    )
                }
            } catch (error: Exception) {
                val cachedAt = _uiState.value.connections.firstOrNull { it.id == editor.draft.id }
                    ?.modelCache?.refreshedAtEpochMillis
                updateEditor {
                    it.copy(
                        refreshingModels = false,
                        message = if (cachedAt != null) {
                            "刷新失败，继续显示上次成功缓存：${error.userMessage()}"
                        } else "刷新失败：${error.userMessage()}",
                    )
                }
            }
        }
    }

    fun delete(connectionId: String) {
        cancelProbe()
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
        it.copy(draft = it.draft.transform(), message = null)
    }

    private fun updateEditor(transform: (ConnectionEditorState) -> ConnectionEditorState) {
        _uiState.update { state -> state.editor?.let { state.copy(editor = transform(it)) } ?: state }
    }

    private fun updateProbe(transform: ProbeUiState.() -> ProbeUiState) {
        _uiState.update { it.copy(probe = it.probe.transform()) }
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
    streamEndpoint = streamEndpoint,
    catalogEndpoint = catalogEndpoint,
    authScheme = authScheme,
    selectedModel = selectedModel,
)

private fun Throwable.userMessage(): String = when (this) {
    is GatewayException.HttpFailure -> buildString {
        append("HTTP ")
        append(status)
        requestId?.let { append(" · requestId=").append(it) }
    }
    is GatewayException -> message ?: "模型网关错误"
    else -> message ?: "未知错误"
}
