package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.conversation.storage.StreamChunk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomConversationRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val compiler = PromptCompiler()
    private suspend fun create(repository: ConversationRepository) = repository.create(
        DemoConversationContent.character, DemoConversationContent.persona, DemoConversationContent.preset)

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
