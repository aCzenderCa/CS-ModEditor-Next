package org.zender.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.zender.app.workspace.DocumentTarget
import org.zender.app.workspace.WorkspaceController
import org.zender.app.workspace.WorkspaceScreen
import org.zender.app.workspace.WorkspaceUiState
import org.zender.editor.document.JsonDocument
import org.zender.editor.framework.FrameworkId
import org.zender.editor.project.PatchKind
import org.zender.editor.project.ProjectObjectKind

private data class VisibleTreeNode(val node: FileTreeNode, val depth: Int)

private data class PatchTargetCandidate(
    val type: String,
    val name: String,
    val displayName: String?,
    val source: String,
    val existing: Boolean,
)

private data class FolderCreationTarget(
    val type: String,
    val kind: ProjectObjectKind,
    val parent: String?,
    /** 从工具栏打开时允许选择类型/对象种类；从类型文件夹右键打开时固定。 */
    val allowTypeChange: Boolean = false,
)

private fun objectKindForGroupId(id: String): ProjectObjectKind =
    if (id.startsWith("group:so:")) {
        ProjectObjectKind.SCRIPTABLE_OBJECT
    } else {
        ProjectObjectKind.UNIQUE_ID
    }

@Composable
fun FileTreePane(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    modifier: Modifier = Modifier,
) {
    var keyword by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<String?>(null) }
    var patchesOnly by remember { mutableStateOf(false) }
    var includePackObjects by remember { mutableStateOf(false) }
    var typeMenuOpen by remember { mutableStateOf(false) }
    var showNewObject by remember { mutableStateOf(false) }
    var newObjectType by remember { mutableStateOf<String?>(null) }
    var newObjectKind by remember { mutableStateOf<ProjectObjectKind?>(null) }
    var newObjectGroup by remember { mutableStateOf<String?>(null) }
    var newObjectName by remember { mutableStateOf<String?>(null) }
    var newObjectSource by remember { mutableStateOf<JsonDocument?>(null) }
    var newObjectSourceName by remember { mutableStateOf<String?>(null) }
    var newObjectTypeLocked by remember { mutableStateOf(false) }
    var newObjectKindLocked by remember { mutableStateOf(false) }
    var newFolderTarget by remember { mutableStateOf<FolderCreationTarget?>(null) }
    var newFolderName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<FileTreeNode?>(null) }
    var newPatchKind by remember { mutableStateOf<PatchKind?>(null) }
    var newPatchCategory by remember { mutableStateOf<String?>(null) }
    var dragNode by remember { mutableStateOf<FileTreeNode?>(null) }
    var dropTargetId by remember { mutableStateOf<String?>(null) }
    val rowBounds = remember { mutableStateMapOf<String, Rect>() }
    val expanded = remember(state.projectRoot) { mutableStateMapOf<String, Boolean>() }

    fun openNewObject(
        type: String?,
        kind: ProjectObjectKind? = null,
        name: String? = null,
        source: JsonDocument? = null,
        sourceName: String? = null,
        typeLocked: Boolean = false,
        kindLocked: Boolean = false,
        group: String? = null,
    ) {
        newObjectType = type
        newObjectKind = kind
        newObjectGroup = group
        newObjectName = name
        newObjectSource = source
        newObjectSourceName = sourceName
        newObjectTypeLocked = typeLocked
        newObjectKindLocked = kindLocked
        showNewObject = true
    }

    /** 对象/数据包条目的补丁创建入口；已有补丁时显示“打开”。 */
    fun patchActions(type: String, name: String): List<Pair<String, () -> Unit>> {
        val current = state.project ?: return emptyList()
        val projectObject = current.objectOf(type, name)
        val packEntry = state.pack?.objectEntry(type, name)
        val uniqueId = projectObject?.uniqueId ?: packEntry?.uniqueId
        if (uniqueId != null) {
            val existing = current.workingTree.gameSourceModify(uniqueId)
            return listOf(
                (if (existing != null) "打开 GameSourceModify" else "新建 GameSourceModify 补丁") to {
                    controller.createGameSourceModifyPatch(type, name)
                },
            )
        }
        if (current.framework == FrameworkId.MC) {
            val dataKey = if (projectObject != null) "${current.namespace}:$name" else name
            val existing = current.workingTree.dataObjectModify(type, dataKey)
            return listOf(
                (if (existing != null) "打开 DataObjectModify" else "新建 DataObjectModify 补丁") to {
                    controller.createDataObjectModifyPatch(type, name)
                },
            )
        }
        return emptyList()
    }

    fun patchCandidates(kind: PatchKind): List<PatchTargetCandidate> {
        val current = state.project ?: return emptyList()
        val result = mutableListOf<PatchTargetCandidate>()
        val seen = HashSet<Pair<String, String>>()

        current.workingTree.objects.values.forEach { entry ->
            val guid = entry.uniqueId
            val matches = if (kind == PatchKind.GAME_SOURCE_MODIFY) {
                guid != null
            } else {
                guid == null
            }
            if (!matches) return@forEach
            val key = entry.type to entry.name
            if (!seen.add(key)) return@forEach
            val existing = if (kind == PatchKind.GAME_SOURCE_MODIFY) {
                current.workingTree.gameSourceModify(guid!!) != null
            } else {
                current.workingTree.dataObjectModify(
                    entry.type,
                    "${current.namespace}:${entry.name}",
                ) != null
            }
            result += PatchTargetCandidate(
                type = entry.type,
                name = entry.name,
                displayName = null,
                source = "工程",
                existing = existing,
            )
        }

        state.pack?.objectIndex?.entries.orEmpty().forEach { entry ->
            val guid = entry.uniqueId
            val matches = if (kind == PatchKind.GAME_SOURCE_MODIFY) {
                guid != null
            } else {
                guid == null
            }
            if (!matches) return@forEach
            val key = entry.type to entry.name
            if (!seen.add(key)) return@forEach
            val existing = if (kind == PatchKind.GAME_SOURCE_MODIFY) {
                current.workingTree.gameSourceModify(guid!!) != null
            } else {
                current.workingTree.dataObjectModify(entry.type, entry.name) != null
            }
            result += PatchTargetCandidate(
                type = entry.type,
                name = entry.name,
                displayName = entry.displayName,
                source = "数据包",
                existing = existing,
            )
        }
        return result.sortedWith(compareBy({ it.type }, { it.name }))
    }

    fun isDraggable(node: FileTreeNode): Boolean =
        node.kind == FileTreeNodeKind.OBJECT ||
            node.kind == FileTreeNodeKind.GAME_SOURCE_MODIFY

    fun isValidDropTarget(source: FileTreeNode, target: FileTreeNode): Boolean {
        if (!isDraggable(source) || target.kind != FileTreeNodeKind.GROUP) return false
        return when (source.kind) {
            FileTreeNodeKind.OBJECT -> {
                val entry = state.project?.objectOf(
                    source.type.orEmpty(),
                    source.name.orEmpty(),
                ) ?: return false
                val prefix = if (entry.kind == ProjectObjectKind.UNIQUE_ID) {
                    "group:uid:"
                } else {
                    "group:so:"
                }
                val type = source.type.orEmpty()
                target.id == "$prefix$type" || target.id.startsWith("$prefix$type:")
            }

            FileTreeNodeKind.GAME_SOURCE_MODIFY ->
                target.id == "group:gsm" || target.id.startsWith("group:gsm:")

            else -> false
        }
    }

    val filter = FileTreeFilter(
        keyword = keyword,
        type = typeFilter,
        patchesOnly = patchesOnly,
        includePackObjects = includePackObjects,
    )
    val nodes = remember(state.project, state.pack, filter) {
        FileTreeBuilder.build(state.project, state.pack, filter)
    }
    val visible = remember(nodes, expanded.toMap(), keyword) {
        flatten(nodes, expanded, forceExpand = keyword.isNotBlank())
    }
    val nodesById = remember(visible) {
        visible.associate { it.node.id to it.node }
    }
    val types = remember(state.project, state.pack, includePackObjects) {
        (
            FileTreeBuilder.availableTypes(state.project) +
                if (includePackObjects) {
                    state.pack?.objectIndex?.referenceTypes.orEmpty()
                } else {
                    emptySet()
                }
            ).distinct().sorted()
    }
    val activeId = activeTreeNodeId(state)
    val activeScreen = state.screen

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        PaneHeader(icon = Icons.Outlined.FolderOpen, title = "文件") {
            Text(
                text = "${state.project?.workingTree?.objectCount ?: 0} 对象",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.project != null) {
                IconButton(
                    onClick = {
                        newFolderName = ""
                        newFolderTarget = FolderCreationTarget(
                            type = typeFilter ?: "",
                            kind = ProjectObjectKind.UNIQUE_ID,
                            parent = null,
                            allowTypeChange = true,
                        )
                    },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CreateNewFolder,
                        contentDescription = "新建文件夹",
                        modifier = Modifier.size(15.dp),
                    )
                }
                IconButton(
                    onClick = { openNewObject(type = null) },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "新建对象",
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                placeholder = { Text("搜索类型 / 对象 / 补丁", style = MaterialTheme.typography.bodySmall) },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    TextButton(onClick = { typeMenuOpen = true }) {
                        Text(
                            text = typeFilter ?: "全部类型",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("全部类型") },
                            onClick = {
                                typeFilter = null
                                typeMenuOpen = false
                            },
                        )
                        types.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type) },
                                onClick = {
                                    typeFilter = type
                                    typeMenuOpen = false
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Checkbox(
                    checked = includePackObjects,
                    onCheckedChange = { includePackObjects = it },
                    modifier = Modifier.size(28.dp),
                )
                Text("数据包", style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.width(2.dp))
                Checkbox(
                    checked = patchesOnly,
                    onCheckedChange = { patchesOnly = it },
                    modifier = Modifier.size(28.dp),
                )
                Text("只看补丁", style = MaterialTheme.typography.labelSmall)
            }
        }
        HorizontalSeparator()

        if (state.project == null) {
            PaneHint("打开工程后显示文件树")
        } else if (visible.isEmpty()) {
            PaneHint("没有匹配的文件")
        } else {
            LazyColumn(modifier = Modifier.fillMaxHeight()) {
                items(visible, key = { it.node.id + "@" + it.depth }) { item ->
                    val node = item.node
                    val isExpanded = expanded[node.id]
                        ?: (item.depth == 0 && node.kind == FileTreeNodeKind.GROUP)
                    val selected = node.id == activeId ||
                        (node.kind == FileTreeNodeKind.METADATA && activeScreen == WorkspaceScreen.METADATA) ||
                        (node.kind == FileTreeNodeKind.LOCALIZATION && activeScreen == WorkspaceScreen.LOCALIZATION) ||
                        (node.kind == FileTreeNodeKind.RESOURCE && activeScreen == WorkspaceScreen.RESOURCES)
                    val contextActions = when {
                        node.kind == FileTreeNodeKind.PACK_OBJECT -> {
                            val type = node.type.orEmpty()
                            val name = node.name.orEmpty()
                            val entry = state.pack?.objectEntry(type, name)
                            val actions = patchActions(type, name).toMutableList()
                            if (entry?.hasDataFile == true) {
                                val kind = if (entry.uniqueId != null) {
                                    ProjectObjectKind.UNIQUE_ID
                                } else {
                                    ProjectObjectKind.SCRIPTABLE_OBJECT
                                }
                                actions += "复制到工程…" to {
                                    val source = controller.readPackObjectDocument(type, name)
                                    if (source != null) {
                                        openNewObject(
                                            type = type,
                                            kind = kind,
                                            name = controller.suggestObjectName(
                                                type,
                                                "${name}_Copy",
                                            ),
                                            source = source,
                                            sourceName = "$type/$name",
                                            typeLocked = true,
                                            kindLocked = true,
                                        )
                                    }
                                }
                            }
                            actions
                        }

                        node.kind == FileTreeNodeKind.OBJECT ->
                            patchActions(node.type.orEmpty(), node.name.orEmpty()) + listOf(
                                "删除对象…" to { deleteTarget = node },
                            )

                        node.kind == FileTreeNodeKind.GAME_SOURCE_MODIFY ||
                            node.kind == FileTreeNodeKind.DATA_OBJECT_MODIFY ->
                            listOf("删除补丁…" to { deleteTarget = node })

                        node.kind == FileTreeNodeKind.GROUP && node.id == "group:gsm" ->
                            listOf(
                                "新建 GameSourceModify 补丁…" to {
                                    newPatchCategory = null
                                    newPatchKind = PatchKind.GAME_SOURCE_MODIFY
                                },
                            )

                        node.kind == FileTreeNodeKind.GROUP &&
                            node.id.startsWith("group:gsm:") ->
                            listOf(
                                "在此文件夹新建 GameSourceModify 补丁…" to {
                                    newPatchCategory = node.folder
                                    newPatchKind = PatchKind.GAME_SOURCE_MODIFY
                                },
                            )

                        node.kind == FileTreeNodeKind.GROUP && node.id == "group:dom" ->
                            listOf(
                                "新建 DataObjectModify 补丁…" to {
                                    newPatchCategory = null
                                    newPatchKind = PatchKind.DATA_OBJECT_MODIFY
                                },
                            )

                        // 类型目录下的子文件夹（对象还可以继续嵌套多层）。
                        node.kind == FileTreeNodeKind.GROUP && node.folder != null &&
                            node.type != null -> {
                            val type = node.type
                            val kind = objectKindForGroupId(node.id)
                            val folder = node.folder
                            listOf(
                                "在此文件夹新建 $type 对象" to {
                                    openNewObject(type, kind, group = folder)
                                },
                                "新建子文件夹…" to {
                                    newFolderName = ""
                                    newFolderTarget = FolderCreationTarget(type, kind, folder)
                                },
                            )
                        }

                        node.kind == FileTreeNodeKind.GROUP &&
                            node.id.startsWith("group:uid:") -> {
                            val type = node.type.orEmpty()
                            listOf(
                                "新建 $type 对象" to {
                                    openNewObject(type, ProjectObjectKind.UNIQUE_ID)
                                },
                                "新建子文件夹…" to {
                                    newFolderName = ""
                                    newFolderTarget = FolderCreationTarget(
                                        type,
                                        ProjectObjectKind.UNIQUE_ID,
                                        null,
                                    )
                                },
                            )
                        }

                        node.kind == FileTreeNodeKind.GROUP &&
                            node.id.startsWith("group:so:") -> {
                            val type = node.type.orEmpty()
                            listOf(
                                "新建 $type 对象" to {
                                    openNewObject(type, ProjectObjectKind.SCRIPTABLE_OBJECT)
                                },
                                "新建子文件夹…" to {
                                    newFolderName = ""
                                    newFolderTarget = FolderCreationTarget(
                                        type,
                                        ProjectObjectKind.SCRIPTABLE_OBJECT,
                                        null,
                                    )
                                },
                            )
                        }

                        node.kind == FileTreeNodeKind.GROUP &&
                            node.id.startsWith("pack:group:") ->
                            listOf(
                                "新建 ${node.type} 对象" to {
                                    openNewObject(
                                        node.type,
                                        controller.inferObjectKind(node.type.orEmpty()),
                                    )
                                },
                            )

                        else -> emptyList()
                    }
                    TreeNodeRow(
                        node = node,
                        depth = item.depth,
                        expanded = isExpanded,
                        selected = selected,
                        contextActions = contextActions,
                        draggable = isDraggable(node),
                        deletable = node.kind == FileTreeNodeKind.OBJECT ||
                            node.kind == FileTreeNodeKind.GAME_SOURCE_MODIFY ||
                            node.kind == FileTreeNodeKind.DATA_OBJECT_MODIFY,
                        dragging = dragNode?.id == node.id,
                        dropHighlighted = dropTargetId == node.id,
                        onBounds = { rowBounds[node.id] = it },
                        onBoundsRemoved = { rowBounds.remove(node.id) },
                        onDragStart = {
                            dragNode = node
                            dropTargetId = null
                        },
                        onDrag = { windowPosition ->
                            val target = nodesById.values.firstOrNull { candidate ->
                                candidate.id != node.id &&
                                    rowBounds[candidate.id]?.contains(windowPosition) == true &&
                                    isValidDropTarget(node, candidate)
                            }
                            dropTargetId = target?.id
                        },
                        onDelete = { deleteTarget = node },
                        onDragEnd = {
                            val source = dragNode
                            val target = dropTargetId?.let(nodesById::get)
                            if (source != null && target != null) {
                                when (source.kind) {
                                    FileTreeNodeKind.OBJECT ->
                                        controller.moveProjectObjectToFolder(
                                            source.type.orEmpty(),
                                            source.name.orEmpty(),
                                            target.folder,
                                        )

                                    FileTreeNodeKind.GAME_SOURCE_MODIFY ->
                                        controller.moveGameSourceModifyToFolder(
                                            source.targetGuid.orEmpty(),
                                            target.folder,
                                        )

                                    else -> Unit
                                }
                            }
                            dragNode = null
                            dropTargetId = null
                        },
                        onToggle = {
                            if (!node.isLeaf) {
                                expanded[node.id] = !isExpanded
                            }
                        },
                        onClick = {
                            when (node.kind) {
                                FileTreeNodeKind.OBJECT -> controller.openObject(
                                    node.type.orEmpty(),
                                    node.name.orEmpty(),
                                )

                                FileTreeNodeKind.PACK_OBJECT -> controller.openPackObject(
                                    node.type.orEmpty(),
                                    node.name.orEmpty(),
                                )

                                FileTreeNodeKind.GAME_SOURCE_MODIFY ->
                                    controller.openGameSourceModify(node.targetGuid.orEmpty())

                                FileTreeNodeKind.DATA_OBJECT_MODIFY ->
                                    controller.openDataObjectModify(
                                        node.type.orEmpty(),
                                        node.dataKey.orEmpty(),
                                    )

                                FileTreeNodeKind.LOCALIZATION ->
                                    controller.setScreen(WorkspaceScreen.LOCALIZATION)

                                FileTreeNodeKind.RESOURCE ->
                                    controller.setScreen(WorkspaceScreen.RESOURCES)

                                FileTreeNodeKind.METADATA ->
                                    controller.setScreen(WorkspaceScreen.METADATA)

                                FileTreeNodeKind.GROUP ->
                                    controller.setScreen(WorkspaceScreen.FILES)
                            }
                        },
                    )
                }
            }
        }
    }

    if (showNewObject) {
        NewObjectDialog(
            controller = controller,
            state = state,
            presetType = newObjectType,
            presetKind = newObjectKind,
            presetGroup = newObjectGroup,
            defaultName = newObjectName,
            presetSourceName = newObjectSourceName,
            sourceDocument = newObjectSource,
            typeLocked = newObjectTypeLocked,
            kindLocked = newObjectKindLocked,
            onDismiss = {
                showNewObject = false
                newObjectType = null
                newObjectKind = null
                newObjectGroup = null
                newObjectName = null
                newObjectSource = null
                newObjectSourceName = null
                newObjectTypeLocked = false
                newObjectKindLocked = false
            },
        )
    }

    val patchKind = newPatchKind
    if (patchKind != null) {
        NewPatchTargetDialog(
            kind = patchKind,
            candidates = patchCandidates(patchKind),
            onSelect = { candidate ->
                when (patchKind) {
                    PatchKind.GAME_SOURCE_MODIFY ->
                        controller.createGameSourceModifyPatch(
                            candidate.type,
                            candidate.name,
                            newPatchCategory,
                        )

                    PatchKind.DATA_OBJECT_MODIFY ->
                        controller.createDataObjectModifyPatch(candidate.type, candidate.name)
                }
                newPatchKind = null
                newPatchCategory = null
            },
            onDismiss = {
                newPatchKind = null
                newPatchCategory = null
            },
        )
    }

    val pendingDelete = deleteTarget
    if (pendingDelete != null) {
        val isPatch = pendingDelete.kind == FileTreeNodeKind.GAME_SOURCE_MODIFY ||
            pendingDelete.kind == FileTreeNodeKind.DATA_OBJECT_MODIFY
        val targetText = when (pendingDelete.kind) {
            FileTreeNodeKind.GAME_SOURCE_MODIFY ->
                "GameSourceModify 补丁 ${pendingDelete.targetGuid}"

            FileTreeNodeKind.DATA_OBJECT_MODIFY ->
                "DataObjectModify 补丁 ${pendingDelete.type}/${pendingDelete.dataKey}"

            else -> "对象 ${pendingDelete.type}/${pendingDelete.name}"
        }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(if (isPatch) "删除补丁" else "删除对象") },
            text = {
                Text(
                    text = "确定删除 $targetText？\n" +
                        "会立即从工作树移除，保存工程后对应 JSON 文件也会被删除。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (pendingDelete.kind) {
                        FileTreeNodeKind.OBJECT -> controller.deleteProjectObject(
                            pendingDelete.type.orEmpty(),
                            pendingDelete.name.orEmpty(),
                        )

                        FileTreeNodeKind.GAME_SOURCE_MODIFY ->
                            controller.deleteGameSourceModify(
                                pendingDelete.targetGuid.orEmpty(),
                            )

                        FileTreeNodeKind.DATA_OBJECT_MODIFY ->
                            controller.deleteDataObjectModify(
                                pendingDelete.type.orEmpty(),
                                pendingDelete.dataKey.orEmpty(),
                            )

                        else -> Unit
                    }
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    val folderTarget = newFolderTarget
    if (folderTarget != null) {
        var folderType by remember(folderTarget) { mutableStateOf(folderTarget.type) }
        var folderKindUnique by remember(folderTarget) {
            mutableStateOf(folderTarget.kind == ProjectObjectKind.UNIQUE_ID)
        }
        AlertDialog(
            onDismissRequest = { newFolderTarget = null },
            title = { Text("新建文件夹") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (folderTarget.allowTypeChange) {
                        OutlinedTextField(
                            value = folderType,
                            onValueChange = { folderType = it.trim() },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            label = { Text("对象类型") },
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Checkbox(
                                checked = folderKindUnique,
                                onCheckedChange = { folderKindUnique = it },
                            )
                            Text(
                                text = if (folderKindUnique) {
                                    "UniqueID 对象目录（Data/<类型>）"
                                } else {
                                    "ScriptableObject 目录"
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    } else {
                        Text(
                            text = "位置：${folderTarget.type}/" +
                                (folderTarget.parent?.let { "$it/" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        label = { Text("文件夹名（多级用 / 分隔）") },
                    )
                }
            },
            confirmButton = {
                val resolvedType = if (folderTarget.allowTypeChange) {
                    folderType.trim()
                } else {
                    folderTarget.type
                }
                val resolvedKind = if (folderTarget.allowTypeChange) {
                    if (folderKindUnique) {
                        ProjectObjectKind.UNIQUE_ID
                    } else {
                        ProjectObjectKind.SCRIPTABLE_OBJECT
                    }
                } else {
                    folderTarget.kind
                }
                TextButton(
                    enabled = newFolderName.isNotBlank() && resolvedType.isNotBlank(),
                    onClick = {
                        val parent = folderTarget.parent
                        val path = listOfNotNull(
                            parent?.takeIf { it.isNotBlank() },
                            newFolderName.trim().replace('\\', '/').trim('/').ifBlank { null },
                        ).joinToString("/")
                        if (controller.createProjectFolder(resolvedType, path, resolvedKind)) {
                            newFolderTarget = null
                        }
                    },
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { newFolderTarget = null }) { Text("取消") }
            },
        )
    }
}

private fun flatten(
    nodes: List<FileTreeNode>,
    expanded: Map<String, Boolean>,
    depth: Int = 0,
    forceExpand: Boolean = false,
): List<VisibleTreeNode> {
    val result = mutableListOf<VisibleTreeNode>()
    nodes.forEach { node ->
        result += VisibleTreeNode(node, depth)
        val isExpanded = expanded[node.id] ?: (depth == 0 && node.kind == FileTreeNodeKind.GROUP) || forceExpand
        if (isExpanded && node.children.isNotEmpty()) {
            result += flatten(node.children, expanded, depth + 1, forceExpand)
        }
    }
    return result
}

private fun activeTreeNodeId(state: WorkspaceUiState): String? =
    when (val target = state.activeTarget) {
        is DocumentTarget.ProjectObjectTarget -> "object:${target.type}/${target.name}"
        is DocumentTarget.GameSourceModifyTarget -> "gsm:${target.targetGuid}"
        is DocumentTarget.DataObjectModifyTarget -> "dom:${target.type}/${target.dataKey}"
        is DocumentTarget.PackObjectTarget -> "pack:${target.type}/${target.name}"
        null -> null
    }

@Composable
private fun TreeNodeRow(
    node: FileTreeNode,
    depth: Int,
    expanded: Boolean,
    selected: Boolean,
    contextActions: List<Pair<String, () -> Unit>> = emptyList(),
    draggable: Boolean = false,
    deletable: Boolean = false,
    dragging: Boolean = false,
    dropHighlighted: Boolean = false,
    onBounds: (Rect) -> Unit = {},
    onBoundsRemoved: () -> Unit = {},
    onDelete: () -> Unit = {},
    onDragStart: () -> Unit = {},
    onDrag: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val background = when {
        dropHighlighted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        dragging -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else -> Color.Transparent
    }
    var menuOpen by remember { mutableStateOf(false) }
    var rowOrigin by remember { mutableStateOf(Offset.Zero) }
    val focusRequester = remember { FocusRequester() }
    // 行滚出可视区后清理命中区域，避免拖拽命中已不在屏幕上的旧行。
    DisposableEffect(node.id) {
        onDispose { onBoundsRemoved() }
    }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(background)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    rowOrigin = bounds.topLeft
                    onBounds(bounds)
                }
                .pointerInput(node.id, draggable) {
                    if (!draggable) return@pointerInput
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDrag = { change, _ ->
                            change.consume()
                            onDrag(rowOrigin + change.position)
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    )
                }
                .pointerInput(node.id) {
                    if (contextActions.isEmpty()) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press &&
                                event.buttons.isSecondaryPressed
                            ) {
                                menuOpen = true
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (deletable &&
                        event.type == KeyEventType.KeyDown &&
                        event.key == Key.Delete
                    ) {
                        onDelete()
                        true
                    } else {
                        false
                    }
                }
                .focusable()
                .clickable(
                    onClick = {
                        focusRequester.requestFocus()
                        onClick()
                        if (!node.isLeaf) onToggle()
                    },
                )
                .padding(start = (6 + depth * 14).dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
        if (!node.isLeaf) {
            IconButton(onClick = onToggle, modifier = Modifier.size(20.dp)) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                    contentDescription = if (expanded) "折叠" else "展开",
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            Spacer(modifier = Modifier.width(20.dp))
        }
        Icon(
            imageVector = iconFor(node),
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = node.label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (node.detail != null) {
            Text(
                text = node.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            contextActions.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        menuOpen = false
                        action()
                    },
                )
            }
        }
    }
}

private fun iconFor(node: FileTreeNode): ImageVector = when (node.kind) {
    FileTreeNodeKind.METADATA -> Icons.Outlined.Description
    FileTreeNodeKind.GROUP -> if (node.children.isEmpty()) Icons.Outlined.Folder else Icons.Outlined.FolderOpen
    FileTreeNodeKind.OBJECT -> Icons.Outlined.Description
    FileTreeNodeKind.PACK_OBJECT -> Icons.Outlined.Description
    FileTreeNodeKind.GAME_SOURCE_MODIFY -> Icons.Outlined.Build
    FileTreeNodeKind.DATA_OBJECT_MODIFY -> Icons.Outlined.Build
    FileTreeNodeKind.LOCALIZATION -> Icons.AutoMirrored.Outlined.Article
    FileTreeNodeKind.RESOURCE -> Icons.Outlined.Image
}

@Composable
private fun NewPatchTargetDialog(
    kind: PatchKind,
    candidates: List<PatchTargetCandidate>,
    onSelect: (PatchTargetCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    val filtered = remember(candidates, keyword) {
        val text = keyword.trim()
        if (text.isEmpty()) {
            candidates
        } else {
            candidates.filter {
                it.name.contains(text, ignoreCase = true) ||
                    it.type.contains(text, ignoreCase = true) ||
                    it.displayName?.contains(text, ignoreCase = true) == true
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (kind == PatchKind.GAME_SOURCE_MODIFY) {
                    "新建 GameSourceModify 补丁"
                } else {
                    "新建 DataObjectModify 补丁"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    label = { Text("搜索目标对象") },
                )
                if (filtered.isEmpty()) {
                    Text(
                        text = if (kind == PatchKind.GAME_SOURCE_MODIFY) {
                            "没有带 UniqueID 的目标对象"
                        } else {
                            "没有无 GUID 的 ScriptableObject 目标"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(340.dp)) {
                        items(filtered, key = { "${it.type}/${it.name}" }) { candidate ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(candidate) }
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = candidate.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = buildString {
                                            append(candidate.type)
                                            append(" · ").append(candidate.source)
                                            candidate.displayName
                                                ?.takeIf { it.isNotBlank() }
                                                ?.let { append(" · ").append(it) }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (candidate.existing) StatusChip("已存在")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
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
