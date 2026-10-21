package org.zender.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.zender.app.platform.FilePicker
import java.awt.Cursor
import java.nio.file.Path
import org.zender.app.ui.theme.ThemeMode
import org.zender.app.workspace.OpenProjectBehavior
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
    onOpenProjectInNewWindow: (Path) -> Unit = {},
    openProjects: List<OpenProjectInfo> = emptyList(),
    onActivateOpenProject: (String) -> Unit = {},
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var closeTarget by remember { mutableStateOf<DocumentId?>(null) }
    var pendingProject by remember { mutableStateOf<Path?>(null) }
    var projectOpenChoice by remember { mutableStateOf<Path?>(null) }
    var dontAskAgain by remember { mutableStateOf(false) }
    var showNewProject by remember { mutableStateOf(false) }
    val themeMode = ThemeMode.fromStorage(state.settings.themeMode)
    val darkTheme = ThemeMode.resolvedDark(themeMode, isSystemInDarkTheme())
    var treeWidth by remember {
        mutableStateOf(state.settings.fileTreeWidth.coerceIn(MIN_TREE_WIDTH, 900f).dp)
    }
    var inspectorWidth by remember {
        mutableStateOf(state.settings.inspectorWidth.coerceIn(MIN_INSPECTOR_WIDTH, 900f).dp)
    }

    fun loadProjectPath(path: Path) {
        scope.launch { controller.loadProject(path) }
    }

    fun loadInCurrentWindow(path: Path) {
        if (controller.state.value.session.hasUnsavedChanges) {
            pendingProject = path
        } else {
            loadProjectPath(path)
        }
    }

    fun requestProjectOpen(path: Path) {
        val normalized = normalizePath(path.toString())
        val alreadyOpen = openProjects.firstOrNull { normalizePath(it.path) == normalized }
        if (alreadyOpen != null) {
            onActivateOpenProject(alreadyOpen.path)
            return
        }
        controller.reloadSettings()
        val current = controller.state.value
        if (current.project == null) {
            loadProjectPath(path)
            return
        }
        when (OpenProjectBehavior.fromStorage(current.settings.openProjectBehavior)) {
            OpenProjectBehavior.NEW_WINDOW -> onOpenProjectInNewWindow(path)
            OpenProjectBehavior.CURRENT_WINDOW -> loadInCurrentWindow(path)
            OpenProjectBehavior.ASK -> {
                dontAskAgain = false
                projectOpenChoice = path
            }
        }
    }

    fun chooseProject() {
        scope.launch {
            runCatching { filePicker.pickDirectory(title = "选择 Mod 工程目录") }
                .getOrNull()
                ?.let(::requestProjectOpen)
        }
    }

    fun openRecentProject(pathText: String) {
        val path = runCatching { Path.of(pathText) }.getOrNull() ?: return
        requestProjectOpen(path)
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
            onNewProject = { showNewProject = true },
            onOpenRecentProject = ::openRecentProject,
            onProjectMenuOpen = controller::reloadSettings,
            openProjects = openProjects,
            onActivateOpenProject = onActivateOpenProject,
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
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val totalWidth = maxWidth.value
                    val reserved = MIN_CENTER_WIDTH + SPLITTER_WIDTH * 2
                    val maxTreeWidth = (totalWidth - inspectorWidth.value - reserved)
                        .coerceAtLeast(MIN_TREE_WIDTH)
                    val maxInspectorWidth = (totalWidth - treeWidth.value - reserved)
                        .coerceAtLeast(MIN_INSPECTOR_WIDTH)
                    Row(modifier = Modifier.fillMaxSize()) {
                        FileTreePane(
                            controller = controller,
                            state = state,
                            modifier = Modifier.width(treeWidth).fillMaxHeight(),
                        )
                        ResizableSplitter(
                            onDrag = { delta ->
                                treeWidth = (treeWidth.value + delta)
                                    .coerceIn(MIN_TREE_WIDTH, maxTreeWidth)
                                    .dp
                            },
                            onDragEnd = {
                                controller.setPaneWidths(treeWidth.value, inspectorWidth.value)
                            },
                        )
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
                                    onNewProject = { showNewProject = true },
                                    onChoosePack = ::choosePack,
                                )
                            }
                        }
                        ResizableSplitter(
                            onDrag = { delta ->
                                inspectorWidth = (inspectorWidth.value - delta)
                                    .coerceIn(MIN_INSPECTOR_WIDTH, maxInspectorWidth)
                                    .dp
                            },
                            onDragEnd = {
                                controller.setPaneWidths(treeWidth.value, inspectorWidth.value)
                            },
                        )
                        InspectorPane(
                            controller = controller,
                            state = state,
                            modifier = Modifier.width(inspectorWidth).fillMaxHeight(),
                        )
                    }
                }
            }

            WorkspaceScreen.METADATA -> MetadataPage(controller, state)
            WorkspaceScreen.GAMES -> GamesPage(controller, state, filePicker)
            WorkspaceScreen.STAR_MAP -> StarMapPage(controller, state)
            WorkspaceScreen.LOCALIZATION -> LocalizationPage(controller, state)
            WorkspaceScreen.RESOURCES -> ResourcePage(controller, state, filePicker)
            WorkspaceScreen.EXPORT -> ExportPage(controller, state, filePicker)
            WorkspaceScreen.HELP -> HelpPage()
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

    val choiceProject = projectOpenChoice
    if (choiceProject != null) {
        AlertDialog(
            onDismissRequest = { projectOpenChoice = null },
            title = { Text("打开工程") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "当前窗口已经打开了工程。请选择打开 ${choiceProject.fileName} 的方式：",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Checkbox(
                            checked = dontAskAgain,
                            onCheckedChange = { dontAskAgain = it },
                        )
                        Text("不再询问（记住本次选择）", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        if (dontAskAgain) {
                            controller.setOpenProjectBehavior(OpenProjectBehavior.NEW_WINDOW)
                        }
                        projectOpenChoice = null
                        onOpenProjectInNewWindow(choiceProject)
                    }) { Text("新窗口") }
                    TextButton(onClick = {
                        if (dontAskAgain) {
                            controller.setOpenProjectBehavior(OpenProjectBehavior.CURRENT_WINDOW)
                        }
                        projectOpenChoice = null
                        loadInCurrentWindow(choiceProject)
                    }) { Text("当前窗口") }
                }
            },
            dismissButton = {
                TextButton(onClick = { projectOpenChoice = null }) { Text("取消") }
            },
        )
    }

    val nextProject = pendingProject
    if (nextProject != null) {
        AlertDialog(
            onDismissRequest = { pendingProject = null },
            title = { Text("未保存的修改") },
            text = {
                Text("当前工程有未保存的修改。打开 ${nextProject.fileName} 前要保存吗？")
            },
            confirmButton = {
                TextButton(onClick = {
                    if (controller.saveProject()) {
                        pendingProject = null
                        loadProjectPath(nextProject)
                    } else {
                        pendingProject = null
                    }
                }) { Text("保存并打开") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        pendingProject = null
                        loadProjectPath(nextProject)
                    }) { Text("放弃并打开") }
                    TextButton(onClick = { pendingProject = null }) { Text("取消") }
                }
            },
        )
    }

    if (showNewProject) {
        NewProjectDialog(
            filePicker = filePicker,
            onDismiss = { showNewProject = false },
            onCreate = { form ->
                scope.launch {
                    val created = controller.createProject(
                        root = form.root,
                        framework = form.framework,
                        name = form.name,
                        namespace = form.namespace,
                        author = form.author,
                        version = form.version,
                    )
                    if (created) {
                        showNewProject = false
                        requestProjectOpen(form.root)
                    }
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
    onNewProject: () -> Unit,
    onOpenRecentProject: (String) -> Unit,
    onProjectMenuOpen: () -> Unit,
    openProjects: List<OpenProjectInfo>,
    onActivateOpenProject: (String) -> Unit,
    onSave: () -> Unit,
    onValidate: () -> Unit,
    onScreen: (WorkspaceScreen) -> Unit,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    var projectMenuOpen by remember { mutableStateOf(false) }
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
            Box {
                Surface(
                    modifier = Modifier.clickable {
                        onProjectMenuOpen()
                        projectMenuOpen = true
                    },
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = state.project?.let { "${it.metadata.displayName()} · ${it.framework}" }
                                ?: "未打开工程",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                        Icon(
                            imageVector = Icons.Outlined.ArrowDropDown,
                            contentDescription = "最近打开的项目",
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                DropdownMenu(
                    expanded = projectMenuOpen,
                    onDismissRequest = { projectMenuOpen = false },
                ) {
                    val openByPath = openProjects.associateBy { normalizePath(it.path) }
                    val recentPaths = state.settings.recentProjects
                    val extraOpen = openProjects.filter { info ->
                        recentPaths.none { normalizePath(it) == normalizePath(info.path) }
                    }
                    val menuPaths = extraOpen.map { it.path } + recentPaths
                    val currentPath = state.projectRoot?.toString()?.let(::normalizePath)

                    Text(
                        text = "项目（已打开 ${openProjects.size}）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    if (menuPaths.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("暂无最近项目", style = MaterialTheme.typography.bodySmall) },
                            onClick = {},
                            enabled = false,
                        )
                    } else {
                        menuPaths.forEach { path ->
                            val normalized = normalizePath(path)
                            val openInfo = openByPath[normalized]
                            val isCurrent = normalized == currentPath
                            val displayName = runCatching {
                                Path.of(path).fileName?.toString()
                            }.getOrNull() ?: path
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = displayName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isCurrent) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            when {
                                                isCurrent -> StatusChip("当前窗口")
                                                openInfo != null -> StatusChip("已打开")
                                                else -> Unit
                                            }
                                        }
                                        Text(
                                            text = path,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                onClick = {
                                    projectMenuOpen = false
                                    if (openInfo != null) {
                                        onActivateOpenProject(openInfo.path)
                                    } else {
                                        onOpenRecentProject(path)
                                    }
                                },
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("新建工程…", style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            projectMenuOpen = false
                            onNewProject()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("打开其他工程…", style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            projectMenuOpen = false
                            onOpenProject()
                        },
                    )
                }
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

private fun normalizePath(value: String): String =
    runCatching { Path.of(value).toAbsolutePath().normalize().toString() }.getOrDefault(value)

private const val MIN_TREE_WIDTH = 180f
private const val MIN_INSPECTOR_WIDTH = 220f
private const val MIN_CENTER_WIDTH = 320f
private const val SPLITTER_WIDTH = 8f

/**
 * 可拖动的竖向分隔线。左右拖动会回调 dp 增量。
 */
@Composable
private fun ResizableSplitter(
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit = {},
) {
    val density = LocalDensity.current
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val cursor = remember { PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)) }
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(SPLITTER_WIDTH.dp)
            .pointerHoverIcon(cursor)
            .pointerInput(density) {
                detectHorizontalDragGestures(
                    onDragEnd = { currentOnDragEnd() },
                ) { change, dragAmount ->
                    change.consume()
                    currentOnDrag(with(density) { dragAmount.toDp().value })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        )
    }
}

private fun screenTabs(): List<Pair<WorkspaceScreen, String>> = listOf(
    WorkspaceScreen.FILES to "文件",
    WorkspaceScreen.METADATA to "元数据",
    WorkspaceScreen.GAMES to "游戏",
    WorkspaceScreen.STAR_MAP to "星图",
    WorkspaceScreen.LOCALIZATION to "本地化",
    WorkspaceScreen.RESOURCES to "资源",
    WorkspaceScreen.EXPORT to "导出",
    WorkspaceScreen.SETTINGS to "设置",
    WorkspaceScreen.HELP to "帮助",
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
