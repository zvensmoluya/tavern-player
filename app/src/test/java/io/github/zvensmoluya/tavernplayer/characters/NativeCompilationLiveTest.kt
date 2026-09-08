package io.github.zvensmoluya.tavernplayer.characters

import io.github.zvensmoluya.modelgateway.*
import io.github.zvensmoluya.modelgateway.catalog.ModelCatalog
import io.github.zvensmoluya.tavernplayer.connections.*
import io.github.zvensmoluya.tavernplayer.content.*
import io.github.zvensmoluya.tavernplayer.conversation.*
import io.github.zvensmoluya.tavernplayer.conversation.script.QuickJsNativeRuntime
import io.github.zvensmoluya.tavernplayer.presets.PresetRepository
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Explicit opt-in only. Reads the compiler connection from .env's _1 group, never a card endpoint. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeCompilationLiveTest {
    @get:Rule val temporary = TemporaryFolder()
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test fun `compile selected source without manual adaptation and verify its installation`() = runBlocking {
        assumeTrue("set TAVERN_COMPILER_LIVE=1 to spend real provider tokens", System.getenv("TAVERN_COMPILER_LIVE") == "1")
        val root = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val config = File(root, ".env").readLines().mapNotNull { line ->
            line.removePrefix("\uFEFF").trim().takeIf { it.isNotBlank() && !it.startsWith('#') && '=' in it }?.let {
                it.substringBefore('=').trim() to it.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
            }
        }.toMap()
        val output = File(root, "app/build/native-compilation-runs/${System.currentTimeMillis()}").apply { mkdirs() }
        val customSourceHash = System.getenv("TAVERN_COMPILER_SOURCE_SHA256")?.takeIf { it.isNotBlank() }
        val sample = when (customSourceHash) {
            null -> "C-03"
            "1945abd1e2368ec332399830cd85c62be4a9a527f0c43dae6f34db9c9b3b2e1a" -> "C-01"
            "b7cf04e3198ffc3a6f9ebed5c398faf7a9066e49dc8887896db5681a0d1498eb" -> "C-02"
            "fa7e8ec564887780b331d0da29f7966f58f3688d49e6faf587d2d80ae9aecefe" -> "C-04"
            "7df0b58b2a46ac9ae2169c45f715a58760ebdad63017c5a860222d808beabe32" -> "C-05"
            else -> "sample-${customSourceHash.take(12)}"
        }
        File(output, "progress.txt").writeText("Preparing $sample\n")
        val sourceHash = customSourceHash ?: "0d9f771474cab7f170f33700e9a0db6b87df96451a4da473c0cfa9f8b70e8c22"
        val source = File(root, "source").walkTopDown().first { file ->
            file.isFile && MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) } == sourceHash
        }
        val directory = temporary.newFolder()
        val characters = CharacterRepository(directory)
        val imported = characters.import(source.readBytes(), "sample-${sample.lowercase()}.png") as CharacterSaveResult.Saved
        File(output, "program-view.json").writeText(NativeAdaptationCompiler().prepare(imported.character, characters.availableAssetIds(imported.character.id)))
        if (System.getenv("TAVERN_COMPILER_PREPARE_ONLY") == "1") {
            File(output, "progress.txt").appendText("PASS: local Program View prepared; no model request\n")
            return@runBlocking
        }
        val credentials = CompilationTestCredentials()
        val state = CompilationTestConnectionState()
        val gateway = ModelGateway(credentials, PlayerModelHttpClient.create())
        val connections = ConnectionRepository(state, credentials, { ModelCatalog(emptyList(), false) })
        suspend fun connection(suffix: String, model: String? = null, context: String = ""): StoredConnection = connections.save(
            ConnectionDraft("live${suffix.ifEmpty { "-chat" }}", "Live verification", "compiler-test",
                ModelProtocol.valueOf(config.getValue("TAVERN_TEST_PROTOCOL$suffix")),
                config.getValue("TAVERN_TEST_BASE_URL$suffix"), model ?: config.getValue("TAVERN_TEST_MODEL$suffix"),
                contextTokenLimitOverride = context),
            config.getValue("TAVERN_TEST_API_KEY$suffix"), false,
        )
        val compilerConnection = connection(
            System.getenv("TAVERN_COMPILER_CONFIG_SUFFIX") ?: "_1",
            System.getenv("TAVERN_COMPILER_MODEL")?.takeIf { it.isNotBlank() },
            System.getenv("TAVERN_COMPILER_CONTEXT_TOKENS") ?: "",
        )
        val rawGenerator = ModelGatewayConversationGenerator(gateway, connections)
        // Capture only provider-exposed reasoning, outside the production compiler and its input.
        // Append while streaming so a later rejection or transport failure retains available evidence.
        val reasoningFile = File(output, "compiler-reasoning.txt").apply { writeText("") }
        val observedGenerator = object : ConversationGenerator {
            override suspend fun validateTokens(connection: StoredConnection, plan: GenerationPlan) =
                rawGenerator.validateTokens(connection, plan)
            override fun stream(connection: StoredConnection, plan: GenerationPlan) = flow {
                val prefix = if (plan.messages.first().origin.stage == "native-compilation-selection") "routing" else "compiler"
                rawGenerator.stream(connection, plan).collect { event ->
                    if (event is GenerationEvent.ReasoningDelta) File(output, "$prefix-reasoning.txt").appendText(event.text)
                    if (event is GenerationEvent.TextDelta) File(output, "$prefix-response-stream.txt").appendText(event.text)
                    if (event is GenerationEvent.Usage) File(output, "$prefix-usage.json").writeText(buildJsonObject {
                        put("inputTokens", event.value.inputTokens); put("outputTokens", event.value.outputTokens)
                        put("reasoningTokens", event.value.reasoningTokens); put("cachedTokens", event.value.cachedTokens)
                    }.toString())
                    emit(event)
                }
            }
        }
        // Replay an already completed private provider output without spending compiler tokens again.
        val replay = System.getenv("TAVERN_COMPILER_REPLAY_FILE")?.takeIf { it.isNotBlank() }?.let(::File)
        val attempt = try {
            if (replay != null) {
                val response = replay.readText()
                NativeCompilationAttempt(
                    NativeAdaptationCompiler().complete(imported.character, response, characters.availableAssetIds(imported.character.id)),
                    response, null, compilerConnection.selectedModel, null,
                )
            } else NativeCompilationService(observedGenerator, mvuRuntime =
                io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuConversationRuntime {
                    File(root, "tools/mvu-probe/build/app-assets/mvu/runtime.js").readText()
                }).compile(
                imported.character, characters.availableAssetIds(imported.character.id), compilerConnection,
                onProgress = { File(output, "progress.txt").appendText("$it\n") },
                onPrepared = {
                    val prefix = if (it.messages.first().origin.stage == "native-compilation-selection") "routing" else "compiler"
                    File(output, "$prefix-request.json").writeText(Json.encodeToString(it))
                },
            )
        } catch (error: GatewayException) {
            File(output, "failure.json").writeText(buildJsonObject {
                put("sample", sample); put("model", compilerConnection.selectedModel)
                put("failureType", error::class.simpleName)
                put("httpStatus", (error as? GatewayException.HttpFailure)?.status)
                // GatewayTransport already redacts the credential and credential-shaped response fields.
                put("diagnostic", error.diagnostic)
            }.toString())
            File(output, "progress.txt").appendText("Provider failed: ${error::class.simpleName}\n")
            throw error
        }
        File(output, "compiler-response.json").writeText(attempt.response)
        attempt.routing?.let { File(output, "routing-response.json").writeText(it.response) }
        File(output, "metadata.json").writeText(buildJsonObject {
            put("sample", sample); put("sourceSha256", sourceHash)
            put("compilerVersion", NativeCompilationInstructions.VERSION); put("model", attempt.model)
            put("mode", if (replay == null) "LIVE" else "REPLAY")
            put("finishReason", attempt.finishReason); put("inputTokens", attempt.usage?.inputTokens)
            put("outputTokens", attempt.usage?.outputTokens); put("result", attempt.result::class.simpleName)
            put("reasoningTokens", attempt.usage?.reasoningTokens)
            put("exposedReasoningBytes", reasoningFile.length())
            attempt.routing?.let {
                put("routingInputTokens", it.usage?.inputTokens); put("routingOutputTokens", it.usage?.outputTokens)
                put("routingReasoningTokens", it.usage?.reasoningTokens); put("routingFinishReason", it.finishReason)
                put("selectedRuntime", it.selection?.runtime?.name)
            }
        }.toString())
        if (attempt.result is NativeCompilationResult.Rejected) {
            File(output, "issues.json").writeText(Json.encodeToString(attempt.result.issues))
            fail("Compilation rejected; see private build report: ${output.name}")
        }
        val ready = attempt.result as NativeCompilationResult.Ready
        val adaptation = ready.adaptation
        File(output, "adaptation.json").writeText(Json.encodeToString(adaptation))
        File(output, "evidence.json").writeText(Json.encodeToString(ready.evidence))
        if (customSourceHash != null) {
            if (sourceHash == "fa7e8ec564887780b331d0da29f7966f58f3688d49e6faf587d2d80ae9aecefe") {
                assertNotNull("C-04 must execute its original MVU schema", adaptation.mvu)
                assertEquals("C-04 selects four original EJS templates", 4, adaptation.ejsTemplates.size)
                assertTrue("MVU must not create a second state store", adaptation.state.isEmpty())
                assertTrue(adaptation.assistantStateAdapters.isEmpty())
                assertTrue(adaptation.progressions.isEmpty())
                assertTrue(adaptation.worldBookTextSelections.isEmpty())
                assertTrue("C-04 needs a status view", adaptation.stateBindings.isNotEmpty() ||
                    adaptation.script?.surfaces?.any { it.surface == NativeSurfaceType.STATUS } == true)
                assertTrue(adaptation.stateBindings.all { it.source == NativeStateSource.MVU })
                assertTrue("C-04 needs an actual inventory view", adaptation.collections.isNotEmpty() ||
                    adaptation.script?.surfaces?.any { it.surface == NativeSurfaceType.COLLECTION } == true)
            }
            assertTrue(characters.installNativeAdaptation(imported.character.id, adaptation) is NativeAdaptationInstallResult.Installed)
            val installed = CharacterRepository(directory).get(imported.character.id)!!
            assertEquals(adaptation, installed.nativeAdaptation)
            val mvu = io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuConversationRuntime {
                File(root, "tools/mvu-probe/build/app-assets/mvu/runtime.js").readText()
            }
            val conversations = ConversationRepository(directory, PromptCompiler(), mvuRuntime = mvu)
            val record = conversations.create(installed, Persona("compiler-test", "测试访客"), PresetRepository(directory).captureActive())
            val restored = ConversationRepository(directory, PromptCompiler()).get(record.id)!!
            assertEquals(adaptation, restored.character.nativeAdaptation)
            File(output, "conversation.json").writeText(Json.encodeToString(restored))
            adaptation.script?.let { program ->
                val runtime = QuickJsNativeRuntime()
                val projected = runtime.present(program, restored.nativeContext(), restored.nativeRevision())
                File(output, "surface-projection.json").writeText(Json.encodeToString(projected.map { it.data }))
                assertTrue("Initial projection must expose content", projected.isNotEmpty())
                assertEquals("Reload must preserve projected content",
                    runtime.present(program, record.nativeContext(), record.nativeRevision()), projected)
                if (sourceHash == "fa7e8ec564887780b331d0da29f7966f58f3688d49e6faf587d2d80ae9aecefe") {
                    val context = restored.nativeContext()
                    val stateData = context.getValue("state").jsonObject
                    val statData = stateData.getValue("stat_data").jsonObject
                    val cases = listOf(
                        """{"audit_a":{"数量":0,"描述":""},"audit_b":{"数量":2,"描述":"audit_description"}}""",
                        "{}", """{"audit_b":{"数量":3,"描述":"audit_updated"}}""",
                    )
                    var inventorySurfaceId: String? = null
                    val itemKeys = mutableMapOf<String, String>()
                    fun displayText(item: NativeSurfaceItem) = listOf(item.title, item.status, item.description).joinToString(" ")
                    val audit = buildJsonArray {
                        cases.forEachIndexed { index, inventory ->
                            val entries = Json.parseToJsonElement(inventory).jsonObject
                            val changed = JsonObject(context + ("state" to JsonObject(stateData +
                                ("stat_data" to JsonObject(statData + ("物品栏" to entries))))))
                            val surfaces = runtime.present(program, changed, "audit-$index")
                            if (index == 0) inventorySurfaceId = surfaces.single { surface ->
                                surface.data.surface == NativeSurfaceType.COLLECTION &&
                                    surface.data.items.size == entries.size &&
                                    entries.keys.all { name -> surface.data.items.count { name in displayText(it) } == 1 }
                            }.id
                            val inventorySurface = surfaces.single { it.id == inventorySurfaceId }.data
                            assertEquals(entries.size, inventorySurface.items.size)
                            entries.forEach { (name, value) ->
                                val item = inventorySurface.items.single { name in displayText(it) }
                                val previousKey = itemKeys.putIfAbsent(name, item.key)
                                if (previousKey != null) assertEquals("Item identity changed with content", previousKey, item.key)
                                val original = value.jsonObject
                                // Original C-04 loop uses quantity || 1 and description || '暂无描述'.
                                val count = original.getValue("数量").jsonPrimitive.int.takeUnless { it == 0 } ?: 1
                                val description = original.getValue("描述").jsonPrimitive.content.ifEmpty { "暂无描述" }
                                val text = displayText(item)
                                assertTrue("Source count fallback lost", "x$count" in text)
                                assertTrue("Source description fallback lost", description in text)
                            }
                            add(Json.encodeToJsonElement(inventorySurface))
                        }
                    }
                    File(output, "inventory-audit.json").writeText(audit.toString())
                }
            }
            val reader = ConversationStateReader(adaptation, restored.runtimeState)
            val missing = adaptation.stateBindings.filter { reader[it.key] == null }.map { it.key }
            File(output, "binding-audit.json").writeText(buildJsonObject {
                put("bindingCount", adaptation.stateBindings.size); put("missing", JsonArray(missing.map(::JsonPrimitive)))
                put("playerStateCount", restored.runtimeState.conversationState.values.size)
            }.toString())
            assertTrue("Bindings missing from actual initialized MVU: $missing", missing.isEmpty())
            File(output, "progress.txt").appendText("PASS: compiled, installed and snapshot reloaded; gameplay requires sample-specific audit\n")
            return@runBlocking
        }
        assertTrue("input-only source must not gain state", adaptation.state.isEmpty())
        assertTrue(adaptation.assistantStateAdapters.isEmpty())
        assertTrue(adaptation.memories.isEmpty())
        val form = adaptation.forms.singleOrNull()
        var values: Map<String, List<String>> = emptyMap()
        var draftedText = ""
        if (form != null) {
        assertNull("ordinary form must not become setup", form.setup)
        assertEquals(5, form.fields.size)
        val reasons = form.fields.single { it.type == NativeFormFieldType.MULTI_SELECT }
        assertEquals(listOf("家庭矛盾", "心理问题", "学业障碍", "单纯想聊天"), reasons.options.map { it.value })
        assertTrue(form.fields.all { it.emptyText.isNotBlank() })
        values = form.fields.associate { field -> field.id to when {
            field.id == reasons.id -> listOf("学业障碍", "单纯想聊天")
            "时间" in field.label -> listOf("周三下午14:00")
            "忌口" in field.label || "需求" in field.label -> listOf("不喝咖啡")
            field.type == NativeFormFieldType.MULTILINE_TEXT -> listOf("最近考试压力大，想聊聊怎样安排复习。")
            else -> listOf("小林")
        } }
        val drafted = NativeAdaptationRuntime().submitForm(adaptation, NativeFormSubmission(form.id, values), "小林", imported.character.promptName)
            as NativeFormSubmissionResult.Draft
        values.values.flatten().forEach { assertTrue("input value lost", it in drafted.text) }
        val empty = NativeAdaptationRuntime().submitForm(adaptation, NativeFormSubmission(form.id, emptyMap()), "小林", imported.character.promptName)
            as NativeFormSubmissionResult.Draft
        form.fields.forEach { assertTrue("empty fallback lost", it.emptyText in empty.text) }
        File(output, "draft.txt").writeText(drafted.text)
        File(output, "empty-draft.txt").writeText(empty.text)
            draftedText = drafted.text
        } else require(adaptation.script != null) { "Input workflow was omitted" }
        assertTrue(characters.installNativeAdaptation(imported.character.id, adaptation) is NativeAdaptationInstallResult.Installed)
        val installed = CharacterRepository(directory).get(imported.character.id)!!
        assertEquals(adaptation, installed.nativeAdaptation)
        val conversations = ConversationRepository(directory, PromptCompiler())
        val presets = PresetRepository(directory)
        val record = conversations.create(installed, Persona("compiler-test", "小林"), presets.captureActive())
        connection("") // Chat uses the separately supplied ordinary model, not the compiler model.
        val viewModel = ChatViewModel(connections, PromptCompiler(), StateConfirmingConversationGenerator(rawGenerator), conversations, presets)
        try {
            viewModel.loadConversation(record.id)
            withTimeout(20_000) { viewModel.uiState.first { it.conversationId == record.id && !it.busy && !it.loadingConnections } }
            if (form != null) {
                assertEquals(form.id, viewModel.uiState.value.messages.single().nativeForms.single().id)
                viewModel.submitNativeForm(form.id, values)
            } else draftedText = verifyScriptForm(viewModel, output)
            assertEquals(draftedText, viewModel.uiState.value.input)
            viewModel.send()
            File(output, "progress.txt").appendText("Playing compiled C-03\n")
            withTimeout(480_000) { viewModel.uiState.first { !it.busy } }
            val chat = viewModel.uiState.value
            chat.lastTrace?.plan?.let { File(output, "chat-request.json").writeText(Json.encodeToString(it)) }
            File(output, "chat-result.txt").writeText(chat.messages.last().message.content)
            File(output, "chat-diagnostic.txt").writeText(chat.lastTrace?.error ?: chat.message.orEmpty())
            assertEquals(ChatMessageStatus.COMPLETE, chat.messages.last().status)
            assertTrue(chat.messages.last().message.content.isNotBlank())
            assertEquals(3, chat.messages.size)
            val restored = ConversationRepository(directory, PromptCompiler()).get(record.id)!!
            assertEquals(adaptation, restored.character.nativeAdaptation)
            assertEquals(3, restored.turns.size)
            assertEquals(chat.messages.last().message.content, restored.turns.last().selected.message.content)
            File(output, "conversation.json").writeText(Json.encodeToString(restored))
            val reader = ConversationStateReader(adaptation, restored.runtimeState)
            val missing = adaptation.stateBindings.filter { reader[it.key] == null }.map { it.key }
            File(output, "binding-audit.json").writeText(buildJsonObject {
                put("bindingCount", adaptation.stateBindings.size); put("missing", JsonArray(missing.map(::JsonPrimitive)))
                put("playerStateCount", restored.runtimeState.conversationState.values.size)
            }.toString())
            assertTrue("Bindings missing from actual initialized MVU: $missing", missing.isEmpty())
            File(output, "progress.txt").appendText("PASS: compiled, installed, form verified, generated and reloaded\n")
        } finally {
            androidx.lifecycle.ViewModelStore().apply { put("live", viewModel); clear() }
        }
    }
    /** Same C-03 behavior checks through real Surface actions, without requiring a fixed-form artifact. */
    private suspend fun verifyScriptForm(model: ChatViewModel, output: File): String {
        suspend fun form(): NativeRenderedSurface = withTimeout(20_000) {
            model.uiState.first { !it.busy && it.nativeSurfaces.any { surface -> surface.data.surface == NativeSurfaceType.FORM } }
                .nativeSurfaces.single { it.data.surface == NativeSurfaceType.FORM }
        }
        suspend fun invoke(action: NativeSurfaceAction, inputs: Map<String, String>) {
            val surface = form()
            model.invokeNativeAction(NativeSurfaceInvocation(surface.id, surface.revision, action, input = inputs))
            withTimeout(20_000) { model.uiState.first { state -> !state.busy && state.nativeSurfaces.any { it.revision != surface.revision } } }
        }
        val first = form()
        assertEquals(4, first.data.fields.size)
        val reasons = listOf("家庭矛盾", "心理问题", "学业障碍", "单纯想聊天")
        val toggles = first.data.actions.filter { action -> action.args.values.any { it is JsonPrimitive && it.content in reasons } }
        assertEquals(reasons.size, toggles.size)
        invoke(first.data.actions.single { it.args.isEmpty() }, emptyMap())
        val empty = model.uiState.value.input
        for (fallback in listOf("匿名访客", "未指定时间", "未勾选", "无详细描述", "忌口/需求：无")) assertTrue(fallback in empty)
        File(output, "empty-draft.txt").writeText(empty)
        val inputs = form().data.fields.associate { field -> field.id to when {
            "时间" in field.label -> "周三下午14:00"
            "忌口" in field.label || "需求" in field.label -> "不喝咖啡"
            "描述" in field.label -> "最近考试压力大，想聊聊怎样安排复习。"
            else -> "小林"
        } }
        // Original checkbox collection is DOM order, not click order.
        for (reason in listOf("单纯想聊天", "学业障碍")) {
            val action = form().data.actions.single { it.args.values.any { v -> v is JsonPrimitive && v.content == reason } }
            invoke(action, inputs)
        }
        form().data.fields.forEach { assertEquals(inputs[it.id], it.value) }
        invoke(form().data.actions.single { it.args.isEmpty() }, inputs)
        val drafted = model.uiState.value.input
        inputs.values.forEach { assertTrue("Input lost", it in drafted) }
        assertTrue("Original checkbox join/order lost", "学业障碍、单纯想聊天" in drafted)
        File(output, "draft.txt").writeText(drafted)
        File(output, "surface-projection.json").writeText(Json.encodeToString(form().data))
        return drafted
    }

}

internal class CompilationTestConnectionState : ConnectionStateStore {
    override val state = MutableStateFlow(GatewayAppState())
    override suspend fun update(transform: (GatewayAppState) -> GatewayAppState) { state.value = transform(state.value) }
}

internal class CompilationTestCredentials : CredentialStore, CredentialResolver {
    private val secrets = mutableMapOf<String, String>()
    override suspend fun put(credentialId: String, secret: String) { secrets[credentialId] = secret }
    override suspend fun getOrNull(credentialId: String) = secrets[credentialId]
    override suspend fun delete(credentialId: String) { secrets.remove(credentialId) }
    override suspend fun contains(credentialId: String) = credentialId in secrets
    override suspend fun resolve(credentialRef: String) = secrets[credentialRef]?.let(::SecretValue)
}
