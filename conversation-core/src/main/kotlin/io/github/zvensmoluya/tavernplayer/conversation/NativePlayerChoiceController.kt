package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.NativePlayerChoice
import io.github.zvensmoluya.tavernplayer.content.NativePlayerChoiceValidator
import java.util.UUID
import kotlinx.serialization.json.JsonPrimitive

data class NativePlayerChoiceOption(val choice: NativePlayerChoice, val unavailableReason: String?)

data class NativePlayerChoicePreview(
    val id: String,
    val conversationId: String,
    val turnId: String,
    val anchor: MessageVariant,
    val runtimeState: ConversationRuntimeState,
    val existingDraft: String,
    val choice: NativePlayerChoice,
    val stateLabel: String,
    val previousValue: String,
)

sealed interface NativePlayerChoicePreparation {
    data class Ready(val preview: NativePlayerChoicePreview) : NativePlayerChoicePreparation
    data class Rejected(val message: String) : NativePlayerChoicePreparation
}

sealed interface NativePlayerChoiceResult {
    data class Committed(val record: ConversationRecord) : NativePlayerChoiceResult
    data class Rejected(val message: String) : NativePlayerChoiceResult
}

/** 只能由玩家预览并确认，不由生成结果、定时器或其他能力调用。 */
class NativePlayerChoiceController(private val idFactory: () -> String = { UUID.randomUUID().toString() }) {
    fun options(record: ConversationRecord): List<NativePlayerChoiceOption> = record.character.nativeAdaptation?.playerChoices.orEmpty().map { choice ->
        NativePlayerChoiceOption(choice, unavailable(record, choice))
    }

    private fun unavailable(record: ConversationRecord, choice: NativePlayerChoice): String? {
        val turn = record.turns.lastOrNull()
        if (turn?.role != MessageRole.ASSISTANT || turn.selected.status != PersistedMessageStatus.COMPLETE) return "请先完成当前回复"
        if (turn.selected.playerChoiceCommits.size >= 64) return "请先继续对话，再作新的选择"
        val gate = record.runtimeState.conversationState.values[choice.availabilityStateKey] as? JsonPrimitive
        if (gate?.isString != true || gate.content !in choice.availableValues) return choice.unavailableLabel
        if (record.draft.isNotBlank() && record.draft != choice.draft) return "输入框已有内容，请先保存或清空草稿"
        return null
    }

    fun prepare(record: ConversationRecord, choiceId: String): NativePlayerChoicePreparation {
        fun reject(message: String) = NativePlayerChoicePreparation.Rejected(message)
        val native = record.character.nativeAdaptation ?: return reject("没有可用的玩家选择")
        val validation = NativePlayerChoiceValidator.validate(native)
        if (validation.isNotEmpty()) return reject(validation.first().message)
        val choice = native.playerChoices.singleOrNull { it.id == choiceId } ?: return reject("找不到这个选择")
        unavailable(record, choice)?.let { return reject(it) }
        val value = record.runtimeState.conversationState.values[choice.stateKey] as? JsonPrimitive
        val definition = native.state.single { it.key == choice.stateKey }
        if (value?.isString != true || value.content !in definition.allowedStrings) return reject("当前状态无效，无法提交选择")
        val turn = record.turns.last()
        return NativePlayerChoicePreparation.Ready(NativePlayerChoicePreview(idFactory(), record.id, turn.id, turn.selected,
            record.runtimeState, record.draft, choice, definition.label.ifBlank { definition.key }, value.content))
    }

    fun commit(record: ConversationRecord, preview: NativePlayerChoicePreview): NativePlayerChoiceResult {
        fun reject(message: String) = NativePlayerChoiceResult.Rejected(message)
        if (record.turns.any { turn -> turn.variants.any { variant -> variant.playerChoiceCommits.any { it.id == preview.id } } }) {
            return reject("这个选择已经保存")
        }
        val prepared = prepare(record, preview.choice.id)
        if (prepared is NativePlayerChoicePreparation.Rejected) return reject(prepared.message)
        val current = (prepared as NativePlayerChoicePreparation.Ready).preview.copy(id = preview.id)
        if (current != preview) return reject("对话、状态或草稿已变化，请重新查看并确认")
        val choice = current.choice
        val state = record.runtimeState.copy(conversationState =
            record.runtimeState.conversationState.applying(ConversationStatePatch(mapOf(choice.stateKey to JsonPrimitive(choice.stateValue)))))
        val receipt = ConversationPlayerChoiceCommit(preview.id, choice.id, choice.title, choice.stateKey, current.previousValue, choice.stateValue, choice.draft)
        val turns = record.turns.map { turn ->
            if (turn.id != current.turnId) turn else turn.copy(variants = turn.variants.map { variant ->
                if (variant.id != current.anchor.id) variant else variant.copy(runtimeStateAfter = state, playerChoiceCommits = variant.playerChoiceCommits + receipt)
            })
        }
        return NativePlayerChoiceResult.Committed(record.copy(turns = turns, runtimeState = state, draft = choice.draft,
            choiceDraft = ConversationChoiceDraft(current.anchor.id, receipt.id, choice.draft)))
    }
}
