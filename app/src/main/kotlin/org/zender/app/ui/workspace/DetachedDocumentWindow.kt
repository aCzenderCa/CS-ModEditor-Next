package org.zender.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import org.zender.app.workspace.WorkspaceController
import org.zender.editor.document.DocumentId
import org.zender.editor.window.DocumentViewMode

/**
 * 已分离窗口的内容：复用主窗口的文档头、字段/原始/差异视图与同一份会话。
 */
@Composable
fun DetachedDocumentWindow(
    controller: WorkspaceController,
    documentId: DocumentId,
) {
    val state by controller.state.collectAsState()
    val target = state.targets[documentId]

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (target == null) {
            EmptyHint(
                title = "文档已关闭",
                hint = "可以关闭这个窗口；主窗口中的文件 Tab 不受影响。",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }
        DocumentHeader(controller, state, documentId, target)
        HorizontalSeparator()
        Box(modifier = Modifier.fillMaxSize()) {
            when (state.viewModes[documentId] ?: DocumentViewMode.FIELD) {
                DocumentViewMode.FIELD -> FieldEditorView(controller, state, documentId, target)
                DocumentViewMode.RAW -> RawJsonEditorView(controller, state, documentId, target)
                DocumentViewMode.DIFF -> DiffView(state, documentId)
            }
        }
    }
}
