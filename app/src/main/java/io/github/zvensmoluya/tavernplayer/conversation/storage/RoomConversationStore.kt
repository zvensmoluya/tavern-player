package io.github.zvensmoluya.tavernplayer.conversation.storage

import android.content.Context
import androidx.room.Room
import io.github.zvensmoluya.tavernplayer.conversation.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.Callable

/** Synchronous database operations are called only by the repository's IO dispatcher. */
class RoomConversationStore(context: Context, filesDir: File, databaseOverride: ConversationDatabase? = null) : AutoCloseable {
    val file = File(filesDir, "tavern/conversation.db").also { it.parentFile!!.mkdirs() }
    val database = databaseOverride ?: Room.databaseBuilder(context.applicationContext, ConversationDatabase::class.java, file.absolutePath)
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
    val dao = database.conversations()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    /** Fault injection happens inside the real SQLite transaction, before its commit. */
    internal var beforeCommit: (() -> Unit)? = null

    fun <T> transaction(block: () -> T): T = database.runInTransaction(Callable {
        val result = block(); beforeCommit?.invoke(); result
    })

    private inner class Codec {
        val encoded = IdentityHashMap<Any, String>()
        fun text(value: String): String {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val id = "v1:" + sha256(bytes)
            dao.put(BlobRow(id, bytes))
            return id
        }
        inline fun <reified T : Any> value(value: T): String = encoded[value]
            ?: text(json.encodeToString(value)).also { encoded[value] = it }
    }
    private fun text(id: String): String = requireNotNull(dao.blob(id)) { "对话内容引用缺失" }.bytes.toString(Charsets.UTF_8)
    private inline fun <reified T> value(id: String): T = json.decodeFromString(text(id))
    private fun obj(value: String): JsonObject = json.parseToJsonElement(value).jsonObject

    private inner class Reader {
        private val texts = HashMap<String, String>()
        private val runtimes = HashMap<String, ConversationRuntimeState>()
        fun text(id: String): String = texts.getOrPut(id) { this@RoomConversationStore.text(id) }
        inline fun <reified T> value(id: String): T = json.decodeFromString(text(id))
        fun runtime(id: String): ConversationRuntimeState = runtimes.getOrPut(id) { value(id) }
    }

    fun read(id: String, diagnostics: Boolean = true): ConversationRecord? = database.runInTransaction(Callable {
        val head = dao.head(id) ?: return@Callable null
        val draft = requireNotNull(dao.draft(id)) { "对话草稿记录缺失" }
        val metadata = obj(head.metadata)
        val reader = Reader()
        ConversationRecord(schemaVersion = metadata.getValue("schemaVersion").jsonPrimitive.int, id = head.id,
            character = reader.value(head.character), persona = reader.value(head.persona),
            turns = dao.turns(id).map { readTurn(it, diagnostics, reader) }, runtimeState = reader.runtime(head.runtime),
            createdAtEpochMillis = metadata.getValue("createdAtEpochMillis").jsonPrimitive.long,
            updatedAtEpochMillis = metadata.getValue("updatedAtEpochMillis").jsonPrimitive.long,
            draft = draft.text, draftSeq = draft.seq, commitRevision = head.revision,
            choiceDraft = draft.choice?.let { json.decodeFromString(it) },
            nativeDraftOrigin = draft.nativeOrigin?.let { json.decodeFromString(it) },
            worldBookState = reader.value(head.worldBook),
            executionMode = ConversationExecutionMode.valueOf(metadata.getValue("executionMode").jsonPrimitive.content))
    })

    fun page(id: String, before: Int = Int.MAX_VALUE, limit: Int = 50): List<ConversationTurn> = database.runInTransaction(Callable {
        require(limit in 1..200)
        val reader = Reader()
        dao.page(id, before, limit).asReversed().map { readTurn(it, false, reader) }
    })

    private fun readTurn(row: TurnRow, diagnostics: Boolean, reader: Reader): ConversationTurn {
        val variants = dao.variants(row.id).map { readVariant(it, diagnostics, reader) }
        val selected = variants.indexOfFirst { it.id == row.selectedVariantId }
        check(selected >= 0) { "消息的选中候选引用无效" }
        return ConversationTurn(row.id, MessageRole.valueOf(row.role), variants, selected)
    }

    private fun readVariant(row: VariantRow, diagnostics: Boolean, reader: Reader): MessageVariant {
        val message = JsonObject(obj(row.message) + mapOf("content" to JsonPrimitive(reader.text(row.canonical)),
            "sourceText" to JsonPrimitive(reader.text(row.source))))
        return json.decodeFromJsonElement<MessageVariant>(JsonObject(obj(row.metadata) + ("message" to message))).let { variant ->
            variant.copy(message = variant.message.copy(reasoning = reader.value(row.reasoning),
                stateConfirmation = row.confirmation?.let(reader::text)),
                runtimeStateBefore = row.before?.let(reader::runtime),
                projectionRuntimeStateBefore = row.projectionBefore?.let(reader::runtime),
                runtimeStateAfter = row.after?.let(reader::runtime),
                browserHead = row.browserHead?.let { reader.value(it) },
                nativeOperations = dao.operations(row.id).map { reader.value(it.content) },
                generationPlan = if (diagnostics) readPlan(row.id, reader) else null)
        }
    }

    fun readPlan(id: String): GenerationPlan? = database.runInTransaction(Callable { readPlan(id, Reader()) })
    private fun readPlan(id: String, reader: Reader): GenerationPlan? {
        val row = dao.plan(id) ?: return null
        val parts = dao.planParts(id)
        return json.decodeFromJsonElement<GenerationPlan>(JsonObject(obj(row.metadata) + mapOf(
            "messages" to JsonArray(emptyList()), "trace" to JsonArray(emptyList()), "diagnostics" to JsonArray(emptyList())
        ))).copy(runtimeState = reader.runtime(row.runtime),
            messages = parts.filter { it.kind == "message" }.map { json.decodeFromJsonElement<PreparedMessage>(
                JsonObject(obj(it.metadata) + ("content" to JsonPrimitive(reader.text(it.content))))) },
            trace = parts.filter { it.kind == "trace" }.map { reader.value(it.content) },
            diagnostics = parts.filter { it.kind == "diagnostic" }.map { reader.value(it.content) })
    }

    fun save(record: ConversationRecord, previous: ConversationRecord?, activity: Boolean = true,
        finishStream: String? = null): ConversationRecord = transaction {
        val oldHead = dao.head(record.id)
        check(oldHead?.revision == previous?.commitRevision) { "对话已被其他写入修改，请重新打开" }
        require(record.turns.map { it.id }.distinct().size == record.turns.size) { "Duplicate turn identity" }
        val variants = record.turns.flatMap { it.variants }
        require(variants.map { it.id }.distinct().size == variants.size) { "Duplicate variant identity" }
        val revision = (oldHead?.revision ?: -1) + 1
        val existingDraft = dao.draft(record.id)
        val saved = if (existingDraft != null && existingDraft.seq > record.draftSeq) record.copy(
            commitRevision = revision, draft = existingDraft.text, draftSeq = existingDraft.seq,
            choiceDraft = existingDraft.choice?.let { json.decodeFromString(it) },
            nativeDraftOrigin = existingDraft.nativeOrigin?.let { json.decodeFromString(it) }) else record.copy(commitRevision = revision)
        val codec = Codec()
        val metadata = buildJsonObject {
            put("schemaVersion", saved.schemaVersion); put("id", saved.id)
            put("createdAtEpochMillis", saved.createdAtEpochMillis); put("updatedAtEpochMillis", saved.updatedAtEpochMillis)
            put("executionMode", saved.executionMode.name)
        }.toString()
        fun <T> same(a: T, b: T) = a === b || a == b
        dao.put(HeadRow(saved.id, revision, metadata,
            if (oldHead != null && same(saved.character, previous?.character)) oldHead.character else codec.value(saved.character),
            if (oldHead != null && same(saved.persona, previous?.persona)) oldHead.persona else codec.value(saved.persona),
            if (oldHead != null && same(saved.runtimeState, previous?.runtimeState)) oldHead.runtime else codec.value(saved.runtimeState),
            if (oldHead != null && same(saved.worldBookState, previous?.worldBookState)) oldHead.worldBook else codec.value(saved.worldBookState)))
        if (existingDraft == null || saved.draftSeq >= existingDraft.seq) putDraft(saved)
        if (saved.turns !== previous?.turns) {
            val before = previous?.turns?.associateBy { it.id }.orEmpty()
            val present = saved.turns.map { it.id }.toSet()
            before.keys.filterNot(present::contains).forEach(dao::deleteTurn)
            saved.turns.forEachIndexed { index, turn ->
                val old = before[turn.id]
                if (turn !== old || previous?.turns?.getOrNull(index)?.id != turn.id) {
                    require(turn.variants.isNotEmpty() && turn.selectedVariantIndex in turn.variants.indices)
                    if (old == null) require(dao.turn(turn.id)?.conversationId?.let { it == saved.id } != false) { "Turn belongs to another conversation" }
                    dao.put(TurnRow(turn.id, saved.id, index, turn.role.name, turn.selected.id))
                    val oldVariants = old?.variants?.associateBy { it.id }.orEmpty()
                    val ids = turn.variants.map { it.id }.toSet()
                    oldVariants.keys.filterNot(ids::contains).forEach(dao::deleteVariant)
                    turn.variants.forEachIndexed { position, variant ->
                        val prior = oldVariants[variant.id]
                        if (prior == null) require(dao.variant(variant.id)?.turnId?.let { it == turn.id } != false) { "Variant belongs to another turn" }
                        if (variant !== prior || old?.variants?.getOrNull(position)?.id != variant.id) putVariant(codec, turn.id, position, variant, prior)
                    }
                }
            }
        }
        if (activity || previous == null || saved.turns !== previous.turns) {
            val preview = saved.turns.asReversed().firstNotNullOfOrNull { it.selected.message.content.trim().takeIf(String::isNotBlank)?.take(160) } ?: "空白对话"
            dao.put(ConversationSummary(saved.id, saved.character.assetId, saved.createdAtEpochMillis, saved.updatedAtEpochMillis,
                saved.turns.size, preview, saved.executionMode.name))
        }
        finishStream?.let { id ->
            val stream = requireNotNull(dao.stream(id)) { "生成进度不存在" }
            require(stream.conversationId == saved.id)
            dao.deleteStream(id)
        }
        saved
    }

    fun saveDraft(record: ConversationRecord) = transaction {
        check(dao.head(record.id) != null)
        val old = dao.draft(record.id)
        if (old == null || record.draftSeq >= old.seq) putDraft(record)
    }
    private fun putDraft(record: ConversationRecord) {
        val row = DraftRow(record.id, record.draftSeq, record.draft,
            record.choiceDraft?.let { json.encodeToString(it) }, record.nativeDraftOrigin?.let { json.encodeToString(it) })
        if (dao.draft(record.id) != row) dao.put(row)
    }

    private fun putVariant(codec: Codec, turnId: String, position: Int, v: MessageVariant, old: MessageVariant?) {
        val metadata = json.encodeToJsonElement(v.copy(message = v.message.copy(content = "", sourceText = "", reasoning = emptyList(), stateConfirmation = null),
            generationPlan = null, runtimeStateBefore = null, projectionRuntimeStateBefore = null, runtimeStateAfter = null,
            browserHead = null, nativeOperations = emptyList())).jsonObject.filterKeys { it !in setOf("message", "generationPlan", "runtimeStateBefore", "projectionRuntimeStateBefore", "runtimeStateAfter", "browserHead", "nativeOperations") }
        val message = json.encodeToJsonElement(v.message.copy(content = "", sourceText = "", reasoning = emptyList(), stateConfirmation = null))
            .jsonObject.filterKeys { it !in setOf("content", "sourceText", "reasoning", "stateConfirmation") }
        dao.put(VariantRow(v.id, turnId, position, JsonObject(metadata).toString(), JsonObject(message).toString(),
            codec.text(v.message.sourceText), codec.text(v.message.content), codec.value(v.message.reasoning), v.message.stateConfirmation?.let(codec::text),
            v.runtimeStateBefore?.let { codec.value(it) }, v.projectionRuntimeStateBefore?.let { codec.value(it) },
            v.runtimeStateAfter?.let { codec.value(it) }, v.browserHead?.let { codec.value(it) }))
        if (v.generationPlan !== old?.generationPlan) {
            val plan = v.generationPlan
            if (plan == null) dao.deletePlan(v.id) else {
                val meta = json.encodeToJsonElement(plan.copy(messages = emptyList(), trace = emptyList(), diagnostics = emptyList(), runtimeState = ConversationRuntimeState()))
                    .jsonObject.filterKeys { it !in setOf("messages", "trace", "diagnostics", "runtimeState") }
                dao.put(PlanRow(v.id, JsonObject(meta).toString(), codec.value(plan.runtimeState)))
                if (plan.messages !== old?.generationPlan?.messages) {
                    dao.deletePlanParts(v.id, "message")
                    plan.messages.forEachIndexed { index, m -> dao.put(PlanPartRow(v.id, "message", index,
                        JsonObject(json.encodeToJsonElement(m.copy(content = "")).jsonObject - "content").toString(), codec.text(m.content))) }
                }
                if (plan.trace !== old?.generationPlan?.trace) {
                    dao.deletePlanParts(v.id, "trace")
                    plan.trace.forEachIndexed { index, item -> dao.put(PlanPartRow(v.id, "trace", index, "{}", codec.value(item))) }
                }
                if (plan.diagnostics !== old?.generationPlan?.diagnostics) {
                    dao.deletePlanParts(v.id, "diagnostic")
                    plan.diagnostics.forEachIndexed { index, item -> dao.put(PlanPartRow(v.id, "diagnostic", index, "{}", codec.value(item))) }
                }
            }
        }
        val operations = old?.nativeOperations?.associateBy { it.id }.orEmpty()
        val ids = v.nativeOperations.map { it.id }.toSet()
        operations.keys.filterNot(ids::contains).forEach(dao::deleteOperation)
        v.nativeOperations.forEachIndexed { index, op -> if (operations[op.id] !== op) dao.put(OperationRow(op.id, v.id, index, codec.value(op))) }
    }

    fun startStream(id: String, conversationId: String, variantId: String, context: String) = transaction {
        val variant = requireNotNull(dao.variant(variantId))
        require(dao.turn(variant.turnId)?.conversationId == conversationId)
        require(dao.streams(conversationId).isEmpty())
        check(dao.stream(id) == null)
        dao.put(StreamRow(id, conversationId, variantId, Codec().text(context)))
    }
    fun appendStream(id: String, chunks: List<StreamChunk>, projectedThrough: Long, preview: MessageVariant?) = transaction {
        val prior = requireNotNull(dao.stream(id))
        val codec = Codec()
        var seq = prior.persistedThrough
        chunks.forEach { chunk ->
            require(chunk.seq == seq + 1) { "生成原始事件序号不连续" }
            dao.put(ChunkRow(id, chunk.seq, chunk.kind, codec.text(chunk.payload))); seq = chunk.seq
        }
        require(projectedThrough in prior.projectedThrough..seq)
        dao.put(prior.copy(persistedThrough = seq, projectedThrough = projectedThrough,
            preview = preview?.let { codec.value(it.copy(generationPlan = null, nativeOperations = emptyList())) } ?: prior.preview))
    }
    fun streamData(id: String): List<RecoveredStream> = dao.streams(id).map { row -> RecoveredStream(row,
        text(row.context), dao.chunks(row.id).map { StreamChunk(it.seq, it.kind, text(it.content)) }, row.preview?.let { value<MessageVariant>(it) }) }
    override fun close() = database.close()
    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

data class StreamChunk(val seq: Long, val kind: String, val payload: String)
data class RecoveredStream(val progress: StreamRow, val context: String, val chunks: List<StreamChunk>, val preview: MessageVariant?)
