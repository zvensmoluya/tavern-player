package io.github.zvensmoluya.tavernplayer.conversation

import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zvensmoluya.tavernplayer.characters.CharacterRepository
import io.github.zvensmoluya.tavernplayer.characters.CharacterSaveResult
import io.github.zvensmoluya.tavernplayer.characters.NativeAdaptationInstallResult
import io.github.zvensmoluya.tavernplayer.content.BuiltInPresets
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import io.github.zvensmoluya.tavernplayer.content.NativeSceneAsset
import io.github.zvensmoluya.tavernplayer.content.NativeSceneView
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PressureCardNativeAndroidTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun realCardInstallsRestoresAndRendersThroughNativeSurfaces() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testAssets = instrumentation.context.assets
        val root = File(context.cacheDir, "pressure-native-${UUID.randomUUID()}")
        try {
            val cardBytes = testAssets.open(CARD_ASSET).use { it.readBytes() }
            val adaptation = testAssets.open(ADAPTATION_ASSET).bufferedReader().use {
                STRICT_JSON.decodeFromString<NativeAdaptation>(it.readText())
            }
            val characters = CharacterRepository(root)
            val saved = runBlocking {
                characters.import(cardBytes, "复杂压测卡.png") as CharacterSaveResult.Saved
            }
            val installed = runBlocking {
                characters.installNativeAdaptation(saved.character.id, adaptation)
            }

            assertTrue(installed is NativeAdaptationInstallResult.Installed)
            val restoredCharacters = CharacterRepository(root)
            val restored = checkNotNull(restoredCharacters.get(saved.character.id))
            assertEquals(adaptation, restored.nativeAdaptation)
            val localImage = checkNotNull(restoredCharacters.assetFile(restored.id, restored.assets.single().id))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(localImage.absolutePath, bounds)
            assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)

            val conversation = runBlocking {
                ConversationRepository(root, PromptCompiler(), idFactory = { "pressure-android" }).create(
                    restored,
                    Persona(id = "android-audit", name = "旅人"),
                    BuiltInPresets.default,
                )
            }
            assertEquals(19, conversation.runtimeState.conversationState.values.size)
            assertEquals(4, conversation.turns.single().variants.size)

            val native = checkNotNull(restored.nativeAdaptation)
            val scene = NativeSceneView(
                id = "real-card-local-image",
                title = "本地角色卡图片",
                stateKey = "android-test-scene",
                assets = listOf(
                    NativeSceneAsset(
                        stateValue = "card",
                        assetId = restored.assets.single().id,
                        contentDescription = "压测卡本地图片",
                    ),
                ),
            )
            val state = conversation.runtimeState.conversationState.values +
                ("android-test-scene" to JsonPrimitive("card"))

            compose.setContent {
                MaterialTheme {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        NativeSceneCard(scene, state) { assetId ->
                            restoredCharacters.assetFile(restored.id, assetId)?.absolutePath
                        }
                        NativeStatusCard(checkNotNull(native.status), state)
                        NativeFormCard(native.forms.single(), enabled = true, onSubmit = {})
                    }
                }
            }

            compose.onNodeWithTag("native-scene-real-card-local-image").assertExists()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("native-scene-image-real-card-local-image")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("native-status").assertExists()
            compose.onNodeWithTag("native-state-protagonist-mana").assertExists()
            compose.onNodeWithTag("native-form-custom-opening-contract").assertExists()
            compose.onNodeWithTag("native-form-field-opening-notes").assertExists()
            assertNotNull(BitmapFactory.decodeFile(localImage.absolutePath))
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val CARD_ASSET = "pressure-card.png"
        const val ADAPTATION_ASSET = "native-adaptation/pressure-card-manual.json"
        val STRICT_JSON = Json { ignoreUnknownKeys = false }
    }
}
