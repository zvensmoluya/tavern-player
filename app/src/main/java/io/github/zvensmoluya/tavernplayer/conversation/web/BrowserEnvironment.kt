package io.github.zvensmoluya.tavernplayer.conversation.web

import android.content.Context
import com.dokar.quickjs.QuickJs
import io.github.zvensmoluya.tavernplayer.characters.CharacterImageRepository
import io.github.zvensmoluya.tavernplayer.content.BrowserProgram
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI

class BrowserEnvironment(
    context: Context,
    val images: CharacterImageRepository,
    val resources: WebResourceRepository,
    val assetFile: (String, String) -> File?,
    val avatarFile: (String) -> File?,
) {
    private val context = context.applicationContext
    private val parser by lazy { asset("programs.js").toString(Charsets.UTF_8) }
    private val preparer by lazy { BrowserProgramPreparer { parser } }
    fun asset(name: String): ByteArray {
        require(name in ASSETS) { "网页运行资源不存在" }
        return context.assets.open("web/$name").use { it.readBytes() }
    }
    val fingerprint: String by lazy { io.github.zvensmoluya.tavernplayer.content.BrowserProgramReader.sha256(asset("manifest.json").toString(Charsets.UTF_8)) }
    suspend fun prepare(program: BrowserProgram) = preparer.prepare(program).copy(runtimeFingerprint = fingerprint)

    suspend fun scriptWithRedirectBase(bytes: ByteArray, original: String, finalUrl: String): ByteArray {
        if (original == finalUrl) return bytes
        return withContext(Dispatchers.Default) {
            val source = bytes.toString(Charsets.UTF_8)
            val engine = QuickJs.create(Dispatchers.Default)
            try {
                engine.memoryLimit = 64L * 1024 * 1024; engine.maxStackSize = 1024L * 1024; engine.evaluationTimeoutMillis = 2_000
                engine.evaluate<Any?>(parser)
                val imports = Json.parseToJsonElement(engine.evaluate<String>("PlayerPrograms.imports(${JsonPrimitive(source)})")).jsonArray
                var result = source
                imports.sortedByDescending { it.jsonObject.getValue("start").jsonPrimitive.int }.forEach { raw ->
                    val item = raw.jsonObject; val start = item.getValue("start").jsonPrimitive.int; val end = item.getValue("end").jsonPrimitive.int
                    val replacement = when {
                        item["dynamic"]?.jsonPrimitive?.booleanOrNull == true -> "new URL((${source.substring(start, end)}),${JsonPrimitive(finalUrl)}).href"
                        item["meta"]?.jsonPrimitive?.booleanOrNull == true -> "({url:${JsonPrimitive(finalUrl)}})"
                        else -> {
                            val value = item.getValue("value").jsonPrimitive.content
                            // Bare module names retain browser resolution (and its explicit missing-import error).
                            if (!value.startsWith('.') && !value.startsWith('/')) return@forEach
                            JsonPrimitive(URI(finalUrl).resolve(value).toString()).toString()
                        }
                    }
                    result = result.substring(0, start) + replacement + result.substring(end)
                }
                result.toByteArray(Charsets.UTF_8)
            } finally { engine.close() }
        }
    }

    suspend fun cssWithRedirectBase(text: String, finalUrl: String): String = withContext(Dispatchers.Default) {
        val engine = QuickJs.create(Dispatchers.Default)
        try {
            engine.memoryLimit = 64L * 1024 * 1024; engine.evaluationTimeoutMillis = 2_000
            engine.evaluate<Any?>(parser)
            val parsed = Json.parseToJsonElement(engine.evaluate<String>("PlayerPrograms.cssResources(${JsonPrimitive(text)})")).jsonObject
            val mapping = buildJsonObject {
                parsed.getValue("urls").jsonArray.forEach { item ->
                    val url = item.jsonPrimitive.content
                    if (!url.startsWith("data:") && !url.startsWith('#')) put(url, URI(finalUrl).resolve(url.replace(" ", "%20")).toString())
                }
            }
            Json.parseToJsonElement(engine.evaluate<String>("PlayerPrograms.cssResources(${JsonPrimitive(text)},$mapping)")).jsonObject.getValue("text").jsonPrimitive.content
        } finally { engine.close() }
    }

    companion object {
        val ASSETS = setOf("index.html", "parent.html", "chat.css", "message.css", "shell.js", "parent.js", "programs.js", "libraries.js",
            "jquery.js", "lodash.js", "vue.js", "manifest.json", "LICENSES.txt")
    }
}
