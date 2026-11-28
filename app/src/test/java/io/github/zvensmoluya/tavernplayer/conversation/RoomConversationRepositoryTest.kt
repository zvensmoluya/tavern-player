package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.conversation.storage.StreamChunk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.zvensmoluya.tavernplayer.conversation.storage.ConversationDatabase
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomConversationRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val compiler = PromptCompiler()
    private suspend fun create(repository: ConversationRepository) = repository.create(
        DemoConversationContent.character, DemoConversationContent.persona, DemoConversationContent.preset)

    private fun playerReply(id: String, text: String): ConversationTurn {
        val message = ConversationMessage(id, MessageRole.USER, text, "Reader")
        return ConversationTurn(id, MessageRole.USER, listOf(MessageVariant(id, message)))
    }

    @Test fun `titles use sent player excerpts and survive subsequent saves and reopen`() = runBlocking {
        val root = folder.newFolder()
        val id = ConversationRepository(root, compiler).use { repo ->
            val first = create(repo)
            val second = create(repo)
            assertEquals(1, repo.forCharacter(first.character.assetId).single { it.id == first.id }.number)
            assertEquals(2, repo.forCharacter(first.character.assetId).single { it.id == second.id }.number)
            val greeting = repo.save(first.copy(draft = "不要取草稿", turns = first.turns + playerReply("hi", "你好，继续！")), first)
            assertNull(repo.store.dao.summary(first.id)!!.title)
            val sent = repo.save(greeting.copy(turns = greeting.turns + playerReply("act", "我把信收进口袋，决定暂时不告诉她。")), greeting)
            assertEquals("决定暂时不告诉她", repo.store.dao.summary(first.id)!!.title)
            val later = (1..8).map { playerReply("next$it", "第${it}天，我去钟楼。") } + playerReply("last", "别开门！")
            val active = repo.save(sent.copy(turns = sent.turns + later), sent)
            assertEquals("决定暂时不告诉她", repo.store.dao.summary(first.id)!!.title)
            val options = repo.titleSuggestions(first.id)
            assertTrue("别开门！" in options)
            assertTrue("我把信收进口袋" in options)
            assertFalse(options.any { "你好" in it })
            assertEquals(options.distinct(), options)
            val before = repo.store.dao.summary(first.id)!!
            repo.rename(first.id, "  灯塔旧事  ")
            assertEquals(before.copy(title = "灯塔旧事"), repo.store.dao.summary(first.id))
            repo.save(active.copy(turns = active.turns.dropLast(1)), active)
            repo.rename(second.id, "手填的开场")
            repo.save(second.copy(turns = second.turns + playerReply("second-act", "我认罪")), second)
            assertEquals("手填的开场", repo.store.dao.summary(second.id)!!.title)
            first.id
        }
        ConversationRepository(root, compiler).use { repo ->
            val summary = repo.conversations.first().single { it.id == id }
            assertEquals("灯塔旧事", summary.title)
            assertEquals(1, summary.number)
        }
    }

    @Test fun `version one histories gain numbers and titles without rewriting messages`() = runBlocking {
        val root = folder.newFolder()
        val (old, empty) = ConversationRepository(root, compiler).use { repo ->
            val first = create(repo)
            val saved = repo.save(first.copy(turns = first.turns + playerReply("continue", "继续") +
                playerReply("choice", "别开门")), first)
            saved to create(repo)
        }
        // Reproduce the v1 file by removing the only two columns introduced in v2.
        android.database.sqlite.SQLiteDatabase.openDatabase(File(root, "tavern/conversation.db").path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE conversation_summary DROP COLUMN title")
            db.execSQL("ALTER TABLE conversation_summary DROP COLUMN number")
            db.version = 1
        }
        ConversationRepository(root, compiler, context = ApplicationProvider.getApplicationContext()).use { repo ->
            val summaries = repo.conversations.first()
            assertEquals(setOf(1, 2), summaries.map { it.number }.toSet())
            assertEquals("别开门", summaries.single { it.id == old.id }.title)
            assertNull(summaries.single { it.id == empty.id }.title)
            assertEquals(old, repo.get(old.id))
            assertEquals(empty, repo.get(empty.id))
            val third = create(repo)
            assertEquals(3, repo.forCharacter(third.character.assetId).single { it.id == third.id }.number)
        }
    }

    @Test fun `only web conversations are imported listed and opened`() = runBlocking {
        val root = folder.newFolder()
        val legacyJson = File(root, "tavern/conversations/native.json")
        legacyJson.parentFile!!.mkdirs()
        // Old native files can lack a mode and contain an obsolete record shape.
        legacyJson.writeText("""{"id":"legacy-json","character":"obsolete"}""")
        ConversationRepository(root, compiler).use { repository ->
            val web = create(repository)
            val old = web.copy(id = "legacy-room", executionMode = ConversationExecutionMode.LEGACY_NATIVE, turns = emptyList())
            repository.store.save(old, null)
            assertEquals(listOf(web.id), repository.forCharacter(web.character.assetId).map { it.id })
            assertEquals(listOf(web.id), repository.conversations.first().map { it.id })
            assertFalse(repository.contains(old.id))
            assertNull(repository.open(old.id))
            assertNull(repository.get(old.id))
            assertNull(repository.store.dao.head("legacy-json"))
            assertNotNull(repository.store.dao.head(old.id))
            assertTrue(legacyJson.isFile)
            assertEquals(web, repository.open(web.id))
        }
    }

    @Test fun `opening for play skips plan parts and editing preserves or explicitly clears them`() = runBlocking {
        val root = folder.newFolder()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(root, "tavern/conversation.db").also { it.parentFile!!.mkdirs() }
        val detailReads = mutableListOf<List<Any?>>()
        val database = Room.databaseBuilder(context, ConversationDatabase::class.java, file.absolutePath)
            .allowMainThreadQueries().setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                    if (sqlQuery.startsWith("SELECT * FROM generation_part")) detailReads.add(bindArgs.toList())
                }
            }, java.util.concurrent.Executor { it.run() }).build()
        ConversationRepository(root, compiler, context = context, databaseOverride = database).use { repository ->
            val initial = create(repository)
            val turn = initial.turns.last()
            val plan = GenerationPlan(
                messages = listOf(PreparedMessage(MessageRole.SYSTEM, "prompt", PromptOrigin("test", emptyList()))),
                maxOutputTokens = 42, declaredContextTokens = null, assistantPrefill = "", presetId = "p", presetName = "Sample",
                diagnostics = listOf(CompilationDiagnostic(DiagnosticSeverity.WARNING, "TEST", "sample warning")),
                trace = listOf(CompilationTraceEntry("test", emptyList(), "included")),
            )
            val first = turn.selected.copy(generationPlan = plan)
            val last = first.copy(id = "tail-variant", message = first.message.copy(id = "tail-message"))
            repository.save(initial.copy(turns = listOf(turn.copy(variants = listOf(first), selectedVariantIndex = 0),
                turn.copy(id = "tail-turn", variants = listOf(last), selectedVariantIndex = 0))), initial)
            detailReads.clear()
            val opened = repository.openForPlay(initial.id, DemoConversationContent.preset, null)!!.record
            val header = opened.turns.last().selected.generationPlan!!
            assertEquals(plan.copy(messages = emptyList(), trace = emptyList(), diagnostics = emptyList(),
                deferredPartsVariantId = last.id), header)
            assertTrue(detailReads.isEmpty())
            assertEquals(plan, repository.readPlan(last.id))
            assertEquals(listOf(listOf(last.id)), detailReads)

            val edited = opened.turns.first().selected.let {
                it.copy(message = it.message.copy(content = "Edited"))
            }
            val tail = opened.turns.last()
            val renamed = tail.selected.copy(generationPlan = header.copy(presetName = "Renamed"))
            val changed = listOf(opened.turns.first().copy(variants = listOf(edited)), tail.copy(variants = listOf(renamed)))
            val saved = repository.save(opened.copy(turns = changed), opened)
            assertEquals(plan, repository.readPlan(first.id))
            val expectedLast = plan.copy(presetName = "Renamed")
            assertEquals(expectedLast, repository.readPlan(last.id))
            repository.save(saved.copy(turns = listOf(saved.turns.first().copy(
                variants = listOf(edited.copy(generationPlan = null))), saved.turns.last())), saved)
            assertNull(repository.readPlan(first.id))
            assertEquals(expectedLast, repository.get(saved.id)!!.turns.last().selected.generationPlan)
        }
    }

    @Test fun `legacy import checks complete records and retains original bytes`() = runBlocking {
        val original = ConversationRepository(folder.newFolder(), compiler).use { create(it) }
        val legacy = original.copy(draft = "draft", draftSeq = 7, turns = original.turns.map { turn ->
            turn.copy(variants = turn.variants.map { it.copy(message = it.message.copy(
                sourceText = "original source", content = "canonical", reasoning = listOf(ReasoningBlock("thought", "signature"))
            ), generationPlan = GenerationPlan(messages = emptyList(), maxOutputTokens = 42, declaredContextTokens = null, assistantPrefill = "", presetId = "p", presetName = "Sample", diagnostics = emptyList(), trace = emptyList())) })
        })
        val root = folder.newFolder()
        val file = File(root, "tavern/conversations/sample.json").also { it.parentFile!!.mkdirs() }
        val bytes = Json { encodeDefaults = true }.encodeToString(legacy).toByteArray()
        file.writeBytes(bytes)
        ConversationRepository(root, compiler).use { repository ->
            assertEquals(legacy, repository.get(legacy.id))
            assertArrayEquals(bytes, file.readBytes())
            repository.save(legacy.withDraft("new draft"))
        }
        ConversationRepository(root, compiler).use { repository ->
            assertEquals("new draft", repository.get(legacy.id)!!.draft)
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun `corrupt legacy file blocks activation and import resumes without duplication`() = runBlocking {
        val original = ConversationRepository(folder.newFolder(), compiler).use { create(it) }
        val root = folder.newFolder()
        File(root, "tavern/conversations/a.json").also { it.parentFile!!.mkdirs(); it.writeText(Json.encodeToString(original)) }
        val broken = File(root, "tavern/conversations/b.json").also { it.writeText("broken") }
        ConversationRepository(root, compiler).use { repository ->
            try { repository.get(original.id); fail("Corrupt import activated") } catch (_: IllegalStateException) { }
            assertNull(repository.store.dao.imported("__active__"))
            assertEquals(1, repository.store.dao.summaries().size)
        }
        // Repair the bad source; the already verified record is not imported twice.
        broken.writeText(Json.encodeToString(original.copy(id = "second", turns = emptyList())))
        ConversationRepository(root, compiler).use { repository ->
            assertNotNull(repository.get(original.id))
            assertEquals(2, repository.store.dao.summaries().size)
        }
    }

    @Test fun `mixed legacy versions preserve records and allow new conversations after import`() = runBlocking {
        val root = folder.newFolder()
        val originals = ConversationRepository(folder.newFolder(), compiler).use { source ->
            (1..3).map { version -> create(source).copy(schemaVersion = version, draft = "draft-$version") }
        }
        val files = originals.map { record ->
            File(root, "tavern/conversations/${record.id}.json").also {
                it.parentFile!!.mkdirs()
                // Omit default fields, matching older files that predate those fields.
                it.writeText(Json.encodeToString(record))
            }
        }
        val bytes = files.map { it.readBytes() }
        val created = ConversationRepository(root, compiler).use { repository ->
            originals.forEach { assertEquals(it, repository.get(it.id)) }
            assertNotNull(repository.store.dao.imported("__active__"))
            create(repository).also { assertEquals(4, repository.store.dao.summaries().size) }
        }
        ConversationRepository(root, compiler).use { repository ->
            originals.forEach { assertEquals(it, repository.get(it.id)) }
            assertEquals(created, repository.get(created.id))
            files.zip(bytes).forEach { (file, original) -> assertArrayEquals(original, file.readBytes()) }
        }
    }

    @Test fun `unknown legacy version still blocks activation and preserves source`() = runBlocking {
        val original = ConversationRepository(folder.newFolder(), compiler).use { create(it) }.copy(schemaVersion = 4)
        val root = folder.newFolder()
        val file = File(root, "tavern/conversations/future.json").also {
            it.parentFile!!.mkdirs(); it.writeText(Json.encodeToString(original))
        }
        val bytes = file.readBytes()
        ConversationRepository(root, compiler).use { repository ->
            try { create(repository); fail("Unknown version activated") } catch (_: IllegalStateException) { }
            assertNull(repository.store.dao.imported("__active__"))
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun `draft writes leave history head and summary unchanged and reject older drafts`() = runBlocking {
        ConversationRepository(folder.newFolder(), compiler).use { repository ->
            val original = create(repository)
            val head = repository.store.dao.head(original.id)
            val summary = repository.store.dao.summaries()
            val variants = repository.store.dao.variants(original.turns.first().id)
            repository.saveDraft(original.withDraft("one").withDraft("two"))
            repository.saveDraft(original.withDraft("stale"))
            assertEquals("two", repository.get(original.id)!!.draft)
            assertEquals(head, repository.store.dao.head(original.id))
            assertEquals(summary, repository.store.dao.summaries())
            assertEquals(variants, repository.store.dao.variants(original.turns.first().id))
        }
    }

    @Test fun `SQLite audit proves draft isolation and changed candidate writes with shared read values`() = runBlocking {
        ConversationRepository(folder.newFolder(), compiler).use { repository ->
            val initial = create(repository)
            val basis = initial.turns.first()
            val record = repository.save(initial.copy(turns = (0 until 40).map { index ->
                basis.copy(id = "turn-$index", variants = listOf(basis.selected.copy(id = "variant-$index",
                    message = basis.selected.message.copy(id = "message-$index"))), selectedVariantIndex = 0)
            }))
            val loaded = repository.get(record.id)!!
            assertSame(loaded.turns[0].selected.message.content, loaded.turns[1].selected.message.content)
            assertSame(loaded.turns[0].selected.runtimeStateBefore, loaded.turns[1].selected.runtimeStateBefore)
            val db = repository.store.database.openHelper.writableDatabase
            db.execSQL("CREATE TABLE write_audit (name TEXT NOT NULL)")
            listOf("conversation_head", "conversation_summary", "conversation_draft", "conversation_turn", "variant",
                "generation", "generation_part", "runtime_operation").forEach { table ->
                db.execSQL("CREATE TRIGGER audit_$table AFTER UPDATE ON $table BEGIN INSERT INTO write_audit VALUES ('$table'); END")
            }
            fun writes(): List<String> = db.query("SELECT name FROM write_audit").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            repository.saveDraft(record.withDraft("typing"))
            assertEquals(listOf("conversation_draft"), writes())
            db.execSQL("DELETE FROM write_audit")
            val last = record.turns.last()
            val changed = record.copy(turns = record.turns.dropLast(1) + last.copy(variants = listOf(last.selected.copy(
                message = last.selected.message.copy(content = "Changed tail", sourceText = "Changed tail")))))
            assertEquals("typing", repository.save(changed, record).draft)
            assertEquals(1, writes().count { it == "variant" })
            assertEquals(1, writes().count { it == "conversation_turn" })
            assertFalse(writes().any { it in setOf("generation", "generation_part", "runtime_operation", "conversation_draft") })
            assertEquals(record.turns.takeLast(7).map { it.id }, repository.readMessages(record.id, limit = 7).map { it.id })
        }
    }

    @Test fun `terminal transaction rolls back head variant and raw journal together`() = runBlocking {
        ConversationRepository(folder.newFolder(), compiler).use { repository ->
            val initial = create(repository)
            val turn = initial.turns.last()
            val streaming = repository.save(initial.copy(turns = initial.turns.dropLast(1) + turn.copy(
                variants = listOf(turn.selected.copy(status = PersistedMessageStatus.STREAMING)), selectedVariantIndex = 0)))
            val variant = streaming.turns.last().selected
            repository.startStream("g", streaming, variant.id, context("g"))
            repository.appendStream("g", listOf(StreamChunk(1, "text", "Partial")), 0, null)
            val terminal = streaming.copy(turns = streaming.turns.dropLast(1) + streaming.turns.last().copy(
                variants = listOf(variant.copy(status = PersistedMessageStatus.COMPLETE, message = variant.message.copy(content = "Partial")))))
            repository.store.beforeCommit = { throw IOException("disk full") }
            try { repository.save(terminal, streaming, finishStream = "g"); fail("Commit succeeded") } catch (_: IOException) { }
            assertEquals(streaming, repository.get(streaming.id))
            assertEquals(1, repository.store.dao.chunks("g").size)
            repository.store.beforeCommit = null
            val saved = repository.save(terminal, streaming, finishStream = "g")
            assertEquals(PersistedMessageStatus.COMPLETE, saved.turns.last().selected.status)
            assertNull(repository.store.dao.stream("g"))
            assertTrue(repository.store.dao.chunks("g").isEmpty())
        }
    }

    @Test fun `reopen reprojects durable raw events and preserves usage signatures without completing`() = runBlocking {
        val root = folder.newFolder()
        val id = ConversationRepository(root, compiler).use { repository ->
            val initial = create(repository)
            val streaming = repository.save(initial.copy(turns = initial.turns.map { turn -> turn.copy(
                variants = turn.variants.map { it.copy(status = PersistedMessageStatus.STREAMING) }) }))
            repository.startStream("g", streaming, streaming.turns.last().selected.id, context("g"))
            repository.appendStream("g", listOf(StreamChunk(1, "text", "Recovered text"),
                StreamChunk(2, "reasoning", "Private thought"), StreamChunk(3, "signature", "opaque"),
                StreamChunk(4, "finish", "\"stop\""), StreamChunk(5, "usage", "{\"input\":12,\"output\":3}")), 0, null)
            streaming.id
        }
        ConversationRepository(root, compiler).use { repository ->
            val recovered = repository.open(id)!!.turns.last().selected
            assertEquals(PersistedMessageStatus.INTERRUPTED, recovered.status)
            assertEquals("Recovered text", recovered.message.sourceText)
            assertEquals("opaque", recovered.message.reasoning.single().signature)
            assertEquals(12L, recovered.inputTokens)
            assertEquals(3L, recovered.outputTokens)
            assertNull(repository.store.dao.stream("g"))
        }
    }

    @Test fun `reopen recovers a stream with a multibyte preset larger than the cursor window`() = runBlocking {
        val root = folder.newFolder()
        val context = context("g").let { it.copy(preset = it.preset.copy(
            source = buildJsonObject { put("sample", "界".repeat(800_000)) }
        )) }
        val id = ConversationRepository(root, compiler).use { repository ->
            val initial = create(repository)
            val turn = initial.turns.last()
            val variant = turn.selected.copy(status = PersistedMessageStatus.STREAMING)
            val streaming = repository.save(initial.copy(turns = initial.turns.dropLast(1) +
                turn.copy(variants = listOf(variant), selectedVariantIndex = 0)))
            repository.startStream("g", streaming, variant.id, context)
            repository.appendStream("g", listOf(StreamChunk(1, "text", "尚未结束的回复")), 0, null)
            streaming.id
        }
        ConversationRepository(root, compiler).use { repository ->
            val stored = repository.store.streamData(id).single()
            assertEquals(context, Json.decodeFromString<StreamContext>(stored.context))
            val recovered = repository.open(id)!!
            assertEquals(PersistedMessageStatus.INTERRUPTED, recovered.turns.last().selected.status)
            assertEquals("尚未结束的回复", recovered.turns.last().selected.message.sourceText)
            assertNull(repository.store.dao.stream("g"))
            assertEquals(recovered, repository.open(id))
        }
    }

    @Test fun `stale writers foreign identities and noncontiguous raw events are rejected`() = runBlocking {
        ConversationRepository(folder.newFolder(), compiler).use { repository ->
            val original = create(repository)
            val next = repository.save(original.withDraft("new"))
            try { repository.save(original); fail("Stale write") } catch (_: IllegalStateException) { }
            val other = create(repository)
            try { repository.save(other.copy(turns = original.turns)); fail("Foreign identities") } catch (_: IllegalArgumentException) { }
            repository.startStream("g", next, next.turns.last().selected.id, context("g"))
            try { repository.appendStream("g", listOf(StreamChunk(2, "text", "gap")), 0, null); fail("Gap accepted") }
            catch (_: IllegalArgumentException) { }
            assertTrue(repository.store.dao.chunks("g").isEmpty())
            assertEquals(0L, repository.store.dao.stream("g")!!.persistedThrough)
        }
    }

    private fun context(id: String) = StreamContext(id, DemoConversationContent.preset.snapshot(), "test",
        "2026-09-15T00:00:00Z", "UTC")
}
