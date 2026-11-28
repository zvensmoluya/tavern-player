package io.github.zvensmoluya.tavernplayer.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.tavernplayer.connections.ModelCache
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.*
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

    @Test fun `composer shows stop throughout generation and reports finalization`() {
        var screen by mutableStateOf(state().copy(browserOperationRunning = true))
        var stops = 0
        compose.setContent { TavernPlayerTheme { ChatScreen(screen, actions(cancel = { stops++ })) } }
        compose.onAllNodesWithTag("cancelGeneration").assertCountEquals(0)
        compose.runOnIdle { screen = screen.copy(generationPhase = GenerationPhase.ACTIVE) }
        compose.onNodeWithTag("cancelGeneration").assertIsDisplayed().performClick()
        assertEquals(1, stops)
        compose.runOnIdle { screen = screen.copy(generationPhase = GenerationPhase.STOPPING) }
        compose.onNodeWithText("正在停止…").assertIsNotEnabled()
        compose.runOnIdle { screen = screen.copy(generationPhase = GenerationPhase.FINISHING) }
        compose.onNodeWithText("正在保存…").assertIsNotEnabled()
    }

    @Test fun `incompatible progress displays saved messages without a send action`() {
        val screen = state(input = "Saved draft").copy(readOnlyReason = "已有历史缺少 MVU 检查点")
        compose.setContent { TavernPlayerTheme { ChatScreen(state = screen, actions = actions()) } }
        compose.onNodeWithText("欢迎来到旅店。").assertIsDisplayed()
        compose.onNodeWithText("仅可阅读历史 · 已有历史缺少 MVU 检查点").assertIsDisplayed()
        compose.onAllNodesWithTag("sendMessage").assertCountEquals(0)
    }

    @Test fun `save retry stays available while business actions are blocked`() {
        val screen = state().copy(storageFailed = true)
        var retries = 0
        compose.setContent { TavernPlayerTheme { ChatScreen(state = screen, actions = actions().copy(retrySave = { retries++ })) } }
        compose.onNodeWithTag("retrySave").assertIsDisplayed().performClick()
        compose.onNodeWithTag("sendMessage").assertIsNotEnabled()
        assertEquals(1, retries)
    }

    @Test fun `world book opens as a full reader during generation and returns to chat`() {
        val screen = state().copy(generationPhase = GenerationPhase.ACTIVE, character = state().character.copy(worldBooks = listOf(
            WorldBookDefinition("book", entries = listOf(WorldBookEntryDefinition("entry", comment = "中性内容", content = "阅读正文"))))))
        compose.setContent { TavernPlayerTheme { ChatScreen(state = screen, actions = actions()) } }
        compose.onNodeWithTag("openWorldBook").performClick()
        compose.onNodeWithText("角色世界书").performClick()
        compose.onNodeWithTag("worldBookEntry-0-0").performClick()
        compose.onNodeWithTag("worldBookText-0").assertTextEquals("阅读正文")
        compose.onNodeWithTag("worldBookUsage").assertIsNotEnabled()
        compose.onNodeWithTag("worldBookBack").performClick()
        compose.onNodeWithTag("worldBookEntry-0-0").assertIsDisplayed()
        compose.onNodeWithTag("worldBookBack").performClick()
        compose.onNodeWithTag("openWorldBook").assertIsDisplayed()
    }

    @Test
    fun `composer sends input`() {
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

        compose.onNodeWithTag("sendMessage").performClick()

        assertEquals("", input)
        assertTrue(sent)
    }

    @Test
    fun `composer keeps the send button beside the input when little vertical room remains`() {
        compose.setContent {
            TavernPlayerTheme {
                Box(modifier = Modifier.size(width = 420.dp, height = 260.dp).testTag("compactChatWindow")) {
                    ChatScreen(
                        state = state(input = "第一行\n第二行\n第三行\n第四行\n第五行"),
                        actions = actions(),
                    )
                }
            }
        }

        val window = compose.onNodeWithTag("compactChatWindow").fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag("chatInput").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithTag("sendMessage").fetchSemanticsNode().boundsInRoot

        // 键盘占去高度后剩下的可视区很矮，发送按钮不能掉到输入框下面或可见区之外。
        assertTrue("发送按钮应与输入框处在同一行", send.top < input.bottom && send.bottom > input.top)
        assertTrue("发送按钮必须留在可见区内", send.top >= window.top - 0.5f && send.bottom <= window.bottom + 0.5f)
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
        compose.onNodeWithTag("sendMessage").assertIsDisplayed()
    }

    @Config(sdk = [35], qualifiers = "w411dp-h891dp")
    @Test
    fun `keyboard insets lift the composer and hand the message area to the keyboard`() {
        var insets by mutableStateOf(WindowInsets(0, 0, 0, 0))
        compose.setContent {
            TavernPlayerTheme {
                Box(modifier = Modifier.size(width = 420.dp, height = 700.dp).testTag("insetChatWindow")) {
                    ChatScreen(state = state(), actions = actions(), bottomBarInsets = insets)
                }
            }
        }

        val window = compose.onNodeWithTag("insetChatWindow").fetchSemanticsNode().boundsInRoot
        val before = compose.onNodeWithTag("webMessageArea").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { insets = WindowInsets(0, 0, 0, 300) }
        val after = compose.onNodeWithTag("webMessageArea").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithTag("sendMessage").fetchSemanticsNode().boundsInRoot

        // 键盘高度由消息区让出，输入栏随之抬到键盘之上，而不是被压在下面。
        assertEquals(300f, before.height - after.height, 1f)
        assertTrue(send.top >= window.top - 0.5f && send.bottom <= window.bottom + 0.5f)
    }

    @Test
    fun `saved context details load when the player opens the sheet`() {
        val warning = CompilationDiagnostic(DiagnosticSeverity.WARNING, "SAVED", "历史警告")
        val plan = GenerationPlan(emptyList(), 512, null, "", "demo", "Demo",
            diagnostics = listOf(warning), trace = emptyList())
        val trace = GenerationTraceState(plan = plan.copy(diagnostics = emptyList(), deferredPartsVariantId = "selected"))
        val reads = mutableListOf<String>()
        compose.setContent {
            TavernPlayerTheme {
                ChatScreen(state = state(lastTrace = trace), actions = actions().copy(readSavedPlan = { id ->
                    reads.add(id)
                    plan
                }))
            }
        }
        compose.runOnIdle { assertTrue(reads.isEmpty()) }
        compose.onNodeWithTag("openTrace").performClick()
        compose.onNodeWithText("WARNING: SAVED").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("selected"), reads) }
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

    private fun state(
        input: String = "",
        running: Boolean = false,
        messages: List<ChatMessageState> = listOf(ChatMessageState(message())),
        readyConnections: List<StoredConnection> = listOf(connection()),
        lastTrace: GenerationTraceState? = null,
        conversationState: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    ) = ChatUiState(
        character = DemoConversationContent.character.snapshot(),
        persona = DemoConversationContent.persona,
        messages = messages,
        input = input,
        readyConnections = readyConnections,
        selectedConnectionId = readyConnections.firstOrNull()?.id,
        loadingConnections = false,
        generationPhase = if (running) GenerationPhase.ACTIVE else GenerationPhase.IDLE,
        lastTrace = lastTrace,
        conversationState = conversationState,
    )

    private fun message(
        id: String = "opening",
        content: String = "欢迎来到旅店。",
        reasoning: List<ReasoningBlock> = emptyList(),
        role: MessageRole = MessageRole.ASSISTANT,
    ) = ConversationMessage(
        id = id,
        role = role,
        content = content,
        authorName = if (role == MessageRole.USER) "旅人" else "米拉",
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
        editMessage: (String, String, MessageEditMode) -> Unit = { _, _, _ -> },
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
        editMessage = editMessage,
    )
}
