package io.github.zvensmoluya.tavernplayer.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.content.CompatibilityDiagnostic
import io.github.zvensmoluya.tavernplayer.conversation.ConversationRecord
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

data class CharacterLibraryUiState(
    val characters: List<CharacterAsset> = emptyList(),
    val conversations: List<ConversationRecord> = emptyList(),
    val persona: Persona = PersonaRepository.defaultPersona(),
    val selectedCharacterId: String? = null,
    val importing: Boolean = false,
    val importDiagnostics: List<CompatibilityDiagnostic> = emptyList(),
    val message: String? = null,
    val openConversationId: String? = null,
) {
    val selectedCharacter: CharacterAsset?
        get() = characters.firstOrNull { it.id == selectedCharacterId }

    fun conversationsFor(characterId: String): List<ConversationRecord> =
        conversations.filter { it.character.assetId == characterId }
}

class CharacterLibraryViewModel(
    private val characterRepository: CharacterRepository,
    private val conversationRepository: ConversationRepository,
    private val defaultPersonaSource: DefaultPersonaSource,
    private val presetRepository: PresetRepository,
    private val shelfTransferReceiver: ShelfTransferReceiver,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CharacterLibraryUiState())
    val uiState: StateFlow<CharacterLibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                characterRepository.characters,
                conversationRepository.conversations,
                defaultPersonaSource.persona,
            ) { characters, conversations, persona -> Triple(characters, conversations, persona) }
                .collect { (characters, conversations, persona) ->
                    _uiState.update { current ->
                        current.copy(
                            characters = characters,
                            conversations = conversations,
                            persona = persona,
                            selectedCharacterId = current.selectedCharacterId.takeIf { id ->
                                characters.any { it.id == id }
                            },
                        )
                    }
                }
        }
    }

    fun import(bytes: ByteArray, fileName: String) {
        if (_uiState.value.importing) return
        _uiState.update { it.copy(importing = true, message = null, importDiagnostics = emptyList()) }
        viewModelScope.launch {
            runCatching { characterRepository.import(bytes, fileName) }
                .onSuccess { result ->
                    when (result) {
                        is CharacterSaveResult.Saved -> _uiState.update {
                            it.copy(
                                importing = false,
                                selectedCharacterId = result.character.id,
                                importDiagnostics = result.diagnostics,
                                message = if (result.duplicate) "这张角色卡已经导入" else "已导入 ${result.character.name}",
                            )
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
        if (_uiState.value.importing) return
        _uiState.update { it.copy(importing = true, message = "正在从 Tavern Shelf 接收…", importDiagnostics = emptyList()) }
        viewModelScope.launch {
            runCatching { shelfTransferReceiver.receive(transferUrl) }
                .onSuccess { transfer ->
                    when (transfer.manifest.kind) {
                        "character" -> importShelfCharacter(transfer.sourceBytes, transfer.manifest.filename)
                        "preset" -> importShelfPreset(transfer.sourceBytes, transfer.manifest.filename)
                        "worldbook" -> _uiState.update {
                            it.copy(importing = false, message = "已识别世界书；当前版本暂不支持独立世界书导入")
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
            is CharacterSaveResult.Saved -> _uiState.update {
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
        when (val result = presetRepository.importPreset(bytes, fileName)) {
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
        _uiState.update { it.copy(selectedCharacterId = characterId, message = null) }
    }

    fun reportMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    fun createConversation(characterId: String) {
        val character = characterRepository.get(characterId) ?: return
        val preset = presetRepository.captureActive()
        viewModelScope.launch {
            val persona = defaultPersonaSource.captureDefault()
            runCatching { conversationRepository.create(character, persona, preset) }
                .onSuccess { record -> _uiState.update { it.copy(openConversationId = record.id, message = null) } }
                .onFailure { error -> _uiState.update { it.copy(message = error.message ?: "无法创建对话") } }
        }
    }

    fun openConversation(conversationId: String) {
        if (conversationRepository.get(conversationId) != null) {
            _uiState.update { it.copy(openConversationId = conversationId) }
        }
    }

    fun consumeOpenConversation() {
        _uiState.update { it.copy(openConversationId = null) }
    }

    fun avatarPath(characterId: String): String? = characterRepository.avatarFile(characterId)?.absolutePath

    class Factory(
        private val characterRepository: CharacterRepository,
        private val conversationRepository: ConversationRepository,
        private val defaultPersonaSource: DefaultPersonaSource,
        private val presetRepository: PresetRepository,
        private val shelfTransferReceiver: ShelfTransferReceiver,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CharacterLibraryViewModel(
                characterRepository,
                conversationRepository,
                defaultPersonaSource,
                presetRepository,
                shelfTransferReceiver,
            ) as T
    }
}
