package org.zender.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.zender.app.workspace.DocumentTarget
import org.zender.app.workspace.WorkspaceController
import org.zender.app.workspace.WorkspaceUiState
import org.zender.editor.project.ValidationSeverity
import org.zender.editor.selection.FieldRef

@Composable
fun InspectorPane(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    modifier: Modifier = Modifier,
) {
    val activeId = state.session.activeDocumentId
    val active = state.activeDocument
    val target = state.activeTarget
    val selectedField = state.session.selection.currentField
    val fieldInfo = selectedField?.let { controller.fieldInfo(it.documentId, it.path) }
    val reference = if (fieldInfo?.isReference == true) {
        controller.referenceDisplay(
            selectedField.documentId,
            selectedField.path,
            fieldInfo.typeName ?: fieldInfo.schemaField.typeName,
        )
    } else {
        null
    }
    val issues = remember(state.validation, target) {
        if (target == null) {
            state.validation
        } else {
            state.validation.filter { issue ->
                val path = issue.path ?: return@filter false
                path == target.relativePath || path.startsWith(target.relativePath + ":")
            }
        }
    }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        PaneHeader(icon = Icons.Outlined.Tune, title = "检查器")
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            SectionTitle("当前文档")
            if (active == null || target == null || activeId == null) {
                Text(
                    text = "选择文件后显示对象、字段与校验信息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                KeyValue("标题", active.title)
                KeyValue("来源", targetLabel(target))
                KeyValue("相对路径", target.relativePath, mono = true)
                KeyValue("视图", state.viewModes[activeId]?.name ?: "FIELD")
                KeyValue(
                    "状态",
                    when {
                        active.isDirty -> "有未保存修改"
                        target.readOnly -> "数据包只读"
                        else -> "已保存"
                    },
                )
                if (active.canUndo || active.canRedo) {
                    KeyValue(
                        "历史",
                        buildString {
                            if (active.canUndo) append("撤销：${active.undoLabel}")
                            if (active.canUndo && active.canRedo) append(" · ")
                            if (active.canRedo) append("重做：${active.redoLabel}")
                        },
                    )
                }
            }

            SectionTitle("当前字段")
            if (selectedField == null) {
                Text(
                    text = "点击字段行查看类型、默认值与引用解析结果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                KeyValue("字段路径", selectedField.path.format(), mono = true)
                KeyValue("类型", fieldInfo?.let { "${it.kind} · ${it.typeName ?: "未知"}" } ?: "未知")
                fieldInfo?.schemaField?.displayName()?.let { display ->
                    if (display != selectedField.path.format()) KeyValue("展示名", display)
                }
                fieldInfo?.schemaField?.note()?.takeIf { it.isNotBlank() }?.let {
                    KeyValue("说明", it)
                }
                if (fieldInfo?.isReadOnly == true) {
                    StatusChip("只读字段", warning = true)
                }
                if (reference != null) {
                    KeyValue("引用键", reference.key ?: "（未设置）", mono = true)
                    if (state.settings.showWarpType) {
                        KeyValue("WarpType", reference.warpType?.let { "${it.value} ${it.name}" } ?: "缺失")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusChip(
                            text = reference.status,
                            warning = reference.warning,
                            error = !reference.resolved && reference.key != null && !reference.warning,
                        )
                    }
                }
            }

            SectionTitle("校验问题（${issues.size}）")
            if (issues.isEmpty()) {
                Text(
                    text = "没有与当前文档相关的问题。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                issues.take(40).forEach { issue ->
                    IssueRow(issue) { controller.openValidationIssue(issue) }
                }
                if (issues.size > 40) {
                    Text(
                        text = "还有 ${issues.size - 40} 条，请运行工程校验查看完整结果。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionTitle("工程")
            val project = state.project
            if (project == null) {
                Text(
                    text = "尚未打开工程。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                KeyValue("框架", project.framework.name)
                KeyValue("Namespace", project.namespace)
                KeyValue("对象", "${project.workingTree.objectCount}")
                KeyValue("补丁", "${project.workingTree.gameSourceModifies.size} GSM / ${project.workingTree.dataObjectModifies.size} DOM")
                KeyValue("资源", "${project.workingTree.resourceCount}")
                KeyValue("本地化 Key", "${project.workingTree.localization.table.size}")
                TextButton(onClick = { controller.validateProject() }) {
                    Text("运行完整校验")
                }
            }

            SectionTitle("数据包")
            val pack = state.pack
            if (pack == null) {
                Text(
                    text = "尚未加载数据包；字段视图不可用，只能编辑原始 JSON。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                KeyValue("名称", pack.info.name)
                KeyValue("版本", pack.info.version)
                KeyValue("类型", "${pack.typeNames.size}")
                KeyValue("对象", "${pack.objectIndex.entries.size}")
                KeyValue("诊断", "${state.diagnostics.size}")
            }
        }
    }
}

@Composable
private fun IssueRow(
    issue: org.zender.editor.project.ValidationIssue,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = issue.path != null, onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusChip(
            text = when (issue.severity) {
                ValidationSeverity.ERROR -> "错误"
                ValidationSeverity.WARNING -> "警告"
                ValidationSeverity.INFO -> "提示"
            },
            warning = issue.severity == ValidationSeverity.WARNING,
            error = issue.severity == ValidationSeverity.ERROR,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = issue.message,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            issue.path?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = issue.code,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private fun targetLabel(target: DocumentTarget): String = when (target) {
    is DocumentTarget.ProjectObjectTarget -> "工程对象 · ${target.type}"
    is DocumentTarget.GameSourceModifyTarget -> "GameSourceModify · ${target.schemaType ?: "未知类型"}"
    is DocumentTarget.DataObjectModifyTarget -> "DataObjectModify · ${target.type}"
    is DocumentTarget.PackObjectTarget -> "数据包对象（只读） · ${target.type}"
}
