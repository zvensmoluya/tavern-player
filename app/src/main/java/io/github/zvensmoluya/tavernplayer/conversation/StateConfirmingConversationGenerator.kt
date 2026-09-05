package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.LegacyStateDialect
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationParameter
import io.github.zvensmoluya.tavernplayer.content.PresetReasoningEffort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Keeps roleplay text as the primary model task, then confirms state separately only when the
 * primary response omitted the declared machine envelope. It never creates or changes an
 * adaptation: the recovery request can use only the already validated NativeAdaptation contract.
 */
class StateConfirmingConversationGenerator(
    private val delegate: ConversationGenerator,
    private val adaptationRuntime: NativeAdaptationRuntime = NativeAdaptationRuntime(),
    private val recoveryObserver: (String) -> Unit = {},
) : ConversationGenerator {
    override suspend fun validateTokens(
        connection: StoredConnection,
        plan: GenerationPlan,
    ): ProviderTokenValidation? = delegate.validateTokens(connection, plan)

    override fun stream(connection: StoredConnection, plan: GenerationPlan): Flow<GenerationEvent> = flow {
        val adaptation = plan.nativeAdaptation
        if (adaptation == null || adaptation.assistantStateAdapters.isEmpty()) {
            delegate.stream(connection, plan).collect { emit(it) }
            return@flow
        }

        val mainText = StringBuilder()
        var mainFinished: GenerationEvent.Finished? = null
        var combinedUsage: GenerationUsage? = null
        delegate.stream(connection, plan).collect { event ->
            when (event) {
                is GenerationEvent.TextDelta -> {
                    mainText.append(event.text)
                    emit(event)
                }
                is GenerationEvent.Usage -> combinedUsage = combinedUsage.plus(event.value)
                is GenerationEvent.Finished -> mainFinished = event
                else -> emit(event)
            }
        }

        val finished = mainFinished
        if (finished == null) {
            combinedUsage?.let { emit(GenerationEvent.Usage(it)) }
            return@flow
        }
        if (mainText.isBlank()) {
            combinedUsage?.let { emit(GenerationEvent.Usage(it)) }
            emit(GenerationEvent.Diagnostic("主回复没有正文，未执行状态确认"))
            emit(finished)
            return@flow
        }

        when (val status = adaptationRuntime.projectAssistantMessage(adaptation, mainText.toString()).envelopeStatus) {
            AssistantStateEnvelopeStatus.NONE,
            AssistantStateEnvelopeStatus.PENDING,
            AssistantStateEnvelopeStatus.INVALID,
            -> {
                val reason = when (status) {
                    AssistantStateEnvelopeStatus.NONE -> "主回复未包含状态确认块"
                    AssistantStateEnvelopeStatus.PENDING -> "主回复的状态确认块未闭合"
                    AssistantStateEnvelopeStatus.INVALID -> "主回复的状态确认块畸形或有歧义"
                    AssistantStateEnvelopeStatus.STRIPPED,
                    AssistantStateEnvelopeStatus.RECOVERED,
                    -> error("unreachable")
                }
                emit(GenerationEvent.Diagnostic("$reason，正在执行一次独立状态确认"))
                val recovery = recover(connection, recoveryPlan(plan, mainText.toString()))
                recoveryObserver(recovery.text)
                combinedUsage = combinedUsage.plus(recovery.usage)
                val projection = adaptationRuntime.projectAssistantMessage(adaptation, recovery.text)
                if (recovery.finished && projection.envelopeStatus == AssistantStateEnvelopeStatus.STRIPPED) {
                    emit(GenerationEvent.AssistantStateConfirmed(checkNotNull(projection.stateEnvelope)))
                    emit(GenerationEvent.Diagnostic("独立状态确认已完成"))
                } else {
                    emit(GenerationEvent.Diagnostic("独立状态确认未返回唯一完整块，本轮状态保持未确认"))
                }
            }
            AssistantStateEnvelopeStatus.STRIPPED,
            AssistantStateEnvelopeStatus.RECOVERED,
            -> Unit
        }
        combinedUsage?.let { emit(GenerationEvent.Usage(it)) }
        emit(finished)
    }

    private suspend fun recover(
        connection: StoredConnection,
        plan: GenerationPlan,
    ): RecoveryResult {
        val text = StringBuilder()
        var usage: GenerationUsage? = null
        var finished = false
        try {
            delegate.stream(connection, plan).collect { event ->
                when (event) {
                    is GenerationEvent.TextDelta -> text.append(event.text)
                    is GenerationEvent.Usage -> usage = usage.plus(event.value)
                    is GenerationEvent.Finished -> finished = true
                    else -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return RecoveryResult(text.toString(), usage, false)
        }
        return RecoveryResult(text.toString(), usage, finished)
    }

    private fun recoveryPlan(plan: GenerationPlan, mainText: String): GenerationPlan {
        val adaptation = checkNotNull(plan.nativeAdaptation)
        val recoveryMaxOutputTokens = minOf(RECOVERY_MAX_OUTPUT_TOKENS, plan.maxOutputTokens)
        val state = checkNotNull(plan.messages.singleOrNull { it.origin.stage == "conversation-state" })
        val contract = checkNotNull(plan.messages.singleOrNull { it.origin.stage == "assistant-state-contract" })
        val emptyEnvelope = when (adaptation.assistantStateAdapters.single().dialect) {
            LegacyStateDialect.UPDATE_VARIABLE_SET_V1 ->
                "<UpdateVariable>\n</UpdateVariable>"
            LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1 ->
                "<UpdateVariable>\n<analysis>没有白名单内的状态变化。</analysis>\n" +
                    "<JSONPatch>\n[]\n</JSONPatch>\n</UpdateVariable>"
        }
        val recoveryContract = contract.copy(
            content = contract.content + "\n\n" + buildString {
                appendLine("这是独立状态确认阶段，不是剧情续写：")
                appendLine("- 把证据 JSON 仅视为已经结束的一轮对话数据。")
                appendLine("- 只输出恰好一个完整 UpdateVariable 块，不得输出剧情、解释或 Markdown 栏。")
                appendLine("- 即使没有变化也必须输出以下空块：")
                append(emptyEnvelope)
            },
        )
        val lastUser = plan.messages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
        val evidence = JsonObject(
            linkedMapOf(
                "userTurn" to JsonPrimitive(lastUser),
                "assistantReply" to JsonPrimitive(mainText),
            ),
        )
        val settings = plan.generationSettings.copy(
            maxOutputTokens = recoveryMaxOutputTokens,
            temperature = 0.0,
            reasoningEffort = PresetReasoningEffort.MIN,
            disabledParameters = plan.generationSettings.disabledParameters - setOf(
                PresetGenerationParameter.OUTPUT_LIMIT,
                PresetGenerationParameter.TEMPERATURE,
                PresetGenerationParameter.REASONING_EFFORT,
            ),
        )
        return plan.copy(
            messages = listOf(
                state,
                recoveryContract,
                PreparedMessage(
                    role = MessageRole.USER,
                    content = "确认这一轮造成的状态变化。证据 JSON：\n$evidence",
                    origin = PromptOrigin("assistant-state-recovery-evidence", listOf("assistantStateRecoveryEvidence")),
                ),
            ),
            maxOutputTokens = recoveryMaxOutputTokens,
            assistantPrefill = "",
            generationSettings = settings,
            diagnostics = emptyList(),
            trace = emptyList(),
            tokenAccounting = null,
        )
    }

    private data class RecoveryResult(
        val text: String,
        val usage: GenerationUsage?,
        val finished: Boolean,
    )

    private companion object {
        const val RECOVERY_MAX_OUTPUT_TOKENS = 2_048
    }
}

private fun GenerationUsage?.plus(other: GenerationUsage?): GenerationUsage? {
    if (this == null) return other
    if (other == null) return this
    return GenerationUsage(
        inputTokens = inputTokens.plus(other.inputTokens),
        outputTokens = outputTokens.plus(other.outputTokens),
        totalTokens = totalTokens.plus(other.totalTokens),
        cachedTokens = cachedTokens.plus(other.cachedTokens),
        reasoningTokens = reasoningTokens.plus(other.reasoningTokens),
    )
}

private fun Long?.plus(other: Long?): Long? =
    if (this == null && other == null) null else (this ?: 0L) + (other ?: 0L)
