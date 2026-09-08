package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeOperationsTest {
    private val program = NativeScriptProgram(
        modules = listOf(NativeScriptModule("main", "export function run(){}", listOf("fixture"), "Synthetic")),
        surfaces = listOf(NativeSurfaceEntry("actions", "main", "run", NativeSurfaceType.ACTION_GROUP)),
        handlers = listOf(NativeHandlerEntry("run", "main", "run")),
    )
    private fun record(): ConversationRecord {
        val character = CharacterAsset("card", "Anonymous").copy(nativeAdaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), script = program))
        return ConversationRecord(id = "c", character = character.snapshot(), persona = Persona("u", "User"),
            turns = listOf(ConversationTurn("t", MessageRole.ASSISTANT, listOf("a", "b").map {
                MessageVariant(it, ConversationMessage(it, MessageRole.ASSISTANT, "Opening", "Anonymous"),
                    runtimeStateAfter = ConversationRuntimeState())
            })), createdAtEpochMillis = 0, updatedAtEpochMillis = 0)
    }
    private val action = NativeSurfaceAction("run", "Run", "run")
    private fun invocation(record: ConversationRecord) = NativeSurfaceInvocation("actions", record.nativeRevision(), action)

    @Test fun openingContextExposesOriginalIndicesOnlyBeforeConversationStarts() {
        val original = record()
        val opening = original.turns.single().let { turn -> turn.copy(
            variants = turn.variants.mapIndexed { index, variant -> variant.copy(openingSourceIndex = index * 2) },
            selectedVariantIndex = 1,
        ) }
        val first = original.copy(turns = listOf(opening))
        assertEquals(JsonPrimitive(2), first.nativeContext()["openingSourceIndex"])
        assertEquals(JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(2))), first.nativeContext()["openingSourceIndices"])
        assertEquals(JsonPrimitive(0), first.copy(turns = listOf(opening.copy(selectedVariantIndex = 0))).nativeContext()["openingSourceIndex"])
        val continued = first.copy(turns = first.turns + original.turns.single().copy(id = "later"))
        assertEquals(JsonNull, continued.nativeContext()["openingSourceIndex"])
        assertEquals(JsonArray(emptyList()), continued.nativeContext()["openingSourceIndices"])
        assertEquals(JsonNull, original.nativeContext()["openingSourceIndex"])
    }

    @Test fun directMvuWritePreservesObjectOrScalarSchemaAndRejectsChanges() {
        for (schema in listOf<JsonElement>(JsonObject(emptyMap()), JsonPrimitive("pinned helper marker"))) {
            val old = MvuStateSnapshot("bundle", "program", buildJsonObject {
                put("schema", schema); put("stat_data", buildJsonObject { put("value", 1) })
            })
            val next = JsonObject(old.data + ("stat_data" to buildJsonObject { put("value", 2) }))
            assertEquals(schema, old.withDirectReplacement(next).data["schema"])
            reject { old.withDirectReplacement(JsonObject(next - "schema")) }
            reject { old.withDirectReplacement(JsonObject(next + ("schema" to JsonPrimitive("changed")))) }
            reject { old.withDirectReplacement(JsonObject(next + ("stat_data" to JsonPrimitive(2)))) }
        }
    }

    @Test fun actionWritesAreDurableBranchHeadsWithoutChangingMessageEndHistory() {
        val original = record()
        var record = NativeOperations.begin(original, invocation(original), "op")
        val one = record.runtimeState.copy(scriptState = buildJsonObject { put("value", 1) })
        record = NativeOperations.commit(record, "op", one)
        val two = one.copy(scriptState = buildJsonObject { put("value", 2) })
        record = NativeOperations.commit(record, "op", two)
        record = NativeOperations.finish(record, "op", NativeOperationStatus.FAILED)
        val restored = Json.decodeFromString<ConversationRecord>(Json.encodeToString(record))
        assertEquals(two.copy(nativeCommitId = "op:1"), restored.runtimeState)
        assertEquals(two.copy(nativeCommitId = "op:1"), restored.turns.single().selected.nativeHead())
        assertEquals(ConversationRuntimeState(), restored.turns.single().selected.runtimeStateAfter)
        assertEquals(ConversationRuntimeState(), restored.turns.single().variants[1].nativeHead())
        val commits = restored.turns.single().selected.nativeOperations.single().commits
        assertEquals(commits.first().id, commits.last().parentId)
        reject { NativeOperations.commit(restored, "op", one) }
        reject { NativeOperations.begin(restored, invocation(restored), "op") }
        val afterLegacyWrite = restored.runtimeState.copy(conversationState = ConversationStateSnapshot(mapOf("choice" to JsonPrimitive("yes"))))
        assertEquals(afterLegacyWrite, restored.turns.single().selected.copy(runtimeStateAfter = afterLegacyWrite).nativeHead())
    }

    @Test fun crashMarksOperationInterruptedRetainsPriorWritesAndNeverReplaysExternalRequest() {
        val original = record()
        var record = NativeOperations.begin(original, invocation(original), "op")
        record = NativeOperations.commit(record, "op", record.runtimeState.copy(scriptState = buildJsonObject { put("reserved", true) }))
        record = NativeOperations.generation(record, "op", "request")
        val recovered = NativeOperations.recover(Json.decodeFromString(Json.encodeToString(record)))
        assertEquals(record.runtimeState, recovered.runtimeState)
        val op = recovered.turns.single().selected.nativeOperations.single()
        assertEquals(NativeOperationStatus.INTERRUPTED, op.status)
        assertEquals(listOf("request"), op.generationRequests)
        assertEquals(recovered, NativeOperations.recover(recovered))
        reject { NativeOperations.active(recovered, "op") }
    }

    @Test fun staleForgedAndDisabledActionsRejectIncludingSameMessageCountChanges() {
        val record = record()
        val rendered = NativeRenderedSurface("actions", record.nativeRevision(), NativeSurfaceData(NativeSurfaceType.ACTION_GROUP, "Actions", actions = listOf(action)))
        val valid = invocation(record)
        NativeOperations.authorize(record, rendered, valid)
        reject { NativeOperations.authorize(record.copy(draft = "edited"), rendered, valid) }
        reject { NativeOperations.authorize(record, rendered, valid.copy(action = action.copy(args = buildJsonObject { put("admin", true) }))) }
        reject { NativeOperations.authorize(record, rendered, valid.copy(action = action.copy(enabled = false))) }
        val otherBranch = record.copy(turns = listOf(record.turns.single().copy(selectedVariantIndex = 1)))
        reject { NativeOperations.authorize(otherBranch, rendered, valid) }
    }

    @Test fun invalidatedMemoryCannotReappearFromAnActionCheckpoint() {
        val original = record()
        val started = NativeOperations.begin(original, invocation(original), "op")
        val withMemory = started.runtimeState.copy(memories = mapOf("note" to ConversationMemory("Old", listOf("a"), 1, "test")))
        val committed = NativeOperations.commit(started, "op", withMemory)
        val cleaned = NativeMemoryController.invalidate(committed, "a")
        assertTrue(cleaned.runtimeState.memories.isEmpty())
        assertTrue(cleaned.turns.single().selected.nativeHead()!!.memories.isEmpty())
        assertTrue(cleaned.turns.single().selected.nativeOperations.single().commits.single().runtime.memories.isEmpty())
    }

    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
}
