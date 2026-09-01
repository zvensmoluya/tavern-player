package io.github.zvensmoluya.tavernplayer.characters

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.tavernplayer.conversation.CompilationResult
import io.github.zvensmoluya.tavernplayer.conversation.ConversationMessage
import io.github.zvensmoluya.tavernplayer.conversation.ConversationRepository
import io.github.zvensmoluya.tavernplayer.conversation.ConversationTurn
import io.github.zvensmoluya.tavernplayer.conversation.DemoConversationContent
import io.github.zvensmoluya.tavernplayer.conversation.MessageRole
import io.github.zvensmoluya.tavernplayer.conversation.MessageVariant
import io.github.zvensmoluya.tavernplayer.conversation.NormalGenerationInput
import io.github.zvensmoluya.tavernplayer.conversation.PromptCompiler
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CommunityCardCompatibilityTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `community card imports plans and restores without loading remote assets`() = runTest {
        val configured = System.getProperty("communityCard")?.takeIf(String::isNotBlank)
        val source = configured?.let(::File) ?: listOf(File("source/少女.png"), File("../source/少女.png"))
            .firstOrNull(File::isFile)
        assumeTrue("community card not available", source?.isFile == true)

        val root = temporary.newFolder("community")
        val characterRepository = CharacterRepository(root)
        val saved = characterRepository.import(source!!.readBytes(), source.name) as CharacterSaveResult.Saved
        val character = saved.character
        assertEquals("奴隶社会与三位少女", character.name)
        assertEquals(18, character.worldBooks.single().entries.size)
        assertEquals(5, character.regexScripts.size)
        assertTrue(character.diagnostics.any { it.code == "THIRD_PARTY_SCRIPT_PRESERVED" })
        assertTrue(character.diagnostics.any { it.code == "UNSUPPORTED_DYNAMIC_MACRO" })
        assertTrue(character.diagnostics.any { it.code == "ACTIVE_MARKUP_DOWNGRADED" })

        val compiler = PromptCompiler()
        var id = 0
        val conversations = ConversationRepository(
            root,
            compiler,
            idFactory = { "community-${id++}" },
        )
        var record = conversations.create(character, DemoConversationContent.persona, DemoConversationContent.preset)
        val user = ConversationMessage(
            id = "user",
            role = MessageRole.USER,
            content = "你好，我们从这里开始。",
            authorName = record.persona.name,
        )
        record = record.copy(
            turns = record.turns + ConversationTurn("user-turn", MessageRole.USER, listOf(MessageVariant("user-v", user))),
        )
        val compilation = compiler.compile(
            NormalGenerationInput(
                character = record.character,
                persona = record.persona,
                history = record.turns.map { it.selected.message },
                preset = DemoConversationContent.preset,
                runtimeState = record.runtimeState,
                conversationId = record.id,
                generationId = "community-attempt",
                modelId = "custom-compatible-model",
            ),
        )
        assertTrue(
            (compilation as? CompilationResult.Failure)?.diagnostics?.joinToString { it.message }.orEmpty(),
            compilation is CompilationResult.Success,
        )
        val plan = (compilation as CompilationResult.Success).plan
        assertTrue(plan.messages.isNotEmpty())

        val assistant = ConversationMessage("assistant", MessageRole.ASSISTANT, "欢迎来到故事。", character.promptName)
        record = conversations.save(
            record.copy(
                turns = record.turns + ConversationTurn(
                    "assistant-turn",
                    MessageRole.ASSISTANT,
                    listOf(MessageVariant("assistant-v", assistant)),
                ),
                runtimeState = plan.runtimeState,
            ),
        )
        val restored = ConversationRepository(root, compiler).get(record.id)!!
        assertEquals("欢迎来到故事。", restored.turns.last().selected.message.content)
        assertEquals(plan.runtimeState, restored.runtimeState)
        assertEquals(character.sourceSha256, restored.character.sourceSha256)
    }
}
