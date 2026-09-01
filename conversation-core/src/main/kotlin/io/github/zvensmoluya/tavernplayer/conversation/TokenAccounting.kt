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
}

class DefaultTokenAccounting : TokenAccounting {
    private val registry by lazy { Encodings.newDefaultEncodingRegistry() }

    override fun count(messages: List<PreparedMessage>, modelId: String): TokenCount {
        val profile = profileFor(modelId)
        if (profile == null) {
            return TokenCount(
                tokens = messages.sumOf {
                    it.content.toByteArray(Charsets.UTF_8).size +
                        it.authorName.orEmpty().toByteArray(Charsets.UTF_8).size + MESSAGE_OVERHEAD
                } + REQUEST_OVERHEAD,
                quality = TokenCountQuality.ESTIMATED,
                tokenizer = "utf8-byte-upper-bound",
            )
        }
        return try {
            val encoding: Encoding = registry.getEncoding(profile.encoding)
            TokenCount(
                tokens = messages.sumOf { message ->
                    encoding.countTokens(message.role.name.lowercase()) +
                        encoding.countTokens(message.content) +
                        message.authorName?.let(encoding::countTokens).orZero() +
                        profile.tokensPerMessage
                } + profile.replyPrimerTokens,
                quality = if (profile.exactMessageFraming) TokenCountQuality.EXACT else TokenCountQuality.ESTIMATED,
                tokenizer = profile.encoding.name.lowercase() + if (profile.exactMessageFraming) "" else "+estimated-framing",
            )
        } catch (_: Exception) {
            TokenCount(
                tokens = messages.sumOf {
                    it.content.toByteArray(Charsets.UTF_8).size +
                        it.authorName.orEmpty().toByteArray(Charsets.UTF_8).size + MESSAGE_OVERHEAD
                } + REQUEST_OVERHEAD,
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
    fun contextLimit(input: NormalGenerationInput): Int {
        val modelLimit = input.modelContextTokens ?: knownContextLimit(input.modelId) ?: DEFAULT_CONTEXT_LIMIT
        return input.preset.generationSettings.maxContextTokens?.let { minOf(it, modelLimit) } ?: modelLimit
    }

    fun outputLimit(input: NormalGenerationInput): Int {
        val contextLimit = contextLimit(input)
        val modelLimit = input.modelOutputTokens ?: unknownModelOutputLimit(contextLimit)
        return minOf(input.preset.generationSettings.maxOutputTokens, modelLimit)
    }

    fun budget(
        messages: List<PreparedMessage>,
        input: NormalGenerationInput,
    ): TokenBudgetResult {
        val contextLimit = contextLimit(input)
        val outputTokens = outputLimit(input)
        val inputLimit = minOf(
            (contextLimit - outputTokens).coerceAtLeast(0),
            input.maxInputTokens ?: Int.MAX_VALUE,
        )
        val working = messages.toMutableList()
        val trace = mutableListOf<CompilationTraceEntry>()
        val diagnostics = mutableListOf<CompilationDiagnostic>()
        if (input.modelContextTokens == null && knownContextLimit(input.modelId) == null) {
            diagnostics += CompilationDiagnostic(
                severity = DiagnosticSeverity.WARNING,
                code = "MODEL_CONTEXT_LIMIT_FALLBACK",
                message = "模型目录未提供 context 上限，且模型名称未命中已知表；本轮使用 $contextLimit tokens 的安全上限",
            )
        }
        val requestedOutputTokens = input.preset.generationSettings.maxOutputTokens
        if (outputTokens < requestedOutputTokens) {
            diagnostics += CompilationDiagnostic(
                severity = DiagnosticSeverity.WARNING,
                code = if (input.modelOutputTokens == null) {
                    "MODEL_OUTPUT_LIMIT_FALLBACK"
                } else {
                    "MODEL_OUTPUT_LIMIT_CLAMPED"
                },
                message = if (input.modelOutputTokens == null) {
                    "模型目录未提供回复上限；Preset 请求 $requestedOutputTokens tokens，本轮安全预留 $outputTokens tokens"
                } else {
                    "Preset 请求 $requestedOutputTokens tokens，已按模型回复上限收敛为 $outputTokens tokens"
                },
            )
        }
        var count = accounting.count(working, input.modelId)

        while (count.tokens > inputLimit) {
            val removableIndex = oldestRemovableIndex(working)
            if (removableIndex < 0) break
            val removed = working.removeAt(removableIndex)
            trace += CompilationTraceEntry(
                stage = "context-budget",
                sourceIds = removed.origin.sourceIds,
                decision = "dropped ${removed.origin.stage} to fit context",
                role = removed.role,
            )
            count = accounting.count(working, input.modelId)
        }

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
            message = "Context $contextLimit，输入 ${count.tokens}，预留回复 $outputTokens；计数器 ${count.tokenizer}",
        )
        val failure = if (count.tokens > inputLimit) {
            CompilationDiagnostic(
                DiagnosticSeverity.ERROR,
                "MANDATORY_CONTEXT_OVERFLOW",
                "必选 Prompt（${count.tokens} tokens）超过本轮输入预算 $inputLimit（context $contextLimit，回复预留 $outputTokens）",
            )
        } else {
            null
        }
        return TokenBudgetResult(working, report, diagnostics, trace, failure)
    }

    private fun oldestRemovableIndex(messages: List<PreparedMessage>): Int {
        val history = messages.indexOfFirst { it.origin.stage == "chat-history" && !it.isLastUserMessage(messages) }
        if (history >= 0) return history
        val examples = messages.indexOfFirst { it.origin.stage == "dialogue-example" }
        if (examples >= 0) return examples
        val world = messages.indexOfFirst { it.origin.stage == "world-book" }
        return world
    }

    private fun PreparedMessage.isLastUserMessage(messages: List<PreparedMessage>): Boolean =
        role == MessageRole.USER && this === messages.lastOrNull { it.origin.stage == "chat-history" && it.role == MessageRole.USER }

    private fun knownContextLimit(modelId: String): Int? {
        val id = modelId.lowercase()
        return when {
            id.startsWith("gpt-5") || id.startsWith("gpt-4.1") || id.startsWith("gpt-4o") -> 128_000
            id.startsWith("gpt-4-turbo") -> 128_000
            id.startsWith("gpt-4") -> 8_192
            id.startsWith("gpt-3.5-turbo") -> 16_385
            id.startsWith("claude-") -> 200_000
            id.startsWith("gemini-") -> 1_000_000
            else -> null
        }
    }

    private fun unknownModelOutputLimit(contextLimit: Int): Int =
        minOf(DEFAULT_UNKNOWN_MODEL_OUTPUT_LIMIT, (contextLimit / 2).coerceAtLeast(1))

    companion object {
        private const val DEFAULT_CONTEXT_LIMIT = 32_768
        private const val DEFAULT_UNKNOWN_MODEL_OUTPUT_LIMIT = 16_384
    }
}
