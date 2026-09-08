package io.github.zvensmoluya.tavernplayer.characters

import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.*
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.conversation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NativeCompilationAttempt(
    val result: NativeCompilationResult,
    val response: String,
    /** Main compilation usage only; routing is recorded separately, including when selection fails. */
    val usage: GenerationUsage?,
    val model: String,
    val finishReason: String?,
    val routing: NativeCompilationRoutingAttempt? = null,
)

data class NativeCompilationRoutingAttempt(
    val response: String, val usage: GenerationUsage?, val finishReason: String?,
    val selection: NativeCompilationSelection?,
)

/** Select ownership, compile with its scoped contract, then complete locally. It never installs partial results or runs chat state confirmation. */
class NativeCompilationService(
    private val generator: ConversationGenerator,
    private val compiler: NativeAdaptationCompiler = NativeAdaptationCompiler(),
    private val mvuRuntime: io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuConversationRuntime =
        io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuConversationRuntime(),
) {
    suspend fun compile(
        character: CharacterAsset,
        availableAssetIds: Set<String>,
        connection: StoredConnection,
        onProgress: (String) -> Unit = {},
        onPrepared: (GenerationPlan) -> Unit = {},
    ): NativeCompilationAttempt {
        require(connection.selectedModel.isNotBlank()) { "请先选择用于适配的模型" }
        onProgress("正在整理程序源码与关联规则…")
        val source = withContext(Dispatchers.Default) { compiler.prepare(character, availableAssetIds) }
        val routingPlan = prepare(source, character.sourceSha256, connection, selection = null)
        onPrepared(routingPlan)
        onProgress("正在识别状态来源…")
        val routed = generate(connection, routingPlan)
        val selectionResult = if (routed.finished) withContext(Dispatchers.Default) {
            compiler.select(character, routed.response, availableAssetIds)
        } else NativeCompilationSelectionResult.Rejected(incomplete("selection").issues)
        val selection = (selectionResult as? NativeCompilationSelectionResult.Ready)?.selection
        val routing = NativeCompilationRoutingAttempt(routed.response, routed.usage, routed.finishReason, selection)
        if (selectionResult is NativeCompilationSelectionResult.Rejected) return NativeCompilationAttempt(
            NativeCompilationResult.Rejected(selectionResult.issues), "", null, connection.selectedModel, null, routing)
        val plan = prepare(source, character.sourceSha256, connection, checkNotNull(selection))
        onPrepared(plan)
        // Compilation preserves the complete program. Provider accounting decides whether it fits;
        // the conservative UTF-8 upper bound is not a reason to reject or trim source code.
        onProgress("正在分析行为与映射…")
        val generated = generate(connection, plan)
        onProgress("正在整理响应格式、校验执行契约并组装适配…")
        var result = if (generated.finished) withContext(Dispatchers.Default) {
            compiler.complete(character, generated.response, availableAssetIds, selection)
        } else incomplete("response")
        (result as? NativeCompilationResult.Ready)?.adaptation?.script?.let { program ->
            onProgress("正在加载 JS 模块并校验导出入口…")
            try {
                io.github.zvensmoluya.tavernplayer.conversation.script.QuickJsNativeRuntime().validate(program)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                result = scriptFailure(true)
            } catch (_: Exception) { result = scriptFailure(false) }
        }
        (result as? NativeCompilationResult.Ready)?.adaptation?.takeIf { it.mvu != null }?.let { adaptation ->
            onProgress("正在验证原 MVU 程序与开场初始化…")
            try {
                mvuRuntime.validateProgram(character.copy(nativeAdaptation = adaptation).snapshot())
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                result = mvuFailure(true)
            } catch (_: Exception) { result = mvuFailure(false) }
        }
        return NativeCompilationAttempt(result, generated.response, generated.usage, connection.selectedModel, generated.finishReason, routing)
    }

    private data class Generated(val response: String, val usage: GenerationUsage?, val finished: Boolean, val finishReason: String?)

    private suspend fun generate(connection: StoredConnection, plan: GenerationPlan): Generated {
        val output = StringBuilder()
        var usage: GenerationUsage? = null
        var finished = false
        var finishReason: String? = null
        generator.stream(connection, plan).collect { event ->
            when (event) {
                is GenerationEvent.TextDelta -> {
                    require(output.length.toLong() + event.text.length <= NativeAdaptationCompiler.MAX_OUTPUT_CHARS) { "适配输出超过大小限制" }
                    output.append(event.text)
                }
                is GenerationEvent.Usage -> usage = event.value
                is GenerationEvent.Finished -> {
                    finishReason = event.reason
                    finished = event.reason in setOf("completed", "stop", "end_turn", "STOP", "stop_sequence")
                }
                else -> Unit
            }
        }
        return Generated(output.toString(), usage, finished, finishReason)
    }

    private fun incomplete(path: String) = NativeCompilationResult.Rejected(listOf(NativeAdaptationValidationIssue(
        path, "COMPILER_INCOMPLETE", "模型输出未完整结束；保留已有适配，可重新尝试")))

    private fun mvuFailure(timedOut: Boolean) = NativeCompilationResult.Rejected(listOf(NativeAdaptationValidationIssue(
        "mvu", if (timedOut) "MVU_INITIALIZATION_TIMEOUT" else "MVU_INITIALIZATION_FAILED",
        if (timedOut) "原 MVU 程序初始化超时；已有适配未更改"
        else "原 MVU 程序或开场初始化失败；已有适配未更改",
    )))

    private fun scriptFailure(timedOut: Boolean) = NativeCompilationResult.Rejected(listOf(NativeAdaptationValidationIssue(
        "script", if (timedOut) "SCRIPT_LOAD_TIMEOUT" else "SCRIPT_LOAD_FAILED",
        if (timedOut) "执行契约已通过，但 JS 模块加载超时；已有适配未更改"
        else "执行契约已通过，但 JS 模块加载失败：请检查语法、导出或依赖；已有适配未更改",
    )))

    private fun prepare(source: String, sourceSha256: String, connection: StoredConnection,
                        selection: NativeCompilationSelection?): GenerationPlan {
        val limits = connection.effectiveTokenLimits()
        val context = limits.contextTokens?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        // Zero is an internal disabled value, never an optional provider output limit.
        // Anthropic requires max_tokens; use the declared model limit, or a 64K fallback.
        val output = (limits.outputTokens ?: if (connection.protocol == ModelProtocol.ANTHROPIC_MESSAGES) 65_536L else 0L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val enabled = setOf(PresetGenerationParameter.REASONING_EFFORT) +
            if (output > 0) setOf(PresetGenerationParameter.OUTPUT_LIMIT) else emptySet()
        return GenerationPlan(
            messages = listOf(
                PreparedMessage(MessageRole.SYSTEM, selection?.let(NativeCompilationInstructions::text) ?: NativeCompilationInstructions.selectionText,
                    PromptOrigin(if (selection == null) "native-compilation-selection" else "native-compilation-contract", emptyList())),
                PreparedMessage(MessageRole.USER, source, PromptOrigin("native-compilation-source", listOf(sourceSha256))),
            ),
            maxOutputTokens = output,
            declaredContextTokens = context,
            assistantPrefill = "",
            presetId = NativeCompilationInstructions.VERSION,
            presetName = "角色卡适配",
            generationSettings = PresetGenerationSettings(
                maxContextTokens = context, maxOutputTokens = output, reasoningEffort = if (selection == null) PresetReasoningEffort.LOW else PresetReasoningEffort.HIGH,
                disabledParameters = PresetGenerationParameter.entries.toSet() - enabled,
            ),
            diagnostics = emptyList(), trace = emptyList(),
        )
    }
}
