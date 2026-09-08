package io.github.zvensmoluya.tavernplayer.characters

import io.github.zvensmoluya.tavernplayer.content.*
import io.github.zvensmoluya.tavernplayer.conversation.*
import io.github.zvensmoluya.tavernplayer.conversation.script.QuickJsNativeRuntime
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Opt-in differential audit of an unmodified model artifact against its original pure draft builder. */
class NativeOpeningCompilationAuditTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun generatedOpeningFormPreservesDraftsGuardsAndCheckpoints() = runBlocking {
        val run = System.getenv("TAVERN_OPENING_AUDIT_RUN")?.takeIf { it.isNotBlank() }
        assumeTrue("Supply a private compiler run and its audit mapping", run != null)
        val directory = File(checkNotNull(run))
        val mapping = Json.parseToJsonElement(File(directory, "opening-audit-map.json").readText()).jsonObject
        val reference = Json.parseToJsonElement(File(directory, "opening-reference.json").readText()).jsonObject
        val original = Json.decodeFromString<ConversationRecord>(File(directory, "conversation.json").readText())
        val root = generateSequence(directory) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuConversationRuntime {
            File(root, "tools/mvu-probe/build/app-assets/mvu/runtime.js").readText()
        }.validateProgram(original.character)
        val program = checkNotNull(original.character.nativeAdaptation?.script)
        val runtime = QuickJsNativeRuntime()
        val storage = temporary.newFolder()
        val repository = ConversationRepository(storage, PromptCompiler())
        var record = original
        var serial = 0
        val methods = mutableListOf<String>()
        fun context() = JsonObject(record.nativeContext() + ("draftText" to JsonPrimitive(record.draft)))
        suspend fun surfaces() = runtime.present(program, context(), record.nativeRevision())
        suspend fun act(kind: String, values: Map<String, String> = emptyMap(), option: String? = null) {
            val handler = mapping.getValue(kind).jsonPrimitive.content
            val candidates = surfaces().flatMap { surface -> surface.data.actions.map { surface to it } }
                .filter { (_, action) -> action.handler == handler && (option == null || option in action.args.values.map { it.jsonPrimitive.content }) }
            val (surface, action) = candidates.single()
            val input = surface.data.fields.associate { it.id to (values[it.id] ?: it.value) }
            val invocation = NativeSurfaceInvocation(surface.id, record.nativeRevision(), action, input = input)
            NativeOperations.authorize(record, surface, invocation)
            val operation = "audit-${serial++}"
            record = NativeOperations.begin(record, invocation, operation)
            try {
                runtime.invoke(program, handler, context(), action.args, input) { method, value ->
                    methods += method
                    when (method) {
                        "variables.read" -> record.nativeContext().getValue("state")
                        "program.replace" -> {
                            record = NativeOperations.commit(record, operation, record.runtimeState.copy(scriptState = value.jsonObject))
                            repository.save(record); JsonNull
                        }
                        "draft.replace" -> {
                            record = NativeOperations.commit(record, operation, record.runtimeState, value.jsonPrimitive.content)
                            repository.save(record); JsonNull
                        }
                        "variables.replaceMvu" -> {
                            val previous = checkNotNull(record.runtimeState.mvuState)
                            record = NativeOperations.commit(record, operation, record.runtimeState.copy(mvuState = previous.withDirectReplacement(value.jsonObject)))
                            repository.save(record); JsonNull
                        }
                        else -> error("Unexpected side effect: $method")
                    }
                }
                record = NativeOperations.finish(record, operation, NativeOperationStatus.COMPLETE)
            } catch (error: Exception) {
                record = NativeOperations.finish(record, operation, NativeOperationStatus.FAILED)
                throw error
            }
            repository.save(record)
        }
        fun select(index: Int) {
            val turn = original.turns.single()
            val candidate = turn.variants.indexOfFirst { it.openingSourceIndex == index }
            require(candidate >= 0)
            record = original.copy(turns = listOf(turn.copy(selectedVariantIndex = candidate)),
                runtimeState = checkNotNull(turn.variants[candidate].nativeHead()), draft = "")
        }
        val results = buildJsonArray {
            for (case in reference.getValue("cases").jsonArray) {
                val data = case.jsonObject.getValue("draft").jsonObject
                val story = data.getValue("story").jsonPrimitive.int
                select(story + 1)
                val first = surfaces().single { it.data.surface == NativeSurfaceType.FORM }.data
                val storyField = mapping.getValue("storyField").jsonPrimitive.content
                act("save", mapOf(storyField to first.fields.single { it.id == storyField }.options[story]))
                val form = surfaces().single { it.data.surface == NativeSurfaceType.FORM }.data
                val inputs = mapping.getValue("fields").jsonObject.mapNotNull { (sourceKey, target) ->
                    val id = target.jsonPrimitive.content
                    if (form.fields.none { it.id == id }) null else id to data.getValue(sourceKey).jsonPrimitive.content
                }.toMap()
                act("save", inputs)
                if (story == 2) {
                    // All/none must preserve text inputs, then explicit toggles preserve click order.
                    act("all"); act("none")
                    data.getValue("present").jsonArray.forEach { act("toggle", option = it.jsonPrimitive.content) }
                }
                act("preview")
                val expected = case.jsonObject.getValue("expected").jsonPrimitive.content
                assertTrue("Original conditional draft was changed", surfaces().any { it.data.description.startsWith(expected) })
                act("back")
                val restoredFields = surfaces().single { it.data.surface == NativeSurfaceType.FORM }.data.fields.associate { it.id to it.value }
                inputs.forEach { (id, value) -> assertEquals(value, restoredFields[id]) }
                act("preview")
                // Guard failures must show an actual exception and perform no host effects.
                val prepared = record
                for (invalid in listOf(prepared.copy(draft = "keep my draft"), prepared.copy(turns = listOf(
                    prepared.turns.single().copy(selectedVariantIndex = 0))))) {
                    record = invalid.copy(runtimeState = prepared.runtimeState)
                    val before = methods.size
                    try { act("confirm"); fail("Expected a guarded operation") } catch (_: com.dokar.quickjs.QuickJsException) { }
                    assertEquals(before, methods.size)
                }
                record = prepared
                val before = methods.size
                act("confirm")
                assertEquals(expected, record.draft)
                assertEquals(listOf("draft.replace", "variables.read", "variables.replaceMvu", "program.replace"), methods.drop(before))
                assertEquals(data.getValue("身体"), record.runtimeState.mvuState!!.data.getValue("stat_data").jsonObject
                    .getValue("主角").jsonObject.getValue("身体"))
                val reloaded = ConversationRepository(storage, PromptCompiler()).get(record.id)!!
                assertEquals(record.draft, reloaded.draft)
                assertEquals(record.runtimeState, reloaded.runtimeState)
                assertEquals(surfaces(), runtime.present(program, JsonObject(reloaded.nativeContext() +
                    ("draftText" to JsonPrimitive(reloaded.draft))), reloaded.nativeRevision()))
                add(buildJsonObject { put("story", story); put("draftMatches", true); put("persisted", true) })
            }
        }
        File(directory, "opening-audit.json").writeText(results.toString())
    }
}
