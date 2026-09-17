package io.github.zvensmoluya.tavernplayer.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

private val Paper = Color(0xFFF8F7F3)
private val Ink = Color(0xFF655E58)
private val Sage = Color(0xFF8B9E8B)
private val Rose = Color(0xFFD5ACB1)
private val Blue = Color(0xFF9EAFBF)

/** No minimum display time: storage readiness, never the animation, opens the library. */
@Composable
internal fun StorybookStartup(loading: Boolean, content: @Composable () -> Unit) {
    Crossfade(loading, animationSpec = tween(180), label = "openLibrary") { waiting ->
        if (waiting) StorybookOpening() else content()
    }
}

@Composable
private fun StorybookOpening() {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(1100)) }
    val breathing = rememberInfiniteTransition(label = "paperStar")
    val star by breathing.animateFloat(
        0.55f, 1f,
        infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "starOpacity",
    )
    StorybookPage(reveal.value, star)
}

@Composable
private fun StorybookPage(reveal: Float, star: Float) {
    BoxWithConstraints(
        Modifier.fillMaxSize().testTag("storybookOpening").drawWithCache {
            // Fixed seed and bounded geometry: no bitmap decode, per-frame noise or blur passes.
            val random = Random(24)
            val fibers = List(650) { Offset(random.nextFloat() * size.width, random.nextFloat() * size.height) }
            onDrawBehind {
                drawRect(Paper)
                fibers.forEachIndexed { index, point ->
                    drawLine(Ink.copy(alpha = 0.045f), point,
                        point + Offset((1 + index % 4) * density, density * 0.3f), density * 0.5f)
                }
                drawCircle(Rose.copy(alpha = 0.045f), size.minDimension * 0.46f,
                    Offset(size.width * 0.12f, size.height * 0.32f))
                drawCircle(Blue.copy(alpha = 0.045f), size.minDimension * 0.38f,
                    Offset(size.width * 0.9f, size.height * 0.7f))
            }
        }.safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        val compact = maxHeight < 440.dp
        Column(
            Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(Modifier.fillMaxWidth().height(if (compact) 95.dp else 150.dp)) {
                val unit = minOf(size.width / 300f, size.height / 150f)
                translate((size.width - 300f * unit) / 2, (size.height - 150f * unit) / 2) {
                    scale(unit, pivot = Offset.Zero) {
                        // An open book, with a stem growing from the fold.
                        val book = Path().apply {
                            moveTo(150f, 130f); cubicTo(125f, 110f, 88f, 110f, 57f, 118f)
                            lineTo(61f, 75f); cubicTo(94f, 65f, 127f, 77f, 150f, 93f)
                            cubicTo(175f, 77f, 207f, 66f, 239f, 75f)
                            lineTo(243f, 118f); cubicTo(209f, 110f, 179f, 111f, 150f, 130f)
                            lineTo(150f, 93f)
                        }
                        clipRect(right = 300f * reveal) {
                            drawPath(book, Sage, style = Stroke(1.5f))
                            for (line in 0..2) {
                                val y = 88f + line * 9f
                                val left = Path().apply {
                                    moveTo(76f, y); quadraticTo(107f, y - 1f, 136f, y + 14f)
                                }
                                val right = Path().apply {
                                    moveTo(164f, y + 14f); quadraticTo(193f, y - 1f, 224f, y)
                                }
                                drawPath(left, Sage.copy(alpha = 0.35f), style = Stroke(1f))
                                drawPath(right, Sage.copy(alpha = 0.35f), style = Stroke(1f))
                            }
                        }
                        clipRect(top = 110f * (1f - reveal)) {
                            val stem = Path().apply {
                                moveTo(150f, 94f); cubicTo(134f, 75f, 168f, 57f, 153f, 26f)
                            }
                            drawPath(stem, Sage, style = Stroke(1.4f))
                            val leaf = Path().apply {
                                moveTo(154f, 62f); cubicTo(169f, 40f, 184f, 46f, 181f, 48f)
                                cubicTo(179f, 63f, 165f, 67f, 154f, 62f)
                                moveTo(153f, 46f); cubicTo(133f, 46f, 130f, 29f, 131f, 28f)
                                cubicTo(148f, 28f, 155f, 34f, 153f, 46f)
                            }
                            drawPath(leaf, Sage.copy(alpha = 0.48f))
                        }
                        listOf(Offset(100f, 35f), Offset(206f, 44f), Offset(224f, 18f)).forEachIndexed { i, p ->
                            val r = if (i == 1) 6f else 3.5f
                            val sparkle = Path().apply {
                                moveTo(p.x, p.y - r); quadraticTo(p.x, p.y, p.x + r, p.y)
                                quadraticTo(p.x, p.y, p.x, p.y + r)
                                quadraticTo(p.x, p.y, p.x - r, p.y)
                                quadraticTo(p.x, p.y, p.x, p.y - r)
                            }
                            drawPath(sparkle, (if (i == 1) Rose else Blue).copy(alpha = reveal * star))
                        }
                    }
                }
            }
            Spacer(Modifier.height(if (compact) 10.dp else 22.dp))
            Text(
                "Tavern Player", fontFamily = FontFamily.Serif, fontSize = 32.sp,
                letterSpacing = 1.sp, color = Ink, textAlign = TextAlign.Center,
                modifier = Modifier.drawWithCache {
                    onDrawWithContent { clipRect(right = size.width * reveal) { this@onDrawWithContent.drawContent() } }
                },
            )
            Spacer(Modifier.height(14.dp))
            Text("每一次相遇，都是故事的第一页", color = Ink.copy(alpha = 0.75f),
                fontSize = 13.sp, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = reveal })
            Spacer(Modifier.height(if (compact) 20.dp else 40.dp))
            Text("正在翻开故事…", color = Ink, fontSize = 12.sp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun StorybookPagePreview() { StorybookPage(1f, 0.8f) }

@Preview(showBackground = true, widthDp = 780, heightDp = 360)
@Composable
private fun StorybookPageLandscapePreview() { StorybookPage(1f, 0.8f) }
