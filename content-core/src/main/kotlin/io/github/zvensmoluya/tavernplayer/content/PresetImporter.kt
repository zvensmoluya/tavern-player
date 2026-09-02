package io.github.zvensmoluya.tavernplayer.content

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

class PresetImporter(
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    fun import(sourceBytes: ByteArray, sourceName: String = ""): PresetImportResult {
        if (sourceBytes.isEmpty()) return rejected("EMPTY_SOURCE", "Preset 文件为空", sourceName)
        if (sourceBytes.size > MAX_SOURCE_BYTES) {
            return rejected("SOURCE_TOO_LARGE", "Preset 超过 32 MiB 导入上限", sourceName)
        }

        val text = try {
            decodeUtf8(sourceBytes).removePrefix("\uFEFF")
        } catch (_: Exception) {
            return rejected("INVALID_UTF8", "Preset 不是有效 UTF-8", sourceName)
        }
        if (!text.trimStart().startsWith('{')) {
            return rejected(
                "UNSUPPORTED_PRESET_FORMAT",
                "仅支持 SillyTavern OpenAI / Chat Completion JSON Preset",
                sourceName,
            )
        }

        val raw = try {
            JSON.parseToJsonElement(text) as? JsonObject
                ?: return rejected("INVALID_PRESET_ROOT", "Preset JSON 根节点必须是对象", sourceName)
        } catch (error: Exception) {
            return rejected("MALFORMED_JSON", "Preset JSON 无法解析：${error.message.orEmpty()}", sourceName)
        }

        val hasDefinitionPool = raw["prompts"] is JsonArray
        val hasPromptOrder = raw["prompt_order"] is JsonArray
        val hasLegacyPrompts = LEGACY_PROMPT_FIELDS.keys.any(raw::containsKey)
        if (!(hasDefinitionPool && hasPromptOrder) && !hasLegacyPrompts) {
            return rejected(
                "UNRECOGNIZED_CHAT_COMPLETION_PRESET",
                "JSON 不含可识别的 prompts/prompt_order，也不含可迁移的旧式 Prompt 字段",
                sourceName,
            )
        }

        val diagnostics = mutableListOf<CompatibilityDiagnostic>()

        return try {
            val definitions = if (hasDefinitionPool) {
                parseDefinitions(raw["prompts"] as JsonArray, diagnostics).toMutableList()
            } else {
                diagnostics.warning(
                    "LEGACY_PROMPTS_MIGRATED",
                    "旧式 main_prompt/nsfw_prompt/jailbreak_prompt 已迁移为 Prompt 定义池",
                )
                defaultPromptDefinitions().toMutableList()
            }
            migrateLegacyPrompts(
                raw = raw,
                definitions = definitions,
                diagnostics = diagnostics,
                replaceExisting = !hasDefinitionPool,
            )

            val order = if (hasPromptOrder) {
                parsePromptOrder(raw["prompt_order"] as JsonArray, definitions, diagnostics)
            } else {
                defaultPromptOrder().filter { entry -> definitions.any { it.identifier == entry.identifier } }
            }
            if (order.isEmpty()) {
                throw ImportFailure("MISSING_USABLE_PROMPT_ORDER", "Preset 没有可用的全局 prompt_order")
            }

            val regexScripts = parseRegexScripts(
                (raw["extensions"] as? JsonObject)?.get("regex_scripts")
                    ?: raw["regex_scripts"],
                diagnostics,
            )
            detectPreservedCapabilities(raw, diagnostics)

            val sourceSha256 = sourceBytes.sha256()
            val draft = PresetAsset(
                id = idFactory(),
                sourceSha256 = sourceSha256,
                contentSha256 = "",
                name = deriveName(sourceName),
                prompts = definitions.toList(),
                promptOrder = order,
                generationSettings = parseGenerationSettings(raw, diagnostics),
                controlSettings = parseControlSettings(raw, diagnostics),
                regexScripts = regexScripts,
                source = raw,
                diagnostics = emptyList(),
            )
            val exportedSource = PresetExporter.exportToJson(draft)
            val contentSha256 = PresetExporter.fingerprint(exportedSource)
            val finalDiagnostics = diagnostics.distinctBy { Triple(it.code, it.message, it.source) }
            val preset = draft.copy(
                contentSha256 = contentSha256,
                source = exportedSource,
                diagnostics = finalDiagnostics,
            )
            PresetImportResult.Ready(
                preset = preset,
                diagnostics = finalDiagnostics,
            )
        } catch (error: ImportFailure) {
            PresetImportResult.Rejected(
                diagnostics + CompatibilityDiagnostic(
                    severity = CompatibilitySeverity.ERROR,
                    code = error.code,
                    message = error.message ?: "Preset 规范化失败",
                    source = sourceName.takeIf(String::isNotBlank),
                ),
            )
        }
    }

    private fun parseDefinitions(
        array: JsonArray,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ): List<PresetPromptDefinition> {
        val seen = mutableSetOf<String>()
        return array.mapIndexedNotNull { index, element ->
            val raw = element as? JsonObject
            if (raw == null) {
                diagnostics.warning("INVALID_PROMPT_DEFINITION", "prompts[$index] 不是对象，已跳过", "prompts[$index]")
                return@mapIndexedNotNull null
            }
            val identifier = raw.string("identifier", diagnostics, "prompts[$index]")?.trim().orEmpty()
            if (identifier.isEmpty()) {
                diagnostics.warning("MISSING_PROMPT_IDENTIFIER", "prompts[$index] 缺少 identifier，已跳过", "prompts[$index]")
                return@mapIndexedNotNull null
            }
            if (!seen.add(identifier)) {
                diagnostics.warning(
                    "DUPLICATE_PROMPT_IDENTIFIER",
                    "Prompt identifier“$identifier”重复；已保留第一项",
                    "prompts[$index]",
                )
                return@mapIndexedNotNull null
            }

            val triggerValues = raw.stringArray("injection_trigger", diagnostics, "prompts[$index]")
            val triggers = if (triggerValues.isEmpty()) {
                PresetGenerationTrigger.entries.toSet()
            } else {
                triggerValues.mapNotNull { value ->
                    PresetGenerationTrigger.entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) }
                }.toSet()
            }
            val unknownTriggers = triggerValues.filter { value ->
                PresetGenerationTrigger.entries.none { it.wireValue.equals(value, ignoreCase = true) }
            }.toSet()
            if (unknownTriggers.isNotEmpty()) {
                diagnostics.warning(
                    "UNSUPPORTED_PROMPT_TRIGGER",
                    "Prompt“$identifier”含当前不会触发的 generation trigger：${unknownTriggers.joinToString()}",
                    "prompts[$index].injection_trigger",
                )
            }

            PresetPromptDefinition(
                identifier = identifier,
                name = raw.string("name", diagnostics, "prompts[$index]")?.ifBlank { identifier } ?: identifier,
                role = raw.role("role", diagnostics, "prompts[$index]"),
                content = raw.string("content", diagnostics, "prompts[$index]").orEmpty(),
                marker = raw.boolean("marker", diagnostics, "prompts[$index]") ?: false,
                systemPrompt = raw.boolean("system_prompt", diagnostics, "prompts[$index]") ?: false,
                forbidOverrides = raw.boolean("forbid_overrides", diagnostics, "prompts[$index]") ?: false,
                injectionPosition = raw.injectionPosition(diagnostics, "prompts[$index]"),
                injectionDepth = (raw.int("injection_depth", diagnostics, "prompts[$index]") ?: 4).let { depth ->
                    if (depth >= 0) depth else {
                        diagnostics.warning(
                            "INVALID_INJECTION_DEPTH",
                            "Prompt“$identifier”的 injection_depth 小于 0，已使用 4",
                            "prompts[$index].injection_depth",
                        )
                        4
                    }
                },
                injectionOrder = raw.int("injection_order", diagnostics, "prompts[$index]") ?: 100,
                triggers = triggers,
                unknownTriggers = unknownTriggers,
                raw = raw,
            )
        }
    }

    private fun parsePromptOrder(
        buckets: JsonArray,
        definitions: List<PresetPromptDefinition>,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ): List<PresetPromptOrderEntry> {
        val objects = buckets.mapIndexedNotNull { index, element ->
            (element as? JsonObject).also {
                if (it == null) diagnostics.warning(
                    "INVALID_PROMPT_ORDER_BUCKET",
                    "prompt_order[$index] 不是对象，已跳过",
                    "prompt_order[$index]",
                )
            }
        }
        val selected = objects.firstOrNull { it.characterId() == GLOBAL_PROMPT_ORDER_ID }
            ?: objects.firstOrNull { it.characterId() == LEGACY_GLOBAL_PROMPT_ORDER_ID }?.also {
                diagnostics.warning(
                    "LEGACY_PROMPT_ORDER_FALLBACK",
                    "未找到 character_id=100001，已使用兼容的 100000 全局 order",
                    "prompt_order",
                )
            }
            ?: objects.firstOrNull { it["order"] is JsonArray }?.also {
                diagnostics.warning(
                    "NONSTANDARD_PROMPT_ORDER_FALLBACK",
                    "未找到 100001/100000 全局 order，已使用第一份可解析 order",
                    "prompt_order",
                )
            }
            ?: return emptyList()

        val entries = selected["order"] as? JsonArray ?: run {
            diagnostics.warning("INVALID_PROMPT_ORDER", "选中的 prompt_order.order 不是数组", "prompt_order")
            return emptyList()
        }
        val definitionIds = definitions.map(PresetPromptDefinition::identifier).toSet()
        val seen = mutableSetOf<String>()
        return entries.mapIndexedNotNull { index, element ->
            val raw = element as? JsonObject
            if (raw == null) {
                diagnostics.warning("INVALID_PROMPT_ORDER_ENTRY", "order[$index] 不是对象，已跳过", "prompt_order.order[$index]")
                return@mapIndexedNotNull null
            }
            val identifier = raw.string("identifier", diagnostics, "prompt_order.order[$index]")?.trim().orEmpty()
            when {
                identifier.isEmpty() -> {
                    diagnostics.warning("MISSING_ORDER_IDENTIFIER", "order[$index] 缺少 identifier，已跳过", "prompt_order.order[$index]")
                    null
                }
                identifier !in definitionIds -> {
                    diagnostics.warning(
                        "MISSING_PROMPT_DEFINITION_REFERENCE",
                        "order 引用不存在的 Prompt“$identifier”；已按 ST 行为跳过",
                        "prompt_order.order[$index]",
                    )
                    null
                }
                !seen.add(identifier) -> {
                    diagnostics.warning(
                        "DUPLICATE_PROMPT_ORDER_ENTRY",
                        "order 重复引用 Prompt“$identifier”；已保留第一项",
                        "prompt_order.order[$index]",
                    )
                    null
                }
                else -> PresetPromptOrderEntry(
                    identifier = identifier,
                    enabled = raw.boolean("enabled", diagnostics, "prompt_order.order[$index]") ?: true,
                    raw = raw,
                )
            }
        }
    }

    private fun migrateLegacyPrompts(
        raw: JsonObject,
        definitions: MutableList<PresetPromptDefinition>,
        diagnostics: MutableList<CompatibilityDiagnostic>,
        replaceExisting: Boolean,
    ) {
        val defaults = defaultPromptDefinitions().associateBy(PresetPromptDefinition::identifier)
        LEGACY_PROMPT_FIELDS.forEach { (field, identifier) ->
            if (!raw.containsKey(field)) return@forEach
            val content = raw.string(field, diagnostics, field) ?: return@forEach
            val existingIndex = definitions.indexOfFirst { it.identifier == identifier }
            if (existingIndex < 0) {
                definitions += defaults.getValue(identifier).copy(content = content)
                diagnostics.warning(
                    "LEGACY_PROMPT_MIGRATED",
                    "$field 已迁移为 Prompt“$identifier”",
                    field,
                )
            } else if (replaceExisting || (definitions[existingIndex].content.isBlank() && content.isNotBlank())) {
                definitions[existingIndex] = definitions[existingIndex].copy(content = content)
                diagnostics.warning(
                    "LEGACY_PROMPT_FILLED",
                    "$field 已填入空的 Prompt“$identifier”",
                    field,
                )
            }
        }
    }

    private fun parseGenerationSettings(
        raw: JsonObject,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ): PresetGenerationSettings {
        val topA = raw.validDouble("top_a", diagnostics, default = 0.0) { it in 0.0..1.0 }
        val minP = raw.validDouble("min_p", diagnostics, default = 0.0) { it in 0.0..1.0 }
        val repetitionPenalty = raw.validDouble("repetition_penalty", diagnostics, default = 1.0) { it > 0.0 }
        if (topA != 0.0 || minP != 0.0 || repetitionPenalty != 1.0) {
            diagnostics.warning(
                "PRESERVED_UNMAPPED_SAMPLERS",
                "top_a、min_p 或 repetition_penalty 已保留供编辑/导出；当前 Provider 请求不会透传这些字段",
            )
        }
        val n = raw.int("n", diagnostics, "n")
        if (n != null && n != 1) {
            diagnostics.warning("MULTIPLE_CANDIDATES_FORCED_TO_ONE", "Preset 的 n=$n 不会生效；Tavern Player 始终请求 1 个候选", "n")
        }
        if (raw.boolean("stream_openai", diagnostics, "stream_openai") == false) {
            diagnostics.warning("STREAM_FORCED_ON", "Preset 关闭了 stream；Tavern Player 始终启用流式生成", "stream_openai")
        }

        val maxContextTokens = raw.int("openai_max_context", diagnostics, "openai_max_context")?.let { value ->
            if (value > 0) value else {
                diagnostics.warning("INVALID_GENERATION_SETTING", "openai_max_context 必须大于 0，已按不设人为上限处理", "openai_max_context")
                null
            }
        }
        val maxOutputTokens = (raw.int("openai_max_tokens", diagnostics, "openai_max_tokens") ?: 1_024).let { value ->
            if (value > 0) value else {
                diagnostics.warning("INVALID_GENERATION_SETTING", "openai_max_tokens 必须大于 0，已使用 1024", "openai_max_tokens")
                1_024
            }
        }
        val temperature = raw.validNullableDouble("temperature", diagnostics) { it >= 0.0 } ?: 1.0
        val topP = raw.validNullableDouble("top_p", diagnostics) { it in 0.0..1.0 } ?: 1.0
        val topK = (raw.int("top_k", diagnostics, "top_k") ?: 0).let { value ->
            if (value >= 0) value else {
                diagnostics.warning("INVALID_GENERATION_SETTING", "top_k 必须大于等于 0，已使用 0", "top_k")
                0
            }
        }
        val frequencyPenalty = raw.validNullableDouble("frequency_penalty", diagnostics) { it in -2.0..2.0 } ?: 0.0
        val presencePenalty = raw.validNullableDouble("presence_penalty", diagnostics) { it in -2.0..2.0 } ?: 0.0
        val seed = raw.int("seed", diagnostics, "seed")?.takeIf { it >= 0 }
        val reasoningEffort = raw.reasoningEffort(diagnostics)
        val verbosity = raw.verbosity(diagnostics)
        val disabledParameters = PresetGenerationParameter.entries
            .filterNotTo(mutableSetOf()) { parameter -> raw.containsKey(parameter.sourceKey) }
            .apply {
                if (seed == null) add(PresetGenerationParameter.SEED)
                if (reasoningEffort == PresetReasoningEffort.AUTO) add(PresetGenerationParameter.REASONING_EFFORT)
                if (verbosity == PresetVerbosity.AUTO) add(PresetGenerationParameter.VERBOSITY)
            }

        return PresetGenerationSettings(
            maxContextTokens = maxContextTokens,
            maxOutputTokens = maxOutputTokens,
            temperature = temperature,
            topP = topP,
            topK = topK,
            topA = topA,
            minP = minP,
            repetitionPenalty = repetitionPenalty,
            frequencyPenalty = frequencyPenalty,
            presencePenalty = presencePenalty,
            seed = seed,
            reasoningEffort = reasoningEffort,
            verbosity = verbosity,
            disabledParameters = disabledParameters,
        )
    }

    private fun parseControlSettings(
        raw: JsonObject,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ): PresetControlSettings = PresetControlSettings(
        newChatPrompt = raw.string("new_chat_prompt", diagnostics, "new_chat_prompt") ?: "[Start a new Chat]",
        newExampleChatPrompt = raw.string("new_example_chat_prompt", diagnostics, "new_example_chat_prompt") ?: "[Example Chat]",
        assistantPrefill = raw.string("assistant_prefill", diagnostics, "assistant_prefill").orEmpty(),
        worldInfoFormat = raw.string("wi_format", diagnostics, "wi_format") ?: "{0}",
        scenarioFormat = raw.string("scenario_format", diagnostics, "scenario_format") ?: "{{scenario}}",
        personalityFormat = raw.string("personality_format", diagnostics, "personality_format") ?: "{{personality}}",
        namesBehavior = when (val value = raw.int("names_behavior", diagnostics, "names_behavior") ?: 0) {
            -1 -> PresetNamesBehavior.NONE
            0 -> PresetNamesBehavior.DEFAULT
            1 -> PresetNamesBehavior.COMPLETION
            2 -> PresetNamesBehavior.CONTENT
            else -> {
                diagnostics.warning("UNSUPPORTED_NAMES_BEHAVIOR", "names_behavior=$value 无法识别，已使用 Default", "names_behavior")
                PresetNamesBehavior.DEFAULT
            }
        },
        squashSystemMessages = raw.boolean("squash_system_messages", diagnostics, "squash_system_messages") ?: false,
        showThoughts = raw.boolean("show_thoughts", diagnostics, "show_thoughts") ?: true,
    )

    private fun parseRegexScripts(
        element: JsonElement?,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ): List<RegexDefinition> {
        if (element == null || element is JsonNull) return emptyList()
        val array = element as? JsonArray ?: run {
            diagnostics.warning("INVALID_PRESET_REGEX_SCRIPTS", "Preset extensions.regex_scripts 不是数组，已忽略")
            return emptyList()
        }
        return array.mapIndexedNotNull { index, value ->
            val raw = value as? JsonObject
            if (raw == null) {
                diagnostics.warning("INVALID_PRESET_REGEX_SCRIPT", "Preset Regex 第 $index 项不是对象，已跳过")
                return@mapIndexedNotNull null
            }
            val placements = (raw["placement"] as? JsonArray).orEmpty().mapNotNull { item ->
                val wire = (item as? JsonPrimitive)?.intOrNull
                RegexPlacement.entries.firstOrNull { it.wireValue == wire }.also { placement ->
                    if (placement == null) diagnostics.warning(
                        "UNSUPPORTED_REGEX_PLACEMENT",
                        "Preset Regex 第 $index 项含未知 placement=$wire；原始值保留但不会执行",
                    )
                }
            }.toSet()
            if (RegexPlacement.SLASH_COMMAND in placements) {
                diagnostics.warning(
                    "UNSUPPORTED_SLASH_REGEX",
                    "Preset Regex“${raw.string("scriptName", diagnostics, "extensions.regex_scripts[$index]").orEmpty()}”含 Slash Command placement；不会执行 STscript",
                )
            }
            RegexDefinition(
                id = raw.primitiveText("id") ?: "preset-regex-$index",
                name = raw.string("scriptName", diagnostics, "extensions.regex_scripts[$index]")
                    .orEmpty().ifBlank { "Regex ${index + 1}" },
                findRegex = raw.string("findRegex", diagnostics, "extensions.regex_scripts[$index]").orEmpty(),
                replaceString = raw.string("replaceString", diagnostics, "extensions.regex_scripts[$index]").orEmpty(),
                trimStrings = raw.stringArray("trimStrings", diagnostics, "extensions.regex_scripts[$index]"),
                placements = placements,
                disabled = raw.boolean("disabled", diagnostics, "extensions.regex_scripts[$index]") ?: false,
                markdownOnly = raw.boolean("markdownOnly", diagnostics, "extensions.regex_scripts[$index]") ?: false,
                promptOnly = raw.boolean("promptOnly", diagnostics, "extensions.regex_scripts[$index]") ?: false,
                runOnEdit = raw.boolean("runOnEdit", diagnostics, "extensions.regex_scripts[$index]") ?: false,
                substitutionMode = when (raw.int("substituteRegex", diagnostics, "extensions.regex_scripts[$index]") ?: 0) {
                    1 -> RegexSubstitutionMode.RAW
                    2 -> RegexSubstitutionMode.ESCAPED
                    else -> RegexSubstitutionMode.NONE
                },
                minDepth = raw.int("minDepth", diagnostics, "extensions.regex_scripts[$index]"),
                maxDepth = raw.int("maxDepth", diagnostics, "extensions.regex_scripts[$index]"),
                raw = raw,
            )
        }
    }

    private fun detectPreservedCapabilities(
        raw: JsonObject,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ) {
        val extensions = raw["extensions"] as? JsonObject
        val scriptKeys = buildList {
            if (raw["tavern_helper"].hasDeepMeaningfulValue()) add("tavern_helper")
            extensions?.forEach { (key, value) ->
                val meaningful = if (key == "tavern_helper") value.hasDeepMeaningfulValue() else value.hasMeaningfulValue()
                if (key != "regex_scripts" && (key.contains("script", ignoreCase = true) || key == "tavern_helper") && meaningful) {
                    add("extensions.$key")
                }
            }
        }.distinct()
        if (scriptKeys.isNotEmpty()) {
            diagnostics.warning(
                "THIRD_PARTY_SCRIPT_PRESERVED",
                "检测到第三方脚本或运行时状态（${scriptKeys.joinToString()}）；导出时保留，但绝不会执行或联网加载",
            )
        }
        if (raw["tools"].hasMeaningfulValue() || raw["tool_choice"].hasMeaningfulValue()) {
            diagnostics.warning("UNSUPPORTED_TOOLS_PRESERVED", "Preset 的 tools/tool_choice 已保留供导出，但不会参与生成")
        }
    }

    private fun rejected(code: String, message: String, sourceName: String) = PresetImportResult.Rejected(
        listOf(
            CompatibilityDiagnostic(
                severity = CompatibilitySeverity.ERROR,
                code = code,
                message = message,
                source = sourceName.takeIf(String::isNotBlank),
            ),
        ),
    )

    private class ImportFailure(val code: String, message: String) : IllegalArgumentException(message)

    companion object {
        const val MAX_SOURCE_BYTES: Int = 32 * 1024 * 1024

        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }
        private val LEGACY_PROMPT_FIELDS = linkedMapOf(
            "main_prompt" to "main",
            "nsfw_prompt" to "nsfw",
            "jailbreak_prompt" to "jailbreak",
        )
    }
}

private fun deriveName(sourceName: String): String {
    val leaf = sourceName.substringAfterLast('/').substringAfterLast('\\').trim()
    val withoutExtension = if (leaf.endsWith(".json", ignoreCase = true)) leaf.dropLast(5) else leaf
    return withoutExtension
        .filterNot(Char::isISOControl)
        .trim()
        .take(120)
        .ifBlank { "导入预设" }
}

private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte) }

private fun MutableList<CompatibilityDiagnostic>.warning(code: String, message: String, source: String? = null) {
    add(CompatibilityDiagnostic(CompatibilitySeverity.WARNING, code, message, source))
}

private fun JsonObject.string(
    name: String,
    diagnostics: MutableList<CompatibilityDiagnostic>,
    path: String,
): String? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    val result = (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
    if (result == null) diagnostics.warning("INVALID_PRESET_FIELD_TYPE", "$name 应为字符串，已使用默认值", path)
    return result
}

private fun JsonObject.int(
    name: String,
    diagnostics: MutableList<CompatibilityDiagnostic>,
    path: String,
): Int? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    val result = (value as? JsonPrimitive)?.intOrNull
    if (result == null) diagnostics.warning("INVALID_PRESET_FIELD_TYPE", "$name 应为整数，已使用默认值", path)
    return result
}

private fun JsonObject.boolean(
    name: String,
    diagnostics: MutableList<CompatibilityDiagnostic>,
    path: String,
): Boolean? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    val result = (value as? JsonPrimitive)?.booleanOrNull
    if (result == null) diagnostics.warning("INVALID_PRESET_FIELD_TYPE", "$name 应为布尔值，已使用默认值", path)
    return result
}

private fun JsonObject.stringArray(
    name: String,
    diagnostics: MutableList<CompatibilityDiagnostic>,
    path: String,
): List<String> {
    val value = this[name] ?: return emptyList()
    if (value is JsonNull) return emptyList()
    val array = value as? JsonArray
    if (array == null) {
        diagnostics.warning("INVALID_PRESET_FIELD_TYPE", "$name 应为字符串数组，已使用空列表", path)
        return emptyList()
    }
    return array.mapIndexedNotNull { index, element ->
        val result = (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        if (result == null) diagnostics.warning(
            "INVALID_PRESET_FIELD_TYPE",
            "$name[$index] 应为字符串，已跳过",
            "$path.$name[$index]",
        )
        result
    }
}

private fun JsonObject.validDouble(
    name: String,
    diagnostics: MutableList<CompatibilityDiagnostic>,
    default: Double,
    predicate: (Double) -> Boolean,
): Double = validNullableDouble(name, diagnostics, predicate) ?: default

private fun JsonObject.validNullableDouble(
    name: String,
    diagnostics: MutableList<CompatibilityDiagnostic>,
    predicate: (Double) -> Boolean,
): Double? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    val result = (value as? JsonPrimitive)?.doubleOrNull
    if (result == null || !predicate(result)) {
        diagnostics.warning("INVALID_GENERATION_SETTING", "$name 的值无效，已使用默认值", name)
        return null
    }
    return result
}

private fun JsonObject.role(
    name: String,
    diagnostics: MutableList<CompatibilityDiagnostic>,
    path: String,
): ContentRole {
    val value = this[name] ?: return ContentRole.SYSTEM
    val primitive = value as? JsonPrimitive
    val role = when {
        primitive?.intOrNull == 1 -> ContentRole.USER
        primitive?.intOrNull == 2 -> ContentRole.ASSISTANT
        primitive?.intOrNull == 0 -> ContentRole.SYSTEM
        primitive?.contentOrNull.equals("system", ignoreCase = true) -> ContentRole.SYSTEM
        primitive?.contentOrNull.equals("user", ignoreCase = true) -> ContentRole.USER
        primitive?.contentOrNull.equals("assistant", ignoreCase = true) -> ContentRole.ASSISTANT
        else -> null
    }
    if (role == null) diagnostics.warning("INVALID_PROMPT_ROLE", "$name 无法识别，已使用 system", "$path.$name")
    return role ?: ContentRole.SYSTEM
}

private fun JsonObject.injectionPosition(
    diagnostics: MutableList<CompatibilityDiagnostic>,
    path: String,
): PresetInjectionPosition {
    val value = this["injection_position"] ?: return PresetInjectionPosition.RELATIVE
    val primitive = value as? JsonPrimitive
    return when {
        primitive?.intOrNull == 0 -> PresetInjectionPosition.RELATIVE
        primitive?.intOrNull == 1 -> PresetInjectionPosition.ABSOLUTE
        primitive?.contentOrNull.equals("relative", ignoreCase = true) -> PresetInjectionPosition.RELATIVE
        primitive?.contentOrNull.equals("absolute", ignoreCase = true) -> PresetInjectionPosition.ABSOLUTE
        else -> {
            diagnostics.warning(
                "INVALID_INJECTION_POSITION",
                "injection_position 无法识别，已使用 relative",
                "$path.injection_position",
            )
            PresetInjectionPosition.RELATIVE
        }
    }
}

private fun JsonObject.reasoningEffort(
    diagnostics: MutableList<CompatibilityDiagnostic>,
): PresetReasoningEffort {
    val value = (this["reasoning_effort"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        ?: return PresetReasoningEffort.AUTO
    return when (value) {
        "auto" -> PresetReasoningEffort.AUTO
        "min", "minimal" -> PresetReasoningEffort.MIN
        "low" -> PresetReasoningEffort.LOW
        "medium", "med" -> PresetReasoningEffort.MEDIUM
        "high" -> PresetReasoningEffort.HIGH
        "max" -> PresetReasoningEffort.MAX
        else -> {
            diagnostics.warning("UNSUPPORTED_REASONING_EFFORT", "reasoning_effort=$value 无法识别，已使用 auto", "reasoning_effort")
            PresetReasoningEffort.AUTO
        }
    }
}

private fun JsonObject.verbosity(
    diagnostics: MutableList<CompatibilityDiagnostic>,
): PresetVerbosity {
    val value = (this["verbosity"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        ?: return PresetVerbosity.AUTO
    return when (value) {
        "auto" -> PresetVerbosity.AUTO
        "low" -> PresetVerbosity.LOW
        "medium", "med" -> PresetVerbosity.MEDIUM
        "high" -> PresetVerbosity.HIGH
        else -> {
            diagnostics.warning("UNSUPPORTED_VERBOSITY", "verbosity=$value 无法识别，已使用 auto", "verbosity")
            PresetVerbosity.AUTO
        }
    }
}

private fun JsonObject.characterId(): Int? {
    val primitive = this["character_id"] as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
}

private fun JsonObject.primitiveText(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.hasMeaningfulValue(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonArray -> isNotEmpty()
    is JsonObject -> isNotEmpty()
    is JsonPrimitive -> contentOrNull?.isNotBlank() == true
}

private fun JsonElement?.hasDeepMeaningfulValue(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonArray -> any { it.hasDeepMeaningfulValue() }
    is JsonObject -> values.any { it.hasDeepMeaningfulValue() }
    is JsonPrimitive -> contentOrNull?.isNotBlank() == true
}
