package org.zender.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.zender.app.platform.FilePicker
import org.zender.app.ui.theme.ThemeMode
import org.zender.app.workspace.WorkspaceController
import org.zender.app.workspace.WorkspaceScreen
import org.zender.app.workspace.WorkspaceUiState
import org.zender.editor.document.DocumentId

/**
 * 常规工作区：文件树 + 文件 Tab / 编辑器 + 检查器，以及本地化、资源、导出与设置页面。
 */
@Composable
fun WorkspaceShell(
    controller: WorkspaceController,
    filePicker: FilePicker,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var closeTarget by remember { mutableStateOf<DocumentId?>(null) }
    val themeMode = ThemeMode.fromStorage(state.settings.themeMode)
    val darkTheme = ThemeMode.resolvedDark(themeMode, isSystemInDarkTheme())

    fun chooseProject() {
        scope.launch {
            runCatching { filePicker.pickDirectory(title = "选择 Mod 工程目录") }
                .getOrNull()
                ?.let { controller.loadProject(it) }
        }
    }

    fun choosePack() {
        scope.launch {
            runCatching { filePicker.pickDirectory(title = "选择游戏数据包目录（csti / csff）") }
                .getOrNull()
                ?.let { controller.loadPack(it) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        WorkspaceTopBar(
            state = state,
            onChoosePack = ::choosePack,
            onOpenProject = ::chooseProject,
            onSave = { scope.launch { controller.saveProject() } },
            onValidate = { controller.validateProject() },
            onScreen = controller::setScreen,
            darkTheme = darkTheme,
            onToggleTheme = {
                controller.setThemeMode(if (darkTheme) ThemeMode.LIGHT else ThemeMode.DARK)
            },
        )
        HorizontalSeparator()
        when (state.screen) {
            WorkspaceScreen.FILES -> {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    FileTreePane(
                        controller = controller,
                        state = state,
                        modifier = Modifier.width(260.dp).fillMaxHeight(),
                    )
                    VerticalSeparator()
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        FileTabBar(
                            controller = controller,
                            state = state,
                            onCloseRequest = { id ->
                                val document = state.session.documents[id]
                                if (document?.isDirty == true) {
                                    closeTarget = id
                                } else {
                                    controller.closeDocument(id)
                                }
                            },
                        )
                        HorizontalSeparator()
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            EditorArea(
                                controller = controller,
                                state = state,
                                onOpenProject = ::chooseProject,
                                onChoosePack = ::choosePack,
                            )
                        }
                    }
                    VerticalSeparator()
                    InspectorPane(
                        controller = controller,
                        state = state,
                        modifier = Modifier.width(300.dp).fillMaxHeight(),
                    )
                }
            }

            WorkspaceScreen.LOCALIZATION -> LocalizationPage(controller, state)
            WorkspaceScreen.RESOURCES -> ResourcePage(controller, state, filePicker)
            WorkspaceScreen.EXPORT -> ExportPage(controller, state, filePicker)
            WorkspaceScreen.SETTINGS -> SettingsPage(controller, state, filePicker)
        }
        HorizontalSeparator()
        StatusBar(state)
    }

    val target = closeTarget
    if (target != null) {
        val document = state.session.documents[target]
        AlertDialog(
            onDismissRequest = { closeTarget = null },
            title = { Text("未保存的修改") },
            text = { Text("${document?.title ?: target.value} 有未保存的修改。保存后再关闭？") },
            confirmButton = {
                TextButton(onClick = {
                    if (controller.saveDocument(target)) {
                        controller.closeDocument(target, force = true)
                    }
                    closeTarget = null
                }) { Text("保存并关闭") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        controller.closeDocument(target, force = true)
                        closeTarget = null
                    }) { Text("不保存") }
                    TextButton(onClick = { closeTarget = null }) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun WorkspaceTopBar(
    state: WorkspaceUiState,
    onChoosePack: () -> Unit,
    onOpenProject: () -> Unit,
    onSave: () -> Unit,
    onValidate: () -> Unit,
    onScreen: (WorkspaceScreen) -> Unit,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                    text = state.project?.let { "${it.metadata.displayName()} · ${it.framework}" }
                        ?: "未打开工程",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    maxLines = 1,
                )
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            ) {
                Text(
                    text = state.packName.ifBlank { "未加载数据包" },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onChoosePack) { Text("选择数据包") }
            OutlinedButton(onClick = onOpenProject) { Text("打开工程") }
            OutlinedButton(
                enabled = state.project != null,
                onClick = onSave,
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("保存")
            }
            Button(
                enabled = state.project != null,
                onClick = onValidate,
            ) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("校验")
            }
        }
        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            screenTabs().forEach { (screen, label) ->
                val selected = state.screen == screen
                TextButton(
                    onClick = { onScreen(screen) },
                    modifier = Modifier.height(30.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            androidx.compose.material3.IconButton(
                onClick = onToggleTheme,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = if (darkTheme) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                    contentDescription = if (darkTheme) "切换到浅色主题" else "切换到深色主题",
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = buildString {
                    if (state.errorCount > 0) append("错误 ${state.errorCount} · ")
                    if (state.warningCount > 0) append("警告 ${state.warningCount} · ")
                    append("${state.session.documentOrder.size} 个 Tab")
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (state.errorCount > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private fun screenTabs(): List<Pair<WorkspaceScreen, String>> = listOf(
    WorkspaceScreen.FILES to "文件",
    WorkspaceScreen.LOCALIZATION to "本地化",
    WorkspaceScreen.RESOURCES to "资源",
    WorkspaceScreen.EXPORT to "导出",
    WorkspaceScreen.SETTINGS to "设置",
)

@Composable
private fun StatusBar(state: WorkspaceUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.status,
            style = MaterialTheme.typography.labelSmall,
            color = if (state.statusIsError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val active = state.activeDocument
        if (active?.isDirty == true) {
            Text(
                text = "未保存 ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = buildString {
                append(state.projectRoot?.fileName?.toString() ?: "未打开工程")
                if (state.project != null) append(" · ${state.project.framework}")
                append(" · ")
                append(state.packRoot?.fileName?.toString() ?: "未选择数据包")
                if (state.errorCount > 0) append(" · 错误 ${state.errorCount}")
                if (state.warningCount > 0) append(" · 警告 ${state.warningCount}")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
