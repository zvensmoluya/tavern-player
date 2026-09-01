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
import androidx.compose.foundation.lazy.items
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
import io.github.zvensmoluya.tavernplayer.presets.PresetViewModel

@Composable
fun ChatRoute(
    viewModel: ChatViewModel,
    presetViewModel: PresetViewModel,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenPresets: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val presetState by presetViewModel.uiState.collectAsState()
    ChatScreen(
        state = state,
        presets = presetState.presets,
        actions = ChatScreenActions(
            updateInput = viewModel::updateInput,
            send = { if (state.selectedConnection == null) onOpenModels() else viewModel.send() },
            cancel = viewModel::cancel,
            retry = viewModel::retry,
            regenerate = viewModel::regenerate,
            previousVariant = viewModel::previousVariant,
            nextVariant = viewModel::nextVariant,
            selectConnection = viewModel::selectConnection,
            reset = viewModel::resetConversation,
            back = onBack,
            openModels = onOpenModels,
            selectPreset = presetViewModel::activate,
            openPresets = onOpenPresets,
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
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    actions: ChatScreenActions,
    presets: List<PresetAsset> = emptyList(),
) {
    var modelPickerVisible by remember { mutableStateOf(false) }
    var presetPickerVisible by remember { mutableStateOf(false) }
    var traceVisible by remember { mutableStateOf(false) }
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
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.messages, key = { it.message.id }) { message ->
                MessageBubble(message)
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
private fun MessageBubble(state: ChatMessageState) {
    val user = state.message.role == MessageRole.USER
    var reasoningVisible by remember(state.message.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        Card(modifier = Modifier.widthIn(max = 560.dp).testTag("message-${state.message.id}")) {
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
                if (state.displayContent.isNotEmpty()) {
                    SafeMarkdownText(state.displayContent)
                } else if (state.status == ChatMessageStatus.STREAMING) {
                    Text("正在生成…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val reasoning = state.displayReasoning.joinToString("\n").trim()
                if (reasoning.isNotEmpty()) {
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
                            "${preset.prompts.size} Prompt · ${preset.regexScripts.size} Regex · 回复 ${preset.generationSettings.maxOutputTokens}",
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
                            append("store=false · hostedState=false · maxOutput=${preview.maxOutputTokens}")
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
