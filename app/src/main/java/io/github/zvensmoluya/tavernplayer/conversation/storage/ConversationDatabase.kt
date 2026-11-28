package io.github.zvensmoluya.tavernplayer.conversation.storage

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.zvensmoluya.tavernplayer.conversation.TITLE_SOURCE_LENGTH
import io.github.zvensmoluya.tavernplayer.conversation.TITLE_WINDOW
import io.github.zvensmoluya.tavernplayer.conversation.titleExcerpts
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversation_head")
data class HeadRow(@PrimaryKey val id: String, val revision: Long, val metadata: String,
    val character: String, val persona: String, val runtime: String, val worldBook: String)

data class ConversationBranch(
    val rootId: String, val parentId: String, val parentTurnId: String, val localTurnId: String,
    val rewritten: Boolean, val temporaryTitle: String?, val titleStart: Int,
)

@Entity(tableName = "conversation_summary", indices = [Index(value = ["assetId", "updatedAtEpochMillis", "id"])],
    foreignKeys = [ForeignKey(entity = HeadRow::class, parentColumns = ["id"], childColumns = ["id"], onDelete = ForeignKey.CASCADE)])
data class ConversationSummary(@PrimaryKey val id: String, val assetId: String, val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long, val turnCount: Int, val preview: String, val executionMode: String,
    @ColumnInfo(defaultValue = "0") val number: Int, val title: String? = null,
    @Embedded val branch: ConversationBranch? = null) {
    fun displayTitle(characterName: String) = title ?: branch?.temporaryTitle ?: "$characterName · 第 $number 场"
}

private const val TITLE_SOURCES = "SELECT substr(CAST(b.bytes AS TEXT), 1, $TITLE_SOURCE_LENGTH) " +
    "FROM conversation_turn t JOIN variant v ON v.id = t.selectedVariantId " +
    "JOIN content_blob b ON b.id = v.source WHERE t.role = 'USER' AND t.conversationId = "
private const val LOCAL_TITLE_SOURCES = TITLE_SOURCES + ":id AND t.position >= COALESCE(" +
    "(SELECT titleStart FROM conversation_summary WHERE id = :id), 0)"
private const val OPENING_ORDER = " ORDER BY t.position, t.id LIMIT $TITLE_WINDOW"
private const val RECENT_ORDER = " ORDER BY t.position DESC, t.id DESC LIMIT $TITLE_WINDOW"

internal val conversationTitleMigration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversation_summary ADD COLUMN number INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversation_summary ADD COLUMN title TEXT")
        db.query("SELECT id, assetId FROM conversation_summary WHERE executionMode = 'BROWSER' " +
            "ORDER BY assetId, createdAtEpochMillis, id").use { rows ->
            var lastCharacter: String? = null
            var number = 0
            while (rows.moveToNext()) {
                val id = rows.getString(0)
                val asset = rows.getString(1)
                if (asset != lastCharacter) { number = 0; lastCharacter = asset }
                val title = db.query(TITLE_SOURCES + "?" + OPENING_ORDER, arrayOf(id)).use { messages ->
                    var found: String? = null
                    while (found == null && messages.moveToNext()) found = titleExcerpts(messages.getString(0)).firstOrNull()
                    found
                }
                number++
                db.execSQL("UPDATE conversation_summary SET number = ?, title = ? WHERE id = ?", arrayOf<Any?>(number, title, id))
            }
        }
    }
}

internal val conversationBranchMigration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (field in listOf("rootId", "parentId", "parentTurnId", "localTurnId", "temporaryTitle"))
            db.execSQL("ALTER TABLE conversation_summary ADD COLUMN $field TEXT")
        for (field in listOf("rewritten", "titleStart"))
            db.execSQL("ALTER TABLE conversation_summary ADD COLUMN $field INTEGER")
    }
}

@Entity(tableName = "conversation_draft", foreignKeys = [ForeignKey(entity = HeadRow::class,
    parentColumns = ["id"], childColumns = ["id"], onDelete = ForeignKey.CASCADE)])
data class DraftRow(@PrimaryKey val id: String, val seq: Long, val text: String, val choice: String?, val nativeOrigin: String?)

@Entity(tableName = "conversation_turn", indices = [Index(value = ["conversationId", "position"])],
    foreignKeys = [ForeignKey(entity = HeadRow::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)])
data class TurnRow(@PrimaryKey val id: String, val conversationId: String, val position: Int, val role: String, val selectedVariantId: String)

@Entity(tableName = "variant", indices = [Index(value = ["turnId", "position"])],
    foreignKeys = [ForeignKey(entity = TurnRow::class, parentColumns = ["id"], childColumns = ["turnId"], onDelete = ForeignKey.CASCADE)])
data class VariantRow(@PrimaryKey val id: String, val turnId: String, val position: Int,
    val metadata: String, val message: String, val source: String, val canonical: String, val reasoning: String,
    val confirmation: String?, val before: String?, val projectionBefore: String?, val after: String?, val browserHead: String?)

@Entity(tableName = "content_blob")
data class BlobRow(@PrimaryKey val id: String, val bytes: ByteArray)

data class BlobSlice(val byteCount: Int, val bytes: ByteArray)

@Entity(tableName = "generation", foreignKeys = [ForeignKey(entity = VariantRow::class,
    parentColumns = ["id"], childColumns = ["id"], onDelete = ForeignKey.CASCADE)])
data class PlanRow(@PrimaryKey val id: String, val metadata: String, val runtime: String)

@Entity(tableName = "generation_part", primaryKeys = ["generationId", "kind", "position"],
    foreignKeys = [ForeignKey(entity = PlanRow::class, parentColumns = ["id"], childColumns = ["generationId"], onDelete = ForeignKey.CASCADE)])
data class PlanPartRow(val generationId: String, val kind: String, val position: Int, val metadata: String, val content: String)

@Entity(tableName = "runtime_operation", indices = [Index(value = ["variantId", "position"])],
    foreignKeys = [ForeignKey(entity = VariantRow::class, parentColumns = ["id"], childColumns = ["variantId"], onDelete = ForeignKey.CASCADE)])
data class OperationRow(@PrimaryKey val id: String, val variantId: String, val position: Int, val content: String)

@Entity(tableName = "stream_progress", indices = [Index("conversationId"), Index("variantId")],
    foreignKeys = [ForeignKey(entity = HeadRow::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)])
data class StreamRow(@PrimaryKey val id: String, val conversationId: String, val variantId: String,
    val context: String, val persistedThrough: Long = 0, val projectedThrough: Long = 0, val preview: String? = null)

@Entity(tableName = "stream_chunk", primaryKeys = ["generationId", "seq"],
    foreignKeys = [ForeignKey(entity = StreamRow::class, parentColumns = ["id"], childColumns = ["generationId"], onDelete = ForeignKey.CASCADE)])
data class ChunkRow(val generationId: String, val seq: Long, val kind: String, val content: String)

@Entity(tableName = "legacy_import")
data class ImportRow(@PrimaryKey val id: String, val sha256: String)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversation_summary WHERE id = :id") fun summary(id: String): ConversationSummary?
    @Query("SELECT COALESCE(MAX(number), 0) + 1 FROM conversation_summary WHERE assetId = :assetId AND executionMode = 'BROWSER'")
    fun nextNumber(assetId: String): Int
    @Query(LOCAL_TITLE_SOURCES + OPENING_ORDER) fun openingTitleSources(id: String): List<String>
    @Query(LOCAL_TITLE_SOURCES + RECENT_ORDER) fun recentTitleSources(id: String): List<String>
    @Query("UPDATE conversation_summary SET title = :title WHERE id = :id AND executionMode = 'BROWSER'")
    fun rename(id: String, title: String): Int
    @Query("SELECT EXISTS(SELECT 1 FROM conversation_summary WHERE id = :id AND executionMode = 'BROWSER')")
    fun containsBrowserConversation(id: String): Boolean

    @Query("SELECT * FROM conversation_summary WHERE executionMode = 'BROWSER' ORDER BY updatedAtEpochMillis DESC, id") fun observeSummaries(): Flow<List<ConversationSummary>>
    @Query("SELECT * FROM conversation_summary WHERE executionMode = 'BROWSER' ORDER BY updatedAtEpochMillis DESC, id") fun summaries(): List<ConversationSummary>
    @Query("SELECT * FROM conversation_turn WHERE id = :id") fun turn(id: String): TurnRow?
    @Query("SELECT * FROM conversation_head WHERE id = :id") fun head(id: String): HeadRow?
    @Query("SELECT * FROM conversation_draft WHERE id = :id") fun draft(id: String): DraftRow?
    @Query("SELECT * FROM conversation_turn WHERE conversationId = :id ORDER BY position, id") fun turns(id: String): List<TurnRow>
    @Query("SELECT * FROM conversation_turn WHERE conversationId = :id AND position < :before ORDER BY position DESC, id DESC LIMIT :limit")
    fun page(id: String, before: Int, limit: Int): List<TurnRow>
    @Query("SELECT * FROM variant WHERE turnId = :id ORDER BY position, id") fun variants(id: String): List<VariantRow>
    @Query("SELECT * FROM variant WHERE id = :id") fun variant(id: String): VariantRow?
    @Query("""
        SELECT length(bytes) AS byteCount,
               CASE WHEN :offset = 0 AND length(bytes) <= :limit THEN bytes
                    ELSE substr(bytes, :offset + 1, :limit) END AS bytes
        FROM content_blob WHERE id = :id
    """)
    fun blobSlice(id: String, offset: Int, limit: Int): BlobSlice?
    @Query("INSERT INTO generation (id, metadata, runtime) SELECT :target, metadata, runtime FROM generation WHERE id = :source")
    fun copyPlan(source: String, target: String)
    @Query("INSERT INTO generation_part (generationId, kind, position, metadata, content) " +
        "SELECT :target, kind, position, metadata, content FROM generation_part WHERE generationId = :source")
    fun copyPlanParts(source: String, target: String)
    @Query("SELECT * FROM generation WHERE id = :id") fun plan(id: String): PlanRow?
    @Query("SELECT * FROM generation_part WHERE generationId = :id ORDER BY kind, position") fun planParts(id: String): List<PlanPartRow>
    @Query("SELECT * FROM runtime_operation WHERE variantId = :id ORDER BY position, id") fun operations(id: String): List<OperationRow>
    @Query("SELECT * FROM stream_progress WHERE conversationId = :id") fun streams(id: String): List<StreamRow>
    @Query("SELECT * FROM stream_progress WHERE id = :id") fun stream(id: String): StreamRow?
    @Query("SELECT * FROM stream_chunk WHERE generationId = :id ORDER BY seq") fun chunks(id: String): List<ChunkRow>
    @Query("SELECT * FROM legacy_import WHERE id = :id") fun imported(id: String): ImportRow?
    @Query("DELETE FROM content_blob WHERE id NOT IN (SELECT ref FROM (SELECT `character` AS ref FROM conversation_head UNION ALL SELECT `persona` AS ref FROM conversation_head UNION ALL SELECT `runtime` AS ref FROM conversation_head UNION ALL SELECT `worldBook` AS ref FROM conversation_head UNION ALL SELECT `source` AS ref FROM variant UNION ALL SELECT `canonical` AS ref FROM variant UNION ALL SELECT `reasoning` AS ref FROM variant UNION ALL SELECT `confirmation` AS ref FROM variant UNION ALL SELECT `before` AS ref FROM variant UNION ALL SELECT `projectionBefore` AS ref FROM variant UNION ALL SELECT `after` AS ref FROM variant UNION ALL SELECT `browserHead` AS ref FROM variant UNION ALL SELECT `runtime` AS ref FROM generation UNION ALL SELECT `content` AS ref FROM generation_part UNION ALL SELECT `content` AS ref FROM runtime_operation UNION ALL SELECT `context` AS ref FROM stream_progress UNION ALL SELECT `preview` AS ref FROM stream_progress UNION ALL SELECT `content` AS ref FROM stream_chunk) WHERE ref IS NOT NULL)") fun collectUnusedBlobs()
    @Upsert fun put(value: HeadRow)
    @Upsert fun put(value: ConversationSummary)
    @Upsert fun put(value: DraftRow)
    @Upsert fun put(value: TurnRow)
    @Upsert fun put(value: VariantRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun put(value: BlobRow)
    @Upsert fun put(value: PlanRow)
    @Upsert fun put(value: PlanPartRow)
    @Upsert fun put(value: OperationRow)
    @Upsert fun put(value: StreamRow)
    @Insert fun put(value: ChunkRow)
    @Upsert fun put(value: ImportRow)
    @Query("DELETE FROM conversation_turn WHERE id = :id") fun deleteTurn(id: String)
    @Query("DELETE FROM variant WHERE id = :id") fun deleteVariant(id: String)
    @Query("DELETE FROM generation WHERE id = :id") fun deletePlan(id: String)
    @Query("DELETE FROM generation_part WHERE generationId = :id AND kind = :kind") fun deletePlanParts(id: String, kind: String)
    @Query("DELETE FROM runtime_operation WHERE id = :id") fun deleteOperation(id: String)
    @Query("DELETE FROM stream_progress WHERE id = :id") fun deleteStream(id: String)
}

@Database(entities = [HeadRow::class, ConversationSummary::class, DraftRow::class, TurnRow::class, VariantRow::class,
    BlobRow::class, PlanRow::class, PlanPartRow::class, OperationRow::class, StreamRow::class, ChunkRow::class, ImportRow::class],
    version = 3, exportSchema = true)
abstract class ConversationDatabase : RoomDatabase() { abstract fun conversations(): ConversationDao }
