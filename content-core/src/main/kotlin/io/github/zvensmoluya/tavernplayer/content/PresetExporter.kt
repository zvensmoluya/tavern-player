package io.github.zvensmoluya.tavernplayer.content

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Produces an ST OpenAI / Chat Completion preset without restoring connection secrets. */
object PresetExporter {
    fun export(preset: PresetAsset): ByteArray = exportToJson(preset).toString().encodeToByteArray()

    fun exportToJson(preset: PresetAsset): JsonObject {
        val root = sanitizePresetJson(preset.sanitizedSource).json.toMutableMap()

        root["prompts"] = JsonArray(preset.prompts.map(::exportPrompt))
        root["prompt_order"] = exportPromptOrder(root["prompt_order"], preset.promptOrder)

        preset.generationSettings.apply {
            root.putNullableNumber("openai_max_context", maxContextTokens)
            root["openai_max_tokens"] = JsonPrimitive(maxOutputTokens)
            root.putNullableNumber("temperature", temperature)
            root.putNullableNumber("top_p", topP)
            root.putNullableNumber("top_k", topK)
            root.putNullableNumber("top_a", topA)
            root.putNullableNumber("min_p", minP)
            root.putNullableNumber("repetition_penalty", repetitionPenalty)
            root.putNullableNumber("frequency_penalty", frequencyPenalty)
            root.putNullableNumber("presence_penalty", presencePenalty)
            root["seed"] = JsonPrimitive(seed ?: -1)
            root["reasoning_effort"] = JsonPrimitive(reasoningEffort.wireValue)
            root["verbosity"] = JsonPrimitive(verbosity.wireValue)
        }

        preset.controlSettings.apply {
            root["new_chat_prompt"] = JsonPrimitive(newChatPrompt)
            root["new_example_chat_prompt"] = JsonPrimitive(newExampleChatPrompt)
            root["assistant_prefill"] = JsonPrimitive(assistantPrefill)
            root["wi_format"] = JsonPrimitive(worldInfoFormat)
            root["scenario_format"] = JsonPrimitive(scenarioFormat)
            root["personality_format"] = JsonPrimitive(personalityFormat)
            root["names_behavior"] = JsonPrimitive(namesBehavior.wireValue)
            root["squash_system_messages"] = JsonPrimitive(squashSystemMessages)
            root["show_thoughts"] = JsonPrimitive(showThoughts)
        }

        // Tavern Player owns transport behavior and never persists hosted continuation state.
        root["stream_openai"] = JsonPrimitive(true)
        root["n"] = JsonPrimitive(1)
        root.remove("previous_response_id")
        root.remove("conversation")

        updateLegacyPromptIfPresent(root, "main_prompt", preset, "main")
        updateLegacyPromptIfPresent(root, "nsfw_prompt", preset, "nsfw")
        updateLegacyPromptIfPresent(root, "jailbreak_prompt", preset, "jailbreak")

        val oldExtensions = root["extensions"] as? JsonObject
        if (preset.regexScripts.isNotEmpty() || oldExtensions?.containsKey("regex_scripts") == true) {
            val extensions = oldExtensions?.toMutableMap() ?: mutableMapOf()
            extensions["regex_scripts"] = JsonArray(preset.regexScripts.map(::exportRegex))
            root["extensions"] = JsonObject(extensions)
        }

        // Run the safety filter again because editor-owned raw payloads are also merged above.
        return sanitizePresetJson(JsonObject(root)).json
    }

    fun fingerprint(preset: PresetAsset): String = fingerprint(exportToJson(preset))

    internal fun fingerprint(json: JsonElement): String = canonicalJson(json).encodeToByteArray().sha256()

    private fun exportPrompt(prompt: PresetPromptDefinition): JsonObject {
        val raw = prompt.raw.toMutableMap()
        raw["identifier"] = JsonPrimitive(prompt.identifier)
        raw["name"] = JsonPrimitive(prompt.name)
        raw["role"] = JsonPrimitive(prompt.role.wireValue())
        raw["content"] = JsonPrimitive(prompt.content)
        raw["marker"] = JsonPrimitive(prompt.marker)
        raw["system_prompt"] = JsonPrimitive(prompt.systemPrompt)
        raw["forbid_overrides"] = JsonPrimitive(prompt.forbidOverrides)
        raw["injection_position"] = JsonPrimitive(
            when (prompt.injectionPosition) {
                PresetInjectionPosition.RELATIVE -> 0
                PresetInjectionPosition.ABSOLUTE -> 1
            },
        )
        raw["injection_depth"] = JsonPrimitive(prompt.injectionDepth)
        raw["injection_order"] = JsonPrimitive(prompt.injectionOrder)
        raw["injection_trigger"] = JsonArray(
            buildList {
                addAll(prompt.triggers.map { JsonPrimitive(it.wireValue) })
                addAll(prompt.unknownTriggers.sorted().map(::JsonPrimitive))
            },
        )
        return JsonObject(raw)
    }

    private fun exportPromptOrder(
        existing: JsonElement?,
        order: List<PresetPromptOrderEntry>,
    ): JsonArray {
        val existingBuckets = (existing as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val globalRaw = existingBuckets.firstOrNull { it.characterId() == GLOBAL_PROMPT_ORDER_ID }
            ?.toMutableMap()
            ?: mutableMapOf()
        globalRaw["character_id"] = JsonPrimitive(GLOBAL_PROMPT_ORDER_ID)
        globalRaw["order"] = JsonArray(order.map { entry ->
            val raw = entry.raw.toMutableMap()
            raw["identifier"] = JsonPrimitive(entry.identifier)
            raw["enabled"] = JsonPrimitive(entry.enabled)
            JsonObject(raw)
        })

        return JsonArray(
            existingBuckets.filterNot { it.characterId() == GLOBAL_PROMPT_ORDER_ID } + JsonObject(globalRaw),
        )
    }

    private fun exportRegex(regex: RegexDefinition): JsonObject {
        val raw = regex.raw.toMutableMap()
        raw["id"] = JsonPrimitive(regex.id)
        raw["scriptName"] = JsonPrimitive(regex.name)
        raw["findRegex"] = JsonPrimitive(regex.findRegex)
        raw["replaceString"] = JsonPrimitive(regex.replaceString)
        raw["trimStrings"] = JsonArray(regex.trimStrings.map(::JsonPrimitive))
        raw["placement"] = JsonArray(regex.placements.sortedBy(RegexPlacement::wireValue).map {
            JsonPrimitive(it.wireValue)
        })
        raw["disabled"] = JsonPrimitive(regex.disabled)
        raw["markdownOnly"] = JsonPrimitive(regex.markdownOnly)
        raw["promptOnly"] = JsonPrimitive(regex.promptOnly)
        raw["runOnEdit"] = JsonPrimitive(regex.runOnEdit)
        raw["substituteRegex"] = JsonPrimitive(
            when (regex.substitutionMode) {
                RegexSubstitutionMode.NONE -> 0
                RegexSubstitutionMode.RAW -> 1
                RegexSubstitutionMode.ESCAPED -> 2
            },
        )
        regex.minDepth?.let { raw["minDepth"] = JsonPrimitive(it) } ?: raw.remove("minDepth")
        regex.maxDepth?.let { raw["maxDepth"] = JsonPrimitive(it) } ?: raw.remove("maxDepth")
        return JsonObject(raw)
    }

    private fun updateLegacyPromptIfPresent(
        root: MutableMap<String, JsonElement>,
        key: String,
        preset: PresetAsset,
        identifier: String,
    ) {
        if (root.containsKey(key)) {
            root[key] = JsonPrimitive(preset.prompts.firstOrNull { it.identifier == identifier }?.content.orEmpty())
        }
    }
}

internal const val GLOBAL_PROMPT_ORDER_ID: Int = 100001
internal const val LEGACY_GLOBAL_PROMPT_ORDER_ID: Int = 100000

internal data class SanitizedPresetJson(
    val json: JsonObject,
    val removedPaths: List<String>,
)

/**
 * Remove known ST connection/provider fields plus credential-shaped extension keys at every depth.
 * The filter works on parsed JSON, so secret values are never copied into the persisted source.
 */
internal fun sanitizePresetJson(source: JsonObject): SanitizedPresetJson {
    val removed = mutableListOf<String>()

    fun visit(element: JsonElement, path: String, root: Boolean): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.mapIndexed { index, child -> visit(child, "$path[$index]", false) })
        is JsonObject -> JsonObject(
            element.mapNotNull { (key, value) ->
                val lower = key.lowercase()
                val shouldRemove = lower in ALWAYS_SENSITIVE_KEYS ||
                    CREDENTIAL_KEY_PATTERN.containsMatchIn(lower) ||
                    (root && lower in ROOT_CONNECTION_AND_MODEL_KEYS)
                if (shouldRemove) {
                    removed += if (path.isBlank()) key else "$path.$key"
                    null
                } else {
                    key to visit(value, if (path.isBlank()) key else "$path.$key", false)
                }
            }.toMap(),
        )
        else -> element
    }

    return SanitizedPresetJson(
        json = visit(source, "", true) as JsonObject,
        removedPaths = removed.distinct(),
    )
}

private val ROOT_CONNECTION_AND_MODEL_KEYS = setOf(
    "chat_completion_source",
    "openai_model",
    "claude_model",
    "openrouter_model",
    "ai21_model",
    "mistralai_model",
    "cohere_model",
    "perplexity_model",
    "groq_model",
    "chutes_model",
    "minimax_model",
    "minimax_endpoint",
    "electronhub_model",
    "google_model",
    "vertexai_model",
    "custom_model",
    "custom_url",
    "custom_include_body",
    "custom_exclude_body",
    "custom_include_headers",
    "reverse_proxy",
    "proxy_password",
    "vertexai_region",
    "vertexai_express_project_id",
    "azure_base_url",
    "azure_deployment_name",
    "workers_ai_account_id",
    "openrouter_site_url",
    "openrouter_app_name",
    "bypass_status_check",
)

private val ALWAYS_SENSITIVE_KEYS = setOf(
    "authorization",
    "proxy_password",
    "api_key",
    "apikey",
    "access_token",
    "refresh_token",
    "client_secret",
)

private val CREDENTIAL_KEY_PATTERN = Regex("(?:^|_)(?:password|passwd|secret|api_?key|access_?token|refresh_?token)$")

private fun JsonObject.characterId(): Int? {
    val primitive = this["character_id"] as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
}

private fun ContentRole.wireValue(): String = when (this) {
    ContentRole.SYSTEM -> "system"
    ContentRole.USER -> "user"
    ContentRole.ASSISTANT -> "assistant"
}

private fun MutableMap<String, JsonElement>.putNullableNumber(name: String, value: Number?) {
    when (value) {
        null -> remove(name)
        is Int -> this[name] = JsonPrimitive(value)
        is Long -> this[name] = JsonPrimitive(value)
        is Double -> this[name] = JsonPrimitive(value)
        is Float -> this[name] = JsonPrimitive(value)
        else -> this[name] = JsonPrimitive(value.toDouble())
    }
}

private fun canonicalJson(element: JsonElement): String = when (element) {
    JsonNull -> "null"
    is JsonPrimitive -> element.toString()
    is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",", transform = ::canonicalJson)
    is JsonObject -> element.entries.sortedBy(Map.Entry<String, JsonElement>::key).joinToString(
        prefix = "{",
        postfix = "}",
        separator = ",",
    ) { (key, value) -> "${JsonPrimitive(key)}:${canonicalJson(value)}" }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte) }
