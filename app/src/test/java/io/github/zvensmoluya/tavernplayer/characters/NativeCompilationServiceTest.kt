package io.github.zvensmoluya.tavernplayer.characters

import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.modelgateway.catalog.ModelCatalog
import io.github.zvensmoluya.tavernplayer.connections.*
import io.github.zvensmoluya.tavernplayer.content.*
import io.github.zvensmoluya.tavernplayer.conversation.*
import io.github.zvensmoluya.tavernplayer.personas.DefaultPersonaSource
import io.github.zvensmoluya.tavernplayer.presets.PresetRepository
import io.github.zvensmoluya.tavernplayer.transfer.ShelfTransferReceiver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeCompilationServiceTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()
    @get:Rule val temporary = TemporaryFolder()
    private val source = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Sample","first_mes":"<START>"}}""".encodeToByteArray()
    private val response = JsonResponse

    @Test fun `compilation uses independent instructions program view and fixed settings`() = runBlocking {
        val harness = harness()
        val generator = FakeGenerator(response)
        val result = NativeCompilationService(generator).compile(harness.character, emptySet(), harness.connection)
        assertTrue(result.result.toString(), result.result is NativeCompilationResult.Ready)
        assertEquals(1, generator.requests)
        val plan = generator.plan!!
        assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER), plan.messages.map { it.role })
        assertEquals("native-compilation-contract", plan.messages.first().origin.stage)
        assertFalse(plan.messages.last().content.contains("originalCard"))
        assertFalse(plan.messages.last().content.contains("preservedLocally"))
        assertEquals(NativeCompilationInstructions.VERSION, plan.presetId)
        assertFalse(plan.messages.last().content.contains("<START>"))
        assertNull(plan.nativeAdaptation)
        assertEquals("", plan.assistantPrefill)
        assertFalse(plan.generationSettings.isEnabled(PresetGenerationParameter.TEMPERATURE))
        assertEquals(PresetReasoningEffort.HIGH, plan.generationSettings.reasoningEffort)
    }

    @Test fun `complete-looking JSON from truncated stream never becomes installable`() = runBlocking {
        val harness = harness()
        val result = NativeCompilationService(FakeGenerator(response, reason = "length")).compile(harness.character, emptySet(), harness.connection)
        assertTrue(result.result is NativeCompilationResult.Rejected)
        assertEquals("COMPILER_INCOMPLETE", (result.result as NativeCompilationResult.Rejected).issues.single().code)
    }

    @Test fun `oversized input rejects before generation without truncating source`() = runBlocking {
        val harness = harness()
        val generator = FakeGenerator(response, inputTokens = 130_000)
        val result = NativeCompilationService(generator).compile(harness.character, emptySet(), harness.connection)
        assertEquals(0, generator.requests)
        assertEquals("COMPILER_CONTEXT_LIMIT", (result.result as NativeCompilationResult.Rejected).issues.single().code)
    }

    @Test fun `successful compilation installs for new conversations and preserves old snapshots`() = runBlocking {
        val harness = harness()
        val old = harness.conversations.create(harness.character, Persona("user", "旅人"), harness.presets.captureActive())
        val generator = FakeGenerator(response)
        val viewModel = harness.viewModel(generator)
        try {
            withTimeout(10_000) { viewModel.uiState.first { it.compilationConnectionId != null } }
            viewModel.compileNativeAdaptation(harness.character.id)
            withTimeout(10_000) { viewModel.uiState.first { !it.busy } }
            val installed = CharacterRepository(harness.directory).get(harness.character.id)!!.nativeAdaptation
            assertNotNull(viewModel.uiState.value.message, installed)
            assertEquals("保留原始文本", installed!!.report.summary)
            assertNull(harness.conversations.get(old.id)!!.character.nativeAdaptation)
            val newer = harness.conversations.create(harness.characters.get(harness.character.id)!!, Persona("user", "旅人"), harness.presets.captureActive())
            assertEquals(installed, newer.character.nativeAdaptation)
            assertEquals(1, generator.requests)
        } finally { clear(viewModel) }
    }

    @Test fun `cancel partial response preserves installed adaptation and does not retry`() = runBlocking {
        val harness = harness()
        val previous = NativeAdaptation(sourceSha256 = harness.character.sourceSha256, report = NativeCompatibilityReport(summary = "Existing"))
        harness.characters.installNativeAdaptation(harness.character.id, previous)
        val gate = CompletableDeferred<Unit>()
        val generator = FakeGenerator(response, gate = gate)
        val viewModel = harness.viewModel(generator)
        try {
            withTimeout(10_000) { viewModel.uiState.first { it.compilationConnectionId != null } }
            viewModel.compileNativeAdaptation(harness.character.id)
            withTimeout(10_000) { generator.started.await() }
            viewModel.compileNativeAdaptation(harness.character.id) // double taps must not dispatch another request
            viewModel.cancelCompilation()
            withTimeout(10_000) { viewModel.uiState.first { !it.busy } }
            assertEquals(previous, CharacterRepository(harness.directory).get(harness.character.id)!!.nativeAdaptation)
            assertEquals(1, generator.requests)
            assertTrue(viewModel.uiState.value.message.orEmpty().contains("已停止"))
        } finally { clear(viewModel) }
    }

    @Test fun `invalid output keeps old adaptation and compiler selection does not switch chat connection`() = runBlocking {
        val harness = harness()
        val previous = NativeAdaptation(sourceSha256 = harness.character.sourceSha256)
        harness.characters.installNativeAdaptation(harness.character.id, previous)
        val other = harness.connections.save(ConnectionDraft("other", "Chat", "test", ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            "https://chat.example.test/v1", "chat-model"), "", false)
        val viewModel = harness.viewModel(FakeGenerator("not JSON"))
        try {
            withTimeout(10_000) { viewModel.uiState.first { it.compilationConnections.size == 2 } }
            viewModel.selectCompilationConnection(harness.connection.id)
            assertEquals(other.id, harness.connections.state.first().recentConnectionId)
            viewModel.compileNativeAdaptation(harness.character.id)
            withTimeout(10_000) { viewModel.uiState.first { !it.busy } }
            assertEquals(previous, harness.characters.get(harness.character.id)!!.nativeAdaptation)
            assertTrue(viewModel.uiState.value.message.orEmpty().startsWith("适配未安装"))
        } finally { clear(viewModel) }
    }

    private suspend fun harness(): Harness {
        val directory = temporary.newFolder()
        val credentials = CompilationTestCredentials()
        val connections = ConnectionRepository(CompilationTestConnectionState(), credentials, { ModelCatalog(emptyList(), false) })
        val connection = connections.save(ConnectionDraft("compiler", "Compiler", "test", ModelProtocol.OPENAI_RESPONSES,
            "https://compiler.example.test/v1", "compiler-model"), "", false)
        val characters = CharacterRepository(directory)
        val character = (characters.import(source, "sample.json") as CharacterSaveResult.Saved).character
        return Harness(directory, characters, character, connections, connection,
            ConversationRepository(directory, PromptCompiler()), PresetRepository(directory))
    }

    private data class Harness(
        val directory: java.io.File,
        val characters: CharacterRepository,
        val character: io.github.zvensmoluya.tavernplayer.content.CharacterAsset,
        val connections: ConnectionRepository,
        val connection: StoredConnection,
        val conversations: ConversationRepository,
        val presets: PresetRepository,
    ) {
        fun viewModel(generator: ConversationGenerator) = CharacterLibraryViewModel(characters, conversations,
            object : DefaultPersonaSource {
                override val persona = MutableStateFlow(Persona("user", "旅人"))
                override fun captureDefault() = persona.value
            }, presets, ShelfTransferReceiver { error("not used") }, NativeCompilationService(generator), connections)
    }

    private class FakeGenerator(
        private val response: String,
        private val reason: String = "completed",
        private val inputTokens: Int = 100,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : ConversationGenerator {
        var requests = 0
        var plan: GenerationPlan? = null
        val started = CompletableDeferred<Unit>()
        override suspend fun validateTokens(connection: StoredConnection, plan: GenerationPlan) = ProviderTokenValidation(inputTokens, TokenCountQuality.EXACT, "test")
        override fun stream(connection: StoredConnection, plan: GenerationPlan) = flow {
            requests++
            this@FakeGenerator.plan = plan
            emit(GenerationEvent.ReasoningDelta("This must not be decoded as the adaptation."))
            emit(GenerationEvent.TextDelta(response.take(10)))
            started.complete(Unit)
            gate?.await()
            emit(GenerationEvent.TextDelta(response.drop(10)))
            emit(GenerationEvent.Finished(reason))
        }
    }

    private fun clear(model: androidx.lifecycle.ViewModel) = androidx.lifecycle.ViewModelStore().apply { put("test", model); clear() }

    companion object {
        val JsonResponse = Json.encodeToString(NativeCompilationDraft("保留原始文本"))
    }
}
