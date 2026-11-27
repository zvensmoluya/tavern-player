package io.github.zvensmoluya.tavernplayer.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zvensmoluya.modelgateway.GatewayException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConnectionPage { CREATE, DETAIL, ADDRESS, KEY, MODELS, NAME, LIMITS }

data class ConnectionEditorState(
    val draft: ConnectionDraft,
    val initialDraft: ConnectionDraft = draft,
    val credentialInput: String = "",
    val page: ConnectionPage = ConnectionPage.DETAIL,
    val credentialStatus: CredentialStatus = CredentialStatus.MISSING,
    val confirmCredentialReuse: Boolean = false,
    val saving: Boolean = false,
    val refreshingModels: Boolean = false,
    val message: String? = null,
    val modelMessage: String? = null,
    val selectionNotice: String? = null,
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
    val activeId: String? = null,
    val activatingId: String? = null,
    val activationError: String? = null,
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
                val sorted = state.connections.sortedBy { it.name.lowercase() }
                val ready = sorted.filter { it.selectedModel.isNotBlank() && statuses[it.id] in readyCredentialStatuses }
                _uiState.update { current ->
                    val editingId = current.editor?.draft?.id
                    val refreshedEditor = editingId?.let { id ->
                        state.connections.firstOrNull { it.id == id }?.let { stored ->
                            current.editor.copy(
                                credentialStatus = statuses[stored.id] ?: CredentialStatus.MISSING,
                            )
                        }
                    } ?: current.editor
                    current.copy(
                        connections = sorted,
                        activeId = activeConnectionId(ready, state.recentConnectionId, current.activeId),
                        credentialStatuses = statuses,
                        editor = refreshedEditor,
                        loading = false,
                    )
                }
            }
        }
    }

    fun useConnection(id: String) {
        if (_uiState.value.activatingId != null) return
        _uiState.update { it.copy(activatingId = id, activationError = null) }
        viewModelScope.launch {
            try {
                val connection = repository.requireConnection(id)
                repository.ensureReady(connection)
                if (connection.selectedModel.isBlank()) throw GatewayException.Configuration("A model is required")
                repository.activate(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(activationError = error.userMessage()) }
            } finally {
                _uiState.update { it.copy(activatingId = null) }
            }
        }
    }

    fun startNew() {
        cancelProbe()
        cancelModelDiscovery()
        val template = ConnectionTemplates.openAiResponses
        _uiState.update {
            it.copy(
                editor = ConnectionEditorState(
                    draft = ConnectionDraft(
                        id = ConnectionRepository.newConnectionId(),
                        name = "",
                        templateId = template.id,
                        protocol = template.protocol,
                        apiAddress = ConnectionEndpointResolver.displayAddress(
                            template.protocol,
                            template.streamEndpoint,
                        ),
                        selectedModel = "",
                    ),
                    credentialStatus = CredentialStatus.NOT_REQUIRED,
                    page = ConnectionPage.CREATE,
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
                    credentialStatus = it.credentialStatuses[connection.id] ?: CredentialStatus.MISSING,
                ),
                probe = ProbeUiState(),
            )
        }
    }

    fun showPage(page: ConnectionPage) {
        val editor = _uiState.value.editor ?: return
        if (editor.saving) return
        val stored = _uiState.value.connections.firstOrNull { it.id == editor.draft.id } ?: return
        val draft = stored.toDraft()
        updateEditor { it.copy(page = page, draft = draft, initialDraft = draft, credentialInput = "",
            confirmCredentialReuse = false, message = null, selectionNotice = null) }
    }

    fun closeEditor() {
        cancelProbe()
        cancelModelDiscovery()
        _uiState.update { it.copy(editor = null, probe = ProbeUiState()) }
    }

    fun chooseProtocol(templateId: String) {
        val template = ConnectionTemplates.require(templateId)
        changeSettings { editor ->
            editor.copy(
                draft = editor.draft.copy(templateId = template.id, protocol = template.protocol),
                confirmCredentialReuse = false,
                message = null,
                modelMessage = null,
            )
        }
    }

    fun updateName(value: String) = updateDraft { copy(name = value) }
    fun updateApiAddress(value: String) = changeSettings {
        it.copy(draft = it.draft.copy(apiAddress = value), confirmCredentialReuse = false, message = null, modelMessage = null)
    }
    fun updateContextTokenLimit(value: String) = updateDraft {
        copy(contextTokenLimitOverride = value.filter(Char::isDigit))
    }
    fun updateOutputTokenLimit(value: String) = updateDraft {
        copy(outputTokenLimitOverride = value.filter(Char::isDigit))
    }
    fun updateCredential(value: String) = changeSettings {
        it.copy(credentialInput = value, message = null, modelMessage = null)
    }
    fun setConfirmCredentialReuse(value: Boolean) = changeSettings { it.copy(confirmCredentialReuse = value) }

    fun save() = persist(null)

    fun selectModel(modelId: String) = persist(modelId.trim())

    private fun persist(modelId: String?) {
        val editor = _uiState.value.editor ?: return
        if (editor.saving || editor.page == ConnectionPage.DETAIL) return
        if (editor.page == ConnectionPage.MODELS && modelId.isNullOrBlank()) return
        cancelProbe()
        cancelModelDiscovery()
        updateEditor { it.copy(saving = true, refreshingModels = false, message = null) }
        viewModelScope.launch {
            try {
                val previous = if (editor.page == ConnectionPage.CREATE) null else repository.requireConnection(editor.draft.id)
                val current = previous?.toDraft() ?: editor.draft
                val preparedDraft = when (editor.page) {
                    ConnectionPage.ADDRESS -> current.copy(apiAddress = editor.draft.apiAddress, protocol = editor.draft.protocol,
                        templateId = editor.draft.templateId)
                    ConnectionPage.NAME -> current.copy(name = editor.draft.name)
                    ConnectionPage.LIMITS -> {
                        if (current.selectedModel != editor.initialDraft.selectedModel) {
                            throw GatewayException.Configuration("Connection changed during save")
                        }
                        current.copy(contextTokenLimitOverride = editor.draft.contextTokenLimitOverride,
                            outputTokenLimitOverride = editor.draft.outputTokenLimitOverride)
                    }
                    ConnectionPage.KEY -> {
                        if (current.apiAddress != editor.initialDraft.apiAddress || current.protocol != editor.initialDraft.protocol) {
                            throw GatewayException.Configuration("Connection changed during save")
                        }
                        current
                    }
                    else -> current
                }
                val shouldDiscover = previous == null ||
                    previous.protocol != preparedDraft.protocol ||
                    previous.apiAddress != preparedDraft.apiAddress.trim().trimEnd('/') ||
                    editor.credentialInput.isNotBlank() ||
                    editor.confirmCredentialReuse
                val stored = if (modelId != null) {
                    repository.selectModel(preparedDraft.id, modelId)
                    repository.requireConnection(preparedDraft.id)
                } else {
                    repository.save(preparedDraft, editor.credentialInput, editor.confirmCredentialReuse)
                }
                val status = repository.credentialStatus(stored)
                if (_uiState.value.editor?.draft?.id != stored.id) return@launch
                val savedDraft = stored.toDraft()
                _uiState.update { state ->
                    val editorAfterSave = editor.copy(
                        draft = savedDraft, initialDraft = savedDraft, credentialInput = "", confirmCredentialReuse = false,
                        page = if (previous == null) ConnectionPage.MODELS else ConnectionPage.DETAIL,
                        credentialStatus = status, saving = false, refreshingModels = false,
                        message = null, modelMessage = if (shouldDiscover) null else editor.modelMessage,
                        selectionNotice = modelId?.let {
                            if (status in readyCredentialStatuses) "✓ 已选用" else "已选择，请先配置连接"
                        },
                    )
                    state.copy(editor = editorAfterSave,
                        activeId = if (modelId != null && status in readyCredentialStatuses) stored.id else state.activeId,
                        credentialStatuses = state.credentialStatuses + (stored.id to status),
                        connections = (state.connections.filterNot { it.id == stored.id } + stored).sortedBy { it.name.lowercase() })
                }
                if (shouldDiscover && status in readyCredentialStatuses &&
                    _uiState.value.editor?.canUseSavedConnection(stored) == true
                ) {
                    startModelDiscovery(stored.id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (_uiState.value.editor?.draft?.id == editor.draft.id) {
                    updateEditor { it.copy(saving = false, message = error.userMessage()) }
                }
            }
        }
    }

    fun refreshModels() {
        val editor = _uiState.value.editor ?: return
        if (canUseSavedConnection(editor)) startModelDiscovery(editor.draft.id)
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
        if (!canUseSavedConnection(editor)) return
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
                val connection = repository.requireConnection(editor.draft.id)
                probeService.stream(connection, ProbeInput(currentProbe.system, currentProbe.user)).collect { event ->
                    updateProbe {
                        when (event) {
                            is ProbeEvent.Text -> copy(text = text + event.delta)
                            is ProbeEvent.Reasoning -> copy(reasoning = reasoning + event.delta)
                            is ProbeEvent.Usage -> copy(usage = event.value)
                            is ProbeEvent.Finished -> copy(finishReason = event.reason)
                            is ProbeEvent.Failed -> copy(error = "模型服务报告生成失败")
                            is ProbeEvent.Diagnostic -> copy(diagnostics = (diagnostics + event.summary).takeLast(20))
                        }
                    }
                }
                updateProbe {
                    copy(running = false, error = error ?: if (text.isBlank() && finishReason == null) {
                        "服务已响应，但没有收到模型回复"
                    } else null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ensureActive()
                updateProbe { copy(running = false, error = error.userMessage()) }
            }
        }
    }

    fun cancelProbe() {
        probeJob?.cancel()
        probeJob = null
        _uiState.update { it.copy(probe = ProbeUiState(system = it.probe.system, user = it.probe.user)) }
    }

    private fun canUseSavedConnection(editor: ConnectionEditorState): Boolean =
        editor.canUseSavedConnection(_uiState.value.connections.firstOrNull { it.id == editor.draft.id })

    private fun updateDraft(transform: ConnectionDraft.() -> ConnectionDraft) = changeSettings {
        it.copy(draft = it.draft.transform(), message = null, modelMessage = null)
    }

    private fun changeSettings(transform: (ConnectionEditorState) -> ConnectionEditorState) {
        val current = _uiState.value.editor ?: return
        if (current.saving) return
        val next = transform(current)
        cancelProbe()
        val discoveryChanged = next.credentialInput != current.credentialInput ||
            next.draft.protocol != current.draft.protocol || next.draft.apiAddress != current.draft.apiAddress ||
            next.confirmCredentialReuse != current.confirmCredentialReuse
        if (discoveryChanged) cancelModelDiscovery()
        updateEditor { next.copy(refreshingModels = next.refreshingModels && !discoveryChanged) }
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
            val message = try {
                repository.refreshModels(connectionId).userMessage()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                error.userMessage()
            }
            ensureActive()
            if (_uiState.value.editor?.draft?.id == connectionId) {
                updateEditor {
                    it.copy(
                        refreshingModels = false,
                        modelMessage = message,
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
        ModelDiscoveryFailureKind.AUTHENTICATION -> credentialFailureText(failure.httpStatus)
        ModelDiscoveryFailureKind.CREDENTIAL_CONFIRMATION -> "请确认 API 地址"
        ModelDiscoveryFailureKind.RATE_LIMITED -> "请求过于频繁，请稍后再试"
        ModelDiscoveryFailureKind.UNREACHABLE -> "无法连接到模型服务"
        ModelDiscoveryFailureKind.INVALID_RESPONSE -> "模型列表不可识别"
        ModelDiscoveryFailureKind.SERVICE -> failure.httpStatus?.let { "模型服务暂时不可用（HTTP $it）" }
            ?: "模型服务暂时不可用"
    }
}

private fun Throwable.userMessage(): String = when (this) {
    is GatewayException.Authentication -> credentialFailureText(null)
    is GatewayException.AuthenticationFailure -> credentialFailureText(status)
    is GatewayException.RateLimited -> "请求过于频繁，请稍后再试"
    is GatewayException.HttpFailure -> when (status) {
        404 -> "API 地址或协议不匹配"
        else -> providerHttpFailureText(status, diagnostic)
    }
    is GatewayException.Network -> "无法连接到模型服务"
    is GatewayException.Security -> "API 地址未获授权"
    is GatewayException.Configuration -> when {
        message == "Connection changed during save" -> "连接已更改，请返回详情后重试"
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

internal fun ConnectionEditorState.hasChanges(stored: StoredConnection?): Boolean =
    credentialInput.isNotBlank() || confirmCredentialReuse ||
        (if (stored == null) draft != initialDraft else !stored.matches(draft))

internal fun ConnectionEditorState.canUseSavedConnection(stored: StoredConnection?): Boolean =
    stored != null && !saving && !hasChanges(stored)

private fun StoredConnection.matches(draft: ConnectionDraft): Boolean =
    name == draft.name.trim() &&
        templateId == draft.templateId &&
        protocol == draft.protocol &&
        apiAddress == draft.apiAddress.trim().trimEnd('/') &&
        selectedModel == draft.selectedModel.trim() &&
        modelTokenLimitOverrides[selectedModel]?.contextTokens?.toString().orEmpty() ==
        draft.contextTokenLimitOverride.trim() &&
        modelTokenLimitOverrides[selectedModel]?.outputTokens?.toString().orEmpty() ==
        draft.outputTokenLimitOverride.trim()
