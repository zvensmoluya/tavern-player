package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.AssistantStateAdapterDefinition
import io.github.zvensmoluya.tavernplayer.content.AssistantStateMapping
import io.github.zvensmoluya.tavernplayer.content.ConversationStateDefinition
import io.github.zvensmoluya.tavernplayer.content.ConversationStateValueType
import io.github.zvensmoluya.tavernplayer.content.LegacyStateDialect
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import io.github.zvensmoluya.tavernplayer.content.NativeFormField
import io.github.zvensmoluya.tavernplayer.content.NativeFormFieldType
import io.github.zvensmoluya.tavernplayer.content.NativeFormOption
import io.github.zvensmoluya.tavernplayer.content.NativeFormView
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAdaptationRuntimeTest {
    private val runtime = NativeAdaptationRuntime()

    @Test
    fun `form has one fixed result which is a user-confirmed draft`() {
        val result = runtime.submitForm(
            fixture(),
            NativeFormSubmission(
                formId = "opening-form",
                values = mapOf("name" to listOf("Mira"), "reasons" to listOf("family", "chat")),
            ),
            userName = "Traveler",
            characterName = "Mara",
        ) as NativeFormSubmissionResult.Draft

        assertEquals("Traveler meets Mara as Mira\nReasons: family、chat", result.text)
    }

    @Test
    fun `invalid form cannot produce a partial result`() {
        val result = runtime.submitForm(
            fixture(),
            NativeFormSubmission("opening-form", mapOf("name" to listOf(""), "reasons" to listOf("invented"))),
        )

        assertTrue(result is NativeFormSubmissionResult.Rejected)
    }

    @Test
    fun `assistant adapter updates only complete whitelisted scalar values`() {
        val adaptation = fixture()
        val initial = runtime.initialState(adaptation)
        val source = """
            正文中的 _.set('世界.日期', 1, 99); 不应执行。
            <UpdateVariable>
              _.set('世界.日期', 1, 2);
              _.set('世界.地点', '家', '客厅');
              _.set('世界.日期', 2, fetch('bad'));
              _.set('未知.字段', 0, 7);
            </UpdateVariable>
        """.trimIndent()

        val result = runtime.ingestAssistantMessage(adaptation, source, initial)

        assertEquals(2, result.appliedUpdates)
        assertEquals(2.0, (result.runtimeState.conversationState.values.getValue("world-day") as JsonPrimitive).double, 0.0)
        assertEquals("客厅", (result.runtimeState.conversationState.values.getValue("world-location") as JsonPrimitive).content)
        assertEquals(1.0, (initial.conversationState.values.getValue("world-day") as JsonPrimitive).double, 0.0)
    }

    @Test
    fun `json patch adapter accepts only one complete envelope of whitelisted scalar replacements`() {
        val adaptation = fixture().copy(
            assistantStateAdapters = listOf(
                AssistantStateAdapterDefinition(
                    LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1,
                    listOf(
                        AssistantStateMapping("/世界/日期", "world-day"),
                        AssistantStateMapping("/世界/地点", "world-location"),
                    ),
                ),
            ),
        )
        val initial = runtime.initialState(adaptation)
        val source = """
            正文中的 {"op":"replace","path":"/世界/日期","value":99} 不应执行。
            <UpdateVariable>
            <analysis>plain text ignored</analysis>
            <JSONPatch>
            [
              {"op":"replace","path":"/世界/日期","value":2},
              {"op":"replace","path":"/世界/地点","value":"客厅"},
              {"op":"add","path":"/世界/日期","value":7},
              {"op":"replace","path":"/未知","value":8},
              {"op":"replace","path":"/世界/日期","value":{"call":"bad"}}
            ]
            </JSONPatch>
            </UpdateVariable>
        """.trimIndent()

        val result = runtime.ingestAssistantMessage(adaptation, source, initial)

        assertEquals(2, result.appliedUpdates)
        assertEquals(2.0, (result.runtimeState.conversationState.values.getValue("world-day") as JsonPrimitive).double, 0.0)
        assertEquals("客厅", (result.runtimeState.conversationState.values.getValue("world-location") as JsonPrimitive).content)
    }

    @Test
    fun `json patch adapter rejects incomplete or ambiguous envelopes atomically`() {
        val adaptation = fixture().copy(
            assistantStateAdapters = listOf(
                AssistantStateAdapterDefinition(
                    LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1,
                    listOf(AssistantStateMapping("/世界/日期", "world-day")),
                ),
            ),
        )
        val initial = runtime.initialState(adaptation)
        val ambiguous = """
            <UpdateVariable><JSONPatch>[{"op":"replace","path":"/世界/日期","value":2}]</JSONPatch></UpdateVariable>
            <UpdateVariable><JSONPatch>[{"op":"replace","path":"/世界/日期","value":3}]</JSONPatch></UpdateVariable>
        """.trimIndent()

        val incomplete = runtime.ingestAssistantMessage(adaptation, "<UpdateVariable><JSONPatch>[]", initial)
        val duplicated = runtime.ingestAssistantMessage(adaptation, ambiguous, initial)

        assertEquals(0, incomplete.appliedUpdates)
        assertEquals(0, duplicated.appliedUpdates)
        assertEquals(initial, incomplete.runtimeState)
        assertEquals(initial, duplicated.runtimeState)
    }

    @Test
    fun `set adapter rejects multiple update envelopes`() {
        val adaptation = fixture()
        val initial = runtime.initialState(adaptation)
        val source = """
            <UpdateVariable>_.set('世界.日期', 1, 2);</UpdateVariable>
            <UpdateVariable>_.set('世界.日期', 2, 3);</UpdateVariable>
        """.trimIndent()

        val result = runtime.ingestAssistantMessage(adaptation, source, initial)

        assertEquals(0, result.appliedUpdates)
        assertEquals(initial, result.runtimeState)
    }

    @Test
    fun `declared assistant envelope is removed from narrative but retained for state ingestion`() {
        val adaptation = fixture().copy(
            assistantStateAdapters = listOf(
                AssistantStateAdapterDefinition(
                    LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1,
                    listOf(AssistantStateMapping("/世界/日期", "world-day")),
                ),
            ),
        )
        val source = """
            <UpdateVariable>
            <analysis>日期推进。</analysis>
            <JSONPatch>
            [{"op":"replace","path":"/世界/日期","value":2}]
            </JSONPatch>
            </UpdateVariable>

            正文继续。
        """.trimIndent()

        val projection = runtime.projectAssistantMessage(adaptation, source)
        val ingestion = runtime.ingestAssistantMessage(adaptation, source, runtime.initialState(adaptation))

        assertEquals(AssistantStateEnvelopeStatus.STRIPPED, projection.envelopeStatus)
        assertEquals("正文继续。", projection.narrativeText)
        assertFalse(projection.narrativeText.contains("UpdateVariable"))
        assertEquals(1, ingestion.appliedUpdates)
        assertEquals(2.0, (ingestion.runtimeState.conversationState.values.getValue("world-day") as JsonPrimitive).double, 0.0)
    }

    @Test
    fun `streaming assistant envelope is withheld until complete and malformed final syntax remains diagnosable`() {
        val adaptation = fixture().copy(
            assistantStateAdapters = listOf(
                AssistantStateAdapterDefinition(
                    LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1,
                    listOf(AssistantStateMapping("/世界/日期", "world-day")),
                ),
            ),
        )

        val partialTag = runtime.projectAssistantMessage(adaptation, "<UpdateVari")
        val partialEnvelope = runtime.projectAssistantMessage(
            adaptation,
            "正文\n<UpdateVariable><JSONPatch>[]",
        )
        val malformed = "<UpdateVariable><JSONPatch>[]</UpdateVariable>正文"
        val malformedProjection = runtime.projectAssistantMessage(adaptation, malformed)

        assertEquals(AssistantStateEnvelopeStatus.PENDING, partialTag.envelopeStatus)
        assertEquals("", partialTag.narrativeText)
        assertEquals(AssistantStateEnvelopeStatus.PENDING, partialEnvelope.envelopeStatus)
        assertEquals("正文", partialEnvelope.narrativeText)
        assertEquals(AssistantStateEnvelopeStatus.INVALID, malformedProjection.envelopeStatus)
        assertEquals(malformed, malformedProjection.narrativeText)
    }

    @Test
    fun `ordinary assistant prose is unchanged when no state update is needed`() {
        val source = "没有变化，继续正文。"

        val projection = runtime.projectAssistantMessage(fixture(), source)

        assertEquals(AssistantStateEnvelopeStatus.NONE, projection.envelopeStatus)
        assertEquals(source, projection.narrativeText)
    }

    @Test
    fun `state-first envelope remains ingestible before a long narrative`() {
        val adaptation = fixture().copy(
            assistantStateAdapters = listOf(
                AssistantStateAdapterDefinition(
                    LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1,
                    listOf(AssistantStateMapping("/世界/日期", "world-day")),
                ),
            ),
        )
        val source = """
            <UpdateVariable><JSONPatch>[{"op":"replace","path":"/世界/日期","value":2}]</JSONPatch></UpdateVariable>
            ${"长".repeat(140 * 1024)}
        """.trimIndent()

        val ingestion = runtime.ingestAssistantMessage(adaptation, source, runtime.initialState(adaptation))
        val projection = runtime.projectAssistantMessage(adaptation, source)

        assertEquals(1, ingestion.appliedUpdates)
        assertEquals(2.0, (ingestion.runtimeState.conversationState.values.getValue("world-day") as JsonPrimitive).double, 0.0)
        assertEquals(AssistantStateEnvelopeStatus.STRIPPED, projection.envelopeStatus)
        assertEquals(140 * 1024, projection.narrativeText.length)
    }

    @Test
    fun `form marker matching is a fixed message lifecycle not a trigger catalog`() {
        assertTrue(fixture().forms.single().matchesMessage("before <GAMESTART/> after"))
    }

    private fun fixture() = NativeAdaptation(
        sourceSha256 = "a".repeat(64),
        state = listOf(
            ConversationStateDefinition("world-day", type = ConversationStateValueType.NUMBER, initialValue = JsonPrimitive(1)),
            ConversationStateDefinition("world-location", type = ConversationStateValueType.STRING, initialValue = JsonPrimitive("家")),
        ),
        assistantStateAdapters = listOf(
            AssistantStateAdapterDefinition(
                LegacyStateDialect.UPDATE_VARIABLE_SET_V1,
                listOf(
                    AssistantStateMapping("世界.日期", "world-day"),
                    AssistantStateMapping("世界.地点", "world-location"),
                ),
            ),
        ),
        forms = listOf(
            NativeFormView(
                id = "opening-form",
                title = "Opening",
                marker = "<GAMESTART/>",
                fields = listOf(
                    NativeFormField("name", NativeFormFieldType.TEXT, "Name", required = true),
                    NativeFormField(
                        "reasons",
                        NativeFormFieldType.MULTI_SELECT,
                        "Reasons",
                        options = listOf(NativeFormOption("family"), NativeFormOption("chat")),
                    ),
                ),
                draftTemplate = "{{user}} meets {{char}} as {{form.name}}\nReasons: {{form.reasons}}",
            ),
        ),
    )
}
