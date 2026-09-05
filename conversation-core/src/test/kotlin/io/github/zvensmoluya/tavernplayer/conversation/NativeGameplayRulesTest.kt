package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NativeGameplayRulesTest {
    private val runtime = NativeAdaptationRuntime()
    private val adaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), state = listOf(
        ConversationStateDefinition("affinity", type = ConversationStateValueType.NUMBER, initialValue = JsonPrimitive(0), numberRange = NativeNumberRange(0.0, 100.0)),
        ConversationStateDefinition("event", type = ConversationStateValueType.BOOLEAN, initialValue = JsonPrimitive(false)),
        ConversationStateDefinition("stage", type = ConversationStateValueType.STRING, initialValue = JsonPrimitive("start")),
        ConversationStateDefinition("weather", type = ConversationStateValueType.STRING, initialValue = JsonPrimitive("sun"), allowedStrings = listOf("sun", "rain")),
    ), assistantStateAdapters = listOf(AssistantStateAdapterDefinition(LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1, listOf(
        AssistantStateMapping("/affinity", "affinity"), AssistantStateMapping("/event", "event"),
        AssistantStateMapping("/stage", "stage", writable = false), AssistantStateMapping("/weather", "weather"),
    ))), progressions = listOf(NativeProgressionDefinition("affinity", "stage", listOf(
        NativeProgressionLevel(0.0, "start"), NativeProgressionLevel(30.0, "middle"), NativeProgressionLevel(85.0, "open", "event", "locked"),
    ))), messagePanels = listOf(NativeMessagePanelView("notes", "本幕", "panel", listOf(
        NativeMessagePanelField("date", "日历"), NativeMessagePanelField("mood", "心境"),
    ))))
    private fun envelope(vararg entries: String) = "<UpdateVariable><JSONPatch>[${entries.joinToString(",")}]</JSONPatch></UpdateVariable>"
    private fun replace(path: String, value: String) = "{\"op\":\"replace\",\"path\":\"/$path\",\"value\":$value}"

    @Test fun `progression preserves boundaries event lock clamping and restored checkpoints`() {
        assertTrue(NativeAdaptationValidator().validate(adaptation).valid)
        val initial = runtime.initialState(adaptation)
        listOf(29 to "start", 30 to "middle", 84 to "middle", 85 to "locked", 101 to "locked").forEach { (value, expected) ->
            val state = runtime.ingestAssistantMessage(adaptation, envelope(replace("affinity", value.toString())), initial).runtimeState
            assertEquals(JsonPrimitive(expected), state.conversationState.values["stage"])
        }
        val opened = runtime.ingestAssistantMessage(adaptation, envelope(replace("affinity", "120"), replace("event", "true")), initial).runtimeState
        assertEquals(JsonPrimitive(100.0), opened.conversationState.values["affinity"])
        assertEquals(JsonPrimitive("open"), opened.conversationState.values["stage"])
        assertEquals(opened, Json.decodeFromString<ConversationRuntimeState>(Json.encodeToString(opened)))
        assertEquals(JsonPrimitive("start"), initial.conversationState.values["stage"])
        assertTrue(LegacyStateReadProjection.project(adaptation, opened.conversationState)!!.contains("\"stage\":\"open\""))
        assertFalse(ConversationStatePromptProjector().projectAdapterContract(adaptation)!!.contains("/stage ->"))
    }

    @Test fun `read only stages and unknown enum values reject the entire state batch`() {
        val initial = runtime.initialState(adaptation)
        listOf(replace("stage", "\"open\""), replace("weather", "\"fog\"")).forEach { invalid ->
            val result = runtime.ingestAssistantMessage(adaptation, envelope(replace("affinity", "40"), invalid), initial)
            assertNotNull(result.rejection)
            assertEquals(initial, result.runtimeState)
        }
    }

    @Test fun `rejects unreachable progression labels and case ambiguous panel tags`() {
        val invalidLabels = adaptation.copy(state = adaptation.state.map {
            if (it.key == "stage") it.copy(allowedStrings = listOf("start", "middle", "open")) else it
        })
        assertTrue(NativeAdaptationValidator().validate(invalidLabels).issues.any { it.code == "INVALID_PROGRESSION_LABEL" })
        val panel = adaptation.messagePanels.single()
        val duplicate = adaptation.copy(messagePanels = listOf(panel.copy(fields = panel.fields + NativeMessagePanelField("DATE", "重复"))))
        assertTrue(NativeAdaptationValidator().validate(duplicate).issues.any { it.code == "INVALID_PANEL_FIELD_TAG" })
    }

    @Test fun `message panels retain all mapped content independently from current state`() {
        val source = "正文。\n<panel><date>昨日黄昏</date><mood>若有所思</mood></panel>" + envelope()
        val projected = runtime.projectAssistantMessage(adaptation, source)
        assertEquals("正文。", projected.narrativeText)
        val panels = NativeMessagePanels.project(adaptation, source).panels
        assertEquals(listOf("日历" to "昨日黄昏", "心境" to "若有所思"), panels.single().fields)
        val future = runtime.ingestAssistantMessage(adaptation, envelope(replace("affinity", "90")), runtime.initialState(adaptation))
        assertEquals(panels, NativeMessagePanels.project(adaptation, source).panels)
        assertEquals(JsonPrimitive("locked"), future.runtimeState.conversationState.values["stage"])
        listOf("<panel><date>日期</date></panel>", "<panel><date>日期</date><mood>心境</mood>额外正文</panel>", "<panel>未结束").forEach { invalid ->
            assertEquals(invalid, NativeMessagePanels.project(adaptation, invalid).narrative)
            assertTrue(NativeMessagePanels.project(adaptation, invalid).panels.isEmpty())
        }
        assertEquals("正文。", NativeMessagePanels.project(adaptation, "正文。<panel>未结束", streaming = true).narrative)
    }
}
