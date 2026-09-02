package io.github.zvensmoluya.tavernplayer.presets

import io.github.zvensmoluya.tavernplayer.content.BuiltInPresets
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationParameter
import io.github.zvensmoluya.tavernplayer.conversation.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PresetViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `active editing is local until save and stable switches do not rewrite order`() = runTest {
        val repository = PresetRepository(
            filesDir = temporary.newFolder("preset-view-model"),
            idFactory = { "copy" },
            ioDispatcher = mainDispatcher.dispatcher,
        )
        val viewModel = PresetViewModel(repository)

        viewModel.openEditor(BuiltInPresets.DEFAULT_ID)
        val active = viewModel.uiState.value.draft!!
        val originalOrder = active.promptOrder.map { it.identifier }

        viewModel.updateDraft { it.copy(name = "Unsaved") }
        assertEquals("Unsaved", viewModel.uiState.value.draft?.name)
        assertEquals(active.name, repository.get(active.id)?.name)
        viewModel.setPromptEnabled(originalOrder.first(), false)
        viewModel.setPromptEnabled("not-in-order", true)
        assertEquals(originalOrder, viewModel.uiState.value.draft?.promptOrder?.map { it.identifier })

        viewModel.cancelEditor()
        assertNull(viewModel.uiState.value.draft)
        viewModel.openEditor(active.id)
        assertEquals(active.name, viewModel.uiState.value.draft?.name)

        viewModel.updateDraft {
            it.copy(
                name = "Saved",
                generationSettings = it.generationSettings.copy(maxOutputTokens = 2_048),
            )
        }
        viewModel.setGenerationParameterEnabled(PresetGenerationParameter.OUTPUT_LIMIT, false)
        viewModel.save()

        assertEquals("Saved", repository.get(active.id)?.name)
        assertEquals(2_048, repository.get(active.id)?.generationSettings?.maxOutputTokens)
        assertFalse(
            repository.get(active.id)?.generationSettings
                ?.isEnabled(PresetGenerationParameter.OUTPUT_LIMIT) == true,
        )
        assertFalse(viewModel.uiState.value.dirty)

        viewModel.restoreInitial()
        assertEquals("Saved", viewModel.uiState.value.draft?.name)
        assertEquals(
            BuiltInPresets.default.promptOrder.map { it.identifier to it.enabled },
            viewModel.uiState.value.draft?.promptOrder?.map { it.identifier to it.enabled },
        )
        assertTrue(viewModel.uiState.value.dirty)

        viewModel.updateDraft {
            it.copy(generationSettings = it.generationSettings.copy(maxOutputTokens = 3_333))
        }
        viewModel.saveAs("Experiment")
        assertEquals("Experiment", viewModel.uiState.value.draft?.name)
        assertEquals(viewModel.uiState.value.draft?.id, repository.library.value.activePresetId)
        assertEquals(3_333, repository.activePreset.value.generationSettings.maxOutputTokens)
        assertEquals(3_333, repository.initialVersion(repository.activePreset.value.id).generationSettings.maxOutputTokens)
        assertFalse(viewModel.uiState.value.draft?.builtIn == true)
    }
}
