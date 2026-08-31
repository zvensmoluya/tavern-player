package io.github.zvensmoluya.tavernplayer.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCompilerTest {
    private val compiler = PromptCompiler()

    @Test
    fun `normal turn compiles markers overrides examples history and ST depth order`() {
        val result = compiler.compile(baseInput()) as CompilationResult.Success

        assertEquals(
            listOf(
                MessageRole.SYSTEM to "基础规则：米拉与旅人。\n始终以米拉的身份回应。",
                MessageRole.SYSTEM to "米拉经营一家雨夜旅店。",
                MessageRole.SYSTEM to "沉着、敏锐、友善。",
                MessageRole.SYSTEM to "暴雨夜，旅人推门进入米拉的旅店。",
                MessageRole.SYSTEM to "以下是示例对话。",
                MessageRole.USER to "这里还有空房吗？",
                MessageRole.ASSISTANT to "有，壁炉旁那间还暖着。",
                MessageRole.SYSTEM to "开始一段新的角色对话。",
                MessageRole.ASSISTANT to "门铃轻响。米拉抬头看向旅人：“欢迎。”",
                MessageRole.SYSTEM to "窗外一直下着雨。",
                MessageRole.SYSTEM to "保持克制、自然的叙述。",
                MessageRole.USER to "我想住一晚。",
                MessageRole.SYSTEM to "不要替旅人决定行动。",
            ),
            result.plan.messages.map { it.role to it.content },
        )
        assertEquals(512, result.plan.maxOutputTokens)
        assertEquals("", result.plan.assistantPrefill)
        assertTrue(result.plan.diagnostics.any { it.code == "CONTEXT_BUDGET_NOT_ENFORCED" })
        assertTrue(result.plan.trace.any { it.stage == "depth-injection" && it.sourceIds == listOf("tone") })
        assertFalse(result.plan.messages.any { "unused" in it.content })
    }

    @Test
    fun `disabled main remains an empty locator and is not overridden or sent`() {
        val input = baseInput().let { value ->
            value.copy(
                preset = value.preset.copy(
                    promptOrder = value.preset.promptOrder.map {
                        if (it.identifier == "main") it.copy(enabled = false) else it
                    },
                ),
            )
        }

        val result = compiler.compile(input) as CompilationResult.Success

        assertFalse(result.plan.messages.any { it.origin.sourceIds == listOf("main") })
        assertTrue(result.plan.trace.any { it.decision == "disabled main placeholder" })
    }

    @Test
    fun `unknown macros and missing prompt references block generation`() {
        val macroInput = baseInput().let { value ->
            value.copy(character = value.character.copy(description = "{{getvar::mood}}"))
        }
        val macroFailure = compiler.compile(macroInput) as CompilationResult.Failure
        assertTrue(macroFailure.diagnostics.any { it.code == "UNSUPPORTED_MACRO" })

        val referenceInput = baseInput().let { value ->
            value.copy(
                preset = value.preset.copy(
                    promptOrder = value.preset.promptOrder + PromptOrderEntry("missing"),
                ),
            )
        }
        val referenceFailure = compiler.compile(referenceInput) as CompilationResult.Failure
        assertTrue(referenceFailure.diagnostics.any { it.code == "MISSING_PROMPT_DEFINITION" })
    }

    @Test
    fun `snapshot owns copied character collections`() {
        val alternate = mutableListOf("第一条备用开场")
        val exampleMessages = mutableListOf(ExampleMessage(MessageRole.USER, "旧示例"))
        val asset = baseAsset().copy(
            alternateFirstMessages = alternate,
            examples = listOf(DialogueExample(exampleMessages)),
        )

        val snapshot = asset.snapshot()
        alternate[0] = "已修改"
        exampleMessages[0] = ExampleMessage(MessageRole.USER, "已修改")

        assertEquals("第一条备用开场", snapshot.alternateFirstMessages.single())
        assertEquals("旧示例", snapshot.examples.single().messages.single().content)
    }

    @Test
    fun `conversation text expands only the supported name macros`() {
        val character = baseAsset().snapshot()
        val persona = Persona("traveler", "旅人")

        assertEquals(
            TextExpansionResult.Success("旅人遇见米拉"),
            compiler.expandConversationText("{{user}}遇见{{char}}", character, persona),
        )
        val failure = compiler.expandConversationText("{{random::a::b}}", character, persona)
        assertTrue(failure is TextExpansionResult.Failure)
    }

    private fun baseInput(): NormalGenerationInput {
        val character = baseAsset().snapshot()
        val persona = Persona("traveler", "旅人")
        return NormalGenerationInput(
            character = character,
            persona = persona,
            history = listOf(
                ConversationMessage(
                    id = "opening",
                    role = MessageRole.ASSISTANT,
                    content = "门铃轻响。{{char}}抬头看向{{user}}：“欢迎。”",
                    authorName = "米拉",
                ),
                ConversationMessage(
                    id = "user-1",
                    role = MessageRole.USER,
                    content = "我想住一晚。",
                    authorName = "旅人",
                ),
            ),
            preset = basePreset(),
        )
    }

    private fun baseAsset() = CharacterAsset(
        id = "mira",
        name = "米拉",
        description = "{{char}}经营一家雨夜旅店。",
        personality = "沉着、敏锐、友善。",
        scenario = "暴雨夜，{{user}}推门进入{{char}}的旅店。",
        firstMessage = "门铃轻响。{{char}}抬头看向{{user}}：“欢迎。”",
        examples = listOf(
            DialogueExample(
                listOf(
                    ExampleMessage(MessageRole.USER, "这里还有空房吗？"),
                    ExampleMessage(MessageRole.ASSISTANT, "有，壁炉旁那间还暖着。"),
                ),
            ),
        ),
        systemPrompt = "{{original}}\n始终以{{char}}的身份回应。",
        postHistoryInstructions = "不要替{{user}}决定行动。",
        depthPrompt = DepthPrompt("窗外一直下着雨。", depth = 1, order = 100),
    )

    private fun basePreset(): Preset {
        fun marker(id: String) = PromptDefinition(id, MessageRole.SYSTEM, marker = true)
        return Preset(
            id = "demo",
            name = "受控样本",
            prompts = listOf(
                PromptDefinition("main", MessageRole.SYSTEM, "基础规则：{{char}}与{{user}}。", systemPrompt = true),
                marker("charDescription"),
                marker("charPersonality"),
                marker("scenario"),
                marker("dialogueExamples"),
                marker("chatHistory"),
                PromptDefinition("jailbreak", MessageRole.SYSTEM, "默认后置规则。", systemPrompt = true),
                PromptDefinition(
                    identifier = "tone",
                    role = MessageRole.SYSTEM,
                    content = "保持克制、自然的叙述。",
                    injectionPosition = InjectionPosition.ABSOLUTE,
                    injectionDepth = 1,
                    injectionOrder = 200,
                ),
                PromptDefinition("unused", MessageRole.SYSTEM, "unused prompt"),
            ),
            promptOrder = listOf(
                PromptOrderEntry("main"),
                PromptOrderEntry("charDescription"),
                PromptOrderEntry("charPersonality"),
                PromptOrderEntry("scenario"),
                PromptOrderEntry("tone"),
                PromptOrderEntry("dialogueExamples"),
                PromptOrderEntry("chatHistory"),
                PromptOrderEntry("jailbreak"),
            ),
            newChatPrompt = "开始一段新的角色对话。",
            newExampleChatPrompt = "以下是示例对话。",
            maxOutputTokens = 512,
            declaredContextTokens = 8_192,
        )
    }
}
