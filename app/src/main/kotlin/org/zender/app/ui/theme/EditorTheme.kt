package org.zender.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDDE3E7),
    onSurfaceVariant = Color(0xFF44474A),
    background = Color(0xFFF8FAFB),
    onBackground = Color(0xFF1A1C1E),
    outline = Color(0xFF74777A),
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
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF3F484D),
    onSurfaceVariant = Color(0xFFC1C7CC),
    background = Color(0xFF14191C),
    onBackground = Color(0xFFE6E1E5),
    outline = Color(0xFF8F979D),
    outlineVariant = Color(0xFF3F484D),
)

/**
 * 主题模式。持久化为设置中的字符串，避免序列化枚举带来的兼容问题。
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM

        fun resolvedDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
            SYSTEM -> systemDark
            LIGHT -> false
            DARK -> true
        }
    }
}

/**
 * 编辑器主题。窗口内所有 Compose 内容都应包在该主题下。
 *
 * 根节点使用 [Surface] 提供 `LocalContentColor`，否则未显式指定颜色的 `Text`
 * 会一直使用默认的黑色，导致深色主题下文字不可见。
 */
@Composable
fun EditorTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = ThemeMode.resolvedDark(themeMode, isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            content()
        }
    }
}
