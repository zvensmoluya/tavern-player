package io.github.zvensmoluya.tavernplayer.conversation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.zvensmoluya.tavernplayer.content.*

/** Player owns every layout decision. JS supplies semantic fields and bound operations only. */
@Composable
internal fun NativeScriptSurfaceCard(
    rendered: NativeRenderedSurface,
    enabled: Boolean,
    invoke: (NativeSurfaceInvocation) -> Unit,
) {
    val data = rendered.data
    var input by rememberSaveable(rendered.id, rendered.revision) {
        mutableStateOf<Map<String, String>>(data.fields.associate { it.id to it.value })
    }
    fun execute(action: NativeSurfaceAction, itemKey: String? = null) {
        invoke(NativeSurfaceInvocation(rendered.id, rendered.revision, action, itemKey, input))
    }
    Card(Modifier.fillMaxWidth().testTag("native-surface-${rendered.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(data.title, style = MaterialTheme.typography.titleMedium)
            if (data.description.isNotBlank()) Text(data.description)
            when (data.surface) {
                NativeSurfaceType.FORM -> data.fields.forEach { field ->
                    if (field.options.isEmpty()) {
                        OutlinedTextField(
                            value = input[field.id].orEmpty(),
                            onValueChange = { if (it.length <= 16_384) input = input + (field.id to it) },
                            label = { Text(field.label + if (field.required) " *" else "") },
                            enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("surface-field-${field.id}"),
                        )
                    } else {
                        Text(field.label, style = MaterialTheme.typography.labelLarge)
                        field.options.forEach { option ->
                            FilterChip(selected = input[field.id] == option,
                                onClick = { input = input + (field.id to option) },
                                enabled = enabled, label = { Text(option) })
                        }
                    }
                }
                NativeSurfaceType.COLLECTION, NativeSurfaceType.STATUS, NativeSurfaceType.SCENE -> {
                    if (data.items.isEmpty() && data.surface == NativeSurfaceType.COLLECTION) Text(data.emptyLabel)
                    data.items.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall)
                            if (item.description.isNotBlank()) Text(item.description)
                            if (item.status.isNotBlank()) Text(item.status, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            item.actions.forEach { action ->
                                OutlinedButton(onClick = { execute(action, item.key) }, enabled = enabled && action.enabled,
                                    modifier = Modifier.testTag("surface-action-${rendered.id}-${item.key}-${action.id}")) { Text(action.label) }
                            }
                        }
                    }
                }
                NativeSurfaceType.ACTION_GROUP -> Unit
            }
            data.actions.forEach { action ->
                Button(onClick = { execute(action) }, enabled = enabled && action.enabled,
                    modifier = Modifier.fillMaxWidth().testTag("surface-action-${rendered.id}-${action.id}")) { Text(action.label) }
            }
        }
    }
}
