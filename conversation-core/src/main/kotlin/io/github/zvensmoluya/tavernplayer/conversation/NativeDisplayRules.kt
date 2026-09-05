package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.RegexDefinition

/** A matched native form owns only the audited source display rules it replaces. */
internal object NativeDisplayRules {
    fun forMessage(character: CharacterSnapshot, sourceText: String, openingSourceIndex: Int?): List<RegexDefinition> {
        val replaced = character.nativeAdaptation?.forms.orEmpty()
            .filter { it.matchesMessage(sourceText, openingSourceIndex) }
            .flatMap { it.replacedDisplayRegexIds }.toSet()
        return character.regexScripts.filterNot { rule ->
            rule.id in replaced && rule.markdownOnly && !rule.promptOnly &&
                character.regexScripts.count { it.id == rule.id } == 1
        }
    }
}
