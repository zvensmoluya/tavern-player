package io.github.zvensmoluya.tavernplayer.conversation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.tavernplayer.content.*
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NativeScriptSurfaceViewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun formKeepsUserTextAndSubmitsBoundActionWithRenderIdentity() {
        val action = NativeSurfaceAction("submit", "Continue", "continue")
        val rendered = NativeRenderedSurface("input", "revision", NativeSurfaceData(NativeSurfaceType.FORM, "Input",
            fields = listOf(NativeSurfaceField("name", "Name", "Default")), actions = listOf(action)))
        var invocation: NativeSurfaceInvocation? = null
        compose.setContent { TavernPlayerTheme { NativeScriptSurfaceCard(rendered, true) { invocation = it } } }
        compose.onNodeWithTag("surface-field-name").performTextReplacement("Edited")
        compose.onNodeWithTag("surface-action-input-submit").performClick()
        val submitted = requireNotNull(invocation)
        assertEquals(mapOf("name" to "Edited"), submitted.input)
        assertEquals("revision", submitted.revision)
        assertEquals(action, submitted.action)
    }

    @Test fun collectionUsesItemIdentityAndDisablesUnavailableActions() {
        val action = NativeSurfaceAction("buy", "Buy", "buy")
        val rendered = NativeRenderedSurface("shop", "revision", NativeSurfaceData(NativeSurfaceType.COLLECTION, "Shop",
            items = listOf(NativeSurfaceItem("a", "A", actions = listOf(action)), NativeSurfaceItem("b", "B", actions = listOf(action.copy(enabled = false))))))
        var invocation: NativeSurfaceInvocation? = null
        compose.setContent { TavernPlayerTheme { NativeScriptSurfaceCard(rendered, true) { invocation = it } } }
        compose.onNodeWithTag("surface-action-shop-b-buy").assertIsNotEnabled()
        compose.onNodeWithTag("surface-action-shop-a-buy").performClick()
        assertEquals("a", invocation!!.itemKey)
    }
}
