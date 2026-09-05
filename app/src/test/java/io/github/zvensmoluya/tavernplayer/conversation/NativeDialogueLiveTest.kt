package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.modelgateway.CredentialResolver
import io.github.zvensmoluya.modelgateway.ModelGateway
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.modelgateway.SecretValue
import io.github.zvensmoluya.modelgateway.catalog.ModelCatalog
import io.github.zvensmoluya.tavernplayer.connections.ConnectionEndpointResolver
import io.github.zvensmoluya.tavernplayer.connections.ConnectionRepository
import io.github.zvensmoluya.tavernplayer.connections.ConnectionStateStore
import io.github.zvensmoluya.tavernplayer.connections.ConnectionTemplates
import io.github.zvensmoluya.tavernplayer.connections.CredentialStore
import io.github.zvensmoluya.tavernplayer.connections.GatewayAppState
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.BuiltInPresets
import io.github.zvensmoluya.tavernplayer.content.CharacterCardImporter
import io.github.zvensmoluya.tavernplayer.content.CharacterImportResult
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationParameter
import io.github.zvensmoluya.tavernplayer.content.PresetReasoningEffort
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in real-provider test. The model exercises normal roleplay only; the adaptation is the
 * manually audited fixture and is never generated or modified by the provider.
 */
class NativeDialogueLiveTest {
    @Test
    fun `real model continues the manually adapted pressure card and feeds state forward`() = runBlocking {
        assumeTrue("set TAVERN_NATIVE_DIALOGUE_LIVE=true to run", liveEnabled())
        val environment = loadEnvironment()
        val source = checkNotNull(pressureCardOrNull()) { "held-back pressure card is missing" }
        val imported = CharacterCardImporter().import(source.readBytes(), source.name) as CharacterImportResult.Ready
        val adaptation = manualAdaptation()
        val character = imported.character.copy(nativeAdaptation = adaptation)
        val runtime = NativeAdaptationRuntime()
        val connectionAndGenerator = liveGenerator(environment)
        val connection = connectionAndGenerator.first
        val generator = connectionAndGenerator.second
        val preset = BuiltInPresets.default.copy(
            generationSettings = BuiltInPresets.default.generationSettings.copy(
                maxOutputTokens = 8_192,
                reasoningEffort = PresetReasoningEffort.LOW,
                temperature = 0.0,
                disabledParameters = BuiltInPresets.default.generationSettings.disabledParameters -
                    setOf(PresetGenerationParameter.REASONING_EFFORT, PresetGenerationParameter.TEMPERATURE),
            ),
        )
        val opening = character.alternateFirstMessages.first()
        val firstUserText = "我收起武器，解除变身，确认战斗已经结束，然后向天海咲道歉并询问她有没有受伤。"
        val initialState = runtime.initialState(adaptation)
        val firstHistory = listOf(
            ConversationMessage("opening", MessageRole.ASSISTANT, opening, character.promptName),
            ConversationMessage("user-1", MessageRole.USER, firstUserText, "旅人"),
        )
        val firstPlan = compile(
            character = character,
            history = firstHistory,
            runtimeState = initialState,
            preset = preset,
            modelId = connection.selectedModel,
            generationId = "pressure-live-1",
        )
        writeRequestDiagnostic("first-request.txt", connection, firstPlan)

        val firstCollected = collectResponse(generator, connection, firstPlan)
        val firstResponse = firstCollected.text
        writeDiagnostic("first-response.txt", firstResponse)
        writeDiagnostic("first-state-confirmation.txt", firstCollected.stateConfirmation.orEmpty())
        writeDiagnostic("first-reasoning.txt", firstCollected.reasoning)
        writeDiagnostic("first-events.txt", firstCollected.events.joinToString("\n"))
        val firstStateSource = firstCollected.stateConfirmation ?: firstResponse
        val firstIngested = runtime.ingestAssistantMessage(adaptation, firstStateSource, firstPlan.runtimeState)
        val firstNarrative = runtime.projectAssistantMessage(
            adaptation,
            firstResponse,
            stateConfirmedSeparately = firstCollected.stateConfirmation != null,
        )

        assertEquals("completed", firstCollected.finishReason)
        assertTrue("first response was empty; events=${firstCollected.events}", firstResponse.isNotBlank())
        assertTrue("first state confirmation did not complete UpdateVariable", firstStateSource.contains("</UpdateVariable>", ignoreCase = true))
        assertTrue("first state confirmation did not complete JSONPatch", firstStateSource.contains("</JSONPatch>", ignoreCase = true))
        assertTrue("no allowed scalar state replacements were ingested", firstIngested.appliedUpdates >= 2)
        assertEquals(
            "未变身",
            (firstIngested.runtimeState.conversationState.values["protagonist-transformation"] as JsonPrimitive).content,
        )
        assertEquals(
            "无战斗",
            (firstIngested.runtimeState.conversationState.values["protagonist-battle"] as JsonPrimitive).content,
        )
        assertTrue(
            "first narrative could not be separated from its state protocol",
            firstNarrative.envelopeStatus in setOf(
                AssistantStateEnvelopeStatus.NONE,
                AssistantStateEnvelopeStatus.PENDING,
                AssistantStateEnvelopeStatus.STRIPPED,
                AssistantStateEnvelopeStatus.RECOVERED,
            ),
        )
        assertTrue(firstNarrative.narrativeText.isNotBlank())
        assertFalse(firstNarrative.narrativeText.contains("<UpdateVariable>", ignoreCase = true))

        val secondUserText = "我看看自己的衣服，请天海咲先用一句自然对白分别明确回答：我是否仍在变身、战斗是否仍在进行。请依据 Player 当前记录作答，不要回避任何一项，然后继续场景。"
        val secondHistory = firstHistory + listOf(
            ConversationMessage(
                "assistant-1",
                MessageRole.ASSISTANT,
                firstNarrative.narrativeText,
                character.promptName,
                sourceText = firstResponse,
            ),
            ConversationMessage("user-2", MessageRole.USER, secondUserText, "旅人"),
        )
        val secondPlan = compile(
            character = character,
            history = secondHistory,
            runtimeState = firstIngested.runtimeState,
            preset = preset,
            modelId = connection.selectedModel,
            generationId = "pressure-live-2",
        )
        val projectedState = secondPlan.messages.single { it.origin.sourceIds == listOf("conversationState") }.content
        assertTrue(projectedState.contains("\"protagonist-transformation\":\"未变身\""))
        assertTrue(projectedState.contains("\"protagonist-battle\":\"无战斗\""))
        writeRequestDiagnostic("second-request.txt", connection, secondPlan)

        val secondCollected = collectResponse(generator, connection, secondPlan)
        val secondResponse = secondCollected.text
        writeDiagnostic("second-response.txt", secondResponse)
        writeDiagnostic("second-state-confirmation.txt", secondCollected.stateConfirmation.orEmpty())
        writeDiagnostic("second-reasoning.txt", secondCollected.reasoning)
        writeDiagnostic("second-events.txt", secondCollected.events.joinToString("\n"))
        val secondStateSource = secondCollected.stateConfirmation ?: secondResponse
        val secondIngested = runtime.ingestAssistantMessage(adaptation, secondStateSource, secondPlan.runtimeState)
        val secondNarrative = runtime.projectAssistantMessage(
            adaptation,
            secondResponse,
            stateConfirmedSeparately = secondCollected.stateConfirmation != null,
        )

        assertEquals("completed", secondCollected.finishReason)
        assertTrue("second response was empty", secondResponse.isNotBlank())
        assertFalse("legacy HTML leaked into dialogue", secondResponse.contains("onclick=", ignoreCase = true))
        assertFalse("state protocol leaked into projected narrative", secondNarrative.narrativeText.contains("<UpdateVariable>", ignoreCase = true))
        val confirmsTransformationEnded = INACTIVE_TRANSFORMATION_SEMANTICS.containsMatchIn(secondNarrative.narrativeText)
        val confirmsBattleEnded = INACTIVE_BATTLE_SEMANTICS.containsMatchIn(secondNarrative.narrativeText)
        assertTrue("second response did not confirm the fed-forward transformation state", confirmsTransformationEnded)
        assertTrue("second response did not confirm the fed-forward battle state", confirmsBattleEnded)
        assertEquals(
            "未变身",
            (secondIngested.runtimeState.conversationState.values["protagonist-transformation"] as JsonPrimitive).content,
        )
        assertEquals(
            "无战斗",
            (secondIngested.runtimeState.conversationState.values["protagonist-battle"] as JsonPrimitive).content,
        )
    }

    private fun compile(
        character: io.github.zvensmoluya.tavernplayer.content.CharacterAsset,
        history: List<ConversationMessage>,
        runtimeState: ConversationRuntimeState,
        preset: io.github.zvensmoluya.tavernplayer.content.PresetAsset,
        modelId: String,
        generationId: String,
    ): GenerationPlan {
        val result = PromptCompiler().compile(
            NormalGenerationInput(
                character = character.snapshot(),
                persona = Persona(id = "live-audit", name = "旅人"),
                history = history,
                preset = preset,
                runtimeState = runtimeState,
                conversationId = "pressure-live",
                generationId = generationId,
                modelId = modelId,
                modelContextTokens = 65_536,
            ),
        )
        check(result is CompilationResult.Success) {
            (result as CompilationResult.Failure).diagnostics.joinToString { it.code }
        }
        return result.plan
    }

    private suspend fun collectResponse(
        generator: ConversationGenerator,
        connection: StoredConnection,
        plan: GenerationPlan,
    ): CollectedResponse = withTimeout(2.minutes) {
        val response = StringBuilder()
        val reasoning = StringBuilder()
        val events = mutableListOf<String>()
        var reasoningDeltaCount = 0
        var finished = false
        var finishReason: String? = null
        var stateConfirmation: String? = null
        generator.stream(connection, plan).collect { event ->
            when (event) {
                is GenerationEvent.TextDelta -> response.append(event.text)
                is GenerationEvent.ReasoningDelta -> {
                    reasoning.append(event.text)
                    reasoningDeltaCount += 1
                }
                is GenerationEvent.Finished -> {
                    finished = true
                    finishReason = event.reason
                    events += "finished:${event.reason}"
                }
                is GenerationEvent.Diagnostic -> events += "diagnostic:${event.summary}"
                is GenerationEvent.Usage -> events +=
                    "usage:input=${event.value.inputTokens},output=${event.value.outputTokens}," +
                        "reasoning=${event.value.reasoningTokens},total=${event.value.totalTokens}"
                is GenerationEvent.AssistantStateConfirmed -> {
                    stateConfirmation = event.envelope
                    events += "assistant-state-confirmed"
                }
                is GenerationEvent.RequestPrepared -> events += "request:${event.preview.protocol}"
                GenerationEvent.ReasoningStarted -> events += "reasoning-started"
                GenerationEvent.ReasoningFinished -> events += "reasoning-finished"
                is GenerationEvent.ReasoningSignature -> events += "reasoning-signature"
            }
        }
        check(finished) { "provider stream ended without a finished event" }
        events += "reasoning-deltas:$reasoningDeltaCount"
        CollectedResponse(response.toString(), reasoning.toString(), events, finishReason, stateConfirmation)
    }

    private fun liveGenerator(environment: Map<String, String>): Pair<StoredConnection, ConversationGenerator> {
        val baseUrl = environment.required("TAVERN_TEST_BASE_URL")
        val apiKey = environment.required("TAVERN_TEST_API_KEY")
        val protocol = ModelProtocol.valueOf(environment.required("TAVERN_TEST_PROTOCOL").uppercase())
        val model = System.getenv("TAVERN_NATIVE_DIALOGUE_MODEL")
            ?.takeIf(String::isNotBlank)
            ?: environment.required("TAVERN_TEST_MODEL")
        val endpoints = ConnectionEndpointResolver.resolve(protocol, baseUrl)
        val credentialRef = "native-dialogue-live"
        val credentials = MemoryCredentials(credentialRef, apiKey)
        val draft = StoredConnection(
            id = "native-dialogue-live",
            name = "Native dialogue live test",
            templateId = "native-dialogue-live",
            protocol = protocol,
            apiAddress = baseUrl,
            streamEndpoint = endpoints.streamEndpoint,
            catalogEndpoint = endpoints.catalogEndpoint,
            authScheme = ConnectionTemplates.forProtocol(protocol).authScheme,
            credentialRef = credentialRef,
            credentialMask = "configured",
            approvedOrigins = emptySet(),
            selectedModel = model,
        )
        val connection = draft.copy(approvedOrigins = draft.currentOrigins())
        val repository = ConnectionRepository(
            stateStore = MemoryConnectionStateStore(
                GatewayAppState(connections = listOf(connection), recentConnectionId = connection.id),
            ),
            credentialStore = credentials,
            catalogLoader = { ModelCatalog(emptyList(), truncated = false) },
        )
        var recoveryAttempt = 0
        return connection to StateConfirmingConversationGenerator(
            delegate = ModelGatewayConversationGenerator(ModelGateway(credentials), repository),
            recoveryObserver = { text ->
                recoveryAttempt += 1
                writeDiagnostic("recovery-attempt-$recoveryAttempt.txt", text)
            },
        )
    }

    private fun manualAdaptation(): NativeAdaptation {
        val source = checkNotNull(javaClass.classLoader?.getResourceAsStream(ADAPTATION_RESOURCE))
        return source.bufferedReader().use { STRICT_JSON.decodeFromString(it.readText()) }
    }

    private fun loadEnvironment(): Map<String, String> {
        val file = sequenceOf(File(".env"), File("../.env")).firstOrNull(File::isFile)
            ?: error(".env is missing")
        return file.readLines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) return@mapNotNull null
            val separator = trimmed.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            trimmed.substring(0, separator).trim() to trimmed.substring(separator + 1).trim().unquote()
        }.toMap()
    }

    private fun Map<String, String>.required(name: String): String =
        get(name)?.takeIf(String::isNotBlank) ?: error("$name is missing")

    private fun String.unquote(): String = when {
        length >= 2 && first() == '"' && last() == '"' -> substring(1, lastIndex)
        length >= 2 && first() == '\'' && last() == '\'' -> substring(1, lastIndex)
        else -> this
    }

    private fun pressureCardOrNull(): File? = sequenceOf(
        File("source/复杂压测卡.png"),
        File("../source/复杂压测卡.png"),
    ).firstOrNull(File::isFile)

    private fun writeDiagnostic(name: String, text: String) {
        val build = sequenceOf(File("app/build"), File("build")).firstOrNull(File::isDirectory) ?: return
        File(build, "live-dialogue").apply(File::mkdirs).resolve(name).writeText(text)
    }

    private fun writeRequestDiagnostic(name: String, connection: StoredConnection, plan: GenerationPlan) {
        val prepared = GenerationRequestMapper.map(connection, plan)
        val text = when (prepared) {
            is PreparedGenerationRequest.Responses -> buildString {
                appendLine("instructions=${prepared.request.instructions?.length ?: 0}")
                appendLine("roles=${prepared.request.input.joinToString { it.role.wire }}")
                appendLine("plan:")
                plan.messages.forEachIndexed { index, message ->
                    appendLine(
                        "$index ${message.role} ${message.origin.stage} " +
                            "${message.origin.sourceIds.joinToString()} chars=${message.content.length}",
                    )
                }
                appendLine("instructions-full:")
                appendLine(prepared.request.instructions.orEmpty())
                appendLine("input-full:")
                prepared.request.input.forEachIndexed { index, message ->
                    appendLine("--- $index ${message.role.wire} ---")
                    appendLine(message.text)
                }
            }
            else -> "protocol=${connection.protocol}"
        }
        writeDiagnostic(name, text)
    }

    private fun liveEnabled(): Boolean =
        System.getenv("TAVERN_NATIVE_DIALOGUE_LIVE")?.equals("true", ignoreCase = true) == true

    private data class CollectedResponse(
        val text: String,
        val reasoning: String,
        val events: List<String>,
        val finishReason: String?,
        val stateConfirmation: String?,
    )

    private class MemoryConnectionStateStore(initial: GatewayAppState) : ConnectionStateStore {
        private val mutable = MutableStateFlow(initial)
        override val state: Flow<GatewayAppState> = mutable

        override suspend fun update(transform: (GatewayAppState) -> GatewayAppState) {
            mutable.value = transform(mutable.value)
        }
    }

    private class MemoryCredentials(
        private val id: String,
        private val secret: String,
    ) : CredentialStore, CredentialResolver {
        override suspend fun put(credentialId: String, secret: String) = Unit
        override suspend fun getOrNull(credentialId: String): String? = secret.takeIf { credentialId == id }
        override suspend fun delete(credentialId: String) = Unit
        override suspend fun contains(credentialId: String): Boolean = credentialId == id
        override suspend fun resolve(credentialRef: String): SecretValue? =
            secret.takeIf { credentialRef == id }?.let(::SecretValue)
    }

    private companion object {
        const val ADAPTATION_RESOURCE = "native-adaptation/pressure-card-manual.json"
        val INACTIVE_TRANSFORMATION_SEMANTICS =
            Regex("未变身|普通|解除.{0,4}变身|变身.{0,6}(?:解除|结束|停止)|(?:不|没).{0,6}变身")
        val INACTIVE_BATTLE_SEMANTICS =
            Regex("无战斗|战斗.{0,5}(?:结束|停止|打完)|(?:结束|停止).{0,5}战斗|仗.{0,5}打完|清剿干净")
        val STRICT_JSON = Json { ignoreUnknownKeys = false }
    }
}
