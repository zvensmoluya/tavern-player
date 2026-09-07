package io.github.zvensmoluya.tavernplayer.conversation.mvu

import io.github.zvensmoluya.tavernplayer.content.*
import io.github.zvensmoluya.tavernplayer.conversation.ConversationStateReader
import io.github.zvensmoluya.tavernplayer.conversation.CharacterAsset
import io.github.zvensmoluya.tavernplayer.conversation.ConversationMessage
import io.github.zvensmoluya.tavernplayer.conversation.ConversationRecord
import io.github.zvensmoluya.tavernplayer.conversation.ConversationRepository
import io.github.zvensmoluya.tavernplayer.conversation.ConversationRuntimeState
import io.github.zvensmoluya.tavernplayer.conversation.ConversationTurn
import io.github.zvensmoluya.tavernplayer.conversation.MessageRole
import io.github.zvensmoluya.tavernplayer.conversation.MessageVariant
import io.github.zvensmoluya.tavernplayer.conversation.Persona
import io.github.zvensmoluya.tavernplayer.conversation.PromptCompiler
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*

/** Same assertions on desktop JNI and Android JNI; no mock JavaScript engine. */
object MvuRuntimeContract {
    suspend fun verifyOriginalSample(bundle: String, program: JsonObject) {
        val runtime = QuickJsMvuRuntime.create(bundle, program)
        try {
            val initial = runtime.initialize().messages.first().applyTo(ConversationRuntimeState())
            val state = initial.mvuState!!.data["stat_data"]!!.jsonObject
            val actor = state.keys.single { it.endsWith("状态") }
            val wardrobe = state.keys.single { it.endsWith("衣橱") }
            val evaluated = runtime.update(envelope("""[
                {"op":"replace","path":"/地点","value":"市场"},
                {"op":"insert","path":"/物品栏/样本物品","value":{"数量":"3"}},
                {"op":"delta","path":"/物品栏/样本物品/数量","value":-1},
                {"op":"insert","path":"/$wardrobe/样本衣物","value":{}},
                {"op":"replace","path":"/$actor/服装/上衣","value":"样本衣物"},
                {"op":"delta","path":"/$actor/亲密度","value":1000}
            ]"""), initial)
            val updated = evaluated.messages.single().applyTo(initial)
            assertTrue(evaluated.diagnostics.none { it.level in setOf("error", "warn", "warning") })
            assertEquals(2, number(updated, "物品栏", "样本物品", "数量"))
            assertEquals("无描述", value(updated, "物品栏", "样本物品", "描述").jsonPrimitive.content)
            assertEquals("上衣", value(updated, wardrobe, "样本衣物", "部位").jsonPrimitive.content)
            assertEquals(400, number(updated, actor, "亲密度"))
            assertEquals("样本衣物", value(updated, actor, "服装", "上衣").jsonPrimitive.content)
            val removed = runtime.update(envelope("""[
                {"op":"remove","path":"/物品栏/样本物品"},
                {"op":"remove","path":"/$wardrobe/样本衣物"}
            ]"""), updated).messages.single().applyTo(updated)
            assertFalse(value(removed, "物品栏").jsonObject.containsKey("样本物品"))
            assertFalse(value(removed, wardrobe).jsonObject.containsKey("样本衣物"))
            val adaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), mvu = NativeMvuProgram("schema"), stateBindings = listOf(
                NativeStateBinding("bag", NativeStateSource.MVU, "/stat_data/物品栏", ConversationStateValueType.RECORD),
                NativeStateBinding("outfit", NativeStateSource.MVU, "/stat_data/$actor/服装/上衣", ConversationStateValueType.STRING)))
            val view = NativeCollectionView("bag", "Bag", "bag", shape = NativeCollectionShape.OBJECT, fields = listOf(
                NativeCollectionField("name", "Name", entryKey = true), NativeCollectionField("quantity", "Quantity", path = "/数量")))
            val reader = ConversationStateReader(adaptation, updated)
            val row = NativeCollectionDisplay.rows(view, reader)!!.single { it.key == "样本物品" }
            assertEquals("样本物品", row.text(view.fields[0]))
            assertEquals("2", row.text(view.fields[1]))
            assertEquals("样本衣物", NativeStatusDisplay.value(NativeStatusItem("outfit", "Outfit"), reader).text)
            assertFalse(NativeCollectionDisplay.rows(view, ConversationStateReader(adaptation, removed))!!.any { it.key == "样本物品" })
            assertTrue(updated.conversationState.values.isEmpty())
            assertSame(value(updated, "物品栏"), reader["bag"])

        } finally { runtime.close() }
    }

    suspend fun verify(bundle: String, program: JsonObject, root: File): Map<String, Long> {
        val started = System.nanoTime()
        val runtime = QuickJsMvuRuntime.create(bundle, program)
        val loaded = System.nanoTime()
        val metrics = linkedMapOf("loadMillis" to (loaded - started) / 1_000_000)
        try {
            val initialized = runtime.initialize()
            assertEquals(2, initialized.messages.size)
            val initial = initialized.messages.first().applyTo(ConversationRuntimeState())
            assertEquals(0, number(initial, "days"))
            assertEquals(3, number(initialized.messages[1].applyTo(initial), "days"))
            val source = envelope("""[
                {"op":"replace","path":"/location","value":"market"},
                {"op":"insert","path":"/inventory/tea","value":{"quantity":"3"}},
                {"op":"delta","path":"/inventory/tea/quantity","value":-1},
                {"op":"insert","path":"/wardrobe/coat","value":{}},
                {"op":"replace","path":"/actor/outfit/top","value":"coat"},
                {"op":"delta","path":"/actor/affinity","value":1000}
            ]""")
            val beforeUpdate = System.nanoTime()
            val evaluated = runtime.update(source, initial)
            metrics["updateMillis"] = (System.nanoTime() - beforeUpdate) / 1_000_000
            assertTrue(evaluated.diagnostics.none { it.level in setOf("error", "warn", "warning") })
            assertTrue(evaluated.events.contains("mag_command_parsed_for_zod"))
            val result = evaluated.messages.single()
            assertEquals(source, result.sourceText)
            assertTrue(result.processedText.endsWith("<StatusPlaceHolderImpl/>"))
            val first = result.applyTo(initial)
            assertEquals("market", value(first, "location").jsonPrimitive.content)
            assertEquals(2, number(first, "inventory", "tea", "quantity"))
            assertEquals("No description", value(first, "inventory", "tea", "description").jsonPrimitive.content)
            assertEquals("top", value(first, "wardrobe", "coat", "slot").jsonPrimitive.content)
            assertEquals("coat", value(first, "actor", "outfit", "top").jsonPrimitive.content)
            assertEquals(400, number(first, "actor", "affinity"))
            assertEquals(JsonObject(emptyMap()), value(initial, "inventory"))

            val secondResult = runtime.update(envelope("""[
                {"op":"remove","path":"/inventory/tea"},
                {"op":"remove","path":"/wardrobe/coat"}
            ]"""), first).messages.single()
            val second = secondResult.applyTo(first)
            assertEquals(JsonObject(emptyMap()), value(second, "inventory"))
            assertEquals(JsonObject(emptyMap()), value(second, "wardrobe"))
            // Re-run the same turn from the same before-checkpoint, after another result already ran.
            val regenerated = runtime.update(source, initial).messages.single().applyTo(initial)
            assertEquals(first, regenerated)

            val turn = ConversationTurn("turn", MessageRole.ASSISTANT, listOf(
                variant("first", result, initial), variant("alternative", secondResult, first),
            ))
            val record = ConversationRecord(id = "mvu-checkpoints", character = CharacterAsset("sample", "Guide").snapshot(),
                persona = Persona("persona", "Traveler"), turns = listOf(turn), runtimeState = first,
                createdAtEpochMillis = 1, updatedAtEpochMillis = 1)
            val repository = ConversationRepository(root, PromptCompiler())
            val saved = repository.save(record)
            val restored = checkNotNull(ConversationRepository(root, PromptCompiler()).get(record.id))
            assertEquals(saved, restored)
            assertEquals(source, restored.turns.single().selected.message.sourceText)
            val selected = restored.turns.single().copy(selectedVariantIndex = 1)
            val switched = restored.copy(turns = listOf(selected), runtimeState = checkNotNull(selected.selected.runtimeStateAfter))
            repository.save(switched)
            assertEquals(second, ConversationRepository(root, PromptCompiler()).get(record.id)!!.runtimeState)

            val invalid = runtime.update(envelope("""[
                {"op":"insert","path":"/wardrobe/bad","value":{"slot":"invalid"}},
                {"op":"replace","path":"/_fixed","value":"changed"},
                {"op":"delta","path":"/location","value":2},
                {"op":"delta","path":"/days","value":1}
            ]"""), initial)
            val afterInvalid = invalid.messages.single().applyTo(initial)
            assertEquals(1, number(afterInvalid, "days"))
            assertEquals("keep", value(afterInvalid, "_fixed").jsonPrimitive.content)
            assertEquals(JsonObject(emptyMap()), value(afterInvalid, "wardrobe"))
            assertTrue(invalid.diagnostics.any { it.level == "warning" })
            val escaped = runtime.update(envelope("""[
                {"op":"insert","path":"/inventory/a.b~1c~0d","value":{}},
                {"op":"delta","path":"/inventory/a.b~1c~0d/quantity","value":2}
            ]"""), initial).messages.single().applyTo(initial)
            assertEquals(3, number(escaped, "inventory", "a.b/c~d", "quantity"))
            val literal = "Literal: \" ); throw new Error('message-is-not-code'); //\nUnicode: 路径/物品"
            val quoted = runtime.update(literal, initial).messages.single()
            assertEquals(literal, quoted.sourceText)
            assertEquals(initial, quoted.applyTo(initial))
            val shortReply = runtime.update("好。", initial).messages.single()
            assertEquals("好。", shortReply.processedText)
            assertEquals(initial, shortReply.applyTo(initial))
            metrics["quickJsHeapBytes"] = runtime.memoryUsedBytes()

            // Destroy the engine before restoring: no JS object survives the disk round trip.
            runtime.close()
            val fresh = QuickJsMvuRuntime.create(bundle, program)
            try {
                val afterRestart = fresh.update(envelope("""[{"op":"delta","path":"/days","value":2}]"""),
                    ConversationRepository(root, PromptCompiler()).get(record.id)!!.runtimeState).messages.single().applyTo(second)
                assertEquals(2, number(afterRestart, "days"))
                assertEquals(JsonObject(emptyMap()), value(afterRestart, "inventory"))
                val mismatched = second.copy(mvuState = second.mvuState!!.copy(programSha256 = "different"))
                try { fresh.update(envelope("[]"), mismatched); fail("Mismatched program must be rejected") }
                catch (_: IllegalArgumentException) { }
            } finally { fresh.close() }
        } finally { runtime.close() }
        return metrics
    }

    private fun variant(id: String, result: MvuMessageResult, previous: ConversationRuntimeState) = MessageVariant(
        id, ConversationMessage(id, MessageRole.ASSISTANT, result.processedText, "Guide", sourceText = result.sourceText),
        runtimeStateBefore = previous, runtimeStateAfter = result.applyTo(previous),
    )

    fun envelope(operations: String) = "Reply.\n<UpdateVariable><JSONPatch>$operations</JSONPatch></UpdateVariable>"
    fun program(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun value(state: ConversationRuntimeState, vararg path: String) = path.fold(
        state.mvuState!!.data["stat_data"]!!,
    ) { node, key -> node.jsonObject[key]!! }
    private fun number(state: ConversationRuntimeState, vararg path: String) = value(state, *path).jsonPrimitive.content.toDouble().toInt()
}
