package io.github.zvensmoluya.tavernplayer.characters

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.map

enum class CharacterLibraryLayout { GRID, LIST }

class CharacterLibraryPreferences(private val store: DataStore<Preferences>) {
    val layout = store.data.map { if (it[LAYOUT] == "list") CharacterLibraryLayout.LIST else CharacterLibraryLayout.GRID }

    suspend fun setLayout(layout: CharacterLibraryLayout) {
        store.edit { it[LAYOUT] = if (layout == CharacterLibraryLayout.LIST) "list" else "grid" }
    }

    private companion object { val LAYOUT = stringPreferencesKey("character_library_layout") }
}
