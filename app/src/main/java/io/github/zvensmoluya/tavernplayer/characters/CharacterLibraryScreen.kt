package io.github.zvensmoluya.tavernplayer.characters

import android.Manifest
import io.github.zvensmoluya.tavernplayer.ui.CharacterPortrait
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import io.github.zvensmoluya.tavernplayer.conversation.ConversationExecutionMode
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.content.CharacterCardImporter
import io.github.zvensmoluya.tavernplayer.content.CompatibilityDiagnostic
import io.github.zvensmoluya.tavernplayer.content.CompatibilitySeverity
import io.github.zvensmoluya.tavernplayer.conversation.storage.ConversationSummary
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
            runCatching {
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { barcode ->
                    barcode.rawValue?.takeIf(String::isNotBlank)?.let(::receiveShelfUrl)
                        ?: viewModel.reportMessage("二维码中没有可用的 Shelf 地址")
                }
                .addOnFailureListener { error ->
                    viewModel.reportMessage(error.message ?: "无法启动二维码扫描")
                }
            }.onFailure { error -> viewModel.reportMessage(error.message ?: "无法启动二维码扫描") }
        },
        onSelectCharacter = onSelectCharacter,
        onLayoutChange = viewModel::setLayout,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterDetailScreen(
    character: CharacterAsset,
    conversations: List<ConversationSummary>,
    avatarPath: String?,
    onBack: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenConversation: (String) -> Unit,
    importing: Boolean = false,
    message: String? = null,
    onReadWorldBooks: () -> Unit = {},
    onOpenResources: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
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
                    CharacterPortrait(avatarPath, character.name, Modifier.size(112.dp))
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onNewConversation,
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth().testTag("newConversation"),
                    ) { Text("开始新对话") }
                    if (message != null) Text(message, modifier = Modifier.testTag("characterDetailNotice"),
                        style = MaterialTheme.typography.bodySmall)
                }
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
                                else "${character.worldBooks.sumOf { it.entries.size }} 项内容",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("阅读", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item("image-resources") {
                Card(onClick = onOpenResources, modifier = Modifier.fillMaxWidth().testTag("characterResources")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("角色资源", style = MaterialTheme.typography.titleMedium)
                        Text("保存和查看角色图片", style = MaterialTheme.typography.bodyMedium)
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
                    Text("${character.worldBooks.sumOf { it.entries.size }} 项世界书内容")
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
                items(conversations, key = ConversationSummary::id) { conversation ->
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("conversation-${conversation.id}"),
                        onClick = { onOpenConversation(conversation.id) },
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(conversation.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${conversation.turnCount} 条消息 · ${formatTimestamp(conversation.updatedAtEpochMillis)} · ${ConversationExecutionMode.valueOf(conversation.executionMode).displayName()}",
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

private fun formatTimestamp(epochMillis: Long): String = runCatching {
    TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
}.getOrDefault("")

/** 已有对话保留创建时的执行路线，列表里必须能一眼分辨，否则两种入口开出来的对话长得一样。 */
private fun ConversationExecutionMode.displayName(): String =
    if (this == ConversationExecutionMode.BROWSER) "网页模式" else "原生模式"

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
