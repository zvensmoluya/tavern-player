package io.github.zvensmoluya.tavernplayer.content

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Original programs, never a compilation prompt or a model-generated adaptation. */
@Serializable
data class BrowserProgram(
    val profile: String = "player-web-1",
    val sources: List<BrowserScriptSource> = emptyList(),
    val ejsTemplates: List<NativeWorldBookReference> = emptyList(),
    val mvu: NativeMvuProgram? = null,
    val mvuSourceIds: Set<String> = emptySet(),
    val blockedSourceIds: Set<String> = emptySet(),
    val runtimeFingerprint: String = "",
    val diagnostics: List<String> = emptyList(),
    val variables: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class BrowserScriptSource(
    val id: String,
    val pointer: String,
    val content: String,
    val sha256: String,
    val enabled: Boolean,
    val buttons: List<BrowserScriptButton> = emptyList(),
    val declaredId: String? = null,
    val data: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class BrowserScriptButton(val name: String, val visible: Boolean = true)

object BrowserProgramReader {
    fun character(asset: CharacterAsset): BrowserProgram {
        val root = if (asset.rawCard["data"] is JsonObject) "/data" else ""
        val data = asset.rawCard["data"] as? JsonObject ?: asset.rawCard
        val program = read(data["extensions"] as? JsonObject, root + "/extensions", "character")
        return program.copy(ejsTemplates = asset.worldBooks.flatMap { book ->
            book.entries.filter { "<%" in it.content }.map { entry ->
                NativeWorldBookReference(book.id, entry.id, sha256(entry.content))
            }
        })
    }

    fun preset(source: JsonObject): BrowserProgram =
        read(source["extensions"] as? JsonObject, "/extensions", "preset")

    private fun read(extensions: JsonObject?, pointer: String, scope: String): BrowserProgram {
        val raw = extensions?.get("tavern_helper")
        // Upstream migrates legacy character settings only when the new container is absent.
        val legacy = raw == null && scope == "character"
        val helper = when (raw) {
            null -> JsonObject(emptyMap())
            is JsonObject -> raw
            is JsonArray -> {
                val fields = linkedMapOf<String, JsonElement>()
                raw.forEach { item ->
                    val pair = item as? JsonArray
                    require(pair?.size == 2) { "$pointer/tavern_helper：助手字段必须为键值对" }
                    val key = (pair[0] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: error("$pointer/tavern_helper：助手字段名必须为字符串")
                    require(key !in fields) { "$pointer/tavern_helper：重复的助手字段" }
                    fields[key] = pair[1]
                }
                JsonObject(fields)
            }
            else -> error("$pointer/tavern_helper：不支持的助手容器")
        }
        val result = mutableListOf<BrowserScriptSource>()
        fun capture(obj: JsonObject, location: String, active: Boolean) {
            val source = (obj["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (source != null) {
                require(result.size < 512 && source.length <= 2 * 1024 * 1024) { "助手脚本超过运行限制" }
                val buttons = (obj["button"] as? JsonObject)?.get("buttons") as? JsonArray
                    ?: obj["buttons"] as? JsonArray ?: JsonArray(emptyList())
                val declaredId = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                val identity = declaredId ?: "$location\n$source"
                result += BrowserScriptSource("$scope:${sha256(identity).take(24)}", "$location/content", source,
                    sha256(source), active, buttons.mapNotNull { b ->
                        val v = b as? JsonObject ?: return@mapNotNull null
                        val name = (v["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                        BrowserScriptButton(name, (v["visible"] as? JsonPrimitive)?.booleanOrNull ?: true)
                    }, declaredId, obj["data"] as? JsonObject ?: JsonObject(emptyMap()))
            }
        }
        fun walk(items: JsonArray, path: String, enabled: Boolean, depth: Int) {
            require(depth < 16) { "助手脚本目录过深" }
            items.forEachIndexed { index, item ->
                val obj = item as? JsonObject ?: error("$path/$index：无效脚本")
                val active = enabled && ((obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true)
                val location = "$path/$index"
                capture(obj, location, active)
                (obj["scripts"] as? JsonArray)?.let { walk(it, "$location/scripts", active, depth + 1) }
            }
        }
        if (legacy) {
            val path = "$pointer/TavernHelper_scripts"
            val scripts = extensions?.get("TavernHelper_scripts") ?: JsonArray(emptyList())
            require(scripts is JsonArray) { "$path：旧版脚本必须为数组" }
            fun legacyScript(item: JsonElement, location: String) {
                val obj = item as? JsonObject ?: error("$location：无效旧版脚本")
                // Legacy ScriptData defaults to disabled; folders have no enable switch.
                capture(obj, location, (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false)
            }
            scripts.forEachIndexed { index, item ->
                val location = "$path/$index"
                val obj = item as? JsonObject ?: error("$location：无效旧版脚本")
                when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                    "script" -> legacyScript(obj["value"] ?: JsonObject(emptyMap()), "$location/value")
                    "folder" -> {
                        val children = obj["value"] ?: JsonArray(emptyList())
                        require(children is JsonArray) { "$location/value：旧版脚本目录必须为数组" }
                        children.forEachIndexed { childIndex, child -> legacyScript(child, "$location/value/$childIndex") }
                    }
                    else -> legacyScript(obj, location)
                }
            }
            return BrowserProgram(sources = result,
                variables = extensions?.get("TavernHelper_characterScriptVariables") as? JsonObject ?: JsonObject(emptyMap()))
        }
        val scripts = helper["scripts"]
        if (scripts != null) {
            require(scripts is JsonArray) { "$pointer/tavern_helper/scripts：脚本必须为数组" }
            val path = if (raw is JsonArray) "$pointer/tavern_helper/${raw.indexOfFirst { (it as? JsonArray)?.firstOrNull() == JsonPrimitive("scripts") }}/1"
                else "$pointer/tavern_helper/scripts"
            walk(scripts, path, true, 0)
        }
        return BrowserProgram(sources = result, variables = helper["variables"] as? JsonObject ?: JsonObject(emptyMap()))
    }

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

val CharacterSnapshot.mvuProgram: NativeMvuProgram? get() = browserProgram?.mvu ?: nativeAdaptation?.mvu
val CharacterSnapshot.ejsProgramTemplates: List<NativeWorldBookReference>
    get() = browserProgram?.ejsTemplates ?: nativeAdaptation?.ejsTemplates.orEmpty()

/**
 * 卡里是否存在只能由作者运行时执行的内容：启用的助手脚本或世界书 EJS 模板。
 * 两条路线都默认可达，因此这个标记只用于在角色详情说明原生模式不会运行什么，不作为入口开关。
 * MVU 程序必然来自启用的脚本，不需要再单独探测。
 */
val BrowserProgram.hasAuthorRuntime: Boolean
    get() = sources.any { it.enabled } || ejsTemplates.isNotEmpty()
