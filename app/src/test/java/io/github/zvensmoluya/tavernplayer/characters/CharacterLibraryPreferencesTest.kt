package io.github.zvensmoluya.tavernplayer.characters

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CharacterLibraryPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `layout defaults to grid and survives reopening its store`() = runBlocking {
        val file = File(temporary.newFolder(), "ui.preferences_pb")
        withStore(file) {
            assertEquals(CharacterLibraryLayout.GRID, layout.first())
            setLayout(CharacterLibraryLayout.LIST)
        }
        withStore(file) { assertEquals(CharacterLibraryLayout.LIST, layout.first()) }
    }

    @Test fun `chat avatars default to hidden and survive reopening the store`() = runBlocking {
        val file = File(temporary.newFolder(), "ui.preferences_pb")
        withStore(file) {
            assertEquals(false, showChatAvatars.first())
            setShowChatAvatars(true)
        }
        withStore(file) { assertEquals(true, showChatAvatars.first()) }
    }

    private suspend fun withStore(file: File, check: suspend CharacterLibraryPreferences.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            CharacterLibraryPreferences(PreferenceDataStoreFactory.create(scope = scope) { file }).check()
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
}
