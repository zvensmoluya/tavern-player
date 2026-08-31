package io.github.zvensmoluya.tavernplayer.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.content.CompatibilityDiagnostic
import io.github.zvensmoluya.tavernplayer.conversation.ConversationRecord
import io.github.zvensmoluya.tavernplayer.conversation.ConversationRepository
import io.github.zvensmoluya.tavernplayer.conversation.Persona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CharacterLibraryUiState(
    val characters: List<CharacterAsset> = emptyList(),
    val conversations: List<ConversationRecord> = emptyList(),
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
    private val defaultPersona: Persona,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CharacterLibraryUiState())
    val uiState: StateFlow<CharacterLibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(characterRepository.characters, conversationRepository.conversations) { characters, conversations ->
                characters to conversations
            }.collect { (characters, conversations) ->
                _uiState.update { current ->
                    current.copy(
                        characters = characters,
                        conversations = conversations,
                        selectedCharacterId = current.selectedCharacterId.takeIf { id -> characters.any { it.id == id } },
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

    fun selectCharacter(characterId: String?) {
        _uiState.update { it.copy(selectedCharacterId = characterId, message = null) }
    }

    fun reportMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    fun createConversation(characterId: String) {
        val character = characterRepository.get(characterId) ?: return
        viewModelScope.launch {
            runCatching { conversationRepository.create(character, defaultPersona) }
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
        private val defaultPersona: Persona,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CharacterLibraryViewModel(characterRepository, conversationRepository, defaultPersona) as T
    }
}
