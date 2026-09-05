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
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
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
