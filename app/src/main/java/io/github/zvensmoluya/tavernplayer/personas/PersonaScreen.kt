package io.github.zvensmoluya.tavernplayer.personas

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PersonaRoute(
    viewModel: PersonaViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    BackHandler { if (!state.busy) onBack() }
    val context = LocalContext.current
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.updateAvatar(uri.toString())
    }
    PersonaScreen(
        state = state,
        onNameChange = viewModel::updateName,
        onDescriptionChange = viewModel::updateDescription,
        onChooseAvatar = { avatarPicker.launch(arrayOf("image/*")) },
        onRemoveAvatar = { viewModel.updateAvatar(null) },
        onSave = viewModel::save,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaScreen(
    state: PersonaUiState,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onChooseAvatar: () -> Unit,
    onRemoveAvatar: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的身份") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(
                        onClick = onSave,
                        enabled = state.dirty && !state.busy && state.name.isNotBlank(),
                        modifier = Modifier.testTag("savePersona"),
                    ) { Text(if (state.busy) "保存中…" else "保存") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PersonaAvatar(state.avatar, state.name, Modifier.size(76.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onChooseAvatar, modifier = Modifier.testTag("choosePersonaAvatar")) {
                            Text(if (state.avatar == null) "选择头像" else "更换头像")
                        }
                        if (state.avatar != null) {
                            TextButton(onClick = onRemoveAvatar, modifier = Modifier.testTag("removePersonaAvatar")) {
                                Text("移除头像")
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth().testTag("personaName"),
                label = { Text("名字") },
                supportingText = { Text("用于 {{user}} 和你发送的消息署名") },
                singleLine = true,
                enabled = !state.busy,
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = onDescriptionChange,
                modifier = Modifier.fillMaxWidth().testTag("personaDescription"),
                label = { Text("描述") },
                supportingText = { Text("提供给 {{persona}}；是否进入请求及所在位置由当前 Preset 决定") },
                minLines = 5,
                maxLines = 12,
                enabled = !state.busy,
            )
            state.message?.let { message ->
                Text(message, modifier = Modifier.testTag("personaMessage"), color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "修改只影响之后新建的对话；已有对话保留创建时的身份。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun PersonaAvatar(uriOrPath: String?, name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, uriOrPath) {
        value = null
        value = withContext(Dispatchers.IO) { context.readPersonaAvatar(uriOrPath) }
    }
    Box(
        modifier = modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image!!,
                contentDescription = "身份头像",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(name.trim().take(1).ifBlank { "我" }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}

private fun Context.readPersonaAvatar(uriOrPath: String?): ImageBitmap? {
    if (uriOrPath.isNullOrBlank()) return null
    return runCatching {
        val uri = Uri.parse(uriOrPath)
        val stream = if (uri.scheme.isNullOrBlank()) File(uriOrPath).inputStream() else contentResolver.openInputStream(uri)
        stream?.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
    }.getOrNull()
}
