package io.github.zvensmoluya.tavernplayer.characters

import io.github.zvensmoluya.tavernplayer.conversation.ConversationRepository
import io.github.zvensmoluya.tavernplayer.conversation.MainDispatcherRule
import io.github.zvensmoluya.tavernplayer.conversation.Persona
import io.github.zvensmoluya.tavernplayer.conversation.PromptCompiler
import io.github.zvensmoluya.tavernplayer.presets.PresetRepository
import io.github.zvensmoluya.tavernplayer.transfer.ShelfTransfer
import io.github.zvensmoluya.tavernplayer.transfer.ShelfTransferManifest
import io.github.zvensmoluya.tavernplayer.transfer.ShelfTransferReceiver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

    private fun harness(transfer: ShelfTransfer): Harness {
        val root = temporary.newFolder()
        val characters = CharacterRepository(root)
        val presets = PresetRepository(root, ioDispatcher = mainDispatcher.dispatcher)
        val conversations = ConversationRepository(root, PromptCompiler())
        val receiver = ShelfTransferReceiver { transfer }
        return Harness(
            viewModel = CharacterLibraryViewModel(
                characters,
                conversations,
                Persona("default", "旅人"),
                presets,
                receiver,
            ),
            characterRepository = characters,
            presetRepository = presets,
        )
    }

    private fun fixture(kind: String, bytes: ByteArray, filename: String, subtype: String? = null) = ShelfTransfer(
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
        val presetRepository: PresetRepository,
    ) {
        suspend fun awaitImport() {
            viewModel.uiState.first { !it.importing }
        }
    }
}
