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
        val raw = extensions?.get("tavern_helper") ?: return BrowserProgram()
        val helper = when (raw) {
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
        fun walk(items: JsonArray, path: String, enabled: Boolean, depth: Int) {
            require(depth < 16) { "助手脚本目录过深" }
            items.forEachIndexed { index, item ->
                val obj = item as? JsonObject ?: error("$path/$index：无效脚本")
                val active = enabled && ((obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true)
                val location = "$path/$index"
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
                (obj["scripts"] as? JsonArray)?.let { walk(it, "$location/scripts", active, depth + 1) }
            }
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
