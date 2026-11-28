package io.github.zvensmoluya.tavernplayer.conversation.web

import io.github.zvensmoluya.tavernplayer.ui.PlayerIcon
import io.github.zvensmoluya.tavernplayer.ui.PlayerSymbol
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.webkit.WebView
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.zvensmoluya.tavernplayer.conversation.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
fun WebMessageView(
    state: ChatUiState,
    environment: BrowserEnvironment?,
    invoke: suspend (BrowserActor, String, String, JsonObject) -> JsonObject,
    onAction: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
    showChatAvatars: Boolean = false,
    links: BranchLinks = BranchLinks(),
    preparingResources: Boolean = false,
    onPreparationFinished: () -> Unit = {},
) {
    val resolver = LocalContext.current.contentResolver
    val avatars = key(showChatAvatars, state.conversationId, state.character.assetId, state.persona.avatar, environment) {
        val loaded by produceState(ChatAvatars.Empty) {
            if (showChatAvatars) value = withContext(Dispatchers.IO) {
                ChatAvatars(buildMap {
                    resolver.chatThumbnail(environment?.avatarFile(state.character.assetId)?.path)?.let { put(MessageRole.ASSISTANT, it) }
                    resolver.chatThumbnail(state.persona.avatar)?.let { put(MessageRole.USER, it) }
                })
            }
        }
        loaded
    }
    if (state.readOnlyReason != null || environment == null) {
        SavedMessages(state, avatars, modifier, links, onAction)
        return
    }

    var restart by remember(state.conversationId) { mutableIntStateOf(0) }
    key(state.conversationId, restart) {
        var session by remember { mutableStateOf<BrowserSession?>(null) }
        var view by remember { mutableStateOf<WebView?>(null) }
        var showSavedText by remember { mutableStateOf(false) }
        var failure by remember { mutableStateOf<String?>(null) }
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
            if (preparingResources) {
                LaunchedEffect(Unit) {
                    failure = null
                    session?.release()
                    try {
                        currentInvoke(BrowserActor("native-resource-preparation"), state.browserSnapshot.getValue("revision").jsonPrimitive.content,
                            "resources.reprepare", JsonObject(emptyMap()))
                        restart++
                    } catch (_: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        failure = "准备已停止，原版本已保留"
                    } catch (error: Exception) {
                        failure = error.message ?: "资源准备失败，原版本已保留"
                    }
                    onPreparationFinished()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("正在重新准备网页资源…")
                    TextButton(onClick = {
                        failure = "准备已停止，原版本已保留"
                        onPreparationFinished()
                    }) { Text("停止准备") }
                }
            } else failure?.let {
                Text(it, modifier = Modifier.testTag("webRuntimeNotice"))
                TextButton(onClick = { restart++ }) { Text("重试加载网页") }
                if (!showSavedText) TextButton(onClick = { showSavedText = true }) { Text("阅读已保存的文字") }
            }
            if (showSavedText) SavedMessages(state, avatars, Modifier.fillMaxSize(), links, onAction)
            else AndroidView(
                modifier = Modifier.fillMaxSize().testTag("webChatContent"),
                factory = { context ->
                    WebView(context).also { web ->
                        view = web; web.setBackgroundColor(Color.TRANSPARENT)
                        val runtime = BrowserSession(web, environment, state,
                            invoke = { actor, revision, method, args -> currentInvoke(actor, revision, method, args) },
                            uiAction = { action, id -> currentAction(action, id) }, onFailure = { failure = it })
                        session = runtime
                        runCatching { runtime.start() }.onFailure {
                            failure = it.message ?: "无法启动网页消息区"
                            showSavedText = true
                        }
                    }
                },
                update = { session?.update(state, avatars.webSources, links) },
                onRelease = { web -> session?.release(); session = null; view = null; web.destroy() },
            )
        }
    }
}

@Composable
private fun SavedMessages(state: ChatUiState, avatars: ChatAvatars, modifier: Modifier, links: BranchLinks,
    onAction: (String, String?) -> Unit) {
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(state.conversationId, links.anchor) {
        val index = state.messages.indexOfFirst { it.turnId == links.anchor }
        if (index >= 0) list.scrollToItem(index)
    }
    LazyColumn(modifier, state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items(state.messages, key = { it.message.id }) { item ->
            val origin = links.current?.branch?.takeIf { it.localTurnId == item.turnId }
            @Composable fun Origin() {
                TextButton(enabled = !state.busy, onClick = { onAction("origin", item.turnId) }) {
                    Text("从《${links.parentTitle.orEmpty()}》分出 · 查看原处")
                }
            }
            if (origin?.rewritten == true) Origin()
            val player = item.message.role == MessageRole.USER
            Box(Modifier.fillMaxWidth().padding(start = if (player) 24.dp else 0.dp),
                contentAlignment = if (player) Alignment.TopEnd else Alignment.TopStart) {
                val bubble = if (player) Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp) else Modifier
                Column(bubble, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        avatars.images[item.message.role]?.let {
                            Image(it.asImageBitmap(), contentDescription = null, Modifier.size(24.dp).clip(CircleShape))
                        }
                        Text(item.message.authorName, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.readOnlyReason == null && item.message.role != MessageRole.SYSTEM) {
                            IconButton(enabled = !state.busy, onClick = { onAction("edit", item.message.id) }) {
                                PlayerIcon(PlayerSymbol.EDIT, "编辑消息", Modifier.size(16.dp))
                            }
                        }
                    }
                    Text(item.message.content, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (origin?.rewritten == false) Origin()
            val paths = links.children.count { it.branch?.parentTurnId == item.turnId }
            if (paths > 0) TextButton(enabled = !state.busy, onClick = { onAction("paths", item.turnId) }) {
                Text("另有 $paths 种走法")
            }
        }
    }
}
