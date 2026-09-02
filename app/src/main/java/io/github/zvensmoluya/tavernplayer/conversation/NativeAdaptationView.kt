package io.github.zvensmoluya.tavernplayer.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zvensmoluya.tavernplayer.content.AdaptationFormField
import io.github.zvensmoluya.tavernplayer.content.AdaptationFormFieldType
import io.github.zvensmoluya.tavernplayer.content.AdaptationUiNode
import io.github.zvensmoluya.tavernplayer.content.AdaptationUiNodeType
import io.github.zvensmoluya.tavernplayer.content.AdaptationView
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

@Composable
internal fun NativeAdaptationView(
    view: AdaptationView,
    state: Map<String, JsonElement>,
    userName: String,
    characterName: String,
    enabled: Boolean,
    onSubmit: (Map<String, List<String>>) -> Unit,
) {
    var values by remember(view.id) { mutableStateOf(initialFormValues(view)) }
    Card(modifier = Modifier.fillMaxWidth().testTag("adaptation-view-${view.id}")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (view.title.isNotBlank()) {
                Text(view.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            view.nodes.forEach { node ->
                AdaptationNode(
                    node = node,
                    state = state,
                    userName = userName,
                    characterName = characterName,
                    values = values,
                    enabled = enabled,
                    onValue = { fieldId, fieldValues -> values = values + (fieldId to fieldValues) },
                )
            }
            if (view.submitActions.isNotEmpty()) {
                Button(
                    onClick = { onSubmit(values) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("adaptation-submit-${view.id}"),
                ) {
                    Text(view.submitLabel?.takeIf(String::isNotBlank) ?: "确认")
                }
            }
        }
    }
}

@Composable
private fun AdaptationNode(
    node: AdaptationUiNode,
    state: Map<String, JsonElement>,
    userName: String,
    characterName: String,
    values: Map<String, List<String>>,
    enabled: Boolean,
    onValue: (String, List<String>) -> Unit,
) {
    when (node.type) {
        AdaptationUiNodeType.TEXT -> if (node.title.isNotBlank() || node.text.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (node.title.isNotBlank()) Text(node.title, style = MaterialTheme.typography.labelMedium)
                if (node.text.isNotBlank()) {
                    Text(
                        renderAdaptationText(node.text, state, userName, characterName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        AdaptationUiNodeType.STATUS -> StatusNode(node, state)
        AdaptationUiNodeType.FORM -> Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (node.title.isNotBlank()) Text(node.title, fontWeight = FontWeight.SemiBold)
            node.fields.forEach { field ->
                AdaptationField(field, values[field.id].orEmpty(), enabled) { onValue(field.id, it) }
            }
            node.children.forEach { AdaptationNode(it, state, userName, characterName, values, enabled, onValue) }
        }
        AdaptationUiNodeType.SECTION -> Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (node.title.isNotBlank()) Text(node.title, fontWeight = FontWeight.SemiBold)
            if (node.text.isNotBlank()) Text(renderAdaptationText(node.text, state, userName, characterName))
            node.children.forEach { AdaptationNode(it, state, userName, characterName, values, enabled, onValue) }
        }
    }
}

@Composable
private fun StatusNode(node: AdaptationUiNode, state: Map<String, JsonElement>) {
    val primitive = state[node.stateKey] as? JsonPrimitive
    val displayed = primitive?.contentOrNull.orEmpty()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(node.title.ifBlank { node.stateKey.orEmpty() }, style = MaterialTheme.typography.labelMedium)
            Text(displayed, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("adaptation-state-${node.stateKey}"))
        }
        val number = primitive?.doubleOrNull
        val min = node.min
        val max = node.max
        if (number != null && min != null && max != null && max > min) {
            val progress = ((number - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AdaptationField(
    field: AdaptationFormField,
    values: List<String>,
    enabled: Boolean,
    onValue: (List<String>) -> Unit,
) {
    when (field.type) {
        AdaptationFormFieldType.TEXT,
        AdaptationFormFieldType.MULTILINE_TEXT,
        AdaptationFormFieldType.NUMBER,
        -> OutlinedTextField(
            value = values.firstOrNull().orEmpty(),
            onValueChange = { onValue(listOf(it)) },
            modifier = Modifier.fillMaxWidth().testTag("adaptation-field-${field.id}"),
            enabled = enabled,
            label = { Text(field.label + if (field.required) " *" else "") },
            placeholder = if (field.placeholder.isBlank()) null else ({ Text(field.placeholder) }),
            minLines = if (field.type == AdaptationFormFieldType.MULTILINE_TEXT) 3 else 1,
            maxLines = if (field.type == AdaptationFormFieldType.MULTILINE_TEXT) 8 else 1,
        )
        AdaptationFormFieldType.SINGLE_SELECT,
        AdaptationFormFieldType.MULTI_SELECT,
        -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(field.label + if (field.required) " *" else "", style = MaterialTheme.typography.labelMedium)
            field.options.forEach { option ->
                val selected = option.value in values
                val click = {
                    onValue(
                        if (field.type == AdaptationFormFieldType.SINGLE_SELECT) {
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
                        modifier = Modifier.fillMaxWidth().testTag("adaptation-option-${field.id}-${option.value}"),
                    ) { Text(option.label) }
                } else {
                    OutlinedButton(
                        onClick = click,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("adaptation-option-${field.id}-${option.value}"),
                    ) { Text(option.label) }
                }
            }
        }
        AdaptationFormFieldType.TOGGLE -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(field.label)
            Switch(
                checked = values.firstOrNull()?.toBooleanStrictOrNull() ?: false,
                onCheckedChange = { onValue(listOf(it.toString())) },
                enabled = enabled,
                modifier = Modifier.testTag("adaptation-field-${field.id}"),
            )
        }
    }
}

private fun initialFormValues(view: AdaptationView): Map<String, List<String>> {
    fun collect(node: AdaptationUiNode): List<AdaptationFormField> = node.fields + node.children.flatMap(::collect)
    return view.nodes.flatMap(::collect).associate { field ->
        field.id to when {
            field.initialValue.isNotEmpty() -> listOf(field.initialValue)
            field.type == AdaptationFormFieldType.TOGGLE -> listOf("false")
            else -> emptyList()
        }
    }
}
