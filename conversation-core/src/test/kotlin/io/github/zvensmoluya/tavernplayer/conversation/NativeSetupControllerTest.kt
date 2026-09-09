package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NativeSetupControllerTest {
    private val controller = NativeSetupController()
    private val form = NativeFormView(
        id = "setup", title = "开局", marker = "<setup/>",
        fields = listOf(NativeFormField("place", NativeFormFieldType.SINGLE_SELECT, "地点", required = true,
            options = listOf(NativeFormOption("海边", setup = NativeSetupPayload(worldBookOverrides = listOf(
                NativeSetupWorldBookOverride("book", "entry", true),
            )))))),
        draftTemplate = "{{user}}在{{form.place}}见到{{char}}", setup = NativeSetupContract(stateFields = mapOf("location" to "place")),
    )
    private val adaptation = NativeAdaptation(sourceSha256 = "a".repeat(64),
        state = listOf(ConversationStateDefinition("location", type = ConversationStateValueType.STRING, initialValue = JsonPrimitive("家"))),
        forms = listOf(form))
    private fun record(adaptation: NativeAdaptation = this.adaptation): ConversationRecord {
        val character = CharacterAsset("card", name = "角色", firstMessage = form.marker, alternateFirstMessages = listOf("另一开场"),
            nativeAdaptation = adaptation, worldBooks = listOf(WorldBookDefinition("book", entries = listOf(WorldBookEntryDefinition("entry", enabled = false)))))
        val state = NativeAdaptationRuntime().initialState(adaptation)
        return ConversationRecord(id = "conversation", character = character.snapshot(), persona = Persona("user", "旅人"),
            turns = listOf(ConversationTurn("opening", MessageRole.ASSISTANT, listOf(
                MessageVariant("opening-1", ConversationMessage("m1", MessageRole.ASSISTANT, form.marker, "角色"), runtimeStateAfter = state, openingSourceIndex = 0),
                MessageVariant("opening-2", ConversationMessage("m2", MessageRole.ASSISTANT, "另一开场", "角色"), runtimeStateAfter = state, openingSourceIndex = 1),
            ))), runtimeState = state, createdAtEpochMillis = 0, updatedAtEpochMillis = 0)
    }
    private val submission = NativeFormSubmission("setup", mapOf("place" to listOf("海边")))

    @Test fun `setup commits state worldbook and draft once and survives serialization with all opening checkpoints`() {
        val before = record()
        val committed = (controller.commit(before, submission) as NativeSetupResult.Committed).record
        assertEquals(JsonPrimitive("家"), before.runtimeState.conversationState.values["location"])
        assertEquals("旅人在海边见到角色", committed.draft)
        val restored = Json.decodeFromString<ConversationRecord>(Json.encodeToString(committed))
        assertEquals(committed, restored)
        val checkpoints = restored.turns.single().variants.flatMap { listOf(it.runtimeStateBefore, it.projectionRuntimeStateBefore, it.runtimeStateAfter) }
        (checkpoints + restored.runtimeState).forEach { state ->
            assertEquals(JsonPrimitive("海边"), state!!.conversationState.values["location"])
            assertEquals(true, state.worldBookActivationOverrides.entries["book"]?.get("entry"))
            assertEquals("setup", state.setupCommit?.formId)
        }
        assertTrue(controller.commit(restored, submission) is NativeSetupResult.Rejected)
    }

    @Test fun `invalid snapshot references conflicting targets and late setup fail without partial results`() {
        val invalidOption = form.fields.single().options.single().copy(setup = NativeSetupPayload(worldBookOverrides = listOf(NativeSetupWorldBookOverride("book", "missing", true))))
        val invalid = adaptation.copy(forms = listOf(form.copy(fields = listOf(form.fields.single().copy(options = listOf(invalidOption))))))
        assertTrue(controller.commit(record(invalid), submission) is NativeSetupResult.Rejected)
        val conflict = invalidOption.copy(setup = NativeSetupPayload(stateValues = mapOf("location" to JsonPrimitive("山上"))))
        val conflicting = adaptation.copy(forms = listOf(form.copy(fields = listOf(form.fields.single().copy(options = listOf(conflict))))))
        assertTrue(controller.commit(record(conflicting), submission) is NativeSetupResult.Rejected)
        val started = record().let { it.copy(turns = it.turns + ConversationTurn("user", MessageRole.USER, listOf(MessageVariant("u", ConversationMessage("u", MessageRole.USER, "开始", "旅人"))))) }
        assertTrue(controller.commit(started, submission) is NativeSetupResult.Rejected)
        assertTrue(controller.commit(record(), submission.copy(values = mapOf("place" to listOf("未知")))) is NativeSetupResult.Rejected)
    }

    @Test fun `ordinary form has empty defaults and does not recursively expand inserted identities`() {
        val plain = NativeFormView("plain", "预约", "<appointment/>", fields = listOf(
            NativeFormField("name", NativeFormFieldType.TEXT, "姓名", emptyText = "匿名访客")), draftTemplate = "{{user}}: {{form.name}}")
        val adaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), forms = listOf(plain))
        val runtime = NativeAdaptationRuntime()
        assertEquals(NativeFormSubmissionResult.Draft("旅人: 匿名访客"), runtime.submitForm(adaptation, NativeFormSubmission("plain", emptyMap()), "旅人"))
        assertEquals(NativeFormSubmissionResult.Draft("旅人: {{char}}"), runtime.submitForm(adaptation, NativeFormSubmission("plain", mapOf("name" to listOf("{{char}}"))), "旅人", "角色"))
    }

    @Test fun `setup selects a source opening and commits its checkpoint together with the draft`() {
        val native = adaptation.copy(forms = listOf(form.copy(marker = "", openingIndices = listOf(0),
            setup = form.setup!!.copy(openingIndex = 1))))
        val before = record(native).let { record -> record.copy(turns = record.turns.map { turn ->
            turn.copy(variants = turn.variants.map { variant ->
                if (variant.openingSourceIndex == 1) variant.copy(runtimeStateAfter = variant.runtimeStateAfter!!.copy(generationIndex = 4)) else variant
            })
        }) }
        val saved = (controller.commit(before, submission) as NativeSetupResult.Committed).record
        assertEquals(1, saved.turns.single().selected.openingSourceIndex)
        assertEquals(4, saved.runtimeState.generationIndex)
        assertEquals("旅人在海边见到角色", saved.draft)
        assertEquals(JsonPrimitive("海边"), saved.runtimeState.conversationState.values["location"])
        assertEquals(saved, Json.decodeFromString<ConversationRecord>(Json.encodeToString(saved)))
        assertEquals(0, before.turns.single().selected.openingSourceIndex)
        assertTrue(before.draft.isEmpty())
        assertTrue(controller.commit(saved, submission) is NativeSetupResult.Rejected)
        assertFalse(native.forms.single().matchesMessage(form.marker))
        assertTrue(native.forms.single().matchesMessage("修改后的开场正文", 0))
    }

    @Test fun `setup refuses foreign openings missing targets and occupied drafts atomically`() {
        val native = adaptation.copy(forms = listOf(form.copy(marker = "", openingIndices = listOf(0),
            setup = form.setup!!.copy(openingIndex = 1))))
        val before = record(native)
        val foreign = before.copy(turns = before.turns.map { it.copy(selectedVariantIndex = 1) })
        val missing = before.copy(turns = before.turns.map { it.copy(variants = it.variants.take(1)) })
        val duplicate = before.copy(turns = before.turns.map { it.copy(variants = it.variants + it.variants.last()) })
        val occupied = before.copy(draft = "尚未发送的内容")
        listOf(foreign, missing, duplicate, occupied).forEach {
            assertTrue(controller.commit(it, submission) is NativeSetupResult.Rejected)
            assertNull(it.runtimeState.setupCommit)
            assertEquals(JsonPrimitive("家"), it.runtimeState.conversationState.values["location"])
        }
        assertEquals("尚未发送的内容", occupied.draft)
    }

    @Test fun `legacy state reads use current authoritative state without evaluating scripts`() {
        val native = adaptation.copy(assistantStateAdapters = listOf(AssistantStateAdapterDefinition(
            LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1, listOf(AssistantStateMapping("/世界/地点", "location")))))
        val snapshot = record(native).character
        val state = ConversationRuntimeState(conversationState = ConversationStateSnapshot(mapOf("location" to JsonPrimitive("海边"))))
        val result = PromptCompiler().expandConversationText("{{get_message_variable::stat_data}}\n{{format_message_variable::stat_data}}", snapshot, Persona("p", "旅人"), state) as TextExpansionResult.Success
        val expected = "{\"世界\":{\"地点\":\"海边\"}}"
        assertEquals("$expected\n世界:\n  地点: 海边", result.text)
        assertEquals(state, result.runtimeState)
        assertTrue(result.diagnostics.isEmpty())
    }
}
