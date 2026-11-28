package io.github.zvensmoluya.tavernplayer.conversation.web

import io.github.zvensmoluya.tavernplayer.conversation.blockingGet

import android.webkit.WebView
import androidx.core.view.allViews
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zvensmoluya.tavernplayer.app.AppGraph
import io.github.zvensmoluya.tavernplayer.conversation.*
import io.github.zvensmoluya.tavernplayer.content.BrowserProgram
import io.github.zvensmoluya.tavernplayer.content.BuiltInPresets
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BrowserSessionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun authorPagesShareObjectsAndSynchronousEventsThroughProductionSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = AppGraph(context)
        val root = File(context.cacheDir, "web-session-${UUID.randomUUID()}")
        val repository = ConversationRepository(root, graph.promptCompiler, prepareBrowser = graph.browserEnvironment::prepare)
        val pages = """<div>Scene time</div>
```html
<body><p>Provider</p><script>
initializeGlobal('Shared', {count:0, increment(){this.count++}});
eventOn('shared-event', value => {Shared.increment(); value.provider=true});
</script></body>
```

> ```html
> <head><title>Consumer</title></head><script>
> (async()=>{await waitGlobalInitialized('Shared');
> const value={};eventEmitAndWait('shared-event',value);
> let isolated=false;try{parent.parent.document.title}catch(e){isolated=true}
> replaceVariables({count:Shared.count,provider:value.provider,same:Shared===parent.Shared,isolated});
> })().catch(error=>toastr.error(error.message));
> </script>
> ```"""
        val saved = AtomicReference(runBlocking { repository.create(CharacterAsset("session-audit", name = "Actor", firstMessage = pages),
            Persona("p", "User"), BuiltInPresets.default) })
        val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
        lateinit var web: WebView
        lateinit var session: BrowserSession
        fun state() = saved.get().let { record -> ChatUiState(conversationId = record.id, character = record.character,
            executionMode = record.executionMode, loadingConnections = false,
            browserSnapshot = JsonObject(BrowserConversation.snapshot(record) +
                ("program" to Json.encodeToJsonElement(BrowserProgram.serializer(), record.character.browserProgram!!)))) }
        try {
            compose.runOnUiThread {
                web = WebView(compose.activity); compose.activity.setContentView(web)
                session = BrowserSession(web, graph.browserEnvironment, state(), { actor, revision, method, args ->
                    BrowserConversation.authorize(saved.get(), actor, revision)
                    saved.set(repository.save(BrowserConversation.apply(saved.get(), actor, method, args)))
                    session.update(state())
                    buildJsonObject { put("snapshot", BrowserConversation.snapshot(saved.get())); put("value", JsonNull) }
                }, { _, _ -> }, failures::add)
                session.start()
            }
            compose.waitUntil(30_000) { saved.get().runtimeState.browserChatVariables["count"] == JsonPrimitive(1) || failures.isNotEmpty() }
            assertTrue(failures.joinToString(), failures.isEmpty())
            val variables = saved.get().runtimeState.browserChatVariables
            assertEquals(JsonPrimitive(1), variables["count"])
            listOf("provider", "same", "isolated").forEach { assertEquals(it, JsonPrimitive(true), variables[it]) }
        } finally {
            compose.runOnUiThread { session.release(); web.destroy() }
            root.deleteRecursively()
        }
    }
    @Test fun originalPageSavesThroughProductionBridgeAndReopensFromDurableState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = AppGraph(context)
        val root = File(context.cacheDir, "web-audit-${UUID.randomUUID()}")
        val repository = ConversationRepository(root, graph.promptCompiler, prepareBrowser = graph.browserEnvironment::prepare)
        val page = """```html
<body><input id="field" style="width:220px;height:42px" oninput="replaceVariables({...getVariables(),input:this.value})"><p id="ready">Browser runtime</p><img id="image" style="width:80px;height:1px" src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9H0AAAAASUVORK5CYII="><script>
setTimeout(()=>document.getElementById('image').style.height='180px',300);
let isolated=false;try{parent.parent.document.title}catch(e){isolated=true}
const previous=getVariables();replaceVariables({...previous,boots:(previous.boots||0)+1,isolated,bridge:typeof PlayerBridge});
</script></body>
```"""
        val initial = runBlocking { repository.create(CharacterAsset("web-audit", name = "Actor", firstMessage = page),
            Persona("p", "User"), BuiltInPresets.default) }
        val saved = AtomicReference(initial)
        val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
        lateinit var web: WebView
        lateinit var session: BrowserSession
        fun state(record: ConversationRecord) = ChatUiState(conversationId = record.id, character = record.character,
            executionMode = record.executionMode, loadingConnections = false,
            browserSnapshot = JsonObject(BrowserConversation.snapshot(record) + ("program" to Json.encodeToJsonElement(BrowserProgram.serializer(), record.character.browserProgram!!))))
        fun mount() = compose.runOnUiThread {
            web = WebView(compose.activity)
            compose.activity.setContentView(web)
            session = BrowserSession(web, graph.browserEnvironment, state(saved.get()), { actor, revision, method, args ->
                BrowserConversation.authorize(saved.get(), actor, revision)
                val next = repository.save(BrowserConversation.apply(saved.get(), actor, method, args))
                saved.set(next)
                session.update(state(next))
                buildJsonObject { put("snapshot", BrowserConversation.snapshot(next)); put("value", JsonNull) }
            }, { _, _ -> }, failures::add)
            session.start()
        }
        try {
            mount()
            compose.waitUntil(30_000) { saved.get().runtimeState.browserChatVariables["boots"] == JsonPrimitive(1) || failures.isNotEmpty() }
            assertTrue(failures.joinToString(), failures.isEmpty())
            assertEquals(JsonPrimitive(true), saved.get().runtimeState.browserChatVariables["isolated"])
            assertEquals(JsonPrimitive("undefined"), saved.get().runtimeState.browserChatVariables["bridge"])
            val coordinates = AtomicReference<JsonArray>()
            val coordinateReady = java.util.concurrent.CountDownLatch(1)
            compose.runOnUiThread {
                web.evaluateJavascript("JSON.stringify((()=>{const r=document.querySelector('article iframe').getBoundingClientRect();return [r.x,r.y,r.height,window.innerWidth]})())") {
                    coordinates.set(Json.parseToJsonElement(Json.parseToJsonElement(it).jsonPrimitive.content).jsonArray)
                    coordinateReady.countDown()
                }
            }
            assertTrue(coordinateReady.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val location = IntArray(2)
            compose.runOnUiThread { web.getLocationOnScreen(location); web.requestFocus() }
            val scale = web.width / coordinates.get()[3].jsonPrimitive.float
            val x = (coordinates.get()[0].jsonPrimitive.float + 20) * scale + location[0]
            val y = (coordinates.get()[1].jsonPrimitive.float + 20) * scale + location[1]
            val instrument = InstrumentationRegistry.getInstrumentation()
            val time = android.os.SystemClock.uptimeMillis()
            instrument.sendPointerSync(android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, x, y, 0))
            instrument.sendPointerSync(android.view.MotionEvent.obtain(time, time + 50, android.view.MotionEvent.ACTION_UP, x, y, 0))
            instrument.waitForIdleSync()
            instrument.sendStringSync("hello")
            compose.waitUntil(15_000) { saved.get().runtimeState.browserChatVariables["input"] == JsonPrimitive("hello") || failures.isNotEmpty() }
            assertTrue(failures.toString(), failures.isEmpty())
            val heightReady = java.util.concurrent.CountDownLatch(1)
            val frameHeight = AtomicReference(0f)
            compose.runOnUiThread { web.evaluateJavascript("document.querySelector('article iframe').getBoundingClientRect().height") {
                frameHeight.set(Json.parseToJsonElement(it).jsonPrimitive.float); heightReady.countDown()
            } }
            assertTrue(heightReady.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("Image resize must expand its containing frame", frameHeight.get() > 180)
            repeat(3) { compose.runOnUiThread { session.update(state(saved.get())) } }
            compose.runOnUiThread { session.release(); web.destroy() }
            val reopened = ConversationRepository(root, graph.promptCompiler).blockingGet(initial.id)!!
            // A web asset update changes the fingerprint between the two mounts.
            saved.set(reopened.copy(character = reopened.character.copy(browserProgram =
                reopened.character.browserProgram!!.copy(runtimeFingerprint = "previous-build"))))
            assertEquals(JsonPrimitive(1), saved.get().runtimeState.browserChatVariables["boots"])
            mount()
            compose.waitUntil(30_000) { saved.get().runtimeState.browserChatVariables["boots"] == JsonPrimitive(2) || failures.isNotEmpty() }
            assertTrue(failures.joinToString(), failures.isEmpty())
            assertEquals(JsonPrimitive(2), saved.get().runtimeState.browserChatVariables["boots"])
        } finally {
            compose.runOnUiThread { session.release(); web.destroy() }
            root.deleteRecursively()
        }
    }
    @Test fun processRecoveryWithExternalTermination() {
        val phase = InstrumentationRegistry.getArguments().getString("webRecoveryPhase")
        org.junit.Assume.assumeTrue("Run in two instrumentation processes with webRecoveryPhase prepare/recover", phase != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "web-process-audit")
        val repository = ConversationRepository(root, PromptCompiler(), idFactory = { "process-record" })
        val pid = File(root, "pid.txt")
        if (phase == "prepare") {
            runBlocking {
                val record = repository.create(CharacterAsset("sample", name = "Actor", firstMessage = "Opening"),
                    Persona("p", "User"), BuiltInPresets.default)
                repository.save(BrowserConversation.apply(record, BrowserActor("page"), "variables.replace",
                    buildJsonObject { put("type", "chat"); putJsonObject("data") { put("checkpoint", 42) } }))
            }
            pid.writeText(android.os.Process.myPid().toString())
        } else {
            assertEquals("recover", phase)
            assertNotEquals(pid.readText().trim().toInt(), android.os.Process.myPid())
            val record = repository.blockingGet("process-record")!!
            assertEquals(ConversationExecutionMode.BROWSER, record.executionMode)
            assertEquals(JsonPrimitive(42), record.runtimeState.browserChatVariables["checkpoint"])
            assertEquals(record.runtimeState.browserChatVariables, record.turns.last().selected.nativeHead()!!.browserChatVariables)
            root.deleteRecursively()
        }
    }

    @Test fun messagePencilOpensEditorAndSavesLongTextWithKeyboardVisible() {
        val graph = AppGraph(InstrumentationRegistry.getInstrumentation().targetContext)
        val message = ConversationMessage("reply", MessageRole.ASSISTANT, "门外有人。\n\n".repeat(60), "守灯人")
        val record = ConversationRecord(id = "editor", character = CharacterAsset("card", name = "守灯人").snapshot(),
            persona = Persona("p", "旅人"), turns = listOf(ConversationTurn("turn", message.role,
                listOf(MessageVariant("variant", message)))), createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
            executionMode = ConversationExecutionMode.BROWSER)
        val state = ChatUiState(conversationId = record.id, character = record.character,
            messages = listOf(ChatMessageState(message)), browserSnapshot = BrowserConversation.snapshot(record))
        val saved = AtomicReference<String>()
        compose.setContent {
            ChatScreen(state, ChatScreenActions(updateInput = {}, send = {}, cancel = {}, retry = {},
                selectConnection = {}, reset = {}, openModels = {}, editMessage = { id, text, mode ->
                    assertEquals(message.id, id)
                    assertEquals(MessageEditMode.TEXT_ONLY, mode)
                    saved.set(text)
                }), browserEnvironment = graph.browserEnvironment)
        }
        val web = compose.runOnIdle { compose.activity.window.decorView.allViews.filterIsInstance<WebView>().single() }
        val tapped = java.util.concurrent.atomic.AtomicBoolean(false)
        compose.waitUntil(20_000) {
            if (!tapped.get()) compose.runOnUiThread {
                web.evaluateJavascript("(() => { const b=document.querySelector('button[aria-label=编辑消息]'); " +
                    "if (!b) return false; b.click(); return true; })()") { if (it == "true") tapped.set(true) }
            }
            tapped.get()
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("webMessageEditor").fetchSemanticsNodes().isNotEmpty() }
        val editor = compose.onNodeWithTag("webMessageEditor")
        editor.assertIsDisplayed()
        compose.onNodeWithText("保存文字").assertIsNotEnabled()
        editor.performClick()
        editor.performTextReplacement("门外的脚步停了。")
        compose.onNodeWithText("另开分支").assertIsDisplayed()
        compose.onNodeWithText("保存文字").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("门外的脚步停了。", saved.get()) }
        editor.assertDoesNotExist()
    }

    @Test fun chatMenuStartsAndStopsResourcePreparation() {
        val graph = AppGraph(InstrumentationRegistry.getInstrumentation().targetContext)
        val record = ConversationRecord(id = "menu", character = CharacterAsset("card", name = "Actor").snapshot(),
            persona = Persona("p", "Reader"), turns = emptyList(), createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
            executionMode = ConversationExecutionMode.BROWSER)
        val state = ChatUiState(conversationId = record.id, character = record.character,
            browserSnapshot = BrowserConversation.snapshot(record))
        val cancelled = kotlinx.coroutines.CompletableDeferred<Unit>()
        compose.setContent {
            ChatScreen(state, ChatScreenActions(updateInput = {}, send = {}, cancel = {}, retry = {},
                selectConnection = {}, reset = {}, openModels = {}),
                browserEnvironment = graph.browserEnvironment,
                browserInvoke = { _, _, method, _ ->
                    check(method == "resources.reprepare")
                    try { kotlinx.coroutines.awaitCancellation() } finally { cancelled.complete(Unit) }
                })
        }
        compose.onNodeWithText("重新准备网页资源").assertDoesNotExist()
        compose.onNodeWithTag("chatMore").performClick()
        compose.onNodeWithText("重新准备网页资源").performClick()
        compose.onNodeWithText("正在重新准备网页资源…").assertIsDisplayed()
        compose.onNodeWithText("停止准备").performClick()
        compose.waitUntil(5_000) { cancelled.isCompleted }
        compose.onNodeWithText("准备已停止，原版本已保留").assertIsDisplayed()
        compose.onNodeWithText("重试加载网页").performClick()
        compose.onNodeWithText("准备已停止，原版本已保留").assertDoesNotExist()
        compose.onNodeWithText("重新准备网页资源").assertDoesNotExist()
    }

    @Test fun savedConversationDisplaysOptionalAvatarsByRole() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = AppGraph(context)
        val root = File(context.cacheDir, "chat-avatars-${UUID.randomUUID()}").apply { mkdirs() }
        fun portrait(name: String, color: Int): File {
            val bitmap = android.graphics.Bitmap.createBitmap(140, 120, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            return File(root, name).also { file ->
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val characterImage = portrait("character.png", android.graphics.Color.RED)
        val userImage = portrait("user.png", android.graphics.Color.BLUE)
        val environment = BrowserEnvironment(context, graph.browserEnvironment.images, graph.browserEnvironment.resources,
            assetFile = { _, _ -> null }, avatarFile = { characterImage })
        val record = ConversationRecord(id = "history", character = CharacterAsset("card", name = "Actor").snapshot(),
            persona = Persona("p", "Reader", avatar = userImage.toURI().toString()),
            turns = MessageRole.entries.map { role ->
                val id = role.name
                val text = if (role == MessageRole.USER) "一段需要换行的玩家发言。".repeat(20) else id
                ConversationTurn(id, role, listOf(MessageVariant(id, ConversationMessage(id, role, text, id))))
            }, createdAtEpochMillis = 1, updatedAtEpochMillis = 1, executionMode = ConversationExecutionMode.BROWSER)
        val show = androidx.compose.runtime.mutableStateOf(false)
        val persona = androidx.compose.runtime.mutableStateOf(record.persona)
        val state = ChatUiState(conversationId = record.id, character = record.character,
            browserSnapshot = BrowserConversation.snapshot(record), executionMode = record.executionMode)
        try {
            compose.setContent {
                WebMessageView(state.copy(persona = persona.value), environment,
                    invoke = { _, _, _, _ -> error("Unexpected author operation") },
                    onAction = { _, _ -> }, showChatAvatars = show.value)
            }
            val web = compose.runOnIdle { compose.activity.window.decorView.allViews.filterIsInstance<WebView>().single() }
            fun evaluate(script: String): String {
                val latch = java.util.concurrent.CountDownLatch(1)
                val result = AtomicReference("")
                compose.runOnUiThread { web.evaluateJavascript(script) { result.set(it); latch.countDown() } }
                check(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return result.get()
            }
            compose.waitUntil(20_000) { evaluate("document.querySelectorAll('article').length") == "3" }
            val widthScript = "document.querySelector('article[data-role=user] .message-segment').getBoundingClientRect().width"
            val width = evaluate(widthScript)
            assertEquals("0", evaluate("document.querySelectorAll('header img').length"))
            compose.runOnIdle { show.value = true }
            compose.waitUntil(10_000) { evaluate("Array.from(document.querySelectorAll('header img')).filter(i => i.naturalWidth > 0).length") == "2" }
            val avatar = "role => document.querySelector('article[data-role=' + role + '] header img')"
            assertEquals("true", evaluate("((img) => !img('system') && img('assistant').src !== img('user').src && img('user').width === 24)($avatar)"))
            assertEquals(width, evaluate(widthScript))
            compose.runOnIdle { persona.value = record.persona.copy(avatar = File(root, "missing.png").path) }
            compose.waitUntil(10_000) { evaluate("((img) => !!img('assistant') && !img('user'))($avatar)") == "true" }
            assertEquals("true", evaluate("document.querySelector('article[data-role=user] header').textContent.includes('USER')"))
            compose.runOnIdle { show.value = false }
            compose.waitUntil(10_000) { evaluate("document.querySelectorAll('header img').length") == "0" }
        } finally { root.deleteRecursively() }
    }

    @Test fun longHistoryMaintainsReadingPositionAndAppendsStreamingText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = AppGraph(context)
        val character = CharacterAsset("scroll-audit", name = "Actor").snapshot()
        var record = ConversationRecord(id = "scroll-audit", character = character, persona = Persona("p", "User"), turns = (0..124).map { n ->
            ConversationTurn("turn-$n", MessageRole.ASSISTANT, listOf(MessageVariant("v-$n",
                ConversationMessage("m-$n", MessageRole.ASSISTANT, "Message $n\n\n" + "Readable text. ".repeat(40), "Actor"))))
        }, createdAtEpochMillis = 1, updatedAtEpochMillis = 1, executionMode = ConversationExecutionMode.BROWSER)
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val taps = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String?>>()
        lateinit var web: WebView
        lateinit var session: BrowserSession
        fun state(running: Boolean = false) = ChatUiState(conversationId = record.id, character = character,
            executionMode = ConversationExecutionMode.BROWSER, browserSnapshot = BrowserConversation.snapshot(record),
            generationPhase = if (running) GenerationPhase.ACTIVE else GenerationPhase.IDLE, loadingConnections = false)
        fun evaluate(script: String): String {
            val ready = java.util.concurrent.CountDownLatch(1)
            val result = AtomicReference("")
            compose.runOnUiThread { web.evaluateJavascript(script) { result.set(it); ready.countDown() } }
            check(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
            return result.get()
        }
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            compose.runOnUiThread {
                web = WebView(compose.activity); compose.activity.setContentView(web)
                session = BrowserSession(web, graph.browserEnvironment, state(), { _, _, _, _ -> error("No script mutations") }, { action, id -> taps.add(action to id) }, errors::add)
                session.start()
            }
            compose.waitUntil(30_000) { evaluate("document.querySelectorAll('article').length") == "50" }
            val firstPaintMillis = android.os.SystemClock.elapsedRealtime() - started
            evaluate("document.getElementById('earlier').click()")
            compose.waitUntil(15_000) { evaluate("document.querySelectorAll('article').length") == "100" }
            evaluate("window.scrollTo(0,0);window.dispatchEvent(new Event('scroll'))")
            val last = record.turns.last()
            record = record.copy(turns = record.turns.dropLast(1) + last.copy(variants = listOf(last.selected.copy(status = PersistedMessageStatus.STREAMING))))
            repeat(10) { n ->
                val turn = record.turns.last()
                record = record.copy(turns = record.turns.dropLast(1) + turn.copy(variants = listOf(turn.selected.copy(message = turn.selected.message.copy(content = "Streaming $n")))))
                compose.runOnUiThread { session.update(state(true)) }
            }
            compose.waitUntil(15_000) { evaluate("document.body.innerText.includes('Streaming 9')") == "true" }
            assertEquals("0", evaluate("Math.round(window.scrollY)"))
            evaluate("window.scrollTo(0,document.documentElement.scrollHeight);window.dispatchEvent(new Event('scroll'))")
            val turn = record.turns.last()
            record = record.copy(turns = record.turns.dropLast(1) + turn.copy(variants = listOf(turn.selected.copy(status = PersistedMessageStatus.COMPLETE))))
            compose.runOnUiThread { session.update(state()) }
            compose.waitUntil(15_000) { evaluate("document.documentElement.scrollHeight-window.scrollY-window.innerHeight < 100") == "true" }
            assertEquals("100", evaluate("document.querySelectorAll('article').length"))
            assertTrue(errors.toString(), errors.isEmpty())
            val origin = io.github.zvensmoluya.tavernplayer.conversation.storage.ConversationBranch(
                "root", "parent", "other-turn", "turn-5", true, null, 5)
            val summary = io.github.zvensmoluya.tavernplayer.conversation.storage.ConversationSummary(record.id,
                character.assetId, 1, 2, 125, "", "BROWSER", 2, branch = origin)
            val child = summary.copy(id = "child", branch = origin.copy(parentId = record.id, parentTurnId = "turn-5"))
            compose.runOnUiThread { session.update(state(), branchLinks = BranchLinks(summary, "雨夜来客", listOf(child), "turn-5")) }
            compose.waitUntil(15_000) { evaluate("document.querySelector('article .branch-link')?.textContent.includes('雨夜来客')") == "true" }
            assertEquals("true", evaluate("(() => { const a=document.querySelector('article:has(.branch-link)'); " +
                "return Math.abs(a.getBoundingClientRect().top)<3 && a.firstElementChild.textContent.includes('查看原处') " +
                "&& a.lastElementChild.textContent.includes('另有 1 种走法') })()"))
            assertTrue(evaluate("document.querySelectorAll('article').length").toInt() <= 50)
            evaluate("document.querySelector('article .branch-link').click()")
            compose.waitUntil(5_000) { ("origin" to "turn-5") in taps }
            evaluate("document.querySelector('article:has(.branch-link) .message-edit').click()")
            compose.waitUntil(5_000) { ("edit" to "m-5") in taps }
            evaluate("document.querySelector('article:has(.branch-link) .branch-link:last-child').click()")
            compose.waitUntil(5_000) { ("paths" to "turn-5") in taps }
            evaluate("document.getElementById('later').click()")
            compose.waitUntil(10_000) { evaluate("document.querySelectorAll('article').length").toInt() > 50 }
            evaluate("document.getElementById('bottom').click()")
            compose.waitUntil(10_000) { evaluate("document.querySelector('article:last-child').textContent.includes('Streaming 9')") == "true" }
            val metrics = File(context.filesDir, "web-scroll-audit.json")
            metrics.writeText(buildJsonObject { put("initialVisible", 50); put("loadedVisible", 100); put("firstPaintMillis", firstPaintMillis);
                put("appPssKiB", android.os.Debug.getPss()); put("readingPositionRetained", true) }.toString())
        } finally { compose.runOnUiThread { session.release(); web.destroy() } }
    }

}
