package io.github.zvensmoluya.tavernplayer.conversation.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.zvensmoluya.tavernplayer.conversation.*
import io.github.zvensmoluya.tavernplayer.content.BrowserProgramReader
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One visible WebView, with a trusted shell and a different origin for author programs. */
class BrowserSession(
    private val webView: WebView,
    private val environment: BrowserEnvironment,
    initial: ChatUiState,
    private val invoke: suspend (BrowserActor, String, String, JsonObject) -> JsonObject,
    private val uiAction: (String, String?) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    val epoch: String = UUID.randomUUID().toString()
    private val conversationId = requireNotNull(initial.conversationId)
    private val character = initial.character
    private val authorOrigin = "https://c-${BrowserProgramReader.sha256(conversationId).take(24)}.cards.invalid"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val frames = ConcurrentHashMap<String, Frame>()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var state = initial
    private var ready = false
    private var released = false
    private var failed = false
    private var updateJob: Job? = null
    private var lastSend = 0L
    private var sentSnapshot: JsonObject? = null
    private var preparedPresetHash: String? = null
    private var preparedPreset: JsonElement = JsonNull
    private var prepareJob: Job? = null
    private val handled = LinkedHashMap<String, JsonObject>()
    private val inFlight = mutableSetOf<String>()
    private val frameOperations = mutableMapOf<String, MutableSet<Job>>()
    private data class Frame(val actor: BrowserActor, val html: String, val kind: String, val snapshot: JsonObject, val viewportHeight: Int)

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        val fingerprint = character.browserProgram?.runtimeFingerprint.orEmpty()
        require(fingerprint.isEmpty() || fingerprint == environment.fingerprint) { "网页运行程序已更新，请新建对话；原有记录保留" }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            error("系统 WebView 版本过旧，请更新 Android System WebView")
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false; allowContentAccess = false
            @Suppress("DEPRECATION")
            setAllowFileAccessFromFileURLs(false)
            @Suppress("DEPRECATION")
            setAllowUniversalAccessFromFileURLs(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            // Existing character images may be HTTP. CSP forbids HTTP scripts/styles/connects,
            // and the interceptor only routes image requests to the existing image validator.
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
                    onFailure("网页程序报告错误：${consoleMessage.message().take(240)}")
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        WebViewCompat.addWebMessageListener(webView, "PlayerBridge", setOf(ROOT_ORIGIN)) { _, message, origin, mainFrame, _ ->
            if (!mainFrame || origin.toString() != ROOT_ORIGIN || released) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            if (raw.length > 3 * 1024 * 1024) { onFailure("网页请求超过限制"); return@addWebMessageListener }
            scope.launch { handle(raw) }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                return try { response(request) }
                catch (error: Exception) {
                    scope.launch { onFailure("网页资源不可用：${error.message?.take(180) ?: "读取失败"}") }
                    result("text/plain", "Resource unavailable".toByteArray(), status = 403)
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val value = request.url.toString()
                return !(value == "$ROOT_ORIGIN/web/index.html" || (!request.isForMainFrame && value.startsWith("$authorOrigin/frame/")))
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                release(); onFailure("网页渲染进程已退出，请重新打开对话；已保存的消息保留")
                return true
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) onFailure("网页消息区加载失败，请重新打开对话")
            }
        }
        webView.loadUrl("$ROOT_ORIGIN/web/index.html")
        preparePreset()
    }

    fun update(next: ChatUiState) {
        if (released || next.conversationId != conversationId) return
        state = next
        if (!next.running && !next.browserGenerating) preparePreset()
        updateJob?.cancel()
        val elapsed = android.os.SystemClock.uptimeMillis() - lastSend
        val wait = if (next.running && elapsed < 50) 50 - elapsed else 0
        updateJob = scope.launch { if (wait > 0) delay(wait); sendSnapshot() }
    }

    private fun preparePreset() {
        val hash = state.browserSnapshot["presetHash"]?.jsonPrimitive?.contentOrNull ?: return
        if (hash == preparedPresetHash) return
        preparedPresetHash = hash
        prepareJob?.cancel()
        prepareJob = scope.launch {
            val program = state.browserSnapshot["presetProgram"]?.let { json.decodeFromJsonElement<io.github.zvensmoluya.tavernplayer.content.BrowserProgram>(it) }
                ?: return@launch
            val prepared = try { environment.prepare(program) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { program.copy(blockedSourceIds = program.sources.map { it.id }.toSet(), diagnostics = program.diagnostics + "当前预设的程序无法完成装载校验") }
            val checked = if (prepared.mvu != null) prepared.copy(mvu = null,
                diagnostics = prepared.diagnostics + "预设内的 MVU 注册不能替换当前会话的角色 Schema，已停用该注册") else prepared
            if (preparedPresetHash == hash) { preparedPreset = json.encodeToJsonElement(checked); sendSnapshot() }
        }
    }

    private fun snapshot(): JsonObject = buildJsonObject {
        state.browserSnapshot.forEach { (key, value) -> put(key, value) }
        put("presetProgram", preparedPreset)
        put("generation", state.browserGeneration)
        put("generating", state.running || state.browserGenerating)
        val ui = state.messages.associateBy { it.message.id }
        putJsonArray("messages") {
            state.browserSnapshot["messages"]?.jsonArray?.forEach { raw ->
                val message = raw.jsonObject
                val display = ui[message["id"]?.jsonPrimitive?.content]
                add(buildJsonObject {
                    message.forEach { (key, value) -> put(key, value) }
                    put("display", display?.displayContent ?: message["message"]?.jsonPrimitive?.content.orEmpty())
                    putJsonArray("reasoning") { display?.displayReasoning?.forEach { add(it) } }
                })
            }
        }
    }
    private fun sendSnapshot() {
        if (!ready || released) return
        lastSend = android.os.SystemClock.uptimeMillis()
        val next = snapshot()
        val previous = sentSnapshot
        sentSnapshot = next
        send(buildJsonObject {
            put("type", if (previous == null) "snapshot" else "delta"); put("epoch", epoch)
            if (previous == null) put("snapshot", next) else {
                put("changes", JsonObject(next.filter { (key, value) -> key != "messages" && previous[key] != value }))
                val before = previous["messages"]!!.jsonArray.associateBy { it.jsonObject["turnId"] }
                val messages = next["messages"]!!.jsonArray
                put("messages", JsonArray(messages.filter { before[it.jsonObject["turnId"]] != it }))
                put("order", JsonArray(messages.map { it.jsonObject.getValue("turnId") }))
            }
            putJsonObject("flags") {
                put("busy", state.busy); put("running", state.running); put("browserGenerating", state.browserGenerating)
                put("retryAvailable", state.retryAvailable); put("regenerateAvailable", state.regenerateAvailable)
                put("variantNavigationAvailable", state.variantNavigationAvailable); put("notice", state.message)
            }
        })
    }
    private fun send(packet: JsonObject) {
        if (!released) webView.evaluateJavascript("window.Player && window.Player.receive(JSON.parse(${JsonPrimitive(packet.toString())}))", null)
    }

    private suspend fun handle(raw: String) {
        val request = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val id = request["id"]?.jsonPrimitive?.contentOrNull ?: return
        if (id.length > 128) return
        handled[id]?.let { send(it); return }
        if (!inFlight.add(id)) return
        val packet = try {
            val method = request["method"]?.jsonPrimitive?.content ?: error("缺少宿主操作")
            val args = request["args"] as? JsonObject ?: JsonObject(emptyMap())
            if (method != "ready") require(request["epoch"]?.jsonPrimitive?.content == epoch) { "网页运行实例已失效" }
            val value: JsonElement = when {
                method == "ready" -> { ready = true; sentSnapshot = null; sendSnapshot(); JsonNull }
                method == "frame.create" -> createFrame(args)
                method == "frame.dispose" -> {
                    val token = args["token"]?.jsonPrimitive?.content
                    frames.remove(token); frameOperations.remove(token)?.forEach { it.cancel() }; JsonNull
                }
                method.startsWith("ui.") -> {
                    require(request["actorToken"] == null) { "作者页面不能调用播放器 UI 接口" }
                    require(request["revision"]?.jsonPrimitive?.content == state.browserSnapshot["revision"]?.jsonPrimitive?.content) { "消息已更新，请重试" }
                    val action = method.removePrefix("ui.")
                    require(action in setOf("edit", "retry", "previous", "next", "regenerate")) { "未支持的界面操作" }
                    require(!state.busy) { "会话正在处理操作" }
                    uiAction(action, args["id"]?.jsonPrimitive?.contentOrNull)
                    buildJsonObject { put("accepted", true) }
                }
                method.startsWith("host.") -> {
                    check(!failed) { "当前网页运行实例已停止，请重新加载" }
                    val frame = frames[request["actorToken"]?.jsonPrimitive?.content] ?: error("作者页面已销毁")
                    require(frame.kind == "page" || frame.kind == "script") { "该网页实例没有业务操作权限" }
                    val actor = frame.actor
                    if (actor.turnId != null) require(state.browserSnapshot["messages"]?.jsonArray?.any {
                        it.jsonObject["turnId"]?.jsonPrimitive?.content == actor.turnId && it.jsonObject["variantId"]?.jsonPrimitive?.content == actor.variantId
                    } == true) { "页面所属候选已失效" }
                    val job = currentCoroutineContext().job
                    val operations = frameOperations.getOrPut(actor.id) { mutableSetOf() }
                    operations.add(job)
                    try { invoke(actor, request["revision"]?.jsonPrimitive?.content ?: error("缺少状态版本"), method.removePrefix("host."), args) }
                    finally { operations.remove(job) }
                }
                else -> error("未支持的网页请求")
            }
            buildJsonObject { put("type", "result"); put("id", id); put("result", value) }
        } catch (error: Exception) {
            if (error is BrowserPersistenceException) {
                failed = true
                send(buildJsonObject { put("type", "fatal"); put("message", error.message); put("epoch", epoch) })
                onFailure(error.message ?: "网页状态未保存，请重新加载")
            }
            buildJsonObject { put("type", "result"); put("id", id); put("error", if (error is CancellationException) "操作已取消，已保存变更保留" else error.message?.take(240) ?: "宿主操作失败") }
        }
        inFlight.remove(id); handled[id] = packet
        while (handled.size > 256) handled.remove(handled.keys.first())
        send(packet)
    }

    private fun createFrame(args: JsonObject): JsonObject {
        require(frames.size < 512) { "当前会话的网页实例达到 512 个上限" }
        val kind = args["kind"]?.jsonPrimitive?.content
        require(kind in setOf("static", "page", "script", "session")) { "无效网页类型" }
        if (kind == "session") require(frames.values.none { it.kind == "session" }) { "会话协调器已存在" }
        val sourceId = args["sourceId"]?.jsonPrimitive?.contentOrNull
        val source = if (kind == "script") listOfNotNull(state.browserSnapshot["program"], preparedPreset.takeIf { it != JsonNull })
            .flatMap { it.jsonObject["sources"]?.jsonArray.orEmpty() }
            .firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == sourceId && it.jsonObject["enabled"]?.jsonPrimitive?.boolean == true }?.jsonObject
            ?: error("脚本来源未启用") else null
        val message = if (kind == "page" || kind == "static") state.browserSnapshot["messages"]?.jsonArray?.firstOrNull {
            it.jsonObject["id"]?.jsonPrimitive?.content == args["messageId"]?.jsonPrimitive?.content
        }?.jsonObject ?: error("消息已失效") else null
        if (kind == "page") require(message?.get("status")?.jsonPrimitive?.content == "COMPLETE") { "消息尚未完成" }
        val token = UUID.randomUUID().toString()
        val actor = BrowserActor(token, message?.get("turnId")?.jsonPrimitive?.content, message?.get("variantId")?.jsonPrimitive?.content, sourceId)
        val html = source?.get("content")?.jsonPrimitive?.content ?: args["html"]?.jsonPrimitive?.content ?: error("缺少网页内容")
        require(html.length <= 2 * 1024 * 1024) { "网页超过 2 MiB" }
        frames[token] = Frame(actor, html, kind!!, snapshot(), args["viewportHeight"]?.jsonPrimitive?.intOrNull?.coerceIn(100, 5000) ?: 800)
        return buildJsonObject { put("token", token); put("url", "$authorOrigin/frame/$token") }
    }

    private fun response(request: WebResourceRequest): WebResourceResponse {
        require(!released) { "网页运行实例已结束" }
        require(request.method == "GET") { "网页资源仅允许 GET" }
        val uri = request.url; val origin = "${uri.scheme}://${uri.authority}"
        if (origin == ROOT_ORIGIN || origin == authorOrigin) {
            if (uri.path == "/favicon.ico") return result("image/x-icon", ByteArray(0))
            if (uri.path?.startsWith("/web/") == true) {
                val name = uri.path!!.removePrefix("/web/")
                require(name in BrowserEnvironment.ASSETS && name != "parent.html")
                val mime = when { name.endsWith(".html") -> "text/html"; name.endsWith(".css") -> "text/css"; name.endsWith(".json") -> "application/json"; else -> "application/javascript" }
                return result(mime, environment.asset(name), csp = if (origin == ROOT_ORIGIN) shellCsp() else authorCsp())
            }
            if (origin == authorOrigin && uri.path?.startsWith("/frame/") == true) {
                val frame = frames[uri.lastPathSegment] ?: error("网页实例已失效")
                val configuration = buildJsonObject {
                    put("epoch", epoch); put("rootOrigin", ROOT_ORIGIN); put("html", frame.html); put("kind", frame.kind)
                    put("actor", json.encodeToJsonElement(frame.actor)); put("snapshot", frame.snapshot); put("viewportHeight", frame.viewportHeight)
                }.toString().replace("<", "\\u003c")
                val body = environment.asset("parent.html").toString(Charsets.UTF_8).replace("__PLAYER_CONFIGURATION__", configuration)
                return result("text/html", body.toByteArray(), csp = authorCsp())
            }
            if (origin == authorOrigin && uri.path == "/avatar") {
                val file = environment.avatarFile(character.assetId) ?: error("角色头像不存在")
                return result("image/png", file.readBytes())
            }
            if (origin == authorOrigin && uri.path?.startsWith("/asset/") == true) {
                val asset = character.assets.find { it.id == uri.lastPathSegment } ?: error("角色资源不存在")
                val file = environment.assetFile(character.assetId, asset.id) ?: error("角色资源尚未准备")
                return result("image/${asset.extension.trimStart('.').ifBlank { "png" }}", file.readBytes())
            }
            error("未映射的本地网页资源：${uri.path?.take(120)}")
        }
        val value = uri.toString()
        require(uri.scheme in setOf("http", "https")) { "不支持的网页资源协议" }
        val destination = request.requestHeaders.entries.find { it.key.equals("Sec-Fetch-Dest", true) }?.value
        val accept = request.requestHeaders.entries.find { it.key.equals("Accept", true) }?.value.orEmpty()
        val image = destination == "image" || accept.startsWith("image/") || Regex("\\.(png|jpe?g|webp)$", RegexOption.IGNORE_CASE).containsMatchIn(uri.path.orEmpty()) ||
            environment.images.states.value[character.assetId]?.entries?.any { it.reference.uri == value } == true
        return runBlocking {
            if (image) {
                val entry = environment.images.resolveForWeb(character.assetId, value)
                val file = environment.images.file(character.assetId, entry) ?: error("图片尚未保存")
                result(entry.mediaType ?: "image/png", file.readBytes())
            } else {
                require(!Regex("/gh/MagicalAstrogy/MagVarUpdate(?:@[^/]+)?/artifact/bundle\\.js").containsMatchIn(value)) { "MVU 公共程序必须通过已登记的 QuickJS 装载入口运行" }
                val (entry, original) = environment.resources.resolve(conversationId, character.sourceSha256, value)
                var bytes = original
                val mime = if (entry.mimeType == "application/octet-stream" && (uri.path?.endsWith(".js") == true || uri.path?.endsWith("/+esm") == true)) "application/javascript" else entry.mimeType
                if (mime in setOf("application/javascript", "text/javascript")) bytes = environment.scriptWithRedirectBase(bytes, value, entry.finalUrl)
                if (mime == "text/css" && value != entry.finalUrl) bytes = environment.cssWithRedirectBase(bytes.toString(Charsets.UTF_8), entry.finalUrl).toByteArray()
                result(mime, bytes)
            }
        }
    }

    private fun shellCsp() = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; frame-src $authorOrigin; img-src 'self' data:; base-uri 'none'; form-action 'none'"
    private fun authorCsp() = "default-src 'none'; script-src 'self' https: blob: 'unsafe-inline' 'unsafe-eval'; style-src 'self' https: 'unsafe-inline'; img-src https: http: data: blob:; font-src https: data:; connect-src https:; frame-src 'self' blob:; worker-src 'none'; object-src 'none'; base-uri https:; form-action 'none'"
    private fun result(mime: String, bytes: ByteArray, csp: String? = null, status: Int = 200): WebResourceResponse {
        val headers = mutableMapOf("Access-Control-Allow-Origin" to "*", "X-Content-Type-Options" to "nosniff", "Cache-Control" to "no-store")
        csp?.let { headers["Content-Security-Policy"] = it }
        return WebResourceResponse(mime, "UTF-8", status, if (status == 200) "OK" else "Blocked", headers, ByteArrayInputStream(bytes))
    }
    fun release() {
        if (released) return
        released = true; scope.cancel(); frames.clear(); handled.clear(); inFlight.clear(); frameOperations.clear()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            WebViewCompat.removeWebMessageListener(webView, "PlayerBridge")
        webView.stopLoading()
    }
    companion object { const val ROOT_ORIGIN = "https://player.invalid" }
}
