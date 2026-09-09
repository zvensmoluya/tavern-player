package io.github.zvensmoluya.tavernplayer.conversation.ejs

import com.dokar.quickjs.QuickJsInterruptedException
import io.github.zvensmoluya.tavernplayer.conversation.*
import io.github.zvensmoluya.tavernplayer.content.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QuickJsEjsRuntimeTest {
    private val assets = File(requireNotNull(System.getProperty("mvuProbeAssets")))
    private fun bundle() = File(assets.parentFile, "app-assets/ejs/runtime.js").readText()

    @Test fun originalControlFlowAndHostSemanticsRunOnRealQuickJs() = runBlocking {
        EjsRuntimeContract.verify(bundle())
    }

    @Test fun loopingAndSuspendedTemplatesAreInterruptedAndNextRenderIsFresh() = runBlocking {
        val runtime = QuickJsEjsRuntime(loadBundle = { bundle() }, timeoutMillis = 200)
        for (template in listOf("<% while (true) {} %>", "<% await new Promise(() => {}) %>")) {
            try { runtime.render(EjsRuntimeContract.request(template)); fail("Expected interruption") }
            catch (_: QuickJsInterruptedException) { }
            catch (_: TimeoutCancellationException) { }
        }
        assertEquals("ok", runtime.render(EjsRuntimeContract.request("<%= 'ok' %>")))
    }

    @Test fun cancellationPropagatesAndFailedTemplatesNeverBecomePrompts() = runBlocking {
        val runtime = QuickJsEjsRuntime(loadBundle = { bundle() }, timeoutMillis = 2_000)
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            started.complete(Unit)
            runtime.render(EjsRuntimeContract.request("<% await new Promise(() => {}) %>"))
            fail("Cancelled render returned")
        }
        started.await()
        delay(100)
        withTimeout(3_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        val fakeInput = NormalGenerationInput(DemoConversationContent.character.snapshot(), DemoConversationContent.persona,
            emptyList(), DemoConversationContent.preset)
        for (template in listOf("<% setvar('x', 1) %>", "<%- include('file') %>", "<% broken( %>", "<%= 'x'.repeat(262145) %>")) {
            val request = EjsRuntimeContract.request(template)
            val planner = object : GenerationPlanner {
                override fun compile(input: NormalGenerationInput): CompilationResult {
                    input.ejsRenderer(request)
                    error("Invalid template returned")
                }
            }
            val result = runtime.compile(planner, fakeInput, mutableMapOf()) as CompilationResult.Failure
            assertEquals("EJS_EVALUATION_FAILED", result.diagnostics.single().code)
        }
    }

    @Test fun providerReclipsReuseOnlyIdenticalInputsWithinTheGeneration() = runBlocking {
        var calls = 0
        val runtime = QuickJsEjsRuntime(loadBundle = { calls++; bundle() })
        val request = EjsRuntimeContract.request("<%= getvar('stat_data.score') %>")
        var score = 2
        val planner = object : GenerationPlanner {
            override fun compile(input: NormalGenerationInput): CompilationResult {
                val current = request.copy(variables = buildJsonObject { putJsonObject("stat_data") { put("score", score) } })
                assertEquals(score.toString(), input.ejsRenderer(current))
                return CompilationResult.Failure(emptyList())
            }
        }
        val input = NormalGenerationInput(DemoConversationContent.character.snapshot(), DemoConversationContent.persona,
            emptyList(), DemoConversationContent.preset)
        val cache = mutableMapOf<EjsTemplateRequest, String>()
        runtime.compile(planner, input, cache)
        runtime.compile(planner, input.copy(maxInputTokens = 2000), cache)
        assertEquals(1, calls)
        score = 4
        runtime.compile(planner, input, cache)
        assertEquals(2, calls)
        runtime.compile(planner, input, mutableMapOf())
        assertEquals(3, calls)
    }

    @Test fun auditedOriginalTemplatesMatchPinnedUpstreamReference() = runBlocking {
        val file = File(assets, "ejs/c04-cases.json")
        org.junit.Assume.assumeTrue("Optional C-04 reference cases not prepared", file.isFile)
        val cases = Json.parseToJsonElement(file.readText()).jsonArray
        val runtime = QuickJsEjsRuntime(loadBundle = { bundle() })
        for (case in cases) {
            val value = case.jsonObject
            val request = Json.decodeFromJsonElement<EjsTemplateRequest>(value.getValue("request"))
            assertEquals(value.getValue("id").jsonPrimitive.content, value.getValue("expected").jsonPrimitive.content, runtime.render(request))
        }
        assertTrue(cases.size >= 20)
    }

    @Test fun originalCardInstallsTemplateReferencesAndBuildsActualPrompt() = runBlocking {
        val file = File(assets, "ejs/c04-card.png")
        org.junit.Assume.assumeTrue("Optional audited C-04 original not prepared", file.isFile)
        val original = (CharacterCardImporter().import(file.readBytes(), "c04-card.png") as CharacterImportResult.Ready).character
        val request = Json.parseToJsonElement(NativeAdaptationCompiler().prepare(original, emptySet())).jsonObject
        val sources = request.getValue("sources").jsonArray.map { it.jsonObject }
        val schema = sources.single { it["kind"] == JsonPrimitive("SCRIPT") &&
            it.getValue("content").jsonPrimitive.content.contains("registerMvuSchema") }
        val templates = sources.filter { it["kind"] == JsonPrimitive("EJS_TEMPLATE") }
        assertEquals(4, templates.size)
        val draft = NativeCompilationDraft("原始模板与变量程序", mvu = NativeCompilationMvu(schema.getValue("id").jsonPrimitive.content),
            ejsSourceIds = templates.map { it.getValue("id").jsonPrimitive.content })
        val installed = NativeAdaptationCompiler().complete(original, Json.encodeToString(NativeCompilationDraft.serializer(), draft), emptySet()) as NativeCompilationResult.Ready
        val snapshot = original.copy(nativeAdaptation = installed.adaptation).snapshot()
        val initial = Json.parseToJsonElement(File(assets, "ejs/c04-cases.json").readText()).jsonArray.first().jsonObject
            .getValue("request").jsonObject.getValue("variables").jsonObject
        val input = NormalGenerationInput(snapshot, Persona("p", "Traveler"),
            listOf(ConversationMessage("u", MessageRole.USER, "Continue.", "Traveler")), BuiltInPresets.default,
            runtimeState = ConversationRuntimeState(mvuState = MvuStateSnapshot("a".repeat(64), "b".repeat(64), initial)),
            // The original constant entries consume more than the 64K context's world-book share.
            // This test exercises all templates; budget overflow has separate core coverage.
            modelContextTokens = 131072)
        val runtime = QuickJsEjsRuntime(loadBundle = { bundle() })
        val result = runtime.compile(PromptCompiler(), input, mutableMapOf())
        assertTrue(result.toString(), result is CompilationResult.Success)
        val plan = (result as CompilationResult.Success).plan
        assertEquals(4, plan.trace.count { it.stage == "ejs" })
        assertFalse(plan.messages.any { "<%" in it.content || "\uE000ejs-" in it.content })
        assertEquals(input.runtimeState.mvuState, plan.runtimeState.mvuState)
        assertEquals(4, snapshot.worldBooks.flatMap { it.entries }.count { "<%" in it.content })
    }
}
