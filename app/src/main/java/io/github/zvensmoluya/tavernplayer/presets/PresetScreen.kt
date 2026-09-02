package io.github.zvensmoluya.tavernplayer.presets

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zvensmoluya.tavernplayer.content.PresetAsset
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationParameter
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationSettings
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationTrigger
import io.github.zvensmoluya.tavernplayer.content.PresetPromptDefinition
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PresetScreenActions(
    val back: () -> Unit = {},
    val importFile: () -> Unit = {},
    val openEditor: (String) -> Unit = {},
    val cancelEditor: () -> Unit = {},
    val updateDraft: ((PresetAsset) -> PresetAsset) -> Unit = {},
    val updatePrompt: (String, (PresetPromptDefinition) -> PresetPromptDefinition) -> Unit = { _, _ -> },
    val setPromptEnabled: (String, Boolean) -> Unit = { _, _ -> },
    val updateRegexEnabled: (String, Boolean) -> Unit = { _, _ -> },
    val setGenerationParameterEnabled: (PresetGenerationParameter, Boolean) -> Unit = { _, _ -> },
    val save: () -> Unit = {},
    val saveAndClose: () -> Unit = {},
    val saveAs: (String) -> Unit = {},
    val restoreInitial: () -> Unit = {},
    val delete: (String) -> Unit = {},
    val exportCurrent: () -> Unit = {},
)

@Composable
fun PresetRoute(
    viewModel: PresetViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    BackHandler(enabled = state.draft == null, onBack = onBack)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri != null && bytes != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                            ?: error("无法打开导出文件")
                    }
                }.onFailure { viewModel.reportMessage(it.message ?: "Preset 导出失败") }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        readPresetBytes(context, uri) to queryFileName(context, uri)
                    }
                }.onSuccess { (bytes, name) -> viewModel.importPreset(bytes, name) }
                    .onFailure { viewModel.reportMessage(it.message ?: "Preset 文件读取失败") }
            }
        }
    }

    PresetScreen(
        state = state,
        actions = PresetScreenActions(
            back = onBack,
            importFile = { importLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
            openEditor = viewModel::openEditor,
            cancelEditor = viewModel::cancelEditor,
            updateDraft = viewModel::updateDraft,
            updatePrompt = viewModel::updatePrompt,
            setPromptEnabled = viewModel::setPromptEnabled,
            updateRegexEnabled = { id, enabled -> viewModel.updateRegex(id) { it.copy(disabled = !enabled) } },
            setGenerationParameterEnabled = viewModel::setGenerationParameterEnabled,
            save = viewModel::save,
            saveAndClose = viewModel::saveAndClose,
            saveAs = viewModel::saveAs,
            restoreInitial = viewModel::restoreInitial,
            delete = viewModel::delete,
            exportCurrent = {
                state.draft?.let { preset ->
                    viewModel.exportDraft()?.let { bytes ->
                        pendingExport = bytes
                        exportLauncher.launch("${preset.name.safeFileName()}.json")
                    }
                }
            },
        ),
    )
}

@Composable
fun PresetScreen(
    state: PresetUiState,
    actions: PresetScreenActions,
) {
    val draft = state.draft
    if (draft == null) {
        PresetCenter(state, actions)
    } else {
        PresetEditor(state, draft, actions)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetCenter(state: PresetUiState, actions: PresetScreenActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("预设") },
                navigationIcon = { TextButton(onClick = actions.back) { Text("返回") } },
                actions = {
                    TextButton(
                        modifier = Modifier.testTag("importPreset"),
                        enabled = !state.busy,
                        onClick = actions.importFile,
                    ) { Text("导入") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("presetList"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.message?.let { message -> item("message") { StatusText(message) } }
            if (state.importDiagnostics.isNotEmpty()) {
                item("diagnostics") {
                    StatusText("导入诊断：${state.importDiagnostics.joinToString { it.code }}")
                }
            }
            items(state.presets, key = PresetAsset::id) { preset ->
                PresetCard(
                    preset = preset,
                    active = preset.id == state.activePresetId,
                    actions = actions,
                )
            }
        }
    }
}

@Composable
private fun PresetCard(
    preset: PresetAsset,
    active: Boolean,
    actions: PresetScreenActions,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("preset-${preset.id}"),
        onClick = { actions.openEditor(preset.id) },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    preset.name,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        active -> "使用中"
                        preset.builtIn -> "内置"
                        else -> ""
                    },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                "${preset.quickPromptDefinitions().size} 个快速项 · ${preset.regexScripts.size} Regex · " +
                    preset.generationSettings.outputLimitSummary(),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                preset.compatibilitySummary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (active) "点击继续调整" else "点击切换并调整",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetEditor(
    state: PresetUiState,
    preset: PresetAsset,
    actions: PresetScreenActions,
) {
    var page by remember(preset.id) { mutableStateOf(PresetEditorPage.MAIN) }
    var promptDetailId by remember(preset.id) { mutableStateOf<String?>(null) }
    var regexDetailId by remember(preset.id) { mutableStateOf<String?>(null) }
    var leaveConfirmation by remember(preset.id) { mutableStateOf(false) }
    var deleteConfirmation by remember(preset.id) { mutableStateOf(false) }
    var restoreConfirmation by remember(preset.id) { mutableStateOf(false) }
    var saveAsVisible by remember(preset.id) { mutableStateOf(false) }
    var saveAsName by remember(preset.id) { mutableStateOf("${preset.name} 分支") }
    val editable = !state.busy

    fun backFromEditor() {
        when {
            promptDetailId != null -> promptDetailId = null
            regexDetailId != null -> regexDetailId = null
            page != PresetEditorPage.MAIN -> page = PresetEditorPage.MAIN
            state.dirty -> leaveConfirmation = true
            else -> actions.cancelEditor()
        }
    }

    BackHandler(onBack = ::backFromEditor)

    when {
        promptDetailId != null -> {
            val prompt = preset.prompts.firstOrNull { it.identifier == promptDetailId }
            if (prompt != null) {
                PromptDetailPage(prompt, editable, actions) { promptDetailId = null }
            } else {
                promptDetailId = null
            }
        }
        regexDetailId != null -> {
            val regex = preset.regexScripts.firstOrNull { it.id == regexDetailId }
            if (regex != null) {
                RegexDetailPage(regex.name, regex.findRegex, regex.replaceString) { regexDetailId = null }
            } else {
                regexDetailId = null
            }
        }
        page == PresetEditorPage.REQUEST_PARAMETERS -> GenerationParameterPage(
            preset = preset,
            editable = editable,
            actions = actions,
            onBack = { page = PresetEditorPage.MAIN },
        )
        page == PresetEditorPage.ADVANCED -> AdvancedPresetPage(
            preset = preset,
            editable = editable,
            actions = actions,
            onBack = { page = PresetEditorPage.MAIN },
        )
        else -> PresetMainEditor(
            state = state,
            preset = preset,
            editable = editable,
            actions = actions,
            onBack = ::backFromEditor,
            onOpenPrompt = { promptDetailId = it },
            onOpenRegex = { regexDetailId = it },
            onOpenParameters = { page = PresetEditorPage.REQUEST_PARAMETERS },
            onOpenAdvanced = { page = PresetEditorPage.ADVANCED },
            onSaveAs = {
                saveAsName = "${preset.name} 分支"
                saveAsVisible = true
            },
            onRestore = { restoreConfirmation = true },
            onDelete = { deleteConfirmation = true },
        )
    }

    if (leaveConfirmation) {
        AlertDialog(
            onDismissRequest = { leaveConfirmation = false },
            title = { Text("保存对 Preset 的修改？") },
            text = { Text("保存后，之后的生成会使用这些设置。") },
            confirmButton = {
                TextButton(onClick = {
                    leaveConfirmation = false
                    actions.saveAndClose()
                }) { Text("保存并返回") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = actions.cancelEditor) { Text("放弃修改") }
                    TextButton(onClick = { leaveConfirmation = false }) { Text("继续编辑") }
                }
            },
        )
    }
    if (saveAsVisible) {
        AlertDialog(
            onDismissRequest = { saveAsVisible = false },
            title = { Text("另存为新预设") },
            text = {
                OutlinedTextField(
                    value = saveAsName,
                    onValueChange = { saveAsName = it },
                    label = { Text("新名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("saveAsPresetName"),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = saveAsName.isNotBlank(),
                    onClick = {
                        saveAsVisible = false
                        actions.saveAs(saveAsName)
                    },
                ) { Text("创建并使用") }
            },
            dismissButton = { TextButton(onClick = { saveAsVisible = false }) { Text("取消") } },
        )
    }
    if (restoreConfirmation) {
        AlertDialog(
            onDismissRequest = { restoreConfirmation = false },
            title = { Text("恢复初始设置？") },
            text = { Text("当前草稿会恢复到导入、内置或创建分支时的状态；保存后才会生效。") },
            confirmButton = {
                TextButton(onClick = {
                    restoreConfirmation = false
                    actions.restoreInitial()
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { restoreConfirmation = false }) { Text("取消") } },
        )
    }
    if (deleteConfirmation) {
        AlertDialog(
            onDismissRequest = { deleteConfirmation = false },
            title = { Text("删除 ${preset.name}？") },
            text = { Text("删除后会立即切换到内置默认，这项操作无法撤销。") },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("confirmDeletePreset"),
                    onClick = {
                        deleteConfirmation = false
                        actions.delete(preset.id)
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirmation = false }) { Text("保留") } },
        )
    }
}

private enum class PresetEditorPage {
    MAIN,
    REQUEST_PARAMETERS,
    ADVANCED,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetMainEditor(
    state: PresetUiState,
    preset: PresetAsset,
    editable: Boolean,
    actions: PresetScreenActions,
    onBack: () -> Unit,
    onOpenPrompt: (String) -> Unit,
    onOpenRegex: (String) -> Unit,
    onOpenParameters: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onSaveAs: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val quickPrompts = preset.quickPromptDefinitions()
    val unusedPromptCount = preset.prompts.count { definition ->
        !definition.marker && preset.promptOrder.none { it.identifier == definition.identifier }
    }
    val enabledRequestParameters = PresetGenerationParameter.entries.count(
        preset.generationSettings::isEnabled,
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (state.dirty) "使用中 · 未保存" else "使用中 · 已保存",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(modifier = Modifier.testTag("cancelPresetEdit"), onClick = onBack) {
                        Text("返回")
                    }
                },
                actions = {
                    TextButton(
                        modifier = Modifier.testTag("savePreset"),
                        enabled = state.dirty && !state.busy,
                        onClick = actions.save,
                    ) { Text("保存") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("presetEditor"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.message?.let { item("message") { StatusText(it) } }
            item("identity") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("快速设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "开关决定哪些普通 Prompt 和 Regex 参与生成。内容编辑收在每一项的详情里。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item("quick-prompt-title") {
                Text("Prompt 开关", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (quickPrompts.isEmpty()) {
                item("quick-prompt-empty") { StatusText("这个 Preset 没有可供玩家调整的普通 Prompt。") }
            }
            items(quickPrompts, key = { "quick-${it.identifier}" }) { prompt ->
                val enabled = preset.promptOrder.firstOrNull { it.identifier == prompt.identifier }?.enabled == true
                PromptQuickSetting(
                    prompt = prompt,
                    enabled = enabled,
                    editable = editable,
                    onEnabledChange = { actions.setPromptEnabled(prompt.identifier, it) },
                    onOpenDetails = { onOpenPrompt(prompt.identifier) },
                )
            }
            if (unusedPromptCount > 0) {
                item("unused-prompt-note") {
                    StatusText("另有 $unusedPromptCount 个未进入编排的 Prompt 定义已完整保留；开关不会把它们追加到队列。")
                }
            }
            item("regex-title") {
                Text("文本处理开关", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (preset.regexScripts.isEmpty()) item("regex-empty") { StatusText("没有 Preset Regex") }
            items(preset.regexScripts, key = { "regex-${it.id}" }) { regex ->
                RegexQuickSetting(
                    name = regex.name,
                    id = regex.id,
                    enabled = !regex.disabled,
                    editable = editable,
                    onEnabledChange = { actions.updateRegexEnabled(regex.id, it) },
                    onOpenDetails = { onOpenRegex(regex.id) },
                )
            }
            item("secondary-settings") {
                EditorSection("较少使用") {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().testTag("openRequestParameters"),
                        onClick = onOpenParameters,
                    ) {
                        Text("请求参数 · $enabledRequestParameters 项开启")
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth().testTag("openPresetAdvanced"),
                        onClick = onOpenAdvanced,
                    ) { Text("名称、格式与结构") }
                }
            }
            item("actions") {
                EditorSection("预设操作") {
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !state.busy, onClick = onSaveAs) {
                        Text("另存为新预设")
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                        onClick = actions.exportCurrent,
                    ) { Text("导出当前 JSON") }
                    TextButton(modifier = Modifier.fillMaxWidth(), enabled = !state.busy, onClick = onRestore) {
                        Text("恢复初始设置")
                    }
                    if (!preset.builtIn) {
                        TextButton(modifier = Modifier.fillMaxWidth(), enabled = !state.busy, onClick = onDelete) {
                            Text("删除预设")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptQuickSetting(
    prompt: PresetPromptDefinition,
    enabled: Boolean,
    editable: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpenDetails: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("quick-prompt-${prompt.identifier}")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                modifier = Modifier.testTag("prompt-enabled-${prompt.identifier}"),
                checked = enabled,
                enabled = editable,
                onCheckedChange = onEnabledChange,
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(prompt.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    prompt.content.quickSummary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                modifier = Modifier.testTag("prompt-details-${prompt.identifier}"),
                onClick = onOpenDetails,
            ) { Text("详情") }
        }
    }
}

@Composable
private fun RegexQuickSetting(
    name: String,
    id: String,
    enabled: Boolean,
    editable: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpenDetails: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("regex-$id")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                modifier = Modifier.testTag("regex-enabled-$id"),
                checked = enabled,
                enabled = editable,
                onCheckedChange = onEnabledChange,
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Preset Regex", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onOpenDetails) { Text("查看") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptDetailPage(
    prompt: PresetPromptDefinition,
    editable: Boolean,
    actions: PresetScreenActions,
    onBack: () -> Unit,
) {
    var advanced by remember(prompt.identifier) { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(prompt.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("promptDetail-${prompt.identifier}"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("title") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Prompt 详情", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(prompt.identifier, style = MaterialTheme.typography.labelSmall)
                }
            }
            item("name") {
                OutlinedTextField(
                    value = prompt.name,
                    onValueChange = { value -> actions.updatePrompt(prompt.identifier) { it.copy(name = value) } },
                    modifier = Modifier.fillMaxWidth().testTag("promptName-${prompt.identifier}"),
                    label = { Text("名称") },
                    enabled = editable,
                    singleLine = true,
                )
            }
            item("content") {
                TextArea("内容", prompt.content, editable) { value ->
                    actions.updatePrompt(prompt.identifier) { it.copy(content = value) }
                }
            }
            item("advanced-toggle") {
                TextButton(
                    modifier = Modifier.testTag("showPromptAdvanced"),
                    onClick = { advanced = !advanced },
                ) { Text(if (advanced) "收起高级设置" else "高级设置") }
            }
            if (advanced) {
                item("advanced") {
                    EditorSection("兼容字段") {
                        EnumCycler("Role", prompt.role, editable) { value ->
                            actions.updatePrompt(prompt.identifier) { it.copy(role = value) }
                        }
                        EnumCycler("Placement", prompt.injectionPosition, editable) { value ->
                            actions.updatePrompt(prompt.identifier) { it.copy(injectionPosition = value) }
                        }
                        RequiredIntField("Depth", prompt.injectionDepth, editable) { value ->
                            actions.updatePrompt(prompt.identifier) { it.copy(injectionDepth = value.coerceAtLeast(0)) }
                        }
                        RequiredIntField("深度注入次序", prompt.injectionOrder, editable) { value ->
                            actions.updatePrompt(prompt.identifier) { it.copy(injectionOrder = value) }
                        }
                        BooleanControl("允许角色 override", !prompt.forbidOverrides, editable) { allowed ->
                            actions.updatePrompt(prompt.identifier) { it.copy(forbidOverrides = !allowed) }
                        }
                        Text("Generation trigger", style = MaterialTheme.typography.labelLarge)
                        PresetGenerationTrigger.entries.forEach { trigger ->
                            val checked = trigger in prompt.triggers
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = checked,
                                    enabled = editable,
                                    onCheckedChange = { enabled ->
                                        actions.updatePrompt(prompt.identifier) { definition ->
                                            definition.copy(
                                                triggers = if (enabled) {
                                                    definition.triggers + trigger
                                                } else {
                                                    definition.triggers - trigger
                                                },
                                            )
                                        }
                                    },
                                )
                                Text(trigger.wireValue)
                            }
                        }
                        StatusText("Prompt 在编排中的位置保持不变；启停只修改 enabled。")
                    }
                }
            }
            item("done") {
                TextButton(
                    modifier = Modifier.fillMaxWidth().testTag("closePromptDetail"),
                    onClick = onBack,
                ) { Text("完成") }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegexDetailPage(
    name: String,
    findRegex: String,
    replaceString: String,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            StatusText("Regex 内容只读；开关位于快速设置。")
            Text("匹配", style = MaterialTheme.typography.labelLarge)
            Text(findRegex, style = MaterialTheme.typography.bodySmall)
            Text("替换", style = MaterialTheme.typography.labelLarge)
            Text(replaceString, style = MaterialTheme.typography.bodySmall)
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = onBack) { Text("完成") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenerationParameterPage(
    preset: PresetAsset,
    editable: Boolean,
    actions: PresetScreenActions,
    onBack: () -> Unit,
) {
    val settings = preset.generationSettings
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("请求参数") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("requestParameterSheet"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("title") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("请求参数", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "开启表示允许 Preset 向兼容 Provider 携带该字段；不支持的模型会在请求边界安全省略。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item("output") {
                GenerationParameterControl(
                    parameter = PresetGenerationParameter.OUTPUT_LIMIT,
                    label = "回复上限",
                    value = settings.maxOutputTokens.toString(),
                    settings = settings,
                    editable = editable,
                    actions = actions,
                ) {
                    RequiredIntField("Token", settings.maxOutputTokens, editable) { value ->
                        actions.updateDraft { it.withGeneration { copy(maxOutputTokens = value.coerceAtLeast(1)) } }
                    }
                }
            }
            item("temperature") {
                GenerationParameterControl(
                    PresetGenerationParameter.TEMPERATURE,
                    "Temperature",
                    settings.temperature?.toString().orEmpty(),
                    settings,
                    editable,
                    actions,
                ) {
                    NullableDoubleField("值", settings.temperature, editable) { value ->
                        actions.updateDraft { it.withGeneration { copy(temperature = value) } }
                    }
                }
            }
            item("top-p") {
                GenerationParameterControl(
                    PresetGenerationParameter.TOP_P,
                    "Top P",
                    settings.topP?.toString().orEmpty(),
                    settings,
                    editable,
                    actions,
                ) {
                    NullableDoubleField("值", settings.topP, editable) { value ->
                        actions.updateDraft { it.withGeneration { copy(topP = value) } }
                    }
                }
            }
            item("top-k") {
                GenerationParameterControl(
                    PresetGenerationParameter.TOP_K,
                    "Top K",
                    settings.topK?.toString().orEmpty(),
                    settings,
                    editable,
                    actions,
                ) {
                    NullableIntField("值", settings.topK, editable) { value ->
                        actions.updateDraft { it.withGeneration { copy(topK = value) } }
                    }
                }
            }
            item("top-a") {
                GenerationParameterControl(
                    PresetGenerationParameter.TOP_A,
                    "Top A · 仅保留/导出",
                    settings.topA?.toString().orEmpty(),
                    settings,
                    editable,
                    actions,
                ) {
                    NullableDoubleField("值", settings.topA, editable) { value ->
                        actions.updateDraft { it.withGeneration { copy(topA = value) } }
                    }
                }
            }
            item("min-p") {
                GenerationParameterControl(
                    PresetGenerationParameter.MIN_P,
                    "Min P · 仅保留/导出",
                    settings.minP?.toString().orEmpty(),
                    settings,
                    editable,
                    actions,
                ) {
                    NullableDoubleField("值", settings.minP, editable) { value ->
                        actions.updateDraft { it.withGeneration { copy(minP = value) } }
                    }
                }
            }
            item("repetition") {
                GenerationParameterControl(
                    PresetGenerationParameter.REPETITION_PENALTY,
                    "Repetition penalty · 仅保留/导出",
                    settings.repetitionPenalty?.toString().orEmpty(),
                    settings,
                    editable,
                    actions,
                ) {
                    NullableDoubleField("值", settings.repetitionPenalty, editable) { value ->
                        actions.updateDraft { it.withGeneration { copy(repetitionPenalty = value) } }
                    }
                }
            }
            item("frequency") {
                GenerationParameterControl(
                    PresetGenerationParameter.FREQUENCY_PENALTY,
                    "Frequency penalty",
                    settings.frequencyPenalty?.toString().orEmpty(),
                    settings,
                    editable,
                    actions,
                ) {
                    NullableDoubleField("值", settings.frequencyPenalty, editable) { value ->
                        actions.updateDraft { it.withGeneration { copy(frequencyPenalty = value) } }
                    }
                }
            }
            item("presence") {
                GenerationParameterControl(
                    PresetGenerationParameter.PRESENCE_PENALTY,
                    "Presence penalty",
                    settings.presencePenalty?.toString().orEmpty(),
                    settings,
                    editable,
                    actions,
                ) {
                    NullableDoubleField("值", settings.presencePenalty, editable) { value ->
                        actions.updateDraft { it.withGeneration { copy(presencePenalty = value) } }
                    }
                }
            }
            item("seed") {
                GenerationParameterControl(
                    PresetGenerationParameter.SEED,
                    "Seed",
                    settings.seed?.toString() ?: "随机",
                    settings,
                    editable,
                    actions,
                ) {
                    NullableIntField("值", settings.seed, editable) { value ->
                        actions.updateDraft { it.withGeneration { copy(seed = value) } }
                    }
                }
            }
            item("reasoning") {
                GenerationParameterControl(
                    PresetGenerationParameter.REASONING_EFFORT,
                    "Reasoning effort",
                    settings.reasoningEffort.wireValue,
                    settings,
                    editable,
                    actions,
                ) {
                    EnumCycler("值", settings.reasoningEffort, editable) { value ->
                        actions.updateDraft { it.withGeneration { copy(reasoningEffort = value) } }
                    }
                }
            }
            item("verbosity") {
                GenerationParameterControl(
                    PresetGenerationParameter.VERBOSITY,
                    "Verbosity",
                    settings.verbosity.wireValue,
                    settings,
                    editable,
                    actions,
                ) {
                    EnumCycler("值", settings.verbosity, editable) { value ->
                        actions.updateDraft { it.withGeneration { copy(verbosity = value) } }
                    }
                }
            }
            item("context") {
                EditorSection("本地上下文预算") {
                    BooleanControl(
                        "限制 Context",
                        settings.maxContextTokens != null,
                        editable,
                    ) { enabled ->
                        actions.updateDraft {
                            it.withGeneration { copy(maxContextTokens = if (enabled) maxContextTokens ?: 32_768 else null) }
                        }
                    }
                    settings.maxContextTokens?.let { limit ->
                        RequiredIntField("Context 上限", limit, editable) { value ->
                            actions.updateDraft { it.withGeneration { copy(maxContextTokens = value.coerceAtLeast(1)) } }
                        }
                    }
                    StatusText("这是播放器的本地裁剪预算，不会作为模型请求字段发送。")
                }
            }
            item("done") {
                TextButton(
                    modifier = Modifier.fillMaxWidth().testTag("closeRequestParameters"),
                    onClick = onBack,
                ) { Text("完成") }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun GenerationParameterControl(
    parameter: PresetGenerationParameter,
    label: String,
    value: String,
    settings: PresetGenerationSettings,
    editable: Boolean,
    actions: PresetScreenActions,
    content: @Composable ColumnScope.() -> Unit,
) {
    val enabled = settings.isEnabled(parameter)
    Card(Modifier.fillMaxWidth().testTag("parameter-${parameter.name.lowercase()}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, fontWeight = FontWeight.Medium)
                    Text(
                        if (enabled) value.ifBlank { "已开启" } else "已关闭 · 保留值 ${value.ifBlank { "—" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    modifier = Modifier.testTag("parameter-enabled-${parameter.name.lowercase()}"),
                    checked = enabled,
                    enabled = editable,
                    onCheckedChange = { actions.setGenerationParameterEnabled(parameter, it) },
                )
            }
            if (enabled) {
                HorizontalDivider()
                content()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedPresetPage(
    preset: PresetAsset,
    editable: Boolean,
    actions: PresetScreenActions,
    onBack: () -> Unit,
) {
    val definitions = preset.prompts.associateBy(PresetPromptDefinition::identifier)
    val structuralPrompts = preset.promptOrder.mapNotNull { entry ->
        definitions[entry.identifier]?.takeIf(PresetPromptDefinition::marker)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("名称、格式与结构") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("presetAdvancedSheet"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("title") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("名称、格式与结构", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    StatusText("这些字段通常无需调整；保留用于兼容少数社区 Preset。")
                }
            }
            item("name") {
                OutlinedTextField(
                    value = preset.name,
                    onValueChange = { value -> actions.updateDraft { it.copy(name = value) } },
                    modifier = Modifier.fillMaxWidth().testTag("presetName"),
                    label = { Text("名称") },
                    enabled = editable,
                    singleLine = true,
                )
            }
            item("controls") { ControlEditor(preset, editable, actions) }
            if (structuralPrompts.isNotEmpty()) {
                item("structure-title") {
                    Text("结构插槽", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                items(structuralPrompts, key = { "structure-${it.identifier}" }) { prompt ->
                    val enabled = preset.promptOrder.firstOrNull { it.identifier == prompt.identifier }?.enabled == true
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Switch(
                                checked = enabled,
                                enabled = editable,
                                onCheckedChange = { actions.setPromptEnabled(prompt.identifier, it) },
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(prompt.name)
                                Text(prompt.identifier, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            item("done") {
                TextButton(
                    modifier = Modifier.fillMaxWidth().testTag("closePresetAdvanced"),
                    onClick = onBack,
                ) { Text("完成") }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun ControlEditor(preset: PresetAsset, editable: Boolean, actions: PresetScreenActions) {
    val controls = preset.controlSettings
    EditorSection("控制 Prompt 与格式") {
        TextArea("New chat prompt", controls.newChatPrompt, editable) { value ->
            actions.updateDraft { it.copy(controlSettings = it.controlSettings.copy(newChatPrompt = value)) }
        }
        TextArea("New example prompt", controls.newExampleChatPrompt, editable) { value ->
            actions.updateDraft { it.copy(controlSettings = it.controlSettings.copy(newExampleChatPrompt = value)) }
        }
        TextArea("Assistant prefill", controls.assistantPrefill, editable) { value ->
            actions.updateDraft { it.copy(controlSettings = it.controlSettings.copy(assistantPrefill = value)) }
        }
        TextArea("World info format", controls.worldInfoFormat, editable) { value ->
            actions.updateDraft { it.copy(controlSettings = it.controlSettings.copy(worldInfoFormat = value)) }
        }
        TextArea("Scenario format", controls.scenarioFormat, editable) { value ->
            actions.updateDraft { it.copy(controlSettings = it.controlSettings.copy(scenarioFormat = value)) }
        }
        TextArea("Personality format", controls.personalityFormat, editable) { value ->
            actions.updateDraft { it.copy(controlSettings = it.controlSettings.copy(personalityFormat = value)) }
        }
        EnumCycler("Names behavior", controls.namesBehavior, editable) { value ->
            actions.updateDraft { it.copy(controlSettings = it.controlSettings.copy(namesBehavior = value)) }
        }
        BooleanControl("合并连续 system 消息", controls.squashSystemMessages, editable) { value ->
            actions.updateDraft { it.copy(controlSettings = it.controlSettings.copy(squashSystemMessages = value)) }
        }
        BooleanControl("显示 reasoning", controls.showThoughts, editable) { value ->
            actions.updateDraft { it.copy(controlSettings = it.controlSettings.copy(showThoughts = value)) }
        }
    }
}

@Composable
private fun EditorSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun TextArea(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        minLines = 2,
        maxLines = 6,
    )
}

@Composable
private fun RequiredIntField(label: String, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { raw -> raw.toIntOrNull()?.let(onChange) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
    )
}

@Composable
private fun NullableIntField(label: String, value: Int?, enabled: Boolean, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { raw -> if (raw.isBlank()) onChange(null) else raw.toIntOrNull()?.let(onChange) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
    )
}

@Composable
private fun NullableDoubleField(label: String, value: Double?, enabled: Boolean, onChange: (Double?) -> Unit) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { raw -> if (raw.isBlank()) onChange(null) else raw.toDoubleOrNull()?.let(onChange) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
    )
}

@Composable
private inline fun <reified T : Enum<T>> EnumCycler(
    label: String,
    value: T,
    enabled: Boolean,
    crossinline onChange: (T) -> Unit,
) {
    val values = enumValues<T>()
    var expanded by remember(value) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Box {
            OutlinedButton(enabled = enabled, onClick = { expanded = true }) {
                Text(value.name.lowercase())
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                values.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name.lowercase()) },
                        onClick = {
                            expanded = false
                            onChange(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BooleanControl(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun StatusText(message: String) {
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun PresetAsset.withGeneration(transform: PresetGenerationSettings.() -> PresetGenerationSettings): PresetAsset =
    copy(generationSettings = generationSettings.transform())

private fun PresetAsset.quickPromptDefinitions(): List<PresetPromptDefinition> {
    val definitions = prompts.associateBy(PresetPromptDefinition::identifier)
    return promptOrder.mapNotNull { entry -> definitions[entry.identifier]?.takeUnless(PresetPromptDefinition::marker) }
}

private fun String.quickSummary(): String = replace(Regex("\\s+"), " ")
    .trim()
    .take(120)
    .ifBlank { "没有额外内容" }

private fun PresetGenerationSettings.outputLimitSummary(): String =
    if (isEnabled(PresetGenerationParameter.OUTPUT_LIMIT)) "回复 $maxOutputTokens" else "回复上限关闭"

private fun PresetAsset.compatibilitySummary(): String {
    val retainedOnly = buildList {
        if (
            generationSettings.isEnabled(PresetGenerationParameter.TOP_A) &&
            generationSettings.topA != null && generationSettings.topA != 0.0
        ) add("top_a")
        if (
            generationSettings.isEnabled(PresetGenerationParameter.MIN_P) &&
            generationSettings.minP != null && generationSettings.minP != 0.0
        ) add("min_p")
        if (
            generationSettings.isEnabled(PresetGenerationParameter.REPETITION_PENALTY) &&
            generationSettings.repetitionPenalty != null && generationSettings.repetitionPenalty != 1.0
        ) {
            add("repetition penalty")
        }
        if (controlSettings.assistantPrefill.isNotBlank()) add("prefill 按协议降级")
    }
    return when {
        retainedOnly.isNotEmpty() -> "运行兼容：${retainedOnly.joinToString()} 将保留、导出或按能力省略"
        diagnostics.isNotEmpty() -> "${diagnostics.size} 条兼容提示；请求按模型能力安全降级"
        else -> "五种协议按模型能力安全映射"
    }
}

private fun readPresetBytes(context: Context, uri: Uri): ByteArray {
    val input = context.contentResolver.openInputStream(uri) ?: error("无法打开 Preset 文件")
    return input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_PRESET_BYTES) error("Preset 超过 32 MiB 导入上限")
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }
}

private fun queryFileName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index)
        }
    }
    return uri.lastPathSegment ?: "Preset.json"
}

private fun String.safeFileName(): String = replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Preset" }

private const val MAX_PRESET_BYTES = 32 * 1024 * 1024
