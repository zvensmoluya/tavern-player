package io.github.zvensmoluya.tavernplayer.characters

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.content.AssistantStateAdapterDefinition
import io.github.zvensmoluya.tavernplayer.content.AssistantStateMapping
import io.github.zvensmoluya.tavernplayer.content.ConversationStateDefinition
import io.github.zvensmoluya.tavernplayer.content.ConversationStateValueType
import io.github.zvensmoluya.tavernplayer.content.LegacyStateDialect
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import io.github.zvensmoluya.tavernplayer.content.NativeFormField
import io.github.zvensmoluya.tavernplayer.content.NativeFormFieldType
import io.github.zvensmoluya.tavernplayer.content.NativeFormView
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
import io.github.zvensmoluya.tavernplayer.conversation.ConversationStateSnapshot
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
import io.github.zvensmoluya.tavernplayer.conversation.WorldBookActivationOverrides
import java.io.File
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
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
    fun `safe inline image assets are materialized behind stable ids`() = runTest {
        val root = temporary.newFolder("character-assets")
        val inspector = StaticImageInspector { StaticImageInfo(2, 2, "image/png") }
        val repository = CharacterRepository(root, imageInspector = inspector)
        val imageBytes = byteArrayOf(1, 2, 3, 4)
        val png = Base64.getEncoder().encodeToString(imageBytes)
        val bytes = """
            {"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Scene","assets":[
              {"type":"background","uri":"data:image/png;base64,$png","name":"beach","ext":"png"},
              {"type":"background","uri":"https://example.invalid/forest.png","name":"forest","ext":"png"}
            ]}}
        """.trimIndent().encodeToByteArray()

        val saved = repository.import(bytes, "scene.json") as CharacterSaveResult.Saved
        val beach = saved.character.assets.first { it.name == "beach" }
        val forest = saved.character.assets.first { it.name == "forest" }

        assertTrue(beach.id.startsWith("asset-"))
        val characterDirectory = File(root, "tavern/characters/${saved.character.id}")
        assertTrue(
            "asset=${beach.id} files=${characterDirectory.walkTopDown().map { it.relativeTo(characterDirectory).path }.toList()} " +
                "manifest=${File(characterDirectory, "manifest.json").readText()}",
            repository.assetFile(saved.character.id, beach.id)?.isFile == true,
        )
        assertEquals(null, repository.assetFile(saved.character.id, forest.id))
        assertTrue(CharacterRepository(root, imageInspector = inspector).assetFile(saved.character.id, beach.id)?.isFile == true)
    }

    @Test
    fun `opening assistant update commits adaptation state`() = runTest {
        val root = temporary.newFolder("opening-state")
        val artifact = nativeAdaptation("a".repeat(64)).copy(
            assistantStateAdapters = listOf(
                AssistantStateAdapterDefinition(
                    dialect = LegacyStateDialect.UPDATE_VARIABLE_SET_V1,
                    mappings = listOf(AssistantStateMapping("world.phase", "phase")),
                ),
            ),
        )
        val character = CharacterAsset(
            id = "opening-state",
            name = "Opening State",
            firstMessage = """
                <GAMESTART/>
                <UpdateVariable>
                _.set('world.phase', 'ready', 'started');
                </UpdateVariable>
            """.trimIndent(),
            nativeAdaptation = artifact,
        )
        val repository = ConversationRepository(root, PromptCompiler(), idFactory = { "opening-state-id" })

        val conversation = repository.create(character, Persona("persona", "Traveler"), preset())

        assertEquals("started", (conversation.runtimeState.conversationState.values["phase"] as JsonPrimitive).content)
        assertEquals("started", (conversation.turns.single().selected.runtimeStateAfter?.conversationState?.values?.get("phase") as JsonPrimitive).content)
    }

    @Test
    fun `selected conversation state candidate survives process restore`() = runTest {
        val root = temporary.newFolder("conversation-state-restore")
        val stateful = nativeAdaptation("a".repeat(64)).copy(
            forms = emptyList(),
            state = listOf(ConversationStateDefinition("phase", type = ConversationStateValueType.STRING, initialValue = JsonPrimitive("ready"))),
            assistantStateAdapters = listOf(
                AssistantStateAdapterDefinition(
                    LegacyStateDialect.UPDATE_VARIABLE_SET_V1,
                    listOf(AssistantStateMapping("game.phase", "phase")),
                ),
            ),
        )
        val repository = ConversationRepository(root, PromptCompiler())
        var record = repository.create(
            CharacterAsset(
                id = "stateful",
                name = "Stateful",
                firstMessage = "<UpdateVariable>\n_.set('game.phase', 'ready', 'first');\n</UpdateVariable>",
                alternateFirstMessages = listOf(
                    "<UpdateVariable>\n_.set('game.phase', 'ready', 'alternate');\n</UpdateVariable>",
                ),
                nativeAdaptation = stateful,
            ),
            Persona("persona", "Traveler"),
            preset(),
        )
        val opening = record.turns.single()
        val alternate = opening.variants[1]
        record = repository.save(
            record.copy(
                turns = listOf(opening.copy(selectedVariantIndex = 1)),
                runtimeState = requireNotNull(alternate.runtimeStateAfter),
            ),
        )

        val restored = ConversationRepository(root, PromptCompiler()).get(record.id)!!

        assertEquals(1, restored.turns.single().selectedVariantIndex)
        assertEquals("alternate", (restored.runtimeState.conversationState.values["phase"] as JsonPrimitive).content)
        assertEquals("alternate", (restored.turns.single().selected.runtimeStateAfter?.conversationState?.values?.get("phase") as JsonPrimitive).content)
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
        assertEquals(3, record.schemaVersion)
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
                runtimeState = ConversationRuntimeState(
                    localVariables = mapOf("mood" to MacroValue("warm")),
                    worldBookActivationOverrides = WorldBookActivationOverrides(
                        books = mapOf("book" to false),
                        entries = mapOf("book" to mapOf("entry" to true)),
                    ),
                    conversationState = ConversationStateSnapshot(mapOf("affection" to JsonPrimitive(30))),
                ),
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
        assertEquals(false, loaded.runtimeState.worldBookActivationOverrides.books["book"])
        assertEquals(true, loaded.runtimeState.worldBookActivationOverrides.entries["book"]?.get("entry"))
        assertEquals(JsonPrimitive(30), loaded.runtimeState.conversationState.values["affection"])
        assertEquals("lore", loaded.character.worldBooks.single().entries.single().content)
        assertEquals("regex", loaded.character.regexScripts.single().id)
    }

    private fun cardJson(name: String, description: String) = """
        {"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"$name","description":"$description"}}
    """.trimIndent().encodeToByteArray()

    private fun nativeAdaptation(sourceSha256: String) = NativeAdaptation(
        sourceSha256 = sourceSha256,
        state = listOf(ConversationStateDefinition("phase", type = ConversationStateValueType.STRING, initialValue = JsonPrimitive("ready"))),
        forms = listOf(
            NativeFormView(
                id = "opening-form",
                title = "Opening",
                marker = "<GAMESTART/>",
                fields = listOf(NativeFormField("name", NativeFormFieldType.TEXT, "Name")),
                draftTemplate = "Name: {{form.name}}",
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
