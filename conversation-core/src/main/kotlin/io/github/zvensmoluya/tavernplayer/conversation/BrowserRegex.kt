package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.RegexDefinition
import io.github.zvensmoluya.tavernplayer.content.RegexPlacement
import kotlinx.serialization.json.*

/** Helper wire format mapped onto the same definitions used by Prompt and Display. */
internal object BrowserRegex {
    private val placements = mapOf("user_input" to RegexPlacement.USER_INPUT, "ai_output" to RegexPlacement.AI_OUTPUT,
        "slash_command" to RegexPlacement.SLASH_COMMAND, "world_info" to RegexPlacement.WORLD_INFO, "reasoning" to RegexPlacement.REASONING)
    fun encode(rule: RegexDefinition): JsonObject = buildJsonObject {
        put("id", rule.id); put("script_name", rule.name); put("enabled", !rule.disabled)
        put("find_regex", rule.findRegex); put("replace_string", rule.replaceString)
        put("trim_strings", JsonArray(rule.trimStrings.map(::JsonPrimitive)))
        putJsonObject("source") { placements.forEach { (name, placement) -> put(name, placement in rule.placements) } }
        putJsonObject("destination") { put("display", rule.markdownOnly); put("prompt", rule.promptOnly) }
        put("run_on_edit", rule.runOnEdit)
        put("min_depth", rule.minDepth?.let(::JsonPrimitive) ?: JsonNull)
        put("max_depth", rule.maxDepth?.let(::JsonPrimitive) ?: JsonNull)
    }
    fun replace(record: ConversationRecord, args: JsonObject): ConversationRecord {
        args.only("regexes")
        val values = args["regexes"] as? JsonArray ?: error("正则必须为数组")
        require(values.size <= 512 && values.toString().length <= 8 * 1024 * 1024) { "正则数据超过限制" }
        val rules = values.map { raw ->
            val item = raw as? JsonObject ?: error("正则必须为对象")
            item.only("id", "script_name", "enabled", "find_regex", "replace_string", "trim_strings", "source", "destination", "run_on_edit", "min_depth", "max_depth", "scope")
            fun flag(value: JsonObject, key: String): Boolean = (value[key] as? JsonPrimitive)?.booleanOrNull ?: error("正则选项必须为布尔值")
            fun depth(key: String): Int? = if (item[key] == null || item[key] == JsonNull) null else
                (item[key] as? JsonPrimitive)?.intOrNull ?: error("正则深度必须为整数")
            val source = item.obj("source").also { it.only(*placements.keys.toTypedArray()) }
            val destination = item.obj("destination").also { it.only("display", "prompt") }
            val id = item.string("id").also { require(it.isNotBlank()) { "缺少正则身份" } }
            val trims = item["trim_strings"] as? JsonArray ?: error("trim_strings 必须为数组")
            RegexDefinition(id = id, name = item.string("script_name").ifEmpty { "Unnamed-$id" },
                findRegex = item.string("find_regex"), replaceString = item.string("replace_string"),
                trimStrings = trims.map { (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("裁剪内容必须为字符串") },
                placements = placements.filter { flag(source, it.key) }.values.toSet(), disabled = !flag(item, "enabled"),
                markdownOnly = flag(destination, "display"), promptOnly = flag(destination, "prompt"),
                runOnEdit = flag(item, "run_on_edit"), minDepth = depth("min_depth"), maxDepth = depth("max_depth"))
        }
        require(rules.map { it.id }.distinct().size == rules.size) { "正则身份不能重复" }
        // Preserve fields not exposed by the helper when its representable value is unchanged.
        val previous = record.character.regexScripts.associateBy { it.id }
        return record.copy(character = record.character.copy(regexScripts = rules.map { rule ->
            previous[rule.id]?.takeIf { encode(it) == encode(rule) } ?: rule
        }))
    }
}
