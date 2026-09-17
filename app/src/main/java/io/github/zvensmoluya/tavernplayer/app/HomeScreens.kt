package io.github.zvensmoluya.tavernplayer.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zvensmoluya.tavernplayer.characters.CharacterLibraryUiState
import io.github.zvensmoluya.tavernplayer.conversation.Persona
import io.github.zvensmoluya.tavernplayer.conversation.sanitizeCardText
import io.github.zvensmoluya.tavernplayer.ui.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val homeTabs = listOf(AppSurface.CONVERSATIONS, AppSurface.CHARACTER_LIBRARY, AppSurface.MODELS, AppSurface.MY)

@Composable
internal fun HomeScaffold(surface: AppSurface, showNavigation: Boolean = true, onNavigate: (AppSurface) -> Unit, content: @Composable () -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets(0), bottomBar = {
        if (showNavigation && surface in homeTabs) {
            Column(Modifier.testTag("homeNavigation")) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                    homeTabs.forEach { tab ->
                        val (label, icon) = when (tab) {
                            AppSurface.CONVERSATIONS -> "对话" to PlayerSymbol.CHAT
                            AppSurface.CHARACTER_LIBRARY -> "角色" to PlayerSymbol.CHARACTERS
                            AppSurface.MODELS -> "模型" to PlayerSymbol.MODEL
                            else -> "我的" to PlayerSymbol.PERSON
                        }
                        NavigationBarItem(selected = surface == tab, onClick = { onNavigate(tab) },
                            icon = { PlayerIcon(icon) }, label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier.testTag("tab-${tab.name}"))
                    }
                }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
    }
}

@Composable
internal fun ConversationLibraryScreen(
    state: CharacterLibraryUiState,
    avatarPath: (String) -> String?,
    onOpenConversation: (String) -> Unit,
    onOpenCharacters: () -> Unit,
) {
    val characters = remember(state.characters) { state.characters.associateBy { it.id } }
    // Do not group by character: one row is one independently saved story.
    val conversations = remember(state.conversations) {
        state.conversations.sortedWith(compareByDescending<io.github.zvensmoluya.tavernplayer.conversation.storage.ConversationSummary> { it.updatedAtEpochMillis }.thenBy { it.id })
    }
    Scaffold(topBar = {
        Column {
            LibraryHeader("对话", if (conversations.isEmpty()) "从一次相遇开始" else "${conversations.size} 场对话 · 继续上次的故事") {
                IconButton(onClick = onOpenCharacters) { PlayerIcon(PlayerSymbol.ADD, "选择角色开始新对话") }
            }
            if (state.openingConversation) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).testTag("conversationNotice"),
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("conversationList"),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            if (conversations.isEmpty()) item {
                LibraryEmptyState(PlayerSymbol.CHAT, "还没有对话", "选一位角色，开启故事。之后都可以在这里接着聊。") {
                    Button(onClick = onOpenCharacters) { Text("去看看角色") }
                }
            }
            items(conversations, key = { it.id }) { conversation ->
                val character = characters[conversation.assetId]
                val name = character?.name ?: "已保存的角色"
                val preview = remember(conversation.preview) {
                    sanitizeCardText(conversation.preview).replace(Regex("\\s+"), " ").trim().ifBlank { "打开查看对话" }
                }
                Row(Modifier.fillMaxWidth().testTag("recent-${conversation.id}")
                    .clickable(enabled = !state.busy) { onOpenConversation(conversation.id) }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CharacterPortrait(avatarPath(conversation.assetId), name, Modifier.size(58.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(conversationTime(conversation.updatedAtEpochMillis), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${conversation.turnCount} 条消息 · 开始于 ${conversationDate(conversation.createdAtEpochMillis)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(Modifier.padding(start = 92.dp, end = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
internal fun MyScreen(persona: Persona, onOpenPersona: () -> Unit, onOpenPresets: () -> Unit, onOpenWorldBooks: () -> Unit) {
    Scaffold(topBar = { LibraryHeader("我的", "你的身份与故事偏好") }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card(onClick = onOpenPersona, modifier = Modifier.fillMaxWidth().testTag("openPersona"),
                    shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        io.github.zvensmoluya.tavernplayer.personas.PersonaAvatar(persona.avatar, persona.name, Modifier.size(60.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(persona.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("编辑我的身份", style = MaterialTheme.typography.bodyMedium)
                        }
                        PlayerIcon(PlayerSymbol.CHEVRON)
                    }
                }
            }
            item {
                SettingsEntry("预设", "管理写作指导与生成偏好", PlayerSymbol.PRESET, "openPresetsFromMy", onOpenPresets)
            }
            item {
                SettingsEntry("全局世界书", "选择跨角色使用的背景知识", PlayerSymbol.BOOK, "openGlobalWorldBooks", onOpenWorldBooks)
            }
            item { Text("Tavern Player", Modifier.fillMaxWidth().padding(top = 24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SettingsEntry(title: String, subtitle: String, symbol: PlayerSymbol, tag: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PlayerIcon(symbol)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PlayerIcon(PlayerSymbol.CHEVRON)
        }
    }
}

private fun conversationTime(time: Long): String {
    val date = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    val today = java.time.LocalDate.now()
    return when (date.toLocalDate()) {
        today -> date.format(DateTimeFormatter.ofPattern("HH:mm"))
        today.minusDays(1) -> "昨天"
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "MM-dd" else "yyyy-MM-dd"))
    }
}

private fun conversationDate(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
