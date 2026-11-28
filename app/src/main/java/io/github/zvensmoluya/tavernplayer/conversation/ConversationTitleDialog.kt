package io.github.zvensmoluya.tavernplayer.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ConversationTitleDialog(
    id: String,
    currentTitle: String,
    suggestions: suspend (String) -> List<String>,
    rename: suspend (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var choices by remember(id) { mutableStateOf<List<String>?>(null) }
    var manual by rememberSaveable(id) { mutableStateOf(false) }
    var input by rememberSaveable(id) { mutableStateOf("") }
    val title = cleanConversationTitle(input)
    val tooLong = title.codePointCount(0, title.length) > MAX_TITLE_LENGTH
    var saving by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) {
        try {
            choices = suggestions(id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { error = e.message ?: "无法读取玩家发言"; choices = emptyList() }
    }
    fun save(title: String) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                rename(id, title)
                onDismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.message ?: "保存失败，请重试" }
            finally { saving = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("更换对话名") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("当前名称", style = MaterialTheme.typography.labelMedium)
                Text(currentTitle, modifier = Modifier.padding(bottom = 12.dp))
                if (manual) {
                    OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                        enabled = !saving, label = { Text("对话名") }, isError = tooLong,
                        supportingText = if (tooLong) ({ Text("最多 $MAX_TITLE_LENGTH 个字") }) else null)
                } else {
                    val available = choices?.filter { it != currentTitle }
                    when {
                        available == null -> Text("正在读取发言…")
                        available.isEmpty() -> Text("暂无其他可选的玩家发言", style = MaterialTheme.typography.bodySmall)
                        else -> {
                            Text("从我的发言中选一句", style = MaterialTheme.typography.labelMedium)
                            available.forEach { title ->
                                Text(title, Modifier.fillMaxWidth().clickable(enabled = !saving) { save(title) }
                                    .padding(vertical = 12.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (manual) TextButton(onClick = { save(title) }, enabled = title.isNotEmpty() && !tooLong && !saving) { Text("保存") }
            else TextButton(onClick = { manual = true }, enabled = !saving) { Text("自己填写") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } })
}
