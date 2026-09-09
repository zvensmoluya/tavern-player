package io.github.zvensmoluya.tavernplayer.conversation.web

import com.dokar.quickjs.QuickJs
import io.github.zvensmoluya.tavernplayer.content.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive

/** Only parses programs here. Original Schema execution remains in the existing bounded MVU host. */
class BrowserProgramPreparer(private val loadParser: suspend () -> String) {
    suspend fun prepare(program: BrowserProgram): BrowserProgram = withContext(Dispatchers.Default) {
        val parser = loadParser()
        val engine = QuickJs.create(Dispatchers.Default)
        try {
            engine.memoryLimit = 64L * 1024 * 1024
            engine.maxStackSize = 1024L * 1024
            engine.evaluationTimeoutMillis = 2_000
            engine.evaluate<Any?>(parser)
            val selected = linkedSetOf<String>()
            val blocked = linkedSetOf<String>()
            var schema: BrowserScriptSource? = null
            val diagnostics = program.diagnostics.toMutableList()
            require(program.sources.all { BrowserProgramReader.sha256(it.content) == it.sha256 }) { "网页脚本与原文哈希不匹配" }
            program.sources.filter { it.enabled }.forEach { source ->
                val kind = try { engine.evaluate<String>("PlayerPrograms.inspect(${JsonPrimitive(source.content)})") }
                catch (_: Exception) {
                    diagnostics += "${source.id}：程序语法或跨引擎依赖不符合运行范围"
                    "invalid"
                }
                when (kind) {
                    "mvu-schema" -> {
                        require(schema == null) { "存在多个启用的 MVU Schema，不能自动合并" }
                        schema = source; selected += source.id
                    }
                    "mvu-loader" -> selected += source.id
                    "invalid" -> blocked += source.id
                }
            }
            val hasMvu = selected.isNotEmpty()
            program.copy(mvu = if (hasMvu) NativeMvuProgram(schema?.content.orEmpty()) else null,
                mvuSourceIds = selected, blockedSourceIds = blocked, diagnostics = diagnostics)
        } finally { engine.close() }
    }
}
