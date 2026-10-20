package org.zender.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.zender.app.workspace.PickerRequest
import org.zender.app.workspace.PickerWindowKind
import org.zender.app.workspace.WorkspaceController
import org.zender.editor.model.EntryOrigin
import org.zender.editor.objects.ReferenceKind
import org.zender.editor.picker.PickerItem

@Composable
fun PickerWindowContent(
    controller: WorkspaceController,
    request: PickerRequest,
) {
    val state by controller.state.collectAsState()
    var keyword by remember(request) { mutableStateOf("") }
    var origin by remember(request) { mutableStateOf<EntryOrigin?>(null) }
    var originMenu by remember { mutableStateOf(false) }
    var selectedIdentity by remember(request) { mutableStateOf<String?>(null) }
    var multiSelected by remember(request) { mutableStateOf<Set<String>>(emptySet()) }
    var recentOnly by remember(request) { mutableStateOf(false) }

    val items = remember(keyword, origin, recentOnly, state.project, state.pack, request) {
        if (recentOnly) {
            filterRecentItems(
                items = controller.recentPickerItems(),
                request = request,
                keyword = keyword,
                origin = origin,
                isCompatible = { item ->
                    state.pack?.referenceRule
                        ?.isCompatible(request.fieldType, item.type)
                        ?.compatible
                        ?: (item.type == request.fieldType)
                },
            )
        } else {
            controller.pickerItems(request, keyword, origin)
        }
    }
    val selected = items.firstOrNull { it.identity() == selectedIdentity } ?: items.firstOrNull()
    val chosen = if (request.isList) {
        items.filter { it.identity() in multiSelected }
    } else {
        listOfNotNull(selected)
    }
    val isAudio = state.pack?.referenceRule?.kindFor(request.fieldType) == ReferenceKind.AUDIO

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.weight(1f).height(42.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                placeholder = {
                    Text(
                        "搜索名称 / 类型 / GUID",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
            Box {
                TextButton(onClick = { originMenu = true }) {
                    Text(originLabel(origin), style = MaterialTheme.typography.labelMedium)
                }
                DropdownMenu(expanded = originMenu, onDismissRequest = { originMenu = false }) {
                    listOf<EntryOrigin?>(null, EntryOrigin.PROJECT, EntryOrigin.BASE_PACK).forEach { value ->
                        DropdownMenuItem(
                            text = { Text(originLabel(value)) },
                            onClick = {
                                origin = value
                                originMenu = false
                            },
                        )
                    }
                }
            }
            TextButton(onClick = { recentOnly = !recentOnly }) {
                Text(
                    text = if (recentOnly) "● 最近" else "最近",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = if (request.isList) "${items.size} 项 · 已选 ${multiSelected.size}" else "${items.size} 项",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalSeparator()
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (request.kind == PickerWindowKind.RESOURCE && !isAudio) {
                ResourceGrid(
                    controller = controller,
                    items = items,
                    selected = selected,
                    multiSelect = request.isList,
                    multiSelected = multiSelected,
                    onSelect = { item ->
                        selectedIdentity = item.identity()
                        if (request.isList) {
                            multiSelected = if (item.identity() in multiSelected) {
                                multiSelected - item.identity()
                            } else {
                                multiSelected + item.identity()
                            }
                        }
                    },
                    modifier = Modifier.weight(0.6f).fillMaxHeight(),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
                    if (items.isEmpty()) {
                        item {
                            EmptyHint(
                                title = "没有匹配的候选",
                                hint = "尝试清除关键字或切换来源过滤。",
                            )
                        }
                    }
                    items(items, key = { it.identity() }) { item ->
                        PickerItemRow(
                            controller = controller,
                            item = item,
                            selected = item.identity() == selected?.identity(),
                            multiSelect = request.isList,
                            checked = item.identity() in multiSelected,
                            onClick = {
                                selectedIdentity = item.identity()
                                if (request.isList) {
                                    multiSelected = if (item.identity() in multiSelected) {
                                        multiSelected - item.identity()
                                    } else {
                                        multiSelected + item.identity()
                                    }
                                }
                            },
                        )
                    }
                }
            }
            VerticalSeparator()
            PickerDetails(
                controller = controller,
                request = request,
                item = selected,
                modifier = Modifier.weight(0.4f).fillMaxHeight(),
            )
        }
        HorizontalSeparator()
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = when {
                    request.isList -> "选择后追加到引用列表（可多选）"
                    request.fieldType.isBlank() -> "选择引用目标"
                    else -> "字段类型：${request.fieldType}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { controller.closePicker() }) { Text("取消") }
            Button(
                enabled = chosen.isNotEmpty(),
                onClick = {
                    if (request.isList) {
                        controller.applyPickerItems(chosen)
                    } else {
                        chosen.firstOrNull()?.let { controller.applyPickerItem(it) }
                    }
                },
            ) { Text(if (request.isList && chosen.size > 1) "确认 ${chosen.size} 项" else "确认") }
        }
    }
}

@Composable
private fun ResourceGrid(
    controller: WorkspaceController,
    items: List<PickerItem>,
    selected: PickerItem?,
    multiSelect: Boolean,
    multiSelected: Set<String>,
    onSelect: (PickerItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        EmptyHint(
            title = "没有匹配的图片资源",
            hint = "可在“资源”页面导入本地图片。",
            modifier = modifier,
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        modifier = modifier.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        gridItems(items, key = { it.identity() }) { item ->
            val selectedNow = if (multiSelect) {
                item.identity() in multiSelected || item.identity() == selected?.identity()
            } else {
                item.identity() == selected?.identity()
            }
            Column(
                modifier = Modifier
                    .border(
                        width = if (selectedNow) 2.dp else 1.dp,
                        color = if (selectedNow) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = MaterialTheme.shapes.small,
                    )
                    .clickable { onSelect(item) }
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (multiSelect) {
                    Checkbox(
                        checked = item.identity() in multiSelected,
                        onCheckedChange = { onSelect(item) },
                        modifier = Modifier.size(24.dp),
                    )
                }
                FileImage(
                    path = controller.resourceFile(item),
                    modifier = Modifier.size(96.dp),
                    contentDescription = item.name,
                )
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = originLabel(item.origin),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PickerItemRow(
    controller: WorkspaceController,
    item: PickerItem,
    selected: Boolean,
    multiSelect: Boolean,
    checked: Boolean,
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
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (multiSelect) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onClick() },
                modifier = Modifier.size(26.dp),
            )
        }
        if (item.isResource) {
            FileImage(
                path = controller.resourceFile(item),
                modifier = Modifier.size(32.dp),
                contentDescription = item.name,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    item.type,
                    item.displayName?.takeIf { it.isNotBlank() },
                    item.uniqueId?.take(12),
                    originLabel(item.origin),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item.uniqueId?.let { uniqueId ->
            Text(
                text = uniqueId.take(8),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PickerDetails(
    controller: WorkspaceController,
    request: PickerRequest,
    item: PickerItem?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(12.dp)) {
        SectionTitle("候选详情")
        if (item == null) {
            Text(
                text = "选择左侧条目查看详情。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        if (item.isResource) {
            FileImage(
                path = controller.resourceFile(item),
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentDescription = item.name,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        KeyValue("名称", item.name, mono = true)
        KeyValue("类型", item.type)
        item.displayName?.takeIf { it.isNotBlank() }?.let { KeyValue("显示名", it) }
        item.uniqueId?.let { KeyValue("GUID", it, mono = true) }
        KeyValue("来源", originLabel(item.origin))
        KeyValue("引用种类", item.referenceKind.name)
        item.resourcePath?.let { KeyValue("相对路径", it, mono = true) }
        item.group?.let { KeyValue("分组", it) }
        val targetType = request.fieldType
        if (targetType.isNotBlank() && !item.isResource) {
            val display = controller.describeReferenceKey(targetType, item.name)
            Spacer(modifier = Modifier.height(6.dp))
            StatusChip(
                text = display.status,
                warning = display.warning,
                error = !display.resolved && !display.warning,
            )
        }
    }
}

private fun PickerItem.identity(): String =
    "${origin}|${type}|${name}|${uniqueId}|${resourcePath}"

private fun filterRecentItems(
    items: List<PickerItem>,
    request: PickerRequest,
    keyword: String,
    origin: EntryOrigin?,
    isCompatible: (PickerItem) -> Boolean,
): List<PickerItem> {
    val value = keyword.trim().lowercase()
    return items.filter { item ->
        val kindMatches = if (request.kind == PickerWindowKind.RESOURCE) {
            item.isResource
        } else {
            !item.isResource
        }
        kindMatches &&
            (origin == null || item.origin == origin) &&
            (value.isEmpty() ||
                item.name.lowercase().contains(value) ||
                item.type.lowercase().contains(value) ||
                item.displayName?.lowercase()?.contains(value) == true) &&
            (item.isResource || isCompatible(item))
    }
}

private fun originLabel(origin: EntryOrigin?): String = when (origin) {
    null -> "全部来源"
    EntryOrigin.PROJECT -> "工程"
    EntryOrigin.BASE_PACK -> "数据包"
    EntryOrigin.OTHER_MOD -> "其他 Mod"
}
