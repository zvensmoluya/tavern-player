package io.github.zvensmoluya.tavernplayer.characters

import io.github.zvensmoluya.tavernplayer.conversation.ConversationRepository
import io.github.zvensmoluya.tavernplayer.conversation.MainDispatcherRule
import io.github.zvensmoluya.tavernplayer.conversation.Persona
import io.github.zvensmoluya.tavernplayer.conversation.PromptCompiler
import io.github.zvensmoluya.tavernplayer.personas.DefaultPersonaSource
import io.github.zvensmoluya.tavernplayer.presets.PresetRepository
import io.github.zvensmoluya.tavernplayer.transfer.ShelfTransfer
import io.github.zvensmoluya.tavernplayer.transfer.ShelfTransferManifest
import io.github.zvensmoluya.tavernplayer.transfer.ShelfTransferReceiver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CharacterLibraryViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `Shelf character is routed into the character repository`() = runTest {
        val source = """
            {"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Lantern"}}
        """.trimIndent().encodeToByteArray()
        val fixture = fixture("character", source, "lantern.json")
        val harness = harness(fixture)

        harness.viewModel.importFromShelf("http://shelf/transfer")
        harness.awaitImport()

        assertEquals(1, harness.characterRepository.characters.value.size)
        assertNotNull(harness.viewModel.uiState.value.selectedCharacterId)
        assertEquals("已从 Shelf 导入 Lantern", harness.viewModel.uiState.value.message)
    }

    @Test
    fun `Shelf preset is routed into the preset repository`() = runTest {
        val source = """
            {"name":"Shelf Preset","chat_completion_source":"openai","main_prompt":"Hello","temperature":0.7}
        """.trimIndent().encodeToByteArray()
        val fixture = fixture("preset", source, "preset.json", subtype = "openai")
        val harness = harness(fixture)

        harness.viewModel.importFromShelf("http://shelf/transfer")
        harness.awaitImport()

        assertEquals(2, harness.presetRepository.library.value.presets.size)
        assertTrue(harness.viewModel.uiState.value.message.orEmpty().startsWith("已从 Shelf 导入 Preset"))
    }

    @Test
    fun `Shelf worldbook is recognized without creating an asset`() = runTest {
        val fixture = fixture("worldbook", """{"entries":{}}""".encodeToByteArray(), "book.json")
        val harness = harness(fixture)

        harness.viewModel.importFromShelf("http://shelf/transfer")
        harness.awaitImport()

        assertEquals("已识别世界书；当前版本暂不支持独立世界书导入", harness.viewModel.uiState.value.message)
        assertTrue(harness.characterRepository.characters.value.isEmpty())
        assertEquals(1, harness.presetRepository.library.value.presets.size)
    }

    @Test
    fun `new conversation captures the latest default persona`() = runTest {
        val source = """
            {"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Lantern"}}
        """.trimIndent().encodeToByteArray()
        val harness = harness(fixture("character", source, "lantern.json"))
        harness.viewModel.importFromShelf("http://shelf/transfer")
        harness.awaitImport()
        harness.personaSource.persona.value = Persona(
            id = "default-persona",
            name = "小舟",
            description = "喜欢雨夜。",
        )

        harness.viewModel.createConversation(harness.characterRepository.characters.value.single().id)
        val conversation = harness.conversationRepository.conversations.first { it.isNotEmpty() }.single()

        assertEquals("小舟", conversation.persona.name)
        assertEquals("喜欢雨夜。", conversation.persona.description)
    }

    @Test
    fun `detail repositories are created only when their features are opened`() = runTest {
        val root = temporary.newFolder("lazy-detail")
        val characters = CharacterRepository(root)
        val saved = characters.import(
            """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Deferred"}}""".encodeToByteArray(),
            "deferred.json",
        ) as CharacterSaveResult.Saved
        val conversations = ConversationRepository(root, PromptCompiler())
        val presets = PresetRepository(root, ioDispatcher = mainDispatcher.dispatcher)
        var conversationCalls = 0
        var presetCalls = 0
        val viewModel = CharacterLibraryViewModel(
            characters,
            conversationRepository = { conversationCalls += 1; conversations },
            defaultPersonaSource = MutablePersonaSource(Persona("default-persona", "旅人")),
            presetRepository = { presetCalls += 1; presets },
            shelfTransferReceiver = ShelfTransferReceiver { error("unused") },
        )

        try {
            assertEquals(0, conversationCalls)
            assertEquals(0, presetCalls)

            viewModel.selectCharacter(saved.character.id)
            withTimeout(5_000) { while (conversationCalls == 0) yield() }
            assertEquals(1, conversationCalls)
            assertEquals(0, presetCalls)

            viewModel.createConversation(saved.character.id)
            conversations.conversations.first { it.isNotEmpty() }
            assertEquals(1, presetCalls)
        } finally {
            clear(viewModel)
        }
    }

    private fun harness(transfer: ShelfTransfer): Harness {
        val root = temporary.newFolder()
        val characters = CharacterRepository(root)
        val presets = PresetRepository(root, ioDispatcher = mainDispatcher.dispatcher)
        val conversations = ConversationRepository(root, PromptCompiler())
        val receiver = ShelfTransferReceiver { transfer }
        val personaSource = MutablePersonaSource(Persona("default-persona", "旅人"))
        return Harness(
            viewModel = CharacterLibraryViewModel(
                characters,
                conversations,
                personaSource,
                presets,
                receiver,
            ),
            characterRepository = characters,
            conversationRepository = conversations,
            presetRepository = presets,
            personaSource = personaSource,
        )
    }

    private fun fixture(
        kind: String,
        bytes: ByteArray,
        filename: String,
        subtype: String? = null,
    ) = ShelfTransfer(
        manifest = ShelfTransferManifest(
            protocol = "tavern-shelf-transfer",
            version = 1,
            kind = kind,
            subtype = subtype,
            name = filename.substringBeforeLast('.'),
            filename = filename,
            size = bytes.size.toLong(),
            sha256 = "0".repeat(64),
            mediaType = "application/json",
            sourceUrl = "http://shelf/source",
            expiresAt = "2026-09-02T12:10:00+08:00",
        ),
        sourceBytes = bytes,
    )

    private data class Harness(
        val viewModel: CharacterLibraryViewModel,
        val characterRepository: CharacterRepository,
        val conversationRepository: ConversationRepository,
        val presetRepository: PresetRepository,
        val personaSource: MutablePersonaSource,
    ) {
        suspend fun awaitImport() {
            viewModel.uiState.first { !it.importing }
        }
    }


    private fun clear(viewModel: androidx.lifecycle.ViewModel) =
        androidx.lifecycle.ViewModelStore().apply { put("test", viewModel); clear() }
    private class MutablePersonaSource(initial: Persona) : DefaultPersonaSource {
        override val persona = MutableStateFlow(initial)
    }
}
