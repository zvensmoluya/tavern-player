package io.github.zvensmoluya.tavernplayer.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zvensmoluya.tavernplayer.characters.AdaptationInstallResult
import io.github.zvensmoluya.tavernplayer.characters.CharacterRepository
import io.github.zvensmoluya.tavernplayer.characters.CharacterSaveResult
import kotlinx.coroutines.runBlocking
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
        assertTrue(installed is AdaptationInstallResult.Installed)
        assertEquals(saved.character.sourceSha256, repository.get(saved.character.id)?.adaptation?.sourceSha256)
    }

    private companion object {
        const val ARG_TRANSFER_URL = "tavernShelfUrl"
    }
}
