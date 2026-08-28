package io.github.zvensmoluya.tavernplayer.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI

@Composable
fun ModelConnectionsRoute(viewModel: ModelConnectionsViewModel) {
    val state by viewModel.uiState.collectAsState()
    ModelConnectionsScreen(
        state = state,
        actions = ConnectionScreenActions(
            add = viewModel::startNew,
            edit = viewModel::edit,
            closeEditor = viewModel::closeEditor,
            delete = viewModel::delete,
            chooseProtocol = viewModel::chooseTemplate,
            updateName = viewModel::updateName,
            updateApiAddress = viewModel::updateApiAddress,
            updateCredential = viewModel::updateCredential,
            updateModel = viewModel::updateModel,
            confirmReuse = viewModel::setConfirmCredentialReuse,
            save = viewModel::save,
            refreshModels = viewModel::refreshModels,
            runTest = viewModel::runProbe,
            cancelTest = viewModel::cancelProbe,
        ),
    )
}

data class ConnectionScreenActions(
    val add: (String) -> Unit,
    val edit: (String) -> Unit,
    val closeEditor: () -> Unit,
    val delete: (String) -> Unit,
    val chooseProtocol: (String) -> Unit,
    val updateName: (String) -> Unit,
    val updateApiAddress: (String) -> Unit,
    val updateCredential: (String) -> Unit,
    val updateModel: (String) -> Unit,
    val confirmReuse: (Boolean) -> Unit,
    val save: () -> Unit,
    val refreshModels: () -> Unit,
    val runTest: () -> Unit,
    val cancelTest: () -> Unit,
)

@Composable
fun ModelConnectionsScreen(
    state: ConnectionsUiState,
    actions: ConnectionScreenActions,
) {
    val editor = state.editor
    if (editor == null) {
        ConnectionList(state, actions)
    } else {
        ConnectionEditor(
            editor = editor,
            stored = state.connections.firstOrNull { it.id == editor.draft.id },
            probe = state.probe,
            actions = actions,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionList(state: ConnectionsUiState, actions: ConnectionScreenActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型") },
                actions = {
                    if (state.connections.isNotEmpty()) {
                        TextButton(
                            modifier = Modifier.testTag("addConnection"),
                            onClick = { actions.add(ConnectionTemplates.openAiResponses.id) },
                        ) { Text("添加") }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.connections.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("添加一个模型", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(20.dp))
                Button(
                    modifier = Modifier.testTag("addConnection"),
                    onClick = { actions.add(ConnectionTemplates.openAiResponses.id) },
                ) { Text("添加模型") }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.connections, key = StoredConnection::id) { connection ->
                    val status = state.credentialStatuses[connection.id]
                    Card(
                        onClick = { actions.edit(connection.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    connection.selectedModel.ifBlank { connection.name },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${protocolLabel(connection.protocol)} · ${addressHost(connection.streamEndpoint)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                connectionIssue(status, connection.selectedModel)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            Text("›", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionEditor(
    editor: ConnectionEditorState,
    stored: StoredConnection?,
    probe: ProbeUiState,
    actions: ConnectionScreenActions,
) {
    var protocolMenu by remember { mutableStateOf(false) }
    var modelPickerVisible by remember { mutableStateOf(false) }
    var manualModel by remember { mutableStateOf(false) }
    var moreSettings by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val cache = stored?.modelCache ?: ModelCache()
    val ready = editor.credentialStatus == CredentialStatus.READY ||
        editor.credentialStatus == CredentialStatus.NOT_REQUIRED
    val unchanged = stored?.matches(editor.draft) == true
    val addressUnchanged = stored != null &&
        editor.draft.apiAddress.trim().trimEnd('/') == stored.apiAddress
    val canTest = stored != null && unchanged && addressUnchanged && ready &&
        editor.draft.selectedModel.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (stored == null) "添加模型" else "模型设置") },
                navigationIcon = { TextButton(onClick = actions.closeEditor) { Text("返回") } },
                actions = {
                    if (stored != null) {
                        TextButton(onClick = { confirmDelete = true }) { Text("删除") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                FieldLabel("接口协议")
                MenuField(
                    value = protocolLabel(editor.draft.protocol),
                    expanded = protocolMenu,
                    onExpand = { protocolMenu = true },
                    onDismiss = { protocolMenu = false },
                ) {
                    ConnectionTemplates.protocols.forEach { template ->
                        DropdownMenuItem(
                            text = { Text(template.displayName) },
                            onClick = {
                                protocolMenu = false
                                actions.chooseProtocol(template.id)
                            },
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = editor.draft.apiAddress,
                    onValueChange = actions.updateApiAddress,
                    modifier = Modifier.fillMaxWidth().testTag("apiAddress"),
                    label = { Text("API 地址") },
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = editor.credentialInput,
                    onValueChange = actions.updateCredential,
                    modifier = Modifier.fillMaxWidth().testTag("credential"),
                    label = {
                        Text(editor.existingCredentialMask?.let { "API Key · $it" } ?: "API Key（可选）")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
            if (editor.credentialStatus == CredentialStatus.ORIGIN_CONFIRMATION_REQUIRED) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = editor.confirmCredentialReuse,
                            onCheckedChange = actions.confirmReuse,
                        )
                        Text("允许向新地址发送已保存的 API Key")
                    }
                }
            }
            item {
                Button(
                    onClick = actions.save,
                    enabled = !editor.saving,
                    modifier = Modifier.fillMaxWidth().testTag("saveConnection"),
                ) {
                    if (editor.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.size(10.dp))
                    }
                    Text(if (stored == null) "连接" else "保存")
                }
                editor.message?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 8.dp).testTag("connectionMessage"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (stored != null) {
                item { HorizontalDivider() }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (editor.refreshingModels) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(
                                onClick = actions.refreshModels,
                                enabled = unchanged && addressUnchanged,
                            ) {
                                Text("重新获取")
                            }
                        }
                    }
                }
                editor.modelMessage?.let { message ->
                    item { StatusText(message, error = true) }
                }
                if (cache.models.isNotEmpty()) {
                    item {
                        OutlinedButton(
                            onClick = { modelPickerVisible = true },
                            modifier = Modifier.fillMaxWidth().testTag("modelPicker"),
                        ) {
                            Text(
                                editor.draft.selectedModel.ifBlank { "选择模型" },
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text("⌄")
                        }
                    }
                }
                item {
                    if (cache.models.isNotEmpty()) {
                        TextButton(onClick = { manualModel = !manualModel }) {
                            Text(if (manualModel) "收起" else "输入模型 ID")
                        }
                    }
                    if (manualModel || cache.models.isEmpty()) {
                        OutlinedTextField(
                            value = editor.draft.selectedModel,
                            onValueChange = actions.updateModel,
                            modifier = Modifier.fillMaxWidth().testTag("manualModelId"),
                            label = { Text("模型 ID") },
                            singleLine = true,
                        )
                    }
                }
                item {
                    Button(
                        onClick = actions.save,
                        enabled = editor.draft.selectedModel.isNotBlank() && !editor.saving,
                        modifier = Modifier.fillMaxWidth().testTag("saveModel"),
                    ) { Text("使用这个模型") }
                }
                item {
                    OutlinedButton(
                        onClick = if (probe.running) actions.cancelTest else actions.runTest,
                        enabled = probe.running || canTest,
                        modifier = Modifier.fillMaxWidth().testTag("testConnection"),
                    ) { Text(if (probe.running) "取消测试" else "测试连接") }
                    when {
                        probe.error != null -> StatusText(probe.error, error = true)
                        !probe.running && (probe.finishReason != null || probe.text.isNotEmpty()) ->
                            StatusText("连接正常", error = false)
                    }
                }
            }

            item {
                TextButton(onClick = { moreSettings = !moreSettings }) {
                    Text(if (moreSettings) "收起更多设置" else "更多设置")
                }
                if (moreSettings) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = editor.draft.name,
                            onValueChange = actions.updateName,
                            modifier = Modifier.fillMaxWidth().testTag("connectionName"),
                            label = { Text("连接名称") },
                            singleLine = true,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmDelete && stored != null) {
        DeleteDialog(
            name = stored.name,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                actions.delete(stored.id)
            },
        )
    }
    if (modelPickerVisible) {
        ModelPickerSheet(
            models = cache.models,
            selected = editor.draft.selectedModel,
            onDismiss = { modelPickerVisible = false },
            onSelect = {
                actions.updateModel(it)
                modelPickerVisible = false
            },
            onManual = {
                manualModel = true
                modelPickerVisible = false
            },
        )
    }
}

@Composable
private fun FieldLabel(value: String) {
    Text(value, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun MenuField(
    value: String,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(modifier) {
        OutlinedButton(onClick = onExpand, modifier = Modifier.fillMaxWidth()) {
            Text(value, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("⌄")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, content = content)
    }
}

@Composable
private fun StatusText(value: String, error: Boolean) {
    Text(
        value,
        modifier = Modifier.padding(top = 8.dp),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodySmall,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    models: List<StoredModel>,
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onManual: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val visibleModels = remember(models, query) {
        val needle = query.trim()
        models.asSequence()
            .filter { model ->
                needle.isBlank() || model.id.contains(needle, ignoreCase = true) ||
                    model.name.orEmpty().contains(needle, ignoreCase = true)
            }
            .take(100)
            .toList()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("选择模型", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索模型") },
                singleLine = true,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(visibleModels, key = StoredModel::id) { model ->
                    TextButton(
                        onClick = { onSelect(model.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                model.name?.takeIf { it != model.id } ?: model.id,
                                fontWeight = if (model.id == selected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            model.name?.takeIf { it != model.id }?.let {
                                Text(
                                    model.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onManual, modifier = Modifier.align(Alignment.End)) {
                Text("输入模型 ID")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DeleteDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除 $name？") },
        confirmButton = { Button(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun protocolLabel(protocol: io.github.zvensmoluya.modelgateway.ModelProtocol): String =
    ConnectionTemplates.forProtocol(protocol).displayName

private fun addressHost(endpoint: String): String =
    runCatching { URI(endpoint.replace("{model}", "model")).host }
        .getOrNull()
        .orEmpty()
        .ifBlank { endpoint }

private fun connectionIssue(status: CredentialStatus?, selectedModel: String): String? = when {
    status == CredentialStatus.MISSING -> "需要 API Key"
    status == CredentialStatus.ORIGIN_CONFIRMATION_REQUIRED -> "API 地址已更改"
    selectedModel.isBlank() -> "请选择模型"
    else -> null
}

private fun StoredConnection.matches(draft: ConnectionDraft): Boolean =
    name == draft.name.trim() &&
        templateId == draft.templateId &&
        protocol == draft.protocol &&
        apiAddress == draft.apiAddress.trim().trimEnd('/') &&
        selectedModel == draft.selectedModel.trim()
