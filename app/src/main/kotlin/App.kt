package org.zender.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.launch
import org.zender.app.platform.FileKitFilePicker
import org.zender.app.ui.theme.EditorTheme
import org.zender.app.ui.theme.ThemeMode
import org.zender.app.ui.workspace.OpenProjectRegistry
import org.zender.app.ui.workspace.ProjectWindow
import org.zender.app.ui.workspace.RegisterOpenProject
import org.zender.app.ui.workspace.WorkspaceAuxiliaryWindows
import org.zender.app.ui.workspace.WorkspaceShell
import org.zender.app.workspace.WorkspaceController
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.log.Log
import org.zender.editor.settings.AppSettings
import org.zender.editor.settings.SettingsLocations
import org.zender.editor.settings.SettingsStore
import org.zender.editor.settings.WindowSettings
import java.nio.file.Path

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
    val openProjectRegistry = remember { OpenProjectRegistry() }
    val appScope = rememberCoroutineScope()
    val projectWindows = remember { mutableStateListOf<WorkspaceController>() }
    var showExitConfirm by remember { mutableStateOf(false) }

    fun openProjectInNewWindow(path: Path) {
        val windowController = WorkspaceController(settingsStore)
        projectWindows += windowController
        appScope.launch { windowController.loadProject(path) }
    }

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
        onPreviewKeyEvent = { event -> handleEditorShortcut(event, controller) },
    ) {
        // FileKit 的原生对话框以当前窗口为 parent，避免对话框出现在窗口后面。
        val filePicker = remember { FileKitFilePicker(window) }
        RegisterOpenProject(openProjectRegistry, "main", controller)
        EditorTheme(themeMode = ThemeMode.fromStorage(state.settings.themeMode)) {
            WorkspaceShell(
                controller = controller,
                filePicker = filePicker,
                onOpenProjectInNewWindow = ::openProjectInNewWindow,
                openProjects = openProjectRegistry.all,
                onActivateOpenProject = { path -> openProjectRegistry.focus(path) },
            )
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

    // 主窗口的已分离文档窗口与引用选择器窗口。
    WorkspaceAuxiliaryWindows(controller)

    // 独立工程窗口：每个控制器拥有自己的工程、文档会话与选择器。
    projectWindows.forEach { projectController ->
        key(projectController) {
            ProjectWindow(
                controller = projectController,
                registry = openProjectRegistry,
                windowKey = "project:${projectController.hashCode()}",
                onOpenProjectInNewWindow = ::openProjectInNewWindow,
                onClose = {
                    projectController.state.value.detachedWindows.toList().forEach { detached ->
                        projectController.closeDetachedWindow(detached.id)
                    }
                    projectController.persistLayout()
                    projectWindows.remove(projectController)
                },
            )
            WorkspaceAuxiliaryWindows(projectController)
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
