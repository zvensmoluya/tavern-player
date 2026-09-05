package io.github.zvensmoluya.tavernplayer.conversation

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.zvensmoluya.tavernplayer.content.NativeCollectionView
import io.github.zvensmoluya.tavernplayer.content.NativeFormField
import io.github.zvensmoluya.tavernplayer.content.NativeFormFieldType
import io.github.zvensmoluya.tavernplayer.content.NativeFormView
import io.github.zvensmoluya.tavernplayer.content.NativeSceneView
import io.github.zvensmoluya.tavernplayer.content.NativeStatusView
import io.github.zvensmoluya.tavernplayer.content.NativeStatusDisplay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun NativePlayerChoicesCard(choices: List<NativePlayerChoiceOption>, enabled: Boolean, onPreview: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag("native-player-choices"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("你的选择", style = MaterialTheme.typography.titleMedium)
            choices.forEach { option ->
                Button(onClick = { onPreview(option.choice.id) }, enabled = enabled && option.unavailableReason == null,
                    modifier = Modifier.fillMaxWidth().testTag("native-choice-${option.choice.id}")) { Text(option.choice.title) }
                Text(option.unavailableReason ?: option.choice.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun NativeStatusCard(
    view: NativeStatusView,
    state: Map<String, JsonElement>,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("native-status")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (view.title.isNotBlank()) {
                Text(view.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            view.items.forEachIndexed { index, item ->
                if (item.group.isNotBlank() && (index == 0 || view.items[index - 1].group != item.group)) {
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(item.group, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                val primitive = state[item.stateKey] as? JsonPrimitive
                val value = NativeStatusDisplay.value(item, state)
                val displayed = value.text
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (displayed.length > 24 || '\n' in displayed) {
                        Text(item.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(displayed, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().testTag("native-state-${item.stateKey}"))
                    } else Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        Text(
                            displayed,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1.3f).testTag("native-state-${item.stateKey}"),
                        )
                    }
                    if (value.adjusted) Text("记录值：${value.recorded}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("native-recorded-${item.stateKey}"))
                    if (value.unavailable) Text("显示规则无法匹配，保留记录值", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    val number = primitive?.doubleOrNull
                    val min = item.min
                    val max = item.max
                    if (number != null && min != null && max != null && max > min) {
                        val progress = ((number - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeOpeningSelector(choices: List<NativeOpeningChoice>, enabled: Boolean, onSelect: (Int) -> Unit, onGuide: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("选择开场", style = MaterialTheme.typography.titleSmall)
            if (onGuide != null) TextButton(onClick = onGuide, modifier = Modifier.testTag("openNativeGuide")) { Text("玩法说明") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { choice ->
                FilterChip(selected = choice.selected, onClick = { onSelect(choice.sourceIndex) }, enabled = enabled,
                    label = { Text(choice.title) }, modifier = Modifier.testTag("native-opening-${choice.sourceIndex}"))
            }
        }
    }
}

@Composable
internal fun NativeSceneCard(
    view: NativeSceneView,
    state: Map<String, JsonElement>,
    resolveAssetPath: (assetId: String) -> String?,
) {
    val selectedValue = (state[view.stateKey] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val selected = view.assets.firstOrNull { it.stateValue == selectedValue }
    val path = selected?.let { resolveAssetPath(it.assetId) }
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = path?.let { imagePath ->
            withContext(Dispatchers.IO) { decodeBoundedImage(imagePath)?.asImageBitmap() }
        }
    }
    Card(modifier = Modifier.fillMaxWidth().testTag("native-scene-${view.id}")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (view.title.isNotBlank()) {
                Text(view.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (image != null) {
                Image(
                    bitmap = image!!,
                    contentDescription = selected?.contentDescription?.ifBlank { view.title },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("native-scene-image-${view.id}"),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(view.emptyLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun decodeBoundedImage(path: String): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > MAX_RENDERED_ASSET_EDGE || bounds.outHeight / sample > MAX_RENDERED_ASSET_EDGE) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private const val MAX_RENDERED_ASSET_EDGE = 2_048

@Composable
internal fun NativeMessagePanelCard(panel: NativeMessagePanelContent) {
    var expanded by rememberSaveable(panel.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("native-message-panel-${panel.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(panel.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            panel.fields.take(if (expanded) panel.fields.size else 2).forEach { (label, value) ->
                if (value.isNotBlank()) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SafeMarkdownText(value)
                }
            }
            if (panel.fields.size > 2) TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起资料" else "查看本幕资料")
            }
        }
    }
}

@Composable
internal fun NativeCollectionCard(
    view: NativeCollectionView,
    state: Map<String, JsonElement>,
) {
    val records = state[view.stateKey] as? JsonArray
    Card(modifier = Modifier.fillMaxWidth().testTag("native-collection-${view.id}")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(view.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (records.isNullOrEmpty()) {
                Text(view.emptyLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                records.forEachIndexed { index, element ->
                    if (index > 0) HorizontalDivider()
                    val record = element as? JsonObject ?: return@forEachIndexed
                    Column(
                        modifier = Modifier.fillMaxWidth().testTag("native-collection-${view.id}-item-$index"),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        view.fields.forEach { field ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(field.label, style = MaterialTheme.typography.labelMedium)
                                Text((record[field.key] as? JsonPrimitive)?.contentOrNull.orEmpty())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NativeFormCard(
    form: NativeFormView,
    enabled: Boolean,
    onSubmit: (Map<String, List<String>>) -> Unit,
    completed: Boolean = false,
) {
    var values by rememberSaveable(form.id, stateSaver = FormValuesSaver) { mutableStateOf(initialFormValues(form)) }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("native-form-${form.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(if (form.setup == null) "填写 · 开始对话" else "开局设定", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(form.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (completed) {
                Text("开局表单已收起；重新开始可再次填写", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("native-setup-completed"))
                return@Column
            }
            if (form.description.isNotBlank()) {
                Text(form.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            form.fields.forEach { field ->
                NativeFormFieldEditor(field, values[field.id].orEmpty(), enabled) { fieldValues ->
                    values = values + (field.id to fieldValues)
                }
            }
            Button(
                onClick = { onSubmit(values) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("native-form-submit-${form.id}"),
            ) {
                Text(form.submitLabel)
            }
            Text(
                if (form.setup == null) "填写内容将放入输入框，你可以修改后再发送。" else "保存后生成开场草稿。开局选定的设定会立即生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NativeFormFieldEditor(
    field: NativeFormField,
    values: List<String>,
    enabled: Boolean,
    onValue: (List<String>) -> Unit,
) {
    when (field.type) {
        NativeFormFieldType.TEXT,
        NativeFormFieldType.MULTILINE_TEXT,
        NativeFormFieldType.NUMBER,
        -> OutlinedTextField(
            value = values.firstOrNull().orEmpty(),
            onValueChange = { onValue(listOf(it)) },
            modifier = Modifier.fillMaxWidth().testTag("native-form-field-${field.id}"),
            enabled = enabled,
            label = { Text(field.label + if (field.required) " *" else "") },
            placeholder = if (field.placeholder.isBlank()) null else ({ Text(field.placeholder) }),
            minLines = if (field.type == NativeFormFieldType.MULTILINE_TEXT) 2 else 1,
            maxLines = if (field.type == NativeFormFieldType.MULTILINE_TEXT) 8 else 1,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = if (field.type == NativeFormFieldType.NUMBER) KeyboardType.Decimal else KeyboardType.Text),
        )
        NativeFormFieldType.SINGLE_SELECT,
        NativeFormFieldType.MULTI_SELECT,
        -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(field.label + if (field.required) " *" else "", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              field.options.forEach { option ->
                val selected = option.value in values
                val click = {
                    onValue(
                        if (field.type == NativeFormFieldType.SINGLE_SELECT) {
                            listOf(option.value)
                        } else if (selected) {
                            values - option.value
                        } else {
                            values + option.value
                        },
                    )
                }
                FilterChip(
                    selected = selected,
                    onClick = click,
                    enabled = enabled,
                    label = { Text(option.label) },
                    modifier = Modifier.testTag("native-form-option-${field.id}-${option.value}"),
                )
              }
            }
        }
        NativeFormFieldType.TOGGLE -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(field.label)
            Switch(
                checked = values.firstOrNull()?.toBooleanStrictOrNull() ?: false,
                onCheckedChange = { onValue(listOf(it.toString())) },
                enabled = enabled,
                modifier = Modifier.testTag("native-form-field-${field.id}"),
            )
        }
    }
}

private val FormValuesSaver = mapSaver(
    save = { values: Map<String, List<String>> -> values.mapValues { ArrayList(it.value) } },
    restore = { values -> values.mapValues { (_, value) -> (value as? List<*>)?.filterIsInstance<String>().orEmpty() } },
)

private fun initialFormValues(form: NativeFormView): Map<String, List<String>> = form.fields.associate { field ->
    field.id to when {
        field.initialValues.isNotEmpty() -> field.initialValues
        field.type == NativeFormFieldType.TOGGLE -> listOf("false")
        else -> emptyList()
    }
}
