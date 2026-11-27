package io.github.zvensmoluya.tavernplayer.conversation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.tavernplayer.content.BuiltInPresets
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PickerFlingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fastSwipesScrollPresetsWithoutMovingSheetOrLosingFooter() {
        val presets = List(40) { BuiltInPresets.default.copy(id = "p$it", name = "Preset $it") }
        var picked = ""
        val open = mutableStateOf(true)
        compose.setContent {
            TavernPlayerTheme {
                if (open.value) PresetPicker(presets, "p0", true,
                    onSelect = { picked = it }, onManage = {}, onDismiss = { open.value = false })
            }
        }
        val header = compose.onNodeWithText("选择预设")
        val list = compose.onNodeWithTag("presetChoices")
        val initialTop = header.fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.autoAdvance = false
        try {
            list.performTouchInput { swipeUp(durationMillis = 80) }
            repeat(12) {
                compose.mainClock.advanceTimeBy(32)
                assertEquals(initialTop, header.fetchSemanticsNode().boundsInRoot.top, 1f)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        assertEquals(initialTop, header.fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("quick-preset-p0").assertIsNotDisplayed()
        list.performScrollToKey("p39")
        list.performTouchInput { swipeUp(durationMillis = 80) }
        compose.onNodeWithTag("quick-preset-p39").assertIsDisplayed().performClick()
        assertEquals("p39", picked)
        compose.onNodeWithText("管理预设").assertIsDisplayed()
        list.performScrollToKey("p0")
        repeat(2) { list.performTouchInput { swipeDown(durationMillis = 80) } }
        assertTrue(open.value)
        assertEquals(initialTop, header.fetchSemanticsNode().boundsInRoot.top, 1f)
        val distance = list.fetchSemanticsNode().size.height * 0.9f
        compose.onNodeWithTag("sheetDragHandle", useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(0f, distance), durationMillis = 160)
        }
        compose.waitUntil(5_000) { !open.value }
    }
}
