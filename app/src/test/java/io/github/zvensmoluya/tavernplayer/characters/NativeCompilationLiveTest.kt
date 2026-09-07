package io.github.zvensmoluya.tavernplayer.characters

import io.github.zvensmoluya.modelgateway.*
import io.github.zvensmoluya.modelgateway.catalog.ModelCatalog
import io.github.zvensmoluya.tavernplayer.connections.*
import io.github.zvensmoluya.tavernplayer.content.*
import io.github.zvensmoluya.tavernplayer.conversation.*
import io.github.zvensmoluya.tavernplayer.presets.PresetRepository
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
            line.trim().takeIf { it.isNotBlank() && !it.startsWith('#') && '=' in it }?.let {
                it.substringBefore('=').trim() to it.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
            }
        }.toMap()
        val output = File(root, "app/build/native-compilation-runs/${System.currentTimeMillis()}").apply { mkdirs() }
        val customSourceHash = System.getenv("TAVERN_COMPILER_SOURCE_SHA256")?.takeIf { it.isNotBlank() }
        val sample = if (customSourceHash == null) "C-03" else "C-04"
        File(output, "progress.txt").writeText("Preparing $sample\n")
        val sourceHash = customSourceHash ?: "0d9f771474cab7f170f33700e9a0db6b87df96451a4da473c0cfa9f8b70e8c22"
        val source = File(root, "source").listFiles()!!.first { file ->
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
        // Replay an already completed private provider output without spending compiler tokens again.
        val replay = System.getenv("TAVERN_COMPILER_REPLAY_FILE")?.takeIf { it.isNotBlank() }?.let(::File)
        val attempt = try {
            if (replay != null) {
                val response = replay.readText()
                NativeCompilationAttempt(
                    NativeAdaptationCompiler().complete(imported.character, response, characters.availableAssetIds(imported.character.id)),
                    response, null, compilerConnection.selectedModel, null,
                )
            } else NativeCompilationService(rawGenerator).compile(
                imported.character, characters.availableAssetIds(imported.character.id), compilerConnection,
                onProgress = { File(output, "progress.txt").appendText("$it\n") },
                onPrepared = { File(output, "compiler-request.json").writeText(Json.encodeToString(it)) },
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
        File(output, "metadata.json").writeText(buildJsonObject {
            put("sample", sample); put("sourceSha256", sourceHash)
            put("compilerVersion", NativeCompilationInstructions.VERSION); put("model", attempt.model)
            put("mode", if (replay == null) "LIVE" else "REPLAY")
            put("finishReason", attempt.finishReason); put("inputTokens", attempt.usage?.inputTokens)
            put("outputTokens", attempt.usage?.outputTokens); put("result", attempt.result::class.simpleName)
            put("reasoningTokens", attempt.usage?.reasoningTokens)
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
                assertTrue("C-04 needs status bindings", adaptation.stateBindings.isNotEmpty())
                assertTrue(adaptation.stateBindings.all { it.source == NativeStateSource.MVU })
                assertTrue("C-04 needs an actual inventory view", adaptation.collections.isNotEmpty())
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
        val form = adaptation.forms.single()
        assertNull("ordinary form must not become setup", form.setup)
        assertEquals(5, form.fields.size)
        val reasons = form.fields.single { it.type == NativeFormFieldType.MULTI_SELECT }
        assertEquals(listOf("家庭矛盾", "心理问题", "学业障碍", "单纯想聊天"), reasons.options.map { it.value })
        assertTrue(form.fields.all { it.emptyText.isNotBlank() })
        val values = form.fields.associate { field -> field.id to when {
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
            assertEquals(form.id, viewModel.uiState.value.messages.single().nativeForms.single().id)
            viewModel.submitNativeForm(form.id, values)
            assertEquals(drafted.text, viewModel.uiState.value.input)
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
