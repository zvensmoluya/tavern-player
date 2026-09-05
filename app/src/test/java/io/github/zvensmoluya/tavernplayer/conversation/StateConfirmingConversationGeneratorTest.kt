package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.GatewayException
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateConfirmingConversationGeneratorTest {
    @Test
    fun `recovery retains evaluated source rules as evidence and enforces its own context budget`() = runTest {
        val rules = "每次点灯消耗 4 点能量。"
        val original = plan(adaptation()).let { it.copy(messages = it.messages + PreparedMessage(
            MessageRole.SYSTEM, rules, PromptOrigin("world-book", listOf("selected-rule")),
        )) }
        var recoveryCalls = 0
        lateinit var validated: GenerationPlan
        val delegate = object : ConversationGenerator {
            override suspend fun validateTokens(connection: StoredConnection, plan: GenerationPlan): ProviderTokenValidation {
                validated = plan
                return ProviderTokenValidation(8_000, TokenCountQuality.ESTIMATED, "test-counter")
            }
            override fun stream(connection: StoredConnection, plan: GenerationPlan) = flow {
                if (plan.messages.any { it.origin.stage == "assistant-state-recovery-evidence" }) recoveryCalls++
                emit(GenerationEvent.TextDelta("灯亮起来了。"))
                emit(GenerationEvent.Finished("completed"))
            }
        }
        val events = StateConfirmingConversationGenerator(delegate).stream(connection(), original).toList()
        val evidence = Json.parseToJsonElement(validated.messages.last().content.substringAfter("证据 JSON：\n")).jsonObject
        val context = evidence.getValue("compiledContext").jsonArray.map { it.jsonObject }
        assertEquals(rules, context.last().getValue("content").jsonPrimitive.content)
        assertTrue(context.none { it.getValue("origin").jsonPrimitive.content == "conversation-state" })
        assertEquals("灯亮起来了。", evidence.getValue("assistantReply").jsonPrimitive.content)
        assertEquals(0, recoveryCalls)
        assertTrue(events.none { it is GenerationEvent.AssistantStateConfirmed })
        assertTrue(events.filterIsInstance<GenerationEvent.Diagnostic>().last().summary.contains("确认上下文超出模型容量"))
    }

    @Test
    fun `failed recovery retains main reply and usage without exposing exception contents`() = runTest {
        val secret = "private-response-and-credential"
        val failures = listOf(
            GatewayException.Configuration(secret) to "请求配置不受支持",
            GatewayException.Network(java.net.SocketTimeoutException(secret)) to "网络超时",
            GatewayException.Network(java.io.IOException(secret)) to "网络连接失败",
            GatewayException.Protocol(secret) to "响应协议错误",
            IllegalStateException(secret) to "内部错误",
        )
        for ((failure, label) in failures) {
            val delegate = RecordingGenerator { call -> flow {
                if (call == 1) {
                    emit(GenerationEvent.TextDelta("已经完成的正文。"))
                    emit(GenerationEvent.Usage(GenerationUsage(totalTokens = 10)))
                    emit(GenerationEvent.Finished("completed"))
                } else {
                    emit(GenerationEvent.TextDelta("<UpdateVariable><JSONPatch>[]</JSONPatch></UpdateVariable>"))
                    emit(GenerationEvent.Usage(GenerationUsage(totalTokens = 5)))
                    throw failure
                }
            } }
            val events = StateConfirmingConversationGenerator(delegate).stream(connection(), plan(adaptation())).toList()
            assertEquals(2, delegate.calls)
            assertEquals("已经完成的正文。", events.filterIsInstance<GenerationEvent.TextDelta>().single().text)
            assertTrue(events.none { it is GenerationEvent.AssistantStateConfirmed })
            assertEquals("completed", events.filterIsInstance<GenerationEvent.Finished>().single().reason)
            assertEquals(15L, events.filterIsInstance<GenerationEvent.Usage>().single().value.totalTokens)
            val diagnostic = events.filterIsInstance<GenerationEvent.Diagnostic>().joinToString { it.summary }
            assertTrue(diagnostic.contains(label))
            assertTrue(!diagnostic.contains(secret))
        }
    }

    @Test
    fun `unfinished and malformed recoveries have distinct diagnostics`() = runTest {
        for (finished in listOf(false, true)) {
            val delegate = RecordingGenerator { call -> flow {
                emit(GenerationEvent.TextDelta(if (call == 1) "正文。" else "<UpdateVariable>"))
                if (call == 1 || finished) emit(GenerationEvent.Finished("completed"))
            } }
            val events = StateConfirmingConversationGenerator(delegate).stream(connection(), plan(adaptation())).toList()
            assertTrue(events.none { it is GenerationEvent.AssistantStateConfirmed })
            val expected = if (finished) "未返回唯一完整块" else "流未完成"
            assertTrue(events.filterIsInstance<GenerationEvent.Diagnostic>().last().summary.contains(expected))
        }
    }

    @Test
    fun `reasoning only response never invents state from a recovery request`() = runTest {
        val delegate = RecordingGenerator { flow {
            emit(GenerationEvent.ReasoningDelta("尚在思考"))
            emit(GenerationEvent.Usage(GenerationUsage(outputTokens = 25, reasoningTokens = 25)))
            emit(GenerationEvent.Finished("completed"))
        } }
        val events = StateConfirmingConversationGenerator(delegate).stream(connection(), plan(adaptation())).toList()
        assertEquals(1, delegate.calls)
        assertTrue(events.none { it is GenerationEvent.AssistantStateConfirmed })
        assertEquals(25L, events.filterIsInstance<GenerationEvent.Usage>().single().value.reasoningTokens)
    }

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
