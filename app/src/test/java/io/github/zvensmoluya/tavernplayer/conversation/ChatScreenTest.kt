package io.github.zvensmoluya.tavernplayer.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.tavernplayer.connections.ModelCache
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.BuiltInPresets
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ChatScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `chat renders opening message and sends composer input`() {
        var input = ""
        var sent = false
        compose.setContent {
            TavernPlayerTheme {
                ChatScreen(
                    state = state(input = "你好"),
                    actions = actions(
                        updateInput = { input = it },
                        send = { sent = true },
                    ),
                )
            }
        }

        compose.onNodeWithText("欢迎来到旅店。").assertIsDisplayed()
        compose.onNodeWithTag("sendMessage").performClick()

        assertEquals("", input)
        assertTrue(sent)
    }

    @Test
    fun `running response exposes cancel and expandable reasoning`() {
        var cancelled = false
        val assistant = message(
            id = "streaming",
            content = "我在这里。",
            reasoning = listOf(ReasoningBlock(text = "先判断来客的意图")),
        )
        compose.setContent {
            TavernPlayerTheme {
                ChatScreen(
                    state = state(
                        running = true,
                        messages = listOf(ChatMessageState(assistant, ChatMessageStatus.STREAMING)),
                    ),
                    actions = actions(cancel = { cancelled = true }),
                )
            }
        }

        compose.onNodeWithTag("chooseModel").assertIsNotEnabled()
        compose.onNodeWithTag("choosePreset").assertIsNotEnabled()
        compose.onNodeWithText("查看思考").performClick()
        compose.onNodeWithText("先判断来客的意图").assertIsDisplayed()
        compose.onNodeWithTag("cancelGeneration").performClick()

        assertTrue(cancelled)
    }

    @Test
    fun `reasoning only stream reports thinking instead of appearing stuck`() {
        val assistant = message(
            id = "thinking",
            content = "",
            reasoning = listOf(ReasoningBlock(text = "先判断来客的意图")),
        )
        compose.setContent {
            TavernPlayerTheme {
                ChatScreen(
                    state = state(
                        running = true,
                        messages = listOf(ChatMessageState(assistant, ChatMessageStatus.STREAMING)),
                    ),
                    actions = actions(),
                )
            }
        }

        compose.onNodeWithTag("generationStatus").assertTextEquals("正在思考…")
    }

    @Test
    fun `restored chat starts at the latest message`() {
        val messages = (1..20).map { index ->
            ChatMessageState(message(id = "message-$index", content = "消息 $index"))
        }
        compose.setContent {
            TavernPlayerTheme {
                ChatScreen(state = state(messages = messages), actions = actions())
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("消息 20").assertIsDisplayed()
    }

    @Test
    fun `trace sheet shows compilation provider usage and finish data`() {
        val warning = CompilationDiagnostic(
            severity = DiagnosticSeverity.WARNING,
            code = "CONTEXT_BUDGET_NOT_ENFORCED",
            message = "本阶段未执行 token 裁剪。",
        )
        val prepared = PreparedMessage(
            role = MessageRole.SYSTEM,
            content = "系统提示",
            origin = PromptOrigin("relative", listOf("main")),
        )
        val trace = GenerationTraceState(
            plan = GenerationPlan(
                messages = listOf(prepared),
                maxOutputTokens = 512,
                declaredContextTokens = 8_192,
                assistantPrefill = "",
                presetId = "demo",
                presetName = "Demo",
                diagnostics = listOf(warning),
                trace = emptyList(),
            ),
            compileDiagnostics = listOf(warning),
            providerPreview = ProviderRequestPreview(
                protocol = ModelProtocol.OPENAI_RESPONSES,
                model = "gpt-test",
                systemInstruction = null,
                messages = listOf(ProviderPreviewMessage("system", "系统提示")),
                maxOutputTokens = 512,
                store = false,
                usesHostedState = false,
                assistantPrefillApplied = false,
            ),
            usage = GenerationUsage(12, 3, 15),
            finishReason = "stop",
        )
        compose.setContent {
            TavernPlayerTheme {
                ChatScreen(state = state(lastTrace = trace), actions = actions())
            }
        }

        compose.onNodeWithTag("openTrace").performClick()

        compose.onNodeWithTag("traceSheet").assertIsDisplayed()
        compose.onNodeWithText("WARNING: CONTEXT_BUDGET_NOT_ENFORCED").assertIsDisplayed()
        compose.onNodeWithTag("traceSheet").performScrollToNode(hasText("Provider 映射"))
        compose.onNodeWithText("Provider 映射").assertExists()
        compose.onNodeWithTag("traceSheet").performScrollToNode(
            hasText("input=12 output=3 total=15 cached=null reasoning=null"),
        )
        compose.onNodeWithText("input=12 output=3 total=15 cached=null reasoning=null").assertExists()
        compose.onNodeWithTag("traceSheet").performScrollToNode(hasText("stop"))
        compose.onNodeWithText("stop").assertExists()
    }

    @Test
    fun `model picker switches a ready connection and opens management`() {
        var selected: String? = null
        var manageCalled = false
        val second = connection(id = "second", model = "model-two")
        compose.setContent {
            TavernPlayerTheme {
                ChatScreen(
                    state = state(readyConnections = listOf(connection(), second)),
                    actions = actions(
                        selectConnection = { selected = it },
                        openModels = { manageCalled = true },
                    ),
                )
            }
        }

        compose.onNodeWithTag("chooseModel").performClick()
        compose.onNodeWithTag("connection-second").performClick()
        assertEquals("second", selected)

        compose.onNodeWithTag("chooseModel").performClick()
        compose.onNodeWithText("管理模型连接").performClick()
        assertTrue(manageCalled)
    }

    @Test
    fun `preset picker switches an asset and opens preset management`() {
        var selected: String? = null
        var manageCalled = false
        val first = BuiltInPresets.default
        val second = first.copy(id = "second-preset", name = "Focused", builtIn = false)
        compose.setContent {
            TavernPlayerTheme {
                ChatScreen(
                    state = state().copy(activePresetId = first.id, activePresetName = first.name),
                    presets = listOf(first, second),
                    actions = actions(
                        selectPreset = { selected = it },
                        openPresets = { manageCalled = true },
                    ),
                )
            }
        }

        compose.onNodeWithTag("choosePreset").performClick()
        compose.onNodeWithTag("quick-preset-second-preset").performClick()
        assertEquals("second-preset", selected)

        compose.onNodeWithTag("choosePreset").performClick()
        compose.onNodeWithText("管理预设").performClick()
        assertTrue(manageCalled)
    }

    @Test
    fun `opening swipe controls do not offer regenerate`() {
        var previous = false
        var next = false
        compose.setContent {
            TavernPlayerTheme {
                ChatScreen(
                    state = state().copy(
                        messages = listOf(ChatMessageState(message(), variantIndex = 0, variantCount = 2)),
                        variantNavigationAvailable = true,
                        regenerateAvailable = false,
                    ),
                    actions = actions(
                        previousVariant = { previous = true },
                        nextVariant = { next = true },
                    ),
                )
            }
        }

        compose.onNodeWithTag("previousVariant").assertIsNotEnabled()
        compose.onNodeWithTag("nextVariant").performClick()
        compose.onAllNodesWithTag("regenerate").assertCountEquals(0)

        assertFalse(previous)
        assertTrue(next)
    }

    private fun state(
        input: String = "",
        running: Boolean = false,
        messages: List<ChatMessageState> = listOf(ChatMessageState(message())),
        readyConnections: List<StoredConnection> = listOf(connection()),
        lastTrace: GenerationTraceState? = null,
    ) = ChatUiState(
        character = DemoConversationContent.character.snapshot(),
        persona = DemoConversationContent.persona,
        messages = messages,
        input = input,
        readyConnections = readyConnections,
        selectedConnectionId = readyConnections.firstOrNull()?.id,
        loadingConnections = false,
        running = running,
        lastTrace = lastTrace,
    )

    private fun message(
        id: String = "opening",
        content: String = "欢迎来到旅店。",
        reasoning: List<ReasoningBlock> = emptyList(),
    ) = ConversationMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        content = content,
        authorName = "米拉",
        reasoning = reasoning,
    )

    private fun connection(
        id: String = "connection",
        model: String = "model-one",
    ) = StoredConnection(
        id = id,
        name = "Test",
        templateId = "test",
        protocol = ModelProtocol.OPENAI_RESPONSES,
        apiAddress = "https://example.com/v1",
        streamEndpoint = "https://example.com/v1/responses",
        catalogEndpoint = null,
        authScheme = AuthScheme.NONE,
        credentialRef = null,
        credentialMask = null,
        approvedOrigins = emptySet(),
        selectedModel = model,
        modelCache = ModelCache(),
    )

    private fun actions(
        updateInput: (String) -> Unit = {},
        send: () -> Unit = {},
        cancel: () -> Unit = {},
        retry: () -> Unit = {},
        selectConnection: (String) -> Unit = {},
        reset: () -> Unit = {},
        openModels: () -> Unit = {},
        previousVariant: () -> Unit = {},
        nextVariant: () -> Unit = {},
        selectPreset: (String) -> Unit = {},
        openPresets: () -> Unit = {},
    ) = ChatScreenActions(
        updateInput = updateInput,
        send = send,
        cancel = cancel,
        retry = retry,
        selectConnection = selectConnection,
        reset = reset,
        openModels = openModels,
        previousVariant = previousVariant,
        nextVariant = nextVariant,
        selectPreset = selectPreset,
        openPresets = openPresets,
    )
}
