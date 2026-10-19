package org.zender.app

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.zender.app.platform.FileKitFilePicker
import org.zender.app.ui.theme.EditorTheme
import org.zender.app.ui.workspace.WorkspaceShell
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.log.Log
import org.zender.editor.settings.AppSettings
import org.zender.editor.settings.SettingsLocations
import org.zender.editor.settings.SettingsStore
import org.zender.editor.settings.WindowSettings

fun main() = application {
    val settingsStore = remember {
        SettingsStore(AtomicFileStore(), SettingsLocations.defaultSettingsFile())
    }
    val startSettings = remember { settingsStore.load() }
    val windowState = rememberWindowState(
        placement = if (startSettings.window.maximized) {
            WindowPlacement.Maximized
        } else {
            WindowPlacement.Floating
        },
        position = startSettings.window.toWindowPosition(),
        size = DpSize(
            width = startSettings.window.width.coerceAtLeast(MIN_WINDOW_WIDTH).dp,
            height = startSettings.window.height.coerceAtLeast(MIN_WINDOW_HEIGHT).dp,
        ),
    )

    Window(
        onCloseRequest = {
            runCatching { settingsStore.save(startSettings.withWindow(windowState)) }
                .onFailure { Log.getLogger("app").warn("保存窗口设置失败", it) }
            exitApplication()
        },
        state = windowState,
        title = "CS-ModEditor-Next",
    ) {
        // FileKit 的原生对话框以当前窗口为 parent，避免对话框出现在窗口后面。
        val filePicker = remember { FileKitFilePicker(window) }
        EditorTheme {
            WorkspaceShell(filePicker = filePicker)
        }
    }
}

private const val MIN_WINDOW_WIDTH = 960f
private const val MIN_WINDOW_HEIGHT = 640f

private fun WindowSettings.toWindowPosition(): WindowPosition {
    val x = this.x
    val y = this.y
    return if (x != null && y != null) {
        WindowPosition.Absolute(x.dp, y.dp)
    } else {
        WindowPosition.PlatformDefault
    }
}

private fun AppSettings.withWindow(state: WindowState): AppSettings {
    val absolute = state.position as? WindowPosition.Absolute
    val width = state.size.width.value.takeIf { it.isFinite() && it > 0f } ?: window.width
    val height = state.size.height.value.takeIf { it.isFinite() && it > 0f } ?: window.height
    return copy(
        window = window.copy(
            x = absolute?.x?.value ?: window.x,
            y = absolute?.y?.value ?: window.y,
            width = width,
            height = height,
            maximized = state.placement == WindowPlacement.Maximized,
        ),
    )
}
