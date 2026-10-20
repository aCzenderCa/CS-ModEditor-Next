package org.zender.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.zender.app.platform.FilePicker
import org.zender.app.workspace.WorkspaceController
import org.zender.app.workspace.WorkspaceUiState
import org.zender.editor.project.ProjectResource
import org.zender.editor.resource.ResourceEntry
import org.zender.editor.resource.ResourceInventoryScanner
import org.zender.editor.resource.ResourceKind
import java.nio.file.Path

private sealed interface ResourceSelection {
    data class Project(val resource: ProjectResource) : ResourceSelection
    data class Pack(val entry: ResourceEntry) : ResourceSelection
}

@Composable
fun ResourcePage(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    filePicker: FilePicker,
) {
    val project = state.project
    if (project == null) {
        EmptyHint(
            title = "尚未打开工程",
            hint = "打开工程后可以预览、导入和管理资源。",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    var kindFilter by remember { mutableStateOf<ResourceKind?>(null) }
    var kindMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ResourceSelection?>(null) }
    var pendingOverwrite by remember { mutableStateOf<Path?>(null) }

    val projectResources = remember(project.workingTree.resources, search, kindFilter) {
        project.workingTree.resources.values
            .filter { kindFilter == null || it.kind == kindFilter }
            .filter { it.key.contains(search, ignoreCase = true) }
            .sortedWith(compareBy({ it.kind }, { it.key }))
    }
    val packResources = remember(state.pack, search, kindFilter) {
        state.pack?.resources?.entries.orEmpty()
            .filter { kindFilter == null || it.kind == kindFilter }
            .filter { it.key.contains(search, ignoreCase = true) }
            .sortedWith(compareBy({ it.kind }, { it.key }))
    }

    fun import(source: Path, overwrite: Boolean) {
        scope.launch {
            val extension = source.fileName?.toString()?.substringAfterLast('.', "")?.lowercase().orEmpty()
            val kind = when (extension) {
                in ResourceInventoryScanner.IMAGE_EXTENSIONS -> ResourceKind.IMAGE
                in ResourceInventoryScanner.AUDIO_EXTENSIONS -> ResourceKind.AUDIO
                else -> null
            }
            val key = source.fileName?.toString()?.substringBeforeLast('.', "").orEmpty()
            if (!overwrite && kind != null && project.workingTree.resource(kind, key) != null) {
                pendingOverwrite = source
                return@launch
            }
            controller.importResource(source, overwrite = overwrite)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.width(280.dp).height(42.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                placeholder = { Text("搜索资源键", style = MaterialTheme.typography.bodySmall) },
            )
            Box {
                TextButton(onClick = { kindMenu = true }) {
                    Text(
                        text = when (kindFilter) {
                            null -> "全部资源"
                            ResourceKind.IMAGE -> "图片"
                            ResourceKind.AUDIO -> "音频"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                DropdownMenu(expanded = kindMenu, onDismissRequest = { kindMenu = false }) {
                    listOf<ResourceKind?>(null, ResourceKind.IMAGE, ResourceKind.AUDIO).forEach { kind ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when (kind) {
                                        null -> "全部资源"
                                        ResourceKind.IMAGE -> "图片"
                                        ResourceKind.AUDIO -> "音频"
                                    },
                                )
                            },
                            onClick = {
                                kindFilter = kind
                                kindMenu = false
                            },
                        )
                    }
                }
            }
            Text(
                text = "工程 ${projectResources.size} · 数据包 ${packResources.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                scope.launch {
                    val path = runCatching {
                        filePicker.openFile(
                            title = "导入图片或音频",
                            extensions = ResourceInventoryScanner.IMAGE_EXTENSIONS +
                                ResourceInventoryScanner.AUDIO_EXTENSIONS,
                        )
                    }.getOrNull()
                    if (path != null) import(path, overwrite = false)
                }
            }) {
                Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("导入资源")
            }
            Button(onClick = { controller.saveProject() }) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("保存工程")
            }
        }
        HorizontalSeparator()
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(modifier = Modifier.weight(0.55f).fillMaxHeight()) {
                item { ResourceSectionTitle("工程资源（${projectResources.size}）") }
                items(projectResources, key = { "project:${it.kind}:${it.key}" }) { resource ->
                    val path = resource.sourcePath ?: project.root?.resolve(resource.relativePath)
                    ResourceRow(
                        iconKind = resource.kind,
                        key = resource.key,
                        typeName = resource.typeName,
                        origin = "工程",
                        relativePath = resource.relativePath,
                        sizeBytes = resource.sizeBytes,
                        imagePath = path,
                        selected = selected == ResourceSelection.Project(resource),
                        onClick = { selected = ResourceSelection.Project(resource) },
                    )
                }
                if (projectResources.isEmpty()) {
                    item { EmptyHint(title = "没有工程资源", hint = "导入图片/音频后会显示在这里。") }
                }
                item { ResourceSectionTitle("数据包资源（${packResources.size}）") }
                items(packResources, key = { "pack:${it.kind}:${it.key}:${it.relativePath}" }) { entry ->
                    ResourceRow(
                        iconKind = entry.kind,
                        key = entry.key,
                        typeName = entry.typeName,
                        origin = "数据包",
                        relativePath = entry.relativePath,
                        sizeBytes = entry.sizeBytes,
                        imagePath = controller.packResourceFile(entry),
                        selected = selected == ResourceSelection.Pack(entry),
                        onClick = { selected = ResourceSelection.Pack(entry) },
                    )
                }
                if (packResources.isEmpty()) {
                    item { EmptyHint(title = "数据包中没有文件型资源", hint = "游戏本体资源通常内嵌在 Unity 中。") }
                }
            }
            VerticalSeparator()
            ResourceDetails(
                selection = selected,
                controller = controller,
                onRemove = { resource ->
                    controller.removeResource(resource.kind, resource.key)
                    selected = null
                },
                modifier = Modifier.weight(0.45f).fillMaxHeight(),
            )
        }
        val resourceIssues = state.validation.filter {
            it.code.startsWith("resource.") || it.code.startsWith("conflict.resource")
        }
        if (resourceIssues.isNotEmpty()) {
            HorizontalSeparator()
            Text(
                text = "资源校验：" + resourceIssues.take(4).joinToString("；") { it.message },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }

    val overwriteTarget = pendingOverwrite
    if (overwriteTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingOverwrite = null },
            title = { Text("资源已存在") },
            text = { Text("工程中已存在同名资源：${overwriteTarget.fileName}。是否覆盖？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingOverwrite = null
                    import(overwriteTarget, overwrite = true)
                }) { Text("覆盖导入") }
            },
            dismissButton = {
                TextButton(onClick = { pendingOverwrite = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ResourceSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun ResourceRow(
    iconKind: ResourceKind,
    key: String,
    typeName: String,
    origin: String,
    relativePath: String,
    sizeBytes: Long,
    imagePath: Path?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (iconKind == ResourceKind.IMAGE) {
            FileImage(path = imagePath, modifier = Modifier.size(40.dp), contentDescription = key)
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.LibraryMusic, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = key,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$origin · $typeName · ${formatBytes(sizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = relativePath,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ResourceDetails(
    selection: ResourceSelection?,
    controller: WorkspaceController,
    onRemove: (ProjectResource) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(12.dp)) {
        SectionTitle("资源详情")
        when (selection) {
            null -> Text(
                text = "选择资源查看预览与元数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is ResourceSelection.Project -> {
                val resource = selection.resource
                if (resource.kind == ResourceKind.IMAGE) {
                    FileImage(
                        path = resource.sourcePath,
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        contentDescription = resource.key,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                } else {
                    Icon(
                        imageVector = Icons.Outlined.LibraryMusic,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "音频试听尚未接入（WAV 原生支持，MP3/OGG 需要额外解码库）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                KeyValue("键", resource.key, mono = true)
                KeyValue("类型", resource.typeName)
                KeyValue("来源", "工程")
                KeyValue("相对路径", resource.relativePath, mono = true)
                KeyValue("大小", formatBytes(resource.sizeBytes))
                TextButton(onClick = { onRemove(resource) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                    Text("从工程移除")
                }
            }

            is ResourceSelection.Pack -> {
                val entry = selection.entry
                if (entry.kind == ResourceKind.IMAGE) {
                    FileImage(
                        path = controller.packResourceFile(entry),
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        contentDescription = entry.key,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                } else {
                    Icon(
                        imageVector = Icons.Outlined.LibraryMusic,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                KeyValue("键", entry.key, mono = true)
                KeyValue("类型", entry.typeName)
                KeyValue("来源", "数据包（只读）")
                KeyValue("相对路径", entry.relativePath, mono = true)
                KeyValue("大小", formatBytes(entry.sizeBytes))
                KeyValue("扩展名", entry.extension)
            }
        }
    }
}
