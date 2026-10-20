package org.zender.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.zender.app.workspace.DocumentTarget
import org.zender.app.workspace.WorkspaceController
import org.zender.app.workspace.WorkspaceUiState
import org.zender.editor.document.DocumentId
import org.zender.editor.window.DocumentViewMode

@Composable
fun FileTabBar(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    onCloseRequest: (DocumentId) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.session.documentOrder.isEmpty()) {
                Text(
                    text = if (state.project == null) "未打开文件" else "从左侧文件树打开对象或补丁",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            state.session.documentOrder.forEach { id ->
                val document = state.session.documents[id] ?: return@forEach
                val active = state.session.activeDocumentId == id
                Row(
                    modifier = Modifier
                        .height(38.dp)
                        .background(
                            if (active) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            else Color.Transparent,
                        )
                        .clickable { controller.activateDocument(id) }
                        .padding(start = 10.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = document.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(150.dp),
                    )
                    if (document.isDirty) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.small),
                        )
                    }
                    IconButton(
                        onClick = { onCloseRequest(id) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                HorizontalSeparator()
            }
        }
        val activeId = state.session.activeDocumentId
        if (activeId != null) {
            IconButton(
                onClick = { controller.openInNewWindow(activeId) },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = "在新窗口打开",
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

@Composable
fun EditorArea(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    onOpenProject: () -> Unit,
    onChoosePack: () -> Unit,
) {
    val activeId = state.session.activeDocumentId
    val target = state.activeTarget
    if (activeId == null || target == null) {
        EmptyWorkspace(
            state = state,
            onOpenProject = onOpenProject,
            onChoosePack = onChoosePack,
        )
        return
    }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        DocumentHeader(controller, state, activeId, target)
        HorizontalSeparator()
        Box(modifier = Modifier.fillMaxSize()) {
            when (state.viewModes[activeId] ?: DocumentViewMode.FIELD) {
                DocumentViewMode.FIELD -> FieldEditorView(controller, state, activeId, target)
                DocumentViewMode.RAW -> RawJsonEditorView(controller, state, activeId, target)
                DocumentViewMode.DIFF -> DiffView(state, activeId)
            }
        }
    }
}

@Composable
fun DocumentHeader(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    documentId: DocumentId,
    target: DocumentTarget,
) {
    val document = state.session.documents[documentId] ?: return
    val mode = state.viewModes[documentId] ?: DocumentViewMode.FIELD
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ViewModeButton("字段", mode == DocumentViewMode.FIELD) {
            controller.setViewMode(documentId, DocumentViewMode.FIELD)
        }
        ViewModeButton("原始", mode == DocumentViewMode.RAW) {
            controller.setViewMode(documentId, DocumentViewMode.RAW)
        }
        ViewModeButton("差异", mode == DocumentViewMode.DIFF) {
            controller.setViewMode(documentId, DocumentViewMode.DIFF)
        }
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(
            onClick = { controller.undo(documentId) },
            enabled = document.canUndo,
            modifier = Modifier.size(30.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "撤销", modifier = Modifier.size(16.dp))
        }
        IconButton(
            onClick = { controller.redo(documentId) },
            enabled = document.canRedo,
            modifier = Modifier.size(30.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "重做", modifier = Modifier.size(16.dp))
        }
        Spacer(modifier = Modifier.weight(1f))
        if (target.readOnly) {
            StatusChip("数据包只读")
        } else if (document.isDirty) {
            StatusChip("未保存", warning = true)
        }
        Text(
            text = target.relativePath,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(260.dp),
        )
        IconButton(
            onClick = { controller.saveDocument(documentId) },
            enabled = !target.readOnly,
            modifier = Modifier.size(30.dp),
        ) {
            Icon(Icons.Outlined.Save, contentDescription = "保存", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ViewModeButton(text: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(30.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyWorkspace(
    state: WorkspaceUiState,
    onOpenProject: () -> Unit,
    onChoosePack: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = if (state.project == null) {
                    Icons.Outlined.FolderOpen
                } else {
                    Icons.AutoMirrored.Outlined.InsertDriveFile
                },
                contentDescription = null,
                modifier = Modifier.size(54.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (state.project == null) "尚未打开 Mod 工程" else "工程已就绪",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = if (state.project == null) {
                    "打开 ML / MC 工程目录；选择数据包后可启用 Schema 字段编辑。"
                } else {
                    "${state.projectName} · ${state.project.framework} · 从左侧选择对象、补丁或本地化。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onOpenProject) { Text("打开工程目录") }
                OutlinedButton(onClick = onChoosePack) { Text("选择游戏数据包") }
            }
        }
    }
}
