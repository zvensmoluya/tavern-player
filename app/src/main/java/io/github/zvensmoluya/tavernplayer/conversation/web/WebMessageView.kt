package io.github.zvensmoluya.tavernplayer.conversation.web

import android.graphics.Color
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.zvensmoluya.tavernplayer.conversation.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

@Composable
fun WebMessageView(
    state: ChatUiState,
    environment: BrowserEnvironment?,
    invoke: suspend (BrowserActor, String, String, JsonObject) -> JsonObject,
    onAction: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (environment == null) { Text("当前运行环境未提供网页资源", modifier); return }

    val focusManager = LocalFocusManager.current
    var restart by remember(state.conversationId) { mutableIntStateOf(0) }
    key(state.conversationId, restart) {
        var session by remember { mutableStateOf<BrowserSession?>(null) }
        var view by remember { mutableStateOf<WebView?>(null) }
        var failure by remember { mutableStateOf<String?>(null) }
        var preparing by remember { mutableStateOf(false) }
        var preparationJob by remember { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()
        val currentInvoke by rememberUpdatedState(invoke)
        val currentAction by rememberUpdatedState(onAction)
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event -> when (event) {
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                else -> Unit
            } }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        Column(modifier) {
            TextButton(enabled = !state.busy && !preparing, onClick = {
                preparing = true
                session?.release()
                preparationJob = scope.launch {
                    try {
                        currentInvoke(BrowserActor("native-resource-preparation"), state.browserSnapshot.getValue("revision").jsonPrimitive.content,
                            "resources.reprepare", JsonObject(emptyMap()))
                        restart++
                    } catch (_: CancellationException) { failure = "准备已停止，原版本已保留" }
                    catch (error: Exception) { failure = error.message ?: "资源准备失败，原版本已保留" }
                    finally { preparing = false }
                }
            }) { Text(if (preparing) "正在重新准备网页资源…" else "重新准备网页资源") }
            if (preparing) TextButton(onClick = { preparationJob?.cancel() }) { Text("停止准备") }
            failure?.let {
                Text(it, modifier = Modifier.testTag("webRuntimeNotice"))
                TextButton(onClick = { restart++ }) { Text("重试加载网页") }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize().testTag("webChatContent"),
                factory = { context ->
                    WebView(context).also { web ->
                        view = web; web.setBackgroundColor(Color.TRANSPARENT)
                        // 焦点二选一：作者页面接管输入时收掉原生输入框的焦点，避免两边抢同一个输入法。
                        web.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) focusManager.clearFocus() }
                        val runtime = BrowserSession(web, environment, state,
                            invoke = { actor, revision, method, args -> currentInvoke(actor, revision, method, args) },
                            uiAction = { action, id -> currentAction(action, id) }, onFailure = { failure = it })
                        session = runtime
                        runCatching { runtime.start() }.onFailure { failure = it.message ?: "无法启动网页消息区" }
                    }
                },
                update = { session?.update(state) },
                onRelease = { web -> session?.release(); session = null; view = null; web.destroy() },
            )
        }
    }
}
