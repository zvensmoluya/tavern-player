package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.json.JsonObject

object BuiltInPresets {
    const val DEFAULT_ID: String = "builtin-default"

    val default: PresetAsset by lazy {
        val draft = PresetAsset(
            id = DEFAULT_ID,
            sourceSha256 = "",
            contentSha256 = "",
            name = "默认",
            prompts = defaultPromptDefinitions(),
            promptOrder = defaultPromptOrder(),
            generationSettings = PresetGenerationSettings(
                maxContextTokens = null,
                maxOutputTokens = 32_768,
                temperature = 1.0,
                topP = 1.0,
                topK = 0,
                topA = 0.0,
                minP = 0.0,
                repetitionPenalty = 1.0,
                frequencyPenalty = 0.0,
                presencePenalty = 0.0,
                seed = null,
                disabledParameters = PresetGenerationParameter.entries
                    .filterNotTo(mutableSetOf()) { it == PresetGenerationParameter.OUTPUT_LIMIT },
            ),
            controlSettings = PresetControlSettings(),
            source = JsonObject(emptyMap()),
            builtIn = true,
        )
        val source = PresetExporter.exportToJson(draft)
        val fingerprint = PresetExporter.fingerprint(source)
        val finalized = draft.copy(
            sourceSha256 = fingerprint,
            contentSha256 = fingerprint,
            source = source,
        )
        finalized.copy(initialState = PresetInitialState(source))
    }
}

internal fun defaultPromptDefinitions(): List<PresetPromptDefinition> = listOf(
    PresetPromptDefinition(
        identifier = "main",
        name = "Main Prompt",
        content = "Write {{char}}'s next reply in a fictional chat between {{char}} and {{user}}.",
        systemPrompt = true,
    ),
    PresetPromptDefinition(
        identifier = "nsfw",
        name = "Auxiliary Prompt",
        content = "",
        systemPrompt = true,
    ),
    marker("dialogueExamples", "Chat Examples"),
    PresetPromptDefinition(
        identifier = "jailbreak",
        name = "Post-History Instructions",
        content = "",
        systemPrompt = true,
    ),
    marker("chatHistory", "Chat History"),
    marker("worldInfoAfter", "World Info (after)"),
    marker("worldInfoBefore", "World Info (before)"),
    PresetPromptDefinition(
        identifier = "enhanceDefinitions",
        name = "Enhance Definitions",
        content = "If you have more knowledge of {{char}}, add to the character's lore and personality " +
            "to enhance them but keep the Character Sheet's definitions absolute.",
        systemPrompt = true,
    ),
    marker("charDescription", "Char Description"),
    marker("charPersonality", "Char Personality"),
    marker("scenario", "Scenario"),
    marker("personaDescription", "Persona Description"),
)

internal fun defaultPromptOrder(): List<PresetPromptOrderEntry> = listOf(
    "main",
    "worldInfoBefore",
    "personaDescription",
    "charDescription",
    "charPersonality",
    "scenario",
    "enhanceDefinitions",
    "nsfw",
    "worldInfoAfter",
    "dialogueExamples",
    "chatHistory",
    "jailbreak",
).map { identifier ->
    PresetPromptOrderEntry(
        identifier = identifier,
        enabled = identifier != "enhanceDefinitions",
    )
}

private fun marker(identifier: String, name: String) = PresetPromptDefinition(
    identifier = identifier,
    name = name,
    marker = true,
    systemPrompt = true,
)
