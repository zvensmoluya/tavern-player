package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConversationStateReaderTest {
    private val adaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), mvu = NativeMvuProgram("schema"),
        stateBindings = listOf(
            NativeStateBinding("inventory", NativeStateSource.MVU, "/stat_data/物品栏", ConversationStateValueType.RECORD),
            NativeStateBinding("score", NativeStateSource.MVU, "/stat_data/数值", ConversationStateValueType.NUMBER),
            NativeStateBinding("escaped", NativeStateSource.MVU, "/stat_data/a~1b/~0/0", ConversationStateValueType.STRING)),
        collections = listOf(NativeCollectionView("bag", "背包", "inventory", shape = NativeCollectionShape.OBJECT, fields = listOf(
            NativeCollectionField("name", "名称", entryKey = true), NativeCollectionField("quantity", "数量", path = "/数量")))))

    private fun state(json: String) = ConversationRuntimeState(mvuState = MvuStateSnapshot("b".repeat(64), "c".repeat(64),
        Json.parseToJsonElement(json).jsonObject))

    @Test fun readsActualCheckpointWithoutMaterializingPlayerStateAndHistoryStaysIndependent() {
        val before = state("""{"stat_data":{"数值":0,"物品栏":{},"a/b":{"~":["literal"]}}}""")
        val after = state("""{"stat_data":{"数值":0.5,"物品栏":{"样本茶":{"数量":2}}}}""")
        val persisted = Json.encodeToString(after)
        val current = ConversationStateReader(adaptation, Json.decodeFromString<ConversationRuntimeState>(persisted))
        val history = ConversationStateReader(adaptation, before)
        assertEquals(JsonPrimitive(0.5), current["score"])
        assertEquals(JsonPrimitive(0), history["score"])
        assertEquals(JsonPrimitive("literal"), history["escaped"])
        val view = adaptation.collections.single()
        assertTrue(NativeCollectionDisplay.rows(view, history)!!.isEmpty())
        val row = NativeCollectionDisplay.rows(view, current)!!.single()
        assertEquals("样本茶", row.text(view.fields[0]))
        assertEquals("2", row.text(view.fields[1]))
        assertEquals("状态不可用", row.text(NativeCollectionField("missing", "missing")))
        assertTrue(after.conversationState.values.isEmpty())
        assertEquals(persisted, Json.encodeToString(after))
        assertSame(before.mvuState!!.data["stat_data"]!!.jsonObject["物品栏"], history["inventory"])
    }

    @Test fun missingMalformedAndDisabledSourcesNeverFallBackToPlayerValuesOrEmptyInventory() {
        val broken = state("""{"stat_data":{"数值":"not a number","物品栏":[]}}""")
            .copy(conversationState = ConversationStateSnapshot(mapOf("score" to JsonPrimitive(999))))
        val reader = ConversationStateReader(adaptation, broken)
        assertNull(reader["score"])
        assertNull(NativeCollectionDisplay.rows(adaptation.collections.single(), reader))
        assertEquals("状态不可用", NativeStatusDisplay.value(NativeStatusItem("score", "值"), reader).text)
        assertNull(ConversationStateReader(adaptation.copy(mvu = null), broken)["score"])
        assertNull(ConversationStateReader(adaptation, ConversationRuntimeState())["inventory"])
    }

    @Test fun playerAndPlainTextSourcesNeedNoMvuAndSupportArrayCollections() {
        val values = mapOf("score" to JsonPrimitive(4), "rows" to Json.parseToJsonElement("""[{"name":"Tea","quantity":3}]"""))
        val checkpoint = ConversationRuntimeState(conversationState = ConversationStateSnapshot(values))
        val player = NativeAdaptation(sourceSha256 = "a".repeat(64), stateBindings = listOf(
            NativeStateBinding("first", NativeStateSource.PLAYER, "/rows/0/name", ConversationStateValueType.STRING)))
        val reader = ConversationStateReader(player, checkpoint)
        assertEquals(JsonPrimitive(4), reader["score"])
        assertEquals(JsonPrimitive("Tea"), reader["first"])
        val rows = NativeCollectionDisplay.rows(NativeCollectionView("bag", "Bag", "rows"), reader)!!
        assertEquals("3", rows.single().text(NativeCollectionField("quantity", "Quantity")))
        assertNull(checkpoint.mvuState)
        assertNull(ConversationStateReader(null, ConversationRuntimeState())["score"])
    }

    @Test fun pathsAreLiteralBoundedPointersNotExpressionsOrPrototypeLookups() {
        val tree = Json.parseToJsonElement("""{"a/b":{"~":[10,20]},"__proto__":"data"}""")
        assertEquals(JsonPrimitive(20), NativeStatePath.read(tree, "/a~1b/~0/1"))
        assertEquals(JsonPrimitive("data"), NativeStatePath.read(tree, "/__proto__"))
        for (path in listOf("a.b", "/a~2b", "/a~1b/~0/01", "/a~1b/~0/-1", "/a~1b/~0/99999999999999")) {
            assertNull(path, NativeStatePath.read(tree, path))
        }
        assertSame(tree, NativeStatePath.read(tree, ""))
        assertEquals("coat", NativeCollectionRow("top", JsonPrimitive("coat")).text(NativeCollectionField("value", "Clothing", path = "")))
        assertFalse(NativeStatePath.valid("/x".repeat(33)))
        assertFalse(NativeStatePath.valid("/" + "x".repeat(512)))
    }
}
