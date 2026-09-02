package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.ContentRole
import io.github.zvensmoluya.tavernplayer.content.PresetAsset
import io.github.zvensmoluya.tavernplayer.content.PresetControlSettings
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationTrigger
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationSettings
import io.github.zvensmoluya.tavernplayer.content.PresetNamesBehavior
import io.github.zvensmoluya.tavernplayer.content.RegexDefinition
import io.github.zvensmoluya.tavernplayer.content.RegexPlacement
import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookEntryDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCompilerTest {
    private val macroEngine = MacroEngine()
    private val regexEngine = CharacterRegexEngine(macroEngine, ImmediateRegexExecutionStrategy)
    private val compiler = PromptCompiler(
        macroEngine = macroEngine,
        regexEngine = regexEngine,
        worldBookEngine = WorldBookEngine(macroEngine, regexEngine),
    )

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
        assertTrue(result.plan.diagnostics.any { it.code == "TOKEN_COUNT_ESTIMATED" })
        assertTrue(result.plan.trace.any { it.stage == "depth-injection" && it.sourceIds == listOf("tone") })
        assertFalse(result.plan.messages.any { "unused" in it.content })
    }

    @Test
    fun `character scan preview does not commit macro mutations twice`() {
        val original = baseInput()
        val input = original.copy(
            character = original.character.copy(description = "{{incvar::scan-count}}A quiet inn."),
        )

        val result = compiler.compile(input) as CompilationResult.Success

        assertEquals("1", result.plan.runtimeState.localVariables["scan-count"]?.text)
    }

    @Test
    fun `known model context is shared by macros world budget and final accounting`() {
        val original = baseInput()
        val input = original.copy(
            modelId = "gpt-4o",
            modelContextTokens = null,
            character = original.character.copy(worldBooks = listOf(WorldBookDefinition("book"))),
            preset = original.preset.copy(
                generationSettings = original.preset.generationSettings.copy(maxContextTokens = null),
                prompts = original.preset.prompts.map { prompt ->
                    if (prompt.identifier == "main") prompt.copy(content = "{{maxContext}}") else prompt
                },
            ),
        )

        val result = compiler.compile(input) as CompilationResult.Success

        assertTrue(result.plan.messages.any { it.content.startsWith("128000") })
        assertEquals(128_000, result.plan.tokenAccounting?.contextLimit)
        assertTrue(result.plan.trace.any { it.stage == "world-book-budget" && it.decision.contains("budget=31872") })
    }

    @Test
    fun `first included message macro is resolved after context trimming stabilizes`() {
        val character = CharacterAsset(id = "card", name = "Ash").snapshot()
        val history = listOf(
            ConversationMessage("m0", MessageRole.ASSISTANT, "a".repeat(40), "Ash"),
            ConversationMessage("m1", MessageRole.USER, "b".repeat(40), "Traveler"),
            ConversationMessage("m2", MessageRole.ASSISTANT, "c".repeat(40), "Ash"),
            ConversationMessage("m3", MessageRole.USER, "new", "Traveler"),
        )
        val preset = testPreset(
            id = "range",
            name = "Range",
            prompts = listOf(
                PromptDefinition("main", role = ContentRole.SYSTEM, content = "first={{firstIncludedMessageId}}"),
                PromptDefinition("chatHistory", role = ContentRole.SYSTEM, marker = true),
            ),
            promptOrder = listOf(PromptOrderEntry("main"), PromptOrderEntry("chatHistory")),
            maxOutputTokens = 10,
        )

        val result = compiler.compile(
            NormalGenerationInput(
                character = character,
                persona = Persona("persona", "Traveler"),
                history = history,
                preset = preset,
                modelId = "unknown",
                modelContextTokens = 80,
            ),
        ) as CompilationResult.Success

        assertEquals("first=3", result.plan.messages.first().content)
        assertTrue(result.plan.trace.any { it.stage == "chat-range" && it.decision == "firstIncludedMessageId=3" })
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
    fun `unknown macros and missing prompt references downgrade without blocking generation`() {
        val macroInput = baseInput().let { value ->
            value.copy(character = value.character.copy(description = "{{third_party_macro::mood}}"))
        }
        val macroResult = compiler.compile(macroInput) as CompilationResult.Success
        assertTrue(macroResult.plan.diagnostics.any { it.code == "UNSUPPORTED_MACRO" })
        assertTrue(macroResult.plan.messages.any { "{{third_party_macro::mood}}" in it.content })

        val referenceInput = baseInput().let { value ->
            value.copy(
                preset = value.preset.copy(
                    promptOrder = value.preset.promptOrder + PromptOrderEntry("missing"),
                ),
            )
        }
        val referenceResult = compiler.compile(referenceInput) as CompilationResult.Success
        assertTrue(referenceResult.plan.diagnostics.any { it.code == "MISSING_PROMPT_DEFINITION_SKIPPED" })
    }

    @Test
    fun `snapshot owns copied character collections`() {
        val alternate = mutableListOf("第一条备用开场")
        val keys = mutableListOf("rain")
        val trimStrings = mutableListOf("trim")
        val asset = baseAsset().copy(
            alternateFirstMessages = alternate,
            rawMessageExamples = "<START>\nUser: 旧示例",
            worldBooks = listOf(
                WorldBookDefinition("book", entries = listOf(WorldBookEntryDefinition("entry", keys = keys))),
            ),
            regexScripts = listOf(
                RegexDefinition(
                    id = "regex",
                    name = "regex",
                    findRegex = "rain",
                    replaceString = "sun",
                    trimStrings = trimStrings,
                    placements = setOf(RegexPlacement.AI_OUTPUT),
                ),
            ),
        )

        val snapshot = asset.snapshot()
        alternate[0] = "已修改"
        keys[0] = "changed"
        trimStrings[0] = "changed"

        assertEquals("第一条备用开场", snapshot.alternateFirstMessages.single())
        assertEquals("旧示例", snapshot.examples.single().messages.single().content)
        assertEquals("rain", snapshot.worldBooks.single().entries.single().keys.single())
        assertEquals("trim", snapshot.regexScripts.single().trimStrings.single())
    }

    @Test
    fun `conversation text expands only the supported name macros`() {
        val character = baseAsset().snapshot()
        val persona = Persona("traveler", "旅人")

        assertEquals(
            TextExpansionResult.Success("旅人遇见米拉"),
            compiler.expandConversationText("{{user}}遇见{{char}}", character, persona),
        )
        val random = compiler.expandConversationText("{{random::a::b}}", character, persona) as TextExpansionResult.Success
        assertTrue(random.text in setOf("a", "b"))
    }

    @Test
    fun `assistant projection preserves raw reasoning without committing display macro effects`() {
        val input = baseInput()
        val first = compiler.projectAssistantOutput(
            rawText = "count={{getvar::count}}",
            rawReasoning = listOf("{{incvar::count}}"),
            character = input.character,
            persona = input.persona,
            preset = input.preset,
            runtimeState = ConversationRuntimeState(),
            history = input.history,
            conversationId = "chat",
            generationId = "attempt",
            modelId = "custom",
        )
        val replay = compiler.projectAssistantOutput(
            rawText = "count={{getvar::count}}",
            rawReasoning = listOf("{{incvar::count}}"),
            character = input.character,
            persona = input.persona,
            preset = input.preset,
            runtimeState = ConversationRuntimeState(),
            history = input.history,
            conversationId = "chat",
            generationId = "attempt",
            modelId = "custom",
        )

        assertEquals(listOf("{{incvar::count}}"), first.storageReasoning)
        assertEquals("count=", first.storageText)
        assertEquals(null, first.runtimeState.localVariables["count"])
        assertEquals(first, replay)
    }

    @Test
    fun `reasoning prompt projection is applied before provider replay`() {
        val input = baseInput().let { original ->
            original.copy(
                character = original.character.copy(
                    regexScripts = listOf(
                        RegexDefinition(
                            id = "reasoning-prompt",
                            name = "Reasoning prompt",
                            findRegex = "private",
                            replaceString = "projected",
                            placements = setOf(RegexPlacement.REASONING),
                            promptOnly = true,
                        ),
                    ),
                ),
                history = original.history.mapIndexed { index, message ->
                    if (index == 0) message.copy(reasoning = listOf(ReasoningBlock("private", "signature"))) else message
                },
            )
        }

        val plan = (compiler.compile(input) as CompilationResult.Success).plan
        val opening = plan.messages.first { it.origin.sourceIds == listOf("opening") }

        assertEquals("projected", opening.reasoning.single().text)
        assertEquals("signature", opening.reasoning.single().signature)
    }

    @Test
    fun `world personality and scenario control formats are applied before macro expansion`() {
        val original = baseInput()
        val formatted = original.copy(
            character = original.character.copy(
                worldBooks = listOf(
                    WorldBookDefinition(
                        id = "book",
                        entries = listOf(
                            WorldBookEntryDefinition(
                                id = "rain",
                                content = "{{char}} knows the rain.",
                                constant = true,
                            ),
                        ),
                    ),
                ),
            ),
            preset = original.preset.copy(
                prompts = original.preset.prompts + PromptDefinition(
                    identifier = "worldInfoBefore",
                    role = ContentRole.SYSTEM,
                    marker = true,
                ),
                promptOrder = listOf(PromptOrderEntry("worldInfoBefore")) + original.preset.promptOrder,
                controlSettings = original.preset.controlSettings.copy(
                    worldInfoFormat = "<world>{0}</world>",
                    personalityFormat = "<personality>{{personality}}</personality>",
                    scenarioFormat = "<scenario>{{scenario}}</scenario>",
                ),
            ),
        )

        val plan = (compiler.compile(formatted) as CompilationResult.Success).plan

        assertTrue(plan.messages.any { it.content == "<world>米拉 knows the rain.</world>" })
        assertTrue(plan.messages.any { it.content == "<personality>沉着、敏锐、友善。</personality>" })
        assertTrue(plan.messages.any { it.content == "<scenario>暴雨夜，旅人推门进入米拉的旅店。</scenario>" })
    }

    @Test
    fun `persona description marker uses the current persona content at preset position`() {
        val original = baseInput()
        val input = original.copy(
            persona = original.persona.copy(description = "旅人是来自北方的寡言制图师。"),
            preset = original.preset.copy(
                prompts = original.preset.prompts + PromptDefinition(
                    identifier = "personaDescription",
                    role = ContentRole.USER,
                    marker = true,
                ),
                promptOrder = listOf(PromptOrderEntry("personaDescription")) + original.preset.promptOrder,
            ),
        )

        val plan = (compiler.compile(input) as CompilationResult.Success).plan
        val personaMessage = plan.messages.first { it.origin.sourceIds == listOf("personaDescription") }

        assertEquals(MessageRole.USER, personaMessage.role)
        assertEquals("旅人是来自北方的寡言制图师。", personaMessage.content)
    }

    @Test
    fun `normal and regenerate prompt triggers select the current transaction type`() {
        val original = baseInput()
        val preset = original.preset.copy(
            prompts = original.preset.prompts + listOf(
                PromptDefinition(
                    identifier = "normal-only",
                    role = ContentRole.SYSTEM,
                    content = "normal branch",
                    triggers = setOf(PresetGenerationTrigger.NORMAL),
                ),
                PromptDefinition(
                    identifier = "regenerate-only",
                    role = ContentRole.SYSTEM,
                    content = "regenerate branch",
                    triggers = setOf(PresetGenerationTrigger.REGENERATE),
                ),
            ),
            promptOrder = listOf(PromptOrderEntry("normal-only"), PromptOrderEntry("regenerate-only")) +
                original.preset.promptOrder,
        )

        val normal = (compiler.compile(original.copy(preset = preset)) as CompilationResult.Success).plan
        val regenerate = (
            compiler.compile(
                original.copy(
                    preset = preset,
                    runtimeState = original.runtimeState.copy(lastGenerationType = "regenerate"),
                ),
            ) as CompilationResult.Success
            ).plan

        assertTrue(normal.messages.any { it.content == "normal branch" })
        assertFalse(normal.messages.any { it.content == "regenerate branch" })
        assertTrue(regenerate.messages.any { it.content == "regenerate branch" })
        assertFalse(regenerate.messages.any { it.content == "normal branch" })
    }

    @Test
    fun `names behavior and consecutive system squash are deterministic`() {
        val original = baseInput()
        val completion = (compiler.compile(
            original.copy(
                preset = original.preset.copy(
                    controlSettings = original.preset.controlSettings.copy(
                        namesBehavior = PresetNamesBehavior.COMPLETION,
                    ),
                ),
            ),
        ) as CompilationResult.Success).plan
        assertEquals("米拉", completion.messages.first { it.origin.sourceIds == listOf("opening") }.authorName)
        assertEquals("旅人", completion.messages.first { it.origin.sourceIds == listOf("user-1") }.authorName)

        val content = (compiler.compile(
            original.copy(
                preset = original.preset.copy(
                    controlSettings = original.preset.controlSettings.copy(
                        namesBehavior = PresetNamesBehavior.CONTENT,
                        squashSystemMessages = true,
                    ),
                ),
            ),
        ) as CompilationResult.Success).plan
        assertTrue(content.messages.any { it.content.startsWith("米拉: 门铃轻响") })
        assertTrue(content.messages.any { it.content.startsWith("旅人: 我想住一晚") })
        assertFalse(content.messages.zipWithNext().any { (left, right) ->
            left.role == MessageRole.SYSTEM && right.role == MessageRole.SYSTEM
        })
        assertTrue(content.trace.any { it.stage == "system-squash" })
    }

    @Test
    fun `show thoughts changes display only and generation plan captures settings and fingerprint`() {
        val original = baseInput()
        val hidden = original.preset.copy(
            controlSettings = original.preset.controlSettings.copy(showThoughts = false),
        )
        val projection = compiler.projectAssistantOutput(
            rawText = "answer",
            rawReasoning = listOf("private reasoning"),
            character = original.character,
            persona = original.persona,
            preset = hidden,
            runtimeState = original.runtimeState,
            history = original.history,
            conversationId = "chat",
            generationId = "generation",
            modelId = "model",
        )
        val plan = (compiler.compile(original.copy(preset = hidden)) as CompilationResult.Success).plan

        assertEquals(listOf("private reasoning"), projection.storageReasoning)
        assertTrue(projection.displayReasoning.isEmpty())
        assertEquals(hidden.contentSha256, plan.presetContentSha256)
        assertEquals(plan.maxOutputTokens, plan.generationSettings.maxOutputTokens)
        assertEquals(plan.declaredContextTokens, plan.generationSettings.maxContextTokens)
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
        fun marker(id: String) = PromptDefinition(id, role = ContentRole.SYSTEM, marker = true)
        return testPreset(
            id = "demo",
            name = "受控样本",
            prompts = listOf(
                PromptDefinition("main", role = ContentRole.SYSTEM, content = "基础规则：{{char}}与{{user}}。", systemPrompt = true),
                marker("charDescription"),
                marker("charPersonality"),
                marker("scenario"),
                marker("dialogueExamples"),
                marker("chatHistory"),
                PromptDefinition("jailbreak", role = ContentRole.SYSTEM, content = "默认后置规则。", systemPrompt = true),
                PromptDefinition(
                    identifier = "tone",
                    role = ContentRole.SYSTEM,
                    content = "保持克制、自然的叙述。",
                    injectionPosition = InjectionPosition.ABSOLUTE,
                    injectionDepth = 1,
                    injectionOrder = 200,
                ),
                PromptDefinition("unused", role = ContentRole.SYSTEM, content = "unused prompt"),
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
            controlSettings = PresetControlSettings(
                newChatPrompt = "开始一段新的角色对话。",
                newExampleChatPrompt = "以下是示例对话。",
            ),
            maxOutputTokens = 512,
            contextTokens = 8_192,
        )
    }

    private fun testPreset(
        id: String,
        name: String,
        prompts: List<PromptDefinition>,
        promptOrder: List<PromptOrderEntry>,
        maxOutputTokens: Int,
        contextTokens: Int? = null,
        controlSettings: PresetControlSettings = PresetControlSettings(),
    ) = PresetAsset(
        id = id,
        sourceSha256 = id,
        contentSha256 = "$id-content",
        name = name,
        prompts = prompts,
        promptOrder = promptOrder,
        generationSettings = PresetGenerationSettings(
            maxContextTokens = contextTokens,
            maxOutputTokens = maxOutputTokens,
        ),
        controlSettings = controlSettings,
    )
}
