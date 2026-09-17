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
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = CharacterLibraryPreferences(PreferenceDataStoreFactory.create(scope = firstScope) { file })
        try {
            assertEquals(CharacterLibraryLayout.GRID, first.layout.first())
            first.setLayout(CharacterLibraryLayout.LIST)
        } finally { firstScope.coroutineContext.job.cancelAndJoin() }
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = CharacterLibraryPreferences(PreferenceDataStoreFactory.create(scope = secondScope) { file })
            assertEquals(CharacterLibraryLayout.LIST, reopened.layout.first())
        } finally { secondScope.coroutineContext.job.cancelAndJoin() }
    }
}
