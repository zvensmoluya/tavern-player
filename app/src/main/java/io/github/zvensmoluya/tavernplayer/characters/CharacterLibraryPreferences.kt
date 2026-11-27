package io.github.zvensmoluya.tavernplayer.characters

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.map

enum class CharacterLibraryLayout { GRID, LIST }

class CharacterLibraryPreferences(private val store: DataStore<Preferences>) {
    val layout = store.data.map { if (it[LAYOUT] == "list") CharacterLibraryLayout.LIST else CharacterLibraryLayout.GRID }

    suspend fun setLayout(layout: CharacterLibraryLayout) {
        store.edit { it[LAYOUT] = if (layout == CharacterLibraryLayout.LIST) "list" else "grid" }
    }

    val showChatAvatars = store.data.map { it[SHOW_CHAT_AVATARS] ?: false }

    suspend fun setShowChatAvatars(show: Boolean) {
        store.edit { it[SHOW_CHAT_AVATARS] = show }
    }

    private companion object {
        val LAYOUT = stringPreferencesKey("character_library_layout")
        val SHOW_CHAT_AVATARS = booleanPreferencesKey("show_chat_avatars")
    }
}
