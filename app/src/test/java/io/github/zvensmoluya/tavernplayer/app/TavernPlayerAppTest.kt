package io.github.zvensmoluya.tavernplayer.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TavernPlayerAppTest {
    @Test
    fun `library remains available without a model connection`() {
        assertEquals(
            AppSurface.CHARACTER_LIBRARY,
            selectAppSurface(AppSurface.CHARACTER_LIBRARY, hasSelectedCharacter = false),
        )
    }

    @Test
    fun `detail and chat fall back when no character is selected`() {
        assertEquals(
            AppSurface.CHARACTER_LIBRARY,
            selectAppSurface(AppSurface.CHARACTER_DETAIL, hasSelectedCharacter = false),
        )
        assertEquals(
            AppSurface.CHARACTER_LIBRARY,
            selectAppSurface(AppSurface.CHAT, hasSelectedCharacter = false),
        )
    }

    @Test
    fun `selected character allows detail chat and model management`() {
        assertEquals(
            AppSurface.CHARACTER_DETAIL,
            selectAppSurface(AppSurface.CHARACTER_DETAIL, hasSelectedCharacter = true),
        )
        assertEquals(
            AppSurface.CHAT,
            selectAppSurface(AppSurface.CHAT, hasSelectedCharacter = true),
        )
        assertEquals(
            AppSurface.MODEL_CONFIGURATION,
            selectAppSurface(AppSurface.MODEL_CONFIGURATION, hasSelectedCharacter = true),
        )
        assertEquals(
            AppSurface.PRESET_CENTER,
            selectAppSurface(AppSurface.PRESET_CENTER, hasSelectedCharacter = true),
        )
        assertEquals(
            AppSurface.PERSONA,
            selectAppSurface(AppSurface.PERSONA, hasSelectedCharacter = false),
        )
    }
}
