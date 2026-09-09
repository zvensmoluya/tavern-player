package io.github.zvensmoluya.tavernplayer.characters

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.tavernplayer.content.CharacterImageReference
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CharacterResourcesScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun pendingResourcesCanBePreparedWithoutModelSetup() {
        val entry = CharacterImageEntry(CharacterImageReference("sample", "https://images.example/a.png", listOf("/description")))
        var clicks = 0
        compose.setContent { TavernPlayerTheme {
            CharacterResourcesScreen(CharacterImageState(listOf(entry)), false, null, { clicks++ }, {}, {}, { null }, {})
        } }
        compose.onNodeWithTag("resourceSummary").assertTextContains("0 / 1", substring = true)
        compose.onNodeWithTag("prepareResources").performClick()
        assertEquals(1, clicks)
        compose.onNodeWithText("待准备").assertExists()
    }

    @Test fun preparingResourcesExposePauseAndProgress() {
        var cancels = 0
        compose.setContent { TavernPlayerTheme {
            CharacterResourcesScreen(CharacterImageState(), true, null, {}, { cancels++ }, {}, { null }, {})
        } }
        compose.onNodeWithTag("cancelResources").performClick()
        assertEquals(1, cancels)
        compose.onNodeWithTag("prepareResources").assertDoesNotExist()
    }
}
