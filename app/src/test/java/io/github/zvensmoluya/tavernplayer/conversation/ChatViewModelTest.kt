package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.modelgateway.catalog.ModelCatalog
import io.github.zvensmoluya.tavernplayer.connections.ConnectionRepository
import io.github.zvensmoluya.tavernplayer.connections.ConnectionStateStore
import io.github.zvensmoluya.tavernplayer.connections.CredentialStore
import io.github.zvensmoluya.tavernplayer.connections.GatewayAppState
import io.github.zvensmoluya.tavernplayer.connections.ModelCache
import io.github.zvensmoluya.tavernplayer.connections.ModelTokenLimits
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.RegexDefinition
import io.github.zvensmoluya.tavernplayer.content.RegexPlacement
import io.github.zvensmoluya.tavernplayer.content.PresetAsset
import io.github.zvensmoluya.tavernplayer.content.AssistantStateAdapterDefinition
import io.github.zvensmoluya.tavernplayer.content.AssistantStateMapping
import io.github.zvensmoluya.tavernplayer.content.ConversationStateDefinition
import io.github.zvensmoluya.tavernplayer.content.ConversationStateValueType
import io.github.zvensmoluya.tavernplayer.content.LegacyStateDialect
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookEntryDefinition
import io.github.zvensmoluya.tavernplayer.presets.ActivePresetSource
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@org.robolectric.annotation.Config(sdk = [35])
class ChatViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test fun `finish waits for trailing usage and atomic persistence before publishing complete`() = runTest {
        val directory = Files.createTempDirectory("stream-terminal-transaction").toFile()
        ConversationRepository(directory, PromptCompiler(), ioDispatcher = mainDispatcherRule.dispatcher).use { conversations ->
            val saved = conversations.create(DemoConversationContent.character, DemoConversationContent.persona, DemoConversationContent.preset)
            lateinit var vm: ChatViewModel
            val generator = FakeGenerator { _, _ -> flow {
                emit(GenerationEvent.TextDelta("Final answer"))
                emit(GenerationEvent.Finished("stop"))
                assertEquals(ChatMessageStatus.STREAMING, vm.uiState.value.messages.last().status)
                emit(GenerationEvent.Usage(GenerationUsage(inputTokens = 21, outputTokens = 4, totalTokens = 25, cachedTokens = 5, reasoningTokens = 2)))
                // Fail only the final transaction, after it has written the terminal variant.
                conversations.store.beforeCommit = {
                    val last = conversations.store.dao.turns(saved.id).last()
                    if (conversations.store.dao.variants(last.id).last().metadata.contains("\"status\":\"COMPLETE\"")) {
                        throw IOException("terminal commit failed")
                    }
                }
            } }
            vm = ChatViewModel(repository(), PromptCompiler(), generator, conversations, FixedPresetSource(), projectionDispatcher = mainDispatcherRule.dispatcher)
            vm.loadConversation(saved.id)
            vm.updateInput("Question")
            vm.send()
            assertTrue(vm.uiState.value.storageFailed)
            assertEquals(ChatMessageStatus.STREAMING, vm.uiState.value.messages.last().status)
            assertEquals(PersistedMessageStatus.STREAMING, conversations.get(saved.id)!!.turns.last().selected.status)
            assertEquals(1, conversations.store.dao.streams(saved.id).size)
            vm.updateInput("Next draft while storage is unavailable")
            conversations.store.beforeCommit = null
            vm.retrySave()
            assertFalse(vm.uiState.value.storageFailed)
            assertEquals(ChatMessageStatus.COMPLETE, vm.uiState.value.messages.last().status)
            val terminal = conversations.get(saved.id)!!.turns.last().selected
            assertEquals(21L, terminal.inputTokens)
            assertEquals(4L, terminal.outputTokens)
            assertEquals(25L, terminal.totalTokens)
            assertEquals(5L, terminal.cachedTokens)
            assertEquals(2L, terminal.reasoningTokens)
            assertEquals("Next draft while storage is unavailable", conversations.get(saved.id)!!.draft)
            assertTrue(conversations.store.dao.streams(saved.id).isEmpty())
            assertEquals(1, generator.calls)
            androidx.lifecycle.ViewModelStore().apply { put("test", vm); clear() }
        }
        directory.deleteRecursively()
    }

    @Test fun `browser writes save literally and reject stale or failed operations`() = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("browser-writes").toFile()
        val conversations = ConversationRepository(directory, PromptCompiler())
        val saved = conversations.create(DemoConversationContent.character, DemoConversationContent.persona,
            DemoConversationContent.preset, ConversationExecutionMode.BROWSER)
        val vm = ChatViewModel(repository(), PromptCompiler(), FakeGenerator { _, _ -> error("No generation expected") }, conversations, FixedPresetSource())
        try {
            vm.loadConversation(saved.id)
            awaitMvuIdle(vm)
            val actor = BrowserActor("page", saved.turns[0].id, saved.turns[0].selected.id)
            val revision = vm.uiState.value.browserSnapshot.getValue("revision").jsonPrimitive.content
            val args = kotlinx.serialization.json.Json.parseToJsonElement("""{"type":"chat","data":{"score":7}}""").jsonObject
            vm.invokeBrowser(actor, revision, "variables.replace", args)
            assertEquals(JsonPrimitive(7), conversations.get(saved.id)!!.runtimeState.browserChatVariables["score"])
            try { vm.invokeBrowser(actor, revision, "variables.replace", args); throw AssertionError("Stale revision accepted") }
            catch (_: IllegalArgumentException) {}
            conversations.store.beforeCommit = { throw IOException("Injected SQLite commit failure") }
            try {
                vm.invokeBrowser(actor, vm.uiState.value.browserSnapshot.getValue("revision").jsonPrimitive.content,
                    "variables.replace", kotlinx.serialization.json.Json.parseToJsonElement("""{"type":"chat","data":{"score":99}}""").jsonObject)
                throw AssertionError("Failed save acknowledged")
            } catch (_: BrowserPersistenceException) {}
            assertEquals(JsonPrimitive(7), vm.uiState.value.browserSnapshot.getValue("chatVariables").jsonObject["score"])
        } finally {
            androidx.lifecycle.ViewModelStore().apply { put("test", vm); clear() }
            directory.deleteRecursively()
        }
    }

    @Test fun `browser regex writes save before refresh and permit a following message operation`() = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("browser-regex-refresh").toFile()
        val conversations = ConversationRepository(directory, PromptCompiler())
        val character = DemoConversationContent.character.copy(firstMessage = "Opening", regexScripts = listOf(
            RegexDefinition("r", "sample", "Opening", "After", disabled = true,
                placements = setOf(RegexPlacement.AI_OUTPUT), markdownOnly = true)))
        val saved = conversations.create(character, DemoConversationContent.persona, DemoConversationContent.preset, ConversationExecutionMode.BROWSER)
        val vm = ChatViewModel(repository(), PromptCompiler(), FakeGenerator { _, _ -> error("No generation expected") }, conversations, FixedPresetSource())
        try {
            vm.loadConversation(saved.id)
            awaitMvuIdle(vm)
            val actor = BrowserActor("script", scriptId = "sample")
            fun revision() = vm.uiState.value.browserSnapshot.getValue("revision").jsonPrimitive.content
            val wire = vm.uiState.value.browserSnapshot.getValue("characterRegexes").let { it as kotlinx.serialization.json.JsonArray }.first().jsonObject
            val rules = kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonObject(wire + ("enabled" to JsonPrimitive(true)))))
            val result = vm.invokeBrowser(actor, revision(), "regex.replace", kotlinx.serialization.json.JsonObject(mapOf("regexes" to rules)))
            assertFalse(conversations.get(saved.id)!!.character.regexScripts.single().disabled)
            assertEquals("Opening", (result.getValue("snapshot").jsonObject.getValue("messages") as kotlinx.serialization.json.JsonArray).first().jsonObject.getValue("display").jsonPrimitive.content)
            vm.invokeBrowser(actor, revision(), "messages.create", kotlinx.serialization.json.Json.parseToJsonElement("""{"messages":[{"role":"user","message":"choice"}]}""").jsonObject)
            assertEquals("choice", conversations.get(saved.id)!!.turns.last().selected.message.content)
            kotlinx.coroutines.delay(1200)
            assertEquals("After", vm.uiState.value.messages.first().displayContent)
            assertEquals("Opening", conversations.get(saved.id)!!.turns.first().selected.message.content)
        } finally {
            androidx.lifecycle.ViewModelStore().apply { put("test", vm); clear() }
            directory.deleteRecursively()
        }
    }

    @Test fun `native surfaces return after chat completion failure and cancellation`() = kotlinx.coroutines.runBlocking {
        for (ending in listOf("complete", "failure", "cancel")) {
            val directory = Files.createTempDirectory("native-chat-refresh").toFile()
            try {
                val program = nativeActionProgram("")
                val character = DemoConversationContent.character.copy(firstMessage = "Opening",
                    nativeAdaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), script = program))
                val conversations = ConversationRepository(directory, PromptCompiler())
                val saved = conversations.create(character, DemoConversationContent.persona, DemoConversationContent.preset)
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val generator = FakeGenerator { _, _ -> flow {
                    emit(GenerationEvent.TextDelta("Reply"))
                    started.complete(Unit)
                    release.await()
                    when (ending) {
                        "complete" -> emit(GenerationEvent.Finished("stop"))
                        "failure" -> error("Synthetic failure")
                        else -> awaitCancellation()
                    }
                } }
                val vm = ChatViewModel(repository(), PromptCompiler(), generator, conversations, FixedPresetSource())
                try {
                    vm.loadConversation(saved.id)
                    val original = awaitNative(vm).nativeSurfaces.single().revision
                    vm.updateInput("Continue"); vm.send()
                    kotlinx.coroutines.withTimeout(5000) { started.await() }
                    assertTrue(vm.uiState.value.nativeSurfaces.isEmpty())
                    if (ending == "cancel") vm.cancel() else release.complete(Unit)
                    val completed = awaitNative(vm)
                    assertFalse(completed.running)
                    assertTrue("$ending must reproject the completed history", original != completed.nativeSurfaces.single().revision)
                } finally { androidx.lifecycle.ViewModelStore().apply { put("test", vm); clear() } }
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun `native actions persist independent commits reject duplicate clicks and restore candidate heads`() = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("native-actions").toFile()
        try {
            val program = nativeActionProgram("await c.program.replace({count:(c.programState.count || 0)+1}); await c.draft.replace('chosen');")
            val character = DemoConversationContent.character.copy(firstMessage = "A", alternateFirstMessages = listOf("B"),
                nativeAdaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), script = program))
            val conversations = ConversationRepository(directory, PromptCompiler())
            val saved = conversations.create(character, DemoConversationContent.persona, DemoConversationContent.preset)
            val vm = ChatViewModel(repository(), PromptCompiler(), FakeGenerator { _, _ -> flow {} }, conversations, FixedPresetSource())
            vm.loadConversation(saved.id)
            val before = awaitNative(vm)
            val surface = before.nativeSurfaces.single()
            val invocation = NativeSurfaceInvocation(surface.id, surface.revision, surface.data.actions.single())
            vm.invokeNativeAction(invocation)
            vm.invokeNativeAction(invocation)
            awaitNative(vm, "1")
            var disk = ConversationRepository(directory, PromptCompiler()).get(saved.id)!!
            assertEquals("chosen", disk.draft)
            assertEquals(1, disk.turns.single().selected.nativeOperations.size)
            assertNull(disk.turns.single().selected.runtimeStateAfter!!.scriptState)
            assertEquals(JsonPrimitive(1), disk.runtimeState.scriptState!!["count"])
            vm.invokeNativeAction(invocation) // stale version, even though action contents are identical
            assertFalse(vm.uiState.value.nativeActionRunning)
            assertEquals(1, conversations.get(saved.id)!!.turns.single().selected.nativeOperations.size)
            vm.nextVariant()
            awaitNative(vm, "0")
            assertEquals("", vm.uiState.value.input)
            vm.previousVariant()
            awaitNative(vm, "1")
            vm.editMessage(vm.uiState.value.messages.single().message.id, "Edited", MessageEditMode.RESTART)
            awaitNative(vm, "0")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5000) { while (conversations.get(saved.id)!!.turns.single().selected.nativeOperations.isNotEmpty()) kotlinx.coroutines.delay(10) }
            }
            disk = ConversationRepository(directory, PromptCompiler()).get(saved.id)!!
            assertTrue(disk.turns.single().selected.nativeOperations.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `cancelled auxiliary generation retains previous durable write and prevents later write`() = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("native-cancel").toFile()
        try {
            val program = nativeActionProgram("await c.program.replace({count:1}); await c.generation.text({prompt:'Describe'}); await c.program.replace({count:2});")
            val character = DemoConversationContent.character.copy(firstMessage = "A", nativeAdaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), script = program))
            val conversations = ConversationRepository(directory, PromptCompiler())
            val saved = conversations.create(character, DemoConversationContent.persona, DemoConversationContent.preset)
            val started = CompletableDeferred<Unit>()
            val generator = object : ConversationGenerator {
                override suspend fun validateTokens(connection: StoredConnection, plan: GenerationPlan): ProviderTokenValidation {
                    assertNull(plan.declaredContextTokens)
                    return ProviderTokenValidation(2_100_000, TokenCountQuality.EXACT, "test")
                }
                override fun stream(connection: StoredConnection, plan: GenerationPlan) = flow<GenerationEvent> {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            val vm = ChatViewModel(repository(), PromptCompiler(), generator, conversations, FixedPresetSource())
            vm.loadConversation(saved.id)
            val surface = awaitNative(vm).nativeSurfaces.single()
            vm.invokeNativeAction(NativeSurfaceInvocation(surface.id, surface.revision, surface.data.actions.single()))
            kotlinx.coroutines.withTimeout(5000) { started.await() }
            vm.cancelNativeAction()
            awaitNative(vm, "1")
            val disk = ConversationRepository(directory, PromptCompiler()).get(saved.id)!!
            assertEquals(JsonPrimitive(1), disk.runtimeState.scriptState!!["count"])
            val operation = disk.turns.single().selected.nativeOperations.single()
            assertEquals(NativeOperationStatus.CANCELLED, operation.status)
            assertEquals(1, operation.commits.size)
            assertEquals(1, operation.generationRequests.size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `failed native write never publishes success and can retry after storage recovers`() = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("native-save-failure").toFile()
        try {
            val program = nativeActionProgram("await c.program.replace({count:1}); await c.generation.text({prompt:'Describe'}); await c.program.replace({count:2});")
            val character = DemoConversationContent.character.copy(firstMessage = "A", nativeAdaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), script = program))
            val conversations = ConversationRepository(directory, PromptCompiler())
            val saved = conversations.create(character, DemoConversationContent.persona, DemoConversationContent.preset)
            var blockOnce = true
            val generator = object : ConversationGenerator {
                override suspend fun validateTokens(connection: StoredConnection, plan: GenerationPlan) = ProviderTokenValidation(10, TokenCountQuality.EXACT, "test")
                override fun stream(connection: StoredConnection, plan: GenerationPlan) = flow<GenerationEvent> {
                    if (blockOnce) {
                        blockOnce = false
                        conversations.store.beforeCommit = { throw IOException("Injected SQLite commit failure") }
                    }
                    emit(GenerationEvent.TextDelta("Result")); emit(GenerationEvent.Finished("stop"))
                }
            }
            val vm = ChatViewModel(repository(), PromptCompiler(), generator, conversations, FixedPresetSource())
            vm.loadConversation(saved.id)
            fun invoke(surface: NativeRenderedSurface) = vm.invokeNativeAction(NativeSurfaceInvocation(surface.id, surface.revision, surface.data.actions.single()))
            invoke(awaitNative(vm).nativeSurfaces.single())
            val failed = kotlinx.coroutines.withTimeout(10000) { vm.uiState.first { it.storageFailed && !it.nativeActionRunning } }
            assertTrue(failed.message.orEmpty().contains("结束状态未能保存"))
            assertEquals(JsonPrimitive(1), conversations.get(saved.id)!!.runtimeState.scriptState!!["count"])
            conversations.store.beforeCommit = null
            vm.retrySave()
            awaitMvuIdle(vm)
            invoke(awaitNative(vm, "1").nativeSurfaces.single())
            awaitNative(vm, "2")
            val disk = ConversationRepository(directory, PromptCompiler()).get(saved.id)!!
            assertEquals(JsonPrimitive(2), disk.runtimeState.scriptState!!["count"])
            assertEquals(listOf(NativeOperationStatus.FAILED, NativeOperationStatus.COMPLETE), disk.turns.single().selected.nativeOperations.map { it.status })
        } finally { directory.deleteRecursively() }
    }

    private fun nativeActionProgram(body: String): io.github.zvensmoluya.tavernplayer.content.NativeScriptProgram {
        val code = "export function present(c){return {surface:'action_group',title:String(c.programState.count || 0),actions:[{id:'run',label:'Run',handler:'run'}]};} export async function run(c){" + body + "}"
        return io.github.zvensmoluya.tavernplayer.content.NativeScriptProgram(
            modules = listOf(io.github.zvensmoluya.tavernplayer.content.NativeScriptModule("main", code, listOf("fixture"), "Synthetic operation")),
            surfaces = listOf(io.github.zvensmoluya.tavernplayer.content.NativeSurfaceEntry("actions", "main", "present", io.github.zvensmoluya.tavernplayer.content.NativeSurfaceType.ACTION_GROUP)),
            handlers = listOf(io.github.zvensmoluya.tavernplayer.content.NativeHandlerEntry("run", "main", "run")),
            capabilities = io.github.zvensmoluya.tavernplayer.content.NativeScriptCapability.entries.toSet() - io.github.zvensmoluya.tavernplayer.content.NativeScriptCapability.MVU_REPLACE,
        )
    }

    private suspend fun awaitNative(vm: ChatViewModel, title: String? = null): ChatUiState = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        try {
            kotlinx.coroutines.withTimeout(10000) {
                vm.uiState.first { !it.busy && it.nativeSurfaces.isNotEmpty() && (title == null || it.nativeSurfaces.single().data.title == title) }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Native title $title not ready: ${vm.uiState.value.message}; surface error=${vm.uiState.value.nativeSurfaceError}", timeout)
        }
    }

    @Test fun `mvu and ejs real engines follow chat candidates edits resets and disk recovery`() = runTest {
        val directory = Files.createTempDirectory("mvu-chat").toFile()
        val assets = java.io.File(System.getProperty("mvuProbeAssets"), "mvu")
        org.junit.Assume.assumeTrue(java.io.File(assets, "runtime.js").isFile)
        val fixture = kotlinx.serialization.json.Json.parseToJsonElement(java.io.File(assets, "state-card.json").readText()).jsonObject
        val runtime = io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuConversationRuntime {
            java.io.File(assets, "runtime.js").readText()
        }
        val ejs = io.github.zvensmoluya.tavernplayer.conversation.ejs.QuickJsEjsRuntime(loadBundle = {
            java.io.File(requireNotNull(assets.parentFile).parentFile, "app-assets/ejs/runtime.js").readText()
        })
        val template = "EJS_DAY=<%= getvar('stat_data.days') %>; EJS_USER=<%= getChatMessage(-1, 'user') %>;"
        val original = DemoConversationContent.character
        val character = original.copy(firstMessage = "Opening.", alternateFirstMessages = listOf("<initvar>\ndays: 3\n</initvar>"),
            regexScripts = listOf(RegexDefinition(
                id = "mvu-panel", name = "MVU panel", findRegex = "<StatusPlaceHolderImpl/>",
                replaceString = "WORLD_STATE_PANEL", placements = setOf(RegexPlacement.AI_OUTPUT), markdownOnly = true,
            )),
            description = "Current variables: {{get_message_variable::stat_data}}",
            nativeAdaptation = NativeAdaptation(sourceSha256 = original.sourceSha256,
                mvu = io.github.zvensmoluya.tavernplayer.content.NativeMvuProgram(fixture.getValue("schemaScript").jsonPrimitive.content),
                stateBindings = listOf(io.github.zvensmoluya.tavernplayer.content.NativeStateBinding("day", io.github.zvensmoluya.tavernplayer.content.NativeStateSource.MVU,
                    "/stat_data/days", io.github.zvensmoluya.tavernplayer.content.ConversationStateValueType.NUMBER)),
                status = io.github.zvensmoluya.tavernplayer.content.NativeStatusView(items = listOf(io.github.zvensmoluya.tavernplayer.content.NativeStatusItem("day", "Day"))),
                ejsTemplates = listOf(io.github.zvensmoluya.tavernplayer.content.NativeWorldBookReference("init", "template",
                    io.github.zvensmoluya.tavernplayer.content.NativeWorldBookTextSelectionValidator.sha256(template)))),
            worldBooks = listOf(WorldBookDefinition("init", entries = listOf(
                WorldBookEntryDefinition("initial", name = "[initvar]", enabled = false, content = "days: 0"),
                WorldBookEntryDefinition("template", constant = true, content = template)))))
        try {
            val conversations = ConversationRepository(directory, PromptCompiler(), ioDispatcher = mainDispatcherRule.dispatcher, mvuRuntime = runtime)
            val saved = conversations.create(character, DemoConversationContent.persona, DemoConversationContent.preset)
            suspend fun days(): Int = conversations.get(saved.id)!!.runtimeState.mvuState!!.data.getValue("stat_data").jsonObject.getValue("days").jsonPrimitive.content.toInt()
            fun text(delta: Int) = "Story. <UpdateVariable><JSONPatch>[{\"op\":\"delta\",\"path\":\"/days\",\"value\":$delta}]</JSONPatch></UpdateVariable>"
            fun assertProcessed(message: ConversationMessage, raw: String) {
                assertEquals(raw, message.sourceText)
                assertFalse(message.sourceText.contains("<StatusPlaceHolderImpl/>"))
                assertEquals(1, Regex("<StatusPlaceHolderImpl/>").findAll(message.content).count())
            }
            var delta = 2
            var prompt = ""
            val generator = FakeGenerator { _, plan -> flow {
                prompt = plan.messages.joinToString("\n") { it.content }
                emit(GenerationEvent.TextDelta(text(delta)))
                emit(GenerationEvent.Finished("stop"))
            } }
            val vm = ChatViewModel(repository(), PromptCompiler(), generator, conversations, FixedPresetSource(),
                projectionDispatcher = mainDispatcherRule.dispatcher, mvuRuntime = runtime, ejsRuntime = ejs)
            suspend fun assertSavedDays(expected: Int) {
                assertEquals("Conversation result: ${vm.uiState.value.message}", JsonPrimitive(expected), vm.uiState.value.nativeState["day"])
                // Editing exposes the new view before persistNow finishes; await the repository acknowledgement.
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    kotlinx.coroutines.withTimeout(60_000) {
                        while (conversations.get(saved.id)?.runtimeState?.mvuState?.data
                            ?.get("stat_data")?.jsonObject?.get("days")?.jsonPrimitive?.content?.toIntOrNull() != expected) kotlinx.coroutines.delay(10)
                    }
                }
                assertEquals(expected, days())
            }
            vm.loadConversation(saved.id)
            assertEquals(0, days())
            vm.nextVariant()
            awaitMvuIdle(vm)
            // Candidate navigation persists on a debounce; sending must nevertheless use the selected checkpoint.
            vm.updateInput("Go."); vm.send(); awaitMvuIdle(vm)
            assertSavedDays(5)
            assertProcessed(vm.uiState.value.messages.last().message, text(2))
            assertTrue(vm.uiState.value.messages.last().displayContent.contains("WORLD_STATE_PANEL"))
            assertEquals(JsonPrimitive(5), vm.uiState.value.nativeState["day"])
            assertEquals(JsonPrimitive(5), vm.uiState.value.messages.last().nativeStateAfter!!["day"])
            assertTrue(vm.uiState.value.conversationState.isEmpty())
            assertTrue(prompt.contains("EJS_DAY=3; EJS_USER=Go.;"))
            assertTrue(prompt.contains("\"days\":3"))
            assertEquals(ChatMessageStatus.COMPLETE, vm.uiState.value.messages.last().status)
            assertTrue(vm.uiState.value.messages.last().message.sourceText.contains("JSONPatch"))
            delta = 4
            vm.regenerate(); awaitMvuIdle(vm)
            assertSavedDays(7)
            assertProcessed(vm.uiState.value.messages.last().message, text(4))
            assertEquals(JsonPrimitive(7), vm.uiState.value.nativeState["day"])
            assertEquals(JsonPrimitive(7), vm.uiState.value.messages.last().nativeStateAfter!!["day"])
            assertTrue(vm.uiState.value.conversationState.isEmpty())
            assertTrue(prompt.contains("EJS_DAY=3; EJS_USER=Go.;"))
            vm.previousVariant()
            vm.updateInput("Continue."); vm.send(); awaitMvuIdle(vm)
            assertSavedDays(9)
            assertProcessed(vm.uiState.value.messages.last().message, text(4))
            assertEquals(JsonPrimitive(9), vm.uiState.value.nativeState["day"])
            assertEquals(JsonPrimitive(9), vm.uiState.value.messages.last().nativeStateAfter!!["day"])
            assertTrue(vm.uiState.value.conversationState.isEmpty())
            assertTrue(prompt.contains("EJS_DAY=5; EJS_USER=Continue.;"))
            assertTrue(prompt.contains("\"days\":5"))
            val target = vm.uiState.value.messages[2].message.id
            vm.editMessage(target, text(10), MessageEditMode.TEXT_ONLY); awaitMvuIdle(vm)
            assertEquals(9, days())
            assertEquals(5, vm.uiState.value.messages.size)
            val other = conversations.create(original, DemoConversationContent.persona, DemoConversationContent.preset)
            vm.editMessage(target, text(10), MessageEditMode.RESTART)
            vm.loadConversation(other.id) // An in-flight edit must not write into a different conversation.
            vm.resetConversation()
            awaitMvuIdle(vm)
            assertEquals(saved.id, vm.uiState.value.conversationId)
            assertNull(conversations.get(other.id)!!.runtimeState.mvuState)
            assertSavedDays(13)
            assertProcessed(vm.uiState.value.messages.last().message, text(10))
            assertEquals(JsonPrimitive(13), vm.uiState.value.nativeState["day"])
            assertEquals(JsonPrimitive(13), vm.uiState.value.messages.last().nativeStateAfter!!["day"])
            assertTrue(vm.uiState.value.conversationState.isEmpty())
            assertEquals(3, vm.uiState.value.messages.size)
            assertEquals(13, ConversationRepository(directory, PromptCompiler()).get(saved.id)!!.runtimeState.mvuState!!
                .data.getValue("stat_data").jsonObject.getValue("days").jsonPrimitive.content.toInt())
            val restored = ConversationRepository(directory, PromptCompiler()).get(saved.id)!!
            assertProcessed(restored.turns.last().selected.message, text(10))
            val restoredPlan = ejs.compile(PromptCompiler(), NormalGenerationInput(restored.character, restored.persona,
                restored.turns.map { it.selected.message }, DemoConversationContent.preset, runtimeState = restored.runtimeState), mutableMapOf()) as CompilationResult.Success
            assertTrue(restoredPlan.plan.messages.any { "EJS_DAY=13; EJS_USER=Go.;" in it.content })
            vm.editMessage(vm.uiState.value.messages.first().message.id, "<initvar>\ndays: 20\n</initvar>", MessageEditMode.RESTART)
            awaitMvuIdle(vm)
            assertSavedDays(20)
            assertEquals(JsonPrimitive(20), vm.uiState.value.nativeState["day"])
            assertEquals(JsonPrimitive(20), vm.uiState.value.messages.last().nativeStateAfter!!["day"])
            assertTrue(vm.uiState.value.conversationState.isEmpty())
            assertEquals(1, vm.uiState.value.messages.size)
            vm.resetConversation(); awaitMvuIdle(vm)
            assertSavedDays(0)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `mvu partial cancelled and truncated replies do not commit variable commands`() = runTest {
        val directory = Files.createTempDirectory("mvu-incomplete").toFile()
        val assets = java.io.File(System.getProperty("mvuProbeAssets"), "mvu")
        org.junit.Assume.assumeTrue(java.io.File(assets, "runtime.js").isFile)
        val runtime = io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuConversationRuntime { java.io.File(assets, "runtime.js").readText() }
        val original = DemoConversationContent.character
        val character = original.copy(firstMessage = "Opening.", alternateFirstMessages = emptyList(),
            nativeAdaptation = NativeAdaptation(sourceSha256 = original.sourceSha256,
                mvu = io.github.zvensmoluya.tavernplayer.content.NativeMvuProgram("$(() => registerMvuSchema(z.object({days:z.number().prefault(0)}).prefault({})));")))
        try {
            val conversations = ConversationRepository(directory, PromptCompiler(), ioDispatcher = mainDispatcherRule.dispatcher, mvuRuntime = runtime)
            val saved = conversations.create(character, DemoConversationContent.persona, DemoConversationContent.preset)
            val checkpoint = saved.runtimeState.mvuState
            var mode = "cancel"
            val emitted = CompletableDeferred<Unit>()
            val vm = ChatViewModel(repository(), PromptCompiler(), FakeGenerator { _, _ -> flow {
                emit(GenerationEvent.TextDelta("Story. <UpdateVariable><JSONPatch>[{\"op\":\"delta\",\"path\":\"/days\",\"value\":5}]</JSONPatch></UpdateVariable>"))
                emitted.complete(Unit)
                if (mode == "cancel") awaitCancellation()
                if (mode == "truncated") emit(GenerationEvent.Finished("length"))
            } }, conversations, FixedPresetSource(), projectionDispatcher = mainDispatcherRule.dispatcher, mvuRuntime = runtime)
            vm.loadConversation(saved.id); vm.updateInput("Go."); vm.send()
            emitted.await()
            assertEquals(checkpoint, conversations.get(saved.id)!!.runtimeState.mvuState)
            vm.cancel(); awaitMvuIdle(vm)
            assertEquals(checkpoint, conversations.get(saved.id)!!.runtimeState.mvuState)
            assertEquals(ChatMessageStatus.CANCELLED, vm.uiState.value.messages.last().status)
            assertFalse(vm.uiState.value.messages.last().message.content.contains("<StatusPlaceHolderImpl/>"))
            mode = "truncated"; vm.regenerate(); awaitMvuIdle(vm)
            assertEquals(checkpoint, conversations.get(saved.id)!!.runtimeState.mvuState)
            assertEquals(ChatMessageStatus.ERROR, vm.uiState.value.messages.last().status)
            assertFalse(vm.uiState.value.messages.last().message.content.contains("<StatusPlaceHolderImpl/>"))
            mode = "no-finish"; vm.regenerate(); awaitMvuIdle(vm)
            assertEquals(checkpoint, conversations.get(saved.id)!!.runtimeState.mvuState)
            assertEquals(ChatMessageStatus.ERROR, vm.uiState.value.messages.last().status)
            assertFalse(vm.uiState.value.messages.last().message.content.contains("<StatusPlaceHolderImpl/>"))
        } finally { directory.deleteRecursively() }
    }

    private suspend fun awaitMvuIdle(vm: ChatViewModel) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        kotlinx.coroutines.withTimeout(60_000) {
            while (vm.uiState.value.busy) kotlinx.coroutines.delay(10)
        }
    }

    private fun memoryCharacter(): io.github.zvensmoluya.tavernplayer.content.CharacterAsset {
        val entry = WorldBookEntryDefinition("memory-instruction", content = "记住已经一起完成的事情。", enabled = false)
        val reference = io.github.zvensmoluya.tavernplayer.content.NativeWorldBookReference("memory-source", entry.id,
            io.github.zvensmoluya.tavernplayer.content.NativeWorldBookTextSelectionValidator.sha256(entry.content))
        val original = DemoConversationContent.character
        return original.copy(worldBooks = listOf(WorldBookDefinition("memory-source", entries = listOf(entry))),
            nativeAdaptation = NativeAdaptation(sourceSha256 = original.sourceSha256, memories = listOf(
                io.github.zvensmoluya.tavernplayer.content.NativeMemoryDefinition("journey", "旅行记忆", reference, firstReply = 2, everyReplies = 2))))
    }

    @Test fun `automatic memory persists with candidates reaches next prompt and invalidates on text edit`() = runTest {
        val directory = Files.createTempDirectory("conversation-memory").toFile()
        try {
            val conversations = ConversationRepository(directory, PromptCompiler(), ioDispatcher = mainDispatcherRule.dispatcher)
            val saved = conversations.create(memoryCharacter(), DemoConversationContent.persona, DemoConversationContent.preset)
            var analyses = 0
            var latestMainPrompt = ""
            val generator = FakeGenerator { _, plan -> flow {
                if (plan.messages.first().origin.stage == "conversation-memory-contract") {
                    emit(GenerationEvent.TextDelta("旅行笔记 ${++analyses}"))
                } else {
                    latestMainPrompt = plan.messages.joinToString("\n") { it.content }
                    emit(GenerationEvent.TextDelta("我们抵达河边。"))
                }
                emit(GenerationEvent.Finished("stop"))
            } }
            val vm = ChatViewModel(repository(), PromptCompiler(), generator, conversations, FixedPresetSource(), projectionDispatcher = mainDispatcherRule.dispatcher)
            vm.loadConversation(saved.id)
            vm.updateInput("出发吧。")
            vm.send()
            assertEquals(1, analyses)
            assertEquals("旅行笔记 1", vm.uiState.value.memories.getValue("journey").content)
            assertEquals(vm.uiState.value.memories, ConversationRepository(directory, PromptCompiler()).get(saved.id)!!.runtimeState.memories)
            vm.regenerate()
            assertEquals(2, analyses)
            assertEquals("旅行笔记 2", vm.uiState.value.memories.getValue("journey").content)
            vm.previousVariant()
            assertEquals("旅行笔记 1", vm.uiState.value.memories.getValue("journey").content)
            vm.nextVariant()
            assertEquals("旅行笔记 2", vm.uiState.value.memories.getValue("journey").content)
            vm.updateInput("继续散步。")
            vm.send()
            assertTrue(latestMainPrompt.contains("旅行笔记 2"))
            assertFalse(latestMainPrompt.contains("旅行笔记 1"))
            assertEquals(2, analyses)
            val message = vm.uiState.value.messages.first { it.message.role == MessageRole.USER }.message
            vm.editMessage(message.id, "沿着河道出发吧。", MessageEditMode.TEXT_ONLY)
            assertTrue(vm.uiState.value.memories.isEmpty())
            assertTrue(ConversationRepository(directory, PromptCompiler()).get(saved.id)!!.runtimeState.memories.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `unconfirmed numeric state does not block independent text analysis or become confirmed`() = runTest {
        val directory = Files.createTempDirectory("memory-unconfirmed-state").toFile()
        try {
            val original = memoryCharacter()
            val character = original.copy(nativeAdaptation = original.nativeAdaptation!!.copy(
                state = listOf(ConversationStateDefinition("score", type = ConversationStateValueType.NUMBER, initialValue = JsonPrimitive(0))),
                assistantStateAdapters = listOf(AssistantStateAdapterDefinition(LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1,
                    listOf(AssistantStateMapping("/score", "score"))))))
            val conversations = ConversationRepository(directory, PromptCompiler(), ioDispatcher = mainDispatcherRule.dispatcher)
            val saved = conversations.create(character, DemoConversationContent.persona, DemoConversationContent.preset)
            var evidence = ""
            val generator = FakeGenerator { _, plan -> flow {
                if (plan.messages.first().origin.stage == "conversation-memory-contract") {
                    evidence = plan.messages.last().content
                    emit(GenerationEvent.TextDelta("已经出发；分数变化未确认。"))
                } else emit(GenerationEvent.TextDelta("我们沿河出发。"))
                emit(GenerationEvent.Finished("stop"))
            } }
            val vm = ChatViewModel(repository(), PromptCompiler(), generator, conversations, FixedPresetSource(), projectionDispatcher = mainDispatcherRule.dispatcher)
            vm.loadConversation(saved.id)
            vm.updateInput("出发吧。")
            vm.send()
            assertTrue(evidence.contains("\"stateConfirmedForLastReply\":false"))
            assertEquals("已经出发；分数变化未确认。", vm.uiState.value.memories.getValue("journey").content)
            assertTrue(vm.uiState.value.messages.last().stateUnconfirmed)
            assertEquals(JsonPrimitive(0), vm.uiState.value.conversationState["score"])
            assertNull(conversations.get(saved.id)!!.turns.last().selected.message.stateConfirmation)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `cancelled memory preserves completed reply and can be retried without another chat turn`() = runTest {
        val directory = Files.createTempDirectory("cancel-memory").toFile()
        try {
            val conversations = ConversationRepository(directory, PromptCompiler(), ioDispatcher = mainDispatcherRule.dispatcher)
            val saved = conversations.create(memoryCharacter(), DemoConversationContent.persona, DemoConversationContent.preset)
            var analyses = 0
            val generator = FakeGenerator { _, plan -> flow {
                if (plan.messages.first().origin.stage == "conversation-memory-contract") {
                    if (++analyses == 1) awaitCancellation()
                    emit(GenerationEvent.TextDelta("完成的记忆"))
                } else emit(GenerationEvent.TextDelta("已完成的正文。"))
                emit(GenerationEvent.Finished("stop"))
            } }
            val vm = ChatViewModel(repository(), PromptCompiler(), generator, conversations, FixedPresetSource(), projectionDispatcher = mainDispatcherRule.dispatcher)
            vm.loadConversation(saved.id)
            vm.updateInput("出发。")
            vm.send()
            vm.cancel()
            assertEquals(ChatMessageStatus.COMPLETE, vm.uiState.value.messages.last().status)
            assertTrue(vm.uiState.value.memories.isEmpty())
            vm.refreshMemories()
            assertEquals(2, analyses)
            assertEquals(3, vm.uiState.value.messages.size)
            assertEquals("完成的记忆", vm.uiState.value.memories.getValue("journey").content)
            assertEquals(ChatMessageStatus.COMPLETE, vm.uiState.value.messages.last().status)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `cancelling during memory save commits once and stops remaining analyses`() = runTest {
        val directory = Files.createTempDirectory("memory-save-cancellation").toFile()
        try {
            lateinit var vm: ChatViewModel
            var cancelAtSave = false
            var analyses = 0
            val finishAnalysis = CompletableDeferred<Unit>()
            val conversations = ConversationRepository(directory, PromptCompiler(), ioDispatcher = mainDispatcherRule.dispatcher, now = {
                if (cancelAtSave && vm.uiState.value.memorySaving) {
                    cancelAtSave = false
                    vm.cancel()
                    vm.updateInput("must not replace the draft during save")
                    vm.resetConversation()
                }
                1L
            })
            val original = memoryCharacter()
            val adaptation = original.nativeAdaptation!!
            val saved = conversations.create(original.copy(nativeAdaptation = adaptation.copy(memories =
                adaptation.memories + adaptation.memories.single().copy(id = "second"))), DemoConversationContent.persona, DemoConversationContent.preset)
            val generator = FakeGenerator { _, plan -> flow {
                if (plan.messages.first().origin.stage == "conversation-memory-contract") {
                    analyses++
                    emit(GenerationEvent.TextDelta("已保存的旅行记忆"))
                    finishAnalysis.await()
                    cancelAtSave = true
                } else emit(GenerationEvent.TextDelta("我们沿河出发。"))
                emit(GenerationEvent.Finished("stop"))
            } }
            vm = ChatViewModel(repository(), PromptCompiler(), generator, conversations, FixedPresetSource(), projectionDispatcher = mainDispatcherRule.dispatcher)
            vm.loadConversation(saved.id)
            vm.updateInput("出发吧。")
            vm.send()
            finishAnalysis.complete(Unit)
            assertFalse(vm.uiState.value.running)
            assertFalse(vm.uiState.value.memorySaving)
            assertEquals(1, analyses)
            assertEquals("", vm.uiState.value.input)
            assertEquals(ChatMessageStatus.COMPLETE, vm.uiState.value.messages.last().status)
            val restored = ConversationRepository(directory, PromptCompiler()).get(saved.id)!!
            assertEquals(vm.uiState.value.memories, restored.runtimeState.memories)
            assertEquals(setOf("journey"), restored.runtimeState.memories.keys)
            assertEquals(restored.runtimeState.memories, restored.turns.last().selected.runtimeStateAfter?.memories)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `typing during analysis preserves draft and rejects obsolete memory`() = runTest {
        val directory = Files.createTempDirectory("stale-memory").toFile()
        try {
            val conversations = ConversationRepository(directory, PromptCompiler(), ioDispatcher = mainDispatcherRule.dispatcher)
            val saved = conversations.create(memoryCharacter(), DemoConversationContent.persona, DemoConversationContent.preset)
            lateinit var vm: ChatViewModel
            val generator = FakeGenerator { _, plan -> flow {
                if (plan.messages.first().origin.stage == "conversation-memory-contract") {
                    vm.updateInput("下一轮草稿")
                    emit(GenerationEvent.TextDelta("迟到的记忆"))
                } else emit(GenerationEvent.TextDelta("正文。"))
                emit(GenerationEvent.Finished("stop"))
            } }
            vm = ChatViewModel(repository(), PromptCompiler(), generator, conversations, FixedPresetSource(), projectionDispatcher = mainDispatcherRule.dispatcher)
            vm.loadConversation(saved.id)
            vm.updateInput("出发。")
            vm.send()
            assertEquals("下一轮草稿", vm.uiState.value.input)
            assertEquals("下一轮草稿", ConversationRepository(directory, PromptCompiler()).get(saved.id)!!.draft)
            assertTrue(vm.uiState.value.memories.isEmpty())
            assertEquals(ChatMessageStatus.COMPLETE, vm.uiState.value.messages.last().status)
        } finally { directory.deleteRecursively() }
    }

    private fun choiceAdaptation() = NativeAdaptation(sourceSha256 = "a".repeat(64), state = listOf(
        ConversationStateDefinition("phase", "阶段", ConversationStateValueType.STRING, initialValue = JsonPrimitive("探索中"), allowedStrings = listOf("探索中", "营地")),
        ConversationStateDefinition("outcome", "结果", ConversationStateValueType.STRING, initialValue = JsonPrimitive("进行中"), allowedStrings = listOf("进行中", "已撤离"))),
        playerChoices = listOf(io.github.zvensmoluya.tavernplayer.content.NativePlayerChoice("retreat", "返回营地", "结束探索。", "phase", listOf("探索中"), "尚未出发", "outcome", "已撤离", "我决定返回营地。")),
        status = io.github.zvensmoluya.tavernplayer.content.NativeStatusView(items = listOf(io.github.zvensmoluya.tavernplayer.content.NativeStatusItem("outcome", "结果"))))

    @Test fun `player choice cancellation persistence retry and branch switching retain explicit facts`() = runTest {
        val directory = Files.createTempDirectory("native-choice-lifecycle").toFile()
        try {
            val conversations = ConversationRepository(directory, PromptCompiler(), ioDispatcher = mainDispatcherRule.dispatcher)
            val record = conversations.create(DemoConversationContent.character.copy(nativeAdaptation = choiceAdaptation(),
                firstMessage = "探索一条林间小路。", alternateFirstMessages = listOf("沿着溪流前进。")), DemoConversationContent.persona, DemoConversationContent.preset)
            var attempts = 0
            val generator = FakeGenerator { _, plan -> flow {
                assertTrue(plan.projectedConversationState().contains("\"outcome\":\"已撤离\""))
                if (++attempts == 1) throw IOException("offline")
                emit(GenerationEvent.TextDelta("你回到营地，开始整理记录。"))
                emit(GenerationEvent.Finished("stop"))
            } }
            fun newViewModel() = ChatViewModel(repository(), PromptCompiler(), generator,
                ConversationRepository(directory, PromptCompiler(), ioDispatcher = mainDispatcherRule.dispatcher), FixedPresetSource(), projectionDispatcher = mainDispatcherRule.dispatcher)
            var vm = newViewModel().also { it.loadConversation(record.id) }
            vm.previewPlayerChoice("retreat")
            assertNotNull(vm.uiState.value.choicePreview)
            vm.cancelPlayerChoice()
            assertEquals("", vm.uiState.value.input)
            assertTrue(ConversationRepository(directory, PromptCompiler()).get(record.id)!!.turns.single().selected.playerChoiceCommits.isEmpty())
            vm.previewPlayerChoice("retreat")
            vm.confirmPlayerChoice()
            assertEquals(0, generator.calls)
            val saved = ConversationRepository(directory, PromptCompiler()).get(record.id)!!
            assertEquals(1, saved.turns.single().selected.playerChoiceCommits.size)
            assertEquals("我决定返回营地。", saved.draft)
            vm = newViewModel().also { it.loadConversation(record.id) }
            assertEquals(saved.draft, vm.uiState.value.input)
            saved.turns.single().selected.runtimeStateAfter!!.conversationState.values.forEach { (key, value) ->
                assertEquals(value, vm.uiState.value.messages.single().nativeStateAfter!![key])
            }
            vm.nextVariant()
            assertEquals("", vm.uiState.value.input)
            assertEquals(JsonPrimitive("进行中"), vm.uiState.value.conversationState["outcome"])
            assertEquals(JsonPrimitive("进行中"), vm.uiState.value.messages.single().nativeStateAfter!!["outcome"])
            vm.previousVariant()
            assertEquals(JsonPrimitive("已撤离"), vm.uiState.value.conversationState["outcome"])
            assertEquals(JsonPrimitive("已撤离"), vm.uiState.value.messages.single().nativeStateAfter!!["outcome"])
            assertEquals("", vm.uiState.value.input)
            vm.updateInput(saved.draft)
            vm.send()
            assertTrue(vm.uiState.value.retryAvailable)
            vm.retry()
            assertEquals(2, attempts)
            val restored = ConversationRepository(directory, PromptCompiler()).get(record.id)!!
            assertEquals(JsonPrimitive("已撤离"), restored.runtimeState.conversationState.values["outcome"])
            assertEquals(1, restored.turns.first().selected.playerChoiceCommits.size)
            assertNull(restored.choiceDraft)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `regenerating a chosen reply starts before that choice and preserves the old candidate`() = runTest {
        val generator = FakeGenerator { _, plan -> flow {
            assertTrue(plan.projectedConversationState().contains("\"outcome\":\"进行中\""))
            emit(GenerationEvent.TextDelta("你继续观察林间的足迹。"))
            emit(GenerationEvent.Finished("stop"))
        } }
        val vm = viewModel(generator, DemoConversationContent.character.copy(nativeAdaptation = choiceAdaptation()))
        vm.updateInput("继续探索")
        vm.send()
        vm.previewPlayerChoice("retreat")
        vm.confirmPlayerChoice()
        assertEquals(JsonPrimitive("已撤离"), vm.uiState.value.conversationState["outcome"])
        vm.regenerate()
        assertEquals(2, generator.calls)
        assertEquals("", vm.uiState.value.input)
        assertEquals(JsonPrimitive("进行中"), vm.uiState.value.conversationState["outcome"])
        vm.previousVariant()
        assertEquals(JsonPrimitive("已撤离"), vm.uiState.value.conversationState["outcome"])
        assertEquals(1, vm.uiState.value.messages.last().playerChoiceCommits.size)
        val message = vm.uiState.value.messages.last().message
        vm.editMessage(message.id, "修改过的林间描述。", MessageEditMode.RESTART)
        assertEquals(JsonPrimitive("进行中"), vm.uiState.value.conversationState["outcome"])
        assertTrue(vm.uiState.value.messages.last().playerChoiceCommits.isEmpty())
    }

    @Test fun `player choice disk failure exposes neither changed facts nor a new draft`() = runTest {
        val directory = Files.createTempDirectory("native-choice-disk-failure").toFile()
        try {
            val conversations = ConversationRepository(directory, PromptCompiler(), ioDispatcher = mainDispatcherRule.dispatcher)
            val created = conversations.create(DemoConversationContent.character.copy(nativeAdaptation = choiceAdaptation()), DemoConversationContent.persona, DemoConversationContent.preset)
            conversations.store.beforeCommit = { throw IOException("Injected SQLite commit failure") }
            val vm = ChatViewModel(repository(), PromptCompiler(), FakeGenerator { _, _ -> flow { error("must not generate") } }, conversations,
                FixedPresetSource(), projectionDispatcher = mainDispatcherRule.dispatcher)
            vm.loadConversation(created.id)
            vm.previewPlayerChoice("retreat")
            vm.confirmPlayerChoice()
            assertEquals("", vm.uiState.value.input)
            assertEquals(JsonPrimitive("进行中"), vm.uiState.value.conversationState["outcome"])
            assertTrue(vm.uiState.value.message.orEmpty().contains("选择未保存"))
            assertFalse(vm.uiState.value.choiceSaving)
            assertTrue(vm.uiState.value.messages.last().playerChoiceCommits.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `switching immediately after editing preserves each conversation draft on disk`() = runTest {
        val directory = Files.createTempDirectory("conversation-draft-switch").toFile()
        try {
            val conversations = ConversationRepository(directory, PromptCompiler(), ioDispatcher = mainDispatcherRule.dispatcher)
            val first = conversations.create(DemoConversationContent.character, DemoConversationContent.persona, DemoConversationContent.preset)
            val second = conversations.create(DemoConversationContent.character, DemoConversationContent.persona, DemoConversationContent.preset)
            val vm = ChatViewModel(repository(), PromptCompiler(), FakeGenerator { _, _ -> flow {} }, conversations,
                FixedPresetSource(), projectionDispatcher = mainDispatcherRule.dispatcher)
            vm.loadConversation(first.id)
            vm.updateInput("尚未发送的预约资料")
            vm.loadConversation(second.id)
            vm.updateInput("另一段草稿")
            vm.loadConversation(first.id)
            assertEquals("尚未发送的预约资料", vm.uiState.value.input)
            val reopened = ConversationRepository(directory, PromptCompiler())
            assertEquals("尚未发送的预约资料", reopened.get(first.id)?.draft)
            assertEquals("另一段草稿", reopened.get(second.id)?.draft)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `switching during generation saves cancelled output before loading another record`() = runTest {
        val directory = Files.createTempDirectory("conversation-stream-switch").toFile()
        try {
            val conversations = ConversationRepository(directory, PromptCompiler(), ioDispatcher = mainDispatcherRule.dispatcher)
            val first = conversations.create(DemoConversationContent.character, DemoConversationContent.persona, DemoConversationContent.preset)
            val second = conversations.create(DemoConversationContent.character, DemoConversationContent.persona, DemoConversationContent.preset)
            val vm = ChatViewModel(repository(), PromptCompiler(), FakeGenerator { _, _ -> flow {
                emit(GenerationEvent.TextDelta("停笔前已经写下的正文。"))
                awaitCancellation()
            } }, conversations, FixedPresetSource(), projectionDispatcher = mainDispatcherRule.dispatcher)
            vm.loadConversation(first.id)
            vm.updateInput("继续")
            vm.send()
            assertTrue(vm.uiState.value.running)
            vm.loadConversation(second.id)
            assertEquals(second.id, vm.uiState.value.conversationId)
            assertFalse(vm.uiState.value.busy)
            assertEquals(second.turns.size, vm.uiState.value.messages.size)
            val saved = ConversationRepository(directory, PromptCompiler()).get(first.id)!!.turns.last().selected
            assertEquals(PersistedMessageStatus.CANCELLED, saved.status)
            assertEquals("停笔前已经写下的正文。", saved.message.sourceText)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `setup persists before its draft and generation retry preserves selected facts`() = runTest {
        val directory = Files.createTempDirectory("native-setup-lifecycle").toFile()
        try {
            val form = io.github.zvensmoluya.tavernplayer.content.NativeFormView(
                id = "setup", title = "开局", openingIndices = listOf(0), fields = listOf(
                    io.github.zvensmoluya.tavernplayer.content.NativeFormField("day", io.github.zvensmoluya.tavernplayer.content.NativeFormFieldType.NUMBER, "日期", required = true)),
                draftTemplate = "从第{{form.day}}天开始", setup = io.github.zvensmoluya.tavernplayer.content.NativeSetupContract(stateFields = mapOf("world-day" to "day"), openingIndex = 2),
            )
            val native = dayAdaptation().copy(forms = listOf(form, form.copy(id = "preset", title = "第二幕", openingIndices = listOf(2), setup = form.setup!!.copy(openingIndex = null))))
            // Empty source greetings are skipped in storage; source index 2 is variant index 1.
            val character = DemoConversationContent.character.copy(firstMessage = "开场选择", alternateFirstMessages = listOf("", "第二幕正文"), nativeAdaptation = native)
            val conversations = ConversationRepository(directory, PromptCompiler(), ioDispatcher = mainDispatcherRule.dispatcher)
            val created = conversations.create(character, DemoConversationContent.persona, DemoConversationContent.preset)
            var attempts = 0
            val generator = FakeGenerator { _, _ -> flow {
                if (++attempts == 1) throw IOException("offline")
                emit(GenerationEvent.TextDelta("第七天的早晨。"))
                emit(GenerationEvent.Finished("stop"))
            } }
            var vm = ChatViewModel(repository(), PromptCompiler(), generator, conversations, FixedPresetSource(), projectionDispatcher = mainDispatcherRule.dispatcher)
            vm.loadConversation(created.id)
            assertEquals(listOf(0, 2), vm.uiState.value.openingChoices.map { it.sourceIndex })
            vm.selectOpening(2)
            assertEquals(2, vm.uiState.value.messages.single().openingSourceIndex)
            vm.submitNativeForm("setup", mapOf("day" to listOf("9")))
            assertEquals("", vm.uiState.value.input)
            assertEquals(1.0, (vm.uiState.value.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)
            vm.selectOpening(0)
            vm.submitNativeForm("setup", mapOf("day" to listOf("7")))
            assertEquals("从第7天开始", vm.uiState.value.input)
            assertTrue(vm.uiState.value.openingChoices.isEmpty())
            val saved = ConversationRepository(directory, PromptCompiler()).get(created.id)!!
            assertEquals(vm.uiState.value.input, saved.draft)
            assertEquals(2, saved.turns.single().selected.openingSourceIndex)
            assertEquals("第二幕正文", saved.turns.single().selected.message.sourceText)
            assertEquals("setup", saved.runtimeState.setupCommit?.formId)
            vm = ChatViewModel(repository(), PromptCompiler(), generator,
                ConversationRepository(directory, PromptCompiler(), ioDispatcher = mainDispatcherRule.dispatcher),
                FixedPresetSource(), projectionDispatcher = mainDispatcherRule.dispatcher)
            vm.loadConversation(created.id)
            assertEquals("从第7天开始", vm.uiState.value.input)
            assertEquals(2, vm.uiState.value.messages.single().openingSourceIndex)
            vm.submitNativeForm("setup", mapOf("day" to listOf("9")))
            assertEquals(7.0, (vm.uiState.value.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)
            vm.send()
            assertTrue(vm.uiState.value.retryAvailable)
            vm.retry()
            assertEquals(2, attempts)
            assertEquals(2, vm.uiState.value.messages.first().openingSourceIndex)
            assertEquals(7.0, (vm.uiState.value.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)
            vm.resetConversation()
            assertEquals(1.0, (vm.uiState.value.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)
            assertEquals(0, vm.uiState.value.messages.single().openingSourceIndex)
            vm.submitNativeForm("setup", mapOf("day" to listOf("8")))
            assertEquals("从第8天开始", vm.uiState.value.input)
            assertEquals(2, vm.uiState.value.messages.single().openingSourceIndex)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `setup disk failure exposes neither state nor draft`() = runTest {
        val directory = Files.createTempDirectory("native-setup-failure").toFile()
        try {
            val native = dayAdaptation().copy(forms = listOf(io.github.zvensmoluya.tavernplayer.content.NativeFormView(
                id = "setup", title = "开局", marker = "<setup/>", fields = listOf(
                    io.github.zvensmoluya.tavernplayer.content.NativeFormField("day", io.github.zvensmoluya.tavernplayer.content.NativeFormFieldType.NUMBER, "日期")),
                draftTemplate = "{{form.day}}", setup = io.github.zvensmoluya.tavernplayer.content.NativeSetupContract(stateFields = mapOf("world-day" to "day")),
            )))
            val conversations = ConversationRepository(directory, PromptCompiler(), ioDispatcher = mainDispatcherRule.dispatcher)
            val created = conversations.create(DemoConversationContent.character.copy(firstMessage = "<setup/>", nativeAdaptation = native), DemoConversationContent.persona, DemoConversationContent.preset)
            conversations.store.beforeCommit = { throw IOException("Injected SQLite commit failure") }
            val vm = ChatViewModel(repository(), PromptCompiler(), FakeGenerator { _, _ -> flow {} }, conversations, FixedPresetSource(), projectionDispatcher = mainDispatcherRule.dispatcher)
            vm.loadConversation(created.id)
            vm.submitNativeForm("setup", mapOf("day" to listOf("7")))
            assertEquals("", vm.uiState.value.input)
            assertEquals(1.0, (vm.uiState.value.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)
            assertTrue(vm.uiState.value.message.orEmpty().contains("开局未保存"))
            assertFalse(vm.uiState.value.setupSaving)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `completed stream appends user once and records response metadata and latest trace`() = runTest {
        val generator = FakeGenerator { connection, _ ->
            flow {
                emit(GenerationEvent.RequestPrepared(preview(connection)))
                emit(GenerationEvent.ReasoningDelta("思考"))
                emit(GenerationEvent.ReasoningSignature("signature"))
                emit(GenerationEvent.TextDelta("欢迎回来。"))
                emit(GenerationEvent.Usage(GenerationUsage(10, 4, 14)))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("我想住一晚。")
        viewModel.send()

        val state = viewModel.uiState.value
        assertFalse(state.running)
        assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT), state.messages.map { it.message.role })
        assertEquals("欢迎回来。", state.messages.last().message.content)
        assertEquals("思考", state.messages.last().message.reasoning.single().text)
        assertEquals("signature", state.messages.last().message.reasoning.single().signature)
        assertEquals(ChatMessageStatus.COMPLETE, state.messages.last().status)
        assertEquals("stop", state.messages.last().metadata?.finishReason)
        assertEquals(14L, state.lastTrace?.usage?.totalTokens)
        assertNotNull(state.lastTrace?.providerPreview)
        assertTrue(state.regenerateAvailable)
    }

    @Test
    fun `completed assistant update dialect commits conversation state`() = runTest {
        val adaptation = dayAdaptation()
        val generator = FakeGenerator { _, _ ->
            flow {
                emit(GenerationEvent.TextDelta("正文\n<UpdateVariable>\n_.set('世界.日期', 1, 2);"))
                emit(GenerationEvent.TextDelta("\n</UpdateVariable>"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator, DemoConversationContent.character.copy(nativeAdaptation = adaptation))

        viewModel.updateInput("继续")
        viewModel.send()

        assertEquals(2.0, (viewModel.uiState.value.conversationState["world-day"] as JsonPrimitive).double, 0.0)
        assertEquals("正文", viewModel.uiState.value.messages.last().message.content)
        assertTrue(viewModel.uiState.value.messages.last().message.sourceText.orEmpty().contains("<UpdateVariable>"))
    }

    @Test
    fun `separate state confirmation is persisted and applied without changing assistant prose`() = runTest {
        val adaptation = dayAdaptation()
        val confirmation = "<UpdateVariable>\n_.set('世界.日期', 1, 2);\n</UpdateVariable>"
        val generator = FakeGenerator { _, _ ->
            flow {
                emit(GenerationEvent.TextDelta("只有正文。"))
                emit(GenerationEvent.AssistantStateConfirmed(confirmation))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator, DemoConversationContent.character.copy(nativeAdaptation = adaptation))

        viewModel.updateInput("继续")
        viewModel.send()

        val message = viewModel.uiState.value.messages.last().message
        assertEquals("只有正文。", message.content)
        assertEquals("只有正文。", message.sourceText)
        assertEquals(confirmation, message.stateConfirmation)
        assertEquals(2.0, (viewModel.uiState.value.conversationState["world-day"] as JsonPrimitive).double, 0.0)
    }

    @Test
    fun `failed assistant response does not commit its conversation state patch`() = runTest {
        val adaptation = dayAdaptation()
        val generator = FakeGenerator { _, _ ->
            flow {
                emit(GenerationEvent.TextDelta("正文\n<UpdateVariable>\n_.set('世界.日期', 1, 2);\n</UpdateVariable>"))
                error("stream failed")
            }
        }
        val viewModel = viewModel(generator, DemoConversationContent.character.copy(nativeAdaptation = adaptation))

        viewModel.updateInput("继续")
        viewModel.send()

        assertEquals(1.0, (viewModel.uiState.value.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)
        assertEquals(ChatMessageStatus.ERROR, viewModel.uiState.value.messages.last().status)
    }

    @Test
    fun `swipe restores conversation state and the selected snapshot reaches the next prompt`() = runTest {
        val adaptation = dayAdaptation()
        val plans = mutableListOf<GenerationPlan>()
        val generator = FakeGenerator { _, plan ->
            plans += plan
            val nextDay = plans.size + 1
            flow {
                emit(GenerationEvent.TextDelta("回复$nextDay\n<UpdateVariable>\n_.set('世界.日期', 1, $nextDay);\n</UpdateVariable>"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator, DemoConversationContent.character.copy(nativeAdaptation = adaptation))

        viewModel.updateInput("继续")
        viewModel.send()
        assertEquals(1, plans.size)
        assertTrue(plans[0].projectedConversationState().contains("\"world-day\":1"))
        assertEquals(2.0, (viewModel.uiState.value.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)

        viewModel.regenerate()
        assertEquals(2, plans.size)
        assertTrue(plans[1].projectedConversationState().contains("\"world-day\":1"))
        assertEquals(3.0, (viewModel.uiState.value.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)

        viewModel.previousVariant()
        assertEquals(2.0, (viewModel.uiState.value.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)
        viewModel.nextVariant()
        assertEquals(3.0, (viewModel.uiState.value.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)
        viewModel.previousVariant()

        viewModel.updateInput("下一轮")
        viewModel.send()
        assertEquals(3, plans.size)
        assertTrue(plans[2].projectedConversationState().contains("\"world-day\":2"))
    }

    @Test
    fun `completed and failed streams preserve narrative after an unclosed machine block`() = runTest {
        for (fail in listOf(false, true)) {
            val generator = FakeGenerator { _, _ -> flow {
                emit(GenerationEvent.TextDelta("<UpdateVariable><JSONPatch>[]</JSONPatch>\n\n正文仍然存在。"))
                if (fail) error("stream failed") else emit(GenerationEvent.Finished("stop"))
            } }
            val viewModel = viewModel(generator, DemoConversationContent.character.copy(nativeAdaptation = dayAdaptation()))
            viewModel.updateInput("继续")
            viewModel.send()
            val last = viewModel.uiState.value.messages.last()
            assertEquals("正文仍然存在。", last.message.content)
            assertEquals(if (fail) ChatMessageStatus.ERROR else ChatMessageStatus.COMPLETE, last.status)
            assertEquals(1.0, (viewModel.uiState.value.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)
        }
    }

    @Test
    fun `world book mode is session level and is not reverted by candidate switching`() = runTest {
        val directory = Files.createTempDirectory("tavern-chat-world-book-swipe").toFile()
        try {
            val compiler = PromptCompiler()
            var repositoryId = 0
            val conversations = ConversationRepository(
                directory,
                compiler,
                idFactory = { "world-book-repository-${repositoryId++}" },
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
            val character = DemoConversationContent.character.copy(
                firstMessage = "opening",
                alternateFirstMessages = emptyList(),
                worldBooks = listOf(
                    WorldBookDefinition(
                        id = "book",
                        entries = listOf(
                            WorldBookEntryDefinition(id = "entry", content = "constant lore", constant = true),
                        ),
                    ),
                ),
            )
            val created = conversations.create(character, DemoConversationContent.persona, DemoConversationContent.preset)
            // 候选各自携带自己的剧情派生运行状态；世界书意图不放在候选里。
            fun branch(name: String) = created.runtimeState.copy(localVariables = mapOf(name to MacroValue("visited")))
            val firstBranch = branch("first")
            val secondBranch = branch("second")
            val userTurn = ConversationTurn(
                id = "seed-user-turn",
                role = MessageRole.USER,
                variants = listOf(
                    MessageVariant(
                        id = "seed-user-variant",
                        message = ConversationMessage("seed-user", MessageRole.USER, "choose", "Traveler"),
                        runtimeStateBefore = firstBranch,
                        projectionRuntimeStateBefore = firstBranch,
                        runtimeStateAfter = firstBranch,
                    ),
                ),
            )
            val assistantTurn = ConversationTurn(
                id = "seed-assistant-turn",
                role = MessageRole.ASSISTANT,
                variants = listOf(
                    MessageVariant(
                        id = "first-variant",
                        message = ConversationMessage("first-message", MessageRole.ASSISTANT, "first", character.promptName),
                        runtimeStateBefore = firstBranch,
                        projectionRuntimeStateBefore = firstBranch,
                        runtimeStateAfter = firstBranch,
                    ),
                    MessageVariant(
                        id = "second-variant",
                        message = ConversationMessage("second-message", MessageRole.ASSISTANT, "second", character.promptName),
                        runtimeStateBefore = firstBranch,
                        projectionRuntimeStateBefore = firstBranch,
                        runtimeStateAfter = secondBranch,
                    ),
                ),
            )
            val seeded = conversations.save(
                created.copy(
                    turns = created.turns + userTurn + assistantTurn,
                    runtimeState = firstBranch,
                ),
            )
            val plans = mutableListOf<GenerationPlan>()
            val generator = FakeGenerator { _, plan ->
                plans += plan
                flow {
                    emit(GenerationEvent.TextDelta("response"))
                    emit(GenerationEvent.Finished("stop"))
                }
            }
            var messageId = 0
            val viewModel = ChatViewModel(
                repository = repository(),
                compiler = compiler,
                generator = generator,
                conversationRepository = conversations,
                presetSource = FixedPresetSource(),
                characterAsset = character,
                idGenerator = { "world-book-message-${messageId++}" },
                projectionDispatcher = mainDispatcherRule.dispatcher,
            )
            viewModel.loadConversation(seeded.id)

            // 玩家只停用本对话中的这一项，不随候选回退。
            viewModel.setWorldBookEntryMode("book", "entry", WorldBookEntryMode.DISABLED)
            assertEquals(WorldBookEntryMode.DISABLED, conversations.get(seeded.id)?.worldBookState?.playerOverrides?.get("book")?.get("entry")?.mode)

            viewModel.regenerate()
            viewModel.previousVariant()
            viewModel.previousVariant()
            viewModel.updateInput("continue from the other candidate")
            viewModel.send()

            // 切候选与再次生成都不回退会话级意图。
            assertEquals(2, plans.size)
            assertTrue(plans.all { plan -> plan.messages.none { "constant lore" in it.content } })
            assertEquals(
                WorldBookEntryMode.DISABLED,
                conversations.get(seeded.id)?.worldBookState?.playerOverrides?.get("book")?.get("entry")?.mode,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `history restart keeps the session level world book mode and restores its own checkpoint`() = runTest {
        val directory = Files.createTempDirectory("tavern-chat-world-book-history").toFile()
        try {
            val compiler = PromptCompiler()
            var repositoryId = 0
            val conversations = ConversationRepository(
                directory,
                compiler,
                idFactory = { "world-book-history-repository-${repositoryId++}" },
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
            val character = DemoConversationContent.character.copy(
                firstMessage = "opening",
                alternateFirstMessages = emptyList(),
                worldBooks = listOf(
                    WorldBookDefinition(
                        id = "book",
                        entries = listOf(
                            WorldBookEntryDefinition(id = "entry", content = "constant lore", constant = true),
                        ),
                    ),
                ),
            )
            val created = conversations.create(character, DemoConversationContent.persona, DemoConversationContent.preset)
            fun branch(name: String) = created.runtimeState.copy(localVariables = mapOf(name to MacroValue("visited")))
            val firstBranch = branch("first")
            val laterBranch = branch("later")
            val firstUser = ConversationTurn(
                id = "first-user-turn",
                role = MessageRole.USER,
                variants = listOf(
                    MessageVariant(
                        id = "first-user-variant",
                        message = ConversationMessage("first-user", MessageRole.USER, "first", "Traveler"),
                        runtimeStateBefore = firstBranch,
                        projectionRuntimeStateBefore = firstBranch,
                        runtimeStateAfter = firstBranch,
                    ),
                ),
            )
            val firstAssistant = ConversationTurn(
                id = "first-assistant-turn",
                role = MessageRole.ASSISTANT,
                variants = listOf(
                    MessageVariant(
                        id = "first-assistant-variant",
                        message = ConversationMessage("first-assistant", MessageRole.ASSISTANT, "first reply", character.promptName),
                        runtimeStateBefore = firstBranch,
                        projectionRuntimeStateBefore = firstBranch,
                        runtimeStateAfter = firstBranch,
                    ),
                ),
            )
            val laterUser = ConversationTurn(
                id = "later-user-turn",
                role = MessageRole.USER,
                variants = listOf(
                    MessageVariant(
                        id = "later-user-variant",
                        message = ConversationMessage("later-user", MessageRole.USER, "later", "Traveler"),
                        runtimeStateBefore = firstBranch,
                        projectionRuntimeStateBefore = firstBranch,
                        runtimeStateAfter = laterBranch,
                    ),
                ),
            )
            val laterAssistant = ConversationTurn(
                id = "later-assistant-turn",
                role = MessageRole.ASSISTANT,
                variants = listOf(
                    MessageVariant(
                        id = "later-assistant-variant",
                        message = ConversationMessage("later-assistant", MessageRole.ASSISTANT, "later reply", character.promptName),
                        runtimeStateBefore = laterBranch,
                        projectionRuntimeStateBefore = laterBranch,
                        runtimeStateAfter = laterBranch,
                    ),
                ),
            )
            val seeded = conversations.save(
                created.copy(
                    turns = created.turns + firstUser + firstAssistant + laterUser + laterAssistant,
                    runtimeState = laterBranch,
                ),
            )
            val plans = mutableListOf<GenerationPlan>()
            val generator = FakeGenerator { _, plan ->
                plans += plan
                flow {
                    emit(GenerationEvent.TextDelta("replacement reply"))
                    emit(GenerationEvent.Finished("stop"))
                }
            }
            var messageId = 0
            val viewModel = ChatViewModel(
                repository = repository(),
                compiler = compiler,
                generator = generator,
                conversationRepository = conversations,
                presetSource = FixedPresetSource(),
                characterAsset = character,
                idGenerator = { "world-book-history-message-${messageId++}" },
                projectionDispatcher = mainDispatcherRule.dispatcher,
            )
            viewModel.loadConversation(seeded.id)

            viewModel.setWorldBookEntryMode("book", "entry", WorldBookEntryMode.FORCED)
            viewModel.setWorldBookEntryContent("book", "entry", "revised lore")
            assertEquals(WorldBookEntryMode.FORCED, conversations.get(seeded.id)?.worldBookState?.playerOverrides?.get("book")?.get("entry")?.mode)

            viewModel.editMessage("first-user", "changed first", MessageEditMode.RESTART)

            assertEquals(3, viewModel.uiState.value.messages.size)
            assertEquals(listOf("entry"), plans.single().activatedWorldBookEntries)
            assertTrue(plans.single().messages.any { it.required && "revised lore" in it.content })
            val restarted = conversations.get(seeded.id)
            assertEquals("constant lore", restarted?.character?.worldBooks?.single()?.entries?.single()?.content)
            assertEquals(WorldBookEntryMode.FORCED, restarted?.worldBookState?.playerOverrides?.get("book")?.get("entry")?.mode)
            assertEquals("revised lore", restarted?.worldBookState?.playerOverrides?.get("book")?.get("entry")?.content)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `display message variables read each floor selected candidate checkpoint`() = runTest {
        val directory = Files.createTempDirectory("tavern-chat-message-variables").toFile()
        try {
            val compiler = PromptCompiler()
            var repositoryId = 0
            val conversations = ConversationRepository(
                directory,
                compiler,
                idFactory = { "message-variable-repository-${repositoryId++}" },
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
            val character = DemoConversationContent.character.copy(
                firstMessage = "opening",
                alternateFirstMessages = emptyList(),
            )
            val created = conversations.create(character, DemoConversationContent.persona, DemoConversationContent.preset)
            fun checkpoint(days: Int) = MvuStateSnapshot(
                bundleSha256 = "b".repeat(64),
                programSha256 = "c".repeat(64),
                data = JsonObject(mapOf("stat_data" to JsonObject(mapOf("days" to JsonPrimitive(days))))),
            )
            fun branch(days: Int) = created.runtimeState.copy(mvuState = checkpoint(days))
            fun variant(id: String, messageId: String, role: MessageRole, days: Int, content: String) = MessageVariant(
                id = id,
                message = ConversationMessage(messageId, role, content, character.promptName),
                runtimeStateBefore = branch(days),
                projectionRuntimeStateBefore = branch(days),
                runtimeStateAfter = branch(days),
            )
            fun turn(id: String, role: MessageRole, variants: List<MessageVariant>) = ConversationTurn(id, role, variants)
            val seeded = conversations.save(
                created.copy(
                    turns = listOf(
                        turn("opening-turn", MessageRole.ASSISTANT, listOf(
                            variant("opening-variant", "opening-message", MessageRole.ASSISTANT, 1, "开场"))),
                        turn("first-user-turn", MessageRole.USER, listOf(
                            variant("first-user-variant", "first-user-message", MessageRole.USER, 1, "继续"))),
                        turn("first-reply-turn", MessageRole.ASSISTANT, listOf(
                            variant("first-reply-variant", "first-reply-message", MessageRole.ASSISTANT, 2, "Day {{get_message_variable::stat_data.days}}"))),
                        turn("second-user-turn", MessageRole.USER, listOf(
                            variant("second-user-variant", "second-user-message", MessageRole.USER, 2, "继续"))),
                        turn("second-reply-turn", MessageRole.ASSISTANT, listOf(
                            variant("third-reply-variant", "third-reply-message", MessageRole.ASSISTANT, 3, "Day {{get_message_variable::stat_data.days}}"),
                            variant("fourth-reply-variant", "fourth-reply-message", MessageRole.ASSISTANT, 5, "Day {{format_message_variable::stat_data.days}}"))),
                    ),
                    // 会话当前 head 与任何候选都不同：广播最新检查点会立刻显示出差异。
                    runtimeState = branch(99),
                ),
            )
            var id = 0
            fun newViewModel() = ChatViewModel(
                repository = repository(),
                compiler = compiler,
                generator = FakeGenerator { _, _ -> error("No generation expected") },
                conversationRepository = conversations,
                presetSource = FixedPresetSource(),
                characterAsset = character,
                idGenerator = { "message-variable-${id++}" },
                projectionDispatcher = mainDispatcherRule.dispatcher,
            )
            suspend fun persisted() = requireNotNull(conversations.get(seeded.id))
            // 网页楼层 API 的读取路径：取选中候选自己的变量（含候选最新检查点）。
            suspend fun candidateDays(turnIndex: Int, variantIndex: Int): String {
                val selected = persisted().turns[turnIndex].variants[variantIndex]
                return ((BrowserConversation.variables(selected)["stat_data"] as JsonObject)["days"] as JsonPrimitive).content
            }
            suspend fun assertFloors(state: ChatUiState) {
                assertEquals("Day ${candidateDays(2, state.messages[2].variantIndex)}", state.messages[2].displayContent)
                assertEquals("Day ${candidateDays(4, state.messages[4].variantIndex)}", state.messages[4].displayContent)
                assertEquals("2", candidateDays(2, 0))
                assertEquals("3", candidateDays(4, 0))
            }

            val viewModel = newViewModel()
            viewModel.loadConversation(seeded.id)
            assertFloors(viewModel.uiState.value)

            // 重开对话后，同一份候选变量仍是各楼层自己的值。
            val reopened = newViewModel()
            reopened.loadConversation(seeded.id)
            assertFloors(reopened.uiState.value)

            // 切候选只改该楼层：历史楼层与另一候选继续读各自检查点。
            reopened.nextVariant()
            assertEquals("5", candidateDays(4, 1))
            assertFloors(reopened.uiState.value)
            reopened.previousVariant()
            assertFloors(reopened.uiState.value)

            // 文本编辑后，历史楼层的宏仍按各自的选中候选取值。
            reopened.editMessage("first-user-message", "继续（改）", MessageEditMode.TEXT_ONLY)
            assertFloors(reopened.uiState.value)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `display message variables treat explicitly empty candidate data as a real value`() = runTest {
        val directory = Files.createTempDirectory("tavern-chat-message-variables-empty").toFile()
        try {
            val compiler = PromptCompiler()
            var repositoryId = 0
            val conversations = ConversationRepository(
                directory,
                compiler,
                idFactory = { "message-variable-empty-repository-${repositoryId++}" },
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
            val character = DemoConversationContent.character.copy(
                firstMessage = "opening",
                alternateFirstMessages = emptyList(),
            )
            val created = conversations.create(character, DemoConversationContent.persona, DemoConversationContent.preset)
            val sessionRuntime = created.runtimeState.copy(mvuState = MvuStateSnapshot(
                "b".repeat(64), "c".repeat(64),
                JsonObject(mapOf("stat_data" to JsonObject(mapOf("days" to JsonPrimitive(99))))),
            ))
            // 作者新建的楼层显式保存空变量：即使候选带有运行状态，也不得回退到会话当前值。
            val emptyData = MessageVariant(
                id = "empty-data-variant",
                message = ConversationMessage("empty-data-message", MessageRole.ASSISTANT,
                    "Day {{get_message_variable::stat_data.days}}", character.promptName),
                runtimeStateBefore = sessionRuntime,
                projectionRuntimeStateBefore = sessionRuntime,
                runtimeStateAfter = sessionRuntime,
                browserVariables = JsonObject(emptyMap()),
                browserOwnVariables = true,
            )
            // 检查点存在但缺少 stat_data：同样按空变量处理。
            val missingStatData = MessageVariant(
                id = "missing-stat-data-variant",
                message = ConversationMessage("missing-stat-data-message", MessageRole.ASSISTANT,
                    "Day {{get_message_variable::stat_data.days}}", character.promptName),
                runtimeStateBefore = sessionRuntime,
                projectionRuntimeStateBefore = sessionRuntime,
                runtimeStateAfter = sessionRuntime.copy(mvuState = MvuStateSnapshot(
                    "b".repeat(64), "c".repeat(64), JsonObject(mapOf("schema" to JsonPrimitive("opaque"))))),
            )
            val seeded = conversations.save(created.copy(
                turns = listOf(
                    ConversationTurn("empty-data-turn", MessageRole.ASSISTANT, listOf(emptyData)),
                    ConversationTurn("missing-stat-data-turn", MessageRole.ASSISTANT, listOf(missingStatData)),
                ),
                runtimeState = sessionRuntime,
            ))
            var id = 0
            val viewModel = ChatViewModel(
                repository = repository(),
                compiler = compiler,
                generator = FakeGenerator { _, _ -> error("No generation expected") },
                conversationRepository = conversations,
                presetSource = FixedPresetSource(),
                characterAsset = character,
                idGenerator = { "message-variable-empty-${id++}" },
                projectionDispatcher = mainDispatcherRule.dispatcher,
            )
            viewModel.loadConversation(seeded.id)

            val state = viewModel.uiState.value
            assertNull(BrowserConversation.variables(emptyData)["stat_data"])
            assertNull(BrowserConversation.variables(missingStatData)["stat_data"])
            // 会话当前检查点是 99；显式空变量必须输出 null 而不是回退成 99。
            assertEquals("Day null", state.messages[0].displayContent)
            assertEquals("Day null", state.messages[1].displayContent)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `opening greetings are swipe variants but cannot be regenerated`() = runTest {
        val character = DemoConversationContent.character.copy(
            alternateFirstMessages = listOf("备用开场，{{user}}。"),
        )
        val viewModel = viewModel(FakeGenerator { _, _ -> flow { } }, character)

        assertEquals(2, viewModel.uiState.value.messages.single().variantCount)
        assertTrue(viewModel.uiState.value.variantNavigationAvailable)
        assertFalse(viewModel.uiState.value.regenerateAvailable)

        viewModel.nextVariant()

        assertEquals("备用开场，旅人。", viewModel.uiState.value.messages.single().message.content)
        assertEquals(1, viewModel.uiState.value.messages.single().variantIndex)
    }

    @Test
    fun `regenerate adds an assistant variant and switching it has no generation side effect`() = runTest {
        var response = 0
        val generator = FakeGenerator { _, _ ->
            flow {
                response += 1
                emit(GenerationEvent.TextDelta("回复$response"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("继续")
        viewModel.send()
        viewModel.regenerate()

        assertEquals(2, generator.calls)
        assertEquals(2, viewModel.uiState.value.messages.last().variantCount)
        assertEquals("回复2", viewModel.uiState.value.messages.last().message.content)

        viewModel.previousVariant()

        assertEquals(2, generator.calls)
        assertEquals("回复1", viewModel.uiState.value.messages.last().message.content)
    }

    @Test
    fun `regenerate replays from turn runtime and swipe restores each cached candidate state`() = runTest {
        val generator = FakeGenerator { _, _ ->
            flow {
                emit(GenerationEvent.TextDelta("{{incvar::answer}}"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("继续")
        viewModel.send()
        assertEquals("1", viewModel.uiState.value.messages.last().message.content)

        viewModel.regenerate()
        assertEquals("1", viewModel.uiState.value.messages.last().message.content)

        viewModel.previousVariant()
        viewModel.regenerate()
        assertEquals("1", viewModel.uiState.value.messages.last().message.content)
        assertEquals(3, generator.calls)
    }

    @Test
    fun `editing a user message truncates the future restores runtime and regenerates`() = runTest {
        var response = 0
        val generator = FakeGenerator { _, _ ->
            flow {
                response += 1
                val text = when (response) {
                    1 -> "{{setvar::answer::old}}one\n<UpdateVariable>\n_.set('世界.日期', 1, 2);\n</UpdateVariable>"
                    2 -> "later\n<UpdateVariable>\n_.set('世界.日期', 2, 3);\n</UpdateVariable>"
                    else -> "{{getvar::route}}/{{getvar::answer}}"
                }
                emit(GenerationEvent.TextDelta(text))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator, DemoConversationContent.character.copy(nativeAdaptation = dayAdaptation()))

        viewModel.updateInput("{{setvar::route::old}}first")
        viewModel.send()
        val firstUserId = viewModel.uiState.value.messages[1].message.id
        viewModel.updateInput("{{setvar::route::later}}second")
        viewModel.send()
        assertEquals(3.0, (viewModel.uiState.value.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)

        viewModel.editMessage(firstUserId, "{{setvar::route::new}}changed", MessageEditMode.RESTART)

        val state = viewModel.uiState.value
        assertEquals(3, state.messages.size)
        assertEquals("changed", state.messages[1].message.content)
        assertEquals("{{setvar::route::new}}changed", state.messages[1].message.sourceText)
        assertTrue(state.messages[1].edited)
        assertEquals("new/", state.messages.last().message.content)
        assertEquals(1.0, (state.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)
        assertEquals(3, generator.calls)
    }

    @Test
    fun `text-only edit preserves the future and current runtime state`() = runTest {
        var response = 0
        val generator = FakeGenerator { _, _ ->
            flow {
                response += 1
                val text = when (response) {
                    1 -> "first answer"
                    2 -> "later answer"
                    else -> "{{getvar::route}}"
                }
                emit(GenerationEvent.TextDelta(text))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("{{setvar::route::old}}first")
        viewModel.send()
        val firstUserId = viewModel.uiState.value.messages[1].message.id
        viewModel.updateInput("{{setvar::route::later}}second")
        viewModel.send()
        val oldFutureIds = viewModel.uiState.value.messages.drop(2).map { it.message.id }

        viewModel.editMessage(firstUserId, "{{setvar::route::corrected}}changed", MessageEditMode.TEXT_ONLY)

        val edited = viewModel.uiState.value
        assertEquals(5, edited.messages.size)
        assertEquals("changed", edited.messages[1].message.content)
        assertTrue(edited.messages[1].edited)
        assertEquals(oldFutureIds, edited.messages.drop(2).map { it.message.id })

        viewModel.updateInput("check")
        viewModel.send()
        assertEquals("later", viewModel.uiState.value.messages.last().message.content)
    }

    @Test
    fun `editing an assistant message makes it the new fact and restores its projected state`() = runTest {
        var response = 0
        val generator = FakeGenerator { _, _ ->
            flow {
                response += 1
                val text = when (response) {
                    1 -> "{{setvar::mood::old}}original"
                    2 -> "later"
                    else -> "{{getvar::mood}}"
                }
                emit(GenerationEvent.TextDelta(text))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("first")
        viewModel.send()
        val firstAssistantId = viewModel.uiState.value.messages[2].message.id
        viewModel.updateInput("second")
        viewModel.send()

        viewModel.editMessage(firstAssistantId, "{{setvar::mood::manual}}修正", MessageEditMode.RESTART)

        val edited = viewModel.uiState.value
        assertEquals(3, edited.messages.size)
        assertEquals("修正", edited.messages.last().message.content)
        assertEquals("{{setvar::mood::manual}}修正", edited.messages.last().message.sourceText)
        assertTrue(edited.messages.last().edited)
        assertTrue(edited.messages.last().message.reasoning.isEmpty())
        assertEquals(null, edited.lastTrace)

        viewModel.updateInput("continue")
        viewModel.send()
        assertEquals("manual", viewModel.uiState.value.messages.last().message.content)
    }

    @Test
    fun `reasoning preserves raw storage and uses a safe display projection`() = runTest {
        val character = DemoConversationContent.character.copy(
            regexScripts = listOf(
                RegexDefinition(
                    id = "reasoning-storage",
                    name = "Reasoning storage",
                    findRegex = "secret",
                    replaceString = "stored",
                    placements = setOf(RegexPlacement.REASONING),
                ),
                RegexDefinition(
                    id = "reasoning-display",
                    name = "Reasoning display",
                    findRegex = "stored",
                    replaceString = "shown",
                    placements = setOf(RegexPlacement.REASONING),
                    markdownOnly = true,
                ),
            ),
        )
        val generator = FakeGenerator { _, _ ->
            flow {
                emit(GenerationEvent.ReasoningDelta("secret"))
                emit(GenerationEvent.ReasoningSignature("opaque"))
                emit(GenerationEvent.TextDelta("正文"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator, character)

        viewModel.updateInput("开始")
        viewModel.send()

        val assistant = viewModel.uiState.value.messages.last()
        assertEquals("secret", assistant.message.reasoning.single().text)
        assertEquals("opaque", assistant.message.reasoning.single().signature)
        assertEquals(listOf("shown"), assistant.displayReasoning)
    }

    @Test
    fun `reasoning-only empty response does not commit assistant output mutations`() = runTest {
        val character = DemoConversationContent.character.copy(
            regexScripts = listOf(
                RegexDefinition(
                    id = "reasoning-state",
                    name = "Reasoning state",
                    findRegex = "thought",
                    replaceString = "{{setvar::reasoning-side-effect::yes}}thought",
                    placements = setOf(RegexPlacement.REASONING),
                ),
            ),
        )
        var attempt = 0
        val generator = FakeGenerator { _, _ ->
            flow {
                attempt += 1
                if (attempt == 1) {
                    emit(GenerationEvent.ReasoningDelta("thought"))
                } else {
                    emit(GenerationEvent.TextDelta("{{getvar::reasoning-side-effect}}"))
                }
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator, character)

        viewModel.updateInput("开始")
        viewModel.send()

        assertTrue(viewModel.uiState.value.retryAvailable)
        viewModel.retry()

        assertEquals(2, generator.calls)
        assertTrue(viewModel.uiState.value.retryAvailable)
        assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER), viewModel.uiState.value.messages.map { it.message.role })
    }

    @Test
    fun `prompt runtime mutations are not committed when request preparation never succeeds`() = runTest {
        val directory = Files.createTempDirectory("tavern-chat-runtime").toFile()
        try {
            val compiler = PromptCompiler()
            var repositoryId = 0
            val conversations = ConversationRepository(
                directory,
                compiler,
                idFactory = { "repository-${repositoryId++}" },
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
            val character = DemoConversationContent.character.copy(
                description = "{{setvar::planned::yes}}${DemoConversationContent.character.description}",
            )
            val created = conversations.create(character, DemoConversationContent.persona, DemoConversationContent.preset)
            var messageId = 0
            val viewModel = ChatViewModel(
                repository = repository(),
                compiler = compiler,
                generator = FakeGenerator { _, _ -> flow { throw IOException("before request") } },
                conversationRepository = conversations,
                presetSource = FixedPresetSource(),
                characterAsset = character,
                idGenerator = { "runtime-${messageId++}" },
                projectionDispatcher = mainDispatcherRule.dispatcher,
            )
            viewModel.loadConversation(created.id)

            viewModel.updateInput("开始")
            viewModel.send()

            val persisted = conversations.get(created.id)!!
            val userVariant = persisted.turns.last().selected
            assertEquals(null, persisted.runtimeState.localVariables["planned"])
            assertEquals("开始", userVariant.message.sourceText)
            assertNotNull(userVariant.runtimeStateBefore)
            assertNotNull(userVariant.runtimeStateAfter)
            assertTrue(viewModel.uiState.value.toString(), viewModel.uiState.value.retryAvailable)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `failure before text keeps user and retry does not duplicate it`() = runTest {
        var fail = true
        val generator = FakeGenerator { connection, _ ->
            flow {
                if (fail) throw GatewayException.Network(IOException("offline"))
                emit(GenerationEvent.RequestPrepared(preview(connection)))
                emit(GenerationEvent.TextDelta("现在可以了。"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("你好")
        viewModel.send()

        assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER), viewModel.uiState.value.messages.map { it.message.role })
        assertTrue(viewModel.uiState.value.retryAvailable)

        fail = false
        viewModel.retry()

        val state = viewModel.uiState.value
        assertEquals(1, state.messages.count { it.message.role == MessageRole.USER })
        assertEquals("现在可以了。", state.messages.last().message.content)
        assertFalse(state.retryAvailable)
    }

    @Test
    fun `cancellation keeps partial assistant and reset clears trace`() = runTest {
        val generator = FakeGenerator { connection, _ ->
            flow {
                emit(GenerationEvent.RequestPrepared(preview(connection)))
                emit(GenerationEvent.TextDelta("半句回复"))
                awaitCancellation()
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("开始")
        viewModel.send()
        assertTrue(viewModel.uiState.value.running)

        viewModel.cancel()

        assertFalse(viewModel.uiState.value.running)
        assertEquals(ChatMessageStatus.CANCELLED, viewModel.uiState.value.messages.last().status)
        assertEquals("半句回复", viewModel.uiState.value.messages.last().message.content)
        assertNotNull(viewModel.uiState.value.lastTrace)

        viewModel.resetConversation()
        assertEquals(1, viewModel.uiState.value.messages.size)
        assertEquals(null, viewModel.uiState.value.lastTrace)
    }

    @Test
    fun `global variable macro remains literal and is never downgraded to local state`() = runTest {
        val generator = FakeGenerator { connection, _ ->
            flow {
                emit(GenerationEvent.RequestPrepared(preview(connection)))
                emit(GenerationEvent.TextDelta("收到。"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("{{getglobalvar::mood}}")
        viewModel.send()

        assertEquals("", viewModel.uiState.value.input)
        assertEquals("{{getglobalvar::mood}}", viewModel.uiState.value.messages[1].message.content)
        assertTrue(viewModel.uiState.value.lastTrace?.compileDiagnostics.orEmpty().any { it.code == "UNSUPPORTED_MACRO" })
        assertEquals(1, generator.calls)
    }

    @Test
    fun `generation captures one preset while active changes affect display and the next request`() = runTest {
        val presetA = DemoConversationContent.preset.copy(
            id = "preset-a",
            name = "Preset A",
            contentSha256 = "fingerprint-a",
            builtIn = false,
            generationSettings = DemoConversationContent.preset.generationSettings.copy(maxOutputTokens = 111),
        )
        val presetB = presetA.copy(
            id = "preset-b",
            name = "Preset B",
            contentSha256 = "fingerprint-b",
            generationSettings = presetA.generationSettings.copy(maxOutputTokens = 222),
            regexScripts = listOf(
                RegexDefinition(
                    id = "display-b",
                    name = "Display B",
                    findRegex = "alpha",
                    replaceString = "beta",
                    placements = setOf(RegexPlacement.AI_OUTPUT),
                    markdownOnly = true,
                ),
            ),
        )
        val presetSource = FixedPresetSource(presetA)
        val releaseFirst = CompletableDeferred<Unit>()
        val plans = mutableListOf<GenerationPlan>()
        val generator = FakeGenerator { _, plan ->
            plans += plan
            flow {
                if (plans.size == 1) releaseFirst.await()
                emit(GenerationEvent.TextDelta("alpha"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        var id = 0
        val viewModel = ChatViewModel(
            repository = repository(),
            compiler = PromptCompiler(),
            generator = generator,
            presetSource = presetSource,
            idGenerator = { "capture-${id++}" },
            projectionDispatcher = mainDispatcherRule.dispatcher,
        )

        viewModel.updateInput("first")
        viewModel.send()
        assertTrue(viewModel.uiState.value.running)
        assertEquals("preset-a", plans.single().presetId)
        assertEquals(111, plans.single().maxOutputTokens)

        presetSource.set(presetB)
        assertEquals("preset-b", viewModel.uiState.value.activePresetId)
        assertEquals("preset-a", plans.single().presetId)
        releaseFirst.complete(Unit)

        val firstAssistant = viewModel.uiState.value.messages.last()
        assertEquals("alpha", firstAssistant.message.content)
        assertEquals("beta", firstAssistant.displayContent)
        assertEquals("preset-a", firstAssistant.metadata?.presetId)
        assertEquals("fingerprint-a", firstAssistant.metadata?.presetContentSha256)

        viewModel.updateInput("second")
        viewModel.send()
        assertEquals(2, plans.size)
        assertEquals("preset-b", plans.last().presetId)
        assertEquals(222, plans.last().maxOutputTokens)
        assertEquals("preset-b", viewModel.uiState.value.messages.last().metadata?.presetId)
    }

    @Test
    fun `unknown context accepts provider counts over two million without reclipping`() = runTest {
        val preset = DemoConversationContent.preset.copy(
            generationSettings = DemoConversationContent.preset.generationSettings.copy(maxContextTokens = null),
        )
        var validations = 0
        var captured: GenerationPlan? = null
        val generator = object : ConversationGenerator {
            override suspend fun validateTokens(connection: StoredConnection, plan: GenerationPlan): ProviderTokenValidation {
                validations++
                return ProviderTokenValidation(2_100_000, TokenCountQuality.EXACT, "test")
            }
            override fun stream(connection: StoredConnection, plan: GenerationPlan) = flow<GenerationEvent> {
                captured = plan
                emit(GenerationEvent.TextDelta("完成"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val vm = ChatViewModel(repository(), PromptCompiler(), generator,
            presetSource = FixedPresetSource(preset), projectionDispatcher = mainDispatcherRule.dispatcher)
        vm.updateInput("开始")
        vm.send()
        assertEquals(1, validations)
        val plan = requireNotNull(captured)
        assertNull(plan.declaredContextTokens)
        assertEquals(2_100_000, plan.tokenAccounting?.inputTokens)
        assertFalse(plan.trace.any { it.stage == "context-budget" })
        assertEquals("完成", vm.uiState.value.messages.last().message.content)
    }

    @Test
    fun `generation uses selected model token limit overrides`() = runTest {
        val connection = connection().copy(
            modelTokenLimitOverrides = mapOf(
                "model" to ModelTokenLimits(contextTokens = 128_000, outputTokens = 32_000),
            ),
        )
        val preset = DemoConversationContent.preset.copy(
            generationSettings = DemoConversationContent.preset.generationSettings.copy(
                maxContextTokens = 2_000_000,
                maxOutputTokens = 65_535,
            ),
        )
        var captured: GenerationPlan? = null
        val generator = FakeGenerator { _, plan ->
            captured = plan
            flow {
                emit(GenerationEvent.TextDelta("完成"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = ChatViewModel(
            repository = repository(connection),
            compiler = PromptCompiler(),
            generator = generator,
            presetSource = FixedPresetSource(preset),
            projectionDispatcher = mainDispatcherRule.dispatcher,
        )

        viewModel.updateInput("开始")
        viewModel.send()

        assertEquals(128_000, captured?.tokenAccounting?.contextLimit)
        assertEquals(32_000, captured?.maxOutputTokens)
        assertTrue(captured?.diagnostics.orEmpty().none { it.code.endsWith("_FALLBACK") })
    }

    private fun viewModel(
        generator: FakeGenerator,
        character: CharacterAsset = DemoConversationContent.character,
    ): ChatViewModel {
        var id = 0
        return ChatViewModel(
            repository = repository(),
            compiler = PromptCompiler(),
            generator = generator,
            presetSource = FixedPresetSource(),
            characterAsset = character,
            idGenerator = { "message-${id++}" },
            projectionDispatcher = mainDispatcherRule.dispatcher,
        )
    }

    private fun dayAdaptation() = NativeAdaptation(
        sourceSha256 = "a".repeat(64),
        state = listOf(ConversationStateDefinition("world-day", type = ConversationStateValueType.NUMBER, initialValue = JsonPrimitive(1))),
        assistantStateAdapters = listOf(
            AssistantStateAdapterDefinition(
                LegacyStateDialect.UPDATE_VARIABLE_SET_V1,
                listOf(AssistantStateMapping("世界.日期", "world-day")),
            ),
        ),
    )

    private class FixedPresetSource(initial: PresetAsset = DemoConversationContent.preset) : ActivePresetSource {
        private val state = MutableStateFlow(initial)
        override val activePreset = state

        fun set(preset: PresetAsset) {
            state.value = preset
        }
    }

    private fun repository(connection: StoredConnection = connection()): ConnectionRepository {
        return ConnectionRepository(
            stateStore = FakeStateStore(GatewayAppState(connections = listOf(connection), recentConnectionId = connection.id)),
            credentialStore = FakeCredentialStore(),
            catalogLoader = { ModelCatalog(emptyList(), truncated = false) },
        )
    }

    private fun connection() = StoredConnection(
        id = "connection",
        name = "Test",
        templateId = "test",
        protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
        apiAddress = "https://example.com/v1",
        streamEndpoint = "https://example.com/v1/chat/completions",
        catalogEndpoint = null,
        authScheme = AuthScheme.NONE,
        credentialRef = null,
        credentialMask = null,
        approvedOrigins = emptySet(),
        selectedModel = "model",
        modelCache = ModelCache(),
    )

    private fun preview(connection: StoredConnection) = ProviderRequestPreview(
        protocol = connection.protocol,
        model = connection.selectedModel,
        systemInstruction = null,
        messages = listOf(ProviderPreviewMessage("user", "hello")),
        maxOutputTokens = 512,
        store = false,
        usesHostedState = false,
        assistantPrefillApplied = false,
    )
}

private fun GenerationPlan.projectedConversationState(): String =
    messages.single { it.origin.sourceIds == listOf("conversationState") }.content

private class FakeGenerator(
    private val block: (StoredConnection, GenerationPlan) -> Flow<GenerationEvent>,
) : ConversationGenerator {
    var calls: Int = 0
        private set

    override fun stream(connection: StoredConnection, plan: GenerationPlan): Flow<GenerationEvent> {
        calls += 1
        return block(connection, plan)
    }
}

private class FakeStateStore(initial: GatewayAppState) : ConnectionStateStore {
    private val mutable = MutableStateFlow(initial)
    override val state: Flow<GatewayAppState> = mutable

    override suspend fun update(transform: (GatewayAppState) -> GatewayAppState) {
        mutable.value = transform(mutable.value)
    }
}

private class FakeCredentialStore : CredentialStore {
    override suspend fun put(credentialId: String, secret: String) = Unit
    override suspend fun getOrNull(credentialId: String): String? = null
    override suspend fun delete(credentialId: String) = Unit
    override suspend fun contains(credentialId: String): Boolean = false
}
