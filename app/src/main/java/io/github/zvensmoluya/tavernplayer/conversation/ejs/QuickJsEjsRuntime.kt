package io.github.zvensmoluya.tavernplayer.conversation.ejs

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import io.github.zvensmoluya.tavernplayer.conversation.*
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Each template gets a fresh engine with generation inputs and a read-only title lookup. */
class QuickJsEjsRuntime(
    private val loadBundle: suspend () -> String = { error("此构建未提供 EJS 运行资源") },
    private val timeoutMillis: Long = 2_000,
) {
    suspend fun render(request: EjsTemplateRequest): String {
        val wire = Json.encodeToString(request)
        require(wire.length <= 2 * 1024 * 1024) { "EJS 输入超过限制" }
        val bundle = withContext(Dispatchers.IO) { loadBundle() }
        require(bundle.length <= 2 * 1024 * 1024)
        val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "ejs-quickjs") }.asCoroutineDispatcher()
        var engine: QuickJs? = null
        try {
            return withContext(dispatcher) {
                val js = QuickJs.create(dispatcher).also { engine = it }
                js.memoryLimit = 32L * 1024 * 1024
                js.maxStackSize = 1024L * 1024
                js.evaluationTimeoutMillis = timeoutMillis
                var remainingInput = 2 * 1024 * 1024 - wire.length
                js.function<String, String>("playerReadWorldInfo") { title ->
                    val content = request.worldInfoByTitle[title].orEmpty()
                    require(content.length <= remainingInput) { "EJS 输入超过限制" }
                    require("<%" !in content) { "getwi 不支持嵌套 EJS 模板" }
                    remainingInput -= content.length
                    content
                }
                withTimeout(timeoutMillis) {
                    js.evaluate<Any?>(bundle, filename = "ejs-runtime.js")
                    js.evaluate<String>("await PlayerEjs.render(JSON.parse(${JsonPrimitive(wire)}), playerReadWorldInfo)", filename = "ejs-template.js")
                        .also { require(it.length <= 262_144) { "EJS 输出超过限制" } }
                }
            }
        } finally {
            withContext(NonCancellable + dispatcher) { engine?.let { if (!it.isClosed) it.close() } }
            dispatcher.close()
        }
    }

    /** One cache per generation, shared by provider re-clips, never by cards or message branches. */
    suspend fun compile(
        compiler: GenerationPlanner,
        input: NormalGenerationInput,
        cache: MutableMap<EjsTemplateRequest, String>,
    ): CompilationResult {
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return compiler.compile(input.copy(ejsRenderer = { cache[it] ?: throw EjsRenderRequired(it) }))
            } catch (needed: EjsRenderRequired) {
                try {
                    require(cache.size < 128) { "EJS 本轮求值次数超过限制" }
                    cache[needed.request] = render(needed.request)
                } catch (cancelled: CancellationException) {
                    if (cancelled !is TimeoutCancellationException) throw cancelled
                    currentCoroutineContext().ensureActive()
                    return failure(needed.request.sourceId, "EJS 执行超时")
                } catch (_: Exception) {
                    // Engine errors may contain card source or state; show only its stable source ID.
                    return failure(needed.request.sourceId, "EJS 模板执行失败：语法、宿主接口或资源限制不满足")
                }
            }
        }
    }

    private fun failure(sourceId: String, message: String) = CompilationResult.Failure(
        listOf(CompilationDiagnostic(DiagnosticSeverity.ERROR, "EJS_EVALUATION_FAILED", message, sourceId)), emptyList(),
    )
}
