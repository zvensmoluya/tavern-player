package io.github.zvensmoluya.tavernplayer.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.ConnectionTemplates
import java.text.DateFormat
import java.util.Date

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
            chooseTemplate = viewModel::chooseTemplate,
            updateName = viewModel::updateName,
            updateStreamEndpoint = viewModel::updateStreamEndpoint,
            updateCatalogEndpoint = viewModel::updateCatalogEndpoint,
            updateAuthScheme = viewModel::updateAuthScheme,
            updateCredential = viewModel::updateCredential,
            updateModel = viewModel::updateModel,
            confirmReuse = viewModel::setConfirmCredentialReuse,
            save = viewModel::save,
            refreshModels = viewModel::refreshModels,
            updateProbeSystem = viewModel::updateProbeSystem,
            updateProbeUser = viewModel::updateProbeUser,
            runProbe = viewModel::runProbe,
            cancelProbe = viewModel::cancelProbe,
        ),
    )
}

data class ConnectionScreenActions(
    val add: (String) -> Unit,
    val edit: (String) -> Unit,
    val closeEditor: () -> Unit,
    val delete: (String) -> Unit,
    val chooseTemplate: (String) -> Unit,
    val updateName: (String) -> Unit,
    val updateStreamEndpoint: (String) -> Unit,
    val updateCatalogEndpoint: (String) -> Unit,
    val updateAuthScheme: (AuthScheme) -> Unit,
    val updateCredential: (String) -> Unit,
    val updateModel: (String) -> Unit,
    val confirmReuse: (Boolean) -> Unit,
    val save: () -> Unit,
    val refreshModels: () -> Unit,
    val updateProbeSystem: (String) -> Unit,
    val updateProbeUser: (String) -> Unit,
    val runProbe: () -> Unit,
    val cancelProbe: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelConnectionsScreen(
    state: ConnectionsUiState,
    actions: ConnectionScreenActions,
) {
    val editor = state.editor
    if (editor == null) {
        ConnectionList(state, actions)
    } else {
        val stored = state.connections.firstOrNull { it.id == editor.draft.id }
        ConnectionEditor(editor, stored, state.probe, actions)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionList(state: ConnectionsUiState, actions: ConnectionScreenActions) {
    var addMenu by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<StoredConnection?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("模型连接") }) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    ConnectionTemplates.all.forEach { template ->
                        DropdownMenuItem(
                            text = { Text(template.displayName) },
                            onClick = {
                                addMenu = false
                                actions.add(template.id)
                            },
                        )
                    }
                }
                FloatingActionButton(
                    modifier = Modifier.testTag("addConnection"),
                    onClick = { addMenu = true },
                ) { Text("+") }
            }
        },
    ) { padding ->
        if (!state.loading && state.connections.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("还没有模型连接", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("从右下角选择协议模板。endpoint、鉴权和模型都可以随后编辑。")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.connections, key = StoredConnection::id) { connection ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(connection.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(connection.protocol.name.replace('_', ' '), style = MaterialTheme.typography.bodySmall)
                            Text(connection.selectedModel.ifBlank { "未选择模型" })
                            Text(
                                credentialStatusLabel(state.credentialStatuses[connection.id]),
                                color = when (state.credentialStatuses[connection.id]) {
                                    CredentialStatus.READY, CredentialStatus.NOT_REQUIRED -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.error
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { actions.edit(connection.id) }) { Text("编辑") }
                                TextButton(onClick = { pendingDelete = connection }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { connection ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除连接？") },
            text = { Text("将同时删除 ${connection.name} 的密钥和模型缓存。") },
            confirmButton = {
                Button(onClick = {
                    pendingDelete = null
                    actions.delete(connection.id)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
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
    var templateMenu by remember { mutableStateOf(false) }
    var authMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var reasoningExpanded by remember { mutableStateOf(false) }
    val cache = stored?.modelCache ?: ModelCache()
    val canUseConnection = stored != null && stored.matches(editor.draft) &&
        editor.credentialStatus in setOf(CredentialStatus.READY, CredentialStatus.NOT_REQUIRED)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (stored == null) "新建模型连接" else "编辑模型连接") },
                navigationIcon = { TextButton(onClick = actions.closeEditor) { Text("返回") } },
                actions = {
                    if (stored != null) TextButton(onClick = { actions.delete(stored.id) }) { Text("删除") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionTitle("连接")
                MenuButton(
                    label = "模板：${ConnectionTemplates.require(editor.draft.templateId).displayName}",
                    expanded = templateMenu,
                    onExpand = { templateMenu = true },
                    onDismiss = { templateMenu = false },
                ) {
                    ConnectionTemplates.all.forEach { template ->
                        DropdownMenuItem(
                            text = { Text(template.displayName) },
                            onClick = {
                                templateMenu = false
                                actions.chooseTemplate(template.id)
                            },
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = editor.draft.name,
                    onValueChange = actions.updateName,
                    modifier = Modifier.fillMaxWidth().testTag("connectionName"),
                    label = { Text("名称") },
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = editor.draft.streamEndpoint,
                    onValueChange = actions.updateStreamEndpoint,
                    modifier = Modifier.fillMaxWidth().testTag("streamEndpoint"),
                    label = { Text("Stream endpoint") },
                    supportingText = { Text("完整 HTTPS 操作 URL；不自动补 /v1") },
                )
            }
            item {
                OutlinedTextField(
                    value = editor.draft.catalogEndpoint.orEmpty(),
                    onValueChange = actions.updateCatalogEndpoint,
                    modifier = Modifier.fillMaxWidth().testTag("catalogEndpoint"),
                    label = { Text("Catalog endpoint（可选）") },
                    supportingText = { Text("留空时始终可手填 model id") },
                )
            }
            item {
                MenuButton(
                    label = "鉴权：${authLabel(editor.draft.authScheme)}",
                    expanded = authMenu,
                    onExpand = { authMenu = true },
                    onDismiss = { authMenu = false },
                ) {
                    AuthScheme.entries.forEach { scheme ->
                        DropdownMenuItem(
                            text = { Text(authLabel(scheme)) },
                            onClick = {
                                authMenu = false
                                actions.updateAuthScheme(scheme)
                            },
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = editor.credentialInput,
                    onValueChange = actions.updateCredential,
                    modifier = Modifier.fillMaxWidth().testTag("credential"),
                    enabled = editor.draft.authScheme != AuthScheme.NONE,
                    label = { Text("密钥") },
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = {
                        Text(
                            when {
                                editor.draft.authScheme == AuthScheme.NONE -> "此连接不使用凭据"
                                editor.existingCredentialMask != null -> "已保存 ${editor.existingCredentialMask}；留空表示保留"
                                else -> "密钥不会回填明文，也不会写入 DataStore"
                            },
                        )
                    },
                    singleLine = true,
                )
            }
            item {
                Text(
                    credentialStatusLabel(editor.credentialStatus),
                    color = if (editor.credentialStatus in setOf(CredentialStatus.READY, CredentialStatus.NOT_REQUIRED)) {
                        MaterialTheme.colorScheme.primary
                    } else MaterialTheme.colorScheme.error,
                )
            }
            if (editor.credentialStatus == CredentialStatus.ORIGIN_CONFIRMATION_REQUIRED) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = editor.confirmCredentialReuse,
                            onCheckedChange = actions.confirmReuse,
                        )
                        Text("我确认在新的 endpoint origin 继续使用原密钥")
                    }
                }
            }
            item {
                Button(
                    onClick = actions.save,
                    enabled = !editor.saving,
                    modifier = Modifier.testTag("saveConnection"),
                ) { Text(if (editor.saving) "保存中…" else "保存连接") }
                editor.message?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
            }
            item { HorizontalDivider(); SectionTitle("模型") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = actions.refreshModels,
                        enabled = canUseConnection && !editor.refreshingModels && !editor.draft.catalogEndpoint.isNullOrBlank(),
                    ) { Text(if (editor.refreshingModels) "刷新中…" else "刷新模型") }
                    cache.refreshedAtEpochMillis?.let {
                        Text("缓存于 ${DateFormat.getDateTimeInstance().format(Date(it))}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (cache.models.isNotEmpty()) {
                item {
                    MenuButton(
                        label = "从缓存选择（${cache.models.size}）",
                        expanded = modelMenu,
                        onExpand = { modelMenu = true },
                        onDismiss = { modelMenu = false },
                    ) {
                        cache.models.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.name?.let { "$it · ${model.id}" } ?: model.id) },
                                onClick = {
                                    modelMenu = false
                                    actions.updateModel(model.id)
                                },
                            )
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = editor.draft.selectedModel,
                    onValueChange = actions.updateModel,
                    modifier = Modifier.fillMaxWidth().testTag("manualModelId"),
                    label = { Text("Model id") },
                    supportingText = { Text("始终允许手填；不根据名称猜测能力") },
                    singleLine = true,
                )
            }
            item { HorizontalDivider(); SectionTitle("一次性连接探针") }
            item { Text("连接测试最多 512 tokens。temperature、top-p、top-k 均不发送。") }
            item {
                OutlinedTextField(
                    value = probe.system,
                    onValueChange = actions.updateProbeSystem,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("System") },
                )
            }
            item {
                OutlinedTextField(
                    value = probe.user,
                    onValueChange = actions.updateProbeUser,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("User") },
                    minLines = 3,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = actions.runProbe,
                        enabled = canUseConnection && !probe.running && editor.draft.selectedModel.isNotBlank(),
                    ) { Text(if (probe.running) "发送中…" else "发送") }
                    OutlinedButton(onClick = actions.cancelProbe, enabled = probe.running) { Text("取消") }
                }
            }
            if (probe.text.isNotEmpty()) {
                item {
                    Text("正文", fontWeight = FontWeight.SemiBold)
                    Text(probe.text, modifier = Modifier.testTag("probeText"))
                }
            }
            if (probe.reasoning.isNotEmpty()) {
                item {
                    TextButton(onClick = { reasoningExpanded = !reasoningExpanded }) {
                        Text(if (reasoningExpanded) "收起思考摘要" else "展开思考摘要")
                    }
                    if (reasoningExpanded) Text(probe.reasoning)
                }
            }
            if (probe.diagnostics.isNotEmpty()) {
                item {
                    Text("诊断摘要", fontWeight = FontWeight.SemiBold)
                    Text(probe.diagnostics.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                }
            }
            probe.usage?.let { usage ->
                item { Text("Usage: in=${usage.inputTokens ?: "?"}, out=${usage.outputTokens ?: "?"}, total=${usage.totalTokens ?: "?"}") }
            }
            probe.finishReason?.let { item { Text("结束状态：$it") } }
            probe.error?.let { item { Text("探针失败：$it", color = MaterialTheme.colorScheme.error) } }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun MenuButton(
    label: String,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column {
        OutlinedButton(onClick = onExpand) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, content = content)
    }
}

private fun credentialStatusLabel(status: CredentialStatus?): String = when (status) {
    CredentialStatus.READY -> "密钥可用"
    CredentialStatus.MISSING -> "需要重新输入密钥"
    CredentialStatus.ORIGIN_CONFIRMATION_REQUIRED -> "endpoint origin 已改变，密钥复用待确认"
    CredentialStatus.NOT_REQUIRED -> "无需密钥"
    null -> "正在检查密钥状态"
}

private fun authLabel(scheme: AuthScheme): String = when (scheme) {
    AuthScheme.BEARER -> "Bearer"
    AuthScheme.X_API_KEY -> "x-api-key"
    AuthScheme.X_GOOG_API_KEY -> "x-goog-api-key"
    AuthScheme.QUERY_KEY -> "query key"
    AuthScheme.NONE -> "none"
}

private fun StoredConnection.matches(draft: ConnectionDraft): Boolean =
    name == draft.name.trim() &&
        templateId == draft.templateId &&
        protocol == draft.protocol &&
        streamEndpoint == draft.streamEndpoint.trim() &&
        catalogEndpoint.orEmpty() == draft.catalogEndpoint.orEmpty().trim() &&
        authScheme == draft.authScheme &&
        selectedModel == draft.selectedModel.trim()
