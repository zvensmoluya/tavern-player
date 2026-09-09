package io.github.zvensmoluya.tavernplayer.conversation.web

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
            Persona("p", "User"), BuiltInPresets.default, ConversationExecutionMode.BROWSER) }
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
            saved.set(ConversationRepository(root, graph.promptCompiler).get(initial.id)!!)
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
                    Persona("p", "User"), BuiltInPresets.default, ConversationExecutionMode.BROWSER)
                repository.save(BrowserConversation.apply(record, BrowserActor("page"), "variables.replace",
                    buildJsonObject { put("type", "chat"); putJsonObject("data") { put("checkpoint", 42) } }))
            }
            pid.writeText(android.os.Process.myPid().toString())
        } else {
            assertEquals("recover", phase)
            assertNotEquals(pid.readText().trim().toInt(), android.os.Process.myPid())
            val record = repository.get("process-record")!!
            assertEquals(ConversationExecutionMode.BROWSER, record.executionMode)
            assertEquals(JsonPrimitive(42), record.runtimeState.browserChatVariables["checkpoint"])
            assertEquals(record.runtimeState.browserChatVariables, record.turns.last().selected.nativeHead()!!.browserChatVariables)
            root.deleteRecursively()
        }
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
        lateinit var web: WebView
        lateinit var session: BrowserSession
        fun state(running: Boolean = false) = ChatUiState(conversationId = record.id, character = character,
            executionMode = ConversationExecutionMode.BROWSER, browserSnapshot = BrowserConversation.snapshot(record), running = running, loadingConnections = false)
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
                session = BrowserSession(web, graph.browserEnvironment, state(), { _, _, _, _ -> error("No script mutations") }, { _, _ -> }, errors::add)
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
            val metrics = File(context.filesDir, "web-scroll-audit.json")
            metrics.writeText(buildJsonObject { put("initialVisible", 50); put("loadedVisible", 100); put("firstPaintMillis", firstPaintMillis);
                put("appPssKiB", android.os.Debug.getPss()); put("readingPositionRetained", true) }.toString())
        } finally { compose.runOnUiThread { session.release(); web.destroy() } }
    }

}
