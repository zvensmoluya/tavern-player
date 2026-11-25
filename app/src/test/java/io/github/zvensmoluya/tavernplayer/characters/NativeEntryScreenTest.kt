package io.github.zvensmoluya.tavernplayer.characters

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NativeEntryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `character detail only offers the main conversation entry even with an adaptation`() {
        var opens = 0
        val card = CharacterAsset("sample", name = "中性样本",
            nativeAdaptation = NativeAdaptation(sourceSha256 = "a".repeat(64)))
        compose.setContent { TavernPlayerTheme {
            CharacterDetailScreen(card, emptyList(), null, {}, { opens++ }, {})
        } }
        compose.onNodeWithTag("newConversation").performClick()
        assertEquals(1, opens)
        compose.onNodeWithTag("newNativeConversation").assertDoesNotExist()
        compose.onNodeWithTag("characterDetail").performScrollToNode(hasText("卡片内容"))
        compose.onNodeWithTag("compileNativeAdaptation").assertDoesNotExist()
        compose.onNodeWithTag("installNativeAdaptation").assertDoesNotExist()
    }

}
