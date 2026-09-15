package io.github.zvensmoluya.tavernplayer.worldbooks

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.zvensmoluya.tavernplayer.characters.WorldBookReaderScreen
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBookLibraryScreen(repository: WorldBookRepository, onBack: () -> Unit) {
    val books by repository.library.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<GlobalWorldBook?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "操作失败，请重试" }
            finally { busy = false }
        }
    }
    LaunchedEffect(repository) {
        try { repository.initialize(); ready = true }
        catch (error: Exception) { message = "世界书读取失败：${error.message}" }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            val asset = withContext(Dispatchers.IO) {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "世界书.json"
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 32 * 1024 * 1024) { "世界书超过 32 MiB" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                repository.import(bytes, name)
            }
            message = "已导入 ${asset.book.name}，启用后参与后续生成"
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = exportId
        if (uri != null && id != null) work {
            withContext(Dispatchers.IO) {
                repository.initialize()
                val asset = requireNotNull(repository.library.value.find { it.book.id == id }) { "世界书已不存在，请重新选择" }
                val text = repository.export(asset)
                requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            }
            exportId = null
            message = "世界书已导出"
        }
    }
    val asset = books.firstOrNull { it.book.id == selected }
    if (asset != null) {
        WorldBookReaderScreen(readerId = asset.book.id, characterName = asset.book.name,
            books = listOf(asset.book), onBack = { if (!busy) selected = null }, sessionState = asset.state,
            busy = busy, message = message, global = true,
            onEntryMode = { book, entry, mode -> work { repository.setMode(book, entry, mode) } },
            onEntryContent = { book, entry, content, saved -> work { repository.setContent(book, entry, content); saved() } })
        return
    }
    BackHandler { if (!busy) onBack() }
    Scaffold(topBar = { TopAppBar(title = { Text("全局世界书") }, navigationIcon = {
        TextButton(onClick = onBack, enabled = !busy) { Text("返回") }
    }, actions = { TextButton(enabled = ready && !busy, onClick = { importer.launch(arrayOf("application/json", "text/json", "application/octet-stream")) }) { Text("导入") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("globalWorldBookLibrary"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("启用后用于所有会话的后续生成，与预设独立。条目按原条件触发，可逐项设为始终注入。") }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (!ready) item { TextButton(onClick = { work { repository.initialize(); ready = true } }, enabled = !busy) { Text("重新读取") } }
            if (ready && books.isEmpty()) item { Text("还没有全局世界书。可导入 JSON，或在角色首页从 Shelf 接收。") }
            items(books, key = { it.book.id }) { book ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { selected = book.book.id; message = null }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(book.book.name) }
                            Switch(checked = book.enabled, enabled = !busy, onCheckedChange = { work { repository.setEnabled(book.book.id, it) } }, modifier = Modifier.testTag("globalWorldBookEnabled-${book.book.id}"))
                        }
                        Text("${book.book.entries.size} 项 · ${if (book.enabled) "已启用" else "未启用"}")
                        book.diagnostics.forEach { Text(it.message, style = MaterialTheme.typography.bodySmall) }
                        Row {
                            TextButton(enabled = !busy, onClick = { work { repository.duplicate(book.book.id) } }) { Text("另存为") }
                            TextButton(enabled = !busy, onClick = { exportId = book.book.id; exporter.launch("worldbook.json") }) { Text("导出") }
                            TextButton(enabled = !busy, onClick = { deleting = book }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { book -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除世界书？") },
        text = { Text("${book.book.name} 将从全局列表移除，不影响已生成的消息。") },
        confirmButton = { TextButton(onClick = { deleting = null; work { repository.delete(book.book.id) } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}
