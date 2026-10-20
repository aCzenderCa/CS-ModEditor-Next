package org.zender.app.ui.workspace

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import org.zender.app.handleEditorShortcut
import org.zender.app.platform.FileKitFilePicker
import org.zender.app.ui.theme.EditorTheme
import org.zender.app.ui.theme.ThemeMode
import org.zender.app.workspace.PickerWindowKind
import org.zender.app.workspace.WorkspaceController
import org.zender.editor.window.WindowGeometry
import java.nio.file.Path

/**
 * 一个独立工程窗口。持有自己的 WorkspaceController / EditorSession。
 */
@Composable
fun ProjectWindow(
    controller: WorkspaceController,
    registry: OpenProjectRegistry,
    windowKey: String,
    onOpenProjectInNewWindow: (Path) -> Unit,
    onClose: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val windowState = rememberWindowState(
        size = DpSize(PROJECT_WINDOW_WIDTH.dp, PROJECT_WINDOW_HEIGHT.dp),
    )
    var showExitConfirm by remember { mutableStateOf(false) }

    Window(
        onCloseRequest = {
            if (state.session.hasUnsavedChanges) {
                showExitConfirm = true
            } else {
                onClose()
            }
        },
        state = windowState,
        title = buildString {
            append("CS-ModEditor-Next")
            state.projectName.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        },
        onPreviewKeyEvent = { event -> handleEditorShortcut(event, controller) },
    ) {
        val filePicker = remember { FileKitFilePicker(window) }
        RegisterOpenProject(registry, windowKey, controller)
        EditorTheme(themeMode = ThemeMode.fromStorage(state.settings.themeMode)) {
            WorkspaceShell(
                controller = controller,
                filePicker = filePicker,
                onOpenProjectInNewWindow = onOpenProjectInNewWindow,
                openProjects = registry.all,
                onActivateOpenProject = { path -> registry.focus(path) },
            )
            if (showExitConfirm) {
                AlertDialog(
                    onDismissRequest = { showExitConfirm = false },
                    title = { Text("有未保存的修改") },
                    text = { Text("关闭这个工程窗口前保存当前工程吗？") },
                    confirmButton = {
                        TextButton(onClick = {
                            if (controller.saveProject()) {
                                showExitConfirm = false
                                onClose()
                            } else {
                                showExitConfirm = false
                            }
                        }) { Text("保存并关闭") }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = {
                                showExitConfirm = false
                                onClose()
                            }) { Text("放弃修改") }
                            TextButton(onClick = { showExitConfirm = false }) { Text("取消") }
                        }
                    },
                )
            }
        }
    }
}

/**
 * 某个工程控制器所属的已分离文档窗口与选择器窗口。
 *
 * 在主窗口和每个独立工程窗口中分别调用；窗口本身仍然注册在 application 级。
 */
@Composable
fun WorkspaceAuxiliaryWindows(controller: WorkspaceController) {
    val state by controller.state.collectAsState()

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
                onPreviewKeyEvent = { event -> handleEditorShortcut(event, controller) },
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
private const val PROJECT_WINDOW_WIDTH = 1440f
private const val PROJECT_WINDOW_HEIGHT = 900f

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
    val width = size.width.value.takeIf { it.isFinite() && it > 0f } ?: PROJECT_WINDOW_WIDTH
    val height = size.height.value.takeIf { it.isFinite() && it > 0f } ?: PROJECT_WINDOW_HEIGHT
    return WindowGeometry(
        x = absolute?.x?.value,
        y = absolute?.y?.value,
        width = width,
        height = height,
        maximized = placement == WindowPlacement.Maximized,
    )
}
