package io.github.zvensmoluya.tavernplayer.characters

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.content.AdaptationAction
import io.github.zvensmoluya.tavernplayer.content.AdaptationActionType
import io.github.zvensmoluya.tavernplayer.content.AdaptationArtifact
import io.github.zvensmoluya.tavernplayer.content.AdaptationCompiler
import io.github.zvensmoluya.tavernplayer.content.AdaptationFormField
import io.github.zvensmoluya.tavernplayer.content.AdaptationFormFieldType
import io.github.zvensmoluya.tavernplayer.content.AdaptationStateDefinition
import io.github.zvensmoluya.tavernplayer.content.AdaptationStateType
import io.github.zvensmoluya.tavernplayer.content.AdaptationStatus
import io.github.zvensmoluya.tavernplayer.content.AdaptationTriggerType
import io.github.zvensmoluya.tavernplayer.content.AdaptationUiNode
import io.github.zvensmoluya.tavernplayer.content.AdaptationUiNodeType
import io.github.zvensmoluya.tavernplayer.content.AdaptationView
import io.github.zvensmoluya.tavernplayer.content.AdaptationViewPlacement
import io.github.zvensmoluya.tavernplayer.content.AdaptationViewTrigger
import io.github.zvensmoluya.tavernplayer.content.ContentRole
import io.github.zvensmoluya.tavernplayer.content.PresetAsset
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationSettings
import io.github.zvensmoluya.tavernplayer.content.RegexDefinition
import io.github.zvensmoluya.tavernplayer.content.RegexPlacement
import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookEntryDefinition
import io.github.zvensmoluya.tavernplayer.conversation.ConversationMessage
import io.github.zvensmoluya.tavernplayer.conversation.ConversationRepository
import io.github.zvensmoluya.tavernplayer.conversation.ConversationRuntimeState
import io.github.zvensmoluya.tavernplayer.conversation.ConversationTurn
import io.github.zvensmoluya.tavernplayer.conversation.InjectionPosition
import io.github.zvensmoluya.tavernplayer.conversation.MacroValue
import io.github.zvensmoluya.tavernplayer.conversation.MessageRole
import io.github.zvensmoluya.tavernplayer.conversation.MessageVariant
import io.github.zvensmoluya.tavernplayer.conversation.PersistedMessageStatus
import io.github.zvensmoluya.tavernplayer.conversation.Persona
import io.github.zvensmoluya.tavernplayer.conversation.PromptCompiler
import io.github.zvensmoluya.tavernplayer.conversation.PromptDefinition
import io.github.zvensmoluya.tavernplayer.conversation.PromptOrderEntry
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CharacterAndConversationRepositoryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `character imports are atomic deduplicated by sha and retain same-name versions`() = runTest {
        val root = temporary.newFolder("characters")
        val repository = CharacterRepository(root)
        val firstBytes = cardJson("Same", "first")
        val secondBytes = cardJson("Same", "second")

        val first = repository.import(firstBytes, "first.json") as CharacterSaveResult.Saved
        val duplicate = repository.import(firstBytes, "copy.json") as CharacterSaveResult.Saved
        val second = repository.import(secondBytes, "second.json") as CharacterSaveResult.Saved

        assertTrue(duplicate.duplicate)
        assertEquals(first.character.id, duplicate.character.id)
        assertNotEquals(first.character.id, second.character.id)
        assertEquals(2, repository.characters.value.size)
        assertArrayEquals(firstBytes, repository.sourceFile(first.character.id)?.readBytes())
        assertTrue(File(root, "tavern/characters").listFiles().orEmpty().none { it.name.endsWith(".importing") })

        val restored = CharacterRepository(root)
        assertEquals(repository.characters.value.map { it.sourceSha256 }, restored.characters.value.map { it.sourceSha256 })
    }

    @Test
    fun `adaptation installs beside immutable source and is captured by new conversations`() = runTest {
        val root = temporary.newFolder("adaptation")
        val characters = CharacterRepository(root)
        val source = """
            {"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Native Form","first_mes":"<GAMESTART/>"}}
        """.trimIndent().encodeToByteArray()
        val saved = characters.import(source, "native-form.json") as CharacterSaveResult.Saved
        val artifact = adaptation(saved.character.sourceSha256)

        val installed = characters.installAdaptation(
            Json { encodeDefaults = true }.encodeToString(artifact).encodeToByteArray(),
        ) as AdaptationInstallResult.Installed

        assertEquals(artifact, installed.character.adaptation)
        assertArrayEquals(source, characters.sourceFile(saved.character.id)?.readBytes())
        val restoredCharacters = CharacterRepository(root)
        assertEquals(artifact, restoredCharacters.get(saved.character.id)?.adaptation)

        val conversations = ConversationRepository(root, PromptCompiler(), idFactory = { "adapted-conversation" })
        val conversation = conversations.create(installed.character, Persona("persona", "Traveler"), preset())
        assertEquals(artifact, conversation.character.adaptation)
        assertEquals("ready", conversation.runtimeState.adaptationState["phase"]?.jsonPrimitive?.content)
    }

    @Test
    fun `adaptation rejects unknown executable surface`() = runTest {
        val root = temporary.newFolder("strict-adaptation")
        val characters = CharacterRepository(root)
        val source = cardJson("Strict", "source")
        val saved = characters.import(source, "strict.json") as CharacterSaveResult.Saved
        val encoded = Json { encodeDefaults = true }.encodeToString(adaptation(saved.character.sourceSha256))
        val unknown = encoded.dropLast(1) + ",\"script\":\"alert(1)\"}"

        val result = characters.installAdaptation(unknown.encodeToByteArray())

        assertTrue(result is AdaptationInstallResult.Rejected)
        assertEquals("INVALID_ARTIFACT", (result as AdaptationInstallResult.Rejected).issues.single().code)
        assertEquals(null, characters.get(saved.character.id)?.adaptation)
    }

    @Test
    fun `conversation restores snapshot variants runtime and interrupted streams`() = runTest {
        val root = temporary.newFolder("conversations")
        var id = 0
        val repository = ConversationRepository(
            filesDir = root,
            compiler = PromptCompiler(),
            idFactory = { "id-${id++}" },
            now = { 100L + id },
        )
        val original = CharacterAsset(
            id = "asset",
            name = "Asset Name",
            nickname = "Prompt Name",
            description = "original",
            firstMessage = "{{setvar::route::main}}Hello {{user}}",
            alternateFirstMessages = listOf("{{setvar::route::alternate}}Alternate"),
            worldBooks = listOf(
                WorldBookDefinition("book", entries = listOf(WorldBookEntryDefinition("entry", content = "lore"))),
            ),
            regexScripts = listOf(
                RegexDefinition(
                    id = "regex",
                    name = "Regex",
                    findRegex = "x",
                    replaceString = "y",
                    placements = setOf(RegexPlacement.AI_OUTPUT),
                ),
            ),
        )
        var record = repository.create(original, Persona("persona", "Traveler"), preset())

        assertEquals("original", record.character.description)
        assertEquals("Prompt Name", record.character.promptName)
        assertEquals(2, record.turns.single().variants.size)
        assertEquals("Hello Traveler", record.turns.single().variants.first().message.content)
        assertEquals("{{setvar::route::main}}Hello {{user}}", record.turns.single().variants.first().message.sourceText)
        assertEquals("Prompt Name", record.turns.single().variants.first().message.authorName)
        assertEquals("main", record.runtimeState.localVariables["route"]?.text)
        assertEquals("main", record.turns.single().variants.first().runtimeStateAfter?.localVariables?.get("route")?.text)
        assertEquals("alternate", record.turns.single().variants.last().runtimeStateAfter?.localVariables?.get("route")?.text)
        assertEquals("preset", record.turns.single().variants.first().presetId)
        assertEquals("Preset", record.turns.single().variants.first().presetName)
        assertEquals("preset-content", record.turns.single().variants.first().presetContentSha256)
        assertEquals(2, record.schemaVersion)
        assertEquals(ConversationRuntimeState(), record.turns.single().variants.first().projectionRuntimeStateBefore)
        assertNotEquals(original.copy(description = "changed").description, record.character.description)

        val streaming = MessageVariant(
            id = "stream",
            message = ConversationMessage("message", MessageRole.ASSISTANT, "partial", "Prompt Name"),
            status = PersistedMessageStatus.STREAMING,
        )
        record = repository.save(
            record.copy(
                turns = record.turns + ConversationTurn("turn", MessageRole.ASSISTANT, listOf(streaming)),
                runtimeState = ConversationRuntimeState(localVariables = mapOf("mood" to MacroValue("warm"))),
            ),
        )
        assertEquals(PersistedMessageStatus.STREAMING, record.turns.last().selected.status)
        val persistedText = File(root, "tavern/conversations/${record.id}.json").readText()
        val persistedFiles = File(root, "tavern/conversations").listFiles().orEmpty()
            .joinToString("\n---\n") { "${it.name}: ${it.readText()}" }
        assertTrue(persistedFiles, persistedText.contains("STREAMING"))
        assertTrue(persistedText, persistedText.contains("partial"))

        val restored = ConversationRepository(root, PromptCompiler())
        val loaded = restored.get(record.id)!!
        assertEquals(2, loaded.turns.size)
        assertEquals(PersistedMessageStatus.INTERRUPTED, loaded.turns.last().selected.status)
        assertEquals("warm", loaded.runtimeState.localVariables["mood"]?.text)
        assertEquals("lore", loaded.character.worldBooks.single().entries.single().content)
        assertEquals("regex", loaded.character.regexScripts.single().id)
    }

    private fun cardJson(name: String, description: String) = """
        {"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"$name","description":"$description"}}
    """.trimIndent().encodeToByteArray()

    private fun adaptation(sourceSha256: String) = AdaptationArtifact(
        sourceSha256 = sourceSha256,
        compiler = AdaptationCompiler("fixture", "1"),
        status = AdaptationStatus.FULL,
        requiredCapabilities = listOf("ui.native", "chat.setDraft", "state.write"),
        state = listOf(AdaptationStateDefinition("phase", AdaptationStateType.STRING, JsonPrimitive("ready"))),
        views = listOf(
            AdaptationView(
                id = "opening-form",
                placement = AdaptationViewPlacement.MESSAGE_REPLACEMENT,
                trigger = AdaptationViewTrigger(AdaptationTriggerType.MESSAGE_EXACT, "<GAMESTART/>"),
                nodes = listOf(
                    AdaptationUiNode(
                        id = "form",
                        type = AdaptationUiNodeType.FORM,
                        fields = listOf(AdaptationFormField("name", AdaptationFormFieldType.TEXT, "Name")),
                    ),
                ),
                submitActions = listOf(
                    AdaptationAction(AdaptationActionType.CHAT_SET_DRAFT, template = "Name: {{form.name}}"),
                ),
            ),
        ),
    )

    private fun preset() = PresetAsset(
        id = "preset",
        sourceSha256 = "preset",
        contentSha256 = "preset-content",
        name = "Preset",
        prompts = listOf(
            PromptDefinition("main", role = ContentRole.SYSTEM, content = "System"),
            PromptDefinition(
                identifier = "chatHistory",
                role = ContentRole.SYSTEM,
                marker = true,
                injectionPosition = InjectionPosition.RELATIVE,
            ),
        ),
        promptOrder = listOf(PromptOrderEntry("main"), PromptOrderEntry("chatHistory")),
        generationSettings = PresetGenerationSettings(maxOutputTokens = 100),
    )
}
