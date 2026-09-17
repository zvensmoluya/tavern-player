package io.github.zvensmoluya.tavernplayer.app

import android.graphics.Bitmap
import androidx.compose.runtime.*
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.tavernplayer.characters.*
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.conversation.storage.ConversationSummary
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreensTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var rootView: View
    private val characters = listOf(
        CharacterAsset("a", name = "角色样本 A", tags = listOf("旅行", "日常")),
        CharacterAsset("b", name = "角色样本 B", creator = "作者样本"),
        CharacterAsset("c", name = "这是一位名字比较长的角色样本 C"),
        CharacterAsset("d", name = "角色样本 D", tags = listOf("探索")),
    )

    @Test fun `character views share search results and open the same detail`() {
        var state by mutableStateOf(CharacterLibraryUiState(characters = characters))
        var selected: String? = null
        var imports = 0
        var scans = 0
        compose.setContent { TavernPlayerTheme {
            rootView = LocalView.current
            HomeScaffold(AppSurface.CHARACTER_LIBRARY, onNavigate = {}) {
                CharacterLibraryScreen(state, { null }, { imports++ }, { scans++ }, { selected = it }, { state = state.copy(layout = it) })
            }
        } }
        capture("characters-grid")
        compose.onNodeWithContentDescription("搜索角色").performClick()
        compose.onNodeWithTag("characterSearch").performTextInput("旅行")
        compose.onNodeWithTag("character-a").assertIsDisplayed()
        compose.onNodeWithTag("character-b").assertDoesNotExist()
        compose.onNodeWithTag("switchCharacterLayout").performClick()
        compose.onNodeWithTag("characterList").assertIsDisplayed()
        compose.onNodeWithTag("character-a").performClick()
        assertEquals("a", selected)
        compose.onNodeWithTag("character-b").assertDoesNotExist()
        compose.onNodeWithContentDescription("清空搜索").performClick()
        compose.onNodeWithContentDescription("搜索角色").performClick()
        capture("characters-list")
        compose.onNodeWithTag("addCharacter").performClick()
        compose.onNodeWithTag("importCharacter").performClick()
        compose.onNodeWithTag("addCharacter").performClick()
        compose.onNodeWithTag("importFromShelf").performClick()
        assertEquals(1, imports)
        assertEquals(1, scans)
    }

    @Test fun `global history preserves separate stories including missing character assets`() {
        val summaries = listOf(
            ConversationSummary("old", "a", 0, 10, 2, "较早的一场故事", "LEGACY_NATIVE"),
            ConversationSummary("new", "a", 0, 30, 5, "<b>最新的故事</b>", "BROWSER"),
            ConversationSummary("missing", "removed", 0, 20, 1, "保留的聊天", "BROWSER"),
        )
        var selected: String? = null
        compose.setContent { TavernPlayerTheme {
            rootView = LocalView.current
            HomeScaffold(AppSurface.CONVERSATIONS, onNavigate = {}) {
                ConversationLibraryScreen(CharacterLibraryUiState(characters = characters, conversations = summaries),
                    { null }, { selected = it }, {})
            }
        } }
        compose.onAllNodesWithText("角色样本 A").assertCountEquals(2)
        compose.onNodeWithText("最新的故事").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("recent-new").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithTag("recent-old").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithTag("recent-missing").performClick()
        assertEquals("missing", selected)
        compose.onNodeWithTag("recent-old").performClick()
        assertEquals("old", selected)
        capture("conversations")
    }

    @Test fun `my screen exposes persona presets and world books`() {
        var persona = 0; var presets = 0; var books = 0
        compose.setContent { TavernPlayerTheme {
            rootView = LocalView.current
            HomeScaffold(AppSurface.MY, onNavigate = {}) {
                MyScreen(CharacterLibraryUiState().persona, { persona++ }, { presets++ }, { books++ })
            }
        } }
        compose.onNodeWithTag("openPersona").performClick()
        compose.onNodeWithTag("openPresetsFromMy").performClick()
        compose.onNodeWithTag("openGlobalWorldBooks").performClick()
        assertEquals(listOf(1, 1, 1), listOf(persona, presets, books))
        capture("my")
    }

    @Test fun `empty history provides a route to characters`() {
        var opens = 0
        compose.setContent { TavernPlayerTheme {
            rootView = LocalView.current
            ConversationLibraryScreen(CharacterLibraryUiState(), { null }, {}, { opens++ })
        } }
        compose.onNodeWithText("去看看角色").performClick()
        assertEquals(1, opens)
    }

    private fun capture(name: String) {
        val bitmap = compose.runOnIdle {
            Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888).also { rootView.draw(Canvas(it)) }
        }
        val file = File("build/reports/home-preview/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
