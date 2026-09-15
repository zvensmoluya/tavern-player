package io.github.zvensmoluya.tavernplayer.content

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

data class WorldBookImport(val book: WorldBookDefinition, val source: JsonObject, val diagnostics: List<CompatibilityDiagnostic>)

/** Standalone ST World Info objects and standalone Character Book objects share the card parser. */
class WorldBookImporter {
    fun import(bytes: ByteArray, name: String): WorldBookImport {
        require(bytes.isNotEmpty() && bytes.size <= 32 * 1024 * 1024) { "世界书文件必须为 1 字节至 32 MiB" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        val source = Json.parseToJsonElement(text) as? JsonObject ?: error("世界书必须是 JSON 对象")
        val entries = source["entries"]
        require(entries is JsonObject || entries is JsonArray) { "缺少世界书 entries" }
        val diagnostics = mutableListOf<CompatibilityDiagnostic>()
        val normalized = if (entries is JsonObject) JsonObject(source + ("entries" to JsonArray(entries.map { (key, value) ->
            val raw = value as? JsonObject ?: error("世界书条目 $key 必须是对象")
            val ext = (raw["extensions"] as? JsonObject).orEmpty().toMutableMap()
            val fields = mapOf("position" to "position", "depth" to "depth", "role" to "role",
                "probability" to "probability", "useProbability" to "useProbability", "selectiveLogic" to "selectiveLogic",
                "group" to "group", "groupOverride" to "group_override", "groupWeight" to "group_weight",
                "useGroupScoring" to "use_group_scoring", "scanDepth" to "scan_depth",
                "caseSensitive" to "case_sensitive", "matchWholeWords" to "match_whole_words",
                "excludeRecursion" to "exclude_recursion", "preventRecursion" to "prevent_recursion",
                "delayUntilRecursion" to "delay_until_recursion", "outletName" to "outlet_name",
                "sticky" to "sticky", "cooldown" to "cooldown", "delay" to "delay",
                "ignoreBudget" to "ignore_budget", "displayIndex" to "display_index",
                "matchCharacterDescription" to "match_character_description", "matchCharacterPersonality" to "match_character_personality",
                "matchCharacterDepthPrompt" to "match_character_depth_prompt", "matchScenario" to "match_scenario",
                "matchCreatorNotes" to "match_creator_notes")
            fields.forEach { (from, to) -> raw[from]?.takeUnless { it is JsonNull }?.let { ext[to] = it } }
            if (raw["vectorized"] == JsonPrimitive(true) || raw["automationId"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) {
                diagnostics += CompatibilityDiagnostic(CompatibilitySeverity.WARNING, "UNSUPPORTED_WORLD_BOOK_TRIGGER", "条目 $key 的向量或自动化触发不受支持")
            }
            JsonObject(raw + mapOf("id" to (raw["uid"] ?: JsonPrimitive(key)),
                "keys" to (raw["key"] ?: JsonArray(emptyList())),
                "secondary_keys" to (raw["keysecondary"] ?: JsonArray(emptyList())),
                "enabled" to JsonPrimitive(raw["disable"] != JsonPrimitive(true)),
                "insertion_order" to (raw["order"] ?: JsonPrimitive(100)),
                "position" to JsonPrimitive("before_char"), "extensions" to JsonObject(ext)))
        }))) else source
        val hash = BrowserProgramReader.sha256(text)
        val parsed = CharacterCardImporter().parseWorldBook(normalized, hash, diagnostics)
        require(parsed.entries.size == (if (entries is JsonArray) entries.size else (entries as JsonObject).size)) { "世界书包含无效条目" }
        require(parsed.entries.map { it.id }.distinct().size == parsed.entries.size) { "世界书条目 ID 不能重复" }
        return WorldBookImport(parsed.copy(name = parsed.name.ifBlank { name.substringBeforeLast('.').ifBlank { "世界书" } }), source, diagnostics)
    }
}
