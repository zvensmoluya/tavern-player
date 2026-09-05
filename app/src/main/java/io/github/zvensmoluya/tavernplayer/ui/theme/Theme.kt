package io.github.zvensmoluya.tavernplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TavernPlayerColors = lightColorScheme(
    primary = Color(0xFF28695F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEDE6),
    onPrimaryContainer = Color(0xFF173D35),
    secondary = Color(0xFF72634E),
    secondaryContainer = Color(0xFFF0E6D5),
    background = Color(0xFFF8F7F3),
    onBackground = Color(0xFF262C29),
    surface = Color(0xFFFFFEFA),
    onSurface = Color(0xFF262C29),
    surfaceVariant = Color(0xFFE9ECE5),
    onSurfaceVariant = Color(0xFF626A63),
    surfaceContainer = Color(0xFFF0F1EB),
    surfaceContainerLow = Color(0xFFF4F5EF),
    surfaceContainerHigh = Color(0xFFE8EBE3),
    surfaceContainerHighest = Color(0xFFE2E6DD),
    outline = Color(0xFF868D84),
    outlineVariant = Color(0xFFDCE1D6),
)

@Composable
fun TavernPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TavernPlayerColors,
        content = content,
    )
}
