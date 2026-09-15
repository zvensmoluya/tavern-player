package io.github.zvensmoluya.tavernplayer.conversation

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingType

data class TokenCount(
    val tokens: Int,
    val quality: TokenCountQuality,
    val tokenizer: String,
)

interface TokenAccounting {
    fun count(messages: List<PreparedMessage>, modelId: String): TokenCount
    /** Null means the counter cannot safely subtract individual messages. */
    fun countParts(messages: List<PreparedMessage>, modelId: String): MessageTokenCounts? = null
}

data class MessageTokenCounts(val messages: List<Int>, val overhead: Int, val quality: TokenCountQuality, val tokenizer: String) {
    fun total() = TokenCount(messages.sum() + overhead, quality, tokenizer)
}

class DefaultTokenAccounting : TokenAccounting {
    private val registry by lazy { Encodings.newDefaultEncodingRegistry() }

    override fun count(messages: List<PreparedMessage>, modelId: String): TokenCount = countParts(messages, modelId).total()

    override fun countParts(messages: List<PreparedMessage>, modelId: String): MessageTokenCounts {
        val profile = profileFor(modelId)
        if (profile == null) {
            return MessageTokenCounts(
                messages = messages.map {
                    it.content.toByteArray(Charsets.UTF_8).size +
                        it.authorName.orEmpty().toByteArray(Charsets.UTF_8).size + MESSAGE_OVERHEAD
                },
                overhead = REQUEST_OVERHEAD,
                quality = TokenCountQuality.ESTIMATED,
                tokenizer = "utf8-byte-upper-bound",
            )
        }
        return try {
            val encoding: Encoding = registry.getEncoding(profile.encoding)
            MessageTokenCounts(
                messages = messages.map { message ->
                    encoding.countTokens(message.role.name.lowercase()) +
                        encoding.countTokens(message.content) +
                        message.authorName?.let(encoding::countTokens).orZero() +
                        profile.tokensPerMessage
                },
                overhead = profile.replyPrimerTokens,
                quality = if (profile.exactMessageFraming) TokenCountQuality.EXACT else TokenCountQuality.ESTIMATED,
                tokenizer = profile.encoding.name.lowercase() + if (profile.exactMessageFraming) "" else "+estimated-framing",
            )
        } catch (_: Exception) {
            MessageTokenCounts(
                messages = messages.map {
                    it.content.toByteArray(Charsets.UTF_8).size +
                        it.authorName.orEmpty().toByteArray(Charsets.UTF_8).size + MESSAGE_OVERHEAD
                },
                overhead = REQUEST_OVERHEAD,
                quality = TokenCountQuality.ESTIMATED,
                tokenizer = "utf8-byte-upper-bound",
            )
        }
    }

    fun countText(text: String, modelId: String): TokenCount {
        val profile = profileFor(modelId)
        if (profile == null) {
            return TokenCount(text.toByteArray(Charsets.UTF_8).size, TokenCountQuality.ESTIMATED, "utf8-byte-upper-bound")
        }
        return try {
            TokenCount(
                registry.getEncoding(profile.encoding).countTokens(text),
                TokenCountQuality.EXACT,
                profile.encoding.name.lowercase(),
            )
        } catch (_: Exception) {
            TokenCount(text.toByteArray(Charsets.UTF_8).size, TokenCountQuality.ESTIMATED, "utf8-byte-upper-bound")
        }
    }

    private fun profileFor(modelId: String): OpenAiTokenProfile? {
        val id = modelId.lowercase()
        return when {
            O200K_CHAT_MODEL.matches(id) -> OpenAiTokenProfile(EncodingType.O200K_BASE)
            CL100K_CHAT_MODEL.matches(id) -> OpenAiTokenProfile(
                EncodingType.CL100K_BASE,
                tokensPerMessage = if (id == "gpt-3.5-turbo-0301") 4 else 3,
            )
            P50K_TEXT_MODEL.matches(id) -> OpenAiTokenProfile(EncodingType.P50K_BASE, exactMessageFraming = false)
            R50K_TEXT_MODEL.matches(id) -> OpenAiTokenProfile(EncodingType.R50K_BASE, exactMessageFraming = false)
            else -> null
        }
    }

    companion object {
        private const val MESSAGE_OVERHEAD = 4
        private const val REQUEST_OVERHEAD = 3
        private val O200K_CHAT_MODEL = Regex("^(?:gpt-5(?:[.-].*)?|gpt-4\\.1(?:[.-].*)?|gpt-4o(?:[.-].*)?|o[134](?:[.-].*)?)$")
        private val CL100K_CHAT_MODEL = Regex("^(?:gpt-4(?:[.-].*)?|gpt-3\\.5-turbo(?:[.-].*)?)$")
        private val P50K_TEXT_MODEL = Regex("^(?:text-davinci-(?:002|003)|code-(?:davinci|cushman)(?:[.-].*)?)$")
        private val R50K_TEXT_MODEL = Regex("^(?:text-davinci-001|davinci|curie|babbage|ada)(?:[.-].*)?$")
    }

    private data class OpenAiTokenProfile(
        val encoding: EncodingType,
        val tokensPerMessage: Int = 3,
        val replyPrimerTokens: Int = 3,
        val exactMessageFraming: Boolean = true,
    )
}

private fun Int?.orZero(): Int = this ?: 0

data class TokenBudgetResult(
    val messages: List<PreparedMessage>,
    val report: TokenAccountingReport,
    val diagnostics: List<CompilationDiagnostic>,
    val trace: List<CompilationTraceEntry>,
    val failure: CompilationDiagnostic? = null,
)

class ContextBudgeter(
    private val accounting: TokenAccounting = DefaultTokenAccounting(),
) {
    fun contextLimit(input: NormalGenerationInput): Int? {
        val declaredBudget = input.preset.generationSettings.maxContextTokens
        val verifiedLimit = input.modelContextTokens
        return when {
            verifiedLimit != null && declaredBudget != null -> minOf(verifiedLimit, declaredBudget)
            verifiedLimit != null -> verifiedLimit
            declaredBudget != null -> declaredBudget
            else -> null
        }
    }

    fun outputLimit(input: NormalGenerationInput): Int {
        val contextLimit = contextLimit(input)
        val requestedOutput = input.preset.generationSettings.maxOutputTokens
        val verifiedLimit = input.modelOutputTokens
        return minOf(requestedOutput, verifiedLimit ?: Int.MAX_VALUE, contextLimit ?: Int.MAX_VALUE)
    }

    fun budget(
        messages: List<PreparedMessage>,
        input: NormalGenerationInput,
    ): TokenBudgetResult {
        val contextLimit = contextLimit(input)
        val outputTokens = outputLimit(input)
        val inputLimit = minOf(
            contextLimit?.let { (it - outputTokens).coerceAtLeast(0) } ?: Int.MAX_VALUE,
            input.maxInputTokens ?: Int.MAX_VALUE,
        )
        val working = messages.toMutableList()
        val trace = mutableListOf<CompilationTraceEntry>()
        val diagnostics = mutableListOf<CompilationDiagnostic>()
        if (input.modelContextTokens == null) {
            val declaredContext = input.preset.generationSettings.maxContextTokens
            diagnostics += CompilationDiagnostic(
                severity = DiagnosticSeverity.WARNING,
                code = if (declaredContext == null) {
                    "MODEL_CONTEXT_BUDGET_UNKNOWN"
                } else {
                    "MODEL_CONTEXT_BUDGET_UNVERIFIED"
                },
                message = if (declaredContext == null) {
                    "模型目录与 Preset 均未提供 context；本轮不设置本地上下文上限，由 Provider 判定请求是否超限"
                } else {
                    "模型目录未提供 context 上限；本轮按 Preset 声明分配 $contextLimit tokens，未经 Provider 验证"
                },
            )
        }
        val requestedOutputTokens = input.preset.generationSettings.maxOutputTokens
        if (input.modelOutputTokens == null) {
            diagnostics += CompilationDiagnostic(
                severity = DiagnosticSeverity.WARNING,
                code = "MODEL_OUTPUT_BUDGET_UNVERIFIED",
                message = if (outputTokens < requestedOutputTokens) {
                    "模型目录未提供回复上限；Preset 请求 $requestedOutputTokens tokens，已受本轮 context 预算约束为 $outputTokens tokens，未经 Provider 验证"
                } else {
                    "模型目录未提供回复上限；本轮按 Preset 请求预留 $outputTokens tokens，未经 Provider 验证"
                },
            )
        } else if (outputTokens < requestedOutputTokens) {
            val clampedByContext = contextLimit != null && contextLimit < minOf(requestedOutputTokens, input.modelOutputTokens)
            diagnostics += CompilationDiagnostic(
                severity = DiagnosticSeverity.WARNING,
                code = if (clampedByContext) "OUTPUT_BUDGET_CONTEXT_CLAMPED" else "MODEL_OUTPUT_LIMIT_CLAMPED",
                message = if (clampedByContext) {
                    "Preset 请求 $requestedOutputTokens tokens，模型回复上限为 ${input.modelOutputTokens}，已受本轮 context 预算约束为 $outputTokens tokens"
                } else {
                    "Preset 请求 $requestedOutputTokens tokens，已按模型回复上限收敛为 $outputTokens tokens"
                },
            )
        }
        val parts = accounting.countParts(working, input.modelId)
        val costs = parts?.messages?.toMutableList()
        var count = parts?.total() ?: accounting.count(working, input.modelId)
        var verified = parts == null

        while (true) {
            if (count.tokens <= inputLimit) {
                if (verified) break
                count = accounting.count(working, input.modelId)
                verified = true
                if (count.tokens <= inputLimit) break
            }
            val removableIndex = oldestRemovableIndex(working)
            if (removableIndex < 0) break
            val removed = working.removeAt(removableIndex)
            trace += CompilationTraceEntry(
                stage = "context-budget",
                sourceIds = removed.origin.sourceIds,
                decision = "dropped ${removed.origin.stage} to fit context",
                role = removed.role,
            )
            count = if (costs != null && !verified) count.copy(tokens = count.tokens - costs.removeAt(removableIndex))
                else accounting.count(working, input.modelId)
        }
        if (!verified) count = accounting.count(working, input.modelId)

        val report = TokenAccountingReport(
            inputTokens = count.tokens,
            contextLimit = contextLimit,
            reservedOutputTokens = outputTokens,
            quality = count.quality,
            tokenizer = count.tokenizer,
        )
        diagnostics += CompilationDiagnostic(
            severity = DiagnosticSeverity.WARNING,
            code = if (count.quality == TokenCountQuality.EXACT) "TOKEN_COUNT_EXACT" else "TOKEN_COUNT_ESTIMATED",
            message = "Context ${contextLimit ?: "未声明"}，输入 ${count.tokens}，预留回复 $outputTokens；计数器 ${count.tokenizer}",
        )
        val failure = if (count.tokens > inputLimit) {
            CompilationDiagnostic(
                DiagnosticSeverity.ERROR,
                "MANDATORY_CONTEXT_OVERFLOW",
                if (working.any { it.required }) "始终注入的世界书内容与必选提示合计超过本轮容量，无法发送；请减少始终注入的内容或增加上下文容量"
                else "必选 Prompt（${count.tokens} tokens）超过本轮输入预算 $inputLimit（context $contextLimit，回复预留 $outputTokens）",
            )
        } else {
            null
        }
        return TokenBudgetResult(working, report, diagnostics, trace, failure)
    }

    private fun oldestRemovableIndex(messages: List<PreparedMessage>): Int {
        val history = messages.indexOfFirst { !it.required && it.origin.stage == "chat-history" && !it.isLastUserMessage(messages) }
        if (history >= 0) return history
        val examples = messages.indexOfFirst { !it.required && it.origin.stage == "dialogue-example" }
        if (examples >= 0) return examples
        val world = messages.indexOfFirst { !it.required && it.origin.stage == "world-book" }
        return world
    }

    private fun PreparedMessage.isLastUserMessage(messages: List<PreparedMessage>): Boolean =
        role == MessageRole.USER && this === messages.lastOrNull { it.origin.stage == "chat-history" && it.role == MessageRole.USER }
}
