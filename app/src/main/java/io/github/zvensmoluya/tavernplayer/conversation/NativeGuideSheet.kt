package io.github.zvensmoluya.tavernplayer.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.zvensmoluya.tavernplayer.content.NativeGuideReading

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeGuideSheet(reading: NativeGuideReading, user: String, character: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(modifier = Modifier.fillMaxWidth().testTag("nativeGuide"),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(reading.content?.title ?: "玩法说明", style = MaterialTheme.typography.headlineSmall)
                    Text("阅读后返回聊天，随时可以再次查看。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            reading.error?.let { error -> item { Text(error, modifier = Modifier.testTag("native-guide-error")) } }
            reading.content?.sections?.forEach { section -> item(key = section.id) {
                Column(Modifier.fillMaxWidth().testTag("native-guide-section-${section.id}"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    SafeMarkdownText(nativeGuideIdentityText(section.text, user, character))
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            } }
            item { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("closeNativeGuide")) { Text("返回聊天") } }
        }
    }
}

/** Fixed identity display, single pass; inserted values and other macros remain inert. */
internal fun nativeGuideIdentityText(text: String, user: String, character: String): String =
    GUIDE_IDENTITY.replace(text) { if (it.groupValues[1] == "user") user else character }

private val GUIDE_IDENTITY = Regex("\\{\\{(user|char)\\}\\}")
