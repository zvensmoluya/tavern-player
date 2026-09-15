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
class NativeBindingLiveAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedRealConversationRendersBoundViewsWithoutNetwork() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("bindingReplay") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, File(context.filesDir, "binding-live-result.txt").readText())
        val record = Json.decodeFromString<ConversationRecord>(File(root, "conversation-2.json").readText())
        val adaptation = checkNotNull(record.character.nativeAdaptation)
        val reader = ConversationStateReader(adaptation, record.runtimeState)
        val binding = adaptation.stateBindings.single { it.path == "/stat_data/物品栏" }
        val view = adaptation.collections.single { it.stateKey == binding.key }
        val opening = ConversationStateReader(adaptation, record.turns.first().selected.runtimeStateAfter!!)
        val name = (reader[binding.key]!!.jsonObject.keys - opening[binding.key]!!.jsonObject.keys).single()
        val rows = NativeCollectionDisplay.rows(view, reader)!!
        val index = rows.indexOfFirst { it.key == name }
        val quantity = view.fields.single { it.path == "/数量" || it.key == "数量" }
        val tag = "native-collection-${view.id}-item-$index-${quantity.key}"
        compose.setContent {
            TavernPlayerTheme {
                androidx.compose.foundation.lazy.LazyColumn(androidx.compose.ui.Modifier.testTag("binding-replay")) {
                    adaptation.status?.let { status -> item { NativeStatusCard(status, reader) } }
                    adaptation.collections.forEach { collection -> item { NativeCollectionCard(collection, reader) } }
                }
            }
        }
        compose.onNodeWithTag("binding-replay").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithTag(tag).assertTextEquals("2").assertIsDisplayed()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(root, "inventory-verified.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun compiledOriginalCardUpdatesOneStateAndRendersInventoryOnAndroid() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("bindingLive") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configFile = File(context.filesDir, "binding-live-config.json")
        val config = Json.parseToJsonElement(configFile.readText()).jsonObject
        check(configFile.delete())
        val root = File(context.cacheDir, "binding-live-${System.currentTimeMillis()}").apply { mkdirs() }
        File(context.filesDir, "binding-live-result.txt").writeText(root.name)
        fun report(name: String, text: String) { File(root, name).writeText(text) }
        fun value(key: String) = config.getValue(key).jsonPrimitive.content
        val secret = MemoryCredentials()
        val connections = ConnectionRepository(MemoryConnections(), secret, { ModelCatalog(emptyList(), false) })
        val connection = connections.save(ConnectionDraft("binding-live", "Binding verification", "test",
            ModelProtocol.valueOf(value("protocol")), value("baseUrl"), value("model")), value("apiKey"), false)
        val characters = CharacterRepository(root)
        val source = instrumentation.context.assets.open("ejs/c04-card.png").use { it.readBytes() }
        val imported = characters.import(source, "sample-c04.png") as CharacterSaveResult.Saved
        val adaptation = Json.decodeFromString<NativeAdaptation>(File(context.filesDir, "binding-live-adaptation.json").readText())
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
                    nextVariant = vm::nextVariant, editMessage = vm::editMessage)) }
            }
            val inventory = adaptation.stateBindings.single { it.path == "/stat_data/物品栏" }
            val view = adaptation.collections.single { it.stateKey == inventory.key }
            suspend fun send(text: String, turn: Int) {
                withContext(Dispatchers.Main) { vm.updateInput(text); vm.send() }
                withTimeout(360_000) { vm.uiState.first { !it.busy && it.messages.size >= 1 + turn * 2 } }
                val saved = repository.blockingGet(initial.id)!!
                report("conversation-$turn.json", Json.encodeToString(saved))
                report("trace-$turn.json", vm.uiState.value.lastTrace?.let { Json.encodeToString(it.plan) } ?: "null")
                assertEquals("Actual model reply did not complete; inspect private trace", ChatMessageStatus.COMPLETE, vm.uiState.value.messages.last().status)
                assertTrue(saved.runtimeState.conversationState.values.isEmpty())
                assertTrue(plans.last().trace.any { it.stage == "ejs" })
                assertFalse(plans.last().messages.any { "<%" in it.content })
                assertEquals(saved.runtimeState.mvuState!!.data["stat_data"]!!.jsonObject["物品栏"], vm.uiState.value.nativeState[inventory.key])
            }
            send("我把三份“样本茶包”收进物品栏，数量为3，描述为普通茶包。用一句话回应，并在回复末尾按世界书变量协议输出完整的 <UpdateVariable><Analysis>...</Analysis><JSONPatch>...</JSONPatch></UpdateVariable>，执行物品记录。不要省略变量更新块。", 1)
            val first = repository.blockingGet(initial.id)!!
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
            val second = repository.blockingGet(initial.id)!!
            assertEquals("Second real reply must update remaining quantity", 2, quantity(second.runtimeState))
            assertEquals("MVU card must not call a second model to confirm copied Player state", 2, plans.size)
            assertEquals(3, quantity(first.runtimeState))
            val firstMessage = vm.uiState.value.messages[2]
            assertEquals(JsonPrimitive(3), NativeStatePath.read(firstMessage.nativeStateAfter!![inventory.key], "$itemPointer/数量"))
            compose.onNodeWithTag("openNativeDetails").performClick()
            compose.onNodeWithTag("nativeDetails").performScrollToNode(hasTestTag("native-collection-${view.id}"))
            compose.onNodeWithText(itemName, useUnmergedTree = true).assertIsDisplayed()
            val quantityField = view.fields.single { it.path == "/数量" || it.key == "数量" }
            val rows = NativeCollectionDisplay.rows(view, vm.uiState.value.nativeState)!!
            assertEquals("2", rows.single { it.key == itemName }.text(quantityField))
            val rowIndex = rows.indexOfFirst { it.key == itemName }
            compose.onNode(hasText("2") and hasAnyAncestor(hasTestTag("native-collection-${view.id}-item-$rowIndex")),
                useUnmergedTree = true).assertIsDisplayed()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(root, "inventory.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val restored = ConversationRepository(root, PromptCompiler(), mvuRuntime = mvu).blockingGet(initial.id)!!
            assertEquals(second.runtimeState, restored.runtimeState)
            assertEquals(vm.uiState.value.nativeState[inventory.key], ConversationStateReader(adaptation, restored.runtimeState)[inventory.key])
            report("result.json", buildJsonObject {
                put("sample", "C-04"); put("model", connection.selectedModel); put("realChatRequests", plans.size)
                put("initialQuantity", 3); put("remainingQuantity", 2); put("playerStateCount", restored.runtimeState.conversationState.values.size)
                put("historyVerified", true); put("diskRecoveryVerified", true); put("nativeInventoryDisplayed", true)
            }.toString())
        } finally {
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
