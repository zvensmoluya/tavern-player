package io.github.zvensmoluya.tavernplayer.conversation

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import io.github.zvensmoluya.tavernplayer.ui.ScrollableBottomSheet
import io.github.zvensmoluya.tavernplayer.ui.PlayerIcon
import io.github.zvensmoluya.tavernplayer.ui.PlayerSymbol
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.github.zvensmoluya.tavernplayer.conversation.storage.ConversationSummary
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.zvensmoluya.tavernplayer.characters.WorldBookReaderScreen
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.PresetAsset
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationParameter
import io.github.zvensmoluya.tavernplayer.presets.PresetViewModel

data class BranchLinks(
    val current: ConversationSummary? = null,
    val parentTitle: String? = null,
    val children: List<ConversationSummary> = emptyList(),
    val anchor: String? = null,
)

@Composable
fun ChatRoute(
    viewModel: ChatViewModel,
    presetViewModel: PresetViewModel,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenGlobalWorldBooks: () -> Unit = {},
    showChatAvatars: Boolean = false,
    onRename: () -> Unit = {},
    conversationTitle: String? = null,
    links: BranchLinks = BranchLinks(),
    onVisit: (String, String, String) -> Unit = { _, _, _ -> },
    focusInput: Boolean = false,
    onFocused: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val presetState by presetViewModel.uiState.collectAsState()
    ChatScreen(
        state = state,
        conversationTitle = conversationTitle ?: state.character.name,
        links = links, focusInput = focusInput, onFocused = onFocused,
        presets = presetState.presets,
        showChatAvatars = showChatAvatars,
        browserEnvironment = viewModel.browserEnvironment,
        browserInvoke = viewModel::invokeBrowser,
        actions = ChatScreenActions(
            updateInput = viewModel::updateInput,
            send = { if (state.selectedConnection == null) onOpenModels() else viewModel.send() },
            cancel = viewModel::cancel,
            retry = viewModel::retry,
            retrySave = viewModel::retrySave,
            regenerate = viewModel::regenerate,
            editMessage = viewModel::editMessage,
            branch = viewModel::branchFromMessage,
            visit = onVisit,
            previousVariant = viewModel::previousVariant,
            nextVariant = viewModel::nextVariant,
            selectConnection = viewModel::selectConnection,
            reset = viewModel::resetConversation,
            back = onBack,
            rename = onRename,
            openModels = onOpenModels,
            selectPreset = presetViewModel::activate,
            openPresets = onOpenPresets,
            openGlobalWorldBooks = onOpenGlobalWorldBooks,
            onWorldBookEntryMode = viewModel::setWorldBookEntryMode,
            onWorldBookEntryContent = viewModel::setWorldBookEntryContent,
            readSavedPlan = viewModel::readSavedPlan,
        ),
    )
}

data class ChatScreenActions(
    val updateInput: (String) -> Unit,
    val send: () -> Unit,
    val cancel: () -> Unit,
    val retry: () -> Unit,
    val selectConnection: (String) -> Unit,
    val reset: () -> Unit,
    val openModels: () -> Unit,
    val retrySave: () -> Unit = {},
    val regenerate: () -> Unit = {},
    val previousVariant: () -> Unit = {},
    val nextVariant: () -> Unit = {},
    val back: () -> Unit = {},
    val rename: () -> Unit = {},
    val selectPreset: (String) -> Unit = {},
    val openPresets: () -> Unit = {},
    val openGlobalWorldBooks: () -> Unit = {},
    val editMessage: (messageId: String, sourceText: String, mode: MessageEditMode) -> Unit = { _, _, _ -> },
    val onWorldBookEntryMode: (String, String, WorldBookEntryMode?) -> Unit = { _, _, _ -> },
    val onWorldBookEntryContent: (String, String, String, () -> Unit) -> Unit = { _, _, _, _ -> },
    val branch: (String) -> Unit = {},
    val visit: (String, String, String) -> Unit = { _, _, _ -> },
    val readSavedPlan: suspend (String) -> GenerationPlan? = { null },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    actions: ChatScreenActions,
    presets: List<PresetAsset> = emptyList(),
    browserEnvironment: io.github.zvensmoluya.tavernplayer.conversation.web.BrowserEnvironment? = null,
    browserInvoke: suspend (BrowserActor, String, String, kotlinx.serialization.json.JsonObject) -> kotlinx.serialization.json.JsonObject = { _, _, _, _ -> error("网页宿主未连接") },
    // 底部条自己让开系统栏与键盘，Scaffold 便以它的高度把消息区压到键盘之上；测试可注入固定值断言这一点。
    bottomBarInsets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
    showChatAvatars: Boolean = false,
    conversationTitle: String = state.character.name,
    links: BranchLinks = BranchLinks(),
    focusInput: Boolean = false,
    onFocused: () -> Unit = {},
) {
    var modelPickerVisible by remember { mutableStateOf(false) }
    BackHandler { if (!state.busy) actions.back() }
    var presetPickerVisible by remember { mutableStateOf(false) }
    var traceVisible by remember(state.conversationId) { mutableStateOf(false) }
    var moreMenu by remember(state.conversationId) { mutableStateOf(false) }
    var readingPreview by rememberSaveable(state.conversationId) { mutableStateOf(false) }
    var preparingResources by remember(state.conversationId, browserEnvironment, state.readOnlyReason) { mutableStateOf(false) }
    var worldBookMenu by remember { mutableStateOf(false) }
    var worldBookVisible by rememberSaveable(state.conversationId) { mutableStateOf(false) }
    var editingMessageId by remember(state.conversationId) { mutableStateOf<String?>(null) }
    var pathsTurn by remember(state.conversationId) { mutableStateOf<String?>(null) }
    val modelFeedback = remember { SnackbarHostState() }
    var requestedConnection by remember(state.conversationId) { mutableStateOf<String?>(null) }
    LaunchedEffect(requestedConnection, state.selectedConnectionId) {
        val id = requestedConnection ?: return@LaunchedEffect
        if (state.selectedConnectionId == id) {
            state.readyConnections.find { it.id == id }?.let { modelFeedback.showSnackbar("已选用 ${it.selectedModel}") }
            requestedConnection = null
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(modelFeedback) },
        topBar = {
            TopAppBar(
                title = {
                    Text(state.character.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    TextButton(
                        modifier = Modifier.testTag("backToCharacter"),
                        enabled = !state.busy,
                        onClick = actions.back,
                    ) { Text("返回") }
                },
                actions = {
                    Box {
                        TextButton(modifier = Modifier.testTag("openWorldBook"), onClick = { worldBookMenu = true }) { Text("世界书", maxLines = 1) }
                        DropdownMenu(expanded = worldBookMenu, onDismissRequest = { worldBookMenu = false }) {
                            DropdownMenuItem(text = { Text("角色世界书") }, onClick = { worldBookMenu = false; worldBookVisible = true })
                            DropdownMenuItem(text = { Text("全局世界书") }, onClick = { worldBookMenu = false; actions.openGlobalWorldBooks() }, enabled = !state.busy)
                        }
                    }
                    Box {
                        IconButton(onClick = { moreMenu = true }, modifier = Modifier.testTag("chatMore")) {
                            PlayerIcon(PlayerSymbol.MORE, description = "更多")
                        }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(
                                text = { ChatMenuLabel("模型", state.selectedConnection?.selectedModel ?: "未选择") },
                                modifier = Modifier.testTag("chooseModel"), enabled = !state.busy,
                                onClick = { moreMenu = false; modelPickerVisible = true },
                            )
                            DropdownMenuItem(
                                text = { ChatMenuLabel("预设", state.activePresetName) },
                                modifier = Modifier.testTag("choosePreset"), enabled = !state.busy,
                                onClick = { moreMenu = false; presetPickerVisible = true },
                            )
                            if (state.conversationId != null) {
                                DropdownMenuItem(text = { Text("更换对话名") },
                                    onClick = { moreMenu = false; actions.rename() })
                                DropdownMenuItem(text = { Text("导出阅读稿") }, enabled = !state.loadingConversation && state.messages.isNotEmpty(),
                                    onClick = { moreMenu = false; readingPreview = true })
                            }
                            val origin = links.current?.branch
                            if (origin != null && state.messages.none { it.turnId == origin.localTurnId }) {
                                DropdownMenuItem(text = { Text("查看分支来源") }, enabled = !state.busy, onClick = {
                                    moreMenu = false
                                    actions.visit(origin.parentId, origin.parentTurnId, state.messages.lastOrNull()?.turnId.orEmpty())
                                })
                            }
                            if (state.lastTrace != null) {
                                DropdownMenuItem(text = { Text("上下文") }, modifier = Modifier.testTag("openTrace"),
                                    enabled = !state.busy, onClick = { moreMenu = false; traceVisible = true })
                            }
                            if (browserEnvironment != null && state.readOnlyReason == null) {
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("重新准备网页资源") }, enabled = !state.busy && !preparingResources,
                                    onClick = { moreMenu = false; preparingResources = true })
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            // 输入栏在这里自己让开底部系统栏与键盘：底部条被抬高，Scaffold 随之把消息区（WebView）压缩到键盘之上。
            Column(modifier = Modifier.windowInsetsPadding(bottomBarInsets)) {
                if (state.storageFailed) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("对话尚未保存", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = actions.retrySave, modifier = Modifier.testTag("retrySave")) { Text("重试保存") }
                    }
                }
                if (state.readOnlyReason == null) ChatComposer(state, actions, focusInput, onFocused)
                else Text("仅可阅读历史 · ${state.readOnlyReason}", modifier = Modifier.padding(12.dp))
            }
        },
    ) { padding ->
        io.github.zvensmoluya.tavernplayer.conversation.web.WebMessageView(state, browserEnvironment, browserInvoke,
            showChatAvatars = showChatAvatars,
            links = links,
            preparingResources = preparingResources,
            onPreparationFinished = { preparingResources = false },
            onAction = { action, id -> when (action) {
                "edit" -> editingMessageId = id
                "paths" -> pathsTurn = id
                "origin" -> links.current?.branch?.let { actions.visit(it.parentId, it.parentTurnId, it.localTurnId) }
                "retry" -> actions.retry()
                "previous" -> actions.previousVariant()
                "next" -> actions.nextVariant()
                "regenerate" -> actions.regenerate()
            } }, modifier = Modifier.fillMaxSize().padding(padding).testTag("webMessageArea"))
    }
    if (readingPreview) {
        io.github.zvensmoluya.tavernplayer.conversation.reading.ReadingExportDialog(
            conversationTitle, state.character.name, state.messages, onClose = { readingPreview = false })
    }
    pathsTurn?.let { anchor ->
        ScrollableBottomSheet(onDismissRequest = { pathsTurn = null }) {
            Text("从这里分出的对话", style = MaterialTheme.typography.titleMedium)
            links.children.filter { it.branch?.parentTurnId == anchor }.forEach { child ->
                TextButton(enabled = !state.busy, onClick = {
                    pathsTurn = null; actions.visit(child.id, requireNotNull(child.branch).localTurnId, anchor)
                }) { Text(child.displayTitle(state.character.name)) }
            }
        }
    }
    editingMessageId?.let { id ->
        state.messages.firstOrNull { it.message.id == id }?.let { target ->
            MessageEditor(target.message, enabled = !state.busy, onDismiss = { editingMessageId = null },
                laterCount = state.messages.lastIndex - state.messages.indexOf(target),
                otherVariants = target.variantCount - 1,
                onRestart = { text ->
                    actions.editMessage(id, text, MessageEditMode.RESTART)
                    editingMessageId = null
                },
                onSave = { text ->
                    actions.editMessage(id, text, MessageEditMode.TEXT_ONLY)
                    editingMessageId = null
                },
                onBranch = { text ->
                    if (text == target.message.sourceText) actions.branch(id)
                    else actions.editMessage(id, text, MessageEditMode.BRANCH)
                    editingMessageId = null
                })
        }
    }
    if (modelPickerVisible) {
        ModelPicker(
            connections = state.readyConnections,
            selectedId = state.selectedConnectionId,
            onSelect = {
                requestedConnection = it
                actions.selectConnection(it)
                modelPickerVisible = false
            },
            onManage = {
                modelPickerVisible = false
                actions.openModels()
            },
            onDismiss = { modelPickerVisible = false },
        )
    }
    if (presetPickerVisible) {
        PresetPicker(
            presets = presets,
            selectedId = state.activePresetId,
            enabled = !state.busy,
            onSelect = {
                actions.selectPreset(it)
                presetPickerVisible = false
            },
            onManage = {
                presetPickerVisible = false
                actions.openPresets()
            },
            onDismiss = { presetPickerVisible = false },
        )
    }
    if (traceVisible) {
        state.lastTrace?.let { trace ->
            TraceSheet(trace, actions.readSavedPlan, onDismiss = { traceVisible = false })
        }
    }
    if (worldBookVisible) {
        // 全屏阅读保留底下的会话 WebView，避免打开资料时销毁作者运行环境。
        Dialog(onDismissRequest = { worldBookVisible = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            WorldBookReaderScreen(
                readerId = "conversation-${state.conversationId}", characterName = state.character.name,
                books = state.character.worldBooks, sessionState = state.worldBookState,
                busy = state.busy || state.browserGenerating || state.readOnlyReason != null, message = state.worldBookMessage,
                lastInjections = state.lastTrace?.plan?.worldBookInjections,
                onBack = { worldBookVisible = false }, onEntryMode = actions.onWorldBookEntryMode,
                onEntryContent = actions.onWorldBookEntryContent,
            )
        }
    }

}

@Composable
private fun MessageEditor(
    message: ConversationMessage,
    enabled: Boolean,
    onDismiss: () -> Unit,
    laterCount: Int,
    otherVariants: Int,
    onRestart: (String) -> Unit,
    onSave: (String) -> Unit,
    onBranch: (String) -> Unit,
) {
    var text by remember(message.id) { mutableStateOf(message.sourceText) }
    var confirmingRestart by remember(message.id) { mutableStateOf(false) }
    if (confirmingRestart) {
        val removed = listOfNotNull(
            "后续 ${laterCount} 条消息".takeIf { laterCount > 0 },
            "这条消息的 ${otherVariants} 个其他候选".takeIf { otherVariants > 0 },
        ).joinToString("和")
        AlertDialog(onDismissRequest = { confirmingRestart = false },
            title = { Text("在当前对话重来？") },
            text = { Text("将移除$removed，并从编辑框中的这句话继续。") },
            confirmButton = { TextButton(enabled = enabled, onClick = { onRestart(text) }) { Text("确认重来") } },
            dismissButton = { TextButton(onClick = { confirmingRestart = false }) { Text("返回编辑") } })
        return
    }
    val changed = text != message.sourceText
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 540.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = colors.surfaceContainerLow,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("编辑消息", style = MaterialTheme.typography.titleLarge)
                        Text(message.authorName, style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onDismiss) { PlayerIcon(PlayerSymbol.CLOSE, "取消编辑") }
                }
                TextField(
                    value = text, onValueChange = { text = it }, readOnly = !enabled,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("webMessageEditor"),
                    minLines = 4, maxLines = 12, textStyle = MaterialTheme.typography.bodyLarge,
                    shape = MaterialTheme.shapes.medium,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = colors.surfaceContainerHighest,
                        unfocusedContainerColor = colors.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                val actionPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        if (laterCount > 0 || otherVariants > 0) confirmingRestart = true else onRestart(text)
                    }, modifier = Modifier.weight(1f), contentPadding = actionPadding,
                        enabled = enabled && text.isNotBlank()) { Text("原地重来") }
                    OutlinedButton(contentPadding = actionPadding, onClick = { onBranch(text) }, modifier = Modifier.weight(1f),
                        enabled = enabled && (!changed || text.isNotBlank())) { Text("另开分支") }
                    Button(contentPadding = actionPadding, onClick = { onSave(text) }, modifier = Modifier.weight(1f),
                        enabled = enabled && changed && text.isNotBlank()) { Text("保存文字") }
                }
            }
        }
    }
}

@Composable
private fun ChatMenuLabel(label: String, value: String) {
    Column {
        Text(label)
        if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChatComposer(state: ChatUiState, actions: ChatScreenActions, focusInput: Boolean, onFocused: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusInput, state.busy) {
        if (focusInput && !state.busy) { focus.requestFocus(); onFocused() }
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!state.loadingConnections && state.readyConnections.isEmpty()) {
            Text(
                "尚未配置模型；输入会保留，发送时可前往配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 发送/停止与输入框同一行：输入区最多长到 5 行，键盘占去高度时按钮也不会被挤出可见区。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = actions.updateInput,
                modifier = Modifier.weight(1f).focusRequester(focus).testTag("chatInput"),
                enabled = !state.busy,
                label = { Text("说点什么") },
                minLines = 1,
                maxLines = 5,
            )
            if (state.generationPhase != GenerationPhase.IDLE) {
                OutlinedButton(
                    modifier = Modifier.testTag("cancelGeneration"),
                    onClick = actions.cancel,
                    enabled = state.generationPhase == GenerationPhase.ACTIVE,
                ) {
                    Text(when (state.generationPhase) {
                        GenerationPhase.STOPPING -> "正在停止…"
                        GenerationPhase.FINISHING -> "正在保存…"
                        else -> "停止"
                    })
                }
            } else {
                Button(
                    modifier = Modifier.testTag("sendMessage"),
                    enabled = state.input.isNotBlank() && state.canRequestTurn,
                    onClick = actions.send,
                ) { Text("发送") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(
    connections: List<StoredConnection>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ScrollableBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("选择模型", style = MaterialTheme.typography.titleLarge)
            if (connections.isEmpty()) {
                Text(
                    "还没有可用连接。请先添加服务并选择模型。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(connections, key = StoredConnection::id) { connection ->
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("connection-${connection.id}"),
                        onClick = { onSelect(connection.id) },
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                connection.selectedModel,
                                fontWeight = if (connection.id == selectedId) FontWeight.Bold else FontWeight.Normal,
                            )
                            Text(
                                if (connection.id == selectedId) "${connection.name} · 当前使用" else connection.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
            TextButton(onClick = onManage) { Text("管理模型连接") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetPicker(
    presets: List<PresetAsset>,
    selectedId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ScrollableBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("选择预设", style = MaterialTheme.typography.titleLarge)
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false).testTag("presetChoices"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(presets, key = PresetAsset::id) { preset ->
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("quick-preset-${preset.id}"),
                        enabled = enabled,
                        onClick = { onSelect(preset.id) },
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                preset.name,
                                fontWeight = if (preset.id == selectedId) FontWeight.Bold else FontWeight.Normal,
                            )
                            Text(
                                "${preset.prompts.count { !it.marker }} 个快速项 · ${preset.regexScripts.size} Regex · " +
                                    if (preset.generationSettings.isEnabled(PresetGenerationParameter.OUTPUT_LIMIT)) {
                                        "回复 ${preset.generationSettings.maxOutputTokens}"
                                    } else {
                                        "回复上限关闭"
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
            TextButton(enabled = enabled, onClick = onManage) { Text("管理预设") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TraceSheet(summary: GenerationTraceState, readSavedPlan: suspend (String) -> GenerationPlan?, onDismiss: () -> Unit) {
    val variantId = summary.plan?.deferredPartsVariantId
    var details by remember(variantId) { mutableStateOf<GenerationPlan?>(null) }
    var failed by remember(variantId) { mutableStateOf(false) }
    LaunchedEffect(variantId) {
        if (variantId != null) {
            try {
                details = readSavedPlan(variantId)
                failed = details == null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            }
        }
    }
    val trace = details?.let { summary.copy(plan = it, compileTrace = it.trace, compileDiagnostics = it.diagnostics) } ?: summary
    ScrollableBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().testTag("traceSheet"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("最近一轮上下文", style = MaterialTheme.typography.titleLarge) }
            if (variantId != null && details == null) {
                item { Text(if (failed) "无法读取生成明细，请重新打开上下文" else "正在读取生成明细…") }
            }
            trace.compileDiagnostics.forEachIndexed { index, diagnostic ->
                item("diagnostic-$index") {
                    TraceBlock(
                        title = "${diagnostic.severity}: ${diagnostic.code}",
                        content = diagnostic.message,
                    )
                }
            }
            trace.plan?.tokenAccounting?.let { accounting ->
                item("token-accounting") {
                    TraceBlock(
                        title = "Context 与 Token",
                        content = "context=${accounting.contextLimit ?: "未声明"} input=${accounting.inputTokens} " +
                            "reservedOutput=${accounting.reservedOutputTokens} quality=${accounting.quality} " +
                            "counter=${accounting.tokenizer}",
                    )
                }
            }
            trace.plan?.activatedWorldBookEntries?.takeIf(List<String>::isNotEmpty)?.let { entries ->
                item("world-book-activation") {
                    TraceBlock("激活的 World Book 条目", entries.joinToString("\n"))
                }
            }
            trace.compileTrace.forEachIndexed { index, entry ->
                item("compile-trace-$index") {
                    TraceBlock(
                        title = "${entry.stage} · ${entry.decision}",
                        content = entry.sourceIds.joinToString().ifBlank { "（无 source id）" },
                    )
                }
            }
            trace.plan?.messages?.forEachIndexed { index, message ->
                item("message-$index") {
                    TraceBlock(
                        title = "${message.role} · ${message.origin.sourceIds.joinToString()}",
                        content = message.content,
                    )
                }
            }
            trace.providerPreview?.let { preview ->
                item("provider") {
                    TraceBlock(
                        title = "Provider 映射",
                        content = buildString {
                            append("${preview.protocol.displayName()} · ${preview.model}\n")
                            append(
                                "store=false · hostedState=false · maxOutput=" +
                                    (preview.maxOutputTokens?.toString() ?: "未携带"),
                            )
                            preview.systemInstruction?.let { append("\n\nsystemInstruction:\n$it") }
                        },
                    )
                }
                preview.messages.forEachIndexed { index, message ->
                    item("provider-message-$index") {
                        TraceBlock(
                            title = message.role + if (message.hasThoughtSignature) " · signature present" else "",
                            content = message.content,
                        )
                    }
                }
            }
            if (trace.streamDiagnostics.isNotEmpty()) {
                item("stream-diagnostics") {
                    TraceBlock("Stream diagnostics", trace.streamDiagnostics.joinToString("\n"))
                }
            }
            trace.usage?.let { usage ->
                item("usage") {
                    TraceBlock(
                        "Usage",
                        "input=${usage.inputTokens} output=${usage.outputTokens} total=${usage.totalTokens} " +
                            "cached=${usage.cachedTokens} reasoning=${usage.reasoningTokens}",
                    )
                }
            }
            trace.finishReason?.let { item("finish") { TraceBlock("Finish reason", it) } }
            trace.error?.let { item("error") { TraceBlock("Error", it) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun TraceBlock(title: String, content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(content, style = MaterialTheme.typography.bodySmall)
    }
}

private fun io.github.zvensmoluya.modelgateway.ModelProtocol.displayName(): String = when (this) {
    io.github.zvensmoluya.modelgateway.ModelProtocol.OPENAI_RESPONSES -> "OpenAI Responses"
    io.github.zvensmoluya.modelgateway.ModelProtocol.OPENAI_CHAT_COMPLETIONS -> "OpenAI Chat Completions"
    io.github.zvensmoluya.modelgateway.ModelProtocol.ANTHROPIC_MESSAGES -> "Anthropic Messages"
    io.github.zvensmoluya.modelgateway.ModelProtocol.GEMINI_INTERACTIONS -> "Gemini Interactions"
    io.github.zvensmoluya.modelgateway.ModelProtocol.GEMINI_GENERATE_CONTENT -> "Gemini GenerateContent"
}
