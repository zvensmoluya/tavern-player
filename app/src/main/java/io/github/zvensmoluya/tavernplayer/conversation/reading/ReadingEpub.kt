package io.github.zvensmoluya.tavernplayer.conversation.reading

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal const val EPUB_MIME = "application/epub+zip"
class ReadingFileProvider : FileProvider()

private val invalidXml = Regex("[^\\x{9}\\x{a}\\x{d}\\x{20}-\\x{d7ff}\\x{e000}-\\x{fffd}\\x{10000}-\\x{10ffff}]")
private fun xml(value: String) = invalidXml.replace(value, "�").replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;")

internal fun ReadingBook.filename(): String {
    val stem = title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim(' ', '.')
    return stem.take(80).ifBlank { "阅读稿" } + ".epub"
}

internal fun ReadingBook.cachedEpub(context: Context): File {
    val dir = File(context.cacheDir, "reading").apply { mkdirs() }
    val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
    dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
    val file = File.createTempFile("book-", ".epub", dir)
    try {
        file.outputStream().buffered().use { writeEpub(it) }
        return file
    } catch (error: Exception) { file.delete(); throw error }
}

internal fun readerIntent(context: Context, file: File, displayName: String): Intent {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".reading", file, displayName)
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, EPUB_MIME)
        clipData = ClipData.newRawUri(displayName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun ReadingBook.cover(output: OutputStream) {
    val bitmap = Bitmap.createBitmap(600, 860, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(244, 239, 228))
        val ink = Color.rgb(51, 48, 41)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
        canvas.drawRect(48f, 128f, 126f, 132f, paint)
        fun draw(text: String, size: Float, top: Float, lines: Int, font: Typeface) {
            paint.textSize = size
            paint.typeface = font
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 500)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build()
            canvas.save()
            canvas.translate(48f, top)
            layout.draw(canvas)
            canvas.restore()
        }
        draw(title, 48f, 184f, 7, Typeface.SERIF)
        draw("与${character}的对话", 25f, 710f, 3, Typeface.SANS_SERIF)
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "无法生成封面" }
    } finally { bitmap.recycle() }
}

private fun page(title: String, body: String) = """<?xml version="1.0" encoding="utf-8"?>
    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh">
    <head><title>${xml(title)}</title><link rel="stylesheet" href="book.css" type="text/css"/></head>
    <body>$body</body></html>""".trimIndent()

private fun ReadingEntry.markup() = buildString {
    append(if (player) "<div class=\"message player\">" else "<div class=\"message\">")
    append("<p class=\"speaker\"><strong>").append(xml(speaker)).append("</strong></p>")
    for (paragraph in paragraphs) {
        append("<p>")
        for (run in paragraph) {
            if (run.bold) append("<strong>")
            if (run.italic) append("<em>")
            append(xml(run.text).replace("\n", "<br/>"))
            if (run.italic) append("</em>")
            if (run.bold) append("</strong>")
        }
        append("</p>")
    }
    append("</div>")
}

internal fun ReadingBook.writeEpub(output: OutputStream) {
    require(entries.isNotEmpty())
    val chapters = entries.chunked(40)
    val id = "urn:uuid:" + UUID.randomUUID()
    val stamp = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    ZipOutputStream(output).use { zip ->
        val mime = EPUB_MIME.toByteArray(Charsets.US_ASCII)
        zip.putNextEntry(ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = mime.size.toLong()
            compressedSize = size
            crc = CRC32().apply { update(mime) }.value
        })
        zip.write(mime)
        zip.closeEntry()
        fun text(path: String, value: String) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(value.encodeToByteArray())
            zip.closeEntry()
        }
        text("META-INF/container.xml", """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles><rootfile full-path="EPUB/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
        val items = chapters.indices.joinToString("") { """<item id="part$it" href="part$it.xhtml" media-type="application/xhtml+xml"/>""" }
        val spine = chapters.indices.joinToString("") { """<itemref idref="part$it"/>""" }
        text("EPUB/book.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">$id</dc:identifier>
            <dc:title>${xml(title)}</dc:title><dc:language>zh</dc:language><meta property="dcterms:modified">$stamp</meta></metadata>
            <manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="css" href="book.css" media-type="text/css"/><item id="cover" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
            <item id="front" href="cover.xhtml" media-type="application/xhtml+xml"/>$items</manifest><spine><itemref idref="front"/>$spine</spine></package>""")
        zip.putNextEntry(ZipEntry("EPUB/cover.jpg"))
        cover(zip)
        zip.closeEntry()
        text("EPUB/book.css", ".message{margin:0 0 1.5em}.player{margin-left:1em}.speaker{font-size:.9em;text-indent:0;margin:0 0 .4em}p{margin:.5em 0}img{max-width:100%;max-height:95vh}")
        text("EPUB/cover.xhtml", page(title, """<img src="cover.jpg" alt="${xml(title)} · 与${xml(character)}的对话"/>"""))
        val links = buildString {
            var start = 1
            chapters.forEachIndexed { index, entries ->
                val label = if (chapters.size == 1) "正文" else "第 $start–${start + entries.size - 1} 条"
                append("""<li><a href="part$index.xhtml">$label</a></li>""")
                text("EPUB/part$index.xhtml", page(label, entries.joinToString("") { it.markup() }))
                start += entries.size
            }
        }
        text("EPUB/nav.xhtml", page("目录", """<nav epub:type="toc"><h1>目录</h1><ol>$links</ol></nav>"""))
    }
}
