package io.github.zvensmoluya.tavernplayer.characters

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.zvensmoluya.tavernplayer.conversation.SafeMarkdownText
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
        onSelectCharacter = onSelectCharacter,
        onOpenModels = onOpenModels,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterLibraryScreen(
    state: CharacterLibraryUiState,
    avatarPath: (String) -> String?,
    onImport: () -> Unit,
    onSelectCharacter: (String) -> Unit,
    onOpenModels: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("角色") },
                actions = {
                    TextButton(onClick = onOpenModels, modifier = Modifier.testTag("openModelsFromLibrary")) {
                        Text("模型")
                    }
                    TextButton(
                        onClick = onImport,
                        enabled = !state.importing,
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
                Button(onClick = onImport, enabled = !state.importing) {
                    if (state.importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("选择角色卡")
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
) {
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
                    modifier = Modifier.fillMaxWidth().testTag("newConversation"),
                ) { Text("开始新对话") }
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
