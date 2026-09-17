package io.github.zvensmoluya.tavernplayer.characters

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.ui.*

@Composable
fun CharacterLibraryScreen(
    state: CharacterLibraryUiState,
    avatarPath: (String) -> String?,
    onImport: () -> Unit,
    onImportFromShelf: () -> Unit,
    onSelectCharacter: (String) -> Unit,
    onLayoutChange: (CharacterLibraryLayout) -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var importMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val characters = remember(state.characters, query) {
        val term = query.trim()
        state.characters.filter { term.isEmpty() || it.name.contains(term, true) || it.tags.any { tag -> tag.contains(term, true) } }
    }
    BackHandler(enabled = searching) { searching = false; query = "" }
    Scaffold(topBar = {
        Column {
            LibraryHeader("角色", if (query.isBlank()) "${state.characters.size} 位角色" else "找到 ${characters.size} 位角色") {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    PlayerIcon(PlayerSymbol.SEARCH, "搜索角色")
                }
                IconButton(onClick = { onLayoutChange(if (state.layout == CharacterLibraryLayout.GRID) CharacterLibraryLayout.LIST else CharacterLibraryLayout.GRID) },
                    modifier = Modifier.testTag("switchCharacterLayout")) {
                    PlayerIcon(if (state.layout == CharacterLibraryLayout.GRID) PlayerSymbol.LIST else PlayerSymbol.GRID,
                        if (state.layout == CharacterLibraryLayout.GRID) "切换为单列名册" else "切换为两列封面")
                }
                Box {
                    IconButton(onClick = { importMenu = true }, enabled = !state.busy,
                        modifier = Modifier.testTag("addCharacter")) { PlayerIcon(PlayerSymbol.ADD, "导入角色") }
                    DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                        DropdownMenuItem(text = { Text("从文件导入") }, onClick = { importMenu = false; onImport() },
                            modifier = Modifier.testTag("importCharacter"))
                        DropdownMenuItem(text = { Text("扫描 Tavern Shelf") }, onClick = { importMenu = false; onImportFromShelf() },
                            modifier = Modifier.testTag("importFromShelf"))
                    }
                }
            }
            if (searching) OutlinedTextField(query, { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp).testTag("characterSearch"),
                placeholder = { Text("搜索名字或标签") }, singleLine = true,
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { PlayerIcon(PlayerSymbol.CLOSE, "清空搜索") } })
            if (state.importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).testTag("libraryNotice"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        when {
            state.characters.isEmpty() -> LazyColumn(contentModifier) {
                item { LibraryEmptyState(PlayerSymbol.CHARACTERS, "还没有角色", "导入一张角色卡，开始你的第一场故事。") {
                    Button(onClick = onImport, enabled = !state.busy) { Text("导入角色卡") }
                    TextButton(onClick = onImportFromShelf, enabled = !state.busy) { Text("扫描 Tavern Shelf") }
                } }
            }
            characters.isEmpty() -> LazyColumn(contentModifier) {
                item { LibraryEmptyState(PlayerSymbol.SEARCH, "没有找到角色", "换个名字或标签试试。") {
                    TextButton(onClick = { query = "" }) { Text("显示全部角色") }
                } }
            }
            state.layout == CharacterLibraryLayout.GRID -> LazyVerticalGrid(
                GridCells.Fixed(2), modifier = contentModifier.testTag("characterGrid"), state = gridState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(characters, key = CharacterAsset::id) { character ->
                    Card(onClick = { onSelectCharacter(character.id) }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("character-${character.id}"),
                        shape = RoundedCornerShape(18.dp), border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        CharacterPortrait(avatarPath(character.id), character.name, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(character.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(character.librarySubtitle(), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            else -> LazyColumn(contentModifier.testTag("characterList"), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                items(characters, key = CharacterAsset::id) { character ->
                    Row(Modifier.fillMaxWidth().testTag("character-${character.id}")
                        .clickable(enabled = !state.busy) { onSelectCharacter(character.id) }.padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CharacterPortrait(avatarPath(character.id), character.name, Modifier.size(64.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(character.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(character.librarySubtitle(), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        PlayerIcon(PlayerSymbol.CHEVRON)
                    }
                    HorizontalDivider(Modifier.padding(start = 100.dp, end = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                }
            }
        }
    }
}

internal fun CharacterAsset.librarySubtitle(): String = tags.map(String::trim).filter(String::isNotEmpty).take(3)
    .joinToString(" · ").ifBlank { creator.trim().takeIf(String::isNotEmpty)?.let { "作者 · $it" } ?: "查看角色详情" }
