package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookEntryDefinition

/** 只修改本对话的玩家覆盖；作者程序和角色原文继续保留自己的归属。 */
object ConversationWorldBookController {
    fun setMode(record: ConversationRecord, bookId: String, entryId: String, mode: WorldBookEntryMode?): ConversationRecord =
        update(record, bookId, entryId) { current, _ -> current.copy(mode = mode) }

    fun setContent(record: ConversationRecord, bookId: String, entryId: String, content: String): ConversationRecord {
        require(content.length <= 1_000_000) { "这段内容过长，无法保存" }
        return update(record, bookId, entryId) { current, entry ->
            current.copy(content = content.takeUnless { it == entry.content })
        }
    }

    private fun update(
        record: ConversationRecord,
        bookId: String,
        entryId: String,
        change: (WorldBookEntryOverride, WorldBookEntryDefinition) -> WorldBookEntryOverride,
    ): ConversationRecord {
        val entry = record.character.worldBooks.find { it.id == bookId }?.entries?.find { it.id == entryId }
            ?: error("这项世界书内容已不存在")
        val books = record.worldBookState.playerOverrides.toMutableMap()
        val entries = books[bookId].orEmpty().toMutableMap()
        val next = change(entries[entryId] ?: WorldBookEntryOverride(), entry)
        if (next == WorldBookEntryOverride()) entries.remove(entryId) else entries[entryId] = next
        if (entries.isEmpty()) books.remove(bookId) else books[bookId] = entries
        return record.copy(worldBookState = record.worldBookState.copy(playerOverrides = books))
    }
}

fun ConversationWorldBookState.entryMode(bookId: String, entry: WorldBookEntryDefinition): WorldBookEntryMode =
    playerOverrides[bookId]?.get(entry.id)?.mode ?: if (activation.isEntryEnabled(bookId, entry.id, entry.enabled)) {
        WorldBookEntryMode.AUTO
    } else WorldBookEntryMode.DISABLED

fun ConversationWorldBookState.entryContent(bookId: String, entry: WorldBookEntryDefinition): String =
    playerOverrides[bookId]?.get(entry.id)?.content ?: entry.content

fun ConversationWorldBookState.projectContent(books: List<WorldBookDefinition>): List<WorldBookDefinition> =
    books.map { book -> book.copy(entries = book.entries.map { entry -> entry.copy(content = entryContent(book.id, entry)) }) }

/** 手动启用可以取用作者停用书中的单项，其余项继续保留作者的停用状态。 */
fun ConversationWorldBookState.effectiveActivation(books: List<WorldBookDefinition>): WorldBookActivationOverrides {
    val bookOverrides = activation.books.toMutableMap()
    val entryOverrides = activation.entries.toMutableMap()
    books.forEach { book ->
        if (playerOverrides[book.id].orEmpty().values.none { it.mode != null }) return@forEach
        val entries = book.entries.associate { entry ->
            entry.id to (entryMode(book.id, entry) != WorldBookEntryMode.DISABLED)
        }
        bookOverrides[book.id] = entries.values.any { it }
        entryOverrides[book.id] = entries
    }
    return WorldBookActivationOverrides(bookOverrides, entryOverrides)
}

fun ConversationWorldBookState.forcedEntries(): Map<String, Set<String>> = playerOverrides.mapValues { (_, entries) ->
    entries.filterValues { it.mode == WorldBookEntryMode.FORCED }.keys
}.filterValues { it.isNotEmpty() }
