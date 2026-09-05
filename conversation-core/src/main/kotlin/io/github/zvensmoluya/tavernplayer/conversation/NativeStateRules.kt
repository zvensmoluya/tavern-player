package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** 固定数值约束与阶段表，没有表达式、回调或规则递归。类型限制排除了派生状态之间的依赖。 */
object NativeStateRules {
    fun apply(adaptation: NativeAdaptation?, state: ConversationStateSnapshot): ConversationStateSnapshot {
        if (adaptation == null) return state
        val values = state.values.toMutableMap()
        adaptation.state.forEach { definition ->
            val range = definition.numberRange ?: return@forEach
            val number = (values[definition.key] as? JsonPrimitive)?.doubleOrNull ?: return@forEach
            values[definition.key] = JsonPrimitive(number.coerceIn(range.min, range.max))
        }
        adaptation.progressions.forEach { progression ->
            val number = (values[progression.valueStateKey] as? JsonPrimitive)?.doubleOrNull ?: return@forEach
            val level = progression.levels.lastOrNull { number >= it.minValue } ?: progression.levels.firstOrNull() ?: return@forEach
            val unlocked = level.unlockStateKey == null || (values[level.unlockStateKey] as? JsonPrimitive)?.booleanOrNull == true
            values[progression.stageStateKey] = JsonPrimitive(if (unlocked) level.label else level.lockedLabel)
        }
        return state.copy(values = values)
    }
}
