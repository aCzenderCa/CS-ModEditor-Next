package org.zender.app.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.window.WindowScope
import org.zender.app.workspace.WorkspaceController
import java.awt.Frame

/** 一个已打开项目的顶级窗口。 */
data class OpenProjectInfo(
    val key: String,
    /** 规范化后的工程目录。 */
    val path: String,
    val title: String,
)

/**
 * 应用级「已打开项目」注册表。
 *
 * 主窗口与每个独立工程窗口在加载工程后登记自己；最近项目菜单据此显示“已打开”，
 * 选择已打开项目时只把对应顶级窗口带到前台，不重新读取工程。
 */
class OpenProjectRegistry {

    private val entries = mutableStateMapOf<String, OpenProjectInfo>()
    private val focusActions = mutableMapOf<String, () -> Unit>()

    val all: List<OpenProjectInfo>
        get() = entries.values.toList()

    fun update(key: String, path: String, title: String, focus: () -> Unit) {
        entries[key] = OpenProjectInfo(key, path, title)
        focusActions[key] = focus
    }

    fun remove(key: String) {
        entries.remove(key)
        focusActions.remove(key)
    }

    /** 按规范化工程路径查找并激活窗口。 */
    fun focus(path: String): Boolean {
        val entry = entries.values.firstOrNull { it.path == path } ?: return false
        focusActions[entry.key]?.invoke()
        return true
    }
}

/**
 * 在 Window 内容中登记当前工程。工程切换时更新路径与标题，窗口销毁时自动移除。
 */
@Composable
fun WindowScope.RegisterOpenProject(
    registry: OpenProjectRegistry,
    key: String,
    controller: WorkspaceController,
) {
    val state by controller.state.collectAsState()
    LaunchedEffect(key, state.projectRoot, state.projectName) {
        val root = state.projectRoot
        if (root == null) {
            registry.remove(key)
        } else {
            registry.update(
                key = key,
                path = root.toString(),
                title = state.projectName.ifBlank { root.fileName?.toString().orEmpty() },
            ) {
                val currentWindow = window
                if (currentWindow is Frame) {
                    currentWindow.extendedState =
                        currentWindow.extendedState and Frame.ICONIFIED.inv()
                }
                currentWindow.isVisible = true
                currentWindow.toFront()
                currentWindow.requestFocus()
            }
        }
    }
    DisposableEffect(key) {
        onDispose { registry.remove(key) }
    }
}
