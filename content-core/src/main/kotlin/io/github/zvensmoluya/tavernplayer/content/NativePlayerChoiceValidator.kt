package io.github.zvensmoluya.tavernplayer.content

object NativePlayerChoiceValidator {
    fun validate(adaptation: NativeAdaptation): List<NativeAdaptationValidationIssue> {
        val issues = mutableListOf<NativeAdaptationValidationIssue>()
        fun reject(path: String, message: String) { issues += NativeAdaptationValidationIssue(path, "INVALID_PLAYER_CHOICE", message) }
        if (adaptation.playerChoices.size > 8) reject("playerChoices", "玩家选择不能超过 8 项")
        val ids = mutableSetOf<String>()
        adaptation.playerChoices.forEachIndexed { index, choice ->
            val path = "playerChoices[$index]"
            if (!Regex("[a-z][a-z0-9-]{0,63}").matches(choice.id) || !ids.add(choice.id)) reject(path, "玩家选择标识必须有效且唯一")
            if (choice.title.isBlank() || choice.title.length > 96 || choice.description.length > 1024 ||
                choice.unavailableLabel.isBlank() || choice.unavailableLabel.length > 160 || choice.draft.isBlank() || choice.draft.length > 4096) {
                reject(path, "玩家选择必须提供有界的标题、不可用说明和草稿")
            }
            val gate = adaptation.state.singleOrNull { it.key == choice.availabilityStateKey }
            val target = adaptation.state.singleOrNull { it.key == choice.stateKey }
            if (gate?.type != ConversationStateValueType.STRING || gate.allowedStrings.isEmpty() ||
                choice.availableValues.isEmpty() || choice.availableValues.size > 32 ||
                choice.availableValues.distinct().size != choice.availableValues.size || choice.availableValues.any { it !in gate.allowedStrings }) {
                reject(path, "可用性只允许读取一个已声明的枚举状态")
            }
            if (target?.type != ConversationStateValueType.STRING || target.allowedStrings.isEmpty() || choice.stateValue !in target.allowedStrings ||
                adaptation.assistantStateAdapters.any { adapter -> adapter.mappings.any { it.targetStateKey == choice.stateKey && !it.writable } } ||
                adaptation.progressions.any { it.stageStateKey == choice.stateKey }) {
                reject(path, "玩家选择只允许写入一个非只读、非派生的枚举状态")
            }
        }
        return issues
    }
}
