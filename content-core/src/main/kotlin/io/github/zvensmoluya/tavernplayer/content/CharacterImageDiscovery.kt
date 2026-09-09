package io.github.zvensmoluya.tavernplayer.content

import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class CharacterImageReference(val id: String, val uri: String, val sourcePaths: List<String>, val localAssetId: String? = null)

data class CharacterImageDiscoveryResult(val references: List<CharacterImageReference>, val notices: List<String>)

/** Finds literal image references only. Does not interpret scripts, templates, or encoded programs. */
object CharacterImageDiscovery {
    private val urls = Regex("https?://[^\\s\"'<>`\\\\]+", RegexOption.IGNORE_CASE)
    private val inline = Regex("data:image/(?:png|jpeg|webp);base64,[A-Za-z0-9+/=]+", RegexOption.IGNORE_CASE)
    private val imageSource = Regex("<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val markdownImage = Regex("!\\[[^\\]\\r\\n]*\\]\\(\\s*(?:<([^<>]+)>|([^\\s)]+))")
    private val extensions = setOf("png", "jpg", "jpeg", "webp")

    fun discover(character: CharacterAsset): CharacterImageDiscoveryResult {
        val found = linkedMapOf<String, CharacterImageReference>()
        val notices = linkedSetOf<String>()
        fun add(raw: String, path: String, explicit: Boolean = false, assetId: String? = null) {
            val uri = raw.trim().replace("&amp;", "&")
            if (uri.length > 12 * 1024 * 1024) { notices += "部分内嵌图片过大，未列入准备清单"; return }
            if (listOf("{{", "${'$'}{", "<%", "{", "}").any(uri::contains)) {
                notices += "动态图片地址需要运行时信息，本次未解析"; return
            }
            val eligible = when {
                uri == "ccdefault:" -> assetId != null
                inline.matches(uri) -> true
                else -> {
                    val parsed = runCatching { URI(uri) }.getOrNull() ?: return
                    if (parsed.scheme?.lowercase() !in setOf("http", "https") || parsed.host.isNullOrBlank() || parsed.userInfo != null) return
                    val extension = parsed.path.orEmpty().substringAfterLast('.', "").lowercase()
                    if (extension in setOf("gif", "svg", "avif")) {
                        notices += "暂只准备 PNG、JPEG 和 WebP 图片"; return
                    }
                    explicit || extension in extensions
                }
            }
            if (!eligible) return
            val id = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val old = found[id]
            require(old != null || found.size < 512) { "图片引用超过 512 项，未截断清单或开始下载" }
            found[id] = CharacterImageReference(id, uri, (old?.sourcePaths.orEmpty() + path).distinct(), old?.localAssetId ?: assetId)
        }
        character.assets.forEachIndexed { index, asset ->
            if (asset.uri == "ccdefault:" || asset.uri.startsWith("data:image/") ||
                asset.type.lowercase() in setOf("icon", "background", "image") || asset.extension.lowercase().trimStart('.') in extensions) {
                add(asset.uri, "/data/assets/$index/uri", explicit = true, assetId = asset.id)
            }
        }
        fun visit(value: JsonElement, path: String, depth: Int) {
            require(depth <= 128) { "角色内容嵌套过深，无法整理图片引用" }
            when (value) {
                is JsonObject -> value.forEach { (key, child) -> visit(child, "$path/${key.replace("~", "~0").replace("/", "~1")}", depth + 1) }
                is JsonArray -> value.forEachIndexed { index, child -> visit(child, "$path/$index", depth + 1) }
                is JsonPrimitive -> if (value.isString) {
                    val text = value.content
                    imageSource.findAll(text).forEach { add(it.groupValues[1], path, explicit = true) }
                    markdownImage.findAll(text).forEach { add(it.groupValues[1].ifEmpty { it.groupValues[2] }, path, explicit = true) }
                    inline.findAll(text).forEach { add(it.value, path) }
                    urls.findAll(text).forEach { match ->
                        // Prose/Markdown delimiters are not part of the URI. Balanced URL parentheses remain intact.
                        var candidate = match.value.trimEnd('.', ',', ';', '，', '。', ']', '）')
                        while (candidate.endsWith(')') && candidate.count { it == ')' } > candidate.count { it == '(' }) candidate = candidate.dropLast(1)
                        add(candidate, path)
                    }
                }
            }
        }
        visit(character.rawCard, "", 0)
        return CharacterImageDiscoveryResult(found.values.toList(), notices.toList())
    }
}
