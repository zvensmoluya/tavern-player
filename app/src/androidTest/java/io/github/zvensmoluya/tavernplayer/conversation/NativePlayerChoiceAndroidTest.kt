package io.github.zvensmoluya.tavernplayer.conversation

import android.graphics.Bitmap
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
            compose.setContent { TavernPlayerTheme { ChatRoute(route.value, remember { PresetViewModel(graph.presetRepository) }, {}, {}, {}) } }
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
            val untransformed = NativeAdaptationRuntime().ingestAssistantMessage(native,
                "<UpdateVariable><JSONPatch>[{\"op\":\"replace\",\"path\":\"/主角/变身\",\"value\":\"未变身\"}]</JSONPatch></UpdateVariable>", saved.runtimeState).runtimeState
            runBlocking { conversations.save(saved.copy(runtimeState = untransformed)) }
            compose.runOnUiThread { active = newViewModel().also { it.loadConversation(record.id) }; route.value = active }
            awaitLoaded()
            compose.onNodeWithTag("openNativeDetails").performClick()
            compose.onNodeWithTag("native-choice-voluntary-defeat").assertIsNotEnabled()
            screenshot("unavailable")
        } finally { root.deleteRecursively() }
    }
}
