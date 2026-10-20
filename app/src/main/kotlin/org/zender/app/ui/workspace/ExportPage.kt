package org.zender.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.zender.app.platform.FilePicker
import org.zender.app.workspace.WorkspaceController
import org.zender.app.workspace.WorkspaceUiState
import org.zender.editor.project.ValidationSeverity
import java.nio.file.Path

@Composable
fun ExportPage(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    filePicker: FilePicker,
) {
    val project = state.project
    if (project == null) {
        EmptyHint(
            title = "尚未打开工程",
            hint = "导出前需要先打开并校验 Mod 工程。",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    val scope = rememberCoroutineScope()
    var targetDir by remember { mutableStateOf<Path?>(null) }
    val invalidRaw = remember(state.rawDocuments, state.targets) {
        state.rawDocuments.count { (id, raw) ->
            state.targets[id]?.readOnly == false && !raw.isValid
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("导出 / 另存为目录", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "把当前工程按目标框架写到一个新目录。导出前必须通过完整校验；" +
                "当前版本不执行 ML ↔ MC 转换（H 区）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle("目标")
        KeyValue("框架", project.framework.name)
        KeyValue("元数据", project.format.metadataFileName)
        KeyValue("Namespace", project.namespace)
        KeyValue("对象 / 补丁 / 资源", "${project.workingTree.objectCount} / " +
            "${project.workingTree.gameSourceModifies.size + project.workingTree.dataObjectModifies.size} / " +
            "${project.workingTree.resourceCount}")
        KeyValue("目标目录", targetDir?.toString() ?: "尚未选择", mono = true)
        if (invalidRaw > 0) {
            StatusChip("有 $invalidRaw 个原始 JSON 文件存在未修复错误", error = true)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    runCatching { filePicker.pickDirectory(title = "选择导出目录") }
                        .getOrNull()
                        ?.let { targetDir = it }
                }
            }) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("选择目标目录")
            }
            OutlinedButton(onClick = { controller.validateProject() }) {
                Text("运行校验")
            }
            Button(
                enabled = targetDir != null && invalidRaw == 0,
                onClick = {
                    val target = targetDir ?: return@Button
                    scope.launch { controller.exportTo(target) }
                },
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("导出")
            }
            OutlinedButton(onClick = { controller.saveProject() }) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("保存到工程目录")
            }
        }

        SectionTitle("校验结果")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("错误 ${state.errorCount}", error = state.errorCount > 0)
            StatusChip("警告 ${state.warningCount}", warning = state.warningCount > 0)
            StatusChip("信息 ${state.validation.size - state.errorCount - state.warningCount}")
        }
        if (state.validation.isEmpty()) {
            Text(
                text = "尚未校验。保存或导出前会再次执行完整校验。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.validation.take(60).forEach { issue ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        Text(issue.message, style = MaterialTheme.typography.bodySmall)
                        issue.path?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (state.validation.size > 60) {
                Text(
                    text = "还有 ${state.validation.size - 60} 条问题。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionTitle("当前状态")
        Text(
            text = state.status,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (state.projectRoot != null) {
            TextButton(onClick = { controller.setScreen(org.zender.app.workspace.WorkspaceScreen.FILES) }) {
                Text("返回文件视图")
            }
        }
    }
}
