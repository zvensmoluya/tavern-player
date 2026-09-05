package io.github.zvensmoluya.tavernplayer.conversation

import android.graphics.Bitmap
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

/** Exercises the production route and storage without making model requests. */
@RunWith(AndroidJUnit4::class)
class NativeOpeningAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sourceOpeningsCommitTheirOwnFormsAndRestoreThroughTheChatRoute() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val root = File(context.cacheDir, "opening-test-${UUID.randomUUID()}")
        val output = File(context.filesDir, "native-opening-verification").apply { mkdirs() }
        try {
            val graph = AppGraph(context)
            val characters = CharacterRepository(root)
            val character = runBlocking {
                val saved = characters.import(assets.open("pressure-card.png").use { it.readBytes() }, "pressure-card.png") as CharacterSaveResult.Saved
                val adaptation = Json.decodeFromString<NativeAdaptation>(assets.open("native-adaptation/pressure-card-manual.json").bufferedReader().use { it.readText() })
                (characters.installNativeAdaptation(saved.character.id, adaptation) as NativeAdaptationInstallResult.Installed).character
            }
            val conversations = ConversationRepository(root, graph.promptCompiler)
            val records = runBlocking {
                (0..2).map { conversations.create(character, Persona("opening-audit", "旅人"), BuiltInPresets.default) }
            }
            val noGeneration = object : ConversationGenerator {
                override fun stream(connection: StoredConnection, plan: GenerationPlan): Flow<GenerationEvent> = error("Setup must not generate a reply")
            }
            fun newViewModel() = ChatViewModel(graph.connectionRepository, graph.promptCompiler, noGeneration,
                ConversationRepository(root, graph.promptCompiler), graph.presetRepository)
            lateinit var active: ChatViewModel
            lateinit var route: MutableState<ChatViewModel>
            compose.runOnUiThread {
                active = newViewModel().also { it.loadConversation(records[0].id) }
                route = mutableStateOf(active)
            }
            compose.setContent {
                TavernPlayerTheme { ChatRoute(route.value, remember { PresetViewModel(graph.presetRepository) }, {}, {}, {}) }
            }
            fun awaitLoaded(id: String) = compose.waitUntil(15_000) {
                active.uiState.value.conversationId == id && !active.uiState.value.busy
            }
            fun screenshot(name: String) {
                compose.waitForIdle()
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            fun chooseOpening(index: Int) {
                compose.onNodeWithTag("chatContent").performScrollToIndex(0)
                compose.onNodeWithTag("native-opening-$index").performClick()
                compose.waitUntil(10_000) { !active.uiState.value.busy && active.uiState.value.messages.single().openingSourceIndex == index }
            }
            fun fill(id: String, text: String) = compose.onNodeWithTag("native-form-field-$id").performScrollTo().performTextReplacement(text)
            records.forEachIndexed { index, record ->
                compose.runOnUiThread { active.loadConversation(record.id) }
                awaitLoaded(record.id)
                chooseOpening(index)
                val formId = if (index == 0) "custom-opening-contract" else "preset-opening-$index"
                if (index == 0) {
                    screenshot("opening-selector")
                    File(output, "opening-display.txt").writeText(sanitizeCardText(active.uiState.value.messages.single().displayContent))
                    compose.onNodeWithTag("native-form-option-body-TS魔法少女").assertIsDisplayed()
                }
                compose.onNodeWithTag("native-form-option-body-TS魔法少女").performScrollTo().performClick()
                fill("extra-power", "照明魔法")
                fill("opening-notes", "先进行安全的巡逻训练")
                if (index == 0) {
                    fill("contract-time", "清晨")
                    fill("contract-place", "训练场")
                    compose.onNodeWithTag("native-form-option-witnesses-白鸟优里").performScrollTo().performClick()
                } else {
                    compose.onNodeWithTag("native-form-field-contract-time").assertDoesNotExist()
                    // Switching away and back must not discard this route's unsent form input.
                    chooseOpening(0)
                    chooseOpening(index)
                    compose.onNodeWithTag("native-form-field-opening-notes").performScrollTo().assertTextContains("先进行安全的巡逻训练")
                }
                compose.onNodeWithTag("native-form-submit-$formId").performScrollTo()
                screenshot("opening-$index-form")
                compose.onNodeWithTag("native-form-submit-$formId").performClick()
                compose.waitUntil(15_000) { !active.uiState.value.busy && active.uiState.value.input.contains("照明魔法") }
                val saved = checkNotNull(ConversationRepository(root, graph.promptCompiler).get(record.id))
                assertEquals(if (index == 0) 3 else index, saved.turns.single().selected.openingSourceIndex)
                assertEquals(JsonPrimitive("TS魔法少女"), saved.runtimeState.conversationState.values["protagonist-body"])
                assertEquals(formId, saved.runtimeState.setupCommit?.formId)
                assertTrue(saved.draft.contains("先进行安全的巡逻训练"))
                assertEquals(index != 0, saved.draft.contains("不要把开场白再复述"))
                compose.runOnUiThread {
                    active = newViewModel().also { it.loadConversation(record.id) }
                    route.value = active
                }
                awaitLoaded(record.id)
                assertEquals(saved.draft, active.uiState.value.input)
                assertEquals(saved.turns.single().selected.openingSourceIndex, active.uiState.value.messages.single().openingSourceIndex)
                assertTrue(active.uiState.value.openingChoices.isEmpty())
                assertFalse(active.uiState.value.running)
                screenshot("opening-$index-restored")
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
