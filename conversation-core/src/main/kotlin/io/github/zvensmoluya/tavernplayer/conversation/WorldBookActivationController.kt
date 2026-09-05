package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition

sealed interface WorldBookActivationIntent {
    val bookId: String

    data class SetBookEnabled(
        override val bookId: String,
        val enabled: Boolean,
    ) : WorldBookActivationIntent

    data class SetEntryEnabled(
        override val bookId: String,
        val entryId: String,
        val enabled: Boolean,
    ) : WorldBookActivationIntent
}

enum class WorldBookActivationRejectionCode {
    UNKNOWN_WORLD_BOOK,
    UNKNOWN_WORLD_BOOK_ENTRY,
}

sealed interface WorldBookActivationMutationResult {
    data class Applied(
        val runtimeState: ConversationRuntimeState,
    ) : WorldBookActivationMutationResult

    data class Rejected(
        val code: WorldBookActivationRejectionCode,
        val intentIndex: Int,
    ) : WorldBookActivationMutationResult
}

/**
 * Applies one domain-owned World Book activation transaction.
 *
 * This deliberately is not a generic operation dispatcher. The lifecycle that owns a future
 * setup or behavior contract must call this controller explicitly with its already typed intents.
 */
class WorldBookActivationController {
    fun apply(
        worldBooks: List<WorldBookDefinition>,
        runtimeState: ConversationRuntimeState,
        intents: List<WorldBookActivationIntent>,
    ): WorldBookActivationMutationResult {
        val booksById = worldBooks.associateBy(WorldBookDefinition::id)
        intents.forEachIndexed { index, intent ->
            val book = booksById[intent.bookId]
                ?: return WorldBookActivationMutationResult.Rejected(
                    WorldBookActivationRejectionCode.UNKNOWN_WORLD_BOOK,
                    index,
                )
            if (intent is WorldBookActivationIntent.SetEntryEnabled && book.entries.none { it.id == intent.entryId }) {
                return WorldBookActivationMutationResult.Rejected(
                    WorldBookActivationRejectionCode.UNKNOWN_WORLD_BOOK_ENTRY,
                    index,
                )
            }
        }

        if (intents.isEmpty()) return WorldBookActivationMutationResult.Applied(runtimeState)

        val books = runtimeState.worldBookActivationOverrides.books.toMutableMap()
        val entries = runtimeState.worldBookActivationOverrides.entries
            .mapValues { (_, values) -> values.toMutableMap() }
            .toMutableMap()
        intents.forEach { intent ->
            when (intent) {
                is WorldBookActivationIntent.SetBookEnabled -> books[intent.bookId] = intent.enabled
                is WorldBookActivationIntent.SetEntryEnabled -> {
                    entries.getOrPut(intent.bookId) { mutableMapOf() }[intent.entryId] = intent.enabled
                }
            }
        }
        return WorldBookActivationMutationResult.Applied(
            runtimeState.copy(
                worldBookActivationOverrides = WorldBookActivationOverrides(
                    books = books.toMap(),
                    entries = entries.mapValues { (_, values) -> values.toMap() },
                ),
            ),
        )
    }
}
