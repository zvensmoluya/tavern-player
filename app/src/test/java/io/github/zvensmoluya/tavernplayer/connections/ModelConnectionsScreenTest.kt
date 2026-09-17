package io.github.zvensmoluya.tavernplayer.connections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ModelConnectionsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `empty list exposes one add action`() {
        var addCalled = false
        compose.setContent {
            TavernPlayerTheme {
                ModelConnectionsScreen(ConnectionsUiState(loading = false), actions(add = { addCalled = true }))
            }
        }

        compose.onNodeWithText("连接你的第一个模型").assertIsDisplayed()
        compose.onNodeWithTag("addConnection").performClick()
        assertTrue(addCalled)
    }

    @Test
    fun `editor exposes protocol address and key without transport fields`() {
        val template = ConnectionTemplates.openAiResponses
        val draft = ConnectionDraft(
            id = "one",
            name = template.displayName,
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
                            credentialStatus = CredentialStatus.MISSING,
                        ),
                    ),
                    actions = actions(),
                )
            }
        }

        compose.onNodeWithText("接口协议").assertIsDisplayed()
        compose.onNodeWithTag("apiAddress").assertIsDisplayed()
        compose.onNodeWithTag("credential").assertIsDisplayed()
        compose.onAllNodesWithText("Stream endpoint").assertCountEquals(0)
        compose.onAllNodesWithText("Catalog endpoint（可选）").assertCountEquals(0)
        compose.onAllNodesWithText("鉴权：Bearer").assertCountEquals(0)
        compose.onNodeWithText("更多设置").performClick()
        compose.onAllNodesWithText("认证方式").assertCountEquals(0)
        compose.onAllNodesWithText("Bearer Token").assertCountEquals(0)
    }

    @Test
    fun `stored model exposes optional token limit overrides`() {
        val template = ConnectionTemplates.openAiResponses
        val stored = StoredConnection(
            id = "limits",
            name = "Custom",
            templateId = template.id,
            protocol = template.protocol,
            apiAddress = "https://gateway.example.test/v1",
            streamEndpoint = "https://gateway.example.test/v1/responses",
            catalogEndpoint = "https://gateway.example.test/v1/models",
            authScheme = AuthScheme.NONE,
            credentialRef = null,
            credentialMask = null,
            approvedOrigins = emptySet(),
            selectedModel = "custom-model",
        )
        val draft = ConnectionDraft(
            id = stored.id,
            name = stored.name,
            templateId = stored.templateId,
            protocol = stored.protocol,
            apiAddress = stored.apiAddress,
            selectedModel = stored.selectedModel,
        )
        compose.setContent {
            TavernPlayerTheme {
                ModelConnectionsScreen(
                    state = ConnectionsUiState(
                        loading = false,
                        connections = listOf(stored),
                        editor = ConnectionEditorState(
                            draft = draft,
                            credentialStatus = CredentialStatus.NOT_REQUIRED,
                        ),
                    ),
                    actions = actions(),
                )
            }
        }

        compose.onNodeWithTag("connectionEditorList")
            .performScrollToNode(hasText("设置 Token 上限（可选）"))
        compose.onNodeWithText("设置 Token 上限（可选）").performClick()
        compose.onNodeWithTag("contextTokenLimitOverride").assertIsDisplayed()
        compose.onNodeWithTag("outputTokenLimitOverride").assertIsDisplayed()
        compose.onNodeWithText("模型目录没有提供这个模型的 Token 上限").assertIsDisplayed()
    }

    private fun actions(add: (String) -> Unit = {}) = ConnectionScreenActions(
        add = add,
        edit = {},
        closeEditor = {},
        delete = {},
        chooseProtocol = {},
        updateName = {},
        updateApiAddress = {},
        updateCredential = {},
        updateModel = {},
        updateContextTokenLimit = {},
        updateOutputTokenLimit = {},
        confirmReuse = {},
        save = {},
        refreshModels = {},
        runTest = {},
        cancelTest = {},
    )
}
