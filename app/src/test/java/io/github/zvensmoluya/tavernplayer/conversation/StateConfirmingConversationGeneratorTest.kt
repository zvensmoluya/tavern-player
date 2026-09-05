package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.AssistantStateAdapterDefinition
import io.github.zvensmoluya.tavernplayer.content.AssistantStateMapping
import io.github.zvensmoluya.tavernplayer.content.ConversationStateDefinition
import io.github.zvensmoluya.tavernplayer.content.ConversationStateValueType
import io.github.zvensmoluya.tavernplayer.content.LegacyStateDialect
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateConfirmingConversationGeneratorTest {
    @Test
    fun `missing main envelope is recovered once without replacing roleplay text`() = runTest {
        val delegate = RecordingGenerator { call ->
            flow {
                if (call == 1) {
                    emit(GenerationEvent.TextDelta("正文完成。"))
                    emit(GenerationEvent.Usage(GenerationUsage(10, 5, 15, reasoningTokens = 1)))
                } else {
                    emit(
                        GenerationEvent.TextDelta(
                            "说明应丢弃<UpdateVariable><JSONPatch>" +
                                "[{\"op\":\"replace\",\"path\":\"/世界/日期\",\"value\":2}]" +
                                "</JSONPatch></UpdateVariable>多余说明也应丢弃",
                        ),
                    )
                    emit(GenerationEvent.Usage(GenerationUsage(20, 4, 24, reasoningTokens = 2)))
                }
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val adaptation = adaptation()
        val events = StateConfirmingConversationGenerator(delegate)
            .stream(connection(), plan(adaptation))
            .toList()
        val combinedText = events.filterIsInstance<GenerationEvent.TextDelta>().joinToString("") { it.text }
        val stateConfirmation = events.filterIsInstance<GenerationEvent.AssistantStateConfirmed>().single().envelope
        val runtime = NativeAdaptationRuntime()
        val projection = runtime.projectAssistantMessage(adaptation, combinedText, stateConfirmedSeparately = true)
        val ingestion = runtime.ingestAssistantMessage(adaptation, stateConfirmation, runtime.initialState(adaptation))

        assertEquals(2, delegate.calls)
        assertEquals("正文完成。", projection.narrativeText)
        assertEquals(AssistantStateEnvelopeStatus.NONE, projection.envelopeStatus)
        assertEquals(1, ingestion.appliedUpdates)
        assertEquals(2.0, (ingestion.runtimeState.conversationState.values.getValue("world-day") as JsonPrimitive).double, 0.0)
        assertEquals(1, events.filterIsInstance<GenerationEvent.Finished>().size)
        assertEquals(39L, events.filterIsInstance<GenerationEvent.Usage>().single().value.totalTokens)
        assertTrue(events.filterIsInstance<GenerationEvent.Diagnostic>().any { "独立状态确认已完成" in it.summary })
        assertEquals(
            listOf("conversation-state", "assistant-state-contract", "assistant-state-recovery-evidence"),
            delegate.plans.last().messages.map { it.origin.stage },
        )
    }

    @Test
    fun `complete main envelope does not spend a recovery call`() = runTest {
        val delegate = RecordingGenerator {
            flow {
                emit(
                    GenerationEvent.TextDelta(
                        "<UpdateVariable><JSONPatch>[]</JSONPatch></UpdateVariable>\n\n正文。",
                    ),
                )
                emit(GenerationEvent.Finished("stop"))
            }
        }

        val events = StateConfirmingConversationGenerator(delegate)
            .stream(connection(), plan(adaptation()))
            .toList()

        assertEquals(1, delegate.calls)
        assertEquals(1, events.filterIsInstance<GenerationEvent.Finished>().size)
        assertTrue(events.none { it is GenerationEvent.AssistantStateConfirmed })
        assertTrue(events.filterIsInstance<GenerationEvent.Diagnostic>().none { "独立状态确认" in it.summary })
    }

    @Test
    fun `malformed main envelope is preserved as source but suppressed after separate confirmation`() = runTest {
        val malformed = "<UpdateVariable><JSONPatch>[]</analysis></UpdateVariable>\n\n正文。"
        val delegate = RecordingGenerator { call ->
            flow {
                emit(
                    GenerationEvent.TextDelta(
                        if (call == 1) malformed else
                            "<UpdateVariable><JSONPatch>" +
                                "[{\"op\":\"replace\",\"path\":\"/世界/日期\",\"value\":2}]" +
                                "</JSONPatch></UpdateVariable>",
                    ),
                )
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val adaptation = adaptation()
        val events = StateConfirmingConversationGenerator(delegate)
            .stream(connection(), plan(adaptation))
            .toList()
        val sourceText = events.filterIsInstance<GenerationEvent.TextDelta>().joinToString("") { it.text }
        val confirmation = events.filterIsInstance<GenerationEvent.AssistantStateConfirmed>().single().envelope
        val projection = NativeAdaptationRuntime().projectAssistantMessage(
            adaptation,
            sourceText,
            stateConfirmedSeparately = true,
        )

        assertEquals(malformed, sourceText)
        assertEquals(confirmation, events.filterIsInstance<GenerationEvent.AssistantStateConfirmed>().single().envelope)
        assertEquals(AssistantStateEnvelopeStatus.RECOVERED, projection.envelopeStatus)
        assertEquals("正文。", projection.narrativeText)
        assertEquals(2, delegate.calls)
    }

    private fun adaptation() = NativeAdaptation(
        sourceSha256 = "a".repeat(64),
        state = listOf(
            ConversationStateDefinition(
                key = "world-day",
                type = ConversationStateValueType.NUMBER,
                initialValue = JsonPrimitive(1),
            ),
        ),
        assistantStateAdapters = listOf(
            AssistantStateAdapterDefinition(
                dialect = LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1,
                mappings = listOf(AssistantStateMapping("/世界/日期", "world-day")),
            ),
        ),
    )

    private fun plan(adaptation: NativeAdaptation) = GenerationPlan(
        messages = listOf(
            PreparedMessage(
                MessageRole.SYSTEM,
                "<conversation_state>{\"values\":{\"world-day\":1}}</conversation_state>",
                PromptOrigin("conversation-state", listOf("conversationState")),
            ),
            PreparedMessage(
                MessageRole.SYSTEM,
                "Only replace /世界/日期 with a scalar number inside UpdateVariable/JSONPatch.",
                PromptOrigin("assistant-state-contract", listOf("assistantStateContract")),
            ),
            PreparedMessage(
                MessageRole.USER,
                "让一天过去。",
                PromptOrigin("chat-history", listOf("user")),
            ),
        ),
        maxOutputTokens = 1_024,
        declaredContextTokens = 8_192,
        assistantPrefill = "",
        presetId = "preset",
        presetName = "Preset",
        diagnostics = emptyList(),
        trace = emptyList(),
        runtimeState = NativeAdaptationRuntime().initialState(adaptation),
        nativeAdaptation = adaptation,
    )

    private fun connection() = StoredConnection(
        id = "connection",
        name = "Test",
        templateId = "test",
        protocol = ModelProtocol.OPENAI_RESPONSES,
        apiAddress = "https://example.com/v1",
        streamEndpoint = "https://example.com/v1/responses",
        catalogEndpoint = null,
        authScheme = AuthScheme.NONE,
        credentialRef = null,
        credentialMask = null,
        approvedOrigins = emptySet(),
        selectedModel = "model",
    )

    private class RecordingGenerator(
        private val response: (Int) -> Flow<GenerationEvent>,
    ) : ConversationGenerator {
        val plans = mutableListOf<GenerationPlan>()
        val calls: Int get() = plans.size

        override fun stream(connection: StoredConnection, plan: GenerationPlan): Flow<GenerationEvent> {
            plans += plan
            return response(plans.size)
        }
    }
}
