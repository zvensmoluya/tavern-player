package io.github.zvensmoluya.tavernplayer.connections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ModelConnectionsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `empty list exposes template add flow`() {
        var addCalled = false
        compose.setContent {
            TavernPlayerTheme {
                ModelConnectionsScreen(ConnectionsUiState(loading = false), actions(add = { addCalled = true }))
            }
        }

        compose.onNodeWithText("还没有模型连接").assertIsDisplayed()
        compose.onNodeWithTag("addConnection").performClick()
        compose.onNodeWithText("OpenAI Responses").performClick()
        assertTrue(addCalled)
    }

    @Test
    fun `editor always exposes manual model field and validation status`() {
        val template = ConnectionTemplates.vertexExpress
        val draft = ConnectionDraft(
            id = "one",
            name = template.displayName,
            templateId = template.id,
            protocol = template.protocol,
            streamEndpoint = template.streamEndpoint,
            catalogEndpoint = null,
            authScheme = AuthScheme.X_GOOG_API_KEY,
            selectedModel = "",
        )
        compose.setContent {
            TavernPlayerTheme {
                ModelConnectionsScreen(
                    state = ConnectionsUiState(
                        loading = false,
                        editor = ConnectionEditorState(draft = draft, credentialStatus = CredentialStatus.MISSING),
                    ),
                    actions = actions(),
                )
            }
        }

        compose.onNode(hasScrollAction()).performScrollToIndex(6)
        compose.onNodeWithText("需要重新输入密钥").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToIndex(10)
        compose.onNodeWithTag("manualModelId").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToIndex(12)
        compose.onNodeWithText("连接测试最多 512 tokens。temperature、top-p、top-k 均不发送。")
            .assertIsDisplayed()
    }

    private fun actions(add: (String) -> Unit = {}) = ConnectionScreenActions(
        add = add,
        edit = {},
        closeEditor = {},
        delete = {},
        chooseTemplate = {},
        updateName = {},
        updateStreamEndpoint = {},
        updateCatalogEndpoint = {},
        updateAuthScheme = {},
        updateCredential = {},
        updateModel = {},
        confirmReuse = {},
        save = {},
        refreshModels = {},
        updateProbeSystem = {},
        updateProbeUser = {},
        runProbe = {},
        cancelProbe = {},
    )
}
