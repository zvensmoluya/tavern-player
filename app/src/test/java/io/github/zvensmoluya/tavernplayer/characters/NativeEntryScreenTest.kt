package io.github.zvensmoluya.tavernplayer.characters

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import io.github.zvensmoluya.tavernplayer.conversation.ConversationExecutionMode
import io.github.zvensmoluya.tavernplayer.conversation.ConversationRecord
import io.github.zvensmoluya.tavernplayer.conversation.Persona
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

    @Test fun `existing conversations disclose their execution route`() {
        val snapshot = CharacterAsset("plain", name = "纯文字样本").snapshot()
        val native = ConversationRecord(id = "c-native", character = snapshot, persona = Persona("p", "User"),
            turns = emptyList(), createdAtEpochMillis = 0, updatedAtEpochMillis = 0,
            executionMode = ConversationExecutionMode.LEGACY_NATIVE)
        val browser = native.copy(id = "c-browser", executionMode = ConversationExecutionMode.BROWSER)
        compose.setContent { TavernPlayerTheme {
            CharacterDetailScreen(CharacterAsset("plain", name = "纯文字样本"), listOf(native, browser).map { io.github.zvensmoluya.tavernplayer.conversation.storage.ConversationSummary(it.id, it.character.assetId, 0, 0, 0, "空白对话", it.executionMode.name) }, null, {}, {}, {})
        } }
        compose.onNodeWithTag("characterDetail").performScrollToNode(hasTestTag("conversation-c-native"))
        compose.onAllNodesWithText("· 原生模式", substring = true).assertCountEquals(1)
        compose.onNodeWithTag("characterDetail").performScrollToNode(hasTestTag("conversation-c-browser"))
        compose.onAllNodesWithText("· 网页模式", substring = true).assertCountEquals(1)
    }

}
