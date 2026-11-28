package io.github.zvensmoluya.tavernplayer.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.content.CompatibilityDiagnostic
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

data class CharacterLibraryUiState(
    val layout: CharacterLibraryLayout = CharacterLibraryLayout.GRID,
    val showChatAvatars: Boolean = false,
    val openingConversation: Boolean = false,
    val loadingCharacters: Boolean = true,
    val loadingConversations: Boolean = true,
    val characters: List<CharacterAsset> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
    val persona: Persona = PersonaRepository.defaultPersona(),
    val selectedCharacterId: String? = null,
    val importing: Boolean = false,
    val importDiagnostics: List<CompatibilityDiagnostic> = emptyList(),
    val message: String? = null,
    val openConversationId: String? = null,
    val imageStates: Map<String, CharacterImageState> = emptyMap(),
    val imageWorkingIds: Set<String> = emptySet(),
    val imageErrors: Map<String, String> = emptyMap(),
) {
    val initialLoading: Boolean get() = loadingCharacters || loadingConversations
    val busy: Boolean get() = importing || openingConversation
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
    private val worldBookRepository: (() -> io.github.zvensmoluya.tavernplayer.worldbooks.WorldBookRepository)? = null,
    private val preferences: CharacterLibraryPreferences? = null,
) : ViewModel() {
    constructor(
        characterRepository: CharacterRepository,
        conversationRepository: ConversationRepository,
        defaultPersonaSource: DefaultPersonaSource,
        presetRepository: PresetRepository,
        shelfTransferReceiver: ShelfTransferReceiver,
    ) : this(
        characterRepository,
        { conversationRepository },
        defaultPersonaSource,
        { presetRepository },
        shelfTransferReceiver,
    )
    private var conversationJob: Job? = null
    private val imageJobs = mutableMapOf<String, Job>()
    private val _uiState = MutableStateFlow(CharacterLibraryUiState())
    val uiState: StateFlow<CharacterLibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                preferences?.layout?.first()?.let { layout -> _uiState.update { it.copy(layout = layout) } }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(message = "无法读取布局偏好，暂用两列视图") }
            }
            try {
                preferences?.showChatAvatars?.first()?.let { show -> _uiState.update { it.copy(showChatAvatars = show) } }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: java.io.IOException) {
                _uiState.update { it.copy(message = "无法读取聊天头像偏好") }
            }
            try {
                characterRepository.initialize()
                defaultPersonaSource.initialize()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(message = "角色库读取失败：${error.message ?: "请检查本地存储"}") }
            } finally {
                _uiState.update { it.copy(
                    characters = characterRepository.characters.value,
                    persona = defaultPersonaSource.persona.value,
                    loadingCharacters = false,
                ) }
            }
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

    fun setShowChatAvatars(show: Boolean) {
        viewModelScope.launch {
            try {
                preferences?.setShowChatAvatars(show)
                _uiState.update { it.copy(showChatAvatars = show) }
            } catch (_: java.io.IOException) {
                _uiState.update { it.copy(message = "聊天头像设置保存失败") }
            }
        }
    }

    suspend fun titleSuggestions(id: String) = conversationRepository().titleSuggestions(id)
    suspend fun renameConversation(id: String, title: String) = conversationRepository().rename(id, title)

    fun setLayout(layout: CharacterLibraryLayout) {
        _uiState.update { it.copy(layout = layout) }
        viewModelScope.launch {
            try { preferences?.setLayout(layout)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(message = "布局已切换，但未能保存，下次启动可能恢复默认") }
            }
        }
    }

    // Global conversation history is the first screen; subscribe to summaries without opening full records.
    private fun loadConversations() {
        if (conversationJob?.isActive != true) conversationJob = viewModelScope.launch {
            try {
                val repository = withContext(kotlinx.coroutines.Dispatchers.IO) { conversationRepository() }
                repository.conversations.collect { conversations ->
                    _uiState.update { it.copy(conversations = conversations, loadingConversations = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(loadingConversations = false, message = "对话读取失败：${error.message ?: "请检查本地存储"}") }
            }
        }
    }

    fun import(bytes: ByteArray, fileName: String) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(importing = true, message = null, importDiagnostics = emptyList()) }
        viewModelScope.launch {
            runCatching { characterRepository.import(bytes, fileName) }
                .onSuccess { result ->
                    when (result) {
                        is CharacterSaveResult.Saved -> {
                            loadConversations()
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
                loadConversations()
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
        if (characterId != null) loadConversations()
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

    fun createConversation(characterId: String) {
        if (_uiState.value.busy) return
        val character = characterRepository.get(characterId) ?: return
        _uiState.update { it.copy(openingConversation = true) }
        viewModelScope.launch {
            val persona = defaultPersonaSource.captureDefault()
            runCatching {
                val dependencies = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    conversationRepository() to presetRepository().captureActive()
                }
                dependencies.first.create(character, persona, dependencies.second)
            }
                .onSuccess { record -> _uiState.update { it.copy(openConversationId = record.id, openingConversation = false, message = null) } }
                .onFailure { error -> _uiState.update { it.copy(openingConversation = false, message = error.message ?: "无法创建对话") } }
        }
    }

    fun openConversation(conversationId: String) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(openingConversation = true, message = null) }
        viewModelScope.launch {
            try {
                val repository = withContext(kotlinx.coroutines.Dispatchers.IO) { conversationRepository() }
                check(repository.contains(conversationId)) { "这场对话已不存在" }
                _uiState.update { it.copy(openConversationId = conversationId, openingConversation = false) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(openingConversation = false, message = error.message ?: "无法打开对话") }
            }
        }
    }

    fun consumeOpenConversation() {
        _uiState.update { it.copy(openConversationId = null) }
    }

    fun avatarPath(characterId: String): String? = characterRepository.avatarFile(characterId)?.absolutePath


    class Factory(
        private val characterRepository: CharacterRepository,
        private val conversationRepository: () -> ConversationRepository,
        private val defaultPersonaSource: DefaultPersonaSource,
        private val presetRepository: () -> PresetRepository,
        private val shelfTransferReceiver: ShelfTransferReceiver,
        private val worldBookRepository: (() -> io.github.zvensmoluya.tavernplayer.worldbooks.WorldBookRepository)? = null,
        private val preferences: CharacterLibraryPreferences? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CharacterLibraryViewModel(
                characterRepository,
                conversationRepository,
                defaultPersonaSource,
                presetRepository,
                shelfTransferReceiver,
                worldBookRepository,
                preferences,
            ) as T
    }
}
