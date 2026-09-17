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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@org.robolectric.annotation.Config(sdk = [35])
class CharacterLibraryViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `opening waits for initialization and finishes for an empty library`() = runTest {
        val root = temporary.newFolder()
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val source = object : DefaultPersonaSource {
            override val persona = MutableStateFlow(Persona("default-persona", "旅人"))
            override suspend fun initialize() { ready.await() }
        }
        val repository = ConversationRepository(root, PromptCompiler())
        val model = CharacterLibraryViewModel(CharacterRepository(root), { repository }, source,
            { error("Presets must stay deferred") }, ShelfTransferReceiver { error("unused") })
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { model.uiState.first { !it.loadingConversations } }
            }
            assertTrue(model.uiState.value.initialLoading)
            ready.complete(Unit)
            val loaded = withContext(Dispatchers.Default) {
                withTimeout(5_000) { model.uiState.first { !it.initialLoading } }
            }
            assertTrue(loaded.characters.isEmpty())
            assertTrue(loaded.conversations.isEmpty())
        } finally { clear(model); repository.close() }
    }

    @Test
    fun `storage failures dismiss the opening and expose a notice`() = runTest {
        val source = object : DefaultPersonaSource {
            override val persona = MutableStateFlow(Persona("default-persona", "旅人"))
            override suspend fun initialize() { error("persona unavailable") }
        }
        val model = CharacterLibraryViewModel(CharacterRepository(temporary.newFolder()),
            { error("conversation unavailable") }, source,
            { error("Presets must stay deferred") }, ShelfTransferReceiver { error("unused") })
        try {
            val loaded = withContext(Dispatchers.Default) {
                withTimeout(5_000) { model.uiState.first { !it.initialLoading } }
            }
            assertNotNull(loaded.message)
        } finally { clear(model) }
    }

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
    fun `Shelf worldbook is imported independently and remains disabled`() = runTest {
        val fixture = fixture("worldbook", """{"entries":{}}""".encodeToByteArray(), "book.json")
        val harness = harness(fixture)

        harness.viewModel.importFromShelf("http://shelf/transfer")
        harness.awaitImport()

        assertEquals("已导入 book，请在全局世界书中启用", harness.viewModel.uiState.value.message)
        assertEquals(1, harness.worldBooks.library.value.size)
        assertTrue(harness.worldBooks.capture().books.isEmpty())
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
        val summary = harness.conversationRepository.conversations.first { it.isNotEmpty() }.single()
        val conversation = harness.conversationRepository.get(summary.id)!!

        assertEquals("小舟", conversation.persona.name)
        assertEquals("喜欢雨夜。", conversation.persona.description)
    }

    @Test
    fun `first screen shows saved conversations while presets stay deferred`() = runTest {
        val root = temporary.newFolder("lazy-detail")
        val characters = CharacterRepository(root)
        val saved = characters.import(
            """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Deferred"}}""".encodeToByteArray(),
            "deferred.json",
        ) as CharacterSaveResult.Saved
        val presets = PresetRepository(root, ioDispatcher = mainDispatcher.dispatcher)
        val persona = Persona("default-persona", "旅人")
        val writer = ConversationRepository(root, PromptCompiler())
        val existing = writer.create(saved.character, persona, presets.activePreset.value)
        writer.create(saved.character.copy(id = "other-character"), persona, presets.activePreset.value)
        val conversations by lazy { ConversationRepository(root, PromptCompiler()) }
        val conversationCalls = AtomicInteger()
        val presetCalls = AtomicInteger()
        val viewModel = CharacterLibraryViewModel(
            characters,
            conversationRepository = { conversationCalls.incrementAndGet(); conversations },
            defaultPersonaSource = MutablePersonaSource(persona),
            presetRepository = { presetCalls.incrementAndGet(); presets },
            shelfTransferReceiver = ShelfTransferReceiver { error("unused") },
        )

        try {
            // A fresh library must publish saved conversations before any detail page is opened.
            // Repository loading uses real I/O, so its deadline must not advance with virtual time.
            val initial = withContext(Dispatchers.Default) {
                withTimeout(5_000) { viewModel.uiState.first { !it.initialLoading && it.conversations.size == 2 } }
            }
            assertNull(initial.selectedCharacterId)
            assertEquals(listOf(existing.id), initial.conversationsFor(saved.character.id).map { it.id })
            assertEquals(0, presetCalls.get())

            viewModel.selectCharacter(saved.character.id)
            viewModel.selectCharacter(null)
            viewModel.selectCharacter(saved.character.id)
            yield()
            assertEquals(1, conversationCalls.get())

            // Detail browsing does not need presets; creating a conversation does.
            assertEquals(0, presetCalls.get())
            viewModel.createConversation(saved.character.id)
            val updated = withContext(Dispatchers.Default) {
                withTimeout(5_000) { viewModel.uiState.first { it.conversations.size == 3 } }
            }
            assertEquals(2, updated.conversationsFor(saved.character.id).size)
            assertEquals(1, presetCalls.get())
        } finally {
            clear(viewModel)
        }
    }

    private fun harness(transfer: ShelfTransfer): Harness {
        val root = temporary.newFolder()
        val characters = CharacterRepository(root)
        val presets = PresetRepository(root, ioDispatcher = mainDispatcher.dispatcher)
        val conversations = ConversationRepository(root, PromptCompiler())
        val worldBooks = io.github.zvensmoluya.tavernplayer.worldbooks.WorldBookRepository(root)
        val receiver = ShelfTransferReceiver { transfer }
        val personaSource = MutablePersonaSource(Persona("default-persona", "旅人"))
        return Harness(
            viewModel = CharacterLibraryViewModel(
                characters,
                { conversations },
                personaSource,
                { presets },
                receiver,
                worldBookRepository = { worldBooks },
            ),
            characterRepository = characters,
            conversationRepository = conversations,
            presetRepository = presets,
            personaSource = personaSource,
            worldBooks = worldBooks,
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
        val worldBooks: io.github.zvensmoluya.tavernplayer.worldbooks.WorldBookRepository,
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
