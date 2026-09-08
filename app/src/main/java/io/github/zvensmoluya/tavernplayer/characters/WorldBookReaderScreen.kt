package io.github.zvensmoluya.tavernplayer.characters

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.content.WorldBookEntryDefinition

/** Reads the card's saved source text without evaluating templates or changing activation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBookReaderScreen(character: CharacterAsset, onBack: () -> Unit) {
    var query by rememberSaveable(character.id) { mutableStateOf("") }
    var selectedBook by rememberSaveable(character.id) { mutableStateOf<Int?>(null) }
    var selectedEntry by rememberSaveable(character.id) { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    val book = selectedBook?.let { character.worldBooks.getOrNull(it) }
    val entry = selectedEntry?.let { book?.entries?.getOrNull(it) }
    val goBack: () -> Unit = {
        if (entry != null) {
            selectedBook = null
            selectedEntry = null
        } else onBack()
    }
    BackHandler(onBack = goBack)
    val matches = remember(character.worldBooks, query) {
        val term = query.trim()
        character.worldBooks.mapIndexed { bookIndex, sourceBook ->
            bookIndex to sourceBook.entries.mapIndexedNotNull { entryIndex, sourceEntry ->
                entryIndex.takeIf {
                    term.isEmpty() || sequenceOf(sourceBook.name, sourceEntry.name, sourceEntry.comment, sourceEntry.content)
                        .plus(sourceEntry.keys).plus(sourceEntry.secondaryKeys).any { it.contains(term, ignoreCase = true) }
                }
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        entry?.readerTitle(selectedEntry ?: 0) ?: "世界书 · ${character.name}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { TextButton(onClick = goBack, modifier = Modifier.testTag("worldBookBack")) { Text("返回") } },
            )
        },
    ) { padding ->
        if (entry != null && book != null) {
            key(selectedBook, selectedEntry) {
                val chunks = remember(entry.content) { readerChunks(entry.content) }
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding).testTag("worldBookEntryContent"),
                    contentPadding = PaddingValues(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item("entry-heading") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(book.name.ifBlank { "世界书 ${(selectedBook ?: 0) + 1}" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(entry.readerTitle(selectedEntry ?: 0), style = MaterialTheme.typography.headlineSmall)
                            Text(entry.activationLabel(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            if (entry.name.isNotBlank() && entry.comment.isNotBlank() && entry.name != entry.comment) Text(entry.name)
                            if (entry.keys.isNotEmpty()) Text("关键词：${entry.keys.joinToString("、")}")
                            if (entry.secondaryKeys.isNotEmpty()) Text("附加关键词：${entry.secondaryKeys.joinToString("、")}")
                        }
                    }
                    if (entry.content.isEmpty()) item { Text("这条世界书没有正文", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    itemsIndexed(chunks) { index, text ->
                        SelectionContainer {
                            Text(text, Modifier.fillMaxWidth().testTag("worldBookText-$index"), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索标题、关键词和正文") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("worldBookSearch"),
                    trailingIcon = {
                        if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除") }
                    },
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("worldBookEntries"),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (character.worldBooks.isEmpty()) {
                        item { Text("这张角色卡没有附带世界书") }
                    } else if (query.isNotBlank() && matches.all { it.second.isEmpty() }) {
                        item { Text("没有找到匹配的条目") }
                    }
                    matches.forEach { (bookIndex, indices) ->
                        val sourceBook = character.worldBooks[bookIndex]
                        if (indices.isNotEmpty() || query.isBlank()) {
                            item("book-$bookIndex") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(sourceBook.name.ifBlank { "世界书 ${bookIndex + 1}" }, style = MaterialTheme.typography.titleLarge)
                                    Text("${indices.size} 个条目", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (sourceBook.description.isNotBlank()) SelectionContainer { Text(sourceBook.description) }
                                    if (sourceBook.entries.isEmpty()) Text("这本世界书没有条目")
                                }
                            }
                            itemsIndexed(indices, key = { _, entryIndex -> "entry-$bookIndex-$entryIndex" }) { _, entryIndex ->
                                val sourceEntry = sourceBook.entries[entryIndex]
                                Card(
                                    onClick = { selectedBook = bookIndex; selectedEntry = entryIndex },
                                    modifier = Modifier.fillMaxWidth().testTag("worldBookEntry-$bookIndex-$entryIndex"),
                                ) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(sourceEntry.readerTitle(entryIndex), style = MaterialTheme.typography.titleMedium)
                                        Text(sourceEntry.activationLabel(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (sourceEntry.keys.isNotEmpty()) Text(
                                            sourceEntry.keys.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(sourceEntry.content.trim().take(180), maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun WorldBookEntryDefinition.readerTitle(index: Int): String = comment.ifBlank { name }.ifBlank { "条目 ${index + 1}" }

private fun WorldBookEntryDefinition.activationLabel(): String = when {
    !enabled -> "已停用"
    constant -> "常驻条目"
    else -> "按条件触发"
}

private fun readerChunks(text: String): List<String> = buildList {
    var start = 0
    while (start < text.length) {
        var end = (start + 8_000).coerceAtMost(text.length)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        add(text.substring(start, end))
        start = end
    }
}
