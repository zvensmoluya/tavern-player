package io.github.zvensmoluya.tavernplayer.characters

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.tavernplayer.content.CharacterCardImporter
import io.github.zvensmoluya.tavernplayer.content.CharacterImportResult
import io.github.zvensmoluya.tavernplayer.content.BuiltInPresets
import io.github.zvensmoluya.tavernplayer.content.LegacyStateDialect
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptationValidator
import io.github.zvensmoluya.tavernplayer.content.NativeCompatibilityStatus
import io.github.zvensmoluya.tavernplayer.content.NativeFormFieldType
import io.github.zvensmoluya.tavernplayer.conversation.CompilationResult
import io.github.zvensmoluya.tavernplayer.conversation.ConversationMessage
import io.github.zvensmoluya.tavernplayer.conversation.MessageRole
import io.github.zvensmoluya.tavernplayer.conversation.NativeAdaptationRuntime
import io.github.zvensmoluya.tavernplayer.conversation.NativeFormSubmission
import io.github.zvensmoluya.tavernplayer.conversation.NativeFormSubmissionResult
import io.github.zvensmoluya.tavernplayer.conversation.NormalGenerationInput
import io.github.zvensmoluya.tavernplayer.conversation.Persona
import io.github.zvensmoluya.tavernplayer.conversation.PromptCompiler
import io.github.zvensmoluya.tavernplayer.conversation.LegacyStateReadProjection
import io.github.zvensmoluya.tavernplayer.conversation.ConversationStatePromptProjector
import io.github.zvensmoluya.tavernplayer.conversation.NativeSetupController
import io.github.zvensmoluya.tavernplayer.conversation.NativeSetupResult
import io.github.zvensmoluya.tavernplayer.conversation.ConversationStateSnapshot
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PressureCardManualAdaptationTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `manually audited pressure card maps only into fixed Player domains`() {
        val source = pressureCardOrNull()
        assumeTrue("held-back pressure card is not present", source != null)
        val character = (CharacterCardImporter().import(checkNotNull(source).readBytes(), source.name) as CharacterImportResult.Ready)
            .character
        val adaptation = manualAdaptation()

        val validation = NativeAdaptationValidator().validate(
            adaptation,
            expectedSourceSha256 = character.sourceSha256,
            availableAssetIds = character.assets.filter { it.isLocallyMaterializableImage }.mapTo(mutableSetOf()) { it.id },
            worldBooks = character.worldBooks,
        )

        assertTrue(validation.issues.toString(), validation.valid)
        assertEquals(19, adaptation.state.size)
        assertEquals(LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1, adaptation.assistantStateAdapters.single().dialect)
        assertEquals(NativeCompatibilityStatus.PARTIAL, adaptation.report.status)
        assertEquals(3, character.alternateFirstMessages.size)
        assertTrue(character.firstMessage.contains(adaptation.forms.single().marker))
        assertTrue(adaptation.scenes.isEmpty())
        assertTrue(adaptation.collections.isEmpty())
    }

    @Test
    fun `manual pressure mapping updates narrative state without executing legacy runtime`() {
        val adaptation = manualAdaptation()
        val runtime = NativeAdaptationRuntime()
        val initial = runtime.initialState(adaptation)
        val assistant = """
            她收起光芒，恢复成日常形态。
            <UpdateVariable>
            <analysis>legacy analysis remains inert text</analysis>
            <JSONPatch>
            [
              {"op":"replace","path":"/主角/魔力","value":92},
              {"op":"replace","path":"/主角/变身","value":"未变身"},
              {"op":"replace","path":"/主角/战局","value":"无战斗"},
              {"op":"replace","path":"/关系/天海咲/好感度","value":10},
              {"op":"replace","path":"/关系/天海咲/心里话","value":"至少她愿意停下来听我说。"}
            ]
            </JSONPatch>
            </UpdateVariable>
        """.trimIndent()

        val ingested = runtime.ingestAssistantMessage(adaptation, assistant, initial)

        assertEquals(5, ingested.appliedUpdates)
        assertEquals(92.0, (ingested.runtimeState.conversationState.values.getValue("protagonist-mana") as JsonPrimitive).double, 0.0)
        assertEquals("未变身", (ingested.runtimeState.conversationState.values.getValue("protagonist-transformation") as JsonPrimitive).content)
        assertEquals("无战斗", (ingested.runtimeState.conversationState.values.getValue("protagonist-battle") as JsonPrimitive).content)
        assertFalse(ingested.runtimeState.conversationState.values.containsKey("任意/脚本"))
    }

    @Test
    fun `source numeric bounds are enforced for every mapped resource and relationship`() {
        val adaptation = manualAdaptation()
        val runtime = NativeAdaptationRuntime()
        val initial = runtime.initialState(adaptation)
        val paths = listOf("/主角/魔力", "/主角/情欲") +
            listOf("新井晴", "天海咲", "星野灯", "白鸟优里").map { "/关系/$it/好感度" }
        paths.forEach { path ->
            val key = adaptation.assistantStateAdapters.single().mappings.single { it.sourcePath == path }.targetStateKey
            listOf(-12 to 0.0, 135 to 100.0).forEach { (input, expected) ->
                val result = runtime.ingestAssistantMessage(adaptation, patch(path, input.toString()), initial)
                assertEquals(null, result.rejection)
                assertEquals(expected, (result.runtimeState.conversationState.values.getValue(key) as JsonPrimitive).double, 0.0)
            }
        }
    }

    @Test
    fun `invalid transformation and battle values reject the whole reply`() {
        val adaptation = manualAdaptation()
        val runtime = NativeAdaptationRuntime()
        val initial = runtime.initialState(adaptation)
        val paths = listOf("/主角/变身", "/主角/战局") +
            listOf("新井晴", "天海咲", "星野灯", "白鸟优里").map { "/关系/$it/变身" }
        paths.forEach { path ->
            val reply = "<UpdateVariable><JSONPatch>[" +
                "{\"op\":\"replace\",\"path\":\"/主角/魔力\",\"value\":50}," +
                "{\"op\":\"replace\",\"path\":\"$path\",\"value\":\"未知状态\"}]</JSONPatch></UpdateVariable>"
            val result = runtime.ingestAssistantMessage(adaptation, reply, initial)
            assertEquals("STATE_TYPE_MISMATCH", result.rejection)
            assertEquals(0, result.appliedUpdates)
            assertEquals(initial, result.runtimeState)
        }
    }

    @Test
    fun `setup identity stays readable but cannot be overwritten by assistant after repository restore`() = runTest {
        val source = pressureCardOrNull()
        assumeTrue("held-back pressure card is not present", source != null)
        val imported = (CharacterCardImporter().import(checkNotNull(source).readBytes(), source.name) as CharacterImportResult.Ready).character
        val adaptation = manualAdaptation()
        val root = temporary.newFolder("identity-lock")
        val repository = io.github.zvensmoluya.tavernplayer.conversation.ConversationRepository(root, PromptCompiler(), idFactory = { "identity-lock" })
        val record = repository.create(imported.copy(nativeAdaptation = adaptation), Persona("p", "旅人"), BuiltInPresets.default)
        val form = adaptation.forms.single()
        val committed = (NativeSetupController().commit(record, NativeFormSubmission(form.id,
            mapOf("body" to listOf("TS魔法少女")))) as NativeSetupResult.Committed).record
        repository.save(committed)
        val restored = checkNotNull(io.github.zvensmoluya.tavernplayer.conversation.ConversationRepository(root, PromptCompiler()).get(record.id))
        val states = restored.turns.single().variants.flatMap {
            listOf(checkNotNull(it.runtimeStateBefore), checkNotNull(it.projectionRuntimeStateBefore), checkNotNull(it.runtimeStateAfter))
        } + restored.runtimeState
        states.forEach { state ->
            val read = Json.parseToJsonElement(checkNotNull(LegacyStateReadProjection.project(adaptation, state.conversationState))).jsonObject
            assertEquals(JsonPrimitive("TS魔法少女"), read.getValue("主角").jsonObject["身体"])
            val result = NativeAdaptationRuntime().ingestAssistantMessage(adaptation, patch("/主角/身体", "\"少女\""), state)
            assertEquals("UNDECLARED_STATE_PATH", result.rejection)
            assertEquals(state, result.runtimeState)
        }
        assertTrue(restored.draft.contains("TS魔法少女"))
        val contract = checkNotNull(ConversationStatePromptProjector().projectAdapterContract(adaptation))
        assertFalse(contract.contains("/主角/身体"))
        assertTrue(checkNotNull(ConversationStatePromptProjector().project(restored.runtimeState.conversationState, adaptation)).contains("TS魔法少女"))
    }

    @Test
    fun `native status exposes every source field including relationship thoughts`() {
        val adaptation = manualAdaptation()
        val status = checkNotNull(adaptation.status)
        assertEquals(adaptation.state.map { it.key }.toSet(), status.items.map { it.stateKey }.toSet())
        assertEquals(19, status.items.size)
    }

    @Test
    fun `each identity compiles exactly its original worldbook branch from the current checkpoint`() {
        val source = pressureCardOrNull()
        assumeTrue("held-back pressure card is not present", source != null)
        val imported = (CharacterCardImporter().import(checkNotNull(source).readBytes(), source.name) as CharacterImportResult.Ready).character
        val adaptation = manualAdaptation()
        val character = imported.copy(nativeAdaptation = adaptation)
        val initial = NativeAdaptationRuntime().initialState(adaptation)
        val originalEntry = character.worldBooks.single().entries.single { it.sourceId == "27" }
        assertTrue("Source entry explicitly bypasses the worldbook sub-budget", originalEntry.ignoreBudget)
        listOf("少女", "TS魔法少女", "少女").forEach { body ->
            val state = initial.copy(conversationState = ConversationStateSnapshot(
                initial.conversationState.values + ("protagonist-body" to JsonPrimitive(body))))
            val result = PromptCompiler().compile(NormalGenerationInput(
                character = character.snapshot(), persona = Persona("audit", "旅人"),
                history = listOf(ConversationMessage("u", MessageRole.USER, "沿着街道散步。", "旅人")),
                preset = BuiltInPresets.default, runtimeState = state,
                conversationId = "branch-audit", generationId = "branch-audit", modelId = "test", modelContextTokens = 65536,
            ))
            assertTrue("Prompt compilation should succeed for each declared identity", result is CompilationResult.Success)
            val plan = (result as CompilationResult.Success).plan
            val combined = plan.messages.joinToString("\n") { it.content }
            val expectedCase = adaptation.worldBookTextSelections.single().cases.single { it.stateValue == body }
            val expected = originalEntry.content.substring(expectedCase.sourceStart, expectedCase.sourceEndExclusive)
                .replace("{{user}}", "旅人")
            assertTrue("Expected source branch must reach the actual prompt: ${plan.trace.filter { originalEntry.id in it.sourceIds }}", combined.contains(expected))
            assertEquals(1, Regex("<身体行文指导>").findAll(combined).count())
            assertFalse(combined.contains("<%"))
            assertEquals(state.conversationState, plan.runtimeState.conversationState)
        }
        assertTrue("Original entry remains immutable", originalEntry.content.contains("<%"))
    }

    private fun patch(path: String, value: String): String =
        "<UpdateVariable><JSONPatch>[{\"op\":\"replace\",\"path\":\"$path\",\"value\":$value}]</JSONPatch></UpdateVariable>"

    @Test
    fun `real pressure card and manual adaptation compile a clean dialogue prompt`() {
        val source = pressureCardOrNull()
        assumeTrue("held-back pressure card is not present", source != null)
        val imported = (CharacterCardImporter().import(checkNotNull(source).readBytes(), source.name) as CharacterImportResult.Ready)
            .character
        val adaptation = manualAdaptation()
        val character = imported.copy(nativeAdaptation = adaptation)
        val runtimeState = NativeAdaptationRuntime().initialState(adaptation)
        val opening = character.alternateFirstMessages.first()
        val userText = "我收起武器，解除变身，确认战斗已经结束，然后向天海咲道歉并询问她有没有受伤。"

        val result = PromptCompiler().compile(
            NormalGenerationInput(
                character = character.snapshot(),
                persona = Persona(id = "manual-audit", name = "旅人"),
                history = listOf(
                    ConversationMessage("opening", MessageRole.ASSISTANT, opening, character.promptName),
                    ConversationMessage("user", MessageRole.USER, userText, "旅人"),
                ),
                preset = BuiltInPresets.default,
                runtimeState = runtimeState,
                conversationId = "pressure-manual-audit",
                generationId = "pressure-manual-audit-1",
                modelId = "deepseek-chat",
                modelContextTokens = 65_536,
            ),
        )

        assertTrue(
            (result as? CompilationResult.Failure)?.diagnostics?.joinToString { it.message }.orEmpty(),
            result is CompilationResult.Success,
        )
        val plan = (result as CompilationResult.Success).plan
        val combined = plan.messages.joinToString("\n") { it.content }
        assertTrue(plan.messages.any { it.origin.sourceIds == listOf("conversationState") })
        assertTrue(combined.contains("<JSONPatch>"))
        assertTrue(combined.contains("protagonist-transformation"))
        assertTrue(combined.contains(userText))
        assertFalse(combined.contains("<CharCreationForm/>"))
        assertFalse(combined.contains("<NavPage/>"))
        assertFalse(combined.contains("onclick="))
        assertFalse(combined.contains("<script"))
    }

    @Test
    fun `manual adaptation installs atomically and survives repository restart`() = runTest {
        val source = pressureCardOrNull()
        assumeTrue("held-back pressure card is not present", source != null)
        val root = temporary.newFolder("manual-install")
        val repository = CharacterRepository(
            root,
            imageInspector = StaticImageInspector { StaticImageInfo(2_048, 2_048, "image/png") },
        )
        val saved = repository.import(checkNotNull(source).readBytes(), source.name) as CharacterSaveResult.Saved
        val adaptation = manualAdaptation()

        val installed = repository.installNativeAdaptation(saved.character.id, adaptation)

        assertTrue(installed is NativeAdaptationInstallResult.Installed)
        val restoredRepository = CharacterRepository(root)
        val restored = checkNotNull(restoredRepository.get(saved.character.id))
        assertEquals(adaptation, restored.nativeAdaptation)
        assertTrue(restoredRepository.assetFile(restored.id, restored.assets.single().id)?.isFile == true)

        val conversation = io.github.zvensmoluya.tavernplayer.conversation.ConversationRepository(
            root,
            PromptCompiler(),
            idFactory = { "pressure-conversation" },
        ).create(
            restored,
            Persona(id = "manual-audit", name = "旅人"),
            BuiltInPresets.default,
        )
        assertEquals(19, conversation.runtimeState.conversationState.values.size)
        assertEquals(4, conversation.turns.single().variants.size)
    }

    @Test
    fun `manual setup remains a draft-only user-confirmed effect`() {
        val adaptation = manualAdaptation()
        val form = adaptation.forms.single()
        val values = form.fields.associate { field ->
            field.id to when (field.type) {
                NativeFormFieldType.SINGLE_SELECT,
                NativeFormFieldType.MULTI_SELECT,
                -> listOf(field.options.first().value)
                NativeFormFieldType.TOGGLE -> listOf("true")
                NativeFormFieldType.NUMBER -> listOf("7")
                NativeFormFieldType.TEXT,
                NativeFormFieldType.MULTILINE_TEXT,
                -> listOf("人工-${field.id}")
            }
        }

        val result = NativeAdaptationRuntime().submitForm(
            adaptation,
            NativeFormSubmission(form.id, values),
            userName = "旅人",
            characterName = "樱见市",
        ) as NativeFormSubmissionResult.Draft

        values.values.flatten().forEach { assertTrue(result.text.contains(it)) }
        assertFalse(result.text.contains("{{form."))
    }

    private fun manualAdaptation(): NativeAdaptation {
        val source = checkNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE_PATH))
        return source.bufferedReader().use { STRICT_JSON.decodeFromString(it.readText()) }
    }

    private fun pressureCardOrNull(): File? = sequenceOf(
        File("source/复杂压测卡.png"),
        File("../source/复杂压测卡.png"),
    ).firstOrNull(File::isFile)

    private companion object {
        const val FIXTURE_PATH = "native-adaptation/pressure-card-manual.json"
        val STRICT_JSON = Json { ignoreUnknownKeys = false }
    }
}
