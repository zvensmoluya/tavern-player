package io.github.zvensmoluya.tavernplayer.characters

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.content.CharacterCardImporter
import io.github.zvensmoluya.tavernplayer.content.CompatibilityDiagnostic
import io.github.zvensmoluya.tavernplayer.content.CompatibilitySeverity
import io.github.zvensmoluya.tavernplayer.conversation.ConversationRecord
import io.github.zvensmoluya.tavernplayer.conversation.Persona
import io.github.zvensmoluya.tavernplayer.conversation.SafeMarkdownText
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CharacterLibraryRoute(
    state: CharacterLibraryUiState,
    viewModel: CharacterLibraryViewModel,
    onSelectCharacter: (String) -> Unit,
    onOpenModels: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenPersona: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingShelfUrl by remember { mutableStateOf<String?>(null) }
    val localNetworkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val url = pendingShelfUrl
        pendingShelfUrl = null
        if (granted && url != null) {
            viewModel.importFromShelf(url)
        } else if (!granted) {
            viewModel.reportMessage("需要本地网络权限才能连接 Tavern Shelf")
        }
    }
    fun receiveShelfUrl(url: String) {
        if (
            Build.VERSION.SDK_INT >= 37 &&
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingShelfUrl = url
            localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            viewModel.importFromShelf(url)
        }
    }
    val shelfScanner = remember(context) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(context, options)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { context.readCharacterCard(uri) }
            }.onSuccess { (bytes, name) ->
                viewModel.import(bytes, name)
            }.onFailure { error ->
                viewModel.reportMessage(error.message ?: "无法读取角色卡")
            }
        }
    }
    CharacterLibraryScreen(
        state = state,
        avatarPath = viewModel::avatarPath,
        onImport = {
            launcher.launch(arrayOf("image/png", "application/json", "text/json", "application/octet-stream"))
        },
        onImportFromShelf = {
            shelfScanner.startScan()
                .addOnSuccessListener { barcode ->
                    barcode.rawValue?.takeIf(String::isNotBlank)?.let(::receiveShelfUrl)
                        ?: viewModel.reportMessage("二维码中没有可用的 Shelf 地址")
                }
                .addOnFailureListener { error ->
                    viewModel.reportMessage(error.message ?: "无法启动二维码扫描")
                }
        },
        onSelectCharacter = onSelectCharacter,
        onOpenModels = onOpenModels,
        onOpenPresets = onOpenPresets,
        onOpenPersona = onOpenPersona,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterLibraryScreen(
    state: CharacterLibraryUiState,
    avatarPath: (String) -> String?,
    onImport: () -> Unit,
    onImportFromShelf: () -> Unit,
    onSelectCharacter: (String) -> Unit,
    onOpenModels: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenPersona: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("角色") },
                actions = {
                    TextButton(onClick = onOpenPresets, modifier = Modifier.testTag("openPresetsFromLibrary")) {
                        Text("预设")
                    }
                    TextButton(onClick = onOpenModels, modifier = Modifier.testTag("openModelsFromLibrary")) {
                        Text("模型")
                    }
                    TextButton(
                        onClick = onImportFromShelf,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("importFromShelf"),
                    ) { Text("Shelf") }
                    TextButton(
                        onClick = onImport,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("importCharacter"),
                    ) { Text("导入") }
                },
            )
        },
    ) { padding ->
        if (state.characters.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("还没有角色", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "导入 PNG 或 JSON Character Card。没有模型连接也可以先浏览角色内容。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onImport, enabled = !state.busy) {
                    if (state.importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("选择角色卡")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onImportFromShelf, enabled = !state.busy) {
                    Text("扫描 Tavern Shelf")
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onOpenPersona, modifier = Modifier.testTag("openPersona")) {
                    Text("编辑我的身份 · ${state.persona.name}")
                }
                state.message?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, modifier = Modifier.testTag("libraryNotice"), color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("persona") {
                    PersonaSummaryCard(state.persona, onOpenPersona)
                }
                state.message?.let { notice ->
                    item("notice") {
                        Text(
                            notice,
                            modifier = Modifier.fillMaxWidth().testTag("libraryNotice"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.characters, key = CharacterAsset::id) { character ->
                    CharacterCardRow(
                        character = character,
                        avatarPath = avatarPath(character.id),
                        conversationCount = state.conversationsFor(character.id).size,
                        onClick = { onSelectCharacter(character.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonaSummaryCard(persona: Persona, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("openPersona"),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(persona.name.trim().take(1).ifBlank { "我" }, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("我的身份 · ${persona.name}", style = MaterialTheme.typography.titleMedium)
                Text(
                    persona.description.trim().takeIf(String::isNotEmpty) ?: "还没有填写身份描述",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("编辑", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun CharacterCardRow(
    character: CharacterAsset,
    avatarPath: String?,
    conversationCount: Int,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("character-${character.id}"),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CharacterAvatar(avatarPath, character.name, Modifier.size(76.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(character.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val subtitle = buildList {
                    add(character.cardGeneration.name)
                    if (character.worldBooks.sumOf { it.entries.size } > 0) {
                        add("${character.worldBooks.sumOf { it.entries.size }} 条世界书")
                    }
                    if (character.regexScripts.isNotEmpty()) add("${character.regexScripts.size} 个 Regex")
                }.joinToString(" · ")
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (conversationCount == 0) "尚未开始对话" else "$conversationCount 个对话",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterDetailScreen(
    character: CharacterAsset,
    conversations: List<ConversationRecord>,
    avatarPath: String?,
    onBack: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onInstallAdaptation: (ByteArray) -> Unit = {},
    onImportError: (String) -> Unit = {},
    importing: Boolean = false,
    message: String? = null,
    compiling: Boolean = false,
    compilationSaving: Boolean = false,
    compilationConnections: List<io.github.zvensmoluya.tavernplayer.connections.StoredConnection> = emptyList(),
    compilationConnectionId: String? = null,
    onSelectCompilationConnection: (String) -> Unit = {},
    onCompile: () -> Unit = {},
    onCancelCompilation: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    onReadWorldBooks: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adaptationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val result = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(result.size() + count <= 1024 * 1024) { "适配文件超过 1 MiB" }
                        result.write(buffer, 0, count)
                    }
                    result.toByteArray()
                } ?: error("无法读取适配文件")
            } }.onSuccess(onInstallAdaptation).onFailure { onImportError(it.message ?: "无法读取适配文件") }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(character.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("characterDetail"),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("identity") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CharacterAvatar(avatarPath, character.name, Modifier.size(112.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(character.name, style = MaterialTheme.typography.headlineSmall)
                        if (character.promptName != character.name) {
                            Text("聊天名：${character.promptName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        listOfNotNull(
                            character.creator.takeIf(String::isNotBlank)?.let { "作者 $it" },
                            character.characterVersion.takeIf(String::isNotBlank)?.let { "版本 $it" },
                            character.specVersion?.let { "CC ${character.cardGeneration.name} · $it" },
                        ).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item("new") {
                Button(
                    onClick = onNewConversation,
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth().testTag("newConversation"),
                ) { Text("开始新对话") }
            }
            item("world-books") {
                Card(
                    onClick = onReadWorldBooks,
                    modifier = Modifier.fillMaxWidth().testTag("readWorldBooks"),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("世界书", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (character.worldBooks.isEmpty()) "这张角色卡没有附带世界书"
                                else "${character.worldBooks.size} 本 · ${character.worldBooks.sumOf { it.entries.size }} 个条目",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("阅读", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item("native-adaptation") {
                DetailSection("原生适配") {
                    var choosingModel by remember { mutableStateOf(false) }
                    val connection = compilationConnections.firstOrNull { it.id == compilationConnectionId }
                    Text("将卡片中的表单、状态和资料准备为原生玩法。", style = MaterialTheme.typography.bodySmall)
                    Box {
                        TextButton(onClick = { choosingModel = true }, enabled = !importing && compilationConnections.isNotEmpty(),
                            modifier = Modifier.testTag("compilationModel")) {
                            Text(connection?.let { "适配模型：${it.selectedModel} · ${it.name}" } ?: "尚未选择适配模型")
                        }
                        DropdownMenu(expanded = choosingModel, onDismissRequest = { choosingModel = false }) {
                            compilationConnections.forEach { option ->
                                DropdownMenuItem(text = { Text("${option.selectedModel} · ${option.name}") }, onClick = {
                                    choosingModel = false
                                    onSelectCompilationConnection(option.id)
                                })
                            }
                        }
                    }
                    if (compilationConnections.isEmpty()) {
                        TextButton(onClick = onOpenModels, enabled = !importing) { Text("配置模型") }
                    }
                    if (compiling) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = onCancelCompilation, enabled = !compilationSaving,
                            modifier = Modifier.testTag("cancelCompilation")) { Text(if (compilationSaving) "正在保存…" else "停止适配") }
                    } else {
                        Button(onClick = onCompile, enabled = !importing && connection != null,
                            modifier = Modifier.testTag("compileNativeAdaptation")) {
                            Text(if (character.nativeAdaptation == null) "准备游玩" else "重新适配")
                        }
                    }
                    OutlinedButton(
                        onClick = { adaptationPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        enabled = !importing,
                        modifier = Modifier.testTag("installNativeAdaptation"),
                    ) { Text("导入原生适配文件") }
                    if (message != null) Text(message, style = MaterialTheme.typography.bodySmall)
                    val adaptation = character.nativeAdaptation
                    if (adaptation == null) {
                        Text("暂无原生适配；原始卡片仍可按文字角色卡使用。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(adaptation.report.summary.ifBlank { "已安装经过本地校验的原生适配。" })
                        if (adaptation.report.restoredBehaviors.isNotEmpty()) {
                            Text("已恢复：${adaptation.report.restoredBehaviors.joinToString("；")}")
                        }
                        if (adaptation.report.degradedPresentation.isNotEmpty()) {
                            Text("表现降级：${adaptation.report.degradedPresentation.joinToString("；")}")
                        }
                        if (adaptation.report.unsupportedBehaviors.isNotEmpty()) {
                            Text("暂不支持：${adaptation.report.unsupportedBehaviors.joinToString("；")}")
                        }
                        adaptation.report.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (character.creatorNotes.isNotBlank()) {
                item("notes") { DetailSection("作者说明") { SafeMarkdownText(character.creatorNotes) } }
            }
            if (character.description.isNotBlank() || character.personality.isNotBlank() || character.scenario.isNotBlank()) {
                item("definition") {
                    DetailSection("角色定义") {
                        CharacterField("描述", character.description)
                        CharacterField("性格", character.personality)
                        CharacterField("场景", character.scenario)
                    }
                }
            }
            if (character.firstMessage.isNotBlank() || character.alternateFirstMessages.isNotEmpty()) {
                item("greetings") {
                    DetailSection("开场") {
                        CharacterField("主开场", character.firstMessage)
                        character.alternateFirstMessages.forEachIndexed { index, greeting ->
                            CharacterField("备用开场 ${index + 1}", greeting)
                        }
                    }
                }
            }
            if (character.rawMessageExamples.isNotBlank()) {
                item("examples") {
                    DetailSection("示例对话") { SafeMarkdownText(character.rawMessageExamples) }
                }
            }
            if (
                character.systemPrompt.isNotBlank() ||
                character.postHistoryInstructions.isNotBlank() ||
                character.depthPrompt?.content?.isNotBlank() == true
            ) {
                item("prompt-overrides") {
                    DetailSection("Prompt 覆盖") {
                        CharacterField("System", character.systemPrompt)
                        CharacterField("Post-history", character.postHistoryInstructions)
                        CharacterField("Depth", character.depthPrompt?.content.orEmpty())
                    }
                }
            }
            item("contents") {
                DetailSection("卡片内容") {
                    Text("${character.worldBooks.size} 本世界书 · ${character.worldBooks.sumOf { it.entries.size }} 个条目")
                    Text("${character.regexScripts.size} 个角色 Regex · ${character.alternateFirstMessages.size + 1} 个开场候选")
                    if (character.tags.isNotEmpty()) {
                        Text(character.tags.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item("compatibility") {
                DetailSection("兼容性报告") {
                    if (character.diagnostics.isEmpty()) {
                        Text("没有发现兼容性降级。", color = MaterialTheme.colorScheme.primary)
                    } else {
                        character.diagnostics.forEach { DiagnosticRow(it) }
                    }
                }
            }
            if (conversations.isNotEmpty()) {
                item("conversation-title") {
                    Text("已有对话", style = MaterialTheme.typography.titleLarge)
                }
                items(conversations, key = ConversationRecord::id) { conversation ->
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("conversation-${conversation.id}"),
                        onClick = { onOpenConversation(conversation.id) },
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(conversation.preview(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${conversation.turns.size} 条消息 · ${formatTimestamp(conversation.updatedAtEpochMillis)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item("footer") { Spacer(Modifier.height(18.dp)) }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        HorizontalDivider()
        content()
    }
}

@Composable
private fun CharacterField(label: String, value: String) {
    if (value.isBlank()) return
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    SafeMarkdownText(value)
}

@Composable
private fun DiagnosticRow(diagnostic: CompatibilityDiagnostic) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            diagnostic.code,
            fontWeight = FontWeight.SemiBold,
            color = if (diagnostic.severity == CompatibilitySeverity.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.tertiary
            },
        )
        Text(diagnostic.message, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CharacterAvatar(path: String?, name: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = path?.let { file ->
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file)?.asImageBitmap() }
        }
    }
    val shape = RoundedCornerShape(14.dp)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = "$name 头像",
            modifier = modifier.clip(shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1), style = MaterialTheme.typography.headlineMedium)
        }
    }
}

private suspend fun Context.readCharacterCard(uri: Uri): Pair<ByteArray, String> {
    val fileName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "character-card"
    val bytes = contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= CharacterCardImporter.MAX_SOURCE_BYTES) { "角色卡超过 32 MiB 导入上限" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("无法打开所选文件")
    return bytes to fileName
}

private fun ConversationRecord.preview(): String = turns.asReversed()
    .asSequence()
    .map { it.selected.message.content.trim() }
    .firstOrNull(String::isNotBlank)
    ?.take(160)
    ?: "空白对话"

private fun formatTimestamp(epochMillis: Long): String = runCatching {
    TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
}.getOrDefault("")

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
