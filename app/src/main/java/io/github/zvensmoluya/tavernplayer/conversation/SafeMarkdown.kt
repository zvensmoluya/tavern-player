package io.github.zvensmoluya.tavernplayer.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Deliberately small, non-HTML renderer for card-authored chat text. Active markup is discarded
 * before a basic Markdown subset is interpreted; no links, CSS or scripts become interactive.
 */
@Composable
fun SafeMarkdownText(
    source: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(source) {
        val bounded = if (source.length > MAX_RENDER_SOURCE_CHARS) {
            source.take(MAX_RENDER_SOURCE_CHARS) + "\n\n[内容过长，显示已截断；原始内容仍完整保存]"
        } else {
            source
        }
        parseMarkdownBlocks(sanitizeCardText(bounded))
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block.kind) {
                MarkdownBlockKind.CODE -> Text(
                    text = block.text,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                MarkdownBlockKind.QUOTE -> Row {
                    Box(
                        Modifier.width(3.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                    )
                    Text(
                        inlineMarkdown(block.text),
                        modifier = Modifier.padding(start = 10.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                    )
                }
                MarkdownBlockKind.HEADING -> Text(
                    inlineMarkdown(block.text),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                MarkdownBlockKind.BULLET -> Text(
                    inlineMarkdown("• ${block.text}"),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                MarkdownBlockKind.PARAGRAPH -> Text(
                    inlineMarkdown(block.text),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

internal fun sanitizeCardText(source: String): String {
    if (source.isEmpty()) return source
    var value = SCRIPT_STYLE_BLOCK.replace(source, "")
    value = UNCLOSED_SCRIPT_STYLE.replace(value, "")
    value = BREAK_TAG.replace(value, "\n")
    value = BLOCK_END_TAG.replace(value, "\n")
    value = HTML_TAG.replace(value, "")
    value = NUMERIC_ENTITY.replace(value) { match ->
        val raw = match.groupValues[1]
        val codePoint = if (raw.startsWith("x", ignoreCase = true)) raw.drop(1).toIntOrNull(16) else raw.toIntOrNull()
        codePoint?.takeIf(Character::isValidCodePoint)?.let(Character::toChars)?.concatToString() ?: match.value
    }
    NAMED_ENTITIES.forEach { (entity, replacement) -> value = value.replace(entity, replacement, ignoreCase = true) }
    return value.replace("\u0000", "").replace(Regex("\n{3,}"), "\n\n").trim()
}

private fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    if (text.isBlank()) return emptyList()
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val code = mutableListOf<String>()
    var inCode = false
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock(MarkdownBlockKind.PARAGRAPH, paragraph.joinToString("\n"))
            paragraph.clear()
        }
    }
    fun flushCode() {
        val visibleLines = code.dropWhile(String::isBlank).dropLastWhile(String::isBlank)
        if (visibleLines.isNotEmpty()) {
            blocks += MarkdownBlock(MarkdownBlockKind.CODE, visibleLines.joinToString("\n"))
        }
        code.clear()
    }
    text.lineSequence().forEach { rawLine ->
        val line = rawLine.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (inCode) flushCode() else flushParagraph()
            inCode = !inCode
            return@forEach
        }
        if (inCode) {
            code += line
            return@forEach
        }
        when {
            line.isBlank() -> flushParagraph()
            HEADING.matches(line) -> {
                flushParagraph()
                blocks += MarkdownBlock(MarkdownBlockKind.HEADING, line.substringAfter(' ').trim())
            }
            line.trimStart().startsWith('>') -> {
                flushParagraph()
                blocks += MarkdownBlock(MarkdownBlockKind.QUOTE, line.trimStart().removePrefix(">").trimStart())
            }
            BULLET.matches(line) -> {
                flushParagraph()
                blocks += MarkdownBlock(MarkdownBlockKind.BULLET, line.replaceFirst(BULLET, ""))
            }
            ORDERED_LIST.matches(line) -> {
                flushParagraph()
                blocks += MarkdownBlock(MarkdownBlockKind.BULLET, line.replaceFirst(ORDERED_LIST, ""))
            }
            else -> paragraph += line
        }
    }
    if (inCode) flushCode() else flushParagraph()
    return blocks
}

@Composable
private fun inlineMarkdown(raw: String): AnnotatedString = remember(raw) { parseInlineMarkdown(raw) }

private fun parseInlineMarkdown(raw: String): AnnotatedString {
    val source = MARKDOWN_LINK.replace(raw) { it.groupValues[1] }
    return buildAnnotatedString {
        var cursor = 0
        INLINE_TOKEN.findAll(source).forEach { match ->
            append(source.substring(cursor, match.range.first))
            val token = match.value
            val (content, style) = when {
                token.startsWith('`') -> token.drop(1).dropLast(1) to SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = Color(0x1A808080),
                )
                token.startsWith("**") || token.startsWith("__") -> token.drop(2).dropLast(2) to SpanStyle(
                    fontWeight = FontWeight.Bold,
                )
                else -> token.drop(1).dropLast(1) to SpanStyle(fontStyle = FontStyle.Italic)
            }
            val start = length
            append(content)
            addStyle(style, start, length)
            cursor = match.range.last + 1
        }
        append(source.substring(cursor))
    }
}

private enum class MarkdownBlockKind { PARAGRAPH, HEADING, QUOTE, BULLET, CODE }

private data class MarkdownBlock(val kind: MarkdownBlockKind, val text: String)

private val SCRIPT_STYLE_BLOCK = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")
private val UNCLOSED_SCRIPT_STYLE = Regex("(?is)<(?:script|style)\\b[^>]*>.*$")
private val BREAK_TAG = Regex("(?i)<br\\s*/?>")
private val BLOCK_END_TAG = Regex("(?i)</(?:p|div|li|blockquote|h[1-6])\\s*>")
private val HTML_TAG = Regex("(?s)<[^>]*>")
private val NUMERIC_ENTITY = Regex("&#(x[0-9a-f]+|[0-9]+);", RegexOption.IGNORE_CASE)
private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+.+$")
private val BULLET = Regex("^\\s*[-+*]\\s+")
private val ORDERED_LIST = Regex("^\\s*\\d+[.)]\\s+")
private val MARKDOWN_LINK = Regex("\\[([^]\\n]+)]\\([^)]*\\)")
private val INLINE_TOKEN = Regex("`[^`\\n]+`|\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__|\\*[^*\\n]+\\*|_[^_\\n]+_")
private val NAMED_ENTITIES = linkedMapOf(
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\"",
    "&#39;" to "'",
    "&apos;" to "'",
    "&nbsp;" to " ",
    "&amp;" to "&",
)
private const val MAX_RENDER_SOURCE_CHARS = 200_000
