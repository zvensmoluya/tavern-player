package io.github.zvensmoluya.tavernplayer.characters

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NativeCompilationScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `adaptation details stay collapsed and repeated warnings render once`() {
        val adaptation = io.github.zvensmoluya.tavernplayer.content.NativeAdaptation(sourceSha256 = "a".repeat(64),
            report = io.github.zvensmoluya.tavernplayer.content.NativeCompatibilityReport(summary = "适配摘要",
                warnings = listOf("未验证的功能", "未验证的功能")))
        compose.setContent { TavernPlayerTheme {
            CharacterDetailScreen(CharacterAsset("sample", name = "中性样本", nativeAdaptation = adaptation),
                emptyList(), null, {}, {}, {}, importing = false)
        } }
        compose.onNodeWithTag("characterDetail").performScrollToNode(hasText("查看适配说明"))
        compose.onAllNodesWithText("未验证的功能").assertCountEquals(0)
        compose.onNodeWithText("查看适配说明").performClick()
        compose.onAllNodesWithText("未验证的功能").assertCountEquals(1)
    }

    @Test fun `detail selects a compiler model and provides cancellable preparation`() {
        var selection by mutableStateOf("model-a")
        var running by mutableStateOf(false)
        var requests = 0
        var stops = 0
        val connections = listOf(connection("model-a"), connection("model-b"))
        compose.setContent { TavernPlayerTheme {
            CharacterDetailScreen(CharacterAsset("sample", name = "中性样本"), emptyList(), null, {}, {}, {},
                importing = running, compiling = running, compilationConnections = connections,
                compilationConnectionId = selection, onSelectCompilationConnection = { selection = it },
                onCompile = { requests++; running = true }, onCancelCompilation = { stops++; running = false })
        } }
        compose.onNodeWithTag("characterDetail").performScrollToNode(hasTestTag("compilationModel"))
        compose.onNodeWithTag("compilationModel").performClick()
        compose.onNodeWithText("model-b · model-b").performClick()
        assertEquals("model-b", selection)
        compose.onNodeWithTag("characterDetail").performScrollToNode(hasTestTag("compileNativeAdaptation"))
        compose.onNodeWithTag("compileNativeAdaptation").performClick()
        assertEquals(1, requests)
        compose.onNodeWithTag("compilationModel").assertIsNotEnabled()
        compose.onNodeWithTag("characterDetail").performScrollToNode(hasTestTag("cancelCompilation"))
        compose.onNodeWithTag("cancelCompilation").performClick()
        assertEquals(1, stops)
        compose.onNodeWithTag("compileNativeAdaptation").assertIsEnabled()
    }

    private fun connection(id: String) = StoredConnection(id, id, "test", ModelProtocol.OPENAI_RESPONSES,
        "https://example.test/v1", "https://example.test/v1/responses", null, AuthScheme.NONE,
        null, null, emptySet(), id)
}
