package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.*
import org.junit.Assert.*
import org.junit.Test

class NativeDisplayRulesTest {
    private val rules = listOf(
        RegexDefinition("form-ui", "表单", "<Form/>", "<div>Legacy UI</div>", placements = setOf(RegexPlacement.AI_OUTPUT, RegexPlacement.USER_INPUT), markdownOnly = true),
        RegexDefinition("prose", "正文", "剧情", "正文", placements = setOf(RegexPlacement.AI_OUTPUT), markdownOnly = true),
        RegexDefinition("prompt", "提示", "剧情", "模型剧情", placements = setOf(RegexPlacement.AI_OUTPUT), promptOnly = true),
        RegexDefinition("storage", "存储", "剧情", "存储剧情", placements = setOf(RegexPlacement.AI_OUTPUT)),
    )
    private val form = NativeFormView("form", "开始", fields = listOf(NativeFormField("note", NativeFormFieldType.TEXT, "要求")),
        draftTemplate = "{{form.note}}", setup = NativeSetupContract(), openingIndices = listOf(0), replacedDisplayRegexIds = listOf("form-ui"))
    private val character = CharacterAsset("card", name = "向导", regexScripts = rules,
        nativeAdaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), forms = listOf(form))).snapshot()
    private val compiler = PromptCompiler()
    private fun display(index: Int?, role: MessageRole = MessageRole.ASSISTANT, card: CharacterSnapshot = character): String {
        val preset = BuiltInPresets.default.copy(regexScripts = listOf(
            RegexDefinition("form-ui", "独立预设规则", "剧情", "剧情！", placements = setOf(RegexPlacement.AI_OUTPUT), markdownOnly = true)))
        return (compiler.projectDisplayText("<Form/>剧情", role, card, Persona("p", "旅人"), preset,
            ConversationRuntimeState(), emptyList(), "c", "g", "m", openingSourceIndex = index) as TextExpansionResult.Success).text
    }

    @Test fun `only the matched native opening replaces its declared character display rule`() {
        assertEquals("<Form/>正文！", display(0))
        assertEquals("<div>Legacy UI</div>正文！", display(1))
        assertEquals("<div>Legacy UI</div>正文！", display(null))
        assertEquals("<div>Legacy UI</div>正文！", display(0, card = character.copy(nativeAdaptation = null)))
        assertEquals("<div>Legacy UI</div>剧情", display(0, MessageRole.USER))
        assertEquals(rules, character.regexScripts)
        listOf(RegexProjection.PROMPT to "模型剧情", RegexProjection.STORAGE to "存储剧情").forEach { (projection, expected) ->
            val result = compiler.projectAssistantText("<Form/>剧情", projection, character, Persona("p", "旅人"), BuiltInPresets.default,
                ConversationRuntimeState(), emptyList(), "c", "g", "m") as TextExpansionResult.Success
            assertEquals("<Form/>$expected", result.text)
        }
    }

    @Test fun `malformed references cannot suppress prompt storage or ambiguous source rules`() {
        val invalid = character.copy(nativeAdaptation = character.nativeAdaptation!!.copy(forms = listOf(form.copy(
            replacedDisplayRegexIds = listOf("prompt", "storage", "missing", "form-ui")))), regexScripts = rules + rules.first())
        assertEquals(invalid.regexScripts, NativeDisplayRules.forMessage(invalid, "<Form/>", 0))
    }
}
