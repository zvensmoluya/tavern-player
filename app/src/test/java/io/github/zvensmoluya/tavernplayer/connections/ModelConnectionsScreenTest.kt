package io.github.zvensmoluya.tavernplayer.connections

import kotlinx.coroutines.runBlocking
import org.junit.After
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import io.github.zvensmoluya.modelgateway.ModelGateway
import io.github.zvensmoluya.modelgateway.CredentialResolver
import io.github.zvensmoluya.modelgateway.SecretValue
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ModelConnectionsScreenTest {
    @get:Rule
    val dispatcher = io.github.zvensmoluya.tavernplayer.conversation.MainDispatcherRule()
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `empty list exposes one add action`() {
        var added = false
        compose.setContent {
            TavernPlayerTheme {
                ModelConnectionsScreen(ConnectionsUiState(loading = false), actions(add = { added = true }))
            }
        }

        compose.onNodeWithText("连接你的第一个模型").assertIsDisplayed()
        compose.onNodeWithTag("addConnection").performClick()
        assertTrue(added)
    }

    @Test
    fun `editor exposes protocol address and key without transport fields`() {
        val template = ConnectionTemplates.openAiResponses
        val draft = ConnectionDraft(
            id = "one",
            name = "",
            templateId = template.id,
            protocol = template.protocol,
            apiAddress = "https://api.openai.com/v1",
            selectedModel = "",
        )
        compose.setContent {
            TavernPlayerTheme {
                ModelConnectionsScreen(
                    state = ConnectionsUiState(
                        loading = false,
                        editor = ConnectionEditorState(
                            draft = draft,
                            credentialStatus = CredentialStatus.NOT_REQUIRED,
                            page = ConnectionPage.CREATE,
                        ),
                    ),
                    actions = actions(),
                )
            }
        }

        compose.onNodeWithTag("apiAddress").assertIsDisplayed().assertTextContains("https://api.openai.com/v1")
        compose.onNodeWithText("请求格式").assertIsDisplayed()
        compose.onNodeWithText("OpenAI Responses").assertIsDisplayed()
        compose.onNodeWithTag("connectionEditorList").performScrollToNode(hasTestTag("credential"))
        compose.onNodeWithTag("credential").assertIsDisplayed()
        compose.onAllNodesWithText("Stream endpoint").assertCountEquals(0)
    }

    @Test
    fun `stored model exposes optional token limit overrides`() {
        showStoredModel()
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("模型 Token 上限").performClick()
        compose.onNodeWithTag("contextTokenLimitOverride").assertIsDisplayed()
        compose.onNodeWithTag("outputTokenLimitOverride").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("模型目录没有提供这个模型的 Token 上限").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `cancel key edit locally without saving or showing the old suffix`() {
        val store = showStoredModel()
        compose.onNodeWithTag("replaceCredential").performScrollTo().performClick()
        compose.onAllNodesWithText("1234", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("credential").performTextInput("fixture-new")
        compose.onNodeWithTag("saveKey").assertIsEnabled()
        compose.onNodeWithTag("saveConnection").assertDoesNotExist()
        compose.onNodeWithTag("modelPicker").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("credential").assertDoesNotExist()
        compose.onNodeWithTag("testConnection").assertIsEnabled()
        assertEquals("fixture-1234", runBlocking { testCredentials.getOrNull(store.value.connections.single().credentialRef!!) })
    }

    @Test
    fun `address changes are scoped and saved together with their format`() {
        val store = showStoredModel()
        compose.onNodeWithText("请求格式").performClick()
        compose.onNodeWithTag("apiAddress").performTextReplacement("https://relay.example.test/api/v1")
        compose.onNodeWithText("请求格式").performClick()
        compose.onNodeWithText("OpenAI Chat Completions").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("保存修改").performClick()
        compose.onNodeWithText("新地址待授权").assertExists()
        val saved = store.value.connections.single()
        assertEquals("https://relay.example.test/api/v1", saved.apiAddress)
        assertEquals(ConnectionTemplates.openAiChat.protocol, saved.protocol)
        assertEquals("custom-model", saved.selectedModel)
        compose.onNodeWithTag("testConnection").assertIsNotEnabled()
        compose.onNodeWithText("确认地址").performScrollTo().performClick()
        compose.onNodeWithText("允许向 https://relay.example.test:443 发送已保存的 Key").assertExists()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("保存修改").performClick()
        compose.onNodeWithTag("testConnection").assertIsEnabled()
    }

    @Test
    fun `manual model is committed without a second save`() {
        val store = showStoredModel()
        compose.onNodeWithTag("modelPicker").performClick()
        compose.onNodeWithText("输入模型 ID").performClick()
        compose.onNodeWithTag("manualModelId").performTextInput("custom/manual")
        compose.onNodeWithText("使用模型").performClick()
        compose.onNodeWithTag("modelPicker").assertTextContains("custom/manual")
        compose.onNodeWithTag("modelPicker").assertTextContains("✓ 已选用")
        assertEquals("custom/manual", store.value.connections.single().selectedModel)
        compose.onNodeWithTag("modelPicker").performClick()
        compose.onNode(androidx.compose.ui.test.isSelected()).assertTextContains("custom/manual")
    }

    @Test
    fun `using another model is explicit and editing a different connection preserves the choice`() {
        val store = showStoredModel(includeSecond = true)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("use-second").performClick()
        compose.waitUntil { store.value.recentConnectionId == "second" }
        compose.onNodeWithTag("use-second").assertDoesNotExist()
        compose.onNodeWithTag("use-limits").assertIsEnabled()
        compose.onNodeWithText("custom-model").performClick()
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("连接名称").performClick()
        compose.onNodeWithTag("connectionName").performTextReplacement("Renamed")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil { store.value.connections.any { it.name == "Renamed" } }
        assertEquals("second", store.value.recentConnectionId)
    }

    @Test
    fun `full catalog can be browsed beyond the first hundred models`() {
        val models = List(130) { StoredModel("m-$it", "Model $it") }
        val store = showStoredModel(models)
        compose.onNodeWithTag("connectionEditorList").performScrollToNode(hasTestTag("modelPicker"))
        compose.onNodeWithTag("modelPicker").performClick()
        compose.onNodeWithTag("catalogModels").performScrollToKey("m-129")
        compose.onNodeWithText("Model 129").performClick()
        assertEquals("m-129", store.value.connections.single().selectedModel)
        compose.onNodeWithTag("catalogModels").assertDoesNotExist()
    }

    @Test
    fun `custom URL survives a format change model selection and key replacement`() {
        val store = FakeConnectionStateStore()
        val credentials = FakeCredentialStore()
        val probeKeys = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "relay.example.test")
            val body = when (request.url.encodedPath) {
                "/api/v1/models" -> """{"data":[{"id":"sample/model"}]}""".toResponseBody("application/json".toMediaType())
                "/api/v1/chat/completions" -> {
                    probeKeys.add(request.header("Authorization").orEmpty())
                    ("""data: {"choices":[{"delta":{"content":"OK"},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n")
                        .toResponseBody("text/event-stream".toMediaType())
                }
                else -> error("Unexpected route")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", body.contentType().toString()).body(body).build()
        }.build()
        val gateway = ModelGateway(CredentialResolver { credentials.getOrNull(it)?.let(::SecretValue) }, client)
        val repo = ConnectionRepository(store, credentials, { gateway.modelCatalog.list(it) })
        val vm = ModelConnectionsViewModel(repo, ProbeService(gateway, repo))
        try {
            compose.setContent { TavernPlayerTheme { ModelConnectionsRoute(vm) } }
            compose.onNodeWithTag("addConnection").performClick()
            val list = compose.onNodeWithTag("connectionEditorList")
            val url = "https://relay.example.test/api/v1/responses"
            compose.onNodeWithTag("apiAddress").performTextReplacement(url)
            compose.onNodeWithText("请求格式").performClick()
            compose.onNodeWithText("选择请求格式").assertIsDisplayed()
            compose.onNode(androidx.compose.ui.test.isSelected()).assertTextContains("OpenAI Responses")
            compose.onNodeWithText("OpenAI Chat Completions").performClick()
            compose.onNodeWithTag("apiAddress").assertTextContains(url)
            list.performScrollToNode(hasTestTag("credential"))
            compose.onNodeWithTag("credential").performTextInput("fixture-1234")
            list.performScrollToNode(hasTestTag("saveConnection"))
            compose.onNodeWithTag("saveConnection").performClick()
            compose.waitUntil(10000) {
                val state = vm.uiState.value
                check(state.editor?.message == null && state.editor?.modelMessage == null) {
                    "Save or catalog failed: ${state.editor?.message} ${state.editor?.modelMessage}"
                }
                state.connections.singleOrNull()?.modelCache?.models?.isNotEmpty() == true
            }
            assertEquals("relay.example.test", store.value.connections.single().name)
            compose.onNodeWithText("选择模型").assertIsDisplayed()
            compose.onNodeWithText("sample/model").performClick()
            compose.waitUntil { store.value.connections.single().selectedModel == "sample/model" }
            list.performScrollToNode(hasTestTag("testConnection"))
            compose.onNodeWithTag("testConnection").performClick()
            compose.waitUntil(10000) { vm.uiState.value.probe.text == "OK" && !vm.uiState.value.probe.running }
            list.performScrollToNode(hasTestTag("replaceCredential"))
            compose.onNodeWithTag("replaceCredential").performScrollTo().performClick()
            compose.onNodeWithTag("credential").performTextInput("fixture-5678")
            list.performScrollToNode(hasTestTag("saveKey"))
            compose.onNodeWithTag("saveKey").performClick()
            compose.waitUntil(10000) { vm.uiState.value.editor?.let { !it.saving && !it.refreshingModels } == true }
            list.performScrollToNode(hasTestTag("testConnection"))
            compose.onNodeWithTag("testConnection").performClick()
            compose.waitUntil(10000) { vm.uiState.value.probe.text == "OK" && !vm.uiState.value.probe.running }
            assertEquals(listOf("Bearer fixture-1234", "Bearer fixture-5678"), probeKeys.toList())
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private val testCredentials = FakeCredentialStore()
    private var activeModel: ModelConnectionsViewModel? = null

    @After fun close() { activeModel?.viewModelScope?.cancel() }

    private fun showStoredModel(models: List<StoredModel> = emptyList(), includeSecond: Boolean = false): FakeConnectionStateStore {
        val template = ConnectionTemplates.openAiResponses
        val stored = StoredConnection(
            id = "limits",
            name = "Custom",
            templateId = template.id,
            protocol = template.protocol,
            apiAddress = "https://gateway.example.test/v1",
            streamEndpoint = "https://gateway.example.test/v1/responses",
            catalogEndpoint = "https://gateway.example.test/v1/models",
            authScheme = AuthScheme.BEARER,
            credentialRef = "fixture",
            approvedOrigins = setOf("https://gateway.example.test:443"),
            selectedModel = "custom-model",
            modelCache = ModelCache(models),
        )
        val connections = if (includeSecond) listOf(stored, stored.copy(id = "second", name = "Second", selectedModel = "other-model"))
            else listOf(stored)
        val store = FakeConnectionStateStore(GatewayAppState(connections = connections, recentConnectionId = stored.id))
        runBlocking { testCredentials.put("fixture", "fixture-1234") }
        val repo = ConnectionRepository(store, testCredentials, { error("Unexpected catalog request") })
        val gateway = ModelGateway(CredentialResolver { null })
        val vm = ModelConnectionsViewModel(repo, ProbeService(gateway, repo))
        activeModel = vm
        compose.setContent { TavernPlayerTheme { ModelConnectionsRoute(vm) } }
        compose.onNodeWithText("custom-model").performClick()
        return store
    }

    private fun actions(add: () -> Unit = {}) = ConnectionScreenActions(
        add = add,
        edit = {},
        use = {},
        closeEditor = {},
        delete = {},
        chooseProtocol = {},
        updateName = {},
        updateApiAddress = {},
        updateCredential = {},
        selectModel = {},
        showPage = {},
        updateContextTokenLimit = {},
        updateOutputTokenLimit = {},
        confirmReuse = {},
        save = {},
        refreshModels = {},
        runTest = {},
        cancelTest = {},
    )
}
