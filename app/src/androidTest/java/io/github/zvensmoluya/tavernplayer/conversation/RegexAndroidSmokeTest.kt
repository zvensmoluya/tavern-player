package io.github.zvensmoluya.tavernplayer.conversation

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.tavernplayer.content.RegexDefinition
import io.github.zvensmoluya.tavernplayer.content.RegexPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegexAndroidSmokeTest {
    @Test
    fun macroAndCharacterRegexCompileOnAndroidRuntime() {
        val character = CharacterAsset(id = "card", name = "少女").snapshot()
        val context = MacroContext(character, Persona("persona", "旅人"), conversationId = "android-smoke")
        val rule = RegexDefinition(
            id = "unicode",
            name = "Unicode",
            findRegex = "/(少女)/iu",
            replaceString = "[$1]-{{char}}",
            placements = setOf(RegexPlacement.AI_OUTPUT),
        )

        val regexed = CharacterRegexEngine().apply(
            text = "少女",
            rules = listOf(rule),
            placement = RegexPlacement.AI_OUTPUT,
            projection = RegexProjection.STORAGE,
            context = context,
            transaction = MacroTransaction(),
        )
        val expanded = PromptCompiler().expandConversationText("{{char}}", character, context.persona)
            as TextExpansionResult.Success

        assertEquals("[少女]-少女", regexed.text)
        assertEquals("少女", expanded.text)
    }

    @Test
    fun variableLookbehindRewriteCompletesWithinProductionFuseOnAndroidRuntime() {
        val character = CharacterAsset(id = "card", name = "Aster").snapshot()
        val context = MacroContext(character, Persona("persona", "Traveler"), conversationId = "lookbehind-smoke")
        val rule = RegexDefinition(
            id = "lookbehind",
            name = "Lookbehind",
            findRegex = "/(?<=(语气|语调|声音)([\\u4e00-\\u9fa5]+?))([,，]?)(得?)(如同|像|仿佛).*?(?=[。，,])/g",
            replaceString = "",
            placements = setOf(RegexPlacement.AI_OUTPUT),
        )

        val result = CharacterRegexEngine().apply(
            text = "她的语气轻柔，像春风一样，令人安心。",
            rules = listOf(rule),
            placement = RegexPlacement.AI_OUTPUT,
            projection = RegexProjection.STORAGE,
            context = context,
            transaction = MacroTransaction(),
        )

        assertEquals("她的语气轻柔，令人安心。", result.text)
        assertFalse(result.diagnostics.any { it.code == "REGEX_TIMEOUT" })
    }
}
