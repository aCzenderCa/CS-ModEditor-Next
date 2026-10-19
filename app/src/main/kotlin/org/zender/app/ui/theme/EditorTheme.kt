package org.zender.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6F8F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC7E7F7),
    onPrimaryContainer = Color(0xFF00344A),
    secondary = Color(0xFF4F6354),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2E8D6),
    onSecondaryContainer = Color(0xFF0D1F14),
    surface = Color(0xFFF8FAFB),
    surfaceVariant = Color(0xFFDDE3E7),
    background = Color(0xFFF8FAFB),
    outlineVariant = Color(0xFFC1C8CC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CCFF0),
    onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF0B4C66),
    onPrimaryContainer = Color(0xFFC7E7F7),
    secondary = Color(0xFFB6CCBA),
    onSecondary = Color(0xFF223528),
    secondaryContainer = Color(0xFF384B3D),
    onSecondaryContainer = Color(0xFFD2E8D6),
    surface = Color(0xFF14191C),
    surfaceVariant = Color(0xFF3F484D),
    background = Color(0xFF14191C),
    outlineVariant = Color(0xFF3F484D),
)

/**
 * 编辑器主题。窗口内所有 Compose 内容都应包在该主题下。
 */
@Composable
fun EditorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
