package org.zender.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import org.zender.app.workspace.WorkspaceController

/**
 * 应用级编辑快捷键。通过窗口的 preview 事件处理器分发，避免被输入框先消费。
 */
fun handleEditorShortcut(event: KeyEvent, controller: WorkspaceController): Boolean {
    if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return false
    val activeDocument = controller.state.value.session.activeDocumentId
    return when {
        event.key == Key.Z && event.isShiftPressed ->
            activeDocument != null && controller.redo(activeDocument)

        event.key == Key.Z ->
            activeDocument != null && controller.undo(activeDocument)

        event.key == Key.Y ->
            activeDocument != null && controller.redo(activeDocument)

        event.key == Key.S -> controller.saveProject()

        else -> false
    }
}
