package io.github.zvensmoluya.tavernplayer.presets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.tavernplayer.content.BuiltInPresets
import io.github.zvensmoluya.tavernplayer.content.PresetAsset
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationParameter
import io.github.zvensmoluya.tavernplayer.content.RegexDefinition
import io.github.zvensmoluya.tavernplayer.content.RegexPlacement
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PresetScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `center shows active metadata and opens an asset`() {
        val custom = editablePreset()
        var opened: String? = null
        compose.setContent {
            TavernPlayerTheme {
                PresetScreen(
                    state = PresetUiState(
                        presets = listOf(BuiltInPresets.default, custom),
                        activePresetId = custom.id,
                    ),
                    actions = PresetScreenActions(openEditor = { opened = it }),
                )
            }
        }

        compose.onNodeWithTag("preset-custom").assertIsDisplayed().performClick()
        compose.onNodeWithText("使用中").assertIsDisplayed()
        compose.onNodeWithText("4 个快速项 · 1 Regex · 回复 1024").assertIsDisplayed()
        assertEquals("custom", opened)
    }

    @Test
    fun `editor keeps changes local until explicit save and edits order and regex state`() {
        var state by mutableStateOf(
            PresetUiState(
                presets = listOf(editablePreset()),
                activePresetId = "custom",
                selectedPresetId = "custom",
                draft = editablePreset(),
            ),
        )
        var saved: PresetAsset? = null
        var cancelled = false
        compose.setContent {
            TavernPlayerTheme {
                PresetScreen(
                    state = state,
                    actions = PresetScreenActions(
                        cancelEditor = { cancelled = true },
                        updateDraft = { transform ->
                            state = state.copy(draft = state.draft?.let(transform), dirty = true)
                        },
                        setPromptEnabled = { id, enabled ->
                            val draft = state.draft!!
                            state = state.copy(
                                draft = draft.copy(
                                    promptOrder = draft.promptOrder.map {
                                        if (it.identifier == id) it.copy(enabled = enabled) else it
                                    },
                                ),
                                dirty = true,
                            )
                        },
                        movePrompt = { id, delta ->
                            val draft = state.draft!!
                            val from = draft.promptOrder.indexOfFirst { it.identifier == id }
                            val to = (from + delta).coerceIn(0, draft.promptOrder.lastIndex)
                            val order = draft.promptOrder.toMutableList()
                            val item = order.removeAt(from)
                            order.add(to, item)
                            state = state.copy(draft = draft.copy(promptOrder = order), dirty = true)
                        },
                        updateRegexEnabled = { id, enabled ->
                            val draft = state.draft!!
                            state = state.copy(
                                draft = draft.copy(
                                    regexScripts = draft.regexScripts.map {
                                        if (it.id == id) it.copy(disabled = !enabled) else it
                                    },
                                ),
                                dirty = true,
                            )
                        },
                        setGenerationParameterEnabled = { parameter, enabled ->
                            val draft = state.draft!!
                            state = state.copy(
                                draft = draft.copy(
                                    generationSettings = draft.generationSettings.withEnabled(parameter, enabled),
                                ),
                                dirty = true,
                            )
                        },
                        save = { saved = state.draft },
                    ),
                )
            }
        }

        compose.onNodeWithTag("savePreset").assertIsNotEnabled()
        compose.onNodeWithTag("prompt-enabled-main").performClick()
        assertFalse(state.draft!!.promptOrder.single { it.identifier == "main" }.enabled)

        compose.onNodeWithTag("presetEditor").performScrollToNode(hasTestTag("openPresetAdvanced"))
        compose.onNodeWithTag("openPresetAdvanced").performClick()
        compose.onNodeWithTag("presetName").performTextReplacement("Edited")
        compose.onNodeWithTag("savePreset").assertIsEnabled()
        compose.onNodeWithTag("presetAdvancedSheet").performScrollToNode(hasTestTag("closePresetAdvanced"))
        compose.onNodeWithTag("closePresetAdvanced").performClick()

        compose.onNodeWithTag("presetEditor").performScrollToNode(hasTestTag("prompt-details-main"))
        compose.onNodeWithTag("prompt-details-main").performClick()
        compose.onNodeWithTag("showPromptAdvanced").performClick()
        compose.onNodeWithTag("promptDetail-main").performScrollToNode(hasTestTag("order-down-main"))
        compose.onNodeWithTag("order-down-main").performClick()
        assertEquals("worldInfoBefore", state.draft?.promptOrder?.first()?.identifier)
        compose.onNodeWithTag("promptDetail-main").performScrollToNode(hasTestTag("closePromptDetail"))
        compose.onNodeWithTag("closePromptDetail").performClick()

        compose.onNodeWithTag("presetEditor").performScrollToNode(hasTestTag("regex-enabled-display"))
        compose.onNodeWithTag("regex-enabled-display").performClick()
        assertTrue(state.draft?.regexScripts?.single()?.disabled == true)

        compose.onNodeWithTag("savePreset").performClick()
        assertEquals("Edited", saved?.name)
        assertTrue(saved?.regexScripts?.single()?.disabled == true)

        compose.onNodeWithTag("cancelPresetEdit").performClick()
        assertTrue(cancelled)
    }

    @Test
    fun `request parameters are secondary controls that can be unplugged`() {
        var state by mutableStateOf(
            PresetUiState(
                presets = listOf(editablePreset()),
                activePresetId = "custom",
                selectedPresetId = "custom",
                draft = editablePreset(),
            ),
        )
        compose.setContent {
            TavernPlayerTheme {
                PresetScreen(
                    state = state,
                    actions = PresetScreenActions(
                        setGenerationParameterEnabled = { parameter, enabled ->
                            val draft = state.draft!!
                            state = state.copy(
                                draft = draft.copy(
                                    generationSettings = draft.generationSettings.withEnabled(parameter, enabled),
                                ),
                                dirty = true,
                            )
                        },
                    ),
                )
            }
        }

        compose.onNodeWithTag("presetEditor").performScrollToNode(hasTestTag("openRequestParameters"))
        compose.onNodeWithTag("openRequestParameters").performClick()
        compose.onNodeWithTag("requestParameterSheet")
            .performScrollToNode(hasTestTag("parameter-enabled-output_limit"))
        compose.onNodeWithTag("parameter-enabled-output_limit").performClick()

        assertFalse(state.draft!!.generationSettings.isEnabled(PresetGenerationParameter.OUTPUT_LIMIT))
        compose.onNodeWithText("已关闭 · 保留值 1024").assertIsDisplayed()
    }

    @Test
    fun `built in editor requires copy before editing`() {
        val builtIn = BuiltInPresets.default
        compose.setContent {
            TavernPlayerTheme {
                PresetScreen(
                    state = PresetUiState(
                        presets = listOf(builtIn),
                        activePresetId = builtIn.id,
                        selectedPresetId = builtIn.id,
                        draft = builtIn,
                    ),
                    actions = PresetScreenActions(),
                )
            }
        }

        compose.onNodeWithText("内置 · 复制后编辑").assertIsDisplayed()
        compose.onNodeWithTag("prompt-enabled-main").assertIsNotEnabled()
        compose.onNodeWithTag("savePreset").assertIsNotEnabled()
        assertFalse(builtIn.builtIn.not())
    }

    private fun editablePreset(): PresetAsset = BuiltInPresets.default.copy(
        id = "custom",
        name = "Custom",
        sourceSha256 = "source",
        contentSha256 = "content",
        builtIn = false,
        regexScripts = listOf(
            RegexDefinition(
                id = "display",
                name = "Display",
                findRegex = "foo",
                replaceString = "bar",
                placements = setOf(RegexPlacement.AI_OUTPUT),
                markdownOnly = true,
            ),
        ),
    )
}
