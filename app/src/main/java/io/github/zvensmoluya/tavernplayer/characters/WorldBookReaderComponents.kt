package io.github.zvensmoluya.tavernplayer.characters

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.zvensmoluya.tavernplayer.conversation.WorldBookEntryMode

/** 本模块的纸面、墨色与操作色，不改变其他页面的主题。 */
internal object ReaderColors {
    val Paper = Color(0xFFFAF9F6)
    val Sheet = Color(0xFFFFFFFF)
    val Ink = Color(0xFF263A32)
    val Muted = Color(0xFF6F786F)
    val Line = Color(0xFFE4E7DF)
    val Accent = Color(0xFF326451)
    val Soft = Color(0xFFEBF0E9)
    val Amber = Color(0xFF815C24)
    val AmberSoft = Color(0xFFF5EEDC)
}

@Composable
internal fun ReaderSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        // 全屏 Dialog 有独立窗口，不继承 Activity 的浅色系统栏配置。
        val window = (view.parent as? DialogWindowProvider)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = true
        controller?.isAppearanceLightNavigationBars = true
        onDispose {
            controller?.let {
                if (previousStatus != null) it.isAppearanceLightStatusBars = previousStatus
                if (previousNavigation != null) it.isAppearanceLightNavigationBars = previousNavigation
            }
        }
    }
}

@Composable
internal fun ReaderTopBar(
    title: String, subtitle: String?, onBack: () -> Unit, backEnabled: Boolean, backLabel: String,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .heightIn(min = 64.dp).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, enabled = backEnabled, modifier = Modifier.testTag("worldBookBack")) {
            ReaderIcon(ReaderSymbol.Back, description = backLabel, tint = ReaderColors.Ink)
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 10.dp)) {
            Text(title, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium,
                color = ReaderColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, fontSize = 11.sp, lineHeight = 17.sp, color = ReaderColors.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        actions()
    }
}

@Composable
internal fun ReaderIntroduction(count: Int, inConversation: Boolean, global: Boolean = false) {
    Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 24.dp, bottom = 26.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("世界书", color = ReaderColors.Ink, fontSize = 32.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold)
                Text("共 $count 项内容", fontSize = 13.sp, color = ReaderColors.Muted, modifier = Modifier.padding(top = 6.dp))
            }
            Box(Modifier.size(54.dp).background(ReaderColors.Soft, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                ReaderIcon(ReaderSymbol.Book, tint = ReaderColors.Accent, modifier = Modifier.size(27.dp))
            }
        }
        if (inConversation) Text(if (global) "修改影响所有会话的后续生成。" else "阅读角色设定，调整这一次的游玩。", fontSize = 13.sp, lineHeight = 21.sp,
            color = ReaderColors.Muted, modifier = Modifier.padding(top = 18.dp))
    }
}

@Composable
internal fun ReaderEntryRow(
    number: Int, title: String, content: String, source: String?, mode: WorldBookEntryMode,
    changed: Boolean, first: Boolean, last: Boolean, onClick: () -> Unit, tag: String,
) {
    val shape = RoundedCornerShape(topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp,
        bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp)
    val preview = remember(content) {
        when {
            content.isBlank() -> "暂无正文"
            content.contains("<%") || content.trimStart().startsWith("<script", ignoreCase = true) -> "包含模板内容，展开查看原文"
            else -> content.take(300).replace(Regex("\\s+"), " ").trim()
        }
    }
    Column(Modifier.fillMaxWidth().clip(shape).background(ReaderColors.Sheet)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick, role = Role.Button).testTag(tag)
            .padding(horizontal = 18.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(number.toString().padStart(2, '0'), fontFamily = FontFamily.Serif, fontSize = 17.sp,
                color = ReaderColors.Muted, modifier = Modifier.widthIn(min = 22.dp).padding(top = 2.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium,
                    color = ReaderColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(preview, fontSize = 13.sp, lineHeight = 22.sp, color = ReaderColors.Muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (source != null) Text(source, fontSize = 11.sp, color = ReaderColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (mode != WorldBookEntryMode.AUTO || changed) FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (mode == WorldBookEntryMode.FORCED) ReaderBadge("始终注入", forced = true)
                    if (mode == WorldBookEntryMode.DISABLED) ReaderBadge("已停用")
                    if (changed) ReaderBadge("正文已调整", accent = true)
                }
            }
            ReaderIcon(ReaderSymbol.Next, tint = ReaderColors.Muted, modifier = Modifier.padding(top = 4.dp).size(17.dp))
        }
        if (!last) HorizontalDivider(Modifier.padding(start = 54.dp, end = 18.dp), color = ReaderColors.Line.copy(alpha = 0.7f))
    }
}

@Composable
internal fun ReaderBadge(text: String, modifier: Modifier = Modifier, accent: Boolean = false, forced: Boolean = false) {
    Text(text, modifier.background(if (forced) ReaderColors.AmberSoft else ReaderColors.Soft, RoundedCornerShape(6.dp))
        .padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp, lineHeight = 16.sp,
        color = if (forced) ReaderColors.Amber else if (accent) ReaderColors.Accent else ReaderColors.Muted)
}

@Composable
internal fun ReaderEmptyState(text: String) {
    Column(Modifier.fillMaxWidth().background(ReaderColors.Sheet, RoundedCornerShape(20.dp)).padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReaderIcon(ReaderSymbol.Book, tint = ReaderColors.Muted, modifier = Modifier.size(32.dp))
        Text(text, fontSize = 15.sp, color = ReaderColors.Muted)
    }
}

@Composable
internal fun ReaderMessage(message: String, modifier: Modifier = Modifier) {
    Text(message, modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp)).padding(12.dp),
        color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp, lineHeight = 21.sp)
}

@Composable
internal fun ReaderBottomBar(
    index: Int, count: Int, mode: WorldBookEntryMode?, busy: Boolean, global: Boolean = false,
    onUsage: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
) {
    Surface(color = ReaderColors.Paper, shadowElevation = 3.dp) {
        Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
            contentAlignment = Alignment.Center) {
            Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (mode != null) Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(if (mode == WorldBookEntryMode.FORCED) ReaderColors.AmberSoft else ReaderColors.Soft)
                        .clickable(enabled = !busy, role = Role.Button, onClick = onUsage)
                        .semantics { contentDescription = "内容使用方式"; stateDescription = mode.label() }
                        .testTag("worldBookUsage").padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (global) "全局" else "本次对话", fontSize = 10.sp, lineHeight = 14.sp, color = ReaderColors.Muted)
                        Text(mode.label(), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                            color = if (mode == WorldBookEntryMode.FORCED) ReaderColors.Amber else ReaderColors.Ink)
                    }
                    ReaderIcon(ReaderSymbol.Expand, tint = ReaderColors.Muted, modifier = Modifier.size(18.dp))
                } else Text("角色附带 · 只读", Modifier.weight(1f).padding(start = 8.dp), fontSize = 12.sp, color = ReaderColors.Muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevious, enabled = index > 0, modifier = Modifier.testTag("worldBookPrevious")) {
                        ReaderIcon(ReaderSymbol.Previous, description = "上一项", tint = if (index > 0) ReaderColors.Ink else ReaderColors.Line)
                    }
                    Text("${index + 1} / $count", fontSize = 11.sp, color = ReaderColors.Muted)
                    IconButton(onClick = onNext, enabled = index < count - 1, modifier = Modifier.testTag("worldBookNext")) {
                        ReaderIcon(ReaderSymbol.Next, description = "下一项", tint = if (index < count - 1) ReaderColors.Ink else ReaderColors.Line)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderUsageSheet(
    title: String, mode: WorldBookEntryMode, canRestore: Boolean, busy: Boolean, global: Boolean = false,
    onDismiss: () -> Unit, onSelect: (WorldBookEntryMode) -> Unit, onRestore: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ReaderColors.Paper, contentColor = ReaderColors.Ink,
        modifier = Modifier.testTag("worldBookUsageSheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text("内容使用方式", fontSize = 23.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
            Text(title, fontSize = 13.sp, lineHeight = 21.sp, color = ReaderColors.Muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, bottom = 22.dp))
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(WorldBookEntryMode.AUTO, WorldBookEntryMode.FORCED, WorldBookEntryMode.DISABLED).forEach { option ->
                    val selected = option == mode
                    val tint = if (option == WorldBookEntryMode.FORCED) ReaderColors.Amber else ReaderColors.Accent
                    Surface(shape = RoundedCornerShape(16.dp),
                        color = if (selected) (if (option == WorldBookEntryMode.FORCED) ReaderColors.AmberSoft else ReaderColors.Soft) else ReaderColors.Sheet,
                        border = BorderStroke(1.dp, if (selected) tint.copy(alpha = 0.35f) else ReaderColors.Line)) {
                        Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = !busy, role = Role.RadioButton,
                            onClick = { onSelect(option) }).testTag("worldBookEntryMode-${option.name}")
                            .padding(horizontal = 16.dp, vertical = 17.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            ReaderIcon(when (option) {
                                WorldBookEntryMode.AUTO -> ReaderSymbol.Auto
                                WorldBookEntryMode.FORCED -> ReaderSymbol.Pin
                                WorldBookEntryMode.DISABLED -> ReaderSymbol.Pause
                            }, tint = if (selected) tint else ReaderColors.Muted, modifier = Modifier.size(21.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(option.label(), fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                                Text(when (option) {
                                    WorldBookEntryMode.AUTO -> "按作者的触发条件使用"
                                    WorldBookEntryMode.FORCED -> "每次生成都加入这段内容"
                                    WorldBookEntryMode.DISABLED -> "后续生成不再使用这段内容"
                                }, fontSize = 12.sp, lineHeight = 19.sp, color = ReaderColors.Muted)
                            }
                            if (selected) ReaderIcon(ReaderSymbol.Check, tint = tint, modifier = Modifier.size(20.dp))
                            else Spacer(Modifier.size(20.dp))
                        }
                    }
                }
            }
            Text(if (global) "该书启用时，用于所有会话的后续生成。" else "只用于本次对话，从下一次生成开始。", fontSize = 12.sp, lineHeight = 20.sp,
                color = ReaderColors.Muted, modifier = Modifier.padding(top = 18.dp))
            if (canRestore) TextButton(onClick = onRestore, enabled = !busy,
                colors = ButtonDefaults.textButtonColors(contentColor = ReaderColors.Accent),
                modifier = Modifier.padding(top = 8.dp).testTag("worldBookRestoreMode")) {
                ReaderIcon(ReaderSymbol.Restore, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("恢复原设置", fontSize = 13.sp)
            }
        }
    }
}

internal enum class ReaderSymbol { Back, Previous, Next, Expand, Book, Edit, Restore, Check, Auto, Pin, Pause }

// 简单线条图标直接使用原生矢量，跟随密度缩放，不引入图片或图标字体。
private val readerIcons = ReaderSymbol.entries.associateWith { symbol ->
    ImageVector.Builder(symbol.name, 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            when (symbol) {
                ReaderSymbol.Back -> { moveTo(19f, 12f); lineTo(5f, 12f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) }
                ReaderSymbol.Previous -> { moveTo(15f, 6f); lineTo(9f, 12f); lineTo(15f, 18f) }
                ReaderSymbol.Next -> { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
                ReaderSymbol.Expand -> { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
                ReaderSymbol.Book -> {
                    moveTo(12f, 6f); curveTo(9f, 3f, 5f, 3f, 2f, 4f); lineTo(2f, 19f)
                    curveTo(6f, 18f, 9f, 18f, 12f, 21f); curveTo(15f, 18f, 18f, 18f, 22f, 19f)
                    lineTo(22f, 4f); curveTo(19f, 3f, 15f, 3f, 12f, 6f); lineTo(12f, 21f)
                }
                ReaderSymbol.Edit -> {
                    moveTo(4f, 20f); lineTo(5f, 15f); lineTo(17f, 3f); lineTo(21f, 7f); lineTo(9f, 19f); close()
                    moveTo(14f, 6f); lineTo(18f, 10f)
                }
                ReaderSymbol.Restore, ReaderSymbol.Auto -> {
                    moveTo(4f, 10f); curveTo(5f, 5f, 10f, 3f, 15f, 5f); curveTo(22f, 8f, 21f, 18f, 14f, 20f)
                    curveTo(10f, 21f, 6f, 19f, 5f, 17f); moveTo(3f, 4f); lineTo(3f, 10f); lineTo(9f, 10f)
                    if (symbol == ReaderSymbol.Auto) { moveTo(12f, 8f); lineTo(12f, 12f); lineTo(15f, 14f) }
                }
                ReaderSymbol.Check -> { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(19f, 7f) }
                ReaderSymbol.Pin -> {
                    moveTo(8f, 3f); lineTo(16f, 3f); moveTo(9f, 3f); lineTo(9f, 9f); lineTo(5f, 14f)
                    lineTo(19f, 14f); lineTo(15f, 9f); lineTo(15f, 3f); moveTo(12f, 14f); lineTo(12f, 21f)
                }
                ReaderSymbol.Pause -> { moveTo(8f, 5f); lineTo(8f, 19f); moveTo(16f, 5f); lineTo(16f, 19f) }
            }
        }
    }.build()
}

@Composable
internal fun ReaderIcon(symbol: ReaderSymbol, modifier: Modifier = Modifier, description: String? = null, tint: Color = LocalContentColor.current) {
    Icon(readerIcons.getValue(symbol), contentDescription = description, modifier = modifier.size(24.dp), tint = tint)
}
