package io.github.zvensmoluya.tavernplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class PlayerSymbol { CHAT, CHARACTERS, MODEL, PERSON, SEARCH, ADD, GRID, LIST, CHEVRON, BOOK, PRESET, CLOSE }

/** Small shared line icons; no bitmap assets or font dependency. */
@Composable
fun PlayerIcon(symbol: PlayerSymbol, description: String? = null, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp).then(if (description == null) Modifier else Modifier.semantics {
        contentDescription = description
    })) {
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            val stroke = Stroke(1.65f, cap = StrokeCap.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = 1.65f, cap = StrokeCap.Round)
            fun circle(x: Float, y: Float, r: Float) = drawCircle(color, r, Offset(x, y), style = stroke)
            fun box(x: Float, y: Float, w: Float, h: Float, radius: Float = 2f) =
                drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(radius), style = stroke)
            when (symbol) {
                PlayerSymbol.CHAT -> drawPath(Path().apply {
                    moveTo(5f, 18f); lineTo(3f, 22f); lineTo(10f, 19f)
                    cubicTo(24f, 21f, 24f, 3f, 13f, 3f)
                    cubicTo(2f, 2f, 0f, 13f, 5f, 18f)
                }, color, style = stroke)
                PlayerSymbol.CHARACTERS -> {
                    box(5f, 5f, 15f, 17f); line(2f, 17f, 2f, 2f); line(2f, 2f, 16f, 2f)
                    circle(12.5f, 11f, 2.4f)
                    drawArc(color, 180f, 180f, false, Offset(8f, 15f), Size(9f, 6f), style = stroke)
                }
                PlayerSymbol.MODEL -> {
                    line(10f, 6f, 5f, 16f); line(14f, 6f, 19f, 16f); line(7f, 19f, 17f, 19f)
                    circle(12f, 4f, 3f); circle(4f, 19f, 3f); circle(20f, 19f, 3f)
                }
                PlayerSymbol.PERSON -> {
                    circle(12f, 7f, 4f)
                    drawArc(color, 180f, 180f, false, Offset(4f, 14f), Size(16f, 14f), style = stroke)
                    line(4f, 21f, 20f, 21f)
                }
                PlayerSymbol.SEARCH -> { circle(10f, 10f, 7f); line(15f, 15f, 22f, 22f) }
                PlayerSymbol.ADD -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
                PlayerSymbol.CLOSE -> { line(5f, 5f, 19f, 19f); line(19f, 5f, 5f, 19f) }
                PlayerSymbol.GRID -> for (x in listOf(3f, 14f)) for (y in listOf(3f, 14f)) box(x, y, 7f, 7f, 1f)
                PlayerSymbol.LIST -> for (y in listOf(4f, 11f, 18f)) {
                    box(3f, y, 3f, 3f, 0.5f); line(10f, y + 1.5f, 21f, y + 1.5f)
                }
                PlayerSymbol.CHEVRON -> { line(9f, 5f, 16f, 12f); line(16f, 12f, 9f, 19f) }
                PlayerSymbol.BOOK -> {
                    drawPath(Path().apply {
                        moveTo(12f, 5f); quadraticTo(7f, 2f, 2f, 4f); lineTo(2f, 20f)
                        quadraticTo(7f, 18f, 12f, 21f); quadraticTo(17f, 18f, 22f, 20f)
                        lineTo(22f, 4f); quadraticTo(17f, 2f, 12f, 5f); lineTo(12f, 21f)
                    }, color, style = stroke)
                }
                PlayerSymbol.PRESET -> {
                    line(3f, 6f, 21f, 6f); line(3f, 12f, 21f, 12f); line(3f, 18f, 21f, 18f)
                    circle(8f, 6f, 2f); circle(16f, 12f, 2f); circle(10f, 18f, 2f)
                }
            }
        }
    }
}
