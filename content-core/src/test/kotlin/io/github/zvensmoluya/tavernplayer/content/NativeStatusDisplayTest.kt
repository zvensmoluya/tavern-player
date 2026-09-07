package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NativeStatusDisplayTest {
    private val display = NativeStatusEnumDisplay("door", mapOf(
        "closed" to mapOf("ready" to "waiting", "waiting" to "waiting"),
        "open" to mapOf("ready" to "ready", "waiting" to "waiting")))
    private val item = NativeStatusItem("queue", "队列", group = "车站", enumDisplay = display)
    private val adaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), state = listOf(
        ConversationStateDefinition("door", type = ConversationStateValueType.STRING, initialValue = JsonPrimitive("open"), allowedStrings = listOf("open", "closed")),
        ConversationStateDefinition("queue", type = ConversationStateValueType.STRING, initialValue = JsonPrimitive("ready"), allowedStrings = listOf("ready", "waiting"))),
        status = NativeStatusView(items = listOf(item)))

    @Test fun `read only display table keeps stored enum distinguishable across gate changes and serialization`() {
        assertTrue(NativeAdaptationValidator().validate(adaptation).valid)
        val restored = Json.decodeFromString<NativeAdaptation>(Json.encodeToString(adaptation))
        val state = mapOf("door" to JsonPrimitive("closed"), "queue" to JsonPrimitive("ready"))
        val value = NativeStatusDisplay.value(restored.status!!.items.single(), PlayerStateReader(state))
        assertEquals(NativeStatusValue("waiting", "ready"), value)
        assertTrue(value.adjusted)
        assertEquals(JsonPrimitive("ready"), state["queue"])
        assertFalse(NativeStatusDisplay.value(item, PlayerStateReader(state + ("door" to JsonPrimitive("open")))).adjusted)
        assertEquals(NativeStatusValue("ready", "ready", true), NativeStatusDisplay.value(item, PlayerStateReader(state - "door")))
        assertTrue(NativeStatusDisplay.value(item, PlayerStateReader(state + ("queue" to JsonPrimitive(false)))).unavailable)
        assertEquals("100", NativeStatusDisplay.value(NativeStatusItem("n", "值"), PlayerStateReader(mapOf("n" to JsonPrimitive(100.0)))).text)
        assertEquals("100.0", NativeStatusDisplay.value(NativeStatusItem("n", "值"), PlayerStateReader(mapOf("n" to JsonPrimitive("100.0")))).text)
    }

    @Test fun `rejects partial extra invalid and non enum display cases`() {
        listOf(display.copy(gateStateKey = "missing"), display.copy(values = display.values - "closed"),
            display.copy(values = display.values + ("open" to mapOf("ready" to "waiting"))),
            display.copy(values = display.values + ("open" to mapOf("ready" to "arbitrary", "waiting" to "waiting"))))
            .forEach { invalid -> assertFalse(NativeAdaptationValidator().validate(adaptation.copy(status = NativeStatusView(items = listOf(item.copy(enumDisplay = invalid))))).valid) }
        assertFalse(NativeAdaptationValidator().validate(adaptation.copy(state = adaptation.state.map { it.copy(allowedStrings = emptyList()) })).valid)
    }
}
