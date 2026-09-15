package io.github.zvensmoluya.tavernplayer.conversation.web

import io.github.zvensmoluya.tavernplayer.conversation.blockingGet

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zvensmoluya.modelgateway.*
import io.github.zvensmoluya.modelgateway.catalog.ModelCatalog
import io.github.zvensmoluya.tavernplayer.app.AppGraph
import io.github.zvensmoluya.tavernplayer.characters.CharacterSaveResult
import io.github.zvensmoluya.tavernplayer.connections.*
import io.github.zvensmoluya.tavernplayer.conversation.*
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationParameter
import io.github.zvensmoluya.tavernplayer.presets.*
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in only. Never serialize a request, provider exception, or credential. */
@RunWith(AndroidJUnit4::class)
class BrowserLiveAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun browserLiveConversationAndRecovery() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("browserLive") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val phase = args.getString("browserLivePhase") ?: "conversation"
        require(phase in setOf("conversation", "recover", "opening"))
        val recovering = phase == "recover"
        val openingOnly = phase == "opening"
        val sample = args.getString("browserSample") ?: "C-02"
        val asset = mapOf("C-01" to "pressure-card.png", "C-02" to "second-pressure-card.png", "C-03" to "doctor-card.png").getValue(sample)
        val turnLimit = (args.getString("browserLiveTurns") ?: "2").toInt().also { require(it in 1..2) }
        val sampleBytes = instrumentation.context.assets.open(asset).use { it.readBytes() }
        val presetBytes = instrumentation.context.assets.open("community-preset.json").use { it.readBytes() }
        val identity = buildJsonObject {
            put("sample", sample); put("sampleSha256", hash(sampleBytes))
            put("preset", "P-01"); put("presetSha256", hash(presetBytes)); put("turnLimit", turnLimit)
        }
        val output = File(context.filesDir, "browser-live-verification/${System.currentTimeMillis()}").apply { mkdirs() }
        val markerFile = File(context.filesDir, "browser-live-verification/marker.json")
        val credentials = MemoryCredentials()
        val connections = ConnectionRepository(MemoryConnections(), credentials, { ModelCatalog(emptyList(), false) })
        val requests = AtomicInteger()
        val models = mutableListOf<ViewModel>()
        var mounted by mutableStateOf<ChatViewModel?>(null)
        var stage = "setup"
        var graph: AppGraph? = null
        var previousPreset: String? = null
        var declaredOutputTokens: Int? = null
        val ownedPresets = mutableListOf<String>()
        fun report(name: String, data: JsonObject) = File(output, "$name.json").writeText(data.toString())
        fun progress(value: String) {
            stage = value
            report("progress", buildJsonObject { put("stage", value); put("requests", requests.get()) })
        }
        try {
            if (!recovering && !openingOnly) {
                val file = File(context.cacheDir, "browser-live.env")
                val source = try { file.readText() } finally { check(!file.exists() || file.delete()) { "BL_CONFIG_DELETE" } }
                val config = source.lineSequence().map(String::trim).filter { it.isNotBlank() && !it.startsWith("#") }
                    .associate { line ->
                        val pair = line.removePrefix("export ").split('=', limit = 2)
                        check(pair.size == 2) { "BL_CONFIG_FORMAT" }
                        pair[0].trim() to pair[1].trim().removeSurrounding("\"").removeSurrounding("'")
                    }
                fun value(name: String) = config[name]?.takeIf { it.isNotBlank() } ?: error("BL_CONFIG_MISSING")
                val protocol = when (value("TAVERN_TEST_PROTOCOL").uppercase().replace('-', '_')) {
                    "OPENAI", "OPENAI_COMPATIBLE", "OPENAI_CHAT_COMPLETIONS" -> ModelProtocol.OPENAI_CHAT_COMPLETIONS
                    else -> ModelProtocol.valueOf(value("TAVERN_TEST_PROTOCOL").uppercase())
                }
                connections.save(ConnectionDraft("browser-live", "Browser verification", "test", protocol,
                    value("TAVERN_TEST_BASE_URL"), value("TAVERN_TEST_MODEL")), value("TAVERN_TEST_API_KEY"), false)
            }
            val app = AppGraph(context)
            graph = app
            previousPreset = app.presetRepository.library.value.activePresetId
            val raw = ModelGatewayConversationGenerator(ModelGateway(credentials, PlayerModelHttpClient.create()), connections)
            val generator = object : ConversationGenerator {
                override suspend fun validateTokens(connection: StoredConnection, plan: GenerationPlan): ProviderTokenValidation? {
                    check(!recovering && !openingOnly) { "BL_REQUEST_FORBIDDEN" }
                    return raw.validateTokens(connection, plan)
                }
                override fun stream(connection: StoredConnection, plan: GenerationPlan) = flow {
                    check(!recovering && !openingOnly) { "BL_REQUEST_FORBIDDEN" }
                    val request = requests.incrementAndGet()
                    check(request <= turnLimit) { "BL_REQUEST_LIMIT" }
                    declaredOutputTokens = declaredOutputTokens ?: plan.maxOutputTokens
                    var chars = 0
                    var nonBlank = false
                    val lengths = mutableListOf<Int>()
                    val reasons = mutableListOf<String>()
                    val accepted = setOf("completed", "stop", "end_turn", "STOP", "stop_sequence")
                    try {
                        raw.stream(connection, plan).collect { event ->
                            when (event) {
                                is GenerationEvent.TextDelta -> {
                                    chars += event.text.length; lengths.add(event.text.length)
                                    nonBlank = nonBlank || event.text.isNotBlank()
                                }
                                is GenerationEvent.Finished -> reasons.add(
                                    event.reason.takeIf { it in accepted || it in setOf("length", "max_tokens", "MAX_TOKENS") } ?: "other")
                                else -> Unit
                            }
                            emit(event)
                        }
                        check(nonBlank && reasons.isNotEmpty() && reasons.all { it in accepted }) { "BL_MODEL_FINISH" }
                    } finally {
                        report("request-$request", buildJsonObject {
                            put("request", request); put("textDeltaChars", chars); put("nonBlank", nonBlank)
                            putJsonArray("textDeltaLengths") { lengths.forEach { add(it) } }
                            putJsonArray("finishedReasons") { reasons.forEach { add(it) } }
                            put("acceptedFinish", reasons.isNotEmpty() && reasons.all { it in accepted })
                        })
                    }
                }
            }
            fun freshRepository() = ConversationRepository(context.filesDir, app.promptCompiler,
                mvuRuntime = app.mvuRuntime, prepareBrowser = app.browserEnvironment::prepare)
            suspend fun viewModel(repository: ConversationRepository, id: String): ChatViewModel {
                val vm = withContext(Dispatchers.Main) {
                    ChatViewModel(connections, app.promptCompiler, StateConfirmingConversationGenerator(generator),
                        repository, app.presetRepository, mvuRuntime = app.mvuRuntime,
                        ejsRuntime = app.ejsRuntime, browserEnvironment = app.browserEnvironment).also {
                        models.add(it)
                        it.loadConversation(id)
                    }
                }
                withTimeout(60_000) { vm.uiState.first { it.conversationId == id && !it.busy && !it.loadingConnections } }
                return vm
            }
            val presetVm = withContext(Dispatchers.Main) { PresetViewModel(app.presetRepository).also { models.add(it) } }
            compose.setContent {
                mounted?.let { vm -> key(vm) {
                    TavernPlayerTheme { ChatRoute(vm, presetVm, {}, {}, {}, { characterId, assetId ->
                        app.characterRepository.assetFile(characterId, assetId)?.absolutePath
                    }) }
                } }
            }
            val repository = app.conversationRepository
            val id: String
            val recoveryMarker = if (recovering) Json.parseToJsonElement(markerFile.readText()).jsonObject.also {
                check(it["identity"] == identity) { "BL_RECOVERY_PARAMETERS" }
            } else null
            progress("import-P01")
            val name = "Browser verification ${java.util.UUID.randomUUID()}"
            val source = Json.parseToJsonElement(presetBytes.decodeToString()).jsonObject
            val imported = app.presetRepository.importPreset(JsonObject(source + ("name" to JsonPrimitive(name)))
                .toString().toByteArray(), "$name.json") as PresetLibraryImportResult.Saved
            val preset = if (imported.duplicate) app.presetRepository.saveAs(imported.preset, name) else imported.preset
            ownedPresets.add(preset.id)
            app.presetRepository.activate(preset.id)
            if (recovering) {
                progress("recover-read")
                val expected = checkNotNull(recoveryMarker)
                check(expected.getValue("pid").jsonPrimitive.int != android.os.Process.myPid()) { "BL_SAME_PROCESS" }
                id = expected.getValue("conversationId").jsonPrimitive.content
                val restored = checkNotNull(freshRepository().blockingGet(id))
                verifyMarker(restored, expected)
            } else {
                progress("import-$sample-P01")
                val character = app.characterRepository.import(
                    sampleBytes, "sample-$sample.png") as CharacterSaveResult.Saved
                check(character.character.nativeAdaptation == null) { "BL_NATIVE_ADAPTATION" }
                id = repository.create(character.character, Persona("browser-live", "Visitor"), preset,
                    ConversationExecutionMode.BROWSER).id
            }
            var vm = viewModel(if (recovering) freshRepository() else repository, id)
            compose.runOnUiThread { mounted = vm }
            progress("opening-$sample")
            waitForPage()
            inspectPage(output, "opening")
            if (openingOnly) {
                check(requests.get() == 0) { "BL_REQUEST_COUNT" }
                report("result", buildJsonObject {
                    put("passed", true); put("phase", phase); put("identity", identity)
                    put("requests", 0); put("completedTurns", 0); put("openingOnly", true)
                    put("processRecovery", false); put("conversationTurnsVerified", false)
                })
                return@runBlocking
            }
            var completedTurns = 0
            if (!recovering) {
                val inputs = listOf(
                    "I set a cup of tea on the table and ask about today's ordinary plans. Continue a calm everyday scene.",
                    "I take a short walk nearby, then return to discuss what to prepare for dinner. Continue the everyday scene.")
                inputs.take(turnLimit).forEachIndexed { index, input ->
                    progress("turn-${index + 1}")
                    val before = vm.uiState.value.messages.map { it.message.id }.toSet()
                    evaluate("window.__browserLiveOldRows = new WeakSet(document.querySelectorAll('#messages > article')); true")
                    compose.onNodeWithTag("chatInput").performTextInput(input)
                    compose.onNodeWithTag("sendMessage").performClick()
                    withTimeout(480_000) { vm.uiState.first { state ->
                        !state.busy && (state.messages.any { it.message.id !in before && it.message.role == MessageRole.ASSISTANT } || state.lastTrace?.error != null)
                    } }
                    val completed = vm.uiState.value.messages.last()
                    check(completed.message.id !in before && completed.message.role == MessageRole.ASSISTANT &&
                        completed.status == ChatMessageStatus.COMPLETE && completed.message.content.isNotBlank()) { "BL_ASSISTANT_INCOMPLETE" }
                    withTimeout(20_000) {
                        while (repository.get(id)!!.turns.none { it.selected.message.id == completed.message.id &&
                            it.selected.status == PersistedMessageStatus.COMPLETE && it.selected.message.content == completed.message.content }) kotlinx.coroutines.delay(10)
                    }
                    val position = vm.uiState.value.messages.indexOfFirst { it.message.id == completed.message.id }
                    val domHash = waitForAssistantDom(position, vm.uiState.value.messages.size)
                    inspectPage(output, "turn-${index + 1}")
                    report("turn-${index + 1}", buildJsonObject {
                        put("turn", index + 1); put("assistantChars", completed.message.content.length)
                        put("assistantDomHash", domHash); put("assistantDomPosition", position); put("newAssistantRendered", true)
                        put("assistantHash", hash(completed.message.content)); put("persisted", true)
                        put("requests", requests.get()); declaredOutputTokens?.let { put("presetMaxOutputTokens", it) }
                    })
                    completedTurns++
                }
                check(requests.get() == turnLimit) { "BL_REQUEST_COUNT" }
            }
            progress("fresh-reopen")
            compose.runOnUiThread { mounted = null }
            compose.waitForIdle()
            withContext(Dispatchers.Main) { clear(vm) }
            val saved = checkNotNull(freshRepository().blockingGet(id))
            check(saved.executionMode == ConversationExecutionMode.BROWSER && saved.character.nativeAdaptation == null) { "BL_MODE" }
            val expected = marker(saved, identity)
            verifyMarker(checkNotNull(freshRepository().blockingGet(id)), expected)
            vm = viewModel(freshRepository(), id)
            check(vm.uiState.value.messages.map { it.message.id } == saved.selectedMessages().map { it.id }) { "BL_UI_RECOVERY_IDS" }
            check(vm.uiState.value.messages.map { hash(it.message.content) } == saved.selectedMessages().map { hash(it.content) }) { "BL_UI_RECOVERY_CONTENT" }
            compose.runOnUiThread { mounted = vm }
            waitForPage()
            inspectPage(output, "reopened")
            compose.runOnUiThread { mounted = null }
            compose.waitForIdle()
            withContext(Dispatchers.Main) { clear(vm) }
            markerFile.writeText(marker(checkNotNull(freshRepository().blockingGet(id)), identity).toString())
            report("result", buildJsonObject {
                put("passed", true); put("sample", sample); put("preset", "P-01")
                put("phase", phase); put("identity", identity); put("completedTurns", completedTurns)
                put("conversationTurnsVerified", !recovering && completedTurns == turnLimit)
                put("recoverOnly", recovering); put("requests", requests.get()); put("executionMode", "BROWSER")
                put("nativeAdaptation", false); declaredOutputTokens?.let { put("presetMaxOutputTokens", it) }
                put("freshRepositoryAndViewModel", true); put("processRecovery", recovering)
            })
        } catch (failure: Throwable) {
            report("result", buildJsonObject {
                put("passed", false); put("stage", stage); put("requests", requests.get())
                put("exceptionClass", failure.javaClass.simpleName)
                failure.message?.takeIf { it.matches(Regex("BL_[A-Z0-9_]+")) }?.let { put("assertion", it) }
                putJsonArray("stack") { failure.stackTrace.take(8).forEach { add("${it.className}.${it.methodName}:${it.lineNumber}") } }
            })
            // Do not attach the original exception: provider bodies and UI assertion dumps can contain source text.
            throw AssertionError("Browser live verification failed at $stage; inspect private anonymous metrics")
        } finally {
            withContext(NonCancellable) {
                runCatching { compose.runOnUiThread { mounted = null }; compose.waitForIdle() }
                withContext(Dispatchers.Main) { models.forEach { runCatching { clear(it) } } }
                credentials.clear()
                // Preserve all existing characters/conversations, including duplicate imports.
                // Test conversations are deliberately retained for inspection and process recovery.
                val cleanup = runCatching {
                    graph?.let { app ->
                        previousPreset?.let { app.presetRepository.activate(it) }
                        ownedPresets.forEach { app.presetRepository.delete(it) }
                    }
                }.isSuccess
                report("cleanup", buildJsonObject { put("credentialsCleared", true); put("presetRestoredAndRemoved", cleanup) })
                check(cleanup) { "BL_CLEANUP_FAILED" }
            }
        }
    }

    private fun waitForPage() {
        compose.waitUntil(60_000) { webViews().any { it.isShown && it.width > 0 && it.height > 0 } }
        compose.waitUntil(60_000) { evaluate("document.readyState === 'complete' && document.querySelectorAll('article').length > 0") == "true" }
        compose.waitUntil(60_000) {
            evaluate("Array.from(document.querySelectorAll('article iframe')).some(f => { const r=f.getBoundingClientRect(); return r.width>0 && r.height>0; })") == "true"
        }
        Thread.sleep(1_000)
        compose.onNodeWithTag("webChatContent").assertIsDisplayed()
    }

    private fun inspectPage(output: File, name: String) {
        compose.waitForIdle()
        val notice = compose.onAllNodesWithTag("webRuntimeNotice").fetchSemanticsNodes().isNotEmpty()
        val noticeText = compose.onAllNodesWithTag("webRuntimeNotice").fetchSemanticsNodes().flatMap {
            it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
        }.joinToString(" ") { it.text }
        val categories = listOf("Unsupported", "ReferenceError", "TypeError", "SyntaxError", "HTTP", "timeout", "fetch", "revision", "stale", "denied", "resource", "404", "403", "network", "CORS", "jQuery", "jquery", "toastr", "undefined", "not defined")
        val shellNotice = Json.parseToJsonElement(evaluate("document.getElementById('notice')?.textContent ?? ''")).jsonPrimitive.content
        if (InstrumentationRegistry.getArguments().getString("browserLivePhase") == "opening") {
            File(output, "$name-private-diagnostic.json").writeText(buildJsonObject {
                put("runtime", noticeText); put("shell", shellNotice)
            }.toString())
        }
        val articles = evaluate("document.querySelectorAll('article').length").toInt()
        val frames = evaluate("document.querySelectorAll('iframe').length").toInt()
        File(output, "$name-page.json").writeText(buildJsonObject {
            put("webViews", webViews().size); put("articles", articles); put("iframes", frames)
            put("runtimeNotice", notice); put("visible", true)
            put("noticeHash", hash(noticeText))
            putJsonArray("noticeCategories") { categories.filter { noticeText.contains(it, ignoreCase = true) }.forEach { add(it) } }
            put("noticeLength", noticeText.length)
            put("shellNotice", shellNotice.isNotEmpty()); put("shellNoticeHash", hash(shellNotice))
            put("shellNoticeLength", shellNotice.length)
            putJsonArray("shellNoticeCategories") { categories.filter { shellNotice.contains(it, ignoreCase = true) }.forEach { add(it) } }
        }.toString())
        // A runtime notice may contain provider/card text; never persist a failure screenshot.
        check(!notice && shellNotice.isEmpty() && articles > 0 && frames > 0) { "BL_PAGE_METRICS" }
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(output, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    private fun waitForAssistantDom(position: Int, messageCount: Int): String {
        // shell.mjs renders #messages children in snapshot order and exposes only data-role.
        // Retained node identity excludes old articles; no invented data-message-id selector.
        val probe = """(() => {
            const rows = document.querySelectorAll('#messages > article'), row = rows[$position];
            if (rows.length !== $messageCount || !row || row.dataset.role !== 'assistant' ||
                window.__browserLiveOldRows.has(row) || row.querySelector('.status, .runtime-loading')) return null;
            const body = Array.from(row.children).filter(n => !['HEADER', 'DETAILS'].includes(n.tagName));
            if (!body.some(n => n.tagName === 'IFRAME' || n.textContent.trim())) return null;
            if (Array.from(row.querySelectorAll('iframe')).some(f => !f.src || f.getBoundingClientRect().height <= 0)) return null;
            return row.outerHTML;
        })()""".trimIndent()
        var rendered = "null"
        compose.waitUntil(60_000) { rendered = evaluate(probe); rendered != "null" }
        return hash(Json.parseToJsonElement(rendered).jsonPrimitive.content)
    }

    private fun webViews(): List<WebView> {
        val result = mutableListOf<WebView>()
        compose.runOnUiThread {
            fun visit(view: View) {
                if (view is WebView) result.add(view)
                if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            visit(compose.activity.window.decorView)
        }
        return result
    }

    private fun evaluate(script: String): String {
        val ready = CountDownLatch(1)
        val result = AtomicReference("null")
        val web = webViews().single()
        compose.runOnUiThread { web.evaluateJavascript(script) { result.set(it); ready.countDown() } }
        check(ready.await(10, TimeUnit.SECONDS)) { "BL_JS_TIMEOUT" }
        return result.get()
    }

    private fun marker(record: ConversationRecord, identity: JsonObject = buildJsonObject {}) = buildJsonObject {
        put("identity", identity)
        put("conversationId", record.id); put("pid", android.os.Process.myPid())
        put("runtimeHash", hash(Json.encodeToString(record.runtimeState)))
        put("recordHash", hash(Json.encodeToString(record)))
        putJsonArray("messages") { record.selectedMessages().forEach { message -> add(buildJsonObject {
            put("id", message.id); put("hash", hash(Json.encodeToString(message)))
        }) } }
    }

    private fun verifyMarker(record: ConversationRecord, expected: JsonObject) {
        check(record.executionMode == ConversationExecutionMode.BROWSER && record.character.nativeAdaptation == null) { "BL_MODE" }
        val actual = marker(record)
        check(actual["messages"] == expected["messages"] && actual["runtimeHash"] == expected["runtimeHash"] &&
            actual["recordHash"] == expected["recordHash"]) { "BL_DISK_RECOVERY" }
    }
    private fun ConversationRecord.selectedMessages() = turns.map { it.selected.message }

    private fun hash(value: String) = hash(value.toByteArray())
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it) }
    private fun clear(vm: ViewModel) = ViewModelStore().apply { put("live", vm); clear() }

    private class MemoryConnections : ConnectionStateStore {
        override val state = MutableStateFlow(GatewayAppState())
        override suspend fun update(transform: (GatewayAppState) -> GatewayAppState) { state.value = transform(state.value) }
    }
    private class MemoryCredentials : CredentialStore, CredentialResolver {
        private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
        override suspend fun put(credentialId: String, secret: String) { values[credentialId] = secret }
        override suspend fun getOrNull(credentialId: String) = values[credentialId]
        override suspend fun delete(credentialId: String) { values.remove(credentialId) }
        override suspend fun contains(credentialId: String) = values.containsKey(credentialId)
        override suspend fun resolve(credentialRef: String) = values[credentialRef]?.let(::SecretValue)
        fun clear() = values.clear()
    }
}
