package io.github.zvensmoluya.tavernplayer.presets

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import io.github.zvensmoluya.tavernplayer.content.ContentRole
import io.github.zvensmoluya.tavernplayer.content.PresetAsset
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationSettings
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationTrigger
import io.github.zvensmoluya.tavernplayer.content.PresetInjectionPosition
import io.github.zvensmoluya.tavernplayer.content.PresetNamesBehavior
import io.github.zvensmoluya.tavernplayer.content.PresetPromptDefinition
import io.github.zvensmoluya.tavernplayer.content.PresetReasoningEffort
import io.github.zvensmoluya.tavernplayer.content.PresetVerbosity
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
    val togglePromptInOrder: (String) -> Unit = {},
    val setPromptEnabled: (String, Boolean) -> Unit = { _, _ -> },
    val movePrompt: (String, Int) -> Unit = { _, _ -> },
    val updateRegexEnabled: (String, Boolean) -> Unit = { _, _ -> },
    val save: () -> Unit = {},
    val activate: (String) -> Unit = {},
    val copy: (String) -> Unit = {},
    val delete: (String) -> Unit = {},
    val export: (PresetAsset) -> Unit = {},
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
            togglePromptInOrder = viewModel::togglePromptInOrder,
            setPromptEnabled = viewModel::setPromptEnabled,
            movePrompt = viewModel::movePrompt,
            updateRegexEnabled = { id, enabled -> viewModel.updateRegex(id) { it.copy(disabled = !enabled) } },
            save = viewModel::save,
            activate = viewModel::activate,
            copy = viewModel::copy,
            delete = viewModel::delete,
            export = { preset ->
                viewModel.exportPreset(preset.id)?.let { bytes ->
                    pendingExport = bytes
                    exportLauncher.launch("${preset.name.safeFileName()}.json")
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
                    busy = state.busy,
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
    busy: Boolean,
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
                "${preset.prompts.size} Prompt · ${preset.regexScripts.size} Regex · 回复 ${preset.generationSettings.maxOutputTokens}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                preset.compatibilitySummary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!active) {
                    TextButton(enabled = !busy, onClick = { actions.activate(preset.id) }) { Text("使用") }
                }
                TextButton(enabled = !busy, onClick = { actions.copy(preset.id) }) { Text("复制") }
                TextButton(enabled = !busy, onClick = { actions.export(preset) }) { Text("导出") }
            }
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
    var deleteConfirmation by remember(preset.id) { mutableStateOf(false) }
    val editable = !preset.builtIn && !state.busy
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (preset.builtIn) "内置 · 复制后编辑" else if (state.dirty) "未保存" else "已保存",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(modifier = Modifier.testTag("cancelPresetEdit"), onClick = actions.cancelEditor) {
                        Text("取消")
                    }
                },
                actions = {
                    TextButton(
                        modifier = Modifier.testTag("savePreset"),
                        enabled = editable && state.dirty,
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
                EditorSection("基本信息") {
                    OutlinedTextField(
                        value = preset.name,
                        onValueChange = { value -> actions.updateDraft { it.copy(name = value) } },
                        modifier = Modifier.fillMaxWidth().testTag("presetName"),
                        label = { Text("名称") },
                        enabled = editable,
                        singleLine = true,
                    )
                    Text("内容指纹 ${preset.contentSha256.take(12)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            item("generation") { GenerationEditor(preset, editable, actions) }
            item("controls") { ControlEditor(preset, editable, actions) }
            item("order-title") {
                Text("Prompt 顺序", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(preset.promptOrder, key = { "order-${it.identifier}" }) { entry ->
                val definition = preset.prompts.firstOrNull { it.identifier == entry.identifier }
                Card(Modifier.fillMaxWidth().testTag("order-${entry.identifier}")) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(
                            modifier = Modifier.testTag("order-enabled-${entry.identifier}"),
                            checked = entry.enabled,
                            enabled = editable,
                            onCheckedChange = { actions.setPromptEnabled(entry.identifier, it) },
                        )
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(definition?.name ?: entry.identifier)
                            Text(entry.identifier, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(
                            modifier = Modifier.testTag("order-up-${entry.identifier}"),
                            enabled = editable,
                            onClick = { actions.movePrompt(entry.identifier, -1) },
                        ) { Text("↑") }
                        TextButton(
                            modifier = Modifier.testTag("order-down-${entry.identifier}"),
                            enabled = editable,
                            onClick = { actions.movePrompt(entry.identifier, 1) },
                        ) { Text("↓") }
                        TextButton(enabled = editable, onClick = { actions.togglePromptInOrder(entry.identifier) }) { Text("移出") }
                    }
                }
            }
            val unused = preset.prompts.filter { definition ->
                preset.promptOrder.none { it.identifier == definition.identifier }
            }
            if (unused.isNotEmpty()) {
                item("unused-title") { Text("未使用定义", style = MaterialTheme.typography.titleMedium) }
                items(unused, key = { "unused-${it.identifier}" }) { definition ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(definition.name, Modifier.weight(1f))
                        TextButton(
                            enabled = editable,
                            onClick = { actions.togglePromptInOrder(definition.identifier) },
                        ) { Text("移入顺序") }
                    }
                }
            }
            item("prompts-title") {
                Text("Prompt 定义", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(preset.prompts, key = { "prompt-${it.identifier}" }) { prompt ->
                PromptEditor(prompt, preset, editable, actions)
            }
            item("regex-title") {
                Text("Preset Regex", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (preset.regexScripts.isEmpty()) item("regex-empty") { StatusText("没有 Preset Regex") }
            items(preset.regexScripts, key = { "regex-${it.id}" }) { regex ->
                Card(Modifier.fillMaxWidth().testTag("regex-${regex.id}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(regex.name, fontWeight = FontWeight.Medium)
                                Text(regex.findRegex, style = MaterialTheme.typography.bodySmall)
                                Text("→ ${regex.replaceString}", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                modifier = Modifier.testTag("regex-enabled-${regex.id}"),
                                checked = !regex.disabled,
                                enabled = editable,
                                onCheckedChange = { actions.updateRegexEnabled(regex.id, it) },
                            )
                        }
                    }
                }
            }
            item("actions") {
                EditorSection("资产操作") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !state.busy, onClick = { actions.copy(preset.id) }) { Text("复制") }
                        OutlinedButton(enabled = !state.busy, onClick = { actions.export(preset) }) { Text("导出") }
                        if (!preset.builtIn) {
                            OutlinedButton(enabled = !state.busy, onClick = { deleteConfirmation = true }) {
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }
    }
    if (deleteConfirmation) {
        AlertDialog(
            onDismissRequest = { deleteConfirmation = false },
            title = { Text("删除 ${preset.name}？") },
            text = { Text(if (preset.id == state.activePresetId) "删除后会立即回退到内置默认。" else "这项操作无法撤销。") },
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

@Composable
private fun GenerationEditor(preset: PresetAsset, editable: Boolean, actions: PresetScreenActions) {
    val settings = preset.generationSettings
    EditorSection("生成参数") {
        NullableIntField("Context 上限（空为模型上限）", settings.maxContextTokens, editable) { value ->
            actions.updateDraft { it.withGeneration { copy(maxContextTokens = value) } }
        }
        RequiredIntField("回复上限", settings.maxOutputTokens, editable) { value ->
            actions.updateDraft { it.withGeneration { copy(maxOutputTokens = value.coerceAtLeast(1)) } }
        }
        NullableDoubleField("Temperature", settings.temperature, editable) { value ->
            actions.updateDraft { it.withGeneration { copy(temperature = value) } }
        }
        NullableDoubleField("Top P", settings.topP, editable) { value ->
            actions.updateDraft { it.withGeneration { copy(topP = value) } }
        }
        NullableIntField("Top K", settings.topK, editable) { value ->
            actions.updateDraft { it.withGeneration { copy(topK = value) } }
        }
        NullableDoubleField("Top A（仅保留/导出）", settings.topA, editable) { value ->
            actions.updateDraft { it.withGeneration { copy(topA = value) } }
        }
        NullableDoubleField("Min P（仅保留/导出）", settings.minP, editable) { value ->
            actions.updateDraft { it.withGeneration { copy(minP = value) } }
        }
        NullableDoubleField("Repetition penalty（仅保留/导出）", settings.repetitionPenalty, editable) { value ->
            actions.updateDraft { it.withGeneration { copy(repetitionPenalty = value) } }
        }
        NullableDoubleField("Frequency penalty", settings.frequencyPenalty, editable) { value ->
            actions.updateDraft { it.withGeneration { copy(frequencyPenalty = value) } }
        }
        NullableDoubleField("Presence penalty", settings.presencePenalty, editable) { value ->
            actions.updateDraft { it.withGeneration { copy(presencePenalty = value) } }
        }
        NullableIntField("Seed（空为随机）", settings.seed, editable) { value ->
            actions.updateDraft { it.withGeneration { copy(seed = value) } }
        }
        EnumCycler("Reasoning effort", settings.reasoningEffort, editable) { value ->
            actions.updateDraft { it.withGeneration { copy(reasoningEffort = value) } }
        }
        EnumCycler("Verbosity", settings.verbosity, editable) { value ->
            actions.updateDraft { it.withGeneration { copy(verbosity = value) } }
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
private fun PromptEditor(
    prompt: PresetPromptDefinition,
    preset: PresetAsset,
    editable: Boolean,
    actions: PresetScreenActions,
) {
    val canEditDefinition = editable && !prompt.marker
    Card(Modifier.fillMaxWidth().testTag("prompt-${prompt.identifier}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(prompt.identifier, style = MaterialTheme.typography.labelSmall)
            OutlinedTextField(
                value = prompt.name,
                onValueChange = { value -> actions.updatePrompt(prompt.identifier) { it.copy(name = value) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("名称") },
                enabled = canEditDefinition,
                singleLine = true,
            )
            if (prompt.marker) {
                StatusText("Marker 定义：可调整顺序和启用状态，内容不可编辑。")
            } else {
                TextArea("内容", prompt.content, canEditDefinition) { value ->
                    actions.updatePrompt(prompt.identifier) { it.copy(content = value) }
                }
                EnumCycler("Role", prompt.role, canEditDefinition) { value ->
                    actions.updatePrompt(prompt.identifier) { it.copy(role = value) }
                }
                EnumCycler("Placement", prompt.injectionPosition, canEditDefinition) { value ->
                    actions.updatePrompt(prompt.identifier) { it.copy(injectionPosition = value) }
                }
                RequiredIntField("Depth", prompt.injectionDepth, canEditDefinition) { value ->
                    actions.updatePrompt(prompt.identifier) { it.copy(injectionDepth = value.coerceAtLeast(0)) }
                }
                RequiredIntField("Order", prompt.injectionOrder, canEditDefinition) { value ->
                    actions.updatePrompt(prompt.identifier) { it.copy(injectionOrder = value) }
                }
                BooleanControl("允许角色 override", !prompt.forbidOverrides, canEditDefinition) { allowed ->
                    actions.updatePrompt(prompt.identifier) { it.copy(forbidOverrides = !allowed) }
                }
                Text("Generation trigger", style = MaterialTheme.typography.labelLarge)
                PresetGenerationTrigger.entries.forEach { trigger ->
                    val checked = trigger in prompt.triggers
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            enabled = canEditDefinition,
                            onCheckedChange = { enabled ->
                                actions.updatePrompt(prompt.identifier) { definition ->
                                    definition.copy(
                                        triggers = if (enabled) definition.triggers + trigger else definition.triggers - trigger,
                                    )
                                }
                            },
                        )
                        Text(trigger.wireValue)
                    }
                }
            }
            val inOrder = preset.promptOrder.any { it.identifier == prompt.identifier }
            TextButton(
                enabled = editable,
                onClick = { actions.togglePromptInOrder(prompt.identifier) },
            ) { Text(if (inOrder) "移出 Prompt order" else "移入 Prompt order") }
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(
            enabled = enabled,
            onClick = { onChange(values[(values.indexOf(value) + 1) % values.size]) },
        ) { Text(value.name.lowercase()) }
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

private fun PresetAsset.compatibilitySummary(): String {
    val retainedOnly = buildList {
        if (generationSettings.topA != null && generationSettings.topA != 0.0) add("top_a")
        if (generationSettings.minP != null && generationSettings.minP != 0.0) add("min_p")
        if (generationSettings.repetitionPenalty != null && generationSettings.repetitionPenalty != 1.0) {
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
