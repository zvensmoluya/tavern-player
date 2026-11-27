package io.github.zvensmoluya.tavernplayer.connections

import androidx.activity.compose.BackHandler
import androidx.compose.material3.IconButton
import io.github.zvensmoluya.tavernplayer.ui.LibraryHeader
import io.github.zvensmoluya.tavernplayer.ui.LibraryEmptyState
import io.github.zvensmoluya.tavernplayer.ui.PlayerIcon
import io.github.zvensmoluya.tavernplayer.ui.PlayerSymbol
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import io.github.zvensmoluya.tavernplayer.ui.ScrollableBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI

@Composable
fun ModelConnectionsRoute(
    viewModel: ModelConnectionsViewModel,
    onBackToChat: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    ModelConnectionsScreen(
        state = state,
        actions = ConnectionScreenActions(
            add = viewModel::startNew,
            edit = viewModel::edit,
            use = viewModel::useConnection,
            closeEditor = viewModel::closeEditor,
            delete = viewModel::delete,
            chooseProtocol = viewModel::chooseProtocol,
            updateName = viewModel::updateName,
            updateApiAddress = viewModel::updateApiAddress,
            updateCredential = viewModel::updateCredential,
            selectModel = viewModel::selectModel,
            showPage = viewModel::showPage,
            updateContextTokenLimit = viewModel::updateContextTokenLimit,
            updateOutputTokenLimit = viewModel::updateOutputTokenLimit,
            confirmReuse = viewModel::setConfirmCredentialReuse,
            save = viewModel::save,
            refreshModels = viewModel::refreshModels,
            runTest = viewModel::runProbe,
            cancelTest = viewModel::cancelProbe,
        ),
        onBackToChat = onBackToChat,
    )
}

data class ConnectionScreenActions(
    val add: () -> Unit,
    val edit: (String) -> Unit,
    val use: (String) -> Unit,
    val closeEditor: () -> Unit,
    val delete: (String) -> Unit,
    val chooseProtocol: (String) -> Unit,
    val updateName: (String) -> Unit,
    val updateApiAddress: (String) -> Unit,
    val updateCredential: (String) -> Unit,
    val selectModel: (String) -> Unit,
    val showPage: (ConnectionPage) -> Unit,
    val updateContextTokenLimit: (String) -> Unit,
    val updateOutputTokenLimit: (String) -> Unit,
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
    onBackToChat: (() -> Unit)? = null,
) {
    val editor = state.editor
    if (editor == null) {
        BackHandler(enabled = onBackToChat != null) { onBackToChat?.invoke() }
        ConnectionList(state, actions, onBackToChat)
    } else key(editor.draft.id) {
        ConnectionEditor(
            editor = editor,
            stored = state.connections.firstOrNull { it.id == editor.draft.id },
            active = state.activeId == editor.draft.id,
            probe = state.probe,
            actions = actions,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionList(
    state: ConnectionsUiState,
    actions: ConnectionScreenActions,
    onBackToChat: (() -> Unit)?,
) {
    Scaffold(
        topBar = {
            LibraryHeader("聊天模型") {
                onBackToChat?.let { onBack ->
                    TextButton(onClick = onBack) { Text("返回聊天") }
                }
                if (state.connections.isNotEmpty()) {
                    IconButton(
                        modifier = Modifier.testTag("addConnection"),
                        onClick = actions.add,
                    ) { PlayerIcon(PlayerSymbol.ADD, "添加连接") }
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.connections.isEmpty() -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                item { LibraryEmptyState(PlayerSymbol.MODEL, "连接你的第一个模型", "填写请求 URL，选择请求格式，然后获取模型。") {
                    Button(modifier = Modifier.testTag("addConnection"),
                        onClick = actions.add) { Text("添加连接") }
                } }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 24.dp),
            ) {
                state.activationError?.let { item { StatusText(it, error = true) } }
                items(state.connections, key = StoredConnection::id) { connection ->
                    val status = state.credentialStatuses[connection.id]
                    val host = addressHost(connection.streamEndpoint)
                    val description = listOf(connection.name, host).distinct().filter(String::isNotBlank).joinToString(" · ")
                    val issue = connectionIssue(status, connection.selectedModel)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { actions.edit(connection.id) }
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                connection.selectedModel.ifBlank { "选择模型" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            issue?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (connection.id == state.activeId) {
                            Text("✓ 当前使用", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        } else if (issue == null) {
                            TextButton(onClick = { actions.use(connection.id) }, enabled = state.activatingId == null,
                                modifier = Modifier.testTag("use-${connection.id}")) { Text("使用") }
                        }
                        PlayerIcon(PlayerSymbol.CHEVRON, modifier = Modifier.size(16.dp))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
    active: Boolean,
    probe: ProbeUiState,
    actions: ConnectionScreenActions,
) {
    val page = editor.page
    val ready = editor.credentialStatus == CredentialStatus.READY || editor.credentialStatus == CredentialStatus.NOT_REQUIRED
    val detail = page == ConnectionPage.DETAIL
    val dirty = editor.hasChanges(stored)
    var more by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    fun leave() {
        if (page == ConnectionPage.CREATE || detail) actions.closeEditor()
        else actions.showPage(ConnectionPage.DETAIL)
    }
    fun back() {
        if (!editor.saving) {
            if (dirty && !detail) confirmLeave = true else leave()
        }
    }
    BackHandler { back() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(when (page) {
                        ConnectionPage.CREATE -> "添加连接"
                        ConnectionPage.ADDRESS -> "地址与请求格式"
                        ConnectionPage.MODELS -> "选择模型"
                        else -> stored?.name.orEmpty()
                    }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                },
                navigationIcon = {
                    IconButton(onClick = ::back, enabled = !editor.saving) {
                        PlayerIcon(PlayerSymbol.CHEVRON, "返回", Modifier.size(20.dp).rotate(180f))
                    }
                },
                actions = {
                    if (detail && stored != null) Box {
                        IconButton(onClick = { more = true }) { PlayerIcon(PlayerSymbol.MORE, "更多操作") }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text("连接名称") }, onClick = { more = false; actions.showPage(ConnectionPage.NAME) })
                            if (stored.selectedModel.isNotBlank()) DropdownMenuItem(text = { Text("模型 Token 上限") },
                                onClick = { more = false; actions.showPage(ConnectionPage.LIMITS) })
                            DropdownMenuItem(text = { Text("删除连接", color = MaterialTheme.colorScheme.error) },
                                onClick = { more = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (page == ConnectionPage.MODELS) {
            ModelCatalogPage(Modifier.fillMaxSize().padding(padding), stored, editor, ready, active, actions)
        } else key(page == ConnectionPage.ADDRESS || page == ConnectionPage.CREATE) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag("connectionEditorList"),
                horizontalAlignment = Alignment.CenterHorizontally, contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                val section = Modifier.widthIn(max = 600.dp).fillMaxWidth()
                if (page == ConnectionPage.CREATE || page == ConnectionPage.ADDRESS) {
                    item("form") {
                        Column(section) {
                            ConnectionFields(editor.draft, actions)
                            if (page == ConnectionPage.CREATE) {
                                ConnectionInput(editor.credentialInput, actions.updateCredential, "API Key（可选）",
                                    Modifier.testTag("credential"), KeyboardType.Password)
                            } else if (stored?.credentialRef != null) {
                                val origin = remember(stored, editor.draft.apiAddress, editor.draft.protocol) {
                                    unapprovedOrigin(stored, editor.draft)
                                }
                                if (origin != null) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = editor.confirmCredentialReuse, onCheckedChange = actions.confirmReuse)
                                    Text("允许向 $origin 发送已保存的 Key")
                                }
                            }
                            editor.message?.let { Box(Modifier.padding(horizontal = 24.dp)) { StatusText(it, error = true) } }
                            Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = actions.save,
                                    enabled = !editor.saving && editor.draft.apiAddress.isNotBlank() && (page == ConnectionPage.CREATE || dirty),
                                    modifier = Modifier.testTag("saveConnection")) {
                                    Text(if (page == ConnectionPage.CREATE) "保存并获取模型" else "保存修改")
                                }
                                if (page == ConnectionPage.ADDRESS) TextButton(onClick = ::leave, enabled = !editor.saving) { Text("取消") }
                            }
                        }
                    }
                } else if (stored != null) {
                    item("model") {
                        Column(section) {
                            Row(Modifier.fillMaxWidth().clickable(enabled = detail, role = Role.Button) {
                                actions.showPage(ConnectionPage.MODELS)
                            }.testTag("modelPicker").padding(horizontal = 24.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(editor.selectionNotice ?: if (active) "当前使用" else "聊天模型", style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary)
                                    Text(stored.selectedModel.ifBlank { "选择模型" }, style = MaterialTheme.typography.titleLarge)
                                }
                                Text(if (stored.selectedModel.isBlank()) "选择" else "更换模型", style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                TextButton(onClick = if (probe.running) actions.cancelTest else actions.runTest,
                                    enabled = detail && (probe.running || (ready && stored.selectedModel.isNotBlank())),
                                    modifier = Modifier.testTag("testConnection")) {
                                    Text(if (probe.running) "取消测试" else "测试连接")
                                }
                                when {
                                    probe.running -> StatusText("正在等待模型回复…", error = false)
                                    probe.error != null -> StatusText(probe.error, error = true)
                                    probe.finishReason != null || probe.text.isNotEmpty() -> StatusText("连接正常", error = false)
                                }
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                        }
                    }
                    item("endpoint") {
                        Column(section) {
                            SelectionRow("请求 URL", stored.apiAddress, { actions.showPage(ConnectionPage.ADDRESS) }, enabled = detail)
                            SelectionRow("请求格式", protocolLabel(stored.protocol), { actions.showPage(ConnectionPage.ADDRESS) }, enabled = detail)
                        }
                    }
                    item("key") {
                        val keyArea = remember { BringIntoViewRequester() }
                        Column(section.bringIntoViewRequester(keyArea)) {
                            if (page == ConnectionPage.KEY) {
                                LaunchedEffect(Unit) { keyArea.bringIntoView() }
                                ConnectionInput(editor.credentialInput, actions.updateCredential, "新的 API Key",
                                    Modifier.testTag("credential"), KeyboardType.Password)
                                Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = actions.save, enabled = !editor.saving && editor.credentialInput.isNotBlank(),
                                        modifier = Modifier.testTag("saveKey")) { Text("保存 Key") }
                                    TextButton(onClick = ::leave, enabled = !editor.saving) { Text("取消") }
                                }
                                editor.message?.let { Box(Modifier.padding(horizontal = 24.dp)) { StatusText(it, error = true) } }
                            } else {
                                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 24.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("API Key", style = MaterialTheme.typography.titleMedium)
                                        Text(when (editor.credentialStatus) {
                                            CredentialStatus.READY -> "已设置"
                                            CredentialStatus.NOT_REQUIRED -> "未设置"
                                            CredentialStatus.MISSING -> "需要重新输入"
                                            CredentialStatus.ORIGIN_CONFIRMATION_REQUIRED -> "新地址待授权"
                                        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (editor.credentialStatus == CredentialStatus.ORIGIN_CONFIRMATION_REQUIRED)
                                        TextButton(onClick = { actions.showPage(ConnectionPage.ADDRESS) }, enabled = detail) { Text("确认地址") }
                                    TextButton(onClick = { actions.showPage(ConnectionPage.KEY) }, enabled = detail,
                                        modifier = Modifier.testTag("replaceCredential")) {
                                        Text(if (stored.credentialRef == null) "设置" else "更换")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (page == ConnectionPage.NAME) AlertDialog(
        onDismissRequest = { if (!editor.saving) leave() }, title = { Text("连接名称") },
        text = { Column {
            OutlinedTextField(editor.draft.name, actions.updateName, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("connectionName"),
                placeholder = { Text(addressHost(editor.draft.apiAddress)) })
            editor.message?.let { StatusText(it, error = true) }
        } },
        confirmButton = { TextButton(onClick = actions.save, enabled = !editor.saving && dirty) { Text("保存") } },
        dismissButton = { TextButton(onClick = ::leave, enabled = !editor.saving) { Text("取消") } },
    )
    if (page == ConnectionPage.LIMITS) ScrollableBottomSheet(
        onDismissRequest = { if (!editor.saving) leave() },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("模型上限", style = MaterialTheme.typography.titleLarge)
            TokenLimitFields(editor.draft, stored?.modelCache?.models?.firstOrNull { it.id == editor.draft.selectedModel }, actions)
            editor.message?.let { StatusText(it, error = true) }
            TextButton(onClick = actions.save, enabled = !editor.saving && dirty, modifier = Modifier.align(Alignment.End)) { Text("保存") }
        }
    }
    if (confirmDelete && stored != null) DeleteDialog(stored.name, { confirmDelete = false }, { actions.delete(stored.id) })
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false }, title = { Text("放弃未保存的修改？") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; leave() }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("继续编辑") } },
    )
}

private fun unapprovedOrigin(stored: StoredConnection, draft: ConnectionDraft): String? = runCatching {
    val endpoints = ConnectionEndpointResolver.resolve(draft.protocol, draft.apiAddress)
    val destination = stored.copy(protocol = draft.protocol, streamEndpoint = endpoints.streamEndpoint,
        catalogEndpoint = endpoints.catalogEndpoint)
    (destination.currentOrigins() - stored.approvedOrigins).singleOrNull()
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionFields(draft: ConnectionDraft, actions: ConnectionScreenActions) {
    var choosingFormat by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    ConnectionInput(draft.apiAddress, actions.updateApiAddress, "请求 URL", Modifier.testTag("apiAddress"),
        keyboardType = KeyboardType.Uri)
    SelectionRow("请求格式", protocolLabel(draft.protocol), onClick = {
        focus.clearFocus()
        choosingFormat = true
    })
    if (choosingFormat) {
        ScrollableBottomSheet(
            onDismissRequest = { choosingFormat = false },
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                Text("选择请求格式", Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.titleLarge)
                Column(Modifier.selectableGroup()) {
                    ConnectionTemplates.protocols.forEach { template ->
                        val selected = draft.protocol == template.protocol
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = {
                                choosingFormat = false
                                if (!selected) actions.chooseProtocol(template.id)
                            }).heightIn(min = 60.dp).padding(horizontal = 24.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(template.displayName, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionInput(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).then(modifier),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        visualTransformation = if (keyboardType == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
private fun SelectionRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 80.dp).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PlayerIcon(PlayerSymbol.CHEVRON, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun TokenLimitFields(draft: ConnectionDraft, model: StoredModel?, actions: ConnectionScreenActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            model?.let { "模型目录 · 上下文 ${it.inputTokenLimit ?: "未知"} · 输出 ${it.outputTokenLimit ?: "未知"}" }
                ?: "模型目录没有提供这个模型的 Token 上限",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = draft.contextTokenLimitOverride, onValueChange = actions.updateContextTokenLimit,
            label = { Text("Context 上限") }, placeholder = { Text("使用默认值") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("contextTokenLimitOverride"),
        )
        OutlinedTextField(
            value = draft.outputTokenLimitOverride, onValueChange = actions.updateOutputTokenLimit,
            label = { Text("单次回复上限") }, placeholder = { Text("使用默认值") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("outputTokenLimitOverride"),
        )
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

@Composable
private fun ModelCatalogPage(
    modifier: Modifier,
    stored: StoredConnection?,
    editor: ConnectionEditorState,
    ready: Boolean,
    active: Boolean,
    actions: ConnectionScreenActions,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf(false) }
    var modelId by rememberSaveable { mutableStateOf("") }
    val catalog = stored?.modelCache?.models.orEmpty()
    val selectedId = stored?.selectedModel.orEmpty()
    val models = remember(catalog, selectedId) {
        if (selectedId.isNotBlank() && catalog.none { it.id == selectedId }) {
            listOf(StoredModel(id = selectedId)) + catalog
        } else catalog
    }
    val matches = remember(models, search) {
        val needle = search.trim()
        models.filter { it.id.contains(needle, ignoreCase = true) || it.name.orEmpty().contains(needle, ignoreCase = true) }
    }
    Column(modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索模型") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions.refreshModels, enabled = ready && !editor.refreshingModels && !editor.saving) {
                Text(if (editor.refreshingModels) "正在获取…" else "刷新模型列表")
            }
            TextButton(onClick = { modelId = search.trim(); manual = true }, enabled = !editor.saving) { Text("输入模型 ID") }
        }
        editor.modelMessage?.let { StatusText("获取模型列表失败 · $it", error = true) }
        if (!manual) editor.message?.let { StatusText(it, error = true) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("catalogModels")) {
            if (matches.isEmpty() && !editor.refreshingModels) item {
                Text(if (models.isEmpty()) "暂无模型，可以手动输入模型 ID" else "没有匹配的模型",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(matches, key = StoredModel::id) { model ->
                val selected = model.id == stored?.selectedModel
                Row(Modifier.fillMaxWidth().selectable(selected, enabled = !editor.saving, role = Role.RadioButton) {
                    actions.selectModel(model.id)
                }.heightIn(min = 60.dp).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(model.name ?: model.id, style = MaterialTheme.typography.bodyLarge)
                        if (model.name != null && model.name != model.id) Text(model.id,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (selected && active) "当前使用" else "选用",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (selected) PlayerIcon(PlayerSymbol.CHECK)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    if (manual) AlertDialog(onDismissRequest = { if (!editor.saving) manual = false }, title = { Text("输入模型 ID") },
        text = { Column {
            OutlinedTextField(modelId, { modelId = it }, singleLine = true, label = { Text("模型 ID") },
                modifier = Modifier.fillMaxWidth().testTag("manualModelId"))
            editor.message?.let { StatusText(it, error = true) }
        } },
        confirmButton = { TextButton(onClick = { actions.selectModel(modelId) }, enabled = modelId.isNotBlank() && !editor.saving) { Text("使用模型") } },
        dismissButton = { TextButton(onClick = { manual = false }, enabled = !editor.saving) { Text("取消") } },
    )
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

private fun connectionIssue(status: CredentialStatus?, selectedModel: String): String? = when {
    status == CredentialStatus.MISSING -> "需要 API Key"
    status == CredentialStatus.ORIGIN_CONFIRMATION_REQUIRED -> "API 地址已更改"
    selectedModel.isBlank() -> "请选择模型"
    else -> null
}
