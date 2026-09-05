package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NativePlayerChoiceValidatorTest {
    private val choice = NativePlayerChoice("leave", "离开", "离开当前地点。", "place", listOf("城内"), "不在城内", "place", "城外", "我走出城门。")
    private val native = NativeAdaptation(sourceSha256 = "a".repeat(64), state = listOf(
        ConversationStateDefinition("place", type = ConversationStateValueType.STRING, initialValue = JsonPrimitive("城内"), allowedStrings = listOf("城内", "城外"))),
        playerChoices = listOf(choice))

    @Test fun `player choice has one finite gate one enum assignment and a bounded draft`() {
        assertTrue(NativeAdaptationValidator().validate(native).valid)
        listOf(choice.copy(availableValues = emptyList()), choice.copy(availableValues = listOf("未知")),
            choice.copy(stateValue = "未知"), choice.copy(stateKey = "missing"), choice.copy(draft = ""), choice.copy(draft = "x".repeat(4097)),
            choice.copy(availabilityStateKey = "missing"), choice.copy(id = "invalid/id")).forEach {
            assertTrue(NativePlayerChoiceValidator.validate(native.copy(playerChoices = listOf(it))).isNotEmpty())
        }
        assertTrue(NativePlayerChoiceValidator.validate(native.copy(playerChoices = listOf(choice, choice))).isNotEmpty())
        assertTrue(NativePlayerChoiceValidator.validate(native.copy(state = native.state.map { it.copy(allowedStrings = emptyList()) })).isNotEmpty())
    }

    @Test fun `player choices cannot bypass readonly or derived states`() {
        val readonly = native.copy(assistantStateAdapters = listOf(AssistantStateAdapterDefinition(LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1,
            listOf(AssistantStateMapping("/place", "place", writable = false)))))
        assertTrue(NativePlayerChoiceValidator.validate(readonly).isNotEmpty())
        assertTrue(NativePlayerChoiceValidator.validate(native.copy(progressions = listOf(NativeProgressionDefinition("score", "place", emptyList())))).isNotEmpty())
    }
}
