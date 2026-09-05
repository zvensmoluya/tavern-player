package io.github.zvensmoluya.tavernplayer.conversation

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zvensmoluya.tavernplayer.app.AppGraph
import io.github.zvensmoluya.tavernplayer.characters.CharacterRepository
import io.github.zvensmoluya.tavernplayer.characters.CharacterSaveResult
import io.github.zvensmoluya.tavernplayer.characters.NativeAdaptationInstallResult
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.BuiltInPresets
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import io.github.zvensmoluya.tavernplayer.presets.PresetViewModel
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic device lifecycle evidence; this test never calls a model. */
@RunWith(AndroidJUnit4::class)
class NativePlayerChoiceAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun choicePreviewCancellationCommitAndRestoreUseTheProductionRoute() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val root = File(context.cacheDir, "choice-test-${UUID.randomUUID()}")
        val output = File(context.filesDir, "native-choice-verification").apply { mkdirs() }
        try {
            val graph = AppGraph(context)
            val characters = CharacterRepository(root)
            val native = Json.decodeFromString<NativeAdaptation>(assets.open("native-adaptation/pressure-card-manual.json").bufferedReader().use { it.readText() })
            val character = runBlocking {
                val saved = characters.import(assets.open("pressure-card.png").use { it.readBytes() }, "pressure-card.png") as CharacterSaveResult.Saved
                (characters.installNativeAdaptation(saved.character.id, native) as NativeAdaptationInstallResult.Installed).character
            }
            val conversations = ConversationRepository(root, graph.promptCompiler)
            val record = runBlocking { conversations.create(character, Persona("choice-audit", "旅人"), BuiltInPresets.default) }
            val noGeneration = object : ConversationGenerator {
                override fun stream(connection: StoredConnection, plan: GenerationPlan): Flow<GenerationEvent> = error("Choice must not send")
            }
            fun newViewModel() = ChatViewModel(graph.connectionRepository, graph.promptCompiler, noGeneration,
                ConversationRepository(root, graph.promptCompiler), graph.presetRepository)
            lateinit var active: ChatViewModel
            lateinit var route: MutableState<ChatViewModel>
            compose.runOnUiThread { active = newViewModel().also { it.loadConversation(record.id) }; route = mutableStateOf(active) }
            compose.setContent { TavernPlayerTheme {
                Box(Modifier.widthIn(max = 320.dp)) {
                    ChatRoute(route.value, remember { PresetViewModel(graph.presetRepository) }, {}, {}, {})
                }
            } }
            fun awaitLoaded() = compose.waitUntil(15_000) { active.uiState.value.conversationId == record.id && !active.uiState.value.busy }
            fun screenshot(name: String) {
                compose.mainClock.advanceTimeBy(500)
                compose.waitForIdle()
                // WindowManager's dialog fade uses real time, outside the Compose test clock.
                android.os.SystemClock.sleep(400)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            fun openChoice() {
                compose.onNodeWithTag("openNativeDetails").performClick()
                compose.onNodeWithTag("native-choice-voluntary-defeat").performScrollTo().performClick()
                compose.onNodeWithTag("native-choice-preview").assertExists()
            }
            awaitLoaded()
            compose.runOnUiThread { active.updateInput("尚未发送的计划") }
            compose.onNodeWithTag("openNativeDetails").performClick()
            compose.onNodeWithTag("native-choice-voluntary-defeat").assertIsNotEnabled()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.runOnUiThread { active.updateInput("") }
            openChoice()
            screenshot("preview")
            compose.onNodeWithTag("native-choice-cancel").performClick()
            assertEquals("", active.uiState.value.input)
            assertEquals(JsonPrimitive("对等"), active.uiState.value.conversationState["protagonist-battle"])
            openChoice()
            compose.onNodeWithTag("native-choice-confirm").performClick()
            compose.waitUntil(15_000) { !active.uiState.value.busy && active.uiState.value.input == native.playerChoices.single().draft }
            val saved = checkNotNull(ConversationRepository(root, graph.promptCompiler).get(record.id))
            assertEquals(1, saved.turns.size)
            assertEquals(JsonPrimitive("战败"), saved.runtimeState.conversationState.values["protagonist-battle"])
            assertEquals(1, saved.turns.single().selected.playerChoiceCommits.size)
            compose.runOnUiThread { active = newViewModel().also { it.loadConversation(record.id) }; route.value = active }
            awaitLoaded()
            assertEquals(saved.draft, active.uiState.value.input)
            assertEquals(JsonPrimitive("战败"), active.uiState.value.conversationState["protagonist-battle"])
            compose.onNodeWithTag("native-choice-receipt-voluntary-defeat").assertIsDisplayed()
            screenshot("restored")
            // A real adapter update supplies the disabled fixture; no model response is fabricated as live evidence.
            val thought = "训练已经结束。".repeat(16) + "现在可以安心整理巡逻笔记。"
            val laterSource = "训练结束，她解除变身。<UpdateVariable><JSONPatch>[{\"op\":\"replace\",\"path\":\"/主角/变身\",\"value\":\"未变身\"},{\"op\":\"replace\",\"path\":\"/关系/新井晴/心里话\",\"value\":\"$thought\"}]</JSONPatch></UpdateVariable>"
            val untransformed = NativeAdaptationRuntime().ingestAssistantMessage(native, laterSource, saved.runtimeState).runtimeState
            val later = saved.withDraft("").copy(runtimeState = untransformed, turns = saved.turns + listOf(
                ConversationTurn("status-user-turn", MessageRole.USER, listOf(MessageVariant("status-user-v", ConversationMessage("status-user", MessageRole.USER, "结束训练。", "旅人")))),
                ConversationTurn("status-later-turn", MessageRole.ASSISTANT, listOf(MessageVariant("status-later-v", ConversationMessage("status-later", MessageRole.ASSISTANT, "训练结束，她解除变身。", "向导", sourceText = laterSource), runtimeStateBefore = saved.runtimeState, runtimeStateAfter = untransformed)))))
            runBlocking { conversations.save(later) }
            compose.runOnUiThread { active = newViewModel().also { it.loadConversation(record.id) }; route.value = active }
            awaitLoaded()
            compose.onNodeWithTag("openNativeDetails").performClick()
            compose.onNodeWithTag("native-choice-voluntary-defeat").assertIsNotEnabled()
            compose.onNodeWithTag("native-state-protagonist-battle").performScrollTo().assertTextEquals("无战斗")
            compose.onNodeWithTag("native-recorded-protagonist-battle").assertTextEquals("记录值：战败")
            screenshot("unavailable")
            compose.onNodeWithTag("native-recorded-protagonist-battle").assertIsDisplayed()
            compose.onNodeWithTag("native-state-arai-thought").performScrollTo().assertTextEquals(thought)
            screenshot("long-status-320dp")
            compose.onNodeWithTag("native-state-arai-thought").assertIsDisplayed()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("chatContent").performScrollToIndex(0)
            compose.onNodeWithTag("viewNativeState-${saved.turns.first().selected.message.id}").performScrollTo().performClick()
            compose.onNodeWithTag("native-state-protagonist-transformation").performScrollTo().assertTextEquals("已变身")
            compose.onNodeWithTag("native-state-protagonist-battle").assertTextEquals("战败")
            screenshot("historical-state-320dp")
            compose.onNodeWithTag("native-state-protagonist-battle").assertIsDisplayed()
            assertEquals(JsonPrimitive("未变身"), active.uiState.value.conversationState["protagonist-transformation"])
            assertEquals(later.runtimeState, ConversationRepository(root, graph.promptCompiler).get(record.id)!!.runtimeState)
        } finally { root.deleteRecursively() }
    }
}
