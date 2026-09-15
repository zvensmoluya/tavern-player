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
import io.github.zvensmoluya.tavernplayer.content.NativeFormField
import io.github.zvensmoluya.tavernplayer.content.NativeFormFieldType
import io.github.zvensmoluya.tavernplayer.content.NativeFormOption
import io.github.zvensmoluya.tavernplayer.content.NativeFormView
import io.github.zvensmoluya.tavernplayer.content.NativeStatusItem
import io.github.zvensmoluya.tavernplayer.content.NativeStatusView
import io.github.zvensmoluya.tavernplayer.content.NativeSceneAsset
import io.github.zvensmoluya.tavernplayer.content.NativeSceneView
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import kotlinx.serialization.json.JsonPrimitive
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

    @Test fun `save retry stays available in both conversation routes while business actions are blocked`() {
        var screen by mutableStateOf(state().copy(storageFailed = true))
        var retries = 0
        compose.setContent { TavernPlayerTheme { ChatScreen(state = screen, actions = actions().copy(retrySave = { retries++ })) } }
        compose.onNodeWithTag("retrySave").assertIsDisplayed().performClick()
        compose.onNodeWithTag("sendMessage").assertIsNotEnabled()
        compose.runOnIdle { screen = screen.copy(executionMode = ConversationExecutionMode.BROWSER) }
        compose.onNodeWithTag("retrySave").assertIsDisplayed().performClick()
        compose.onNodeWithTag("sendMessage").assertIsNotEnabled()
        assertEquals(2, retries)
    }

    @Test fun `world book opens as a full reader during generation and returns to chat`() {
        val screen = state().copy(running = true, character = state().character.copy(worldBooks = listOf(
            WorldBookDefinition("book", entries = listOf(WorldBookEntryDefinition("entry", comment = "中性内容", content = "阅读正文"))))))
        compose.setContent { TavernPlayerTheme { ChatScreen(state = screen, actions = actions()) } }
        compose.onNodeWithTag("openWorldBook").performClick()
        compose.onNodeWithTag("worldBookEntry-0-0").performClick()
        compose.onNodeWithTag("worldBookText-0").assertTextEquals("阅读正文")
        compose.onNodeWithTag("worldBookUsage").assertIsNotEnabled()
        compose.onNodeWithTag("worldBookBack").performClick()
        compose.onNodeWithTag("worldBookEntry-0-0").assertIsDisplayed()
        compose.onNodeWithTag("worldBookBack").performClick()
        compose.onNodeWithTag("openWorldBook").assertIsDisplayed()
    }

    @Test fun `historical status reads the chosen message snapshot rather than current state`() {
        val older = ChatMessageState(message = message(id = "past", content = "昨日抵达。"), nativeStateAfter = io.github.zvensmoluya.tavernplayer.content.PlayerStateReader(mapOf("day" to JsonPrimitive(2))))
        val later = ChatMessageState(message = message(id = "now", content = "今天启程。"), nativeStateAfter = io.github.zvensmoluya.tavernplayer.content.PlayerStateReader(mapOf("day" to JsonPrimitive(5))))
        compose.setContent { TavernPlayerTheme { ChatScreen(state = state(messages = listOf(older, later),
            conversationState = mapOf("day" to JsonPrimitive(5)), nativeStatus = NativeStatusView(items = listOf(NativeStatusItem("day", "天数")))), actions = actions()) } }
        compose.onNodeWithTag("viewNativeState-past").performScrollTo().performClick()
        compose.onNodeWithText("此条回复后的状态").assertIsDisplayed()
        compose.onNodeWithTag("native-state-day").assertTextEquals("2")
    }

    @Test fun `historical inventory uses its MVU checkpoint even without a status panel`() {
        val bag = NativeCollectionView("bag", "背包", "inventory", shape = NativeCollectionShape.OBJECT, fields = listOf(
            NativeCollectionField("name", "物品", entryKey = true), NativeCollectionField("count", "数量", path = "/数量")))
        val adaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), mvu = NativeMvuProgram("schema"),
            stateBindings = listOf(NativeStateBinding("inventory", NativeStateSource.MVU, "/stat_data/物品栏", ConversationStateValueType.RECORD)),
            collections = listOf(bag))
        fun reader(count: Int) = ConversationStateReader(adaptation, ConversationRuntimeState(mvuState = MvuStateSnapshot("b".repeat(64), "c".repeat(64),
            kotlinx.serialization.json.Json.parseToJsonElement("""{"stat_data":{"物品栏":{"茶包":{"数量":$count}}}}""") as kotlinx.serialization.json.JsonObject)))
        val past = reader(3)
        val now = reader(2)
        val screen = state(messages = listOf(ChatMessageState(message(id = "past", content = "收起茶包。"), nativeStateAfter = past),
            ChatMessageState(message(id = "now", content = "取用一份。"), nativeStateAfter = now)))
            .copy(nativeCollections = listOf(bag), nativeState = now)
        compose.setContent { TavernPlayerTheme { ChatScreen(screen, actions = actions()) } }
        compose.onNodeWithTag("viewNativeState-past").performScrollTo().performClick()
        compose.onNodeWithText("茶包").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("2").assertDoesNotExist()
    }

    @Test fun `long inventory descriptions use full width below their labels`() {
        val description = "这是一段较长的物品说明，应该完整换行展示，不能与左侧字段标签重叠。"
        val view = NativeCollectionView("bag", "背包", "items", shape = NativeCollectionShape.OBJECT,
            fields = listOf(NativeCollectionField("description", "描述", path = "/description")))
        val reader = PlayerStateReader(mapOf("items" to kotlinx.serialization.json.buildJsonObject {
            put("tea", kotlinx.serialization.json.buildJsonObject { put("description", JsonPrimitive(description)) })
        }))
        compose.setContent { TavernPlayerTheme { NativeCollectionCard(view, reader) } }
        val label = compose.onNodeWithText("描述").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("native-collection-bag-item-0-description").fetchSemanticsNode().boundsInRoot
        assertTrue("description must be below its label", body.top >= label.bottom)
        compose.onNodeWithText(description).assertIsDisplayed()
    }

    @Test fun `missing scene state cannot select an asset with an empty key`() {
        var resolutions = 0
        compose.setContent { TavernPlayerTheme {
            NativeSceneCard(NativeSceneView("scene", stateKey = "absent", assets = listOf(NativeSceneAsset("", "image"))),
                PlayerStateReader(emptyMap())) { resolutions++; null }
        } }
        compose.onNodeWithText("状态不可用").assertIsDisplayed()
        assertEquals(0, resolutions)
    }

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
        val before = compose.onNodeWithTag("chatContent").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { insets = WindowInsets(0, 0, 0, 300) }
        val after = compose.onNodeWithTag("chatContent").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithTag("sendMessage").fetchSemanticsNode().boundsInRoot

        // 键盘高度由消息区让出，输入栏随之抬到键盘之上，而不是被压在下面。
        assertEquals(300f, before.height - after.height, 1f)
        assertTrue(send.top >= window.top - 0.5f && send.bottom <= window.bottom + 0.5f)
    }

    @Test
    fun `native opening form replaces marker and submits structured values`() {
        var submitted: Pair<String, Map<String, List<String>>>? = null
        val view = NativeFormView(
            id = "opening-form",
            title = "预约申请单",
            marker = "<GAMESTART/>",
            fields = listOf(
                NativeFormField("name", NativeFormFieldType.TEXT, "姓名"),
                NativeFormField(
                    "reason",
                    NativeFormFieldType.MULTI_SELECT,
                    "预约理由",
                    options = listOf(NativeFormOption("chat", "单纯想聊天")),
                ),
            ),
            submitLabel = "确定预约",
            draftTemplate = "{{form.name}}",
        )
        val opening = ChatMessageState(
            message = message(content = "<GAMESTART/>"),
            nativeForms = listOf(view),
        )
        compose.setContent {
            TavernPlayerTheme {
                ChatScreen(
                    state = state(messages = listOf(opening)),
                    actions = actions(submitNativeForm = { id, values -> submitted = id to values }),
                )
            }
        }

        compose.onNodeWithText("预约申请单").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("native-form-field-name").performScrollTo().performTextReplacement("米拉")
        compose.onNodeWithTag("native-form-option-reason-chat").performScrollTo().performClick()
        compose.onNodeWithTag("native-form-submit-opening-form").performScrollTo().performClick()

        assertEquals("opening-form", submitted?.first)
        assertEquals(listOf("米拉"), submitted?.second?.get("name"))
        assertEquals(listOf("chat"), submitted?.second?.get("reason"))
    }

    @Test
    fun `native status is a Player owned conversation surface`() {
        val view = NativeStatusView(
            title = "当前状态",
            items = listOf(NativeStatusItem("world-day", "天数")),
        )
        compose.setContent {
            TavernPlayerTheme {
                ChatScreen(
                    state = state(
                        conversationState = mapOf("world-day" to JsonPrimitive(3)),
                        nativeStatus = view,
                    ),
                    actions = actions(),
                )
            }
        }

        compose.onNodeWithTag("openNativeDetails").performClick()
        compose.onNodeWithText("当前状态").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("native-state-world-day").assertTextEquals("3")
    }

    @Test
    fun `native scene selects only a mapped character asset`() {
        var requestedAsset: String? = null
        val scene = NativeSceneView(
            id = "location",
            title = "当前场景",
            stateKey = "location",
            assets = listOf(NativeSceneAsset("beach", "asset-beach", "海滩")),
            emptyLabel = "图片不可用",
        )
        compose.setContent {
            TavernPlayerTheme {
                ChatScreen(
                    state = state(
                        conversationState = mapOf("location" to JsonPrimitive("beach")),
                        nativeScenes = listOf(scene),
                    ),
                    actions = actions(),
                    resolveAssetPath = { _, assetId -> requestedAsset = assetId; null },
                )
            }
        }

        compose.onNodeWithTag("openNativeDetails").performClick()
        compose.onNodeWithTag("native-scene-location").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("图片不可用").assertIsDisplayed()
        assertEquals("asset-beach", requestedAsset)
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

    @Test
    fun `editing a historical user message confirms truncation and requests regeneration`() {
        var edited: Triple<String, String, MessageEditMode>? = null
        val messages = listOf(
            ChatMessageState(message(id = "opening", content = "开场")),
            ChatMessageState(message(id = "user", content = "原问题", role = MessageRole.USER)),
            ChatMessageState(message(id = "answer", content = "旧回复")),
        )
        compose.setContent {
            TavernPlayerTheme {
                ChatScreen(
                    state = state(messages = messages),
                    actions = actions(editMessage = { id, text, mode -> edited = Triple(id, text, mode) }),
                )
            }
        }

        compose.onNodeWithTag("editMessage-user").performClick()
        compose.onNodeWithTag("messageEditInput-user").performTextReplacement("新问题")
        compose.onNodeWithTag("restartFromMessage-user").performClick()

        compose.onNodeWithTag("confirmMessageEdit").assertIsDisplayed()
        assertEquals(null, edited)
        compose.onNodeWithTag("confirmMessageEditAction").performClick()
        assertEquals(Triple("user", "新问题", MessageEditMode.RESTART), edited)
    }

    @Test
    fun `editing the latest assistant message saves inline without regeneration`() {
        var edited: Triple<String, String, MessageEditMode>? = null
        compose.setContent {
            TavernPlayerTheme {
                ChatScreen(
                    state = state(messages = listOf(ChatMessageState(message(id = "answer", content = "旧回复")))),
                    actions = actions(editMessage = { id, text, mode -> edited = Triple(id, text, mode) }),
                )
            }
        }

        compose.onNodeWithTag("editMessage-answer").performClick()
        compose.onNodeWithTag("messageEditInput-answer").performTextReplacement("手动修正")
        compose.onNodeWithTag("saveMessageTextEdit-answer").performClick()

        compose.onAllNodesWithTag("confirmMessageEdit").assertCountEquals(0)
        assertEquals(Triple("answer", "手动修正", MessageEditMode.TEXT_ONLY), edited)
    }

    private fun state(
        input: String = "",
        running: Boolean = false,
        messages: List<ChatMessageState> = listOf(ChatMessageState(message())),
        readyConnections: List<StoredConnection> = listOf(connection()),
        lastTrace: GenerationTraceState? = null,
        conversationState: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
        nativeStatus: NativeStatusView? = null,
        nativeScenes: List<NativeSceneView> = emptyList(),
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
        conversationState = conversationState,
        nativeStatus = nativeStatus,
        nativeScenes = nativeScenes,
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
        editMessage: (String, String, MessageEditMode) -> Unit = { _, _, _ -> },
        submitNativeForm: (String, Map<String, List<String>>) -> Unit = { _, _ -> },
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
        submitNativeForm = submitNativeForm,
    )
}
