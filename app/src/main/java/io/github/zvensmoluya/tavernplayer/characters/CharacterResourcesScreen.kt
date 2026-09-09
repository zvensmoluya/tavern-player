package io.github.zvensmoluya.tavernplayer.characters

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterResourcesScreen(
    state: CharacterImageState?, working: Boolean, error: String?,
    onPrepare: () -> Unit, onCancel: () -> Unit, onReload: () -> Unit,
    resolvePath: (CharacterImageEntry) -> String?, onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = { TopAppBar(title = { Text("角色资源") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("返回") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("characterResourceList"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("图片作为角色数据保存在应用内，可离线查看，不写入相册。")
                if (state != null) Text("已保存 ${state.savedCount} / ${state.entries.size} 张 · ${String.format(Locale.ROOT, "%.1f", state.savedBytes / 1048576.0)} MiB",
                    modifier = Modifier.testTag("resourceSummary"))
                if (working) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    TextButton(onClick = onCancel, modifier = Modifier.testTag("cancelResources")) { Text("暂停准备") }
                } else if (state != null && state.entries.isNotEmpty()) {
                    Button(onClick = onPrepare, enabled = state.savedCount < state.entries.size, modifier = Modifier.testTag("prepareResources")) {
                        Text(when {
                            state.savedCount == state.entries.size -> "图片已全部保存"
                            state.entries.any { it.error != null } || state.savedCount > 0 -> "继续准备未完成图片"
                            else -> "准备图片"
                        })
                    }
                }
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onReload, enabled = !working) { Text("重新检查") }
                } else if (!working && state?.entries?.isEmpty() == true) {
                    Text("没有找到可直接取得的图片。")
                }
                state?.notices?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            itemsIndexed(state?.entries.orEmpty(), key = { _, entry -> entry.reference.id }) { index, entry ->
                Card(Modifier.fillMaxWidth().testTag("resource-${entry.reference.id}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("图片 ${index + 1}", style = MaterialTheme.typography.titleSmall)
                        val origin = if (entry.reference.uri.startsWith("data:") || entry.reference.uri == "ccdefault:") "卡内图片"
                            else runCatching { URI(entry.reference.uri).host }.getOrNull() ?: "图片来源"
                        Text("$origin · ${entry.reference.sourcePaths.size} 处引用", style = MaterialTheme.typography.bodySmall)
                        Text(when {
                            entry.saved -> "已保存，可离线查看"
                            state?.currentId == entry.reference.id -> "正在准备…"
                            entry.error != null -> entry.error
                            else -> "待准备"
                        }, color = if (entry.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        if (entry.saved) LocalResourceImage(resolvePath(entry))
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalResourceImage(path: String?) {
    val image by produceState<ImageBitmap?>(null, path) {
        value = null
        value = withContext(Dispatchers.IO) {
            if (path == null) null else runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    image?.let { Image(it, contentDescription = "已保存的角色图片", modifier = Modifier.fillMaxWidth().height(200.dp).testTag("savedResourceImage"), contentScale = ContentScale.Fit) }
        ?: Text("图片暂时无法显示，可返回后重新检查", style = MaterialTheme.typography.bodySmall)
}
