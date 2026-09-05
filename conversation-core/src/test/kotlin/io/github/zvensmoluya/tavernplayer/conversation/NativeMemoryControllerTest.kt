package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeMemoryControllerTest {
    private val instruction = WorldBookEntryDefinition("instructions", content = "分析{{user}}与向导的合作，不把推测当事实。", enabled = false)
    private val profile = WorldBookEntryDefinition("profile", content = "向导希望旅行顺利。", enabled = false)
    private val book = WorldBookDefinition("source", recursiveScanning = true, entries = listOf(instruction, profile,
        WorldBookEntryDefinition("keyword", keys = listOf("blue-lantern"), content = "THE_LANTERN_LORE")))
    private fun ref(entry: WorldBookEntryDefinition) = NativeWorldBookReference(book.id, entry.id, NativeWorldBookTextSelectionValidator.sha256(entry.content))
    private val definition = NativeMemoryDefinition("cooperation", "合作记忆", ref(instruction), listOf(ref(profile)), 2, 3)
    private val adaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), memories = listOf(definition))
    private val character = CharacterAsset("card", sourceSha256 = adaptation.sourceSha256, name = "向导", description = "徒步向导", firstMessage = "出发。", worldBooks = listOf(book), nativeAdaptation = adaptation)
    private val mainPlan = GenerationPlan(emptyList(), 2048, 32768, "", "preset", "Preset", diagnostics = emptyList(), trace = emptyList())
    private fun record(): ConversationRecord {
        val runtime = NativeAdaptationRuntime().initialState(adaptation)
        return ConversationRecord(3, "record", character.snapshot(), Persona("p", "旅人"),
            listOf(MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT).mapIndexed { i, role ->
                ConversationTurn("turn-$i", role, listOf(MessageVariant("v-$i", ConversationMessage("m-$i", role,
                    listOf("出发。", "请带路。", "一起沿河走。")[i], "旅人"), generationPlan = if (i == 2) mainPlan else null,
                    runtimeStateBefore = runtime, runtimeStateAfter = runtime)))
            }, runtime, 0, 0)
    }

    @Test fun `source requirements selected history and prior result reach analysis without state write access`() {
        val record = record()
        val request = checkNotNull(NativeMemoryController.prepare(record, definition.id))
        assertNull(request.plan.nativeAdaptation)
        val evidence = Json.parseToJsonElement(request.plan.messages.last().content).jsonObject
        assertEquals("分析旅人与向导的合作，不把推测当事实。", evidence.getValue("analysisSpecification").jsonPrimitive.content)
        assertEquals(3, evidence.getValue("conversation").jsonArray.size)
        val committed = NativeMemoryController.commit(request, record, "共同完成了沿河步行。", "test", 100, 20)
        assertEquals(record.turns.size, committed.turns.size)
        assertEquals(record.runtimeState.conversationState, committed.runtimeState.conversationState)
        assertEquals(committed.runtimeState, committed.turns.last().selected.runtimeStateAfter)
        assertEquals(record.turns.last().selected.runtimeStateBefore, committed.turns.last().selected.runtimeStateBefore)
        assertNull(NativeMemoryController.prepare(committed, definition.id))
        val refresh = checkNotNull(NativeMemoryController.prepare(committed, definition.id, force = true))
        assertTrue(refresh.plan.messages.last().content.contains("共同完成了沿河步行。"))
        assertEquals(committed, Json.decodeFromString<ConversationRecord>(Json.encodeToString(committed)))
    }

    @Test fun `stale requests and bad results cannot commit and old candidates retain their own memory`() {
        val original = record()
        val request = checkNotNull(NativeMemoryController.prepare(original, definition.id))
        listOf(original.copy(draft = "新草稿"), original.copy(runtimeState = original.runtimeState.copy(generationIndex = 9))).forEach {
            assertThrows(IllegalArgumentException::class.java) { NativeMemoryController.commit(request, it, "结果", "model") }
        }
        listOf("", "x".repeat(32769)).forEach {
            assertThrows(IllegalArgumentException::class.java) { NativeMemoryController.commit(request, original, it, "model") }
        }
        val old = NativeMemoryController.commit(request, original, "候选 A 的结果", "model")
        val last = old.turns.last()
        val replacement = original.turns.last().selected.copy(id = "new", message = original.turns.last().selected.message.copy(content = "改走山路。"))
        val branched = old.copy(turns = old.turns.dropLast(1) + last.copy(variants = last.variants + replacement, selectedVariantIndex = 1), runtimeState = original.runtimeState)
        val updated = NativeMemoryController.commit(checkNotNull(NativeMemoryController.prepare(branched, definition.id)), branched, "候选 B 的结果", "model")
        assertEquals("候选 A 的结果", updated.turns.last().variants.first().runtimeStateAfter?.memories?.get(definition.id)?.content)
        assertEquals("候选 B 的结果", updated.runtimeState.memories.getValue(definition.id).content)
        val invalidated = NativeMemoryController.invalidate(updated, "v-1")
        assertTrue(invalidated.runtimeState.memories.isEmpty())
        assertTrue(invalidated.turns.last().variants.all { it.runtimeStateAfter!!.memories.isEmpty() })
    }

    @Test fun `memory affects prompt and keyword lore but stays literal and leaves source unchanged`() {
        val record = record()
        val literal = "blue-lantern {{setvar::intruder::yes}} {{user}}"
        val committed = NativeMemoryController.commit(checkNotNull(NativeMemoryController.prepare(record, definition.id)), record, literal, "model")
        val result = PromptCompiler().compile(NormalGenerationInput(character = character.snapshot(), persona = record.persona,
            history = record.turns.map { it.selected.message }, preset = BuiltInPresets.default, runtimeState = committed.runtimeState,
            conversationId = "r", generationId = "g", modelId = "test", modelContextTokens = 32768))
        assertTrue(result.toString(), result is CompilationResult.Success)
        val plan = (result as CompilationResult.Success).plan
        val text = plan.messages.joinToString("\n") { it.content }
        assertTrue(text.contains(literal))
        assertTrue(text.contains("THE_LANTERN_LORE"))
        assertFalse(plan.runtimeState.localVariables.containsKey("intruder"))
        assertEquals(committed.runtimeState.memories, plan.runtimeState.memories)
        assertEquals(book, character.worldBooks.single())
    }

    @Test fun `three staggered analyses refresh twice without replaying when reopened`() {
        val definitions = listOf(5, 10, 15).map { definition.copy(id = "note-$it", firstReply = it, everyReplies = 15) }
        var current = record().let { it.copy(character = it.character.copy(nativeAdaptation = adaptation.copy(memories = definitions))) }
        val triggered = mutableListOf<Pair<String, Int>>()
        for (count in 2..31) {
            if (count > 2) {
                val runtime = current.runtimeState
                val user = ConversationTurn("user-$count", MessageRole.USER, listOf(MessageVariant("user-$count",
                    ConversationMessage("user-$count", MessageRole.USER, "继续旅行。", "旅人"))))
                val reply = current.turns.last().copy(id = "reply-$count", variants = listOf(current.turns.last().selected.copy(
                    id = "reply-$count", message = ConversationMessage("reply-$count", MessageRole.ASSISTANT, "经过新的路标。", "向导"),
                    runtimeStateBefore = runtime, runtimeStateAfter = runtime)))
                current = current.copy(turns = current.turns + user + reply)
            }
            for (item in definitions) {
                val request = NativeMemoryController.prepare(current, item.id) ?: continue
                val previous = Json.parseToJsonElement(request.plan.messages.last().content).jsonObject.getValue("previousAnalysis").jsonPrimitive.content
                assertEquals(if (count > 15) "${item.id}:${count - 15}" else "", previous)
                current = NativeMemoryController.commit(request, current, "${item.id}:$count", "test")
                triggered += item.id to count
            }
            current = Json.decodeFromString(Json.encodeToString(current))
            definitions.forEach { assertNull(NativeMemoryController.prepare(current, it.id)) }
        }
        assertEquals(listOf("note-5" to 5, "note-10" to 10, "note-15" to 15,
            "note-5" to 20, "note-10" to 25, "note-15" to 30), triggered)
    }

    @Test fun `unconfirmed state is explicit evidence without blocking independent analysis`() {
        val original = record()
        val withState = adaptation.copy(state = listOf(ConversationStateDefinition("score", type = ConversationStateValueType.NUMBER,
            initialValue = JsonPrimitive(0))), assistantStateAdapters = listOf(AssistantStateAdapterDefinition(
            LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1, listOf(AssistantStateMapping("/score", "score")))))
        val current = original.copy(character = original.character.copy(nativeAdaptation = withState))
        val request = checkNotNull(NativeMemoryController.prepare(current, definition.id))
        val evidence = Json.parseToJsonElement(request.plan.messages.last().content).jsonObject
        assertFalse(evidence.getValue("stateConfirmedForLastReply").jsonPrimitive.boolean)
        val updated = NativeMemoryController.commit(request, current, "已经沿河同行；数值变化未确认。", "test")
        assertEquals(current.runtimeState.conversationState, updated.runtimeState.conversationState)
        assertNull(request.plan.nativeAdaptation)
    }

    @Test fun `cadence ignores user turns and failed assistant candidates`() {
        val record = record()
        assertNull(NativeMemoryController.prepare(record.copy(turns = record.turns.dropLast(1)), definition.id))
        val last = record.turns.last()
        assertNull(NativeMemoryController.prepare(record.copy(turns = record.turns.dropLast(1) + last.copy(
            variants = listOf(last.selected.copy(status = PersistedMessageStatus.ERROR)))), definition.id))
        val extra = last.copy(id = "extra", variants = listOf(last.selected.copy(id = "extra")))
        assertNull(NativeMemoryController.prepare(record.copy(turns = record.turns + extra), definition.id))
        assertNotNull(NativeMemoryController.prepare(record.copy(turns = record.turns + extra), definition.id, force = true))
    }
}
