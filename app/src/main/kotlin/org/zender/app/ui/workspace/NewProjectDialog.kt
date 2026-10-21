package org.zender.app.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.zender.app.platform.FilePicker
import org.zender.editor.framework.FrameworkId
import org.zender.editor.framework.ModNamespace
import java.nio.file.Path

data class NewProjectForm(
    val root: Path,
    val framework: FrameworkId,
    val name: String,
    val namespace: String?,
    val author: String,
    val version: String,
)

/**
 * 新建 Mod 工程：选择框架与空目录，写入 `ModInfo.json` / `ModMeta.json`。
 */
@Composable
fun NewProjectDialog(
    filePicker: FilePicker,
    onDismiss: () -> Unit,
    onCreate: (NewProjectForm) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var framework by remember { mutableStateOf(FrameworkId.MC) }
    var name by remember { mutableStateOf("NewMod") }
    var namespace by remember { mutableStateOf("") }
    var namespaceEdited by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("1.0.0") }

    val effectiveNamespace = when (framework) {
        FrameworkId.ML -> name.trim()
        FrameworkId.MC -> if (namespaceEdited) namespace.trim() else name.trim()
    }
    val targetPath = runCatching { Path.of(target.trim()) }.getOrNull()
    val valid = name.isNotBlank() &&
        target.isNotBlank() &&
        targetPath != null &&
        (framework != FrameworkId.MC || ModNamespace.isValid(effectiveNamespace))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建 Mod 工程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (framework == FrameworkId.MC) {
                        OutlinedButton(onClick = {}) { Text("ModCore (MC)") }
                    } else {
                        TextButton(onClick = { framework = FrameworkId.MC }) {
                            Text("ModCore (MC)")
                        }
                    }
                    if (framework == FrameworkId.ML) {
                        OutlinedButton(onClick = {}) { Text("ModLoader (ML)") }
                    } else {
                        TextButton(onClick = { framework = FrameworkId.ML }) {
                            Text("ModLoader (ML)")
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    label = { Text("Mod 名称") },
                )
                if (framework == FrameworkId.MC) {
                    OutlinedTextField(
                        value = effectiveNamespace,
                        onValueChange = {
                            namespace = it
                            namespaceEdited = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        label = { Text("Namespace") },
                        isError = !ModNamespace.isValid(effectiveNamespace),
                        supportingText = {
                            Text(
                                text = "不能为空，且不能包含 : @ |",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                } else {
                    Text(
                        text = "ML 使用 Mod 名称作为前缀（类似 Namespace）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    label = { Text("工程目录（必须为空或尚不存在）") },
                    trailingIcon = {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        filePicker.pickDirectory(title = "选择新工程目录")
                                    }.getOrNull()?.let { target = it.toString() }
                                }
                            },
                        ) { Text("选择…") }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = author,
                        onValueChange = { author = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        label = { Text("作者") },
                    )
                    OutlinedTextField(
                        value = version,
                        onValueChange = { version = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        label = { Text("版本") },
                    )
                }
                if (targetPath == null && target.isNotBlank()) {
                    Text(
                        text = "目录路径不合法",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onCreate(
                        NewProjectForm(
                            root = targetPath ?: return@TextButton,
                            framework = framework,
                            name = name.trim(),
                            namespace = if (framework == FrameworkId.MC) {
                                effectiveNamespace
                            } else {
                                null
                            },
                            author = author.trim(),
                            version = version.trim(),
                        ),
                    )
                },
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
