package io.github.zvensmoluya.tavernplayer.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zvensmoluya.tavernplayer.characters.AdaptationInstallResult
import io.github.zvensmoluya.tavernplayer.characters.CharacterRepository
import io.github.zvensmoluya.tavernplayer.characters.CharacterSaveResult
import io.github.zvensmoluya.tavernplayer.content.AdaptationStateType
import io.github.zvensmoluya.tavernplayer.content.ProgramViewExtractor
import io.github.zvensmoluya.tavernplayer.conversation.AdaptationRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShelfAdaptationAndroidSmokeTest {
    @Test
    fun importsSourceAndInstallsOptionalAdaptationFromRealShelf() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val transferUrl = InstrumentationRegistry.getArguments().getString(ARG_TRANSFER_URL)
        assumeTrue("Pass -e $ARG_TRANSFER_URL <url> to run the real Shelf smoke test", !transferUrl.isNullOrBlank())

        val transfer = ShelfTransferClient().receive(requireNotNull(transferUrl))
        assertEquals("character", transfer.manifest.kind)
        assertNotNull(transfer.adaptationBytes)

        val repository = CharacterRepository(instrumentation.targetContext.filesDir)
        val saved = repository.import(transfer.sourceBytes, transfer.manifest.filename) as CharacterSaveResult.Saved
        val installed = repository.installAdaptation(requireNotNull(transfer.adaptationBytes))

        assertEquals(transfer.manifest.sha256.lowercase(), saved.character.sourceSha256)
        val localHints = ProgramViewExtractor().extract(saved.character).stateProtocolHints
        assertTrue("adaptation install result: $installed; local hints: $localHints", installed is AdaptationInstallResult.Installed)
        val artifact = requireNotNull(repository.get(saved.character.id)?.adaptation)
        assertEquals(saved.character.sourceSha256, artifact.sourceSha256)

        val definitions = artifact.state.associateBy { it.key }
        val mappings = artifact.messageStateRules.single().mappings
        val numberMapping = mappings.first { definitions[it.target]?.type == AdaptationStateType.NUMBER }
        val stringMapping = mappings.first { definitions[it.target]?.type == AdaptationStateType.STRING }
        val sourceText = """
            <UpdateVariable>
            _.set('${numberMapping.sourcePath}', 0, 321);
            _.set('${stringMapping.sourcePath}', '', '设备验证地点');
            </UpdateVariable>
        """.trimIndent()
        val runtime = AdaptationRuntime()
        val ingested = runtime.ingestAssistantMessage(artifact, sourceText, runtime.initialState(artifact))

        assertEquals(2, ingested.appliedUpdates)
        assertEquals(
            321.0,
            requireNotNull((ingested.runtimeState.adaptationState[numberMapping.target] as JsonPrimitive).doubleOrNull),
            0.0,
        )
        assertEquals(
            "设备验证地点",
            (ingested.runtimeState.adaptationState[stringMapping.target] as JsonPrimitive).content,
        )
    }

    private companion object {
        const val ARG_TRANSFER_URL = "tavernShelfUrl"
    }
}
