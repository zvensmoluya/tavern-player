package io.github.zvensmoluya.tavernplayer.conversation.web

import com.dokar.quickjs.QuickJs
import io.github.zvensmoluya.tavernplayer.content.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

@Serializable
private data class ModuleImports(val imports: List<String?>, val standaloneImport: String?)

private val schemaHelper = Regex("""https://(?:cdn|testingcf)\.jsdelivr\.net/gh/StageDog/tavern_resource/dist/util/mvu_zod\.js""")

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
            var incompatibleBundle = false
            val diagnostics = program.diagnostics.toMutableList()
            require(program.sources.all { BrowserProgramReader.sha256(it.content) == it.sha256 }) { "网页脚本与原文哈希不匹配" }
            program.sources.filter { it.enabled }.forEach { source ->
                val module = try {
                    val result = engine.evaluate<String>("JSON.stringify(PlayerPrograms.inspect(${JsonPrimitive(source.content)}))")
                    Json.decodeFromString<ModuleImports>(result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    diagnostics += "${source.pointer}：无法解析助手脚本"
                    blocked += source.id
                    return@forEach
                }
                val standalone = module.standaloneImport?.let(MvuBundle::from)
                val bundles = module.imports.filterNotNull().mapNotNull(MvuBundle::from)
                val incompatibility = standalone?.incompatibility ?: bundles.firstNotNullOfOrNull { it.incompatibility }
                val hasSchema = module.imports.any { it != null && schemaHelper.matches(it) }
                fun block(reason: String) {
                    diagnostics += "${source.pointer}：$reason，已停用该脚本"
                    blocked += source.id
                }
                when {
                    incompatibility != null -> {
                        incompatibleBundle = true
                        block(incompatibility)
                    }
                    standalone != null -> selected += source.id
                    bundles.isNotEmpty() -> block("MVU 加载与其他脚本逻辑混用，无法单独交给内置引擎")
                    hasSchema && !module.imports.all { it != null && schemaHelper.matches(it) } ->
                        block("MVU Schema 含有内置引擎无法加载的依赖")
                    hasSchema -> {
                        require(schema == null) { "存在多个启用的 MVU Schema，不能自动合并" }
                        schema = source
                        selected += source.id
                    }
                }
            }
            if (incompatibleBundle && selected.isNotEmpty()) {
                diagnostics += "同一程序声明了不支持的 MVU 依赖，已停用关联的 MVU 注册"
                blocked += selected
                selected.clear()
            }
            val hasMvu = selected.isNotEmpty()
            program.copy(mvu = if (hasMvu) NativeMvuProgram(schema?.content.orEmpty()) else null,
                mvuSourceIds = selected, blockedSourceIds = blocked, diagnostics = diagnostics)
        } finally { engine.close() }
    }
}
