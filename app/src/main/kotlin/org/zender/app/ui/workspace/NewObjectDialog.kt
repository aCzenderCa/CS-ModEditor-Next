package org.zender.app.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.zender.app.workspace.WorkspaceController
import org.zender.app.workspace.WorkspaceUiState
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.objects.ObjectEntry
import org.zender.editor.project.ProjectObject
import org.zender.editor.project.ProjectObjectKind

/**
 * 新建工程对象对话框。
 *
 * 类型可从数据包已知类型中选择，也允许手填插件自定义类型；
 * UniqueID 对象自动生成 GUID，ScriptableObject 不生成。
 */
@Composable
fun NewObjectDialog(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    presetType: String? = null,
    presetKind: ProjectObjectKind? = null,
    presetGroup: String? = null,
    presetSourceName: String? = null,
    defaultName: String? = null,
    sourceDocument: JsonDocument? = null,
    typeLocked: Boolean = false,
    kindLocked: Boolean = false,
    onDismiss: () -> Unit,
    onCreated: (ProjectObject) -> Unit = {},
) {
    val pack = state.pack
    val knownTypes = remember(pack) {
        buildSet {
            pack?.typeNames?.let(::addAll)
            pack?.objectIndex?.guidIndexTypes?.let(::addAll)
            pack?.objectIndex?.nameIndexTypes?.let(::addAll)
        }.sorted()
    }
    var type by remember { mutableStateOf(presetType ?: knownTypes.firstOrNull().orEmpty()) }
    var name by remember {
        mutableStateOf(
            defaultName ?: if (type.isBlank()) "NewObject" else "New$type",
        )
    }
    var kind by remember {
        mutableStateOf(presetKind ?: controller.inferObjectKind(type))
    }
    val cardTypes = remember(pack, type) {
        pack?.schemaRepository?.schema(type)?.field("CardType")?.enumValues.orEmpty()
    }
    val presetFolder = presetGroup
        ?.trim()
        ?.replace('\\', '/')
        ?.trim('/')
        ?.takeIf { it.isNotEmpty() }
    val presetFolderFirst = presetFolder?.substringBefore('/')
    var group by remember {
        val sourceCardType = (sourceDocument?.root as? JsonNode.ObjectNode)?.get("CardType")
        val sourceGroup = cardTypes.firstOrNull { it.value == sourceCardType }?.name
        val presetCardType = presetFolderFirst
            ?.takeIf { candidate -> cardTypes.any { it.name == candidate } }
        mutableStateOf(
            presetCardType
                ?: sourceGroup
                ?: cardTypes.firstOrNull()?.name,
        )
    }
    // 从文件树文件夹右键打开时，presetFolder 是完整的目标目录；直接使用，避免重复叠加 CardType。
    val useAbsoluteFolder = presetFolder != null
    var subdirectory by remember { mutableStateOf(presetFolder.orEmpty()) }
    var copySource by remember(sourceDocument) { mutableStateOf(sourceDocument) }
    var copySourceName by remember { mutableStateOf(presetSourceName) }
    var sourcePickerOpen by remember { mutableStateOf(false) }
    var typePickerOpen by remember { mutableStateOf(false) }
    var cardTypeMenuOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val sourceEntries = remember(pack, type) {
        pack?.objectIndex?.entriesOfType(type)
            ?.filter { it.hasDataFile }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    LaunchedEffect(type) {
        if (defaultName.isNullOrBlank() &&
            type.isNotBlank() &&
            (name.isBlank() || name.startsWith("New"))
        ) {
            name = "New$type"
        }
        if (!kindLocked && type.isNotBlank()) {
            kind = controller.inferObjectKind(type)
        }
        if (cardTypes.isEmpty()) {
            group = null
        } else if (cardTypes.none { it.name == group }) {
            group = cardTypes.first().name
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建对象") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = type,
                        onValueChange = {
                            if (it.trim() != type) {
                                copySource = null
                                copySourceName = null
                            }
                            type = it.trim()
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !typeLocked,
                        textStyle = MaterialTheme.typography.bodySmall,
                        label = { Text("类型") },
                    )
                    TextButton(
                        enabled = !typeLocked,
                        onClick = { typePickerOpen = true },
                    ) { Text("搜索选择…") }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    label = { Text("对象名（文件名）") },
                )
                OutlinedTextField(
                    value = subdirectory,
                    onValueChange = { subdirectory = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    label = { Text("子目录（可选，多级用 / 分隔）") },
                    placeholder = { Text("例如 Weapons/Melee", style = MaterialTheme.typography.bodySmall) },
                )
                if (useAbsoluteFolder) {
                    Text(
                        text = "目标目录已由文件树指定；CardData 的第一段若为 CardType 会自动同步字段。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (type == "CardData" && cardTypes.isNotEmpty() && !useAbsoluteFolder) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("CardType", style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = "决定存放目录 Objects/CardData/<CardType>/",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.foundation.layout.Box {
                            TextButton(onClick = { cardTypeMenuOpen = true }) {
                                Text(group ?: "选择")
                            }
                            DropdownMenu(
                                expanded = cardTypeMenuOpen,
                                onDismissRequest = { cardTypeMenuOpen = false },
                            ) {
                                cardTypes.forEach { value ->
                                    DropdownMenuItem(
                                        text = { Text(value.name) },
                                        onClick = {
                                            group = value.name
                                            cardTypeMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("复制源", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = copySourceName?.let { "数据包对象：$it" } ?: "不使用，按模板新建",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        enabled = pack != null,
                        onClick = { sourcePickerOpen = true },
                    ) { Text(if (copySource == null) "选择…" else "更换…") }
                    if (copySource != null) {
                        TextButton(
                            onClick = {
                                copySource = null
                                copySourceName = null
                            },
                        ) { Text("清除") }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Checkbox(
                        checked = kind == ProjectObjectKind.UNIQUE_ID,
                        enabled = !kindLocked,
                        onCheckedChange = {
                            kind = if (it) {
                                ProjectObjectKind.UNIQUE_ID
                            } else {
                                ProjectObjectKind.SCRIPTABLE_OBJECT
                            }
                        },
                    )
                    Column {
                        Text("UniqueID 对象", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "勾选时自动生成 GUID；无 GUID 对象放在 ScriptableObject 目录。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (copySource != null) {
                    Text(
                        text = "将从数据包对象复制内容，并重新生成 GUID 与本地化 Key。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (pack?.schemaRepository?.schema(type) == null) {
                    Text(
                        text = "数据包中没有类型 $type 的 Schema，将创建空白对象并按原始 JSON 编辑。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = type.isNotBlank() && name.isNotBlank(),
                onClick = {
                    val folderPath = if (useAbsoluteFolder) {
                        subdirectory.trim().replace('\\', '/').trim('/').ifBlank { null }
                    } else {
                        buildString {
                            if (type == "CardData" && group != null) {
                                append(group)
                                val extra = subdirectory.trim()
                                    .replace('\\', '/')
                                    .trim('/')
                                if (extra.isNotEmpty()) append('/').append(extra)
                            } else {
                                append(subdirectory.trim().replace('\\', '/').trim('/'))
                            }
                        }.ifBlank { null }
                    }
                    val created = controller.createProjectObject(
                        type = type.trim(),
                        name = name.trim(),
                        kind = kind,
                        group = folderPath,
                        sourceDocument = copySource,
                    )
                    if (created != null) {
                        onCreated(created)
                        onDismiss()
                    } else {
                        error = controller.state.value.status
                    }
                },
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )

    if (typePickerOpen) {
        ObjectTypePickerDialog(
            types = knownTypes,
            onSelect = {
                type = it
                typePickerOpen = false
            },
            onDismiss = { typePickerOpen = false },
        )
    }

    if (sourcePickerOpen) {
        DataPackObjectSourceDialog(
            entries = sourceEntries,
            onSelect = { entry ->
                val document = controller.readPackObjectDocument(entry.type, entry.name)
                if (document != null) {
                    copySource = document
                    copySourceName = "${entry.type}/${entry.name}"
                    if (!typeLocked) type = entry.type
                    if (!kindLocked) {
                        kind = if (entry.uniqueId != null) {
                            ProjectObjectKind.UNIQUE_ID
                        } else {
                            ProjectObjectKind.SCRIPTABLE_OBJECT
                        }
                    }
                    val sourceCardTypes = pack?.schemaRepository
                        ?.schema(entry.type)
                        ?.field("CardType")
                        ?.enumValues
                        .orEmpty()
                    val sourceRoot = document.root as? JsonNode.ObjectNode
                    val sourceCardType = sourceRoot?.get("CardType")
                    group = sourceCardTypes.firstOrNull { it.value == sourceCardType }?.name
                        ?: entry.group
                        ?: sourceCardTypes.firstOrNull()?.name
                    name = controller.suggestObjectName(entry.type, "${entry.name}_Copy")
                }
                sourcePickerOpen = false
            },
            onDismiss = { sourcePickerOpen = false },
        )
    }
}

/**
 * 对象类型选择：支持按名称搜索，避免数据包类型较多时滚动查找。
 */
@Composable
private fun ObjectTypePickerDialog(
    types: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    val filtered = remember(types, keyword) {
        val text = keyword.trim()
        if (text.isEmpty()) {
            types
        } else {
            types.filter { it.contains(text, ignoreCase = true) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择对象类型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    label = { Text("搜索类型") },
                )
                if (filtered.isEmpty()) {
                    Text(
                        text = if (types.isEmpty()) "数据包中没有已知类型" else "没有匹配的类型",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                        items(filtered, key = { it }) { candidate ->
                            Text(
                                text = candidate,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(candidate) }
                                    .padding(vertical = 6.dp),
                            )
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
private fun DataPackObjectSourceDialog(
    entries: List<ObjectEntry>,
    onSelect: (ObjectEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    val filtered = remember(entries, keyword) {
        val text = keyword.trim()
        if (text.isEmpty()) {
            entries
        } else {
            entries.filter { entry ->
                entry.name.contains(text, ignoreCase = true) ||
                    entry.displayText.contains(text, ignoreCase = true) ||
                    entry.group?.contains(text, ignoreCase = true) == true
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从数据包对象复制") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    label = { Text("搜索对象名 / 显示名 / CardType") },
                )
                if (filtered.isEmpty()) {
                    Text(
                        text = "当前类型没有可复制的数据包对象",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                        items(filtered, key = { "${it.type}/${it.name}" }) { entry ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(entry) }
                                    .padding(vertical = 5.dp),
                            ) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = listOfNotNull(
                                        entry.displayName?.takeIf { it.isNotBlank() },
                                        entry.group?.takeIf { it.isNotBlank() },
                                    ).joinToString(" · ").ifBlank { "无显示名" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
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
