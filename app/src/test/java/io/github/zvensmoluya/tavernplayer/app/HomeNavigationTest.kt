package io.github.zvensmoluya.tavernplayer.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.modelgateway.ModelGateway
import io.github.zvensmoluya.tavernplayer.characters.*
import io.github.zvensmoluya.tavernplayer.connections.*
import io.github.zvensmoluya.tavernplayer.conversation.*
import io.github.zvensmoluya.tavernplayer.personas.*
import io.github.zvensmoluya.tavernplayer.presets.*
import io.github.zvensmoluya.tavernplayer.transfer.ShelfTransferReceiver
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HomeNavigationTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val models = ViewModelStore()
    private var repository: ConversationRepository? = null
    private lateinit var back: OnBackPressedDispatcher

    @After fun close() { models.clear(); repository?.close() }

    @Test fun `pick a recent player line from history and rename it manually from chat`() {
        val fixture = fixture()
        val id = fixture.conversationId
        val repo = requireNotNull(repository)
        runBlocking {
            val saved = requireNotNull(repo.get(id))
            val turns = listOf("别开门", "把灯熄了").mapIndexed { index, text ->
                val message = ConversationMessage("reply-$index", MessageRole.USER, text, "玩家")
                ConversationTurn(message.id, MessageRole.USER, listOf(MessageVariant(message.id, message)))
            }
            repo.save(saved.copy(turns = saved.turns + turns), saved)
        }
        compose.setContent { TavernPlayerTheme { fixture.Content() } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("别开门").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("rename-$id").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("把灯熄了") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("把灯熄了") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { fixture.library.uiState.value.conversations.single().title == "把灯熄了" }
        compose.onNodeWithTag("recent-$id").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("chatMore").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chatMore").performClick()
        compose.onNodeWithText("更换对话名").performClick()
        compose.onNodeWithText("自己填写").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("灯塔旧事")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5_000) { fixture.library.uiState.value.conversations.single().title == "灯塔旧事" }
        compose.onNodeWithTag("backToCharacter").performClick()
        compose.onNodeWithText("灯塔旧事").assertIsDisplayed()
    }

    @Test fun `global chat opens without a selected character and returns to history after restoration`() {
        val fixture = fixture()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TavernPlayerTheme {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            fixture.Content()
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("recent-${fixture.conversationId}").fetchSemanticsNodes().isNotEmpty() }
        assertNull(fixture.library.uiState.value.selectedCharacterId)
        compose.onNodeWithTag("recent-${fixture.conversationId}").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("backToCharacter").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("homeNavigation").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("backToCharacter").assertIsDisplayed()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithTag("tab-CONVERSATIONS").assertIsSelected()
        compose.onNodeWithTag("recent-${fixture.conversationId}").assertIsDisplayed()

        compose.onNodeWithTag("tab-CHARACTER_LIBRARY").performClick()
        compose.onNodeWithTag("character-${fixture.characterId}").performClick()
        compose.onNodeWithTag("homeNavigation").assertDoesNotExist()
        compose.onNodeWithTag("characterDetail").performScrollToNode(hasTestTag("conversation-${fixture.conversationId}"))
        compose.onNodeWithTag("conversation-${fixture.conversationId}").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("backToCharacter").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("backToCharacter").performClick()
        compose.onNodeWithTag("characterDetail").assertIsDisplayed()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithTag("tab-CHARACTER_LIBRARY").assertIsSelected()
    }

    @Test fun `tabs retain search and editor back returns through model and my destinations`() {
        val fixture = fixture()
        compose.setContent { TavernPlayerTheme {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            fixture.Content()
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("tab-CHARACTER_LIBRARY").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab-CHARACTER_LIBRARY").performClick()
        compose.onNodeWithContentDescription("搜索角色").performClick()
        compose.onNodeWithTag("characterSearch").performTextInput("样本")
        compose.onNodeWithTag("switchCharacterLayout").performClick()
        compose.onNodeWithTag("tab-MY").performClick()
        compose.onNodeWithTag("openPersona").performClick()
        compose.onNodeWithTag("homeNavigation").assertDoesNotExist()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithTag("tab-MY").assertIsSelected()
        compose.onNodeWithTag("openPresetsFromMy").performClick()
        compose.onNodeWithTag("homeNavigation").assertDoesNotExist()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithTag("tab-MY").assertIsSelected()
        compose.onNodeWithTag("tab-MODELS").performClick()
        compose.onNodeWithTag("addConnection").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("homeNavigation").assertDoesNotExist()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithTag("tab-MODELS").assertIsSelected()
        compose.onNodeWithTag("tab-CHARACTER_LIBRARY").performClick()
        compose.onNodeWithTag("characterSearch").assertTextContains("样本")
        compose.onNodeWithTag("characterList").assertIsDisplayed()
    }

    private fun <T : ViewModel> keep(model: T): T = model.also { models.put(model.javaClass.name, it) }

    private fun fixture(): Fixture {
        val root = temporary.newFolder()
        val characters = CharacterRepository(root, ioDispatcher = dispatcher.dispatcher)
        val presets = PresetRepository(root, ioDispatcher = dispatcher.dispatcher)
        val personas = PersonaRepository(root, ioDispatcher = dispatcher.dispatcher)
        val conversations = ConversationRepository(root, PromptCompiler(), ioDispatcher = dispatcher.dispatcher).also { repository = it }
        val card = runBlocking { characters.import(
            """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"角色样本","first_mes":"你好，这是一段开场。"}}""".encodeToByteArray(), "sample.json") } as CharacterSaveResult.Saved
        val saved = runBlocking { conversations.create(card.character, personas.persona.value, presets.activePreset.value) }
        val connections = ConnectionRepository(object : ConnectionStateStore {
            override val state = MutableStateFlow(GatewayAppState())
            override suspend fun update(transform: (GatewayAppState) -> GatewayAppState) { state.value = transform(state.value) }
        }, object : CredentialStore {
            override suspend fun put(credentialId: String, secret: String) = Unit
            override suspend fun getOrNull(credentialId: String): String? = null
            override suspend fun delete(credentialId: String) = Unit
            override suspend fun contains(credentialId: String) = false
        }, { error("No model calls during navigation") })
        val library = keep(CharacterLibraryViewModel(characters, conversations, personas, presets,
            ShelfTransferReceiver { error("unused") }))
        return Fixture(card.character.id, saved.id, library,
            chat = { keep(ChatViewModel(connections, PromptCompiler(), object : ConversationGenerator {
                override fun stream(connection: StoredConnection, plan: GenerationPlan): Flow<GenerationEvent> = error("No generation during navigation")
            }, conversations, presets, projectionDispatcher = dispatcher.dispatcher)) },
            connections = { keep(ModelConnectionsViewModel(connections, ProbeService(ModelGateway({ null }), connections))) },
            presets = { keep(PresetViewModel(presets)) }, persona = { keep(PersonaViewModel(personas)) })
    }

    private class Fixture(
        val characterId: String, val conversationId: String, val library: CharacterLibraryViewModel,
        chat: () -> ChatViewModel, connections: () -> ModelConnectionsViewModel,
        presets: () -> PresetViewModel, persona: () -> PersonaViewModel,
    ) {
        private val chat by lazy(chat)
        private val connections by lazy(connections)
        private val presets by lazy(presets)
        private val persona by lazy(persona)
        @androidx.compose.runtime.Composable fun Content() {
            TavernPlayerApp({ chat }, { connections }, library, { presets }, { persona })
        }
    }
}
