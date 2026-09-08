package io.github.zvensmoluya.tavernplayer.conversation

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zvensmoluya.modelgateway.*
import io.github.zvensmoluya.modelgateway.catalog.ModelCatalog
import io.github.zvensmoluya.tavernplayer.characters.*
import io.github.zvensmoluya.tavernplayer.connections.*
import io.github.zvensmoluya.tavernplayer.content.*
import io.github.zvensmoluya.tavernplayer.conversation.ejs.QuickJsEjsRuntime
import io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuConversationRuntime
import io.github.zvensmoluya.tavernplayer.presets.ActivePresetSource
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real provider test. Config is pushed to app-private storage, never packaged in an APK. */
@RunWith(AndroidJUnit4::class)
class NativeSurfaceLiveAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compiledScriptUpdatesStateAndRendersInventoryOnAndroid() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("surfaceLive") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configFile = File(context.filesDir, "surface-live-config.json")
        val config = Json.parseToJsonElement(configFile.readText()).jsonObject
        check(configFile.delete())
        val root = File(context.cacheDir, "surface-live-${System.currentTimeMillis()}").apply { mkdirs() }
        File(context.filesDir, "surface-live-result.txt").writeText(root.name)
        fun report(name: String, text: String) { File(root, name).writeText(text) }
        fun value(key: String) = config.getValue(key).jsonPrimitive.content
        val secret = MemoryCredentials()
        val connections = ConnectionRepository(MemoryConnections(), secret, { ModelCatalog(emptyList(), false) })
        val connection = connections.save(ConnectionDraft("surface-live", "Binding verification", "test",
            ModelProtocol.valueOf(value("protocol")), value("baseUrl"), value("model")), value("apiKey"), false)
        val characters = CharacterRepository(root)
        val source = instrumentation.context.assets.open("ejs/c04-card.png").use { it.readBytes() }
        val imported = characters.import(source, "sample-c04.png") as CharacterSaveResult.Saved
        val adaptation = Json.decodeFromString<NativeAdaptation>(File(context.filesDir, "surface-live-adaptation.json").readText())
        assertTrue(characters.installNativeAdaptation(imported.character.id, adaptation) is NativeAdaptationInstallResult.Installed)
        assertTrue(adaptation.state.isEmpty())
        val character = characters.get(imported.character.id)!!
        val mvu = MvuConversationRuntime { context.assets.open("mvu/runtime.js").bufferedReader().use { it.readText() } }
        val ejs = QuickJsEjsRuntime(loadBundle = { context.assets.open("ejs/runtime.js").bufferedReader().use { it.readText() } })
        val presets = object : ActivePresetSource {
            override val activePreset = MutableStateFlow(BuiltInPresets.default.copy(generationSettings =
                BuiltInPresets.default.generationSettings.copy(maxOutputTokens = 4096)))
        }
        val repository = ConversationRepository(root, PromptCompiler(), mvuRuntime = mvu)
        val initial = repository.create(character, Persona("binding-test", "测试访客"), presets.captureActive())
        val raw = ModelGatewayConversationGenerator(ModelGateway(secret, PlayerModelHttpClient.create()), connections)
        val plans = mutableListOf<GenerationPlan>()
        val generator = object : ConversationGenerator {
            override suspend fun validateTokens(connection: StoredConnection, plan: GenerationPlan) = raw.validateTokens(connection, plan)
            override fun stream(connection: StoredConnection, plan: GenerationPlan) = flow {
                plans += plan
                report("request-${plans.size}.json", Json.encodeToString(plan))
                raw.stream(connection, plan).collect { emit(it) }
            }
        }
        val vm = withContext(Dispatchers.Main) { ChatViewModel(connections, PromptCompiler(), StateConfirmingConversationGenerator(generator),
            repository, presets, mvuRuntime = mvu, ejsRuntime = ejs) }
        try {
            withContext(Dispatchers.Main) { vm.loadConversation(initial.id) }
            withTimeout(20_000) { vm.uiState.first { it.conversationId == initial.id && !it.busy && !it.loadingConnections } }
            compose.setContent {
                val state by vm.uiState.collectAsState()
                TavernPlayerTheme { ChatScreen(state, actions = ChatScreenActions(vm::updateInput, vm::send, vm::cancel,
                    vm::retry, vm::selectConnection, vm::resetConversation, {}, previousVariant = vm::previousVariant,
                    nextVariant = vm::nextVariant, editMessage = vm::editMessage, invokeNativeAction = vm::invokeNativeAction)) }
            }
            val program = checkNotNull(adaptation.script)
            val runtime = io.github.zvensmoluya.tavernplayer.conversation.script.QuickJsNativeRuntime()
            withTimeout(20_000) { vm.uiState.first { it.nativeSurfaces.isNotEmpty() } }
            // Some compiler outputs preserve the original collapsed panel. Open it through its real handler.
            if (vm.uiState.value.nativeSurfaces.none { it.data.surface == NativeSurfaceType.COLLECTION }) {
                val control = vm.uiState.value.nativeSurfaces.single { surface -> surface.data.actions.any { "打开" in it.label } }
                val action = control.data.actions.single { "打开" in it.label }
                compose.onNodeWithTag("openNativeDetails").performClick()
                compose.onNodeWithTag("surface-action-${control.id}-${action.id}").performClick()
                withTimeout(20_000) { vm.uiState.first { state -> !state.busy && state.nativeSurfaces.any { it.data.surface == NativeSurfaceType.COLLECTION } } }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitForIdle()
            }
            suspend fun send(text: String, turn: Int) {
                withContext(Dispatchers.Main) { vm.updateInput(text); vm.send() }
                withTimeout(360_000) { vm.uiState.first { !it.busy && it.messages.size >= 1 + turn * 2 } }
                val saved = repository.get(initial.id)!!
                report("conversation-$turn.json", Json.encodeToString(saved))
                report("trace-$turn.json", vm.uiState.value.lastTrace?.let { Json.encodeToString(it.plan) } ?: "null")
                assertEquals("Actual model reply did not complete; inspect private trace", ChatMessageStatus.COMPLETE, vm.uiState.value.messages.last().status)
                assertTrue(saved.runtimeState.conversationState.values.isEmpty())
                assertTrue(plans.last().trace.any { it.stage == "ejs" })
                assertFalse(plans.last().messages.any { "<%" in it.content })
                report("surfaces-$turn.json", Json.encodeToString(runtime.present(program, saved.nativeContext(), saved.nativeRevision()).map { it.data }))
            }
            send("我把三份“样本茶包”收进物品栏，数量为3，描述为普通茶包。用一句话回应，并在回复末尾按世界书变量协议输出完整的 <UpdateVariable><Analysis>...</Analysis><JSONPatch>...</JSONPatch></UpdateVariable>，执行物品记录。不要省略变量更新块。", 1)
            val first = repository.get(initial.id)!!
            fun inventoryKeys(state: ConversationRuntimeState) = NativeStatePath.read(state.mvuState?.data, "/stat_data/物品栏")!!.jsonObject.keys
            val added = inventoryKeys(first.runtimeState) - inventoryKeys(initial.runtimeState)
            assertEquals("First reply must add exactly one inventory record", 1, added.size)
            // The model owns item naming. Read the actual inserted key instead of rewriting its output.
            val itemName = added.single()
            val itemPointer = "/" + itemName.replace("~", "~0").replace("/", "~1")
            fun quantity(state: ConversationRuntimeState): Int? = NativeStatePath.read(state.mvuState?.data,
                "/stat_data/物品栏$itemPointer/数量")?.jsonPrimitive?.intOrNull
            assertEquals("First real reply must insert three items", 3, quantity(first.runtimeState))
            send("我取用一份物品栏里名为“$itemName”的物品，其余保留。用一句话回应，并在回复末尾输出世界书要求的完整 <UpdateVariable><Analysis>...</Analysis><JSONPatch>...</JSONPatch></UpdateVariable>，更新剩余数量。不要省略变量更新块。", 2)
            val second = repository.get(initial.id)!!
            assertEquals("Second real reply must update remaining quantity", 2, quantity(second.runtimeState))
            assertEquals("MVU card must not call a second model to confirm copied Player state", 2, plans.size)
            assertEquals(3, quantity(first.runtimeState))
            assertEquals(3, quantity(second.turns[2].selected.runtimeStateAfter!!))
            withTimeout(20_000) { vm.uiState.first { state -> state.nativeSurfaces.any { surface -> surface.data.items.any { itemName in it.title } } } }
            val inventorySurface = vm.uiState.value.nativeSurfaces.single { surface -> surface.data.items.any { itemName in it.title } }
            val item = inventorySurface.data.items.single { itemName in it.title }
            assertTrue("Quantity must be rendered", Regex("(?:^|[^0-9])2(?:[^0-9]|$)").containsMatchIn(listOf(item.title, item.description, item.status).joinToString(" ")))
            compose.onNodeWithTag("openNativeDetails").performClick()
            compose.onNodeWithTag("nativeDetails").performScrollToNode(hasText(item.title))
            compose.onNodeWithText(item.title, useUnmergedTree = true).assertIsDisplayed()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(root, "inventory.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val restored = ConversationRepository(root, PromptCompiler(), mvuRuntime = mvu).get(initial.id)!!
            assertEquals(second.runtimeState, restored.runtimeState)
            assertEquals(runtime.present(program, second.nativeContext(), second.nativeRevision()), runtime.present(program, restored.nativeContext(), restored.nativeRevision()))
            // Exercise actual regeneration and candidate restoration without changing model output.
            withContext(Dispatchers.Main) { vm.regenerate() }
            withTimeout(360_000) { vm.uiState.first { !it.busy && it.messages.last().variantCount == 2 } }
            val regenerated = repository.get(initial.id)!!
            report("conversation-regenerated.json", Json.encodeToString(regenerated))
            assertEquals(ChatMessageStatus.COMPLETE, vm.uiState.value.messages.last().status)
            assertEquals("Regeneration must consume from the pre-reply quantity", 2, quantity(regenerated.runtimeState))
            withContext(Dispatchers.Main) { vm.previousVariant() }
            withTimeout(20_000) { vm.uiState.first { !it.busy && it.messages.last().variantIndex == 0 } }
            withTimeout(20_000) { repository.conversations.first { records -> records.any { it.id == initial.id && it.turns.last().selectedVariantIndex == 0 } } }
            assertEquals(second.runtimeState, repository.get(initial.id)!!.runtimeState)
            withContext(Dispatchers.Main) { vm.nextVariant() }
            withTimeout(20_000) { vm.uiState.first { !it.busy && it.messages.last().variantIndex == 1 } }
            withTimeout(20_000) { repository.conversations.first { records -> records.any { it.id == initial.id && it.turns.last().selectedVariantIndex == 1 } } }
            assertEquals(regenerated.runtimeState, repository.get(initial.id)!!.runtimeState)
            val reloaded = ConversationRepository(root, PromptCompiler(), mvuRuntime = mvu).get(initial.id)!!
            assertEquals(regenerated.runtimeState, reloaded.runtimeState)
            report("result.json", buildJsonObject {
                put("sample", "C-04"); put("model", connection.selectedModel); put("realChatRequests", plans.size)
                put("initialQuantity", 3); put("remainingQuantity", 2); put("playerStateCount", restored.runtimeState.conversationState.values.size)
                put("candidateSwitchVerified", true); put("historyVerified", true); put("diskRecoveryVerified", true); put("nativeInventoryDisplayed", true)
            }.toString())
        } finally {
            report("final-ui-status.json", buildJsonObject {
                put("busy", vm.uiState.value.busy)
                put("message", vm.uiState.value.message)
                put("surfaceError", vm.uiState.value.nativeSurfaceError)
                put("messageCount", vm.uiState.value.messages.size)
            }.toString())
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(root, "final-screen.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            withContext(Dispatchers.Main) { androidx.lifecycle.ViewModelStore().apply { put("live", vm); clear() } }
            secret.clear()
        }
    }

    private class MemoryConnections : ConnectionStateStore {
        override val state = MutableStateFlow(GatewayAppState())
        override suspend fun update(transform: (GatewayAppState) -> GatewayAppState) { state.value = transform(state.value) }
    }
    private class MemoryCredentials : CredentialStore, CredentialResolver {
        private val values = mutableMapOf<String, String>()
        override suspend fun put(credentialId: String, secret: String) { values[credentialId] = secret }
        override suspend fun getOrNull(credentialId: String) = values[credentialId]
        override suspend fun delete(credentialId: String) { values.remove(credentialId) }
        override suspend fun contains(credentialId: String) = credentialId in values
        override suspend fun resolve(credentialRef: String) = values[credentialRef]?.let(::SecretValue)
        fun clear() = values.clear()
    }
}
