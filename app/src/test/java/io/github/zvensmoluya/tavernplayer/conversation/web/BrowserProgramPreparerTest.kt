package io.github.zvensmoluya.tavernplayer.conversation.web

import io.github.zvensmoluya.tavernplayer.content.*
import io.github.zvensmoluya.tavernplayer.conversation.*
import io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuConversationRuntime
import io.github.zvensmoluya.tavernplayer.conversation.ejs.QuickJsEjsRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BrowserProgramPreparerTest {
    @get:Rule val folder = TemporaryFolder()
    private val assets = File(requireNotNull(System.getProperty("mvuProbeAssets")))
    private val preparer = BrowserProgramPreparer { File(System.getProperty("webRuntimeAssets"), "programs.js").readText() }
    @Test fun originalProgramsInitializeMvuAndRenderEjsWithoutAnAdaptationCompiler() = runBlocking {
        val file = File(assets, "ejs/c04-card.png")
        assumeTrue("Optional C-04 original is unavailable", file.isFile)
        val card = (CharacterCardImporter().import(file.readBytes(), "sample.png") as CharacterImportResult.Ready).character
        val runtime = MvuConversationRuntime { File(assets, "mvu/runtime.js").readText() }
        val repository = ConversationRepository(folder.newFolder(), PromptCompiler(), mvuRuntime = runtime, prepareBrowser = preparer::prepare)
        val record = repository.create(card, Persona("p", "User"), BuiltInPresets.default, ConversationExecutionMode.BROWSER)
        assertNull(record.character.nativeAdaptation)
        val program = record.character.browserProgram!!
        assertEquals(4, program.ejsTemplates.size)
        assertNotNull(program.mvu)
        assertTrue(program.diagnostics.toString(), program.blockedSourceIds.isEmpty())
        assertNotNull(record.runtimeState.mvuState)
        program.sources.forEach { assertEquals(BrowserProgramReader.sha256(it.content), it.sha256) }
        // Replace a checkpoint through the explicit page API using an audited gameplay state,
        // without changing the original card bytes. Both initial and replaced states compile below.
        val checkpoint = Json.parseToJsonElement(File(assets, "ejs/c04-cases.json").readText()).jsonArray.first().jsonObject
            .getValue("request").jsonObject.getValue("variables").jsonObject
        val prepared = repository.save(BrowserConversation.apply(record, BrowserActor("page"), "mvu.replace",
            buildJsonObject { put("message_id", record.turns.lastIndex); put("data", checkpoint) }))
        val ejs = QuickJsEjsRuntime(loadBundle = { File(assets.parentFile, "app-assets/ejs/runtime.js").readText() })
        assertTrue("Replacement must become the authoritative MVU checkpoint", checkpoint == prepared.runtimeState.mvuState!!.data)
        val input = NormalGenerationInput(prepared.character, prepared.persona,
            listOf(ConversationMessage("u", MessageRole.USER, "Continue.", "User")), BuiltInPresets.default,
            runtimeState = prepared.runtimeState, modelContextTokens = 131072)
        val openingResult = ejs.compile(PromptCompiler(), input.copy(runtimeState = record.runtimeState), mutableMapOf())
        assertTrue("Opening checkpoint must compile: $openingResult", openingResult is CompilationResult.Success)
        val result = ejs.compile(PromptCompiler(), input, mutableMapOf())
        assertTrue(result.toString(), result is CompilationResult.Success)
        val plan = (result as CompilationResult.Success).plan
        assertEquals(4, plan.trace.count { it.stage == "ejs" })
        assertFalse(plan.messages.any { "<%" in it.content })
    }
}
