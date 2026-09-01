package io.github.zvensmoluya.tavernplayer.presets

import io.github.zvensmoluya.tavernplayer.content.BuiltInPresets
import io.github.zvensmoluya.tavernplayer.conversation.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PresetViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `copy editing is cancelled locally and explicit save persists the draft`() = runTest {
        val repository = PresetRepository(
            filesDir = temporary.newFolder("preset-view-model"),
            idFactory = { "copy" },
            ioDispatcher = mainDispatcher.dispatcher,
        )
        val viewModel = PresetViewModel(repository)

        viewModel.copy(BuiltInPresets.DEFAULT_ID)
        val copied = viewModel.uiState.value.draft!!
        assertFalse(copied.builtIn)

        viewModel.updateDraft { it.copy(name = "Unsaved") }
        assertEquals("Unsaved", viewModel.uiState.value.draft?.name)
        assertEquals(copied.name, repository.get(copied.id)?.name)

        viewModel.cancelEditor()
        assertNull(viewModel.uiState.value.draft)
        viewModel.openEditor(copied.id)
        assertEquals(copied.name, viewModel.uiState.value.draft?.name)

        viewModel.updateDraft {
            it.copy(
                name = "Saved",
                generationSettings = it.generationSettings.copy(maxOutputTokens = 2_048),
            )
        }
        viewModel.save()

        assertEquals("Saved", repository.get(copied.id)?.name)
        assertEquals(2_048, repository.get(copied.id)?.generationSettings?.maxOutputTokens)
        assertFalse(viewModel.uiState.value.dirty)
    }
}
