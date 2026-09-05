package io.github.zvensmoluya.tavernplayer.conversation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.modelgateway.ModelGateway
import io.github.zvensmoluya.modelgateway.transport.GatewayTransport
import io.github.zvensmoluya.tavernplayer.app.AppGraph
import io.github.zvensmoluya.tavernplayer.characters.CharacterSaveResult
import io.github.zvensmoluya.tavernplayer.characters.NativeAdaptationInstallResult
import io.github.zvensmoluya.tavernplayer.connections.ConnectionDraft
import io.github.zvensmoluya.tavernplayer.content.*
import io.github.zvensmoluya.tavernplayer.presets.PresetLibraryImportResult
import io.github.zvensmoluya.tavernplayer.presets.PresetViewModel
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 需显式把测试配置放入目标应用 cache/native-live.env。密钥不进入测试 APK 或报告。 */
@RunWith(AndroidJUnit4::class)
class NativeGameplayLiveAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun doctorFormPreservesInputsAcrossTwoTurns() = verify("doctor")
    @Test fun pressureSetupSurvivesReloadAndStateConfirmation() = verify("pressure")
    @Test fun secondPressurePreservesProgressionAndHistoricalPanels() = verify("second-pressure")

    private fun verify(scenario: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configFile = File(context.cacheDir, "native-live.env")
        assumeTrue("explicit device live configuration is required", configFile.isFile)
        val env = configFile.readLines().filter { it.contains('=') && !it.trim().startsWith('#') }.associate {
            it.substringBefore('=').trim() to it.substringAfter('=').trim().trim('\'', '"')
        }
        val graph = AppGraph(context)
        val assets = instrumentation.context.assets
        val output = File(context.filesDir, "native-gameplay-verification/${System.currentTimeMillis()}").apply { mkdirs() }
        // Observe response bytes only. Never record authorization headers or alter the stream.
        val requestIndex = AtomicInteger()
        val observedClient = GatewayTransport.defaultClient().newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body
            val capture = File(output, "response-${requestIndex.incrementAndGet()}.sse").outputStream()
            val observedSource = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val previousSize = sink.size
                    val count = super.read(sink, byteCount)
                    if (count > 0) {
                        val copy = Buffer()
                        sink.copyTo(copy, previousSize, count)
                        capture.write(copy.readByteArray())
                        capture.flush()
                    }
                    return count
                }
                override fun close() {
                    try { super.close() } finally { capture.close() }
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = observedSource
            }).build()
        }.build()
        val observedGenerator = StateConfirmingConversationGenerator(ModelGatewayConversationGenerator(
            ModelGateway(graph.credentialStore, observedClient), graph.connectionRepository,
        ))
        val connection = runBlocking { graph.connectionRepository.save(
            ConnectionDraft("native-gameplay-live", "社区卡实测", "native-gameplay-live",
                ModelProtocol.valueOf(env.getValue("TAVERN_TEST_PROTOCOL").uppercase()),
                env.getValue("TAVERN_TEST_BASE_URL"), env.getValue("TAVERN_TEST_MODEL")),
            env.getValue("TAVERN_TEST_API_KEY"), false,
        ) }
        val importedPreset = runBlocking {
            graph.presetRepository.importPreset(assets.open("community-preset.json").use { it.readBytes() }, "夏瑾 天琴座 Beta 3.4.json")
        } as PresetLibraryImportResult.Saved
        val preset = runBlocking {
            graph.presetRepository.save(importedPreset.preset.copy(
                generationSettings = importedPreset.preset.generationSettings.copy(maxOutputTokens = 32768,
                    reasoningEffort = PresetReasoningEffort.LOW,
                    disabledParameters = importedPreset.preset.generationSettings.disabledParameters - setOf(PresetGenerationParameter.OUTPUT_LIMIT, PresetGenerationParameter.REASONING_EFFORT)),
            )).also { graph.presetRepository.activate(it.id) }
        }
        fun install(card: String, fixture: String): CharacterAsset = runBlocking {
            val saved = graph.characterRepository.import(assets.open(card).use { it.readBytes() }, card) as CharacterSaveResult.Saved
            val adaptation = Json.decodeFromString<NativeAdaptation>(assets.open("native-adaptation/$fixture").bufferedReader().use { it.readText() })
            val installed = graph.characterRepository.installNativeAdaptation(saved.character.id, adaptation)
            check(installed is NativeAdaptationInstallResult.Installed)
            installed.character
        }
        val doctor = install("doctor-card.png", "doctor-manual.json")
        val pressure = install("pressure-card.png", "pressure-card-manual.json")
        val secondPressure = install("second-pressure-card.png", "second-pressure-manual.json")
        val doctorRecord = runBlocking { graph.conversationRepository.create(doctor, Persona("native-live", "林澈"), preset) }
        val pressureRecord = runBlocking { graph.conversationRepository.create(pressure, Persona("native-live", "林澈"), preset) }
        fun newViewModel(repository: ConversationRepository = graph.conversationRepository) = ChatViewModel(
            graph.connectionRepository, graph.promptCompiler, observedGenerator, repository, graph.presetRepository,
        )
        lateinit var active: ChatViewModel
        lateinit var route: MutableState<ChatViewModel>
        compose.runOnUiThread {
            active = newViewModel().also { it.loadConversation(doctorRecord.id) }
            route = mutableStateOf(active)
        }
        compose.setContent {
            TavernPlayerTheme { ChatRoute(route.value, remember { PresetViewModel(graph.presetRepository) }, {}, {}, {},
                resolveAssetPath = { character, asset -> graph.characterRepository.assetFile(character, asset)?.absolutePath }) }
        }
        fun fill(field: String, text: String) {
            compose.onNodeWithTag("native-form-field-$field").performScrollTo().performTextReplacement(text)
        }
        fun choose(field: String, value: String) {
            compose.onNodeWithTag("native-form-option-$field-$value").performScrollTo().performClick()
        }
        fun screenshot(name: String) {
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        fun awaitReply(name: String) {
            compose.waitUntil(600_000) { !active.uiState.value.running }
            val state = active.uiState.value
            if (state.messages.last().message.role == MessageRole.ASSISTANT && state.messages.last().metadata != null) {
                compose.waitUntil(15_000) {
                    val persisted = graph.conversationRepository.get(checkNotNull(state.conversationId))?.turns?.lastOrNull()?.selected
                    persisted?.message?.id == state.messages.last().message.id && persisted.status == PersistedMessageStatus.COMPLETE
                }
            }
            val record = checkNotNull(graph.conversationRepository.get(checkNotNull(state.conversationId)))
            File(output, "$name.json").writeText(Json.encodeToString(record))
            File(output, "$name.txt").writeText(state.messages.lastOrNull()?.message?.sourceText.orEmpty())
            File(output, "$name-result.txt").writeText("message=${state.message}\nerror=${state.lastTrace?.error}\nusage=${state.lastTrace?.usage}\nfinish=${state.lastTrace?.finishReason}\n")
            assertEquals("${state.message}; ${state.lastTrace?.error}", MessageRole.ASSISTANT, state.messages.last().message.role)
            assertNotNull("response must originate in a model generation", state.messages.last().metadata)
            assertEquals("${state.message}; ${state.lastTrace?.error}", ChatMessageStatus.COMPLETE, state.messages.last().status)
            assertTrue(state.messages.last().displayContent.isNotBlank())
            assertFalse(state.messages.last().displayContent.contains("<UpdateVariable>"))
            screenshot(name)
        }
        compose.waitUntil(15_000) { active.uiState.value.selectedConnection?.id == connection.id }
        if (scenario == "doctor") {
            compose.onNodeWithTag("chatContent").performScrollToIndex(0)
            screenshot("doctor-form")
            fill("name", "林澈")
            fill("time", "周三下午两点")
            choose("reasons", "学业障碍")
            choose("reasons", "单纯想聊天")
            fill("description", "最近准备考试有点紧张，想聊聊如何安排休息。")
            fill("dietary", "无糖，不喝咖啡")
            compose.onNodeWithTag("native-form-submit-appointment").performScrollTo().performClick()
            assertTrue(active.uiState.value.input.contains("学业障碍、单纯想聊天"))
            assertTrue(active.uiState.value.input.contains("无糖，不喝咖啡"))
            compose.onNodeWithTag("sendMessage").performClick()
            awaitReply("doctor-first")
            compose.runOnUiThread { active.updateInput("我在椅子上坐好，把书包放到一旁，问她能不能先聊聊休息。") }
            compose.onNodeWithTag("sendMessage").performClick()
            awaitReply("doctor-second")
        }

        if (scenario == "pressure") {
            compose.runOnUiThread { active.loadConversation(pressureRecord.id) }
            choose("body", "TS魔法少女")
            fill("contract-time", "傍晚")
            fill("contract-place", "河边公园")
            fill("opening-notes", "刚结束一次训练。收起武器，解除变身，接下来没有战斗，和天海咲休息聊天。")
            choose("witnesses", "天海咲")
            compose.onNodeWithTag("native-form-submit-custom-opening-contract").performScrollTo().performClick()
            compose.waitUntil(15_000) { !active.uiState.value.setupSaving }
            assertEquals(JsonPrimitive("TS魔法少女"), active.uiState.value.conversationState["protagonist-body"])
            val committed = checkNotNull(graph.conversationRepository.get(pressureRecord.id))
            assertEquals("custom-opening-contract", committed.runtimeState.setupCommit?.formId)
            val restoredRepository = ConversationRepository(context.filesDir, PromptCompiler())
            compose.runOnUiThread {
                active = newViewModel(restoredRepository).also { it.loadConversation(pressureRecord.id) }
                route.value = active
            }
            assertEquals(committed.draft, active.uiState.value.input)
            assertEquals(JsonPrimitive("TS魔法少女"), active.uiState.value.conversationState["protagonist-body"])
            compose.waitUntil(15_000) { active.uiState.value.selectedConnection != null }
            compose.onNodeWithTag("sendMessage").performClick()
            compose.waitUntil(600_000) { !active.uiState.value.running }
            compose.waitUntil(15_000) {
                val persisted = restoredRepository.get(pressureRecord.id)?.turns?.lastOrNull()?.selected
                persisted?.message?.id == active.uiState.value.messages.last().message.id && persisted.status == PersistedMessageStatus.COMPLETE
            }
            val savedPressure = checkNotNull(restoredRepository.get(pressureRecord.id))
            File(output, "pressure-first.json").writeText(Json.encodeToString(savedPressure))
            File(output, "pressure-first.txt").writeText(active.uiState.value.messages.last().message.sourceText)
            assertEquals(active.uiState.value.message, ChatMessageStatus.COMPLETE, active.uiState.value.messages.last().status)
            assertEquals(MessageRole.ASSISTANT, active.uiState.value.messages.last().message.role)
            assertTrue(active.uiState.value.messages.last().displayContent.isNotBlank())
            assertEquals(JsonPrimitive("TS魔法少女"), active.uiState.value.conversationState["protagonist-body"])
            assertEquals(JsonPrimitive("未变身"), active.uiState.value.conversationState["protagonist-transformation"])
            assertEquals(JsonPrimitive("无战斗"), active.uiState.value.conversationState["protagonist-battle"])
            compose.onNodeWithTag("openNativeDetails").performClick()
            compose.onNodeWithTag("native-state-protagonist-mana").assertExists()
            screenshot("pressure-state")
            val disk = ConversationRepository(context.filesDir, PromptCompiler()).get(pressureRecord.id)
            assertTrue("Persisted runtime differs after reopening the conversation repository", savedPressure.runtimeState == disk?.runtimeState)
        }
        if (scenario == "second-pressure") {
            val restoredRepository = ConversationRepository(context.filesDir, PromptCompiler())
            val secondRecord = runBlocking { restoredRepository.create(secondPressure, Persona("native-live", "林澈"), preset) }
            compose.runOnUiThread {
                active = newViewModel(restoredRepository).also { it.loadConversation(secondRecord.id) }
                route.value = active
            }
            compose.waitUntil(15_000) { active.uiState.value.selectedConnection?.id == connection.id && !active.uiState.value.busy }
            compose.runOnUiThread { active.updateInput("时间来到中午十二点，沛宁城开始下雨。我带着伞走到门前，帮林纾璃收好门边的东西，再轻声问云知意要不要一同吃午饭。按眼下的关系自然继续，不跳过矛盾。") }
            compose.onNodeWithTag("sendMessage").performClick()
            compose.waitUntil(600_000) { !active.uiState.value.running }
            var secondState = active.uiState.value
            File(output, "second-pressure-first.txt").writeText(secondState.messages.last().message.sourceText)
            assertEquals(secondState.message, MessageRole.ASSISTANT, secondState.messages.last().message.role)
            assertEquals(secondState.message, ChatMessageStatus.COMPLETE, secondState.messages.last().status)
            assertFalse("second card state confirmation failed", secondState.messages.last().stateUnconfirmed)
            assertEquals(JsonPrimitive("雨"), secondState.conversationState["weather"])
            assertEquals(JsonPrimitive("暗流涌动"), secondState.conversationState["relationship-stage"])
            assertTrue("per-message panel was not restored", secondState.messages.last().nativePanels.isNotEmpty())
            assertFalse(secondState.messages.last().displayContent.contains("<suihan_panel>"))
            screenshot("second-pressure-first")
            val firstPanel = secondState.messages.last().nativePanels
            compose.runOnUiThread { active.updateInput("我把伞放在门旁，先去准备午饭，告诉她们不用急着回答。让场景顺着刚才的气氛继续。") }
            compose.onNodeWithTag("sendMessage").performClick()
            compose.waitUntil(600_000) { !active.uiState.value.running }
            secondState = active.uiState.value
            assertEquals(secondState.message, MessageRole.ASSISTANT, secondState.messages.last().message.role)
            assertEquals(secondState.message, ChatMessageStatus.COMPLETE, secondState.messages.last().status)
            assertFalse(secondState.messages.last().stateUnconfirmed)
            assertEquals(firstPanel, secondState.messages[secondState.messages.lastIndex - 2].nativePanels)
            compose.waitUntil(15_000) { restoredRepository.get(secondRecord.id)?.turns?.lastOrNull()?.selected?.status == PersistedMessageStatus.COMPLETE }
            val savedSecond = checkNotNull(restoredRepository.get(secondRecord.id))
            File(output, "second-pressure-second.json").writeText(Json.encodeToString(savedSecond))
            File(output, "second-pressure-second.txt").writeText(secondState.messages.last().message.sourceText)
            assertTrue(savedSecond.runtimeState == ConversationRepository(context.filesDir, PromptCompiler()).get(secondRecord.id)?.runtimeState)
            screenshot("second-pressure-second")
        }
        File(output, "result.txt").writeText("scenario=$scenario passed\npreset=${preset.name}\nmodel=${connection.selectedModel}\n")
    }
}
