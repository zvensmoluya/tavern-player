package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition

/**
 * 一次世界书意图提交。三类意图共同表达书级三态：
 * 停用（`SetBookEnabled(false)`）／自动（默认）／必定生效（`SetBookForceEnabled(true)`），
 * 以及条目级两态。
 */
sealed interface WorldBookActivationIntent {
    val bookId: String

    data class SetBookEnabled(
        override val bookId: String,
        val enabled: Boolean,
    ) : WorldBookActivationIntent

    /** 「必定生效」：该书已启用条目按常开处理并跳过概率。开启它会同时解除书级停用。 */
    data class SetBookForceEnabled(
        override val bookId: String,
        val forced: Boolean,
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
        val state: ConversationWorldBookState,
    ) : WorldBookActivationMutationResult

    data class Rejected(
        val code: WorldBookActivationRejectionCode,
        val intentIndex: Int,
    ) : WorldBookActivationMutationResult
}

/**
 * Applies one domain-owned World Book intent transaction.
 *
 * This deliberately is not a generic operation dispatcher. The lifecycle that owns a future
 * setup or behavior contract must call this controller explicitly with its already typed intents.
 *
 * 提交落在会话级的 [ConversationWorldBookState] 上：玩家与作者程序对启用状态的意图不随消息候选回退。
 */
class WorldBookActivationController {
    fun apply(
        worldBooks: List<WorldBookDefinition>,
        state: ConversationWorldBookState,
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

        if (intents.isEmpty()) return WorldBookActivationMutationResult.Applied(state)

        val books = state.activation.books.toMutableMap()
        val entries = state.activation.entries
            .mapValues { (_, values) -> values.toMutableMap() }
            .toMutableMap()
        val forced = state.forcedBooks.toMutableSet()
        intents.forEach { intent ->
            when (intent) {
                is WorldBookActivationIntent.SetBookEnabled -> {
                    books[intent.bookId] = intent.enabled
                    // 书级停用与「必定生效」是同一个三态的两端，不能同时成立。
                    if (!intent.enabled) forced -= intent.bookId
                }
                is WorldBookActivationIntent.SetBookForceEnabled -> {
                    if (intent.forced) {
                        forced += intent.bookId
                        books[intent.bookId] = true
                    } else {
                        forced -= intent.bookId
                    }
                }
                is WorldBookActivationIntent.SetEntryEnabled -> {
                    entries.getOrPut(intent.bookId) { mutableMapOf() }[intent.entryId] = intent.enabled
                }
            }
        }
        return WorldBookActivationMutationResult.Applied(
            state.copy(
                activation = WorldBookActivationOverrides(
                    books = books.toMap(),
                    entries = entries.mapValues { (_, values) -> values.toMap() },
                ),
                forcedBooks = forced.toSet(),
            ),
        )
    }
}
