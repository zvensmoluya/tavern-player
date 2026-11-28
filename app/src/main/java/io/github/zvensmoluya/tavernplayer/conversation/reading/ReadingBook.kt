package io.github.zvensmoluya.tavernplayer.conversation.reading

import android.content.Context
import com.dokar.quickjs.QuickJs
import io.github.zvensmoluya.tavernplayer.conversation.ChatMessageState
import io.github.zvensmoluya.tavernplayer.conversation.ChatMessageStatus
import io.github.zvensmoluya.tavernplayer.conversation.MessageRole
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class ReadingRun(val text: String, val bold: Boolean = false, val italic: Boolean = false)

internal data class ReadingEntry(val speaker: String, val player: Boolean, val paragraphs: List<List<ReadingRun>>) {
    val excerpt: String get() = paragraphs.first().joinToString("") { it.text }.take(100)
    fun plainText() = paragraphs.joinToString("\n\n") { runs -> runs.joinToString("") { it.text } }
}

internal data class ReadingBook(val title: String, val character: String, val entries: List<ReadingEntry>) {
    fun plainText() = entries.joinToString("\n\n") { it.speaker + "\n" + it.plainText() }
}

internal suspend fun prepareReadingBook(context: Context, title: String, character: String, messages: List<ChatMessageState>): ReadingBook {
    val bundle = withContext(Dispatchers.IO) { context.assets.open("reading.js").bufferedReader().use { it.readText() } }
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    try {
        return withContext(dispatcher) {
            val js = QuickJs.create(dispatcher)
            try {
                js.memoryLimit = 96L * 1024 * 1024
                js.maxStackSize = 2L * 1024 * 1024
                js.evaluationTimeoutMillis = 10_000
                js.evaluate<Any?>(bundle, filename = "reading.js")
                val entries = messages.mapNotNull { item ->
                    ensureActive()
                    if (item.hidden || item.message.role == MessageRole.SYSTEM || item.displayContent.isBlank()) return@mapNotNull null
                    val display = Json.encodeToString(item.displayContent)
                    val changes = Json.encodeToString(Json.encodeToString(item.displayChanges))
                    val incomplete = item.status != ChatMessageStatus.COMPLETE
                    val result = js.evaluate<String>("JSON.stringify(Reading.paragraphs($display, JSON.parse($changes), $incomplete))")
                    val paragraphs = Json.decodeFromString<List<List<ReadingRun>>>(result)
                    if (paragraphs.isEmpty()) null else ReadingEntry(item.message.authorName, item.message.role == MessageRole.USER, paragraphs)
                }
                ReadingBook(title, character, entries)
            } finally { js.close() }
        }
    } finally { dispatcher.close() }
}
