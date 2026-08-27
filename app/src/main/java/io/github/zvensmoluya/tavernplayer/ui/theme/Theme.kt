package io.github.zvensmoluya.tavernplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val TavernPlayerColors = lightColorScheme()

@Composable
fun TavernPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TavernPlayerColors,
        content = content,
    )
}
