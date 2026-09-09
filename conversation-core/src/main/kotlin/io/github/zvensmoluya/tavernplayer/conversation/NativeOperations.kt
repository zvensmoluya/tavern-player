package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.*
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
enum class NativeOperationStatus { RUNNING, COMPLETE, CANCELLED, FAILED, INTERRUPTED }

@Serializable
data class NativeDraftOrigin(val variantId: String, val operationId: String, val text: String)

@Serializable
data class NativeOperationCommit(val id: String, val parentId: String, val runtime: ConversationRuntimeState)

@Serializable
data class NativeOperationRecord(
    val id: String, val baseRevision: String, val artifactSha256: String,
    val surfaceId: String, val handlerId: String, val args: JsonObject, val input: Map<String, String>,
    val status: NativeOperationStatus = NativeOperationStatus.RUNNING,
    val commits: List<NativeOperationCommit> = emptyList(),
    val generationRequests: List<String> = emptyList(),
)

data class NativeRenderedSurface(val id: String, val revision: String, val data: NativeSurfaceData)
data class NativeSurfaceInvocation(
    val surfaceId: String, val revision: String, val action: NativeSurfaceAction,
    val itemKey: String? = null, val input: Map<String, String> = emptyMap(),
)

/** Message-end snapshots remain immutable; subsequent action commits form that branch's head. */
fun MessageVariant.nativeHead(): ConversationRuntimeState? {
    browserHead?.let { return it }
    val action = nativeOperations.asReversed().firstNotNullOfOrNull { it.commits.lastOrNull() } ?: return runtimeStateAfter
    // Existing explicit choice/memory workflows may subsequently save a newer snapshot based on this commit.
    // Their copies retain its identity; a message-end snapshot from before the action cannot match it.
    return runtimeStateAfter?.takeIf { it.nativeCommitId == action.id } ?: action.runtime
}

fun ConversationRecord.nativeRevision(): String = nativeHash(buildString {
    // Do not recursively hash historical commit snapshots or captured generation plans on the UI thread.
    append(buildJsonArray { turns.forEach { turn -> add(buildJsonArray {
        val variant = turn.selected
        add(variant.id); add(variant.message.content); add(variant.message.sourceText); add(variant.status.name)
        variant.nativeOperations.lastOrNull()?.let { add(it.id); add(it.status.name); add(it.commits.size) }
    }) } })
    append(Json.encodeToString(runtimeState)); append(Json.encodeToString(character.nativeAdaptation?.script))
    append(JsonPrimitive(id)); append(JsonPrimitive(draft))
})

fun nativeHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

fun ConversationRecord.nativeContext(): JsonObject = buildJsonObject {
    put("state", runtimeState.mvuState?.data ?: JsonObject(runtimeState.conversationState.values))
    put("programState", runtimeState.scriptState ?: character.nativeAdaptation?.script?.initialState ?: JsonObject(emptyMap()))
    put("draft", draft)
    val opening = turns.singleOrNull()?.takeIf { turn -> turn.variants.all { it.openingSourceIndex != null } }
    put("openingSourceIndex", opening?.selected?.openingSourceIndex)
    put("openingSourceIndices", buildJsonArray { opening?.variants?.forEach { add(checkNotNull(it.openingSourceIndex)) } })
    put("userName", persona.name); put("characterName", character.promptName)
    put("history", buildJsonArray { turns.forEach { turn -> add(buildJsonObject {
        put("role", turn.role.name.lowercase()); put("text", turn.selected.message.content)
    }) } })
}

/** The pinned Zod helper may use a scalar schema marker; preserve it exactly, do not invent a schema. */
fun MvuStateSnapshot.withDirectReplacement(replacement: JsonObject): MvuStateSnapshot {
    require(replacement["stat_data"] is JsonObject && "schema" in replacement && replacement["schema"] == data["schema"]) {
        "MVU 数据必须保留 stat_data 对象及原 schema 值"
    }
    return copy(data = replacement)
}

object NativeOperations {
    fun authorize(record: ConversationRecord, rendered: NativeRenderedSurface, invocation: NativeSurfaceInvocation) {
        require(invocation.revision == record.nativeRevision() && rendered.revision == invocation.revision &&
            rendered.id == invocation.surfaceId) { "界面已更新，请重新操作" }
        val actions = if (invocation.itemKey == null) rendered.data.actions else
            rendered.data.items.singleOrNull { it.key == invocation.itemKey }?.actions.orEmpty()
        require(invocation.action.enabled && invocation.action in actions) { "操作当前不可用" }
        require(invocation.input.keys.all { key -> rendered.data.fields.any { it.id == key } })
        rendered.data.fields.forEach { field ->
            val value = invocation.input[field.id] ?: field.value
            require(value.length <= 16_384 && (!field.required || value.isNotBlank()) &&
                (field.options.isEmpty() || value.isEmpty() || value in field.options)) { "请检查输入：${field.label}" }
        }
    }

    fun begin(record: ConversationRecord, invocation: NativeSurfaceInvocation, id: String): ConversationRecord {
        require(record.turns.isNotEmpty()) { "请先开始对话" }
        require(record.turns.none { turn -> turn.variants.any { variant ->
            variant.nativeOperations.any { it.status == NativeOperationStatus.RUNNING || it.id == id }
        } })
        val program = requireNotNull(record.character.nativeAdaptation?.script)
        val operation = NativeOperationRecord(id, invocation.revision, nativeHash(Json.encodeToString(program)),
            invocation.surfaceId, invocation.action.handler, invocation.action.args, invocation.input)
        return updateLast(record) { it.copy(nativeOperations = it.nativeOperations + operation) }
    }

    fun active(record: ConversationRecord, id: String): NativeOperationRecord =
        requireNotNull(record.turns.lastOrNull()?.selected?.nativeOperations?.singleOrNull {
            it.id == id && it.status == NativeOperationStatus.RUNNING
        }) { "操作已失效" }

    fun commit(record: ConversationRecord, id: String, runtime: ConversationRuntimeState, draft: String = record.draft): ConversationRecord {
        val op = active(record, id)
        require(op.commits.size < 128) { "单次操作提交过多" }
        val commitId = "$id:${op.commits.size}"
        val committedRuntime = runtime.copy(nativeCommitId = commitId)
        val commit = NativeOperationCommit(commitId, op.commits.lastOrNull()?.id ?: op.baseRevision, committedRuntime)
        val committed = updateOperation(record, id) { it.copy(commits = it.commits + commit) }
            .copy(runtimeState = committedRuntime).withDraft(draft)
        return if (draft == record.draft) committed else committed.copy(nativeDraftOrigin = NativeDraftOrigin(record.turns.last().selected.id, id, draft))
    }

    fun generation(record: ConversationRecord, id: String, requestId: String): ConversationRecord {
        active(record, id)
        return updateOperation(record, id) { it.copy(generationRequests = it.generationRequests + requestId) }
    }

    fun finish(record: ConversationRecord, id: String, status: NativeOperationStatus): ConversationRecord {
        require(status != NativeOperationStatus.RUNNING)
        active(record, id)
        return updateOperation(record, id) { it.copy(status = status) }
    }

    fun recover(record: ConversationRecord): ConversationRecord = record.copy(turns = record.turns.map { turn ->
        turn.copy(variants = turn.variants.map { variant -> variant.copy(nativeOperations = variant.nativeOperations.map { op ->
            if (op.status == NativeOperationStatus.RUNNING) op.copy(status = NativeOperationStatus.INTERRUPTED) else op
        }) })
    })

    private fun updateOperation(record: ConversationRecord, id: String, transform: (NativeOperationRecord) -> NativeOperationRecord) =
        updateLast(record) { it.copy(nativeOperations = it.nativeOperations.map { op -> if (op.id == id) transform(op) else op }) }

    private fun updateLast(record: ConversationRecord, transform: (MessageVariant) -> MessageVariant): ConversationRecord =
        record.copy(turns = record.turns.mapIndexed { index, turn ->
            if (index != record.turns.lastIndex) turn else turn.copy(variants = turn.variants.mapIndexed { i, variant ->
                if (i == turn.selectedVariantIndex) transform(variant) else variant
            })
        })
}
