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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
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
    @Test fun pressureGameplayChoicesAcrossMultipleTurns() = verify("pressure-gameplay")
    @Test fun confirmSavedReplyWithLiveModel() = verify("recovery-replay")
    @Test fun secondPressurePreservesProgressionAndHistoricalPanels() = verify("second-pressure")
    @Test fun secondPressureMemoriesReachNextPrompt() = verify("second-pressure-memory")
    @Test fun savedSecondPressureMemoryExperiment() = verify("memory-experiment")

    private fun memoryExperiment(
        graph: AppGraph,
        gateway: ConversationGenerator,
        connection: io.github.zvensmoluya.tavernplayer.connections.StoredConnection,
        preset: PresetAsset,
        output: File,
        progress: (String) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sourceFile = File(context.cacheDir, "native-live-replay.json")
        assumeTrue("explicit saved real conversation is required", sourceFile.isFile)
        val source = Json.decodeFromString<ConversationRecord>(sourceFile.readText())
        assertEquals(3, source.character.nativeAdaptation?.memories?.size)
        val seed = runBlocking { graph.conversationRepository.save(source.copy(id = java.util.UUID.randomUUID().toString(), draft = "")) }
        File(output, "input.json").writeText(Json.encodeToString(seed))
        // Exercise real Android preparation before the UI's user-facing error classification.
        assertNotNull(NativeMemoryController.prepare(seed, source.character.nativeAdaptation!!.memories.first().id, force = true))
        val requests = java.util.Collections.synchronizedList(mutableListOf<GenerationPlan>())
        val generator = object : ConversationGenerator {
            override suspend fun validateTokens(connection: io.github.zvensmoluya.tavernplayer.connections.StoredConnection, plan: GenerationPlan) = gateway.validateTokens(connection, plan)
            override fun stream(connection: io.github.zvensmoluya.tavernplayer.connections.StoredConnection, plan: GenerationPlan): kotlinx.coroutines.flow.Flow<GenerationEvent> {
                if (plan.messages.firstOrNull()?.origin?.stage == "conversation-memory-contract") {
                    requests += plan
                    File(output, "analysis-request-${requests.size}.json").writeText(Json.encodeToString(plan))
                    progress("analysis request ${requests.size}")
                }
                return gateway.stream(connection, plan)
            }
        }
        fun model(repository: ConversationRepository) = ChatViewModel(graph.connectionRepository, graph.promptCompiler,
            generator, repository, graph.presetRepository)
        lateinit var active: ChatViewModel
        lateinit var route: MutableState<ChatViewModel>
        compose.runOnUiThread {
            active = model(graph.conversationRepository).also { it.loadConversation(seed.id) }
            route = mutableStateOf(active)
        }
        compose.setContent { TavernPlayerTheme {
            ChatRoute(route.value, remember { PresetViewModel(graph.presetRepository) }, {}, {}, {},
                resolveAssetPath = { character, asset -> graph.characterRepository.assetFile(character, asset)?.absolutePath })
        } }
        compose.waitUntil(15_000) { active.uiState.value.selectedConnection?.id == connection.id && !active.uiState.value.busy }
        fun refresh(name: String): ConversationRecord {
            compose.onNodeWithTag("openNativeDetails").performClick()
            compose.onNodeWithTag("refreshConversationMemories").performScrollTo().performClick()
            compose.waitUntil(600_000) { !active.uiState.value.running }
            val saved = checkNotNull(ConversationRepository(context.filesDir, PromptCompiler()).get(seed.id))
            File(output, "$name.json").writeText(Json.encodeToString(saved))
            File(output, "$name-result.txt").writeText(active.uiState.value.message.orEmpty())
            assertEquals(active.uiState.value.message, 3, saved.runtimeState.memories.size)
            assertEquals(seed.runtimeState.conversationState, saved.runtimeState.conversationState)
            assertEquals(seed.turns.map { it.selected.message }, saved.turns.map { it.selected.message })
            assertEquals(saved.runtimeState.memories, saved.turns.last().selected.runtimeStateAfter?.memories)
            compose.onNodeWithTag("memory-toggle-yun-relationship").performScrollTo().performClick()
            compose.onNodeWithTag("memory-body-yun-relationship").assertExists()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitForIdle()
            return saved
        }
        val first = refresh("analysis-first")
        assertEquals(3, requests.size)
        compose.runOnUiThread {
            active = model(ConversationRepository(context.filesDir, PromptCompiler())).also { it.loadConversation(seed.id) }
            route.value = active
        }
        compose.waitUntil(15_000) { active.uiState.value.selectedConnection?.id == connection.id && !active.uiState.value.busy }
        assertEquals(first.runtimeState.memories, active.uiState.value.memories)
        assertEquals("reopening must not trigger analysis", 3, requests.size)
        val updated = refresh("analysis-refresh")
        assertEquals(6, requests.size)
        for (request in requests.takeLast(3)) {
            val id = request.messages.first().origin.sourceIds.single()
            assertTrue(request.messages.last().content.contains(first.runtimeState.memories.getValue(id).content.let {
                JsonPrimitive(it).toString().removeSurrounding("\"" )
            }))
        }
        progress("six analyses saved; repository and screen restored; previous analyses reached refresh")
        val nextUser = ConversationMessage("comparison-user", MessageRole.USER,
            "午饭后我准备出门买纸墨，问她们有没有需要顺路带的东西。只继续普通日常，自然保持现有关系，不突然和解，也不要制造新冲突。", seed.persona.name)
        val instant = java.time.Instant.now()
        for ((label, runtime) in listOf("without-analysis" to updated.runtimeState.copy(memories = emptyMap()), "with-analysis" to updated.runtimeState)) {
            val compiled = graph.promptCompiler.compile(NormalGenerationInput(character = updated.character, persona = updated.persona,
                history = updated.turns.map { it.selected.message } + nextUser, preset = preset, runtimeState = runtime,
                conversationId = updated.id, generationId = "comparison", inputText = nextUser.content,
                modelId = connection.selectedModel, modelOutputTokens = 16384, evaluationInstant = instant))
            assertTrue(compiled.toString(), compiled is CompilationResult.Success)
            val plan = (compiled as CompilationResult.Success).plan
            val text = plan.messages.joinToString("\n") { it.content }
            updated.runtimeState.memories.values.forEach { note -> assertEquals(label, label == "with-analysis", note.content in text) }
            File(output, "$label-request.json").writeText(Json.encodeToString(plan))
            progress("comparison $label")
            val events = runBlocking { gateway.stream(connection, plan).toList() }
            val response = events.filterIsInstance<GenerationEvent.TextDelta>().joinToString("") { it.text }
            File(output, "$label.txt").writeText(response)
            File(output, "$label-result.txt").writeText("finish=${events.filterIsInstance<GenerationEvent.Finished>().lastOrNull()?.reason}\nusage=${events.filterIsInstance<GenerationEvent.Usage>().lastOrNull()?.value}\n")
            assertTrue(response.isNotBlank())
            assertTrue(events.filterIsInstance<GenerationEvent.Finished>().lastOrNull()?.reason in setOf("completed", "stop", "end_turn", "STOP"))
        }
        File(output, "result.txt").writeText("memory experiment passed\nmodel=${connection.selectedModel}\nSix live analyses; fresh repository and view model; two matched-history continuations.\nCadence is tested deterministically, not by a live 30-turn run.\n")
        progress("memory experiment complete")
    }

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
        fun progress(message: String) {
            File(output, "progress.txt").appendText("${System.currentTimeMillis()} $message\n")
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "native-live: $message\n") })
        }
        progress("scenario=$scenario output=${output.name}")
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
        if (scenario == "recovery-replay") {
            val replayFile = File(context.cacheDir, "native-live-replay.json")
            assumeTrue("explicit saved live reply is required", replayFile.isFile)
            val record = Json.decodeFromString<ConversationRecord>(replayFile.readText())
            val reply = record.turns.last().selected
            var calls = 0
            val gateway = ModelGatewayConversationGenerator(ModelGateway(graph.credentialStore, observedClient), graph.connectionRepository)
            val replay = object : ConversationGenerator {
                override suspend fun validateTokens(connection: io.github.zvensmoluya.tavernplayer.connections.StoredConnection, plan: GenerationPlan) =
                    gateway.validateTokens(connection, plan)
                override fun stream(connection: io.github.zvensmoluya.tavernplayer.connections.StoredConnection, plan: GenerationPlan) =
                    if (++calls == 1) flow {
                        emit(GenerationEvent.TextDelta(reply.message.sourceText))
                        emit(GenerationEvent.Finished("completed"))
                    } else gateway.stream(connection, plan)
            }
            val events = runBlocking {
                StateConfirmingConversationGenerator(replay, recoveryObserver = {
                    File(output, "recovery.txt").writeText(it)
                }).stream(connection, checkNotNull(reply.generationPlan).copy(nativeAdaptation = record.character.nativeAdaptation)).toList()
            }
            val diagnostics = events.filterIsInstance<GenerationEvent.Diagnostic>().joinToString("\n") { it.summary }
            File(output, "recovery-result.txt").writeText(diagnostics)
            progress(diagnostics)
            assertEquals(diagnostics, 1, events.filterIsInstance<GenerationEvent.AssistantStateConfirmed>().size)
            return
        }
        val importedPreset = runBlocking {
            graph.presetRepository.importPreset(assets.open("community-preset.json").use { it.readBytes() }, "夏瑾 天琴座 Beta 3.4.json")
        } as PresetLibraryImportResult.Saved
        val preset = importedPreset.preset
        runBlocking { graph.presetRepository.activate(preset.id) }
        if (scenario == "memory-experiment") {
            memoryExperiment(graph, ModelGatewayConversationGenerator(ModelGateway(graph.credentialStore, observedClient),
                graph.connectionRepository), connection, preset, output, ::progress)
            return
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
            progress("waiting $name")
            compose.waitUntil(600_000) { !active.uiState.value.running }
            val state = active.uiState.value
            if (state.messages.last().message.role == MessageRole.ASSISTANT && state.messages.last().metadata != null) {
                compose.waitUntil(15_000) {
                    val persisted = ConversationRepository(context.filesDir, graph.promptCompiler).get(checkNotNull(state.conversationId))?.turns?.lastOrNull()?.selected
                    persisted?.message?.id == state.messages.last().message.id && persisted.status != PersistedMessageStatus.STREAMING
                }
            }
            val record = checkNotNull(ConversationRepository(context.filesDir, graph.promptCompiler).get(checkNotNull(state.conversationId)))
            File(output, "$name.json").writeText(Json.encodeToString(record))
            File(output, "$name.txt").writeText(state.messages.lastOrNull()?.message?.sourceText.orEmpty())
            File(output, "$name-result.txt").writeText("message=${state.message}\nerror=${state.lastTrace?.error}\nusage=${state.lastTrace?.usage}\nfinish=${state.lastTrace?.finishReason}\ndiagnostics=${state.lastTrace?.streamDiagnostics?.joinToString("\n")}\n")
            progress("saved $name status=${state.messages.lastOrNull()?.status}")
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

        if (scenario == "pressure-gameplay") {
            compose.runOnUiThread { active.loadConversation(pressureRecord.id) }
            compose.waitUntil(15_000) { active.uiState.value.conversationId == pressureRecord.id && !active.uiState.value.busy }
            choose("body", "TS魔法少女")
            fill("extra-power", "照明魔法与光盾")
            fill("contract-time", "清晨")
            fill("contract-place", "河边公园的训练场")
            fill("opening-notes", "本次只进行普通日常和安全的魔法训练，没有色情或性行为。开场尚未变身，武器收起，暂时没有战斗。我和天海咲先确认训练安排。")
            choose("witnesses", "天海咲")
            compose.onNodeWithTag("native-form-submit-custom-opening-contract").performScrollTo().performClick()
            compose.waitUntil(15_000) { !active.uiState.value.setupSaving }
            val setup = checkNotNull(graph.conversationRepository.get(pressureRecord.id))
            assertEquals(3, setup.turns.single().selected.openingSourceIndex)
            assertEquals(JsonPrimitive("TS魔法少女"), setup.runtimeState.conversationState.values["protagonist-body"])
            compose.runOnUiThread {
                active = newViewModel(ConversationRepository(context.filesDir, graph.promptCompiler)).also { it.loadConversation(pressureRecord.id) }
                route.value = active
            }
            compose.waitUntil(15_000) { active.uiState.value.selectedConnection != null && !active.uiState.value.busy }
            assertEquals(setup.draft, active.uiState.value.input)
            compose.onNodeWithTag("sendMessage").performClick()
            awaitReply("gameplay-1-opening")
            assertFalse(active.uiState.value.messages.last().stateUnconfirmed)
            assertEquals(JsonPrimitive("未变身"), active.uiState.value.conversationState["protagonist-transformation"])
            assertEquals(JsonPrimitive("无战斗"), active.uiState.value.conversationState["protagonist-battle"])
            val manaBefore = active.uiState.value.conversationState["protagonist-mana"]

            compose.runOnUiThread { active.updateInput("我按计划变身，开始与训练假人的模拟交战，并施放一次光盾挡住它的练习弹。保持变身，先停在交战中的这一刻；依据实际施法更新资源。只描写安全训练，不加入色情内容。") }
            compose.onNodeWithTag("sendMessage").performClick()
            awaitReply("gameplay-2-training")
            assertFalse(active.uiState.value.messages.last().stateUnconfirmed)
            assertEquals(JsonPrimitive("已变身"), active.uiState.value.conversationState["protagonist-transformation"])
            val training = checkNotNull(ConversationRepository(context.filesDir, graph.promptCompiler).get(pressureRecord.id))
            File(output, "mana-observation.txt").writeText("before=$manaBefore\nafter=${training.runtimeState.conversationState.values["protagonist-mana"]}\n")
            compose.runOnUiThread { active.previewPlayerChoice("voluntary-defeat") }
            assertNotNull(active.uiState.value.choicePreview)
            compose.runOnUiThread { active.confirmPlayerChoice() }
            compose.waitUntil(15_000) { !active.uiState.value.busy }
            val chosen = checkNotNull(ConversationRepository(context.filesDir, graph.promptCompiler).get(pressureRecord.id))
            File(output, "gameplay-choice.json").writeText(Json.encodeToString(chosen))
            assertEquals(JsonPrimitive("战败"), chosen.runtimeState.conversationState.values["protagonist-battle"])
            assertEquals(training.turns.size, chosen.turns.size)
            assertEquals(1, chosen.turns.last().selected.playerChoiceCommits.size)
            val choiceDraft = active.uiState.value.input
            compose.runOnUiThread { active.updateInput(choiceDraft + "\n这只是安全演习，我举手认输，没有受伤或色情情节。请保持变身，与天海咲复盘失误，不开始下一场战斗。") }
            compose.onNodeWithTag("sendMessage").performClick()
            awaitReply("gameplay-3-choice")
            assertFalse(active.uiState.value.messages.last().stateUnconfirmed)
            assertEquals(JsonPrimitive("TS魔法少女"), active.uiState.value.conversationState["protagonist-body"])

            compose.runOnUiThread { active.updateInput("复盘结束。我现在解除变身，收起武器，结束这次训练和战斗，坐在长椅上喝水休息。继续普通日常对话。") }
            compose.onNodeWithTag("sendMessage").performClick()
            awaitReply("gameplay-4-rest")
            assertFalse(active.uiState.value.messages.last().stateUnconfirmed)
            assertEquals(JsonPrimitive("未变身"), active.uiState.value.conversationState["protagonist-transformation"])
            assertEquals(JsonPrimitive("无战斗"), active.uiState.value.conversationState["protagonist-battle"])
            val complete = checkNotNull(ConversationRepository(context.filesDir, graph.promptCompiler).get(pressureRecord.id))
            val restored = checkNotNull(ConversationRepository(context.filesDir, graph.promptCompiler).get(pressureRecord.id))
            assertEquals(complete, restored)
            assertEquals(1, restored.turns.sumOf { it.selected.playerChoiceCommits.size })
            assertEquals(chosen.turns.last().selected.runtimeStateAfter, restored.turns[chosen.turns.lastIndex].selected.runtimeStateAfter)
            progress("four-turn gameplay completed")
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
        if (scenario == "second-pressure" || scenario == "second-pressure-memory") {
            val restoredRepository = ConversationRepository(context.filesDir, PromptCompiler())
            val secondRecord = runBlocking { restoredRepository.create(secondPressure, Persona("native-live", "林澈"), preset) }
            compose.runOnUiThread {
                active = newViewModel(restoredRepository).also { it.loadConversation(secondRecord.id) }
                route.value = active
            }
            compose.waitUntil(15_000) { active.uiState.value.selectedConnection?.id == connection.id && !active.uiState.value.busy }
            compose.runOnUiThread { active.updateInput("时间来到中午十二点，沛宁城开始下雨。我带着伞走到门前，帮林纾璃收好门边的东西，再轻声问云知意要不要一同吃午饭。按眼下的关系自然继续，不跳过矛盾。") }
            compose.onNodeWithTag("sendMessage").performClick()
            awaitReply("second-pressure-first")
            var secondState = active.uiState.value
            val actualPrompt = checkNotNull(secondState.lastTrace?.plan).messages.joinToString("\n") { it.content }
            assertEquals(1, Regex("<cloud_attitude>").findAll(actualPrompt).count())
            assertTrue(actualPrompt.contains("云知意当前对"))
            assertTrue(actualPrompt.contains("表面恭谨挑不出错"))
            assertFalse(actualPrompt.contains("好感极高但尚未堪破心结"))
            assertFalse(actualPrompt.contains("<%_ let stage"))
            assertEquals(secondState.message, MessageRole.ASSISTANT, secondState.messages.last().message.role)
            assertEquals(secondState.message, ChatMessageStatus.COMPLETE, secondState.messages.last().status)
            assertFalse("second card state confirmation failed", secondState.messages.last().stateUnconfirmed)
            assertEquals(JsonPrimitive("雨"), secondState.conversationState["weather"])
            assertEquals(JsonPrimitive("暗流涌动"), secondState.conversationState["relationship-stage"])
            assertTrue("per-message panel was not restored", secondState.messages.last().nativePanels.isNotEmpty())
            assertFalse(secondState.messages.last().displayContent.contains("<suihan_panel>"))
            val firstPanel = secondState.messages.last().nativePanels
            compose.runOnUiThread { active.updateInput("我把伞放在门旁，先去准备午饭，告诉她们不用急着回答。让场景顺着刚才的气氛继续。") }
            compose.onNodeWithTag("sendMessage").performClick()
            awaitReply("second-pressure-second")
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
            if (scenario == "second-pressure-memory") {
                val beforeMemory = active.uiState.value.conversationState
                val beforeTurns = active.uiState.value.messages.size
                compose.onNodeWithTag("openNativeDetails").performClick()
                compose.onNodeWithTag("refreshConversationMemories").performScrollTo().performClick()
                progress("waiting for three source-bound memory analyses")
                compose.waitUntil(600_000) { !active.uiState.value.running }
                val afterMemory = checkNotNull(ConversationRepository(context.filesDir, graph.promptCompiler).get(secondRecord.id))
                File(output, "second-memory.json").writeText(Json.encodeToString(afterMemory))
                File(output, "second-memory-result.txt").writeText(active.uiState.value.message.orEmpty())
                assertEquals(active.uiState.value.message, 3, afterMemory.runtimeState.memories.size)
                assertEquals(beforeMemory, active.uiState.value.conversationState)
                assertEquals(beforeTurns, active.uiState.value.messages.size)
                afterMemory.runtimeState.memories.forEach { (id, note) ->
                    assertTrue(note.content.isNotBlank())
                    assertEquals(afterMemory.turns.map { it.selected.id }, note.sourceVariantIds)
                    assertEquals(afterMemory.runtimeState.memories, afterMemory.turns.last().selected.runtimeStateAfter?.memories)
                }
                compose.onNodeWithTag("memory-toggle-yun-relationship").performScrollTo().performClick()
                compose.onNodeWithTag("memory-body-yun-relationship").assertExists()
                screenshot("second-memory-details")
                // Dismiss the sheet through Android back; the next turn uses the saved checkpoint.
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                compose.waitForIdle()
                compose.runOnUiThread { active.updateInput("午饭后继续普通日常。我请她们各自安排下午的休息，不制造新的冲突，也不突然改变现有关系。") }
                compose.onNodeWithTag("sendMessage").performClick()
                awaitReply("second-memory-next-turn")
                val prompt = checkNotNull(active.uiState.value.lastTrace?.plan)
                afterMemory.runtimeState.memories.values.forEach { note ->
                    assertTrue("memory must reach the actual next request", prompt.messages.any { note.content in it.content })
                }
                progress("three analyses persisted and reached the next actual request")
            }
        }
        File(output, "result.txt").writeText("scenario=$scenario passed\npreset=${preset.name}\nmodel=${connection.selectedModel}\n")
    }
}
