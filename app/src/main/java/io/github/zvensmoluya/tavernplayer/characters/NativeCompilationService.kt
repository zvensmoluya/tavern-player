package io.github.zvensmoluya.tavernplayer.characters

import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.*
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.conversation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NativeCompilationAttempt(
    val result: NativeCompilationResult,
    val response: String,
    val usage: GenerationUsage?,
    val model: String,
    val finishReason: String?,
)

/** One model request, then local completion. It never installs partial results or runs chat state confirmation. */
class NativeCompilationService(
    private val generator: ConversationGenerator,
    private val compiler: NativeAdaptationCompiler = NativeAdaptationCompiler(),
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
        val plan = withContext(Dispatchers.Default) { prepare(character, availableAssetIds, connection) }
        onPrepared(plan)
        val validation = generator.validateTokens(connection, plan)
        if (validation == null || validation.inputTokens.toLong() + plan.maxOutputTokens > checkNotNull(plan.declaredContextTokens)) {
            return NativeCompilationAttempt(
                NativeCompilationResult.Rejected(listOf(NativeAdaptationValidationIssue(
                    "input", "COMPILER_CONTEXT_LIMIT", "程序结构超出当前适配预算或无法估算；未截断或发送生成请求，请检查结构规模与模型预算",
                ))), "", null, connection.selectedModel, null,
            )
        }
        onProgress("正在分析行为与映射…")
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
        onProgress("正在本地组装并校验适配…")
        val result = if (finished) withContext(Dispatchers.Default) { compiler.complete(character, output.toString(), availableAssetIds) }
        else NativeCompilationResult.Rejected(listOf(NativeAdaptationValidationIssue(
            "response", "COMPILER_INCOMPLETE", "模型输出未完整结束；保留已有适配，可重新尝试",
        )))
        return NativeCompilationAttempt(result, output.toString(), usage, connection.selectedModel, finishReason)
    }

    private fun prepare(character: CharacterAsset, availableAssetIds: Set<String>, connection: StoredConnection): GenerationPlan {
        val limits = connection.effectiveTokenLimits()
        val context = (limits.contextTokens ?: 128_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val output = minOf(limits.outputTokens ?: 16_384L, 16_384L, context.toLong() - 1).toInt()
        require(context > 0 && output > 0) { "模型适配预算无效" }
        return GenerationPlan(
            messages = listOf(
                PreparedMessage(MessageRole.SYSTEM, NativeCompilationInstructions.text, PromptOrigin("native-compilation-contract", emptyList())),
                PreparedMessage(MessageRole.USER, compiler.prepare(character, availableAssetIds), PromptOrigin("native-compilation-source", listOf(character.sourceSha256))),
            ),
            maxOutputTokens = output,
            declaredContextTokens = context,
            assistantPrefill = "",
            presetId = NativeCompilationInstructions.VERSION,
            presetName = "角色卡适配",
            generationSettings = PresetGenerationSettings(
                maxContextTokens = context, maxOutputTokens = output, reasoningEffort = PresetReasoningEffort.HIGH,
                disabledParameters = PresetGenerationParameter.entries.toSet() - setOf(
                    PresetGenerationParameter.OUTPUT_LIMIT,
                    PresetGenerationParameter.REASONING_EFFORT,
                ),
            ),
            diagnostics = emptyList(), trace = emptyList(),
        )
    }
}
