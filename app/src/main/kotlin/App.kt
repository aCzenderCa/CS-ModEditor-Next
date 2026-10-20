package org.zender.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import org.zender.app.platform.FileKitFilePicker
import org.zender.app.ui.theme.EditorTheme
import org.zender.app.ui.theme.ThemeMode
import org.zender.app.ui.workspace.DetachedDocumentWindow
import org.zender.app.ui.workspace.PickerWindowContent
import org.zender.app.ui.workspace.WorkspaceShell
import org.zender.app.workspace.PickerWindowKind
import org.zender.app.workspace.WorkspaceController
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.log.Log
import org.zender.editor.settings.AppSettings
import org.zender.editor.settings.SettingsLocations
import org.zender.editor.settings.SettingsStore
import org.zender.editor.settings.WindowSettings
import org.zender.editor.window.WindowGeometry

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
    val controller = remember { WorkspaceController(settingsStore) }
    val state by controller.state.collectAsState()
    var showExitConfirm by remember { mutableStateOf(false) }

    fun persistLayout() {
        runCatching {
            settingsStore.update { it.withWindow(windowState) }
        }.onFailure { Log.getLogger("app").warn("保存窗口设置失败", it) }
        controller.persistLayout()
    }

    fun exit() {
        persistLayout()
        exitApplication()
    }

    Window(
        onCloseRequest = {
            if (state.session.hasUnsavedChanges) {
                showExitConfirm = true
            } else {
                exit()
            }
        },
        state = windowState,
        title = buildString {
            append("CS-ModEditor-Next")
            state.projectName.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        },
    ) {
        // FileKit 的原生对话框以当前窗口为 parent，避免对话框出现在窗口后面。
        val filePicker = remember { FileKitFilePicker(window) }
        EditorTheme(themeMode = ThemeMode.fromStorage(state.settings.themeMode)) {
            WorkspaceShell(controller = controller, filePicker = filePicker)
            if (showExitConfirm) {
                AlertDialog(
                    onDismissRequest = { showExitConfirm = false },
                    title = { Text("有未保存的修改") },
                    text = { Text("关闭前保存当前工程吗？关闭窗口后撤销历史将丢失。") },
                    confirmButton = {
                        TextButton(onClick = {
                            if (controller.saveProject()) {
                                showExitConfirm = false
                                exit()
                            } else {
                                showExitConfirm = false
                            }
                        }) { Text("保存并退出") }
                    },
                    dismissButton = {
                        androidx.compose.foundation.layout.Row {
                            TextButton(onClick = {
                                showExitConfirm = false
                                exit()
                            }) { Text("放弃修改") }
                            TextButton(onClick = { showExitConfirm = false }) { Text("取消") }
                        }
                    },
                )
            }
        }
    }

    // 已分离的编辑窗口：共享同一会话与命令栈。
    state.detachedWindows.forEach { detached ->
        key(detached.id.value) {
            val detachedState = rememberWindowState(
                placement = if (detached.geometry.maximized) {
                    WindowPlacement.Maximized
                } else {
                    WindowPlacement.Floating
                },
                position = detached.geometry.toWindowPosition(),
                size = DpSize(
                    width = detached.geometry.width.coerceAtLeast(MIN_WINDOW_WIDTH).dp,
                    height = detached.geometry.height.coerceAtLeast(MIN_WINDOW_HEIGHT).dp,
                ),
            )
            Window(
                onCloseRequest = { controller.closeDetachedWindow(detached.id) },
                state = detachedState,
                title = detached.title,
            ) {
                EditorTheme(themeMode = ThemeMode.fromStorage(state.settings.themeMode)) {
                    DetachedDocumentWindow(controller, detached.documentId)
                }
                LaunchedEffect(
                    detachedState.position,
                    detachedState.size,
                    detachedState.placement,
                ) {
                    controller.updateDetachedGeometry(
                        detached.id,
                        detachedState.toGeometry(),
                    )
                }
            }
        }
    }

    // 对象/资源引用选择器：独立 Compose 窗口，提交到发起请求的字段。
    state.picker?.let { request ->
        key(request) {
            val dialogState = rememberDialogState(size = DpSize(980.dp, 640.dp))
            DialogWindow(
                onCloseRequest = { controller.closePicker() },
                state = dialogState,
                title = when (request.kind) {
                    PickerWindowKind.OBJECT -> "选择对象引用 · ${request.fieldType}"
                    PickerWindowKind.RESOURCE -> "选择资源引用 · ${request.fieldType}"
                },
            ) {
                EditorTheme(themeMode = ThemeMode.fromStorage(state.settings.themeMode)) {
                    PickerWindowContent(controller, request)
                }
            }
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

private fun WindowGeometry.toWindowPosition(): WindowPosition {
    val x = this.x
    val y = this.y
    return if (x != null && y != null) {
        WindowPosition.Absolute(x.dp, y.dp)
    } else {
        WindowPosition.PlatformDefault
    }
}

private fun WindowState.toGeometry(): WindowGeometry {
    val absolute = position as? WindowPosition.Absolute
    val width = size.width.value.takeIf { it.isFinite() && it > 0f } ?: 1200f
    val height = size.height.value.takeIf { it.isFinite() && it > 0f } ?: 800f
    return WindowGeometry(
        x = absolute?.x?.value,
        y = absolute?.y?.value,
        width = width,
        height = height,
        maximized = placement == WindowPlacement.Maximized,
    )
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
