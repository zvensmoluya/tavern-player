package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Regressions for typed state confirmation and preservation of malformed reply narratives. */
class NativeAdaptationRegressionTest {
    private val adaptation = NativeAdaptation(
        sourceSha256 = "a".repeat(64),
        state = listOf(ConversationStateDefinition("day", type = ConversationStateValueType.NUMBER, initialValue = JsonPrimitive(1))),
        assistantStateAdapters = listOf(AssistantStateAdapterDefinition(
            LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1,
            listOf(AssistantStateMapping("/day", "day")),
        )),
    )
    private val invalidUpdate = "<UpdateVariable><JSONPatch>[{\"op\":\"replace\",\"path\":\"/day\",\"value\":\"two\"}]</JSONPatch></UpdateVariable>"
    private val validUpdate = "<UpdateVariable><JSONPatch>[{\"op\":\"replace\",\"path\":\"/day\",\"value\":2}]</JSONPatch></UpdateVariable>"
    private val connection = StoredConnection(
        id = "review", name = "Review", templateId = "test", protocol = ModelProtocol.OPENAI_RESPONSES,
        apiAddress = "https://example.com/v1", streamEndpoint = "https://example.com/v1/responses",
        catalogEndpoint = null, authScheme = AuthScheme.NONE, credentialRef = null, credentialMask = null,
        approvedOrigins = emptySet(), selectedModel = "test",
    )
    private fun plan() = GenerationPlan(
        messages = listOf(
            PreparedMessage(MessageRole.SYSTEM, "Current day is 1", PromptOrigin("conversation-state", listOf("conversationState"))),
            PreparedMessage(MessageRole.SYSTEM, "Replace /day with a number", PromptOrigin("assistant-state-contract", listOf("assistantStateContract"))),
            PreparedMessage(MessageRole.USER, "One day passes", PromptOrigin("chat-history", listOf("user"))),
        ),
        maxOutputTokens = 1024, declaredContextTokens = 8192, assistantPrefill = "", presetId = "test", presetName = "Test",
        diagnostics = emptyList(), trace = emptyList(), runtimeState = NativeAdaptationRuntime().initialState(adaptation),
        nativeAdaptation = adaptation,
    )
    private class Fake(private val replies: List<String>) : ConversationGenerator {
        var calls = 0
        override fun stream(connection: StoredConnection, plan: GenerationPlan) = flow {
            emit(GenerationEvent.TextDelta(replies[calls++]))
            emit(GenerationEvent.Finished("stop"))
        }
    }

    @Test fun invalidTypedUpdateShouldRequestConfirmation() = runTest {
        assertTrue(NativeAdaptationValidator().validate(adaptation).valid)
        val delegate = Fake(listOf(invalidUpdate + "\n\nThe next morning.", validUpdate))
        StateConfirmingConversationGenerator(delegate).stream(connection, plan()).toList()
        assertEquals("An invalid typed update must not count as a confirmed no-op", 2, delegate.calls)
    }

    @Test fun invalidRecoveryShouldNotReportConfirmed() = runTest {
        val delegate = Fake(listOf("The next morning.", invalidUpdate))
        val events = StateConfirmingConversationGenerator(delegate).stream(connection, plan()).toList()
        assertEquals(2, delegate.calls)
        assertTrue("A discarded invalid update must not emit AssistantStateConfirmed", events.none { it is GenerationEvent.AssistantStateConfirmed })
    }

    @Test fun recoveredMissingOuterCloseShouldNotEraseFinishedNarrative() = runTest {
        val mainText = "<UpdateVariable><JSONPatch>[]</JSONPatch>\n\nThe next morning."
        val events = StateConfirmingConversationGenerator(Fake(listOf(mainText, validUpdate))).stream(connection, plan()).toList()
        assertEquals(1, events.filterIsInstance<GenerationEvent.AssistantStateConfirmed>().size)
        val projection = NativeAdaptationRuntime().projectAssistantMessage(adaptation, mainText, stateConfirmedSeparately = true)
        assertTrue("Separately confirmed finished reply must retain its narrative", projection.narrativeText.contains("The next morning."))
    }
}
