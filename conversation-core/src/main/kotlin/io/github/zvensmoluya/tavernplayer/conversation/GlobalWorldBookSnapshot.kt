package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.*

data class GlobalWorldBookSnapshot(
    val books: List<WorldBookDefinition> = emptyList(),
    val overrides: Map<String, Map<String, WorldBookEntryOverride>> = emptyMap(),
) {
    fun apply(input: NormalGenerationInput): NormalGenerationInput {
        if (books.isEmpty()) return input
        require((input.character.worldBooks + books).map { it.id }.distinct().size == input.character.worldBooks.size + books.size)
        val templates = books.flatMap { book -> book.entries.filter { "<%" in it.content }.map {
            NativeWorldBookReference(book.id, it.id, BrowserProgramReader.sha256(it.content))
        } }
        return input.copy(
            character = input.character.copy(worldBooks = input.character.worldBooks + books,
                browserProgram = input.character.browserProgram?.let { it.copy(ejsTemplates = it.ejsTemplates + templates) }),
            worldBookState = input.worldBookState.copy(playerOverrides = input.worldBookState.playerOverrides + overrides),
        )
    }
}
