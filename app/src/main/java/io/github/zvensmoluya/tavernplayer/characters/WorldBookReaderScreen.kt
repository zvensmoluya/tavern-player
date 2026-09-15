package io.github.zvensmoluya.tavernplayer.characters

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookEntryDefinition
import io.github.zvensmoluya.tavernplayer.conversation.ConversationWorldBookState
import io.github.zvensmoluya.tavernplayer.conversation.WorldBookEntryMode
import io.github.zvensmoluya.tavernplayer.conversation.WorldBookInjectionStatus
import io.github.zvensmoluya.tavernplayer.conversation.entryContent
import io.github.zvensmoluya.tavernplayer.conversation.entryMode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Composable
fun WorldBookReaderScreen(character: CharacterAsset, onBack: () -> Unit) = WorldBookReaderScreen(
    readerId = "character-${character.id}", characterName = character.name, books = character.worldBooks, onBack = onBack,
)

/** 角色页与对话共用阅读器；玩家覆盖只在对话内开放。 */
@Composable
fun WorldBookReaderScreen(
    readerId: String,
    characterName: String,
    books: List<WorldBookDefinition>,
    onBack: () -> Unit,
    sessionState: ConversationWorldBookState? = null,
    busy: Boolean = false,
    global: Boolean = false,
    message: String? = null,
    lastInjections: Map<String, Map<String, WorldBookInjectionStatus>>? = null,
    onEntryMode: (String, String, WorldBookEntryMode?) -> Unit = { _, _, _ -> },
    onEntryContent: (String, String, String, () -> Unit) -> Unit = { _, _, _, _ -> },
) {
    ReaderSystemBars()
    var selectedBook by rememberSaveable(readerId) { mutableStateOf<String?>(null) }
    var selectedEntry by rememberSaveable(readerId) { mutableStateOf<String?>(null) }
    var editing by rememberSaveable(readerId) { mutableStateOf(false) }
    var draft by rememberSaveable(readerId) { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    var usageVisible by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val rows = remember(books) {
        books.flatMapIndexed { bookIndex, book ->
            book.entries.withIndex().sortedBy { (index, entry) ->
                (entry.extensions["display_index"] as? JsonPrimitive)?.intOrNull ?: index
            }.map { (entryIndex, entry) -> ReaderEntry(bookIndex, entryIndex, book, entry) }
        }
    }
    val selectedIndex = rows.indexOfFirst { it.book.id == selectedBook && it.entry.id == selectedEntry }
    val selected = rows.getOrNull(selectedIndex)
    val entry = selected?.entry
    val content = selected?.let { sessionState?.entryContent(it.book.id, it.entry) ?: it.entry.content }.orEmpty()
    val playerOverride = sessionState?.playerOverrides?.get(selectedBook)?.get(selectedEntry)
    val isEditing = editing && selected != null
    val mode = selected?.let { sessionState?.entryMode(it.book.id, it.entry) }
    val select: (ReaderEntry) -> Unit = { row ->
        selectedBook = row.book.id
        selectedEntry = row.entry.id
        editing = false
        usageVisible = false
    }
    val goBack: () -> Unit = {
        when {
            usageVisible -> usageVisible = false
            isEditing && busy -> Unit
            isEditing && draft != content -> confirmDiscard = true
            isEditing -> editing = false
            selected != null -> { selectedBook = null; selectedEntry = null }
            else -> onBack()
        }
    }
    BackHandler(onBack = goBack)

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("worldBookReader"),
        containerColor = ReaderColors.Paper,
        contentColor = ReaderColors.Ink,
        topBar = {
            ReaderTopBar(
                title = if (isEditing) "修改正文" else if (selected != null) "世界书" else characterName,
                subtitle = characterName.takeIf { selected != null && !isEditing },
                onBack = goBack, backEnabled = !isEditing || !busy,
                backLabel = if (isEditing) "返回阅读" else if (selected != null) "返回目录" else "返回",
            ) {
                when {
                    isEditing -> Button(
                        onClick = { onEntryContent(selected.book.id, selected.entry.id, draft) { editing = false } },
                        enabled = !busy && draft != content,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ReaderColors.Accent),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                        modifier = Modifier.padding(end = 16.dp).testTag("worldBookSave"),
                    ) { Text(if (busy) "保存中" else "保存", fontSize = 14.sp) }
                    selected != null && sessionState != null -> TextButton(
                        onClick = { draft = content; editing = true }, enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = ReaderColors.Accent),
                        modifier = Modifier.padding(end = 12.dp).testTag("worldBookEditContent"),
                    ) {
                        ReaderIcon(ReaderSymbol.Edit, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("修改", fontSize = 14.sp)
                    }
                    else -> ReaderBadge(if (sessionState == null) "角色附带" else if (global) "全局" else "本次对话",
                        modifier = Modifier.padding(end = 20.dp))
                }
            }
        },
        bottomBar = {
            if (selected != null && !isEditing) ReaderBottomBar(
                index = selectedIndex, count = rows.size, mode = mode, busy = busy, global = global,
                onUsage = { usageVisible = true },
                onPrevious = { rows.getOrNull(selectedIndex - 1)?.let(select) },
                onNext = { rows.getOrNull(selectedIndex + 1)?.let(select) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            when {
                isEditing && entry != null -> ReaderEditor(
                    title = entry.readerTitle(selected.entryIndex), draft = draft, original = entry.content,
                    changed = draft != content, busy = busy, message = message, global = global,
                    onChange = { draft = it }, onRestore = { draft = entry.content },
                )
                selected != null -> key(selected.book.id, selected.entry.id) {
                    val chunks = remember(content) { readerChunks(content) }
                    LazyColumn(
                        Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("worldBookEntryContent"),
                        contentPadding = PaddingValues(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 36.dp),
                    ) {
                        item("heading") {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 26.dp)) {
                                Text(buildString {
                                    append((selectedIndex + 1).toString().padStart(2, '0'))
                                    if (books.size > 1) append("  /  ${selected.book.name.ifBlank { "角色附带内容" }}")
                                }, color = ReaderColors.Muted, fontSize = 12.sp, letterSpacing = 1.sp)
                                Text(selected.entry.readerTitle(selected.entryIndex),
                                    fontSize = 29.sp, lineHeight = 39.sp, fontWeight = FontWeight.SemiBold, color = ReaderColors.Ink)
                                if (sessionState == null) Text(if (entry?.enabled == true) "作者已启用" else "作者已停用",
                                    fontSize = 12.sp, color = ReaderColors.Muted)
                                if (playerOverride?.content != null) ReaderBadge(if (global) "正文已调整 · 全局" else "正文已调整 · 仅本次对话",
                                    accent = true, modifier = Modifier.testTag("worldBookContentChanged"))
                                lastInjections?.get(selected.book.id)?.get(selected.entry.id)?.let { status ->
                                    Text("最近一次生成 · ${status.label()}", fontSize = 12.sp, color = ReaderColors.Muted,
                                        modifier = Modifier.testTag("worldBookLastInjection"))
                                }
                                HorizontalDivider(color = ReaderColors.Line)
                                message?.let { ReaderMessage(it) }
                            }
                        }
                        if (content.isEmpty()) item {
                            Text("这项内容没有正文", color = ReaderColors.Muted, style = ReaderBodyStyle)
                        }
                        itemsIndexed(chunks) { index, text ->
                            SelectionContainer {
                                Text(text, Modifier.fillMaxWidth().testTag("worldBookText-$index"), style = ReaderBodyStyle)
                            }
                        }
                    }
                }
                else -> LazyColumn(
                    Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("worldBookEntries"), state = listState,
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
                ) {
                    item("intro") { ReaderIntroduction(rows.size, sessionState != null, global) }
                    if (rows.isEmpty()) item {
                        ReaderEmptyState(if (books.isEmpty()) "这张角色卡没有附带世界书" else "世界书暂无内容")
                    }
                    itemsIndexed(rows, key = { _, row -> "${row.bookIndex}-${row.entryIndex}" }) { index, row ->
                        ReaderEntryRow(
                            number = index + 1, title = row.entry.readerTitle(row.entryIndex),
                            content = sessionState?.entryContent(row.book.id, row.entry) ?: row.entry.content,
                            source = row.book.name.ifBlank { "角色附带内容" }.takeIf { books.size > 1 },
                            mode = sessionState?.entryMode(row.book.id, row.entry)
                                ?: if (row.entry.enabled) WorldBookEntryMode.AUTO else WorldBookEntryMode.DISABLED,
                            changed = sessionState?.playerOverrides?.get(row.book.id)?.get(row.entry.id)?.content != null,
                            first = index == 0, last = index == rows.lastIndex,
                            onClick = { select(row) }, tag = "worldBookEntry-${row.bookIndex}-${row.entryIndex}",
                        )
                    }
                }
            }
        }
    }
    if (usageVisible && selected != null && mode != null) ReaderUsageSheet(
        title = selected.entry.readerTitle(selected.entryIndex), mode = mode,
        canRestore = playerOverride?.mode != null, busy = busy, global = global,
        onDismiss = { usageVisible = false },
        onSelect = { option ->
            if (option != mode) onEntryMode(selected.book.id, selected.entry.id, option)
            usageVisible = false
        },
        onRestore = { onEntryMode(selected.book.id, selected.entry.id, null); usageVisible = false },
    )
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false }, containerColor = ReaderColors.Paper,
        title = { Text("放弃这次修改？") }, text = { Text("尚未保存的正文修改会丢失。") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; editing = false }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续修改") } },
    )
}

@Composable
private fun ReaderEditor(
    title: String, draft: String, original: String, changed: Boolean, busy: Boolean, message: String?, global: Boolean,
    onChange: (String) -> Unit, onRestore: () -> Unit,
) {
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Column(Modifier.widthIn(max = 720.dp).fillMaxSize().imePadding().padding(horizontal = 26.dp)) {
        if (keyboardVisible) Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                color = ReaderColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (global) "全局修改" else "仅本次对话", fontSize = 11.sp, color = ReaderColors.Muted)
        } else {
            Text(title, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold,
                color = ReaderColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
            Text(if (global) "修改影响所有会话的后续生成，恢复原文使用导入时的正文。" else "仅用于本次对话，不改动角色原卡。", fontSize = 13.sp, lineHeight = 21.sp, color = ReaderColors.Muted)
            if (original.contains("<%")) Text("原文包含模板。修改后若仍含模板代码，该段不会执行或注入；恢复原文可恢复模板。",
                fontSize = 12.sp, lineHeight = 19.sp, color = ReaderColors.Muted, modifier = Modifier.padding(top = 8.dp))
        }
        message?.let { ReaderMessage(it, Modifier.padding(top = 8.dp)) }
        HorizontalDivider(Modifier.padding(vertical = if (keyboardVisible) 12.dp else 20.dp), color = ReaderColors.Line)
        BasicTextField(
            value = draft, onValueChange = onChange, enabled = !busy,
            textStyle = ReaderBodyStyle, cursorBrush = SolidColor(ReaderColors.Accent),
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("worldBookContentEditor"),
            decorationBox = { inner ->
                Box(Modifier.fillMaxSize()) {
                    if (draft.isEmpty()) Text("写下这次游玩使用的内容…", style = ReaderBodyStyle.copy(color = ReaderColors.Muted))
                    inner()
                }
            },
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRestore, enabled = !busy && draft != original,
                colors = ButtonDefaults.textButtonColors(contentColor = ReaderColors.Accent),
                contentPadding = PaddingValues(end = 12.dp), modifier = Modifier.testTag("worldBookRestoreContent")) {
                ReaderIcon(ReaderSymbol.Restore, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("恢复原文", fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(if (changed) "未保存" else "已保存", fontSize = 12.sp,
                color = if (changed) ReaderColors.Accent else ReaderColors.Muted)
        }
    }
}

internal val ReaderBodyStyle = TextStyle(fontSize = 17.sp, lineHeight = 30.sp, letterSpacing = 0.2.sp, color = ReaderColors.Ink)
private data class ReaderEntry(val bookIndex: Int, val entryIndex: Int, val book: WorldBookDefinition, val entry: WorldBookEntryDefinition)
private fun WorldBookEntryDefinition.readerTitle(index: Int): String = comment.ifBlank { name }.ifBlank { "内容 ${index + 1}" }
internal fun WorldBookEntryMode.label(): String = when (this) {
    WorldBookEntryMode.DISABLED -> "停用"
    WorldBookEntryMode.AUTO -> "按原条件"
    WorldBookEntryMode.FORCED -> "始终注入"
}
private fun WorldBookInjectionStatus.label(): String = when (this) {
    WorldBookInjectionStatus.INCLUDED -> "已注入请求"
    WorldBookInjectionStatus.NOT_INCLUDED -> "未注入"
    WorldBookInjectionStatus.UNCONFIRMED -> "未确认注入"
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
