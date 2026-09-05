package io.github.zvensmoluya.tavernplayer.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.PresetAsset
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationParameter
import io.github.zvensmoluya.tavernplayer.presets.PresetViewModel

@Composable
fun ChatRoute(
    viewModel: ChatViewModel,
    presetViewModel: PresetViewModel,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenPresets: () -> Unit,
    resolveAssetPath: (characterId: String, assetId: String) -> String? = { _, _ -> null },
) {
    val state by viewModel.uiState.collectAsState()
    val presetState by presetViewModel.uiState.collectAsState()
    ChatScreen(
        state = state,
        resolveAssetPath = resolveAssetPath,
        presets = presetState.presets,
        actions = ChatScreenActions(
            updateInput = viewModel::updateInput,
            send = { if (state.selectedConnection == null) onOpenModels() else viewModel.send() },
            cancel = viewModel::cancel,
            retry = viewModel::retry,
            regenerate = viewModel::regenerate,
            editMessage = viewModel::editMessage,
            previousVariant = viewModel::previousVariant,
            nextVariant = viewModel::nextVariant,
            selectConnection = viewModel::selectConnection,
            reset = viewModel::resetConversation,
            back = onBack,
            openModels = onOpenModels,
            selectPreset = presetViewModel::activate,
            openPresets = onOpenPresets,
            submitNativeForm = viewModel::submitNativeForm,
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
    val regenerate: () -> Unit = {},
    val previousVariant: () -> Unit = {},
    val nextVariant: () -> Unit = {},
    val back: () -> Unit = {},
    val selectPreset: (String) -> Unit = {},
    val openPresets: () -> Unit = {},
    val editMessage: (messageId: String, sourceText: String, mode: MessageEditMode) -> Unit = { _, _, _ -> },
    val submitNativeForm: (formId: String, values: Map<String, List<String>>) -> Unit = { _, _ -> },
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
) {
    var modelPickerVisible by remember { mutableStateOf(false) }
    var presetPickerVisible by remember { mutableStateOf(false) }
    var traceVisible by remember { mutableStateOf(false) }
    var editingMessageId by remember(state.conversationId) { mutableStateOf<String?>(null) }
    var editingText by remember(state.conversationId) { mutableStateOf("") }
    var pendingMessageEdit by remember(state.conversationId) { mutableStateOf<PendingMessageEdit?>(null) }
    val messageListState = rememberLazyListState()
    val latestMessage = state.messages.lastOrNull()
    val scrollAnchorIndex = (if (state.nativeStatus != null) 1 else 0) + state.nativeScenes.size +
        state.nativeCollections.size + state.messages.size +
        (if (state.message != null) 1 else 0) +
        (if (state.retryAvailable) 1 else 0) +
        (if ((state.regenerateAvailable || state.variantNavigationAvailable) && !state.running) 1 else 0)
    LaunchedEffect(latestMessage?.message?.id, latestMessage?.status, scrollAnchorIndex, state.message) {
        if (latestMessage != null) messageListState.scrollToItem(scrollAnchorIndex)
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
                        enabled = !state.running,
                        onClick = actions.back,
                    ) { Text("返回") }
                },
                actions = {
                    TextButton(
                        modifier = Modifier.testTag("choosePreset"),
                        enabled = !state.running,
                        onClick = { presetPickerVisible = true },
                    ) { Text(state.activePresetName.ifBlank { "预设" }, maxLines = 1) }
                    if (state.lastTrace != null) {
                        TextButton(
                            modifier = Modifier.testTag("openTrace"),
                            enabled = !state.running,
                            onClick = { traceVisible = true },
                        ) { Text("上下文") }
                    }
                    TextButton(
                        modifier = Modifier.testTag("chooseModel"),
                        enabled = !state.running,
                        onClick = { modelPickerVisible = true },
                    ) { Text("模型") }
                },
            )
        },
        bottomBar = {
            ChatComposer(state, actions)
        },
    ) { padding ->
        LazyColumn(
            state = messageListState,
            modifier = Modifier.fillMaxSize().padding(padding).testTag("chatContent"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.nativeStatus?.let { status ->
                item("native-status") { NativeStatusCard(status, state.conversationState) }
            }
            state.nativeScenes.forEach { scene ->
                item("native-scene-${scene.id}") {
                    NativeSceneCard(
                        view = scene,
                        state = state.conversationState,
                        resolveAssetPath = { assetId -> resolveAssetPath(state.character.assetId, assetId) },
                    )
                }
            }
            state.nativeCollections.forEach { collection ->
                item("native-collection-${collection.id}") {
                    NativeCollectionCard(collection, state.conversationState)
                }
            }
            itemsIndexed(state.messages, key = { _, item -> item.message.id }) { index, message ->
                MessageBubble(
                    state = message,
                    onSubmitNativeForm = actions.submitNativeForm,
                    editable = !state.running,
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
            if (state.retryAvailable) {
                item("retry") {
                    OutlinedButton(
                        modifier = Modifier.testTag("retryGeneration"),
                        onClick = actions.retry,
                    ) { Text("重试这一轮") }
                }
            }
            if ((state.regenerateAvailable || state.variantNavigationAvailable) && !state.running) {
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
            enabled = !state.running,
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
        OutlinedTextField(
            value = state.input,
            onValueChange = actions.updateInput,
            modifier = Modifier.fillMaxWidth().testTag("chatInput"),
            enabled = !state.running,
            label = { Text("说点什么") },
            minLines = 1,
            maxLines = 5,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (state.running) {
                OutlinedButton(
                    modifier = Modifier.testTag("cancelGeneration"),
                    onClick = actions.cancel,
                ) { Text("停止") }
            } else {
                Button(
                    modifier = Modifier.testTag("sendMessage"),
                    enabled = state.input.isNotBlank(),
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
        Card(modifier = cardModifier.testTag("message-${state.message.id}")) {
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
                    state.nativeForms.forEach { form ->
                        NativeFormCard(
                            form = form,
                            enabled = editable,
                            onSubmit = { values -> onSubmitNativeForm(form.id, values) },
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
                        content = "context=${accounting.contextLimit} input=${accounting.inputTokens} " +
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
