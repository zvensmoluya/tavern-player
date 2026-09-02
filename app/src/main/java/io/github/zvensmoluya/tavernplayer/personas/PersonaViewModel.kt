package io.github.zvensmoluya.tavernplayer.personas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zvensmoluya.tavernplayer.conversation.Persona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PersonaUiState(
    val saved: Persona = PersonaRepository.defaultPersona(),
    val name: String = saved.name,
    val description: String = saved.description,
    val avatar: String? = saved.avatar,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val dirty: Boolean
        get() = name != saved.name || description != saved.description || avatar != saved.avatar
}

class PersonaViewModel(
    private val repository: PersonaRepository,
) : ViewModel() {
    private val initial = repository.persona.value
    private val _uiState = MutableStateFlow(PersonaUiState(saved = initial, name = initial.name, description = initial.description, avatar = initial.avatar))
    val uiState: StateFlow<PersonaUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.persona.collect { persona ->
                _uiState.update { current ->
                    if (current.dirty || current.busy) current.copy(saved = persona) else current.from(persona)
                }
            }
        }
    }

    fun startEditing() {
        _uiState.update { it.from(repository.persona.value).copy(message = null) }
    }

    fun cancelEditing() {
        _uiState.update { it.from(repository.persona.value).copy(message = null) }
    }

    fun updateName(value: String) {
        _uiState.update { it.copy(name = value, message = null) }
    }

    fun updateDescription(value: String) {
        _uiState.update { it.copy(description = value, message = null) }
    }

    fun updateAvatar(value: String?) {
        _uiState.update { it.copy(avatar = value, message = null) }
    }

    fun save() {
        val state = _uiState.value
        if (state.busy || !state.dirty) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            runCatching { repository.save(state.name, state.description, state.avatar) }
                .onSuccess { saved -> _uiState.value = _uiState.value.from(saved).copy(message = "身份已保存") }
                .onFailure { error ->
                    _uiState.update { it.copy(busy = false, message = error.message ?: "无法保存身份") }
                }
        }
    }

    class Factory(private val repository: PersonaRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PersonaViewModel(repository) as T
    }
}

private fun PersonaUiState.from(persona: Persona): PersonaUiState = copy(
    saved = persona,
    name = persona.name,
    description = persona.description,
    avatar = persona.avatar,
    busy = false,
)
