package io.github.zvensmoluya.tavernplayer.conversation

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import io.github.zvensmoluya.tavernplayer.content.NativeGuideReader
import io.github.zvensmoluya.tavernplayer.content.NativeStatusDisplay
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationParameter
import io.github.zvensmoluya.tavernplayer.presets.PresetViewModel

@Composable
fun ChatRoute(
    viewModel: ChatViewModel,
    presetViewModel: PresetViewModel,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenGlobalWorldBooks: () -> Unit = {},
    resolveAssetPath: (characterId: String, assetId: String) -> String? = { _, _ -> null },
) {
    val state by viewModel.uiState.collectAsState()
    val presetState by presetViewModel.uiState.collectAsState()
    ChatScreen(
        state = state,
        resolveAssetPath = resolveAssetPath,
        presets = presetState.presets,
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
            previousVariant = viewModel::previousVariant,
            nextVariant = viewModel::nextVariant,
            selectOpening = viewModel::selectOpening,
            selectConnection = viewModel::selectConnection,
            reset = viewModel::resetConversation,
            back = onBack,
            openModels = onOpenModels,
            selectPreset = presetViewModel::activate,
            openPresets = onOpenPresets,
            openGlobalWorldBooks = onOpenGlobalWorldBooks,
            submitNativeForm = viewModel::submitNativeForm,
            invokeNativeAction = viewModel::invokeNativeAction,
            cancelNativeAction = viewModel::cancelNativeAction,
            previewPlayerChoice = viewModel::previewPlayerChoice,
            confirmPlayerChoice = viewModel::confirmPlayerChoice,
            cancelPlayerChoice = viewModel::cancelPlayerChoice,
            refreshMemories = viewModel::refreshMemories,
            onWorldBookEntryMode = viewModel::setWorldBookEntryMode,
            onWorldBookEntryContent = viewModel::setWorldBookEntryContent,
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
    val selectOpening: (Int) -> Unit = {},
    val back: () -> Unit = {},
    val selectPreset: (String) -> Unit = {},
    val openPresets: () -> Unit = {},
    val openGlobalWorldBooks: () -> Unit = {},
    val editMessage: (messageId: String, sourceText: String, mode: MessageEditMode) -> Unit = { _, _, _ -> },
    val invokeNativeAction: (NativeSurfaceInvocation) -> Unit = {},
    val cancelNativeAction: () -> Unit = {},
    val submitNativeForm: (formId: String, values: Map<String, List<String>>) -> Unit = { _, _ -> },
    val previewPlayerChoice: (String) -> Unit = {},
    val confirmPlayerChoice: () -> Unit = {},
    val cancelPlayerChoice: () -> Unit = {},
    val refreshMemories: () -> Unit = {},
    val onWorldBookEntryMode: (String, String, WorldBookEntryMode?) -> Unit = { _, _, _ -> },
    val onWorldBookEntryContent: (String, String, String, () -> Unit) -> Unit = { _, _, _, _ -> },
)

private data class PendingMessageEdit(
    val messageId: String,
    val sourceText: String,
    val regenerate: Boolean,
    val removedMessageCount: Int,
    val discardedVariantCount: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    actions: ChatScreenActions,
    presets: List<PresetAsset> = emptyList(),
    resolveAssetPath: (characterId: String, assetId: String) -> String? = { _, _ -> null },
    browserEnvironment: io.github.zvensmoluya.tavernplayer.conversation.web.BrowserEnvironment? = null,
    browserInvoke: suspend (BrowserActor, String, String, kotlinx.serialization.json.JsonObject) -> kotlinx.serialization.json.JsonObject = { _, _, _, _ -> error("网页宿主未连接") },
    // 底部条自己让开系统栏与键盘，Scaffold 便以它的高度把消息区压到键盘之上；测试可注入固定值断言这一点。
    bottomBarInsets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
) {
    var modelPickerVisible by remember { mutableStateOf(false) }
    BackHandler { if (!state.busy) actions.back() }
    var presetPickerVisible by remember { mutableStateOf(false) }
    var traceVisible by remember { mutableStateOf(false) }
    var nativeDetailsVisible by remember(state.conversationId) { mutableStateOf(false) }
    var nativeGuideVisible by remember(state.conversationId) { mutableStateOf(false) }
    var worldBookMenu by remember { mutableStateOf(false) }
    var worldBookVisible by rememberSaveable(state.conversationId) { mutableStateOf(false) }
    var historicalStateMessageId by remember(state.conversationId) { mutableStateOf<String?>(null) }
    val hasNativeGuide = state.character.nativeAdaptation?.guide != null
    val nativeGuide = remember(state.character) {
        NativeGuideReader.read(state.character.nativeAdaptation, state.character.regexScripts, state.character.sourceSha256)
    }
    var editingMessageId by remember(state.conversationId) { mutableStateOf<String?>(null) }
    var editingText by remember(state.conversationId) { mutableStateOf("") }
    var pendingMessageEdit by remember(state.conversationId) { mutableStateOf<PendingMessageEdit?>(null) }
    val messageListState = rememberLazyListState()
    val latestMessage = state.messages.lastOrNull()
    val scrollAnchorIndex = state.messages.size +
        (if (state.message != null) 1 else 0) +
        (if (state.retryAvailable) 1 else 0) +
        (if ((state.regenerateAvailable || state.variantNavigationAvailable) && !state.busy) 1 else 0)
    LaunchedEffect(latestMessage?.message?.id, latestMessage?.status, scrollAnchorIndex, state.message) {
        if (latestMessage != null) {
            val openingForm = state.messages.size == 1 && latestMessage.nativeForms.isNotEmpty() && !latestMessage.setupClosed
            messageListState.scrollToItem(if (openingForm) 0 else scrollAnchorIndex)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.character.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        state.selectedConnection?.let {
                            Text(
                                it.selectedModel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
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
                    TextButton(
                        modifier = Modifier.testTag("choosePreset"),
                        enabled = !state.busy,
                        onClick = { presetPickerVisible = true },
                    ) { Text("预设", maxLines = 1) }
                    if (state.lastTrace != null) {
                        TextButton(
                            modifier = Modifier.testTag("openTrace"),
                            enabled = !state.busy,
                            onClick = { traceVisible = true },
                        ) { Text("上下文") }
                    }
                    TextButton(
                        modifier = Modifier.testTag("chooseModel"),
                        enabled = !state.busy,
                        onClick = { modelPickerVisible = true },
                    ) { Text("模型") }
                },
            )
        },
        bottomBar = {
            // 输入栏在这里自己让开底部系统栏与键盘：底部条被抬高，Scaffold 随之把消息区（WebView）压缩到键盘之上。
            Column(modifier = Modifier.windowInsetsPadding(bottomBarInsets)) {
                if (state.character.nativeAdaptation?.script != null || state.nativeStatus != null || state.nativeScenes.isNotEmpty() || state.nativeCollections.isNotEmpty() || state.nativeChoices.isNotEmpty() || hasNativeGuide || state.character.nativeAdaptation?.memories.orEmpty().isNotEmpty()) {
                    TextButton(
                        onClick = { nativeDetailsVisible = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("openNativeDetails"),
                    ) {
                        Text(
                            state.nativeStatus?.items?.take(3)?.joinToString("  ·  ") { item ->
                                "${item.label} ${NativeStatusDisplay.value(item, state.nativeState).text}"
                            }.orEmpty().ifBlank { "查看场景、资料与选择" },
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text("  详情", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (state.storageFailed) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("对话尚未保存", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = actions.retrySave, modifier = Modifier.testTag("retrySave")) { Text("重试保存") }
                    }
                }
                ChatComposer(state, actions)
            }
        },
    ) { padding ->
        if (state.executionMode == ConversationExecutionMode.BROWSER) {
            io.github.zvensmoluya.tavernplayer.conversation.web.WebMessageView(state, browserEnvironment, browserInvoke,
                onAction = { action, id -> when (action) {
                    "edit" -> state.messages.find { it.message.id == id }?.let { message ->
                        editingMessageId = message.message.id; editingText = message.message.sourceText
                    }
                    "retry" -> actions.retry()
                    "previous" -> actions.previousVariant()
                    "next" -> actions.nextVariant()
                    "regenerate" -> actions.regenerate()
                } }, modifier = Modifier.fillMaxSize().padding(padding))
        } else LazyColumn(
            state = messageListState,
            modifier = Modifier.fillMaxSize().padding(padding).testTag("chatContent"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.openingChoices.isNotEmpty()) {
                item("native-opening-selector") {
                    NativeOpeningSelector(state.openingChoices, !state.busy, actions.selectOpening,
                        onGuide = if (hasNativeGuide) ({ nativeGuideVisible = true }) else null)
                }
            }
            itemsIndexed(state.messages, key = { _, item -> item.message.id }) { index, message ->
                MessageBubble(
                    state = message,
                    onSubmitNativeForm = actions.submitNativeForm,
                    onViewState = { historicalStateMessageId = message.message.id },
                    editable = !state.busy,
                    editingText = editingText.takeIf { editingMessageId == message.message.id },
                    onStartEdit = {
                        editingMessageId = message.message.id
                        editingText = message.message.sourceText
                    },
                    onEditTextChange = { editingText = it },
                    onCancelEdit = {
                        editingMessageId = null
                        editingText = ""
                    },
                    onSaveText = {
                        actions.editMessage(message.message.id, editingText, MessageEditMode.TEXT_ONLY)
                        editingMessageId = null
                        editingText = ""
                    },
                    onRestart = {
                        val edit = PendingMessageEdit(
                            messageId = message.message.id,
                            sourceText = editingText,
                            regenerate = message.message.role == MessageRole.USER,
                            removedMessageCount = state.messages.lastIndex - index,
                            discardedVariantCount = message.variantCount - 1,
                        )
                        if (edit.removedMessageCount > 0 || edit.discardedVariantCount > 0) {
                            pendingMessageEdit = edit
                        } else {
                            actions.editMessage(edit.messageId, edit.sourceText, MessageEditMode.RESTART)
                            editingMessageId = null
                            editingText = ""
                        }
                    },
                )
            }
            state.message?.let { notice ->
                item("notice") {
                    Text(
                        notice,
                        modifier = Modifier.fillMaxWidth().testTag("chatNotice"),
                        color = if (state.retryAvailable) MaterialTheme.colorScheme.error else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (state.retryAvailable && !state.storageFailed) {
                item("retry") {
                    OutlinedButton(
                        modifier = Modifier.testTag("retryGeneration"),
                        onClick = actions.retry,
                    ) { Text("重试这一轮") }
                }
            }
            if ((state.regenerateAvailable || state.variantNavigationAvailable) && !state.busy) {
                item("assistant-variants") {
                    val last = state.messages.lastOrNull()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (last != null && state.variantNavigationAvailable) {
                            TextButton(
                                enabled = last.variantIndex > 0,
                                onClick = actions.previousVariant,
                                modifier = Modifier.testTag("previousVariant"),
                            ) { Text("上一条") }
                            Text("${last.variantIndex + 1} / ${last.variantCount}")
                            TextButton(
                                enabled = last.variantIndex < last.variantCount - 1,
                                onClick = actions.nextVariant,
                                modifier = Modifier.testTag("nextVariant"),
                            ) { Text("下一条") }
                        }
                        if (state.regenerateAvailable) {
                            OutlinedButton(
                                onClick = actions.regenerate,
                                modifier = Modifier.testTag("regenerate"),
                            ) { Text("重新生成") }
                        }
                    }
                }
            }
            item("latest-message-anchor") {
                Spacer(Modifier.height(1.dp).testTag("latestMessageAnchor"))
            }
        }
    }
    if (state.executionMode == ConversationExecutionMode.BROWSER && editingMessageId != null && pendingMessageEdit == null) {
        state.messages.firstOrNull { it.message.id == editingMessageId }?.let { target ->
            AlertDialog(onDismissRequest = { editingMessageId = null }, title = { Text("编辑消息") },
                text = { OutlinedTextField(value = editingText, onValueChange = { editingText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp).testTag("webMessageEditor")) },
                confirmButton = { Column {
                    TextButton(enabled = !state.busy && editingText.isNotBlank(), onClick = {
                        actions.editMessage(target.message.id, editingText, MessageEditMode.TEXT_ONLY); editingMessageId = null
                    }) { Text("保存文字") }
                    TextButton(enabled = !state.busy && editingText.isNotBlank(), onClick = {
                        val edit = PendingMessageEdit(target.message.id, editingText, target.message.role == MessageRole.USER,
                            state.messages.lastIndex - state.messages.indexOf(target), target.variantCount - 1)
                        if (edit.removedMessageCount > 0 || edit.discardedVariantCount > 0) pendingMessageEdit = edit
                        else { actions.editMessage(edit.messageId, edit.sourceText, MessageEditMode.RESTART); editingMessageId = null }
                    }) { Text("从这里重新生成 / 继续") }
                } }, dismissButton = { TextButton(onClick = { editingMessageId = null }) { Text("取消") } })
        }
    }
    if (nativeDetailsVisible) {
        ModalBottomSheet(onDismissRequest = { nativeDetailsVisible = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = state.nativeStatus != null)) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().testTag("nativeDetails"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (hasNativeGuide) item {
                    OutlinedButton(onClick = { nativeDetailsVisible = false; nativeGuideVisible = true }, modifier = Modifier.fillMaxWidth().testTag("openNativeGuideFromDetails")) {
                        Text("玩法说明")
                    }
                }
                if (state.nativeChoices.isNotEmpty()) item {
                    NativePlayerChoicesCard(state.nativeChoices, !state.busy) { id ->
                        nativeDetailsVisible = false
                        actions.previewPlayerChoice(id)
                    }
                }
                if (state.nativeActionRunning) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        TextButton(onClick = actions.cancelNativeAction) { Text("停止操作") }
                    }
                }
                state.nativeSurfaceError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                if (state.character.nativeAdaptation?.script != null) state.message?.let { notice ->
                    item("native-operation-notice") { Text(notice) }
                }
                state.nativeSurfaces.forEach { surface -> item("script-${surface.id}") {
                    NativeScriptSurfaceCard(surface, !state.busy, actions.invokeNativeAction)
                } }
                state.nativeStatus?.let { status -> item { NativeStatusCard(status, state.nativeState) } }
                state.nativeScenes.forEach { scene -> item {
                    NativeSceneCard(scene, state.nativeState) { resolveAssetPath(state.character.assetId, it) }
                } }
                state.nativeCollections.forEach { collection -> item { NativeCollectionCard(collection, state.nativeState) } }
                if (state.character.nativeAdaptation?.memories.orEmpty().isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("对话记忆", style = MaterialTheme.typography.titleLarge)
                        Text("模型依据已发生的对话整理的分析，会影响后续回复。切换候选时随之恢复。", style = MaterialTheme.typography.bodySmall)
                        state.character.nativeAdaptation?.memories.orEmpty().forEach { definition ->
                            Text(definition.title, style = MaterialTheme.typography.titleMedium)
                            val note = state.memories[definition.id]
                            if (note == null) Text("尚未生成") else {
                                Text("更新于第 ${note.assistantReplyCount} 条助手回复 · ${note.model}", style = MaterialTheme.typography.labelSmall)
                                var expanded by remember(definition.id, note) { mutableStateOf(false) }
                                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("memory-toggle-${definition.id}")) {
                                    Text(if (expanded) "收起分析" else "查看分析")
                                }
                                if (expanded) Text(note.content, modifier = Modifier.testTag("memory-body-${definition.id}"))
                            }
                        }
                        OutlinedButton(onClick = actions.refreshMemories, enabled = !state.busy && state.selectedConnection != null && state.messages.lastOrNull()?.metadata != null,
                            modifier = Modifier.testTag("refreshConversationMemories")) { Text("更新记忆") }
                    }
                }
            }
        }
    }

    if (nativeGuideVisible) {
        NativeGuideSheet(nativeGuide, state.persona.name, state.character.name) { nativeGuideVisible = false }
    }

    state.messages.firstOrNull { it.message.id == historicalStateMessageId }?.let { historical ->
        val status = state.nativeStatus
        val values = historical.nativeStateAfter
        if (values != null || historical.memoriesAfter.isNotEmpty()) ModalBottomSheet(onDismissRequest = { historicalStateMessageId = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            LazyColumn(Modifier.fillMaxWidth().testTag("historicalNativeState"), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("此条回复后的状态", style = MaterialTheme.typography.titleLarge)
                    if (historical.stateUnconfirmed) Text("本轮状态未确认，显示保留下来的记录", color = MaterialTheme.colorScheme.error)
                    if (historical.playerChoiceCommits.isNotEmpty()) Text("包含你在此候选中确认的选择", style = MaterialTheme.typography.bodySmall)
                }
                if (status != null && values != null) item { NativeStatusCard(status.copy(title = ""), values) }
                if (values != null) {
                    state.nativeCollections.forEach { collection -> item { NativeCollectionCard(collection, values) } }
                    state.nativeScenes.forEach { scene -> item { NativeSceneCard(scene, values) { resolveAssetPath(state.character.assetId, it) } } }
                }
                historical.memoriesAfter.forEach { (id, note) -> item {
                    Text(state.character.nativeAdaptation?.memories?.find { it.id == id }?.title ?: id, style = MaterialTheme.typography.titleMedium)
                    Text(note.content)
                } }
            }
        }
    }

    state.choicePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = actions.cancelPlayerChoice,
            title = { Text(preview.choice.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("native-choice-preview")) {
                    Text(preview.choice.description)
                    Text("${preview.stateLabel}：${preview.previousValue} → ${preview.choice.stateValue}", fontWeight = FontWeight.SemiBold)
                    Text(preview.choice.draft)
                    Text("确认后设定立即生效，草稿由你发送。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = actions.confirmPlayerChoice, enabled = !state.busy, modifier = Modifier.testTag("native-choice-confirm")) { Text("保存选择并填入草稿") } },
            dismissButton = { TextButton(onClick = actions.cancelPlayerChoice, modifier = Modifier.testTag("native-choice-cancel")) { Text("取消") } },
        )
    }

    if (modelPickerVisible) {
        ModelPicker(
            connections = state.readyConnections,
            selectedId = state.selectedConnectionId,
            onSelect = {
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
            TraceSheet(trace = trace, onDismiss = { traceVisible = false })
        }
    }
    if (worldBookVisible) {
        // 全屏阅读保留底下的会话 WebView，避免打开资料时销毁作者运行环境。
        Dialog(onDismissRequest = { worldBookVisible = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            WorldBookReaderScreen(
                readerId = "conversation-${state.conversationId}", characterName = state.character.name,
                books = state.character.worldBooks, sessionState = state.worldBookState,
                busy = state.busy || state.browserGenerating, message = state.worldBookMessage,
                lastInjections = state.lastTrace?.plan?.worldBookInjections,
                onBack = { worldBookVisible = false }, onEntryMode = actions.onWorldBookEntryMode,
                onEntryContent = actions.onWorldBookEntryContent,
            )
        }
    }
    pendingMessageEdit?.let { edit ->
        val consequences = buildList {
            if (edit.removedMessageCount > 0) add("移除后续 ${edit.removedMessageCount} 条消息")
            if (edit.discardedVariantCount > 0) add("丢弃当前消息的 ${edit.discardedVariantCount} 个其他候选")
        }.joinToString("，")
        AlertDialog(
            modifier = Modifier.testTag("confirmMessageEdit"),
            onDismissRequest = { pendingMessageEdit = null },
            title = { Text("从这里重新开始？") },
            text = { Text("保存修改将$consequences，且无法在当前对话中恢复。") },
            confirmButton = {
                Button(
                    onClick = {
                        actions.editMessage(edit.messageId, edit.sourceText, MessageEditMode.RESTART)
                        pendingMessageEdit = null
                        editingMessageId = null
                        editingText = ""
                    },
                    modifier = Modifier.testTag("confirmMessageEditAction"),
                ) { Text(if (edit.regenerate) "确认并重新生成" else "确认修改") }
            },
            dismissButton = {
                TextButton(onClick = { pendingMessageEdit = null }) { Text("继续编辑") }
            },
        )
    }
}

@Composable
private fun ChatComposer(state: ChatUiState, actions: ChatScreenActions) {
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
                modifier = Modifier.weight(1f).testTag("chatInput"),
                enabled = !state.busy,
                label = { Text("说点什么") },
                minLines = 1,
                maxLines = 5,
            )
            if (state.running || state.browserGenerating) {
                OutlinedButton(
                    modifier = Modifier.testTag("cancelGeneration"),
                    onClick = actions.cancel,
                ) { Text("停止") }
            } else {
                Button(
                    modifier = Modifier.testTag("sendMessage"),
                    enabled = state.input.isNotBlank() && !state.busy,
                    onClick = actions.send,
                ) { Text("发送") }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    state: ChatMessageState,
    onSubmitNativeForm: (formId: String, values: Map<String, List<String>>) -> Unit,
    onViewState: () -> Unit,
    editable: Boolean,
    editingText: String?,
    onStartEdit: () -> Unit,
    onEditTextChange: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSaveText: () -> Unit,
    onRestart: () -> Unit,
) {
    val user = state.message.role == MessageRole.USER
    var reasoningVisible by remember(state.message.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        val cardModifier = if (editingText == null) {
            Modifier.widthIn(max = 560.dp)
        } else {
            Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp)
        }
        Card(
            modifier = cardModifier.testTag("message-${state.message.id}"),
            colors = CardDefaults.cardColors(containerColor = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    state.message.authorName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (editingText != null) {
                    OutlinedTextField(
                        value = editingText,
                        onValueChange = onEditTextChange,
                        modifier = Modifier.fillMaxWidth().testTag("messageEditInput-${state.message.id}"),
                        label = { Text("消息内容") },
                        minLines = 2,
                        maxLines = 12,
                    )
                } else if (state.displayContent.isNotEmpty()) {
                    SafeMarkdownText(state.displayContent)
                } else if (state.status == ChatMessageStatus.STREAMING) {
                    Text(
                        if (state.message.reasoning.isEmpty()) "正在等待回复…" else "正在思考…",
                        modifier = Modifier.testTag("generationStatus"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (editingText == null) {
                    state.playerChoiceCommits.lastOrNull()?.let { choice ->
                        Text("已选择：${choice.title}（${choice.value}）", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("native-choice-receipt-${choice.choiceId}"))
                    }
                    state.nativePanels.forEach { panel -> NativeMessagePanelCard(panel) }
                    state.nativeForms.forEach { form ->
                        NativeFormCard(
                            form = form,
                            enabled = editable,
                            onSubmit = { values -> onSubmitNativeForm(form.id, values) },
                            completed = form.setup != null && state.setupClosed,
                        )
                    }
                }
                val reasoning = state.displayReasoning.joinToString("\n").trim()
                if (editingText == null && reasoning.isNotEmpty()) {
                    TextButton(onClick = { reasoningVisible = !reasoningVisible }) {
                        Text(if (reasoningVisible) "收起思考" else "查看思考")
                    }
                    if (reasoningVisible) {
                        Text(
                            reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                when (state.status) {
                    ChatMessageStatus.CANCELLED -> Text("已停止", style = MaterialTheme.typography.labelSmall)
                    ChatMessageStatus.ERROR -> Text(
                        "生成中断",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    ChatMessageStatus.INTERRUPTED -> Text(
                        "上次生成被进程中断",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Unit
                }
                if (state.stateUnconfirmed) {
                    Text("本轮状态未能确认，保留上一轮记录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                if (editingText != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = onCancelEdit,
                            modifier = Modifier.testTag("cancelMessageEdit-${state.message.id}"),
                        ) { Text("取消") }
                        Button(
                            onClick = onSaveText,
                            enabled = editingText.isNotBlank() && editingText != state.message.sourceText,
                            modifier = Modifier.testTag("saveMessageTextEdit-${state.message.id}"),
                        ) { Text("保存文字") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = onRestart,
                            enabled = editingText.isNotBlank() && editingText != state.message.sourceText,
                            modifier = Modifier.testTag("restartFromMessage-${state.message.id}"),
                        ) { Text(if (user) "从这里重新生成" else "从这里继续") }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.nativeStateAfter != null || state.memoriesAfter.isNotEmpty()) {
                            TextButton(onClick = onViewState, modifier = Modifier.testTag("viewNativeState-${state.message.id}")) { Text("状态") }
                        }
                        if (state.edited) {
                            Text(
                                "已编辑",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (editable && state.status != ChatMessageStatus.STREAMING) {
                            TextButton(
                                onClick = onStartEdit,
                                modifier = Modifier.testTag("editMessage-${state.message.id}"),
                            ) { Text("编辑") }
                        }
                    }
                }
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("选择模型", style = MaterialTheme.typography.titleLarge)
            if (connections.isEmpty()) {
                Text(
                    "还没有可用连接。可以先保留当前对话，再去配置模型。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            connections.forEach { connection ->
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
                            "${connection.protocol.displayName()} · ${connection.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
private fun PresetPicker(
    presets: List<PresetAsset>,
    selectedId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("选择预设", style = MaterialTheme.typography.titleLarge)
            presets.forEach { preset ->
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
            HorizontalDivider()
            TextButton(enabled = enabled, onClick = onManage) { Text("管理预设") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TraceSheet(trace: GenerationTraceState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().testTag("traceSheet"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("最近一轮上下文", style = MaterialTheme.typography.titleLarge) }
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
