package io.github.zvensmoluya.tavernplayer.conversation.reading

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.zvensmoluya.tavernplayer.conversation.ChatMessageState
import java.io.File
import kotlinx.coroutines.*

@Composable
internal fun ReadingExportDialog(title: String, character: String, messages: List<ChatMessageState>, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var book by remember { mutableStateOf<ReadingBook?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var first by rememberSaveable { mutableIntStateOf(0) }
    var last by rememberSaveable { mutableIntStateOf(Int.MAX_VALUE) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var pendingFile by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { book = prepareReadingBook(context, title, character, messages) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { feedback = "阅读稿生成失败，请关闭后重试" }
    }
    val full = book
    val selection = remember(full, first, last) {
        if (full == null || full.entries.isEmpty()) null else {
            val from = first.coerceIn(full.entries.indices)
            val end = last.coerceIn(from, full.entries.lastIndex)
            full.copy(entries = full.entries.subList(from, end + 1))
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EPUB_MIME)) { uri ->
        val file = pendingFile?.let { File(context.cacheDir, "reading/$it") }
        pendingFile = null
        if (uri != null && file != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        file.inputStream().use { input ->
                            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { input.copyTo(it) }
                        }
                    }
                    feedback = "电子书已保存"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { feedback = "电子书保存失败，请重新选择保存位置" }
                finally { busy = false }
            }
        }
    }
    fun export(open: Boolean) {
        val selected = selection ?: return
        if (busy) return
        busy = true
        feedback = null
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { selected.cachedEpub(context) }
                if (open) context.startActivity(readerIntent(context, file, selected.filename()))
                else {
                    pendingFile = file.name
                    save.launch(selected.filename())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ActivityNotFoundException) {
                feedback = if (open) "没有找到能打开 EPUB 的应用。可以先保存电子书，再从阅读器导入。" else "无法打开系统文件选择器"
            } catch (_: Exception) { feedback = "无法导出电子书，请重试" }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(horizontal = 18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("阅读稿预览", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose, enabled = !busy) { Text("关闭") }
                }
                if (full == null) {
                    if (feedback == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (selection == null) {
                    Text("这场对话还没有可导出的正文")
                } else {
                    Text(selection.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("与${selection.character}的对话", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val range = if (selection.entries.size == full.entries.size) "全部 ${full.entries.size} 条"
                            else "第 ${first + 1}–${first + selection.entries.size} 条"
                        Text(range, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { picking = true }, enabled = !busy) { Text("选择范围") }
                    }
                    HorizontalDivider()
                    key(first, last) {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            itemsIndexed(selection.entries) { _, entry ->
                                Column(Modifier.padding(start = if (entry.player) 16.dp else 0.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(entry.speaker, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    entry.paragraphs.forEach { runs ->
                                        Text(remember(runs) { buildAnnotatedString {
                                            runs.forEach { run -> withStyle(SpanStyle(
                                                fontWeight = if (run.bold) FontWeight.Bold else null,
                                                fontStyle = if (run.italic) FontStyle.Italic else null,
                                            )) { append(run.text) } }
                                        } }, style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        }
                    }
                    Button(onClick = { export(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("用阅读器打开") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { export(false) }, enabled = !busy) { Text("保存电子书") }
                        TextButton(enabled = !busy, onClick = {
                            try {
                                context.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText(selection.title, selection.plainText()))
                                feedback = "已复制选段"
                            } catch (_: RuntimeException) { feedback = "无法复制，请缩小选取范围或保存电子书" }
                        }) { Text("复制选段") }
                    }
                }
                feedback?.let { Text(it, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (picking && full != null && full.entries.isNotEmpty()) {
        ReadingRangeDialog(full.entries, first.coerceIn(full.entries.indices), onDismiss = { picking = false }) { start, end ->
            first = start; last = end; feedback = null; picking = false
        }
    }
}

@Composable
private fun ReadingRangeDialog(entries: List<ReadingEntry>, first: Int, onDismiss: () -> Unit, onRange: (Int, Int) -> Unit) {
    var start by rememberSaveable { mutableStateOf<Int?>(null) }
    val offset = start ?: 0
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (start == null) "选择开始消息" else "选择结束消息") },
        text = {
            key(start) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    state = rememberLazyListState(initialFirstVisibleItemIndex = if (start == null) first else 0)) {
                    items(entries.size - offset) { row ->
                        val index = row + offset
                        val entry = entries[index]
                        Column(Modifier.fillMaxWidth().clickable {
                            if (start == null) start = index else onRange(offset, index)
                        }.padding(vertical = 9.dp)) {
                            Text("${index + 1} · ${entry.speaker}", style = MaterialTheme.typography.labelMedium)
                            Text(entry.excerpt, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { onRange(0, entries.lastIndex) }) { Text("整场对话") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
