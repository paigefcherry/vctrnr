package app.vctrnr.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Always dark (the screen stays on while the app is open, and dark pixels are nearly free
 * on the S23's OLED), with colours taken from your wallpaper via One UI's colour palette.
 */
@Composable
fun VctrnrTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = dynamicDarkColorScheme(LocalContext.current), content = content)
}
