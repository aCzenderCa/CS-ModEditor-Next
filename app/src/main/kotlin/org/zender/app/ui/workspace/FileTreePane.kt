package org.zender.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import org.zender.editor.project.ProjectObjectKind

private data class VisibleTreeNode(val node: FileTreeNode, val depth: Int)

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
    var newObjectName by remember { mutableStateOf<String?>(null) }
    var newObjectSource by remember { mutableStateOf<JsonDocument?>(null) }
    var newObjectSourceName by remember { mutableStateOf<String?>(null) }
    var newObjectTypeLocked by remember { mutableStateOf(false) }
    var newObjectKindLocked by remember { mutableStateOf(false) }
    val expanded = remember(state.projectRoot) { mutableStateMapOf<String, Boolean>() }

    fun openNewObject(
        type: String?,
        kind: ProjectObjectKind? = null,
        name: String? = null,
        source: JsonDocument? = null,
        sourceName: String? = null,
        typeLocked: Boolean = false,
        kindLocked: Boolean = false,
    ) {
        newObjectType = type
        newObjectKind = kind
        newObjectName = name
        newObjectSource = source
        newObjectSourceName = sourceName
        newObjectTypeLocked = typeLocked
        newObjectKindLocked = kindLocked
        showNewObject = true
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
                        (node.kind == FileTreeNodeKind.LOCALIZATION && activeScreen == WorkspaceScreen.LOCALIZATION) ||
                        (node.kind == FileTreeNodeKind.RESOURCE && activeScreen == WorkspaceScreen.RESOURCES)
                    val contextActions = when {
                        node.kind == FileTreeNodeKind.PACK_OBJECT -> {
                            val type = node.type.orEmpty()
                            val name = node.name.orEmpty()
                            val entry = state.pack?.objectEntry(type, name)
                            if (entry?.hasDataFile == true) {
                                val kind = if (entry.uniqueId != null) {
                                    ProjectObjectKind.UNIQUE_ID
                                } else {
                                    ProjectObjectKind.SCRIPTABLE_OBJECT
                                }
                                listOf(
                                    "复制到工程…" to {
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
                                    },
                                )
                            } else {
                                emptyList()
                            }
                        }

                        node.kind == FileTreeNodeKind.GROUP &&
                            node.id.startsWith("group:uid:") ->
                            listOf(
                                "新建 ${node.type} 对象" to {
                                    openNewObject(node.type, ProjectObjectKind.UNIQUE_ID)
                                },
                            )

                        node.kind == FileTreeNodeKind.GROUP &&
                            node.id.startsWith("group:so:") ->
                            listOf(
                                "新建 ${node.type} 对象" to {
                                    openNewObject(node.type, ProjectObjectKind.SCRIPTABLE_OBJECT)
                                },
                            )

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

                                FileTreeNodeKind.METADATA,
                                FileTreeNodeKind.GROUP,
                                -> controller.setScreen(WorkspaceScreen.FILES)
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
            defaultName = newObjectName,
            presetSourceName = newObjectSourceName,
            sourceDocument = newObjectSource,
            typeLocked = newObjectTypeLocked,
            kindLocked = newObjectKindLocked,
            onDismiss = {
                showNewObject = false
                newObjectType = null
                newObjectKind = null
                newObjectName = null
                newObjectSource = null
                newObjectSourceName = null
                newObjectTypeLocked = false
                newObjectKindLocked = false
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
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        Color.Transparent
    }
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(background)
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
                .clickable(
                    onClick = {
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
private fun PaneHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
