package io.github.zvensmoluya.tavernplayer.characters

import androidx.compose.ui.test.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookEntryDefinition
import io.github.zvensmoluya.tavernplayer.conversation.ConversationRecord
import io.github.zvensmoluya.tavernplayer.conversation.ConversationWorldBookController
import io.github.zvensmoluya.tavernplayer.conversation.Persona
import io.github.zvensmoluya.tavernplayer.conversation.WorldBookEntryMode
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WorldBookReaderScreenTest {
    @get:Rule val compose = createComposeRule()
    private val source = "<% if (getvar('score') > 0) { %>灯塔守则{{user}}<% } %>\n<script>readOnly()</script>"
    private val character = CharacterAsset("card", name = "中性样本", worldBooks = listOf(
        WorldBookDefinition("first", name = "城镇资料", entries = listOf(
            WorldBookEntryDefinition("same", comment = "灯塔档案", content = source, keys = listOf("beacon"), enabled = false),
        )),
        WorldBookDefinition("second", name = "远行资料", entries = listOf(
            WorldBookEntryDefinition("same", content = "雪原的道路", constant = true),
        )),
    ))

    @Test fun `character detail exposes world book reading before adaptation`() {
        var opens = 0
        compose.setContent { TavernPlayerTheme {
            CharacterDetailScreen(character, emptyList(), null, {}, {}, {}, onReadWorldBooks = { opens++ })
        } }
        compose.onNodeWithTag("readWorldBooks").assertIsDisplayed().performClick()
        assertEquals(1, opens)
    }

    @Test fun `browsing opens disabled source text and restores the selected entry without search or editing`() {
        val restoration = StateRestorationTester(compose)
        var backs = 0
        restoration.setContent { TavernPlayerTheme { WorldBookReaderScreen(character) { backs++ } } }
        compose.onNodeWithTag("worldBookSearch").assertDoesNotExist()
        compose.onNodeWithTag("worldBookEntry-0-0").performClick()
        compose.onNodeWithText("作者已停用").assertExists()
        compose.onNodeWithTag("worldBookEditContent").assertDoesNotExist()
        compose.onNodeWithTag("worldBookText-0").assertTextEquals(source)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("worldBookText-0").assertTextEquals(source)
        compose.onNodeWithTag("worldBookBack").performClick()
        compose.onNodeWithTag("worldBookEntry-1-0").performClick()
        compose.onNodeWithTag("worldBookText-0").assertTextEquals("雪原的道路")
        compose.onNodeWithTag("worldBookBack").performClick()
        compose.onNodeWithTag("worldBookBack").performClick()
        assertEquals(1, backs)
        assertEquals(source, character.worldBooks.first().entries.single().content)
    }

    @Test fun `empty cards have a readable empty state`() {
        compose.setContent { TavernPlayerTheme { WorldBookReaderScreen(character.copy(worldBooks = emptyList())) {} } }
        compose.onNodeWithText("这张角色卡没有附带世界书").assertExists()
    }

    @Test fun `long entries remain readable to the end`() {
        val content = "第一段\n".repeat(5_000) + "末尾标记"
        val card = character.copy(worldBooks = listOf(WorldBookDefinition("long", entries = listOf(WorldBookEntryDefinition("entry", content = content)))))
        compose.setContent { TavernPlayerTheme { WorldBookReaderScreen(card) {} } }
        compose.onNodeWithTag("worldBookEntry-0-0").performClick()
        compose.onNodeWithTag("worldBookEntryContent").performScrollToNode(hasText("末尾标记", substring = true))
        compose.onNodeWithText("末尾标记", substring = true).assertExists()
    }

    @Test fun `session editing is explicit scoped and restored only after saving`() {
        val card = character.copy(worldBooks = listOf(WorldBookDefinition("book", entries = listOf(
            WorldBookEntryDefinition("entry", comment = "中性设定", content = "保留这句。去掉这句。", enabled = false)))))
        var record by mutableStateOf(ConversationRecord(id = "session", character = card.snapshot(), persona = Persona("p", "旅人"),
            turns = emptyList(), createdAtEpochMillis = 1, updatedAtEpochMillis = 1))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TavernPlayerTheme {
            WorldBookReaderScreen("session", card.name, record.character.worldBooks, {}, sessionState = record.worldBookState,
                onEntryMode = { book, entry, mode -> record = ConversationWorldBookController.setMode(record, book, entry, mode) },
                onEntryContent = { book, entry, text, saved -> record = ConversationWorldBookController.setContent(record, book, entry, text); saved() })
        } }
        compose.onNodeWithTag("worldBookEntry-0-0").performClick()
        compose.onNodeWithTag("worldBookContentEditor").assertDoesNotExist()
        compose.onNodeWithTag("worldBookUsage").performClick()
        compose.onNodeWithTag("worldBookEntryMode-DISABLED").assertIsSelected()
        compose.onNodeWithTag("worldBookEntryMode-FORCED").performClick()
        compose.onNodeWithTag("worldBookEditContent").performClick()
        compose.onNodeWithTag("worldBookContentEditor").performTextReplacement("保留这句。")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("worldBookContentEditor").assertTextContains("保留这句。")
        compose.onNodeWithTag("worldBookSave").performClick()
        compose.onNodeWithTag("worldBookText-0").assertTextEquals("保留这句。")
        compose.onNodeWithTag("worldBookContentChanged").assertExists()
        assertEquals("保留这句。去掉这句。", record.character.worldBooks.single().entries.single().content)
        compose.onNodeWithTag("worldBookEditContent").performClick()
        compose.onNodeWithTag("worldBookRestoreContent").performClick()
        assertEquals("保留这句。", record.worldBookState.playerOverrides["book"]?.get("entry")?.content)
        compose.onNodeWithTag("worldBookSave").performClick()
        compose.onNodeWithTag("worldBookText-0").assertTextEquals("保留这句。去掉这句。")
        compose.onNodeWithTag("worldBookContentChanged").assertDoesNotExist()
        assertEquals(WorldBookEntryMode.FORCED, record.worldBookState.playerOverrides["book"]?.get("entry")?.mode)
        compose.onNodeWithTag("worldBookUsage").performClick()
        compose.onNodeWithTag("worldBookRestoreMode").performScrollTo().performClick()
        assertEquals(null, record.worldBookState.playerOverrides["book"]?.get("entry")?.mode)
        compose.onNodeWithTag("worldBookUsage").performClick()
        compose.onNodeWithTag("worldBookEntryMode-DISABLED").assertIsSelected()
    }

    @Test fun `failed save keeps draft and busy generation allows reading but disables adjustments`() {
        var busy by mutableStateOf(false)
        var message by mutableStateOf<String?>(null)
        compose.setContent { TavernPlayerTheme {
            WorldBookReaderScreen("session", character.name, character.worldBooks, {},
                sessionState = io.github.zvensmoluya.tavernplayer.conversation.ConversationWorldBookState(), busy = busy, message = message,
                onEntryContent = { _, _, _, _ -> message = "保存失败，请重试" })
        } }
        compose.onNodeWithTag("worldBookEntry-0-0").performClick()
        compose.onNodeWithTag("worldBookEditContent").performClick()
        compose.onNodeWithTag("worldBookContentEditor").performTextReplacement("草稿")
        compose.onNodeWithTag("worldBookSave").performClick()
        compose.onNodeWithText("保存失败，请重试").assertExists()
        compose.onNodeWithTag("worldBookContentEditor").assertTextContains("草稿")
        compose.onNodeWithTag("worldBookBack").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle { busy = true }
        compose.onNodeWithTag("worldBookText-0").assertTextEquals(source)
        compose.onNodeWithTag("worldBookUsage").assertIsNotEnabled()
        compose.onNodeWithTag("worldBookEditContent").assertIsNotEnabled()
    }

    @Test fun `reader previews source and pages through entries without opening adjustment controls`() {
        compose.setContent { TavernPlayerTheme {
            WorldBookReaderScreen("session", character.name, character.worldBooks, {},
                sessionState = io.github.zvensmoluya.tavernplayer.conversation.ConversationWorldBookState())
        } }
        compose.onNodeWithText("包含模板内容，展开查看原文").assertExists()
        compose.onNodeWithText("雪原的道路").assertExists()
        compose.onNodeWithTag("worldBookEntry-0-0").performClick()
        compose.onNodeWithTag("worldBookPrevious").assertIsNotEnabled()
        compose.onNodeWithTag("worldBookEntryMode-FORCED").assertDoesNotExist()
        compose.onNodeWithTag("worldBookNext").performClick()
        compose.onNodeWithTag("worldBookText-0").assertTextEquals("雪原的道路")
        compose.onNodeWithTag("worldBookNext").assertIsNotEnabled()
        compose.onNodeWithTag("worldBookPrevious").performClick()
        compose.onNodeWithTag("worldBookText-0").assertTextEquals(source)
        compose.onNodeWithTag("worldBookBack").performClick()
        compose.onNodeWithTag("worldBookEntry-0-0").assertIsDisplayed()
    }
}
