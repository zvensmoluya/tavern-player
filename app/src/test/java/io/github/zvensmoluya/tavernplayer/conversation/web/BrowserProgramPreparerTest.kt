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

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@org.robolectric.annotation.Config(sdk = [35])
class BrowserProgramPreparerTest {
    @get:Rule val folder = TemporaryFolder()
    private val assets = File(requireNotNull(System.getProperty("mvuProbeAssets")))
    private val preparer = BrowserProgramPreparer { File(System.getProperty("webRuntimeAssets"), "programs.js").readText() }
    @Test fun legacyOriginalInitializesAndUpdatesThroughThePinnedMvuHost() = runBlocking {
        val hash = "5191b0bcb615e2abe1fa6fef20212e64d6df483f0f453dc929d4bf15d3ef07d4"
        val source = assets.toPath().resolve("../../../..").normalize().resolve("source").toFile()
        val original = source.listFiles().orEmpty().firstOrNull { file ->
            file.isFile && file.extension == "png" && java.security.MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes()).joinToString("") { "%02x".format(it) } == hash
        }
        assumeTrue("Optional C-08 original is unavailable", original != null)
        val card = (CharacterCardImporter().import(original!!.readBytes(), "sample.png") as CharacterImportResult.Ready).character
        val runtime = MvuConversationRuntime { File(assets, "mvu/runtime.js").readText() }
        val root = folder.newFolder()
        val repository = ConversationRepository(root, PromptCompiler(), mvuRuntime = runtime, prepareBrowser = preparer::prepare)
        val record = repository.create(card, Persona("p", "User"), BuiltInPresets.default, ConversationExecutionMode.BROWSER)
        val program = requireNotNull(record.character.browserProgram)
        assertEquals(1, program.sources.size)
        assertEquals(setOf(program.sources.single().id), program.mvuSourceIds)
        assertTrue(program.blockedSourceIds.isEmpty())
        assertTrue(program.sources.single().pointer.contains("TavernHelper_scripts/0/value/content"))
        fun day(state: ConversationRuntimeState) = state.mvuState!!.data.getValue("stat_data").jsonObject
            .getValue("世界").jsonObject.getValue("日期").jsonArray[0].jsonPrimitive.int
        assertEquals(1, day(record.runtimeState))
        val evaluation = requireNotNull(runtime.update(record.character,
            "A quiet day passes.\n<UpdateVariable>\n_.set('世界.日期', 1, 2);\n</UpdateVariable>", record.runtimeState, persona = record.persona))
        assertTrue(evaluation.diagnostics.toString(), evaluation.diagnostics.none { it.level == "error" })
        val next = evaluation.messages.single().applyTo(record.runtimeState)
        assertEquals(2, day(next))
        assertEquals(1, day(record.runtimeState))
        val saved = repository.save(BrowserConversation.withRuntime(record, next))
        val restored = Json.decodeFromString<ConversationRecord>(Json.encodeToString(ConversationRecord.serializer(), saved))
        runtime.validateCheckpoint(restored.character, restored.runtimeState)
        assertEquals(next.mvuState, restored.runtimeState.mvuState)
        val ejs = QuickJsEjsRuntime(loadBundle = { File(assets.parentFile, "app-assets/ejs/runtime.js").readText() })
        val input = NormalGenerationInput(record.character, record.persona,
            record.turns.map { it.selected.message } + ConversationMessage("u", MessageRole.USER, "Continue.", "User"),
            BuiltInPresets.default, runtimeState = next, modelContextTokens = 131072)
        val compiled = ejs.compile(PromptCompiler(), input, mutableMapOf())
        assertTrue("Following prompt must compile: $compiled", compiled is CompilationResult.Success)
    }
    @Test fun localComplexOriginalsPrepareCompileAndAcceptACompletedReply() = runBlocking {
        val hashes = setOf(
            "7df0b58b2a46ac9ae2169c45f715a58760ebdad63017c5a860222d808beabe32",
            "fa7e8ec564887780b331d0da29f7966f58f3688d49e6faf587d2d80ae9aecefe",
            "8f24972a97e9cb357e105e5d7d101a7ec3b7ff5c6cc0f98a4024ac94a895583f",
            "0166ea69a6bdfa0e7559cc98e877d3d5b6b106bc12e1c712a45ba96585f359f0",
        )
        val source = assets.toPath().resolve("../../../..").normalize().resolve("source").toFile()
        val originals = source.listFiles().orEmpty().filter { it.isFile && it.extension == "png" }
            .associateBy { file -> java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) } }
        assumeTrue("Optional complex originals are unavailable", hashes.all(originals::containsKey))
        val runtime = MvuConversationRuntime { File(assets, "mvu/runtime.js").readText() }
        val ejs = QuickJsEjsRuntime(loadBundle = { File(assets.parentFile, "app-assets/ejs/runtime.js").readText() })
        for (hash in hashes) {
            val card = (CharacterCardImporter().import(originals.getValue(hash).readBytes(), "sample.png") as CharacterImportResult.Ready).character
            val repository = ConversationRepository(folder.newFolder(), PromptCompiler(), mvuRuntime = runtime, prepareBrowser = preparer::prepare)
            val record = repository.create(card, Persona("p", "User"), BuiltInPresets.default, ConversationExecutionMode.BROWSER)
            val program = requireNotNull(record.character.browserProgram)
            assertTrue("$hash: ${program.diagnostics}", program.blockedSourceIds.isEmpty())
            assertNotNull("$hash: missing MVU provider", program.mvu)
            assertNotNull("$hash: missing initial state", record.runtimeState.mvuState)
            val visible = PromptCompiler().projectDisplayText(record.turns.first().selected.message.content, MessageRole.ASSISTANT,
                record.character, record.persona, BuiltInPresets.default, record.runtimeState, record.turns.map { it.selected.message },
                record.id, "display", "sample") as TextExpansionResult.Success
            assertFalse("$hash: message variable macros must resolve", "format_message_variable::" in visible.text)
            assertFalse("$hash: user macro must resolve before initvar YAML parsing", "{{user}}" in record.runtimeState.mvuState!!.data.toString())
            val input = NormalGenerationInput(record.character, record.persona,
                record.turns.map { it.selected.message } + ConversationMessage("u", MessageRole.USER, "Continue.", "User"),
                BuiltInPresets.default, runtimeState = record.runtimeState, modelContextTokens = 131072)
            val compiled = ejs.compile(PromptCompiler(), input, mutableMapOf())
            assertTrue("$hash: prompt compilation failed: $compiled", compiled is CompilationResult.Success)
            fun numericPath(value: JsonElement, path: String = ""): String? = when (value) {
                is JsonObject -> value.entries.firstNotNullOfOrNull { (key, child) -> numericPath(child, path + "/" + key.replace("~", "~0").replace("/", "~1")) }
                is JsonArray -> value.withIndex().firstNotNullOfOrNull { (index, child) -> numericPath(child, "$path/$index") }
                is JsonPrimitive -> path.takeIf { !value.isString && value.doubleOrNull != null }
            }
            val path = requireNotNull(numericPath(record.runtimeState.mvuState!!.data.getValue("stat_data"))) { "$hash: no numeric gameplay field" }
            val patch = buildJsonArray { add(buildJsonObject { put("op", "delta"); put("path", path); put("value", 1) }) }
            var evaluation = requireNotNull(runtime.update(record.character,
                "Reply.\n<UpdateVariable><JSONPatch>$patch</JSONPatch></UpdateVariable>", record.runtimeState, persona = record.persona))
            if (evaluation.messages.single().state.data == record.runtimeState.mvuState!!.data) {
                val decrement = buildJsonArray { add(buildJsonObject { put("op", "delta"); put("path", path); put("value", -1) }) }
                evaluation = requireNotNull(runtime.update(record.character, "Reply.\n<UpdateVariable><JSONPatch>$decrement</JSONPatch></UpdateVariable>", record.runtimeState, persona = record.persona))
            }
            assertTrue("$hash: reply update failed", evaluation.diagnostics.none { it.level == "error" })
            val nextState = evaluation.messages.single().applyTo(record.runtimeState)
            assertNotEquals("$hash: reply must change a gameplay variable", record.runtimeState.mvuState!!.data, nextState.mvuState!!.data)
            val restored = Json.decodeFromString<ConversationRecord>(Json.encodeToString(ConversationRecord.serializer(), repository.save(BrowserConversation.withRuntime(record, nextState))))
            runtime.validateCheckpoint(restored.character, restored.runtimeState)
            assertEquals(nextState.mvuState, restored.runtimeState.mvuState)
            val following = ejs.compile(PromptCompiler(), input.copy(runtimeState = restored.runtimeState), mutableMapOf())
            assertTrue("$hash: following turn must compile", following is CompilationResult.Success)
        }
    }
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
