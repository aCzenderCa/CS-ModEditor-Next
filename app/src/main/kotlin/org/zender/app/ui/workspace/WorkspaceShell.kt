package org.zender.app.ui.workspace

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.zender.app.platform.FilePicker
import java.nio.file.Path

/**
 * 常规工作区骨架：顶部工具栏 + 文件树 + 文件 Tab / 编辑器 + 检查器 + 状态栏。
 *
 * 当前阶段只有布局与空状态；工程解析、文件树内容与编辑器会在后续阶段接入。
 */
@Composable
fun WorkspaceShell(filePicker: FilePicker) {
    var projectDirectory by remember { mutableStateOf<Path?>(null) }
    var packDirectory by remember { mutableStateOf<Path?>(null) }
    var status by remember { mutableStateOf("就绪") }
    val scope = rememberCoroutineScope()

    fun chooseProject() {
        scope.launch {
            runCatching { filePicker.pickDirectory(title = "选择 Mod 工程目录") }
                .onSuccess { path ->
                    if (path != null) {
                        projectDirectory = path
                        status = "已选择工程目录：${path.fileName}"
                    }
                }
                .onFailure { status = "打开工程失败：${it.message ?: it::class.simpleName}" }
        }
    }

    fun choosePack() {
        scope.launch {
            runCatching { filePicker.pickDirectory(title = "选择游戏数据包目录（csti / csff）") }
                .onSuccess { path ->
                    if (path != null) {
                        packDirectory = path
                        status = "已选择数据包目录：${path.fileName}"
                    }
                }
                .onFailure { status = "选择数据包失败：${it.message ?: it::class.simpleName}" }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        EditorTopBar(
            projectDirectory = projectDirectory,
            onChoosePack = ::choosePack,
            onOpenProject = ::chooseProject,
        )
        HorizontalSeparator()
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            FileTreePane(
                modifier = Modifier.width(248.dp).fillMaxHeight(),
                projectDirectory = projectDirectory,
            )
            VerticalSeparator()
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                FileTabBar(projectDirectory)
                HorizontalSeparator()
                EditorArea(
                    projectDirectory = projectDirectory,
                    onOpenProject = ::chooseProject,
                    onChoosePack = ::choosePack,
                )
            }
            VerticalSeparator()
            InspectorPane(modifier = Modifier.width(288.dp).fillMaxHeight())
        }
        HorizontalSeparator()
        StatusBar(status = status, packDirectory = packDirectory)
    }
}

@Composable
private fun EditorTopBar(
    projectDirectory: Path?,
    onChoosePack: () -> Unit,
    onOpenProject: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "CS-ModEditor-Next",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(
                text = projectDirectory?.fileName?.toString() ?: "未打开工程",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onChoosePack) { Text("选择数据包") }
        OutlinedButton(onClick = onOpenProject) { Text("打开工程") }
    }
}

@Composable
private fun FileTreePane(
    modifier: Modifier,
    projectDirectory: Path?,
) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
    ) {
        PaneHeader(icon = Icons.Outlined.FolderOpen, title = "文件")
        if (projectDirectory == null) {
            PaneHint("打开工程后显示文件树")
        } else {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = projectDirectory.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "文件树将在工程读取阶段接入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun FileTabBar(projectDirectory: Path?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (projectDirectory == null) "未打开文件" else "暂无打开的文件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EditorArea(
    projectDirectory: Path?,
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
                imageVector = if (projectDirectory == null) {
                    Icons.Outlined.FolderOpen
                } else {
                    Icons.AutoMirrored.Outlined.InsertDriveFile
                },
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (projectDirectory == null) "尚未打开 Mod 工程" else "工作区骨架已就绪",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = if (projectDirectory == null) {
                    "选择一个 ML / MC 工程目录，或指定游戏数据包后创建新工程"
                } else {
                    "工程解析、文件树与字段编辑器将在后续阶段接入"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (projectDirectory != null) {
                Text(
                    text = projectDirectory.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onOpenProject) { Text("打开工程目录") }
                OutlinedButton(onClick = onChoosePack) { Text("选择游戏数据包") }
            }
        }
    }
}

@Composable
private fun InspectorPane(modifier: Modifier) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
    ) {
        PaneHeader(icon = Icons.Outlined.Tune, title = "检查器")
        PaneHint("选择对象后显示字段、引用与校验信息")
    }
}

@Composable
private fun PaneHeader(icon: ImageVector, title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PaneHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun StatusBar(status: String, packDirectory: Path?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = packDirectory?.let { "数据包：${it.fileName}" } ?: "未选择数据包 · 未选择框架",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun HorizontalSeparator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}

@Composable
private fun VerticalSeparator() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}
