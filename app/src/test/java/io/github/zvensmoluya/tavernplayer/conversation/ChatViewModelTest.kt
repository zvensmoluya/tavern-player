package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.modelgateway.catalog.ModelCatalog
import io.github.zvensmoluya.tavernplayer.connections.ConnectionRepository
import io.github.zvensmoluya.tavernplayer.connections.ConnectionStateStore
import io.github.zvensmoluya.tavernplayer.connections.CredentialStore
import io.github.zvensmoluya.tavernplayer.connections.GatewayAppState
import io.github.zvensmoluya.tavernplayer.connections.ModelCache
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `completed stream appends user once and records response metadata and latest trace`() = runTest {
        val generator = FakeGenerator { connection, _ ->
            flow {
                emit(GenerationEvent.RequestPrepared(preview(connection)))
                emit(GenerationEvent.ReasoningDelta("思考"))
                emit(GenerationEvent.ReasoningSignature("signature"))
                emit(GenerationEvent.TextDelta("欢迎回来。"))
                emit(GenerationEvent.Usage(GenerationUsage(10, 4, 14)))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("我想住一晚。")
        viewModel.send()

        val state = viewModel.uiState.value
        assertFalse(state.running)
        assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT), state.messages.map { it.message.role })
        assertEquals("欢迎回来。", state.messages.last().message.content)
        assertEquals("思考", state.messages.last().message.reasoning.single().text)
        assertEquals("signature", state.messages.last().message.reasoning.single().signature)
        assertEquals(ChatMessageStatus.COMPLETE, state.messages.last().status)
        assertEquals("stop", state.messages.last().metadata?.finishReason)
        assertEquals(14L, state.lastTrace?.usage?.totalTokens)
        assertNotNull(state.lastTrace?.providerPreview)
    }

    @Test
    fun `failure before text keeps user and retry does not duplicate it`() = runTest {
        var fail = true
        val generator = FakeGenerator { connection, _ ->
            flow {
                if (fail) throw GatewayException.Network(IOException("offline"))
                emit(GenerationEvent.RequestPrepared(preview(connection)))
                emit(GenerationEvent.TextDelta("现在可以了。"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("你好")
        viewModel.send()

        assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER), viewModel.uiState.value.messages.map { it.message.role })
        assertTrue(viewModel.uiState.value.retryAvailable)

        fail = false
        viewModel.retry()

        val state = viewModel.uiState.value
        assertEquals(1, state.messages.count { it.message.role == MessageRole.USER })
        assertEquals("现在可以了。", state.messages.last().message.content)
        assertFalse(state.retryAvailable)
    }

    @Test
    fun `cancellation keeps partial assistant and reset clears trace`() = runTest {
        val generator = FakeGenerator { connection, _ ->
            flow {
                emit(GenerationEvent.RequestPrepared(preview(connection)))
                emit(GenerationEvent.TextDelta("半句回复"))
                awaitCancellation()
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("开始")
        viewModel.send()
        assertTrue(viewModel.uiState.value.running)

        viewModel.cancel()

        assertFalse(viewModel.uiState.value.running)
        assertEquals(ChatMessageStatus.CANCELLED, viewModel.uiState.value.messages.last().status)
        assertEquals("半句回复", viewModel.uiState.value.messages.last().message.content)
        assertNotNull(viewModel.uiState.value.lastTrace)

        viewModel.resetConversation()
        assertEquals(1, viewModel.uiState.value.messages.size)
        assertEquals(null, viewModel.uiState.value.lastTrace)
    }

    @Test
    fun `unsupported user macro stays in composer and never calls generator`() = runTest {
        val generator = FakeGenerator { _, _ -> error("must not run") }
        val viewModel = viewModel(generator)

        viewModel.updateInput("{{getvar::mood}}")
        viewModel.send()

        assertEquals("{{getvar::mood}}", viewModel.uiState.value.input)
        assertEquals(1, viewModel.uiState.value.messages.size)
        assertTrue(viewModel.uiState.value.message.orEmpty().contains("Unsupported macro"))
        assertEquals(0, generator.calls)
    }

    private fun viewModel(generator: FakeGenerator): ChatViewModel {
        var id = 0
        return ChatViewModel(
            repository = repository(),
            compiler = PromptCompiler(),
            generator = generator,
            idGenerator = { "message-${id++}" },
        )
    }

    private fun repository(): ConnectionRepository {
        val connection = connection()
        return ConnectionRepository(
            stateStore = FakeStateStore(GatewayAppState(connections = listOf(connection), recentConnectionId = connection.id)),
            credentialStore = FakeCredentialStore(),
            catalogLoader = { ModelCatalog(emptyList(), truncated = false) },
        )
    }

    private fun connection() = StoredConnection(
        id = "connection",
        name = "Test",
        templateId = "test",
        protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
        apiAddress = "https://example.com/v1",
        streamEndpoint = "https://example.com/v1/chat/completions",
        catalogEndpoint = null,
        authScheme = AuthScheme.NONE,
        credentialRef = null,
        credentialMask = null,
        approvedOrigins = emptySet(),
        selectedModel = "model",
        modelCache = ModelCache(),
    )

    private fun preview(connection: StoredConnection) = ProviderRequestPreview(
        protocol = connection.protocol,
        model = connection.selectedModel,
        systemInstruction = null,
        messages = listOf(ProviderPreviewMessage("user", "hello")),
        maxOutputTokens = 512,
        store = false,
        usesHostedState = false,
        assistantPrefillApplied = false,
    )
}

private class FakeGenerator(
    private val block: (StoredConnection, GenerationPlan) -> Flow<GenerationEvent>,
) : ConversationGenerator {
    var calls: Int = 0
        private set

    override fun stream(connection: StoredConnection, plan: GenerationPlan): Flow<GenerationEvent> {
        calls += 1
        return block(connection, plan)
    }
}

private class FakeStateStore(initial: GatewayAppState) : ConnectionStateStore {
    private val mutable = MutableStateFlow(initial)
    override val state: Flow<GatewayAppState> = mutable

    override suspend fun update(transform: (GatewayAppState) -> GatewayAppState) {
        mutable.value = transform(mutable.value)
    }
}

private class FakeCredentialStore : CredentialStore {
    override suspend fun put(credentialId: String, secret: String) = Unit
    override suspend fun getOrNull(credentialId: String): String? = null
    override suspend fun delete(credentialId: String) = Unit
    override suspend fun contains(credentialId: String): Boolean = false
}
