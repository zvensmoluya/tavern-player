package io.github.zvensmoluya.tavernplayer.characters

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookEntryDefinition
import io.github.zvensmoluya.tavernplayer.conversation.ConversationExecutionMode
import io.github.zvensmoluya.tavernplayer.conversation.ConversationRecord
import io.github.zvensmoluya.tavernplayer.conversation.Persona
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NativeEntryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `plain card reaches the native route without a runtime notice`() {
        var opens = 0
        compose.setContent { TavernPlayerTheme {
            CharacterDetailScreen(CharacterAsset("plain", name = "纯文字样本"), emptyList(), null, {}, {}, {},
                onNewNativeConversation = { opens++ })
        } }
        compose.onNodeWithTag("characterDetail").performScrollToNode(hasTestTag("newNativeConversation"))
        compose.onNodeWithText("使用原生模式开始对话").assertIsDisplayed()
        compose.onAllNodesWithTag("nativeEntryNotice").assertCountEquals(0)
        compose.onNodeWithTag("newNativeConversation").performClick()
        assertEquals(1, opens)
    }

    @Test fun `author runtime is disclosed before the native route is taken`() {
        compose.setContent { TavernPlayerTheme {
            CharacterDetailScreen(scriptedCard(), emptyList(), null, {}, {}, {})
        } }
        compose.onNodeWithTag("characterDetail").performScrollToNode(hasTestTag("nativeEntryNotice"))
        compose.onNodeWithTag("nativeEntryNotice").assertIsDisplayed()
        compose.onNodeWithText("为网页模式声明了", substring = true).assertIsDisplayed()
    }

    @Test fun `world book template alone counts as author runtime`() {
        compose.setContent { TavernPlayerTheme {
            CharacterDetailScreen(templateCard(), emptyList(), null, {}, {}, {})
        } }
        compose.onNodeWithTag("characterDetail").performScrollToNode(hasTestTag("nativeEntryNotice"))
        compose.onNodeWithTag("nativeEntryNotice").assertIsDisplayed()
    }

    @Test fun `compiled card keeps its adaptation label and drops the raw script notice`() {
        val card = scriptedCard().copy(id = "compiled",
            nativeAdaptation = NativeAdaptation(sourceSha256 = "a".repeat(64)))
        compose.setContent { TavernPlayerTheme {
            CharacterDetailScreen(card, emptyList(), null, {}, {}, {})
        } }
        compose.onNodeWithTag("characterDetail").performScrollToNode(hasTestTag("newNativeConversation"))
        compose.onNodeWithText("使用原生适配开始对话").assertIsDisplayed()
        compose.onAllNodesWithTag("nativeEntryNotice").assertCountEquals(0)
    }

    @Test fun `unreadable helper container still reaches the native route`() {
        val broken = CharacterAsset("broken", name = "畸形样本", rawCard = buildJsonObject {
            putJsonObject("data") { putJsonObject("extensions") { put("tavern_helper", "unsupported") } }
        })
        var opens = 0
        compose.setContent { TavernPlayerTheme {
            CharacterDetailScreen(broken, emptyList(), null, {}, {}, {}, onNewNativeConversation = { opens++ })
        } }
        compose.onNodeWithTag("characterDetail").performScrollToNode(hasTestTag("nativeEntryNotice"))
        // 网页模式对同一张卡也建不出会话，提示不能把玩家指过去。
        compose.onNodeWithText("无法读取", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("newNativeConversation").performClick()
        assertEquals(1, opens)
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

    private fun scriptedCard(): CharacterAsset = CharacterAsset("scripted", name = "带脚本样本",
        rawCard = buildJsonObject { putJsonObject("data") { putJsonObject("extensions") {
            putJsonArray("tavern_helper") { add(buildJsonArray { add("scripts"); add(buildJsonArray {
                add(buildJsonObject { put("enabled", true); put("content", "console.log(1);") }) }) }) }
        } } })

    private fun templateCard(): CharacterAsset = CharacterAsset("ejs", name = "模板样本",
        worldBooks = listOf(WorldBookDefinition("book", entries = listOf(
            WorldBookEntryDefinition("entry", content = "血量：<%= 1 %>")))))
}
