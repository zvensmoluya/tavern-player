package io.github.zvensmoluya.tavernplayer.conversation

import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import io.github.zvensmoluya.tavernplayer.conversation.storage.ConversationDatabase
import io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuConversationRuntime
import io.github.zvensmoluya.tavernplayer.content.BrowserProgram
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.UUID

/** Real file-backed SQLite, with main-thread checks disabled only in deterministic JVM tests. */
fun ConversationRepository(
    filesDir: File, compiler: PromptCompiler, idFactory: () -> String = { UUID.randomUUID().toString() },
    now: () -> Long = System::currentTimeMillis, ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    mvuRuntime: MvuConversationRuntime = MvuConversationRuntime(),
    prepareBrowser: suspend (BrowserProgram) -> BrowserProgram = { it },
): ConversationRepository {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val file = File(filesDir, "tavern/conversation.db").also { it.parentFile!!.mkdirs() }
    val database = Room.databaseBuilder(context, ConversationDatabase::class.java, file.absolutePath)
        .allowMainThreadQueries().build()
    return ConversationRepository(filesDir, compiler, idFactory, now, ioDispatcher, mvuRuntime, prepareBrowser, context, database)
}

/** Synchronous instrumentation assertions run their repository reads on its IO dispatcher. */
fun ConversationRepository.blockingGet(id: String): ConversationRecord? = kotlinx.coroutines.runBlocking { get(id) }
