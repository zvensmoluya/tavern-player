package io.github.zvensmoluya.tavernplayer.conversation.script

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.ModuleContent
import com.dokar.quickjs.moduleLoader
import com.dokar.quickjs.binding.asyncFunction
import io.github.zvensmoluya.tavernplayer.content.*
import io.github.zvensmoluya.tavernplayer.conversation.*
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Fresh ES modules per projection/action. No Android objects, browser, URLs or mutable display host. */
class QuickJsNativeRuntime(private val timeoutMillis: Long = 2_000, private val actionTimeoutMillis: Long = 120_000) {
    suspend fun validate(program: NativeScriptProgram) = engine(program, timeoutMillis) { js ->
        load(js, program)
    }

    suspend fun present(program: NativeScriptProgram, context: JsonObject, revision: String): List<NativeRenderedSurface> =
        engine(program, timeoutMillis) { js ->
            load(js, program)
            val wire = bounded(context.toString())
            js.evaluate<Any?>("globalThis.__context = JSON.parse(${JsonPrimitive(wire)}); " + FREEZE + " freeze(__context);")
            program.surfaces.mapNotNull { entry ->
                val value = js.evaluate<String>(
                    "JSON.stringify((await __modules[${JsonPrimitive(entry.module)}][${JsonPrimitive(entry.export)}](__context)) ?? null)",
                )
                require(value.length <= 262_144) { "Surface 输出过大" }
                if (value == "null") null else {
                    val surface = Json.decodeFromString<NativeSurfaceData>(value)
                    NativeScriptValidator.validateSurface(surface, entry, program)
                    NativeRenderedSurface(entry.id, revision, surface)
                }
            }
        }

    suspend fun invoke(
        program: NativeScriptProgram, handlerId: String, context: JsonObject, args: JsonObject, input: Map<String, String>,
        host: suspend (String, JsonElement) -> JsonElement,
    ) = engine(program, actionTimeoutMillis) { js ->
        // Top-level imports run before any native capability is bound.
        load(js, program)
        js.evaluationTimeoutMillis = actionTimeoutMillis
        js.asyncFunction("__nativeCall") { arguments ->
            val method = arguments.getOrNull(0) as? String ?: error("缺少宿主方法")
            val capability = when (method) {
                "variables.read" -> NativeScriptCapability.VARIABLES_READ
                "variables.replaceMvu" -> NativeScriptCapability.MVU_REPLACE
                "program.replace" -> NativeScriptCapability.PROGRAM_STATE_REPLACE
                "draft.replace" -> NativeScriptCapability.DRAFT_REPLACE
                "generation.text" -> NativeScriptCapability.GENERATE_TEXT
                else -> error("未知宿主方法")
            }
            require(capability in program.capabilities) { "模块未声明宿主能力" }
            val data = Json.parseToJsonElement(bounded(arguments.getOrNull(1) as? String ?: "null"))
            // The operation coordinator checks cancellation and durable identity again before effects.
            bounded(host(method, data).toString())
        }
        val entry = program.handlers.single { it.id == handlerId }
        js.evaluate<Any?>("""
            globalThis.__context = JSON.parse(${JsonPrimitive(bounded(context.toString()))});
            $FREEZE
            freeze(__context);
            globalThis.__actionContext = Object.freeze({ ...__context,
                variables: Object.freeze({
                    read: async () => JSON.parse(await __nativeCall('variables.read', 'null')),
                    replaceMvu: async value => JSON.parse(await __nativeCall('variables.replaceMvu', JSON.stringify(value)))
                }),
                program: Object.freeze({replace: async value => JSON.parse(await __nativeCall('program.replace', JSON.stringify(value)))}),
                draft: Object.freeze({replace: async value => JSON.parse(await __nativeCall('draft.replace', JSON.stringify(value)))}),
                generation: Object.freeze({text: async request => JSON.parse(await __nativeCall('generation.text', JSON.stringify(request)))})
            });
            await __modules[${JsonPrimitive(entry.module)}][${JsonPrimitive(entry.export)}](
                __actionContext, JSON.parse(${JsonPrimitive(args.toString())}),
                JSON.parse(${JsonPrimitive(JsonObject(input.mapValues { JsonPrimitive(it.value) }).toString())}));
        """.trimIndent())
        Unit
    }

    private suspend fun load(js: QuickJs, program: NativeScriptProgram) = withTimeout(timeoutMillis) {
        js.evaluationTimeoutMillis = timeoutMillis
        js.evaluate<Any?>("globalThis.__modules = Object.create(null)")
        val imports = program.modules.mapIndexed { i, module -> "import * as m$i from ${JsonPrimitive(module.id)};" }.joinToString("\n")
        val assignments = program.modules.mapIndexed { i, module -> "__modules[${JsonPrimitive(module.id)}] = m$i;" }.joinToString("\n")
        js.evaluate<Any?>("$imports\n$assignments", filename = "player-entry", asModule = true)
        val entries = program.surfaces.map { it.module to it.export } + program.handlers.map { it.module to it.export }
        entries.forEach { (module, export) ->
            require(js.evaluate<Boolean>("typeof __modules[${JsonPrimitive(module)}][${JsonPrimitive(export)}] === 'function'")) { "缺少声明的模块导出" }
        }
    }

    private suspend fun <T> engine(program: NativeScriptProgram, timeout: Long, block: suspend (QuickJs) -> T): T {
        val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "native-script") }.asCoroutineDispatcher()
        var engine: QuickJs? = null
        try {
            return withContext(dispatcher) {
                val modules = program.modules.associate { it.id to it.code }
                val js = QuickJs.create(dispatcher, moduleLoader = moduleLoader {
                    load { name -> modules[name]?.let(ModuleContent::Source) }
                }).also { engine = it }
                js.memoryLimit = 32L * 1024 * 1024
                js.maxStackSize = 1024L * 1024
                // Library timeout covers elapsed evaluation, including suspended host calls.
                js.evaluationTimeoutMillis = timeout
                withTimeout(timeout) { block(js) }
            }
        } finally {
            withContext(NonCancellable + dispatcher) { engine?.let { if (!it.isClosed) it.close() } }
            dispatcher.close()
        }
    }

    private fun bounded(value: String): String = value.also { require(it.length <= 2 * 1024 * 1024) { "JS 数据超过限制" } }

    companion object {
        private const val FREEZE = "function freeze(x) { if (x && typeof x === 'object' && !Object.isFrozen(x)) { Object.freeze(x); Object.values(x).forEach(freeze); } return x; }"
    }
}
