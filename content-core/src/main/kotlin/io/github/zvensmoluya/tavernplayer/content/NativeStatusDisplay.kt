package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

data class NativeStatusValue(val text: String, val recorded: String, val unavailable: Boolean = false) {
    val adjusted: Boolean get() = text != recorded
}

object NativeStatusDisplay {
    fun validate(adaptation: NativeAdaptation): List<NativeAdaptationValidationIssue> {
        val issues = mutableListOf<NativeAdaptationValidationIssue>()
        adaptation.status?.items?.forEachIndexed { index, item ->
            fun issue(message: String) { issues += NativeAdaptationValidationIssue("status.items[$index]", "INVALID_STATUS_DISPLAY", message) }
            if (item.group.length > 96) issue("状态分组名不能超过 96 字符")
            val display = item.enumDisplay ?: return@forEachIndexed
            val gate = adaptation.state.singleOrNull { it.key == display.gateStateKey }
            val target = adaptation.state.singleOrNull { it.key == item.stateKey }
            if (gate?.type != ConversationStateValueType.STRING || target?.type != ConversationStateValueType.STRING ||
                gate.allowedStrings.size !in 1..16 || target.allowedStrings.size !in 1..16) {
                issue("状态显示查表仅接受两个各有 1 到 16 个选项的字符串枚举")
                return@forEachIndexed
            }
            val gates = gate.allowedStrings.toSet()
            val values = target.allowedStrings.toSet()
            if (display.values.keys != gates || display.values.values.any { row -> row.keys != values || row.values.any { it !in values } }) {
                issue("显示查表必须完整覆盖两个枚举，结果必须属于原状态枚举")
            }
        }
        return issues
    }

    fun value(item: NativeStatusItem, state: Map<String, JsonElement>): NativeStatusValue {
        val primitive = state[item.stateKey] as? JsonPrimitive
        val raw = primitive?.content.orEmpty()
        val number = primitive?.takeUnless { it.isString }?.doubleOrNull
        val recorded = if (number != null && raw.endsWith(".0")) raw.removeSuffix(".0") else raw
        val table = item.enumDisplay ?: return NativeStatusValue(recorded, recorded)
        val gate = state[table.gateStateKey] as? JsonPrimitive
        val projected = if (gate?.isString == true && primitive?.isString == true) table.values[gate.content]?.get(raw) else null
        return NativeStatusValue(projected ?: recorded, recorded, unavailable = projected == null)
    }
}
