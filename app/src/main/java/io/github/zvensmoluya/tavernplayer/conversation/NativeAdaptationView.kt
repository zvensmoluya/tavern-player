package io.github.zvensmoluya.tavernplayer.conversation

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zvensmoluya.tavernplayer.content.NativeCollectionView
import io.github.zvensmoluya.tavernplayer.content.NativeFormField
import io.github.zvensmoluya.tavernplayer.content.NativeFormFieldType
import io.github.zvensmoluya.tavernplayer.content.NativeFormView
import io.github.zvensmoluya.tavernplayer.content.NativeSceneView
import io.github.zvensmoluya.tavernplayer.content.NativeStatusView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            view.items.forEach { item ->
                val primitive = state[item.stateKey] as? JsonPrimitive
                val displayed = primitive?.contentOrNull.orEmpty()
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.label, style = MaterialTheme.typography.labelMedium)
                        Text(
                            displayed,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("native-state-${item.stateKey}"),
                        )
                    }
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
) {
    var values by remember(form.id) { mutableStateOf(initialFormValues(form)) }
    Card(modifier = Modifier.fillMaxWidth().testTag("native-form-${form.id}")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(form.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (form.description.isNotBlank()) {
                Text(form.description, style = MaterialTheme.typography.bodyMedium)
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
        }
    }
}

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
            minLines = if (field.type == NativeFormFieldType.MULTILINE_TEXT) 3 else 1,
            maxLines = if (field.type == NativeFormFieldType.MULTILINE_TEXT) 8 else 1,
        )
        NativeFormFieldType.SINGLE_SELECT,
        NativeFormFieldType.MULTI_SELECT,
        -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(field.label + if (field.required) " *" else "", style = MaterialTheme.typography.labelMedium)
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
                if (selected) {
                    Button(
                        onClick = click,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("native-form-option-${field.id}-${option.value}"),
                    ) { Text(option.label) }
                } else {
                    OutlinedButton(
                        onClick = click,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("native-form-option-${field.id}-${option.value}"),
                    ) { Text(option.label) }
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

private fun initialFormValues(form: NativeFormView): Map<String, List<String>> = form.fields.associate { field ->
    field.id to when {
        field.initialValues.isNotEmpty() -> field.initialValues
        field.type == NativeFormFieldType.TOGGLE -> listOf("false")
        else -> emptyList()
    }
}
