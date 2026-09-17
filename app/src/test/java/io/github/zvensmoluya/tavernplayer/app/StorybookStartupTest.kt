package io.github.zvensmoluya.tavernplayer.app

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StorybookStartupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `ready library skips opening entirely`() {
        compose.setContent { StorybookStartup(false) { Text("角色库已就绪") } }
        compose.onNodeWithText("角色库已就绪").assertIsDisplayed()
        compose.onNodeWithTag("storybookOpening").assertDoesNotExist()
    }

    @Test fun `loading hides empty library and readiness interrupts the illustration`() {
        val loading = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { StorybookStartup(loading.value) { Text("角色库已就绪") } }
        compose.onNodeWithTag("storybookOpening").assertIsDisplayed()
        compose.onNodeWithText("角色库已就绪").assertDoesNotExist()
        compose.runOnIdle { loading.value = false }
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithText("角色库已就绪").assertIsDisplayed()
        compose.onNodeWithTag("storybookOpening").assertDoesNotExist()
    }
}
