package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Package by card fields and text delimiters, never by recognized gameplay syntax. */
internal class NativeProgramExtractor {
    private val json = Json { encodeDefaults = false }

    fun extract(character: CharacterAsset, availableAssetIds: Set<String>): NativeProgramView {
        require(character.rawCard.isNotEmpty()) { "原卡内容缺失，无法自动适配" }
        val sources = mutableListOf<NativeProgramSource>()
        val warnings = mutableListOf<String>()
        val payload = mutableListOf<JsonObject>()
        val data = character.rawCard["data"] as? JsonObject ?: character.rawCard
        val root = if (character.rawCard["data"] is JsonObject) "/data" else ""
        fun hasProgramMacro(text: String) = Regex("\\{\\{([^{}]+)\\}\\}").findAll(text).any {
            it.groupValues[1].trim().lowercase() !in setOf("user", "char", "persona", "charname", "username")
        }
        fun add(source: NativeProgramSource, metadata: JsonObject = JsonObject(emptyMap()), projectEjs: Boolean = false) {
            sources += source
            var content = source.content
            if (projectEjs) {
                val tags = Regex("<%[\\s\\S]*?%>").findAll(content).toList()
                if (tags.isNotEmpty() && tags.size == Regex("<%").findAll(content).count() && "<%%" !in content) {
                    var start = 0
                    var part = 0
                    content = buildString {
                        fun literal(end: Int) {
                            if (end > start) {
                                val id = source.id + ".text" + part++
                                append("[[LOCAL_TEXT:$id]]")
                            }
                        }
                        tags.forEach { tag ->
                            literal(tag.range.first)
                            append(tag.value) // original JS, comments and strings are kept
                            start = tag.range.last + 1
                        }
                        literal(source.content.length)
                    }
                } else warnings += source.id + "：模板分隔符不明确，已保留完整混合文本供模型判断"
            }
            payload += buildJsonObject {
                put("id", source.id); put("kind", source.kind); put("active", source.active)
                put("metadata", metadata); put("content", content)
            }
        }
        val extensions = data["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        fun scripts(values: JsonArray, path: String, inheritedEnabled: Boolean = true, depth: Int = 0) {
            require(depth < 16) { "脚本目录层级过深" }
            values.forEachIndexed { index, item ->
                val obj = item as? JsonObject ?: return@forEachIndexed
                val active = inheritedEnabled && ((obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true)
                (obj["content"] as? JsonPrimitive)?.contentOrNull?.let { code ->
                    add(NativeProgramSource("script" + sources.count { it.kind == "SCRIPT" }, "$path/$index/content", "SCRIPT", active, code),
                        JsonObject(obj.filterKeys { it !in setOf("content", "scripts") }))
                }
                (obj["scripts"] as? JsonArray)?.let { scripts(it, "$path/$index/scripts", active, depth + 1) }
            }
        }
        val helper = extensions["tavern_helper"] as? JsonObject
        (helper?.get("scripts") as? JsonArray)?.let { scripts(it, "$root/extensions/tavern_helper/scripts") }
        helper?.filterKeys { it != "scripts" }?.takeIf { it.isNotEmpty() }?.let {
            add(NativeProgramSource("helper-metadata", "$root/extensions/tavern_helper", "EXTENSION_METADATA", true, JsonObject(it).toString()))
        }
        character.regexScripts.forEachIndexed { index, rule ->
            // Importer raw is a lossless backup, not a second copy of the program to upload.
            val metadata = json.encodeToJsonElement(rule).jsonObject.filterKeys { it !in setOf("replaceString", "raw") }.toMutableMap()
            val extra = rule.raw.filterKeys { it !in setOf("id", "name", "scriptName", "findRegex", "replaceString", "trimStrings",
                "placement", "placements", "disabled", "markdownOnly", "promptOnly", "runOnEdit", "substituteRegex", "substitutionMode", "minDepth", "maxDepth") }
            if (extra.isNotEmpty()) metadata["additionalOptions"] = JsonObject(extra)
            add(NativeProgramSource("regex$index", "$root/extensions/regex_scripts/$index/replaceString",
                "REGEX_REPLACEMENT", !rule.disabled, rule.replaceString, regexId = rule.id), JsonObject(metadata))
        }
        val books = mutableListOf<JsonObject>()
        character.worldBooks.forEachIndexed { bi, book -> book.entries.forEachIndexed { ei, entry ->
            val id = "book$bi.entry$ei"
            val metadata = json.encodeToJsonElement(entry).jsonObject.filterKeys { it != "content" }
            val name = entry.name + " " + entry.comment
            val rules = Regex("initvar|mvu|schema|update|format|rule|变量|规则|协议|格式", RegexOption.IGNORE_CASE).containsMatchIn(name)
            val dynamic = "<%" in entry.content
            val macros = hasProgramMacro(entry.content)
            val html = Regex("<(?:script|input|textarea|form|html)\\b", RegexOption.IGNORE_CASE).containsMatchIn(entry.content)
            val protocol = Regex("<(?:UpdateVariable|JSONPatch|status_current_variable)\\b", RegexOption.IGNORE_CASE).containsMatchIn(entry.content)
            books += buildJsonObject {
                put("sourceId", id); put("metadata", JsonObject(metadata))
                put("contentIncluded", rules || dynamic || macros || html || protocol)
            }
            if (rules || dynamic || macros || html || protocol) {
                add(NativeProgramSource(id, "$root/character_book/entries/$ei/content",
                    if (dynamic) "EJS_TEMPLATE" else "WORLD_BOOK_RULES", entry.enabled,
                    entry.content, book.id, entry.id), projectEjs = dynamic && !html)
            } else {
                // Retain the original source locally even when only metadata is sent.
                sources += NativeProgramSource(id, "$root/character_book/entries/$ei/content", "STATIC_WORLD_BOOK",
                    entry.enabled, entry.content, book.id, entry.id)
            }
        } }
        listOf("description", "personality", "scenario", "first_mes", "mes_example", "system_prompt", "post_history_instructions", "alternate_greetings").forEach { field ->
            val element = data[field]
            val values = if (element is JsonArray) element.mapIndexed { i, e -> "$field/$i" to e } else listOf(field to element)
            values.forEach { (path, value) ->
                val text = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
                if (hasProgramMacro(text) || Regex("<%|<(?:script|input|textarea|form|html)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    add(NativeProgramSource("field-" + path.replace('/', '-'), "$root/$path", "MIXED_CARD_FIELD", true, text))
                }
            }
        }
        val unknown = extensions.keys - setOf("tavern_helper", "regex_scripts", "talkativeness", "fav", "world", "depth_prompt")
        unknown.forEach { key ->
            warnings += "未解释的扩展字段 $root/extensions/$key 保留本地；不声明其功能已恢复"
        }
        val omitted = books.count { it["contentIncluded"] == JsonPrimitive(false) }
        if (omitted > 0) warnings += "$omitted 个静态世界书正文未发送；按标题和结构选取规则可能遗漏自然语言行为，不能视为完整语义审计"
        require(sources.size <= 512) { "程序来源数量超过预算，未截断" }
        val request = buildJsonObject {
            put("sources", JsonArray(payload)); put("worldBooks", JsonArray(books))
            put("assetIds", JsonArray(availableAssetIds.sorted().map(::JsonPrimitive)))
            put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
            put("dependencyContext", "Card JS runs in SillyTavern/Tavern Helper, not standalone JS. MVU processes model variable-update blocks and stores per-message state. Prompt Template evaluates EJS when building prompts. Regex display and outgoing-prompt paths differ. External imports are NOT fetched/executed here; their exact versions and implementation are unverified. Card schema and update-format rules are primary evidence; do not assume all MVU versions behave identically.")
        }
        require(request.toString().length <= NativeAdaptationCompiler.MAX_INPUT_CHARS) { "程序材料超过预算，未截断或删除未知语法" }
        return NativeProgramView(request, sources, warnings)
    }
}
