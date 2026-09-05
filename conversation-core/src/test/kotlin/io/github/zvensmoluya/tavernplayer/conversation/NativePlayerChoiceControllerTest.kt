package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NativePlayerChoiceControllerTest {
    private val choice = NativePlayerChoice("retreat", "返回营地", "结束这次探索。", "phase", listOf("探索中"), "尚未出发", "outcome", "已撤离", "我决定返回营地，整理沿途观察。")
    private val native = NativeAdaptation(sourceSha256 = "a".repeat(64), state = listOf(
        ConversationStateDefinition("phase", "阶段", ConversationStateValueType.STRING, initialValue = JsonPrimitive("探索中"), allowedStrings = listOf("探索中", "营地")),
        ConversationStateDefinition("outcome", "行动结果", ConversationStateValueType.STRING, initialValue = JsonPrimitive("进行中"), allowedStrings = listOf("进行中", "已撤离"))),
        playerChoices = listOf(choice))
    private val controller = NativePlayerChoiceController { "confirmation" }
    private fun record(): ConversationRecord {
        val state = NativeAdaptationRuntime().initialState(native)
        val variants = (0..1).map { index -> MessageVariant("v$index", ConversationMessage("m$index", MessageRole.ASSISTANT, "前方出现了一条岔路。", "向导"),
            runtimeStateBefore = state, projectionRuntimeStateBefore = state, runtimeStateAfter = state) }
        return ConversationRecord(id = "c", character = io.github.zvensmoluya.tavernplayer.content.CharacterAsset("card", name = "向导", nativeAdaptation = native).snapshot(),
            persona = Persona("p", "旅人"), turns = listOf(ConversationTurn("t", MessageRole.ASSISTANT, variants)), runtimeState = state,
            createdAtEpochMillis = 0, updatedAtEpochMillis = 0)
    }
    private fun preview(record: ConversationRecord) = (controller.prepare(record, choice.id) as NativePlayerChoicePreparation.Ready).preview
    private fun commit(record: ConversationRecord) = (controller.commit(record, preview(record)) as NativePlayerChoiceResult.Committed).record

    @Test fun `preview is inert and confirmation commits one branch without changing narrative or sending`() {
        val before = record()
        val preview = preview(before)
        assertEquals("", before.draft)
        assertTrue(before.turns.single().selected.playerChoiceCommits.isEmpty())
        val saved = (controller.commit(before, preview) as NativePlayerChoiceResult.Committed).record
        assertEquals(1, saved.turns.size)
        assertEquals(before.turns.single().selected.message, saved.turns.single().selected.message)
        assertEquals(before.turns.single().selected.runtimeStateBefore, saved.turns.single().selected.runtimeStateBefore)
        assertEquals(before.turns.single().variants[1], saved.turns.single().variants[1])
        assertEquals(JsonPrimitive("已撤离"), saved.runtimeState.conversationState.values["outcome"])
        assertEquals(saved.runtimeState, saved.turns.single().selected.runtimeStateAfter)
        assertEquals(choice.draft, saved.draft)
        assertEquals("confirmation", saved.turns.single().selected.playerChoiceCommits.single().id)
        assertEquals(saved, Json.decodeFromString<ConversationRecord>(Json.encodeToString(saved)))
        assertTrue(controller.commit(saved, preview) is NativePlayerChoiceResult.Rejected)
        val result = PromptCompiler().compile(NormalGenerationInput(saved.character, saved.persona,
            saved.turns.map { it.selected.message } + ConversationMessage("u", MessageRole.USER, saved.draft, "旅人"),
            BuiltInPresets.default, runtimeState = saved.runtimeState)) as CompilationResult.Success
        assertTrue(result.plan.messages.any { it.origin.sourceIds == listOf("conversationState") && it.content.contains("\"outcome\":\"已撤离\"") })
        assertTrue(result.plan.messages.any { it.content == choice.draft })
    }

    @Test fun `unavailable states occupied drafts and unfinished replies cannot prepare a choice`() {
        val before = record()
        val invalid = listOf(
            before.copy(draft = "我还在输入"),
            before.copy(runtimeState = before.runtimeState.copy(conversationState = ConversationStateSnapshot(mapOf("phase" to JsonPrimitive("营地"))))),
            before.copy(turns = before.turns.map { it.copy(role = MessageRole.USER) }),
        ) + listOf(PersistedMessageStatus.STREAMING, PersistedMessageStatus.ERROR, PersistedMessageStatus.CANCELLED).map { status ->
            before.copy(turns = before.turns.map { it.copy(variants = listOf(it.selected.copy(status = status))) })
        }
        invalid.forEach { assertTrue(controller.prepare(it, choice.id) is NativePlayerChoicePreparation.Rejected) }
        assertTrue(controller.prepare(before, "unknown") is NativePlayerChoicePreparation.Rejected)
        assertTrue(controller.prepare(before.copy(draft = choice.draft), choice.id) is NativePlayerChoicePreparation.Ready)
    }

    @Test fun `confirmation rejects changes since preview including narrative edits and candidate switches`() {
        val before = record()
        val preview = preview(before)
        val changed = listOf(
            before.copy(id = "another"), before.copy(draft = "新的草稿"),
            before.copy(runtimeState = before.runtimeState.copy(generationIndex = 2)),
            before.copy(turns = before.turns.map { it.copy(selectedVariantIndex = 1) }),
            before.copy(turns = before.turns.map { it.copy(variants = listOf(it.selected.copy(message = it.selected.message.copy(sourceText = "修改后的剧情")))) }),
            before.copy(character = before.character.copy(nativeAdaptation = native.copy(playerChoices = listOf(choice.copy(draft = "新要求")))))
        )
        changed.forEach { assertTrue(controller.commit(it, preview) is NativePlayerChoiceResult.Rejected) }
    }

    @Test fun `unmodified choice drafts follow their source while user edits remain ordinary drafts`() {
        val saved = commit(record())
        fun otherBranch(record: ConversationRecord) = record.copy(turns = record.turns.map { it.copy(selectedVariantIndex = 1) },
            runtimeState = record.turns.single().variants[1].runtimeStateAfter!!).reconcileChoiceDraft()
        val other = otherBranch(saved)
        assertEquals("", other.draft)
        assertNull(other.choiceDraft)
        assertEquals(JsonPrimitive("进行中"), other.runtimeState.conversationState.values["outcome"])
        val back = other.copy(turns = other.turns.map { it.copy(selectedVariantIndex = 0) }, runtimeState = saved.runtimeState).reconcileChoiceDraft()
        assertEquals("", back.draft)
        assertEquals(JsonPrimitive("已撤离"), back.runtimeState.conversationState.values["outcome"])
        assertEquals("自己写下的计划", otherBranch(saved.withDraft("自己写下的计划")).draft)
        val restarted = saved.copy(turns = saved.turns.map { it.copy(variants = listOf(it.selected.copy(playerChoiceCommits = emptyList()))) }).reconcileChoiceDraft()
        assertEquals("", restarted.draft)
        assertNull(saved.withDraft("").choiceDraft)
    }
}
