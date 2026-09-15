package io.github.zvensmoluya.tavernplayer.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.content.CompatibilityDiagnostic
import io.github.zvensmoluya.tavernplayer.content.NativeCompilationResult
import io.github.zvensmoluya.tavernplayer.connections.ConnectionRepository
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.tavernplayer.conversation.storage.ConversationSummary
import io.github.zvensmoluya.tavernplayer.conversation.ConversationRepository
import io.github.zvensmoluya.tavernplayer.conversation.Persona
import io.github.zvensmoluya.tavernplayer.personas.DefaultPersonaSource
import io.github.zvensmoluya.tavernplayer.personas.PersonaRepository
import io.github.zvensmoluya.tavernplayer.presets.PresetLibraryImportResult
import io.github.zvensmoluya.tavernplayer.presets.PresetRepository
import io.github.zvensmoluya.tavernplayer.transfer.ShelfTransferReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class CharacterLibraryUiState(
    val characters: List<CharacterAsset> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
    val persona: Persona = PersonaRepository.defaultPersona(),
    val selectedCharacterId: String? = null,
    val importing: Boolean = false,
    val compilingCharacterId: String? = null,
    val compilationSaving: Boolean = false,
    val compilationConnections: List<StoredConnection> = emptyList(),
    val compilationConnectionId: String? = null,
    val importDiagnostics: List<CompatibilityDiagnostic> = emptyList(),
    val message: String? = null,
    val openConversationId: String? = null,
    val imageStates: Map<String, CharacterImageState> = emptyMap(),
    val imageWorkingIds: Set<String> = emptySet(),
    val imageErrors: Map<String, String> = emptyMap(),
) {
    val busy: Boolean get() = importing || compilingCharacterId != null
    val selectedCharacter: CharacterAsset?
        get() = characters.firstOrNull { it.id == selectedCharacterId }

    fun conversationsFor(characterId: String): List<ConversationSummary> =
        conversations.filter { it.assetId == characterId }
}

class CharacterLibraryViewModel(
    private val characterRepository: CharacterRepository,
    private val conversationRepository: () -> ConversationRepository,
    private val defaultPersonaSource: DefaultPersonaSource,
    private val presetRepository: () -> PresetRepository,
    private val shelfTransferReceiver: ShelfTransferReceiver,
    private val compilationService: (() -> NativeCompilationService)? = null,
    private val connectionRepository: (() -> ConnectionRepository)? = null,
    private val worldBookRepository: (() -> io.github.zvensmoluya.tavernplayer.worldbooks.WorldBookRepository)? = null,
) : ViewModel() {
    constructor(
        characterRepository: CharacterRepository,
        conversationRepository: ConversationRepository,
        defaultPersonaSource: DefaultPersonaSource,
        presetRepository: PresetRepository,
        shelfTransferReceiver: ShelfTransferReceiver,
        compilationService: NativeCompilationService? = null,
        connectionRepository: ConnectionRepository? = null,
    ) : this(
        characterRepository,
        { conversationRepository },
        defaultPersonaSource,
        { presetRepository },
        shelfTransferReceiver,
        compilationService?.let { service -> { service } },
        connectionRepository?.let { repository -> { repository } },
    )
    private var compilationJob: Job? = null
    private var conversationJob: Job? = null
    private var connectionJob: Job? = null
    private val imageJobs = mutableMapOf<String, Job>()
    private val _uiState = MutableStateFlow(CharacterLibraryUiState())
    val uiState: StateFlow<CharacterLibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            characterRepository.initialize()
            defaultPersonaSource.initialize()
        }
        viewModelScope.launch {
            characterRepository.imageResources.states.collect { states ->
                _uiState.update { it.copy(imageStates = states) }
            }
        }
        viewModelScope.launch {
            combine(characterRepository.characters, defaultPersonaSource.persona) { characters, persona ->
                characters to persona
            }.collect { (characters, persona) ->
                _uiState.update { current ->
                    current.copy(
                        characters = characters,
                        persona = persona,
                        selectedCharacterId = current.selectedCharacterId.takeIf { id ->
                            characters.any { it.id == id }
                        },
                    )
                }
            }
        }
        loadConversations()
    }

    // The library surface shows a conversation count on every card, so conversations belong to the
    // first screen even though the repository behind them is still created on demand.
    private fun loadConversations() {
        if (conversationJob?.isActive != true) conversationJob = viewModelScope.launch {
            try {
                val repository = withContext(kotlinx.coroutines.Dispatchers.IO) { conversationRepository() }
                repository.conversations.collect { conversations ->
                    _uiState.update { it.copy(conversations = conversations) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(message = "对话读取失败：${error.message ?: "请检查本地存储"}") }
            }
        }
    }

    private fun loadCompilationConnections() {
        val connectionSource = connectionRepository ?: return
        if (connectionJob == null) connectionJob = viewModelScope.launch {
            val repository = withContext(kotlinx.coroutines.Dispatchers.IO) { connectionSource() }
            repository.state.collect { state ->
                val choices = state.connections.filter { it.selectedModel.isNotBlank() }
                _uiState.update { current -> current.copy(
                    compilationConnections = choices,
                    compilationConnectionId = current.compilationConnectionId?.takeIf { id -> choices.any { it.id == id } }
                        ?: state.recentConnectionId?.takeIf { id -> choices.any { it.id == id } } ?: choices.firstOrNull()?.id,
                ) }
            }
        }
    }

    private fun loadDetailDependencies() {
        loadConversations()
        loadCompilationConnections()
    }

    fun import(bytes: ByteArray, fileName: String) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(importing = true, message = null, importDiagnostics = emptyList()) }
        viewModelScope.launch {
            runCatching { characterRepository.import(bytes, fileName) }
                .onSuccess { result ->
                    when (result) {
                        is CharacterSaveResult.Saved -> {
                            loadDetailDependencies()
                            _uiState.update {
                                it.copy(
                                    importing = false,
                                    selectedCharacterId = result.character.id,
                                    importDiagnostics = result.diagnostics,
                                    message = if (result.duplicate) "这张角色卡已经导入" else "已导入 ${result.character.name}",
                                )
                            }
                        }
                        is CharacterSaveResult.Rejected -> _uiState.update {
                            it.copy(
                                importing = false,
                                importDiagnostics = result.diagnostics,
                                message = result.diagnostics.firstOrNull()?.message ?: "角色卡无法导入",
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(importing = false, message = error.message ?: "角色卡导入失败") }
                }
        }
    }

    fun importFromShelf(transferUrl: String) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(importing = true, message = "正在从 Tavern Shelf 接收…", importDiagnostics = emptyList()) }
        viewModelScope.launch {
            runCatching { shelfTransferReceiver.receive(transferUrl) }
                .onSuccess { transfer ->
                    when (transfer.manifest.kind) {
                        "character" -> importShelfCharacter(transfer.sourceBytes, transfer.manifest.filename)
                        "preset" -> importShelfPreset(transfer.sourceBytes, transfer.manifest.filename)
                        "worldbook" -> {
                            val book = requireNotNull(worldBookRepository) { "世界书仓库不可用" }().import(transfer.sourceBytes, transfer.manifest.filename)
                            _uiState.update { it.copy(importing = false, importDiagnostics = book.diagnostics,
                                message = "已导入 ${book.book.name}，请在全局世界书中启用") }
                        }
                        else -> _uiState.update {
                            it.copy(importing = false, message = "未知的 Shelf 资源类型：${transfer.manifest.kind}")
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(importing = false, message = error.message ?: "Shelf 资源接收失败") }
                }
        }
    }

    private suspend fun importShelfCharacter(bytes: ByteArray, fileName: String) {
        when (val result = characterRepository.import(bytes, fileName)) {
            is CharacterSaveResult.Saved -> {
                loadDetailDependencies()
                _uiState.update {
                    it.copy(
                        importing = false,
                        selectedCharacterId = result.character.id,
                        importDiagnostics = result.diagnostics,
                        message = if (result.duplicate) {
                            "Shelf 中的这张角色卡已经导入"
                        } else {
                            "已从 Shelf 导入 ${result.character.name}"
                        },
                    )
                }
            }
            is CharacterSaveResult.Rejected -> _uiState.update {
                it.copy(
                    importing = false,
                    importDiagnostics = result.diagnostics,
                    message = result.diagnostics.firstOrNull()?.message ?: "Shelf 角色卡无法导入",
                )
            }
        }
    }

    fun installNativeAdaptation(characterId: String, bytes: ByteArray) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(importing = true, message = null) }
        viewModelScope.launch {
            try {
                require(bytes.size <= 1024 * 1024) { "适配文件超过 1 MiB" }
                val adaptation = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    kotlinx.serialization.json.Json.decodeFromString<io.github.zvensmoluya.tavernplayer.content.NativeAdaptation>(bytes.decodeToString())
                }
                val message = when (val result = characterRepository.installNativeAdaptation(characterId, adaptation)) {
                    is NativeAdaptationInstallResult.Installed -> "原生适配已安装，开始新对话即可使用"
                    is NativeAdaptationInstallResult.Rejected -> "适配未安装：${result.issues.firstOrNull()?.message.orEmpty()}"
                }
                _uiState.update { it.copy(message = message) }
            } catch (error: Exception) {
                _uiState.update { it.copy(message = "适配文件无法导入：${error.message.orEmpty().take(200)}") }
            } finally {
                _uiState.update { it.copy(importing = false) }
            }
        }
    }

    private suspend fun importShelfPreset(bytes: ByteArray, fileName: String) {
        when (val result = withContext(kotlinx.coroutines.Dispatchers.IO) { presetRepository().importPreset(bytes, fileName) }) {
            is PresetLibraryImportResult.Saved -> _uiState.update {
                it.copy(
                    importing = false,
                    importDiagnostics = result.diagnostics,
                    message = if (result.duplicate) {
                        "Shelf 中的这个 Preset 已经导入"
                    } else {
                        "已从 Shelf 导入 Preset：${result.preset.name}"
                    },
                )
            }
            is PresetLibraryImportResult.Rejected -> _uiState.update {
                it.copy(
                    importing = false,
                    importDiagnostics = result.diagnostics,
                    message = result.diagnostics.firstOrNull()?.message ?: "Shelf Preset 无法导入",
                )
            }
        }
    }

    fun selectCharacter(characterId: String?) {
        if (characterId != null) loadDetailDependencies()
        _uiState.update { it.copy(selectedCharacterId = characterId, message = null) }
    }

    fun loadImages(characterId: String) = runImageJob(characterId) { characterRepository.imageResources.load(characterId) }
    fun prepareImages(characterId: String) = runImageJob(characterId) { characterRepository.imageResources.prepare(characterId) }
    fun cancelImages(characterId: String) { imageJobs[characterId]?.cancel() }
    fun imagePath(characterId: String, entry: CharacterImageEntry): String? = characterRepository.imageResources.file(characterId, entry)?.absolutePath

    private fun runImageJob(characterId: String, action: suspend () -> Unit) {
        if (imageJobs[characterId]?.isActive == true) return
        _uiState.update { it.copy(imageWorkingIds = it.imageWorkingIds + characterId, imageErrors = it.imageErrors - characterId) }
        imageJobs[characterId] = viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                _uiState.update { it.copy(imageErrors = it.imageErrors + (characterId to (error.message?.take(160) ?: "角色资源准备失败"))) }
            } finally {
                _uiState.update { it.copy(imageWorkingIds = it.imageWorkingIds - characterId) }
                imageJobs.remove(characterId)
            }
        }
    }

    fun reportMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    fun selectCompilationConnection(id: String) {
        if (_uiState.value.busy || _uiState.value.compilationConnections.none { it.id == id }) return
        _uiState.update { it.copy(compilationConnectionId = id) }
    }

    fun compileNativeAdaptation(characterId: String) {
        if (_uiState.value.busy) return
        val serviceSource = compilationService ?: return reportMessage("自动适配尚未配置")
        val character = characterRepository.get(characterId) ?: return
        val connection = _uiState.value.compilationConnections.singleOrNull { it.id == _uiState.value.compilationConnectionId }
            ?: return reportMessage("请先配置并选择用于适配的模型")
        _uiState.update { it.copy(compilingCharacterId = characterId, message = "正在准备适配…") }
        compilationJob = viewModelScope.launch {
            var installed = false
            try {
                val service = withContext(kotlinx.coroutines.Dispatchers.IO) { serviceSource() }
                val attempt = service.compile(character, characterRepository.availableAssetIds(characterId), connection,
                    onProgress = { progress -> _uiState.update { it.copy(message = progress) } })
                currentCoroutineContext().ensureActive()
                when (val result = attempt.result) {
                    is NativeCompilationResult.Rejected -> reportMessage(
                        "适配未安装：${result.issues.take(3).joinToString("；") { it.message }}",
                    )
                    is NativeCompilationResult.Ready -> withContext(NonCancellable) {
                        _uiState.update { it.copy(compilationSaving = true, message = "正在保存适配…") }
                        when (val installation = characterRepository.installNativeAdaptation(characterId, result.adaptation)) {
                            is NativeAdaptationInstallResult.Installed -> {
                                installed = true
                                reportMessage("适配已准备好，查看下方说明后即可开始新对话")
                            }
                            is NativeAdaptationInstallResult.Rejected -> reportMessage("适配未安装：${installation.issues.firstOrNull()?.message.orEmpty()}")
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (!installed) reportMessage("适配已停止，已有角色与适配保留")
                throw cancelled
            } catch (error: Exception) {
                val reason = when (error) {
                    is GatewayException.Authentication, is GatewayException.AuthenticationFailure -> "模型连接认证失败"
                    is GatewayException.Security -> "请在模型连接中确认凭据授权"
                    is GatewayException.HttpFailure -> "模型服务返回 HTTP ${error.status}"
                    is GatewayException.Network -> "网络连接失败或超时"
                    is GatewayException.Configuration -> "请检查模型连接配置"
                    is GatewayException.Protocol -> "模型响应协议错误"
                    is GatewayException.ResponseTooLarge -> "模型响应超过大小限制"
                    is IllegalArgumentException -> error.message ?: "输入或输出不符合适配要求"
                    else -> "无法完成适配，请重试"
                }
                reportMessage("适配未安装：$reason")
            } finally {
                _uiState.update { it.copy(compilingCharacterId = null, compilationSaving = false) }
            }
        }
    }

    fun cancelCompilation() {
        if (!_uiState.value.compilationSaving) compilationJob?.cancel()
    }

    fun createConversation(characterId: String, native: Boolean = false) {
        if (_uiState.value.busy) return
        val character = characterRepository.get(characterId) ?: return
        viewModelScope.launch {
            val persona = defaultPersonaSource.captureDefault()
            runCatching {
                val dependencies = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    conversationRepository() to presetRepository().captureActive()
                }
                dependencies.first.create(character, persona, dependencies.second,
                    if (native) io.github.zvensmoluya.tavernplayer.conversation.ConversationExecutionMode.LEGACY_NATIVE
                    else io.github.zvensmoluya.tavernplayer.conversation.ConversationExecutionMode.BROWSER)
            }
                .onSuccess { record -> _uiState.update { it.copy(openConversationId = record.id, message = null) } }
                .onFailure { error -> _uiState.update { it.copy(message = error.message ?: "无法创建对话") } }
        }
    }

    fun openConversation(conversationId: String) {
        viewModelScope.launch {
            val repository = withContext(kotlinx.coroutines.Dispatchers.IO) { conversationRepository() }
            if (repository.contains(conversationId)) {
                _uiState.update { it.copy(openConversationId = conversationId) }
            }
        }
    }

    fun consumeOpenConversation() {
        _uiState.update { it.copy(openConversationId = null) }
    }

    fun avatarPath(characterId: String): String? = characterRepository.avatarFile(characterId)?.absolutePath

    fun assetPath(characterId: String, assetId: String): String? =
        characterRepository.assetFile(characterId, assetId)?.absolutePath

    class Factory(
        private val characterRepository: CharacterRepository,
        private val conversationRepository: () -> ConversationRepository,
        private val defaultPersonaSource: DefaultPersonaSource,
        private val presetRepository: () -> PresetRepository,
        private val shelfTransferReceiver: ShelfTransferReceiver,
        private val compilationService: (() -> NativeCompilationService)? = null,
        private val connectionRepository: (() -> ConnectionRepository)? = null,
        private val worldBookRepository: (() -> io.github.zvensmoluya.tavernplayer.worldbooks.WorldBookRepository)? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CharacterLibraryViewModel(
                characterRepository,
                conversationRepository,
                defaultPersonaSource,
                presetRepository,
                shelfTransferReceiver,
                compilationService,
                connectionRepository,
                worldBookRepository,
            ) as T
    }
}
