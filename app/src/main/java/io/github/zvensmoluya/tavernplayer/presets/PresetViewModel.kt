package io.github.zvensmoluya.tavernplayer.presets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zvensmoluya.tavernplayer.content.CompatibilityDiagnostic
import io.github.zvensmoluya.tavernplayer.content.PresetAsset
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationParameter
import io.github.zvensmoluya.tavernplayer.content.PresetPromptDefinition
import io.github.zvensmoluya.tavernplayer.content.PresetPromptOrderEntry
import io.github.zvensmoluya.tavernplayer.content.PresetReasoningEffort
import io.github.zvensmoluya.tavernplayer.content.PresetVerbosity
import io.github.zvensmoluya.tavernplayer.content.RegexDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PresetUiState(
    val presets: List<PresetAsset> = emptyList(),
    val activePresetId: String = "",
    val selectedPresetId: String? = null,
    val draft: PresetAsset? = null,
    val dirty: Boolean = false,
    val busy: Boolean = false,
    val importDiagnostics: List<CompatibilityDiagnostic> = emptyList(),
    val message: String? = null,
) {
    val activePreset: PresetAsset?
        get() = presets.firstOrNull { it.id == activePresetId }
}

class PresetViewModel(
    private val repository: PresetRepository,
) : ViewModel() {
    private val initial = repository.library.value
    private val _uiState = MutableStateFlow(
        PresetUiState(
            presets = initial.presets,
            activePresetId = initial.activePresetId,
        ),
    )
    val uiState: StateFlow<PresetUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.library.collect { library ->
                _uiState.update { current ->
                    val selected = current.selectedPresetId?.takeIf { id -> library.presets.any { it.id == id } }
                    val refreshedDraft = when {
                        selected == null -> null
                        current.dirty -> current.draft
                        else -> library.presets.firstOrNull { it.id == selected }?.snapshot()
                    }
                    current.copy(
                        presets = library.presets,
                        activePresetId = library.activePresetId,
                        selectedPresetId = selected,
                        draft = refreshedDraft,
                    )
                }
            }
        }
    }

    fun openEditor(presetId: String) {
        val preset = repository.get(presetId) ?: return
        _uiState.update {
            it.copy(
                selectedPresetId = preset.id,
                draft = preset,
                dirty = false,
                message = null,
            )
        }
    }

    fun cancelEditor() {
        _uiState.update { it.copy(selectedPresetId = null, draft = null, dirty = false, message = null) }
    }

    fun updateDraft(transform: (PresetAsset) -> PresetAsset) {
        _uiState.update { state ->
            val draft = state.draft ?: return@update state
            if (draft.builtIn) return@update state
            state.copy(draft = transform(draft), dirty = true, message = null)
        }
    }

    fun updatePrompt(identifier: String, transform: (PresetPromptDefinition) -> PresetPromptDefinition) {
        updateDraft { draft ->
            draft.copy(prompts = draft.prompts.map { if (it.identifier == identifier) transform(it) else it })
        }
    }

    fun togglePromptInOrder(identifier: String) {
        updateDraft { draft ->
            val present = draft.promptOrder.any { it.identifier == identifier }
            draft.copy(
                promptOrder = if (present) {
                    draft.promptOrder.filterNot { it.identifier == identifier }
                } else {
                    draft.promptOrder + PresetPromptOrderEntry(identifier)
                },
            )
        }
    }

    fun setPromptEnabled(identifier: String, enabled: Boolean) {
        updateDraft { draft ->
            draft.copy(
                promptOrder = if (draft.promptOrder.any { it.identifier == identifier }) {
                    draft.promptOrder.map {
                        if (it.identifier == identifier) it.copy(enabled = enabled) else it
                    }
                } else if (enabled) {
                    draft.promptOrder + PresetPromptOrderEntry(identifier, enabled = true)
                } else {
                    draft.promptOrder
                },
            )
        }
    }

    fun movePrompt(identifier: String, delta: Int) {
        updateDraft { draft ->
            val current = draft.promptOrder.indexOfFirst { it.identifier == identifier }
            if (current < 0) return@updateDraft draft
            val destination = (current + delta).coerceIn(0, draft.promptOrder.lastIndex)
            if (destination == current) return@updateDraft draft
            val reordered = draft.promptOrder.toMutableList()
            val item = reordered.removeAt(current)
            reordered.add(destination, item)
            draft.copy(promptOrder = reordered)
        }
    }

    fun updateRegex(id: String, transform: (RegexDefinition) -> RegexDefinition) {
        updateDraft { draft ->
            draft.copy(regexScripts = draft.regexScripts.map { if (it.id == id) transform(it) else it })
        }
    }

    fun setGenerationParameterEnabled(parameter: PresetGenerationParameter, enabled: Boolean) {
        updateDraft { draft ->
            var settings = draft.generationSettings.withEnabled(parameter, enabled)
            if (enabled) {
                settings = when (parameter) {
                    PresetGenerationParameter.OUTPUT_LIMIT -> settings
                    PresetGenerationParameter.TEMPERATURE -> if (settings.temperature == null) {
                        settings.copy(temperature = 1.0)
                    } else settings
                    PresetGenerationParameter.TOP_P -> if (settings.topP == null) {
                        settings.copy(topP = 1.0)
                    } else settings
                    PresetGenerationParameter.TOP_K -> if (settings.topK?.let { it <= 0 } != false) {
                        settings.copy(topK = 40)
                    } else settings
                    PresetGenerationParameter.TOP_A -> if (settings.topA == null) {
                        settings.copy(topA = 0.0)
                    } else settings
                    PresetGenerationParameter.MIN_P -> if (settings.minP == null) {
                        settings.copy(minP = 0.0)
                    } else settings
                    PresetGenerationParameter.REPETITION_PENALTY -> if (settings.repetitionPenalty == null) {
                        settings.copy(repetitionPenalty = 1.0)
                    } else settings
                    PresetGenerationParameter.FREQUENCY_PENALTY -> if (settings.frequencyPenalty == null) {
                        settings.copy(frequencyPenalty = 0.0)
                    } else settings
                    PresetGenerationParameter.PRESENCE_PENALTY -> if (settings.presencePenalty == null) {
                        settings.copy(presencePenalty = 0.0)
                    } else settings
                    PresetGenerationParameter.SEED -> if (settings.seed == null) settings.copy(seed = 0) else settings
                    PresetGenerationParameter.REASONING_EFFORT -> {
                        if (settings.reasoningEffort == PresetReasoningEffort.AUTO) {
                            settings.copy(reasoningEffort = PresetReasoningEffort.MEDIUM)
                        } else settings
                    }
                    PresetGenerationParameter.VERBOSITY -> if (settings.verbosity == PresetVerbosity.AUTO) {
                        settings.copy(verbosity = PresetVerbosity.MEDIUM)
                    } else settings
                }
            }
            draft.copy(generationSettings = settings)
        }
    }

    fun save() {
        val draft = _uiState.value.draft ?: return
        if (draft.builtIn || _uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            runCatching { repository.save(draft) }
                .onSuccess { saved ->
                    _uiState.update {
                        it.copy(
                            selectedPresetId = saved.id,
                            draft = saved,
                            dirty = false,
                            busy = false,
                            message = "已保存 ${saved.name}",
                        )
                    }
                }
                .onFailure { error -> _uiState.update { it.copy(busy = false, message = error.displayMessage()) } }
        }
    }

    fun activate(presetId: String) {
        if (_uiState.value.busy || presetId == _uiState.value.activePresetId) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            runCatching { repository.activate(presetId) }
                .onSuccess { preset -> _uiState.update { it.copy(busy = false, message = "已切换到 ${preset.name}") } }
                .onFailure { error -> _uiState.update { it.copy(busy = false, message = error.displayMessage()) } }
        }
    }

    fun copy(presetId: String) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            runCatching { repository.copy(presetId) }
                .onSuccess { copied ->
                    _uiState.update {
                        it.copy(
                            selectedPresetId = copied.id,
                            draft = copied,
                            dirty = false,
                            busy = false,
                            message = "已复制 ${copied.name}",
                        )
                    }
                }
                .onFailure { error -> _uiState.update { it.copy(busy = false, message = error.displayMessage()) } }
        }
    }

    fun delete(presetId: String) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            runCatching { repository.delete(presetId) }
                .onSuccess { deleted ->
                    _uiState.update {
                        it.copy(
                            selectedPresetId = null,
                            draft = null,
                            dirty = false,
                            busy = false,
                            message = "已删除 ${deleted.name}",
                        )
                    }
                }
                .onFailure { error -> _uiState.update { it.copy(busy = false, message = error.displayMessage()) } }
        }
    }

    fun importPreset(bytes: ByteArray, fileName: String) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null, importDiagnostics = emptyList()) }
        viewModelScope.launch {
            runCatching { repository.importPreset(bytes, fileName) }
                .onSuccess { result ->
                    when (result) {
                        is PresetLibraryImportResult.Rejected -> _uiState.update {
                            it.copy(
                                busy = false,
                                importDiagnostics = result.diagnostics,
                                message = result.diagnostics.firstOrNull()?.message ?: "Preset 无法导入",
                            )
                        }
                        is PresetLibraryImportResult.Saved -> _uiState.update {
                            it.copy(
                                busy = false,
                                selectedPresetId = result.preset.id,
                                draft = result.preset,
                                dirty = false,
                                importDiagnostics = result.diagnostics,
                                message = if (result.duplicate) "这个 Preset 已经导入" else "已导入 ${result.preset.name}",
                            )
                        }
                    }
                }
                .onFailure { error -> _uiState.update { it.copy(busy = false, message = error.displayMessage()) } }
        }
    }

    fun exportPreset(presetId: String): ByteArray? = runCatching { repository.exportPreset(presetId) }
        .onFailure { error -> _uiState.update { it.copy(message = error.displayMessage()) } }
        .getOrNull()

    fun reportMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    class Factory(private val repository: PresetRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PresetViewModel(repository) as T
    }
}

private fun Throwable.displayMessage(): String = message?.takeIf(String::isNotBlank) ?: "Preset 操作失败"
