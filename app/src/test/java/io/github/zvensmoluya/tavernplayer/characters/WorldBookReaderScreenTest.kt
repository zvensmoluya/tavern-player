package io.github.zvensmoluya.tavernplayer.characters

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookEntryDefinition
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

    @Test fun `search opens disabled source text and restores the entry and query`() {
        val restoration = StateRestorationTester(compose)
        var backs = 0
        restoration.setContent { TavernPlayerTheme { WorldBookReaderScreen(character) { backs++ } } }
        compose.onNodeWithTag("worldBookSearch").performTextInput("BEACON")
        compose.onNodeWithTag("worldBookEntry-0-0").performClick()
        compose.onNodeWithText("已停用").assertExists()
        compose.onNodeWithTag("worldBookText-0").assertTextEquals(source)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("worldBookText-0").assertTextEquals(source)
        compose.onNodeWithTag("worldBookBack").performClick()
        compose.onNodeWithTag("worldBookSearch").assertTextContains("BEACON")
        compose.onNodeWithTag("worldBookEntry-1-0").assertDoesNotExist()
        compose.onNodeWithTag("worldBookSearch").performTextReplacement("雪原")
        compose.onNodeWithTag("worldBookEntry-1-0").performClick()
        compose.onNodeWithTag("worldBookText-0").assertTextEquals("雪原的道路")
        compose.onNodeWithTag("worldBookBack").performClick()
        compose.onNodeWithTag("worldBookBack").performClick()
        assertEquals(1, backs)
        assertEquals(source, character.worldBooks.first().entries.single().content)
    }

    @Test fun `empty cards and searches have readable empty states`() {
        compose.setContent { TavernPlayerTheme { WorldBookReaderScreen(character.copy(worldBooks = emptyList())) {} } }
        compose.onNodeWithText("这张角色卡没有附带世界书").assertExists()
    }

    @Test fun `long entries remain readable to the end and unmatched searches can be cleared`() {
        val content = "第一段\n".repeat(5_000) + "末尾标记"
        val card = character.copy(worldBooks = listOf(WorldBookDefinition("long", entries = listOf(WorldBookEntryDefinition("entry", content = content)))))
        compose.setContent { TavernPlayerTheme { WorldBookReaderScreen(card) {} } }
        compose.onNodeWithTag("worldBookSearch").performTextInput("没有这个关键词")
        compose.onNodeWithText("没有找到匹配的条目").assertExists()
        compose.onNodeWithText("清除").performClick()
        compose.onNodeWithTag("worldBookEntry-0-0").performClick()
        compose.onNodeWithTag("worldBookEntryContent").performScrollToNode(hasText("末尾标记", substring = true))
        compose.onNodeWithText("末尾标记", substring = true).assertExists()
    }
}
