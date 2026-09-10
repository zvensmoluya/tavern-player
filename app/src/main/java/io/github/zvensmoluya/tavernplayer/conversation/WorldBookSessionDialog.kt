package io.github.zvensmoluya.tavernplayer.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookEntryDefinition

/**
 * 会话内的世界书设置：每本书的参与方式、条目启停与正文改写。
 *
 * 这里只呈现玩家在对话里要做的决定。关键词、位置、深度、顺序、概率、分组与
 * sticky / cooldown / delay 等编排字段属于角色卡与运行状态，不在这张界面上出现。
 */
@Composable
fun WorldBookSessionDialog(
    characterName: String = "",
    books: List<WorldBookDefinition> = emptyList(),
    state: ConversationWorldBookState = ConversationWorldBookState(),
    busy: Boolean = false,
    onDismiss: () -> Unit = {},
    onBookMode: (String, WorldBookBookMode) -> Unit = { _, _ -> },
    onEntryEnabled: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onEntryContent: (String, String, String) -> Unit = { _, _, _ -> },
    onRestore: (String, String?) -> Unit = { _, _ -> },
    onResetAll: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("worldBookDialog"),
        title = {
            Column {
                Text("世界书")
                if (characterName.isNotBlank()) {
                    Text(
                        characterName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("worldBookDialogContent"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (books.isEmpty()) {
                    Text("这张角色卡没有附带世界书")
                } else {
                    if (state.hasChanges()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "本对话已调整世界书设置",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(
                                onClick = onResetAll,
                                enabled = !busy,
                                modifier = Modifier.testTag("worldBookResetAll"),
                            ) { Text("全部恢复") }
                        }
                        HorizontalDivider()
                    }
                    books.forEach { book ->
                        WorldBookSection(
                            book = book,
                            state = state,
                            busy = busy,
                            onBookMode = onBookMode,
                            onEntryEnabled = onEntryEnabled,
                            onEntryContent = onEntryContent,
                            onRestore = onRestore,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("worldBookClose")) { Text("关闭") }
        },
    )
}

@Composable
private fun WorldBookSection(
    book: WorldBookDefinition,
    state: ConversationWorldBookState,
    busy: Boolean,
    onBookMode: (String, WorldBookBookMode) -> Unit,
    onEntryEnabled: (String, String, Boolean) -> Unit,
    onEntryContent: (String, String, String) -> Unit,
    onRestore: (String, String?) -> Unit,
) {
    val mode = state.modeOf(book.id)
    val bookDisabled = mode == WorldBookBookMode.DISABLED
    Column(
        modifier = Modifier.fillMaxWidth().testTag("worldBookBook-${book.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(book.name.ifBlank { book.id }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorldBookModeChip("不参与", WorldBookBookMode.DISABLED, book.id, mode, busy, onBookMode)
            WorldBookModeChip("自动", WorldBookBookMode.AUTO, book.id, mode, busy, onBookMode)
            WorldBookModeChip("必定生效", WorldBookBookMode.FORCED, book.id, mode, busy, onBookMode)
        }
        if (book.entries.isEmpty()) {
            Text(
                "这本世界书没有条目",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        book.entries.forEach { entry ->
            WorldBookEntryRow(
                bookId = book.id,
                entry = entry,
                bookDisabled = bookDisabled,
                state = state,
                busy = busy,
                onEntryEnabled = onEntryEnabled,
                onEntryContent = onEntryContent,
                onRestore = onRestore,
            )
        }
    }
}

@Composable
private fun WorldBookModeChip(
    label: String,
    mode: WorldBookBookMode,
    bookId: String,
    current: WorldBookBookMode,
    busy: Boolean,
    onBookMode: (String, WorldBookBookMode) -> Unit,
) {
    FilterChip(
        selected = current == mode,
        onClick = { onBookMode(bookId, mode) },
        enabled = !busy,
        label = { Text(label) },
        modifier = Modifier.testTag("worldBookMode-$bookId-${mode.name}"),
    )
}

@Composable
private fun WorldBookEntryRow(
    bookId: String,
    entry: WorldBookEntryDefinition,
    bookDisabled: Boolean,
    state: ConversationWorldBookState,
    busy: Boolean,
    onEntryEnabled: (String, String, Boolean) -> Unit,
    onEntryContent: (String, String, String) -> Unit,
    onRestore: (String, String?) -> Unit,
) {
    val override = state.activation.entries[bookId]?.get(entry.id)
    val enabled = !bookDisabled && (override ?: entry.enabled)
    val edited = state.isEdited(entry.id)
    var expanded by remember(bookId, entry.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = enabled,
                enabled = !busy && !bookDisabled,
                onCheckedChange = { onEntryEnabled(bookId, entry.id, it) },
                modifier = Modifier.testTag("worldBookEntryToggle-$bookId-${entry.id}"),
            )
            TextButton(
                onClick = { expanded = !expanded },
                enabled = !busy,
                modifier = Modifier.weight(1f).testTag("worldBookEntryTitle-$bookId-${entry.id}"),
            ) {
                Text(
                    entry.comment.ifBlank { entry.name }.ifBlank { entry.id },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
            }
            if (edited) {
                Text(
                    "已改过",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("worldBookEntryEdited-$bookId-${entry.id}"),
                )
            }
        }
        if (expanded) {
            Text(
                entry.content.ifBlank { "这条世界书没有正文" },
                modifier = Modifier.fillMaxWidth().testTag("worldBookEntryText-$bookId-${entry.id}"),
                style = MaterialTheme.typography.bodyMedium,
            )
            var draft by remember(bookId, entry.id, entry.content) { mutableStateOf(entry.content) }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                enabled = !busy,
                label = { Text("条目正文") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp, max = 240.dp)
                    .testTag("worldBookEntryEditor-$bookId-${entry.id}"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onEntryContent(bookId, entry.id, draft) },
                    enabled = !busy,
                    modifier = Modifier.testTag("worldBookEntrySave-$bookId-${entry.id}"),
                ) { Text("保存正文") }
                if (edited) {
                    TextButton(
                        onClick = { onRestore(bookId, entry.id) },
                        enabled = !busy,
                        modifier = Modifier.testTag("worldBookEntryRestore-$bookId-${entry.id}"),
                    ) { Text("恢复原文") }
                }
            }
        }
    }
}
