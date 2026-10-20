package org.zender.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.zender.app.workspace.DocumentTarget
import org.zender.app.workspace.FieldViewOptions
import org.zender.app.workspace.WorkspaceController
import org.zender.app.workspace.WorkspaceUiState
import org.zender.editor.document.DocumentId
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.document.JsonPathSegment
import org.zender.editor.document.jsonBoolean
import org.zender.editor.document.jsonString
import org.zender.editor.document.readPath
import org.zender.editor.edit.ValueCodec
import org.zender.editor.edit.WarpFields
import org.zender.editor.edit.WarpType
import org.zender.editor.field.FieldEditorInfo
import org.zender.editor.field.FieldRow
import org.zender.editor.field.FieldProjectionOptions
import org.zender.editor.schema.DefaultValueResolver
import org.zender.editor.schema.FieldKind
import org.zender.editor.selection.FieldRef

private data class VisibleField(val row: FieldRow, val depth: Int)

private const val LOCALIZED_STRING_TYPE = "LocalizedString"

@Composable
fun FieldEditorView(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    documentId: DocumentId,
    target: DocumentTarget,
) {
    val sessionDocument = state.session.documents[documentId] ?: return
    val pack = state.pack
    val schemaType = target.schemaType
    if (pack == null || schemaType == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyHint(
                title = "该文件没有可用的 Schema",
                hint = "可以在原始文件 / 差异视图之间切换；原始 JSON 会完整保留未知字段。",
            )
        }
        return
    }

    val options = state.fieldOptions
    val favoriteType = schemaType
    val favorites = remember(state.session.favorites, favoriteType) {
        state.session.favorites.filter { it.type == favoriteType }.toSet()
    }
    val includeDefaults = if (target.isPatch) false else options.showDefaults
    val projection = remember(
        sessionDocument.document,
        pack,
        schemaType,
        includeDefaults,
        options.showUnknown,
        favorites,
    ) {
        pack.fieldProjection.project(
            document = sessionDocument.document,
            typeName = schemaType,
            options = FieldProjectionOptions(
                includeDefaults = includeDefaults,
                includeUnknown = options.showUnknown,
                includeReadOnly = true,
                favorites = favorites,
                favoriteTypeName = favoriteType,
            ),
        )
    }

    val expanded = remember(documentId) { mutableStateMapOf<String, Boolean>() }
    // 收藏区使用独立展开状态，避免继承主树里已经展开的节点，收藏后默认保持收起。
    val favoriteExpanded = remember(documentId) { mutableStateMapOf<String, Boolean>() }
    val favoriteRows = remember(projection.rows) {
        projection.rows.flatMap { it.walk().toList() }.filter { it.isFavorite }
    }
    val favoriteVisible = remember(favoriteRows, favoriteExpanded.toMap()) {
        favoriteVisibleRows(favoriteRows, favoriteExpanded)
    }
    val filtered = remember(projection.rows, options.search) {
        filterRows(projection.rows, options.search)
    }
    val treeRows = if (!options.onlyFavorites && favoriteRows.isNotEmpty()) {
        removeFavoriteRows(filtered)
    } else {
        filtered
    }
    val rows: List<VisibleField> = if (options.onlyFavorites) {
        favoriteVisible
    } else {
        flattenFields(treeRows, expanded, forceExpand = options.search.isNotBlank())
    }

    Column(modifier = Modifier.fillMaxSize()) {
        FieldToolbar(
            controller = controller,
            state = state,
            options = options,
            target = target,
            projectionIssues = projection.issues.map { it.message },
            allRows = projection.rows,
            expanded = expanded,
        )
        HorizontalSeparator()
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (!options.onlyFavorites && favoriteRows.isNotEmpty()) {
                item(key = "favorites-title") { SectionTitle("收藏字段") }
                items(favoriteVisible, key = { "fav:" + it.row.path.format() + "@" + it.depth }) { entry ->
                    FieldRowItem(
                        controller = controller,
                        state = state,
                        documentId = documentId,
                        target = target,
                        rootType = schemaType,
                        entry = entry,
                        expanded = favoriteExpanded,
                        displayPath = entry.depth == 0,
                    )
                }
                item(key = "favorites-separator") {
                    Box(modifier = Modifier.padding(vertical = 6.dp)) { HorizontalSeparator() }
                }
            }
            items(rows, key = { it.row.path.format() + "@" + it.depth }) { entry ->
                FieldRowItem(
                    controller = controller,
                    state = state,
                    documentId = documentId,
                    target = target,
                    rootType = schemaType,
                    entry = entry,
                    expanded = expanded,
                )
            }
        }
    }
}

@Composable
private fun FieldToolbar(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    options: FieldViewOptions,
    target: DocumentTarget,
    projectionIssues: List<String>,
    allRows: List<FieldRow>,
    expanded: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
) {
    var search by remember { mutableStateOf(options.search) }
    LaunchedEffect(options.search) {
        if (search != options.search) search = options.search
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = {
                    search = it
                    controller.setFieldOptions(options.copy(search = it))
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                placeholder = { Text("搜索字段名 / 路径 / 值", style = MaterialTheme.typography.bodySmall) },
            )
            TextButton(onClick = {
                val paths = allRows.flatMap { row -> row.walk().map { it.path.format() } }.toSet()
                if (expanded.values.any { it }) {
                    expanded.clear()
                } else {
                    paths.forEach { expanded[it] = true }
                }
            }) {
                Text(if (expanded.values.any { it }) "折叠" else "展开")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!target.isPatch) {
                Checkbox(
                    checked = options.showDefaults,
                    onCheckedChange = { controller.setFieldOptions(options.copy(showDefaults = it)) },
                    modifier = Modifier.size(26.dp),
                )
                Text("默认值", style = MaterialTheme.typography.labelSmall)
            } else {
                StatusChip("补丁：隐藏模板字段")
            }
            Spacer(modifier = Modifier.width(4.dp))
            Checkbox(
                checked = options.showUnknown,
                onCheckedChange = { controller.setFieldOptions(options.copy(showUnknown = it)) },
                modifier = Modifier.size(26.dp),
            )
            Text("未知字段", style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.width(4.dp))
            Checkbox(
                checked = options.onlyFavorites,
                onCheckedChange = { controller.setFieldOptions(options.copy(onlyFavorites = it)) },
                modifier = Modifier.size(26.dp),
            )
            Text("只看收藏", style = MaterialTheme.typography.labelSmall)
        }
        if (projectionIssues.isNotEmpty()) {
            Text(
                text = projectionIssues.first(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}


@Composable
private fun FieldRowItem(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    documentId: DocumentId,
    target: DocumentTarget,
    rootType: String,
    entry: VisibleField,
    expanded: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    displayPath: Boolean = false,
) {
    val row = entry.row
    val info = remember(rootType, row.path, state.pack) {
        controller.fieldInfo(documentId, row.path)
    }
    val document = state.session.documents[documentId]?.document
    val localizedString = info?.kind == FieldKind.OBJECT &&
        (info.typeName == LOCALIZED_STRING_TYPE || info.schemaField.typeName == LOCALIZED_STRING_TYPE)
    val hasChildren = row.children.isNotEmpty() && !localizedString
    val isExpanded = expanded[row.path.format()] ?: false
    val editable = !target.readOnly && !row.isReadOnly
    val selected = state.session.selection.currentField == FieldRef(documentId, row.path)
    val pathText = row.path.format()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else Color.Transparent,
            )
            .clickable { controller.selectField(documentId, row.path) }
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.widthIn(min = 190.dp).weight(0.44f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.width((entry.depth * 12).dp))
                if (hasChildren) {
                    IconButton(
                        onClick = { expanded[pathText] = !isExpanded },
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(20.dp))
                }
                if (!row.isUnknown) {
                    IconButton(
                        onClick = { controller.toggleFavorite(rootType, pathText) },
                        modifier = Modifier.size(22.dp),
                    ) {
                        Icon(
                            imageVector = if (row.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (row.isFavorite) "取消收藏" else "收藏字段",
                            modifier = Modifier.size(14.dp),
                            tint = if (row.isFavorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(22.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = info?.schemaField?.displayName() ?: row.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = typeLabel(row, info),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (displayPath) {
                        Text(
                            text = row.path.format(),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(0.56f)) {
                when {
                    row.isReadOnly -> ReadonlyValue(row.value, "只读")
                    !editable && target.readOnly -> ReadonlyValue(row.value, "数据包只读")
                    row.isUnknown -> UnknownValue(row.value)
                    info == null -> ReadonlyValue(row.value, "无 Schema")
                    info.isReferenceList -> ReferenceListEditor(
                        controller = controller,
                        state = state,
                        documentId = documentId,
                        row = row,
                        info = info,
                        editable = editable,
                        document = document,
                    )

                    info.isReference -> ReferenceScalarEditor(
                        controller = controller,
                        state = state,
                        documentId = documentId,
                        row = row,
                        info = info,
                        editable = editable,
                    )

                    localizedString -> LocalizedStringEditor(
                        controller = controller,
                        state = state,
                        documentId = documentId,
                        row = row,
                        editable = editable,
                    )

                    info.kind == FieldKind.OBJECT -> ObjectValue(row)
                    info.kind == FieldKind.LIST -> ListValue(
                        controller = controller,
                        state = state,
                        documentId = documentId,
                        row = row,
                        info = info,
                        editable = editable,
                    )

                    info.kind == FieldKind.BOOLEAN -> BooleanEditor(
                        controller = controller,
                        documentId = documentId,
                        row = row,
                        info = info,
                        editable = editable,
                    )

                    info.kind == FieldKind.ENUM -> EnumEditor(
                        controller = controller,
                        documentId = documentId,
                        row = row,
                        info = info,
                        editable = editable,
                    )

                    info.kind == FieldKind.INTEGER ||
                        info.kind == FieldKind.FLOAT ||
                        info.kind == FieldKind.STRING -> ScalarTextEditor(
                        controller = controller,
                        documentId = documentId,
                        row = row,
                        info = info,
                        editable = editable,
                    )

                    else -> ReadonlyValue(row.value, info.kind.name)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FieldFlags(row)
                    if (row.name.startsWith("[") && row.path.last is JsonPathSegment.Index) {
                        ListElementActions(
                            controller = controller,
                            state = state,
                            documentId = documentId,
                            row = row,
                            editable = editable,
                        )
                    }
                    val defaultValue = row.defaultValue
                    if (editable && !localizedString && defaultValue != null && !row.isDefault) {
                        TextButton(
                            onClick = {
                                controller.resetField(documentId, row.path, defaultValue)
                            },
                        ) {
                            Text("重置", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                info?.schemaField?.note()?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldFlags(row: FieldRow) {
    if (row.isDefault && row.value != null) StatusChip("默认")
    if (row.isModified) StatusChip("已修改")
    if (row.isUnknown) StatusChip("未知", warning = true)
    if (row.isReadOnly) StatusChip("只读", warning = true)
    if (row.isUnresolvedReference) StatusChip("未解析", error = true)
}

private fun typeLabel(row: FieldRow, info: FieldEditorInfo?): String {
    val base = info?.typeName ?: row.typeName ?: "未知"
    val element = info?.schemaField?.elementTypeName
    return when {
        info?.kind == FieldKind.LIST && element != null -> "List<$element>"
        info?.kind == FieldKind.LIST -> "List"
        else -> base
    }
}

@Composable
private fun ObjectValue(row: FieldRow) {
    Text(
        text = "对象 · ${row.childCount} 个字段",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ListValue(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    documentId: DocumentId,
    row: FieldRow,
    info: FieldEditorInfo,
    editable: Boolean,
) {
    val elementKind = info.schemaField.elementKind ?: FieldKind.UNKNOWN
    val template = (info.schemaField.templateValue as? JsonNode.ArrayNode)?.elements?.firstOrNull()
    val elementType = info.schemaField.elementTypeName
    val enumValues = remember(info, state.pack) {
        if (elementKind == FieldKind.ENUM && elementType != null) {
            state.pack?.schemaRepository?.enumValues(elementType).orEmpty()
        } else {
            emptyList()
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "列表 · ${row.childCount} 项",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (editable) {
            TextButton(onClick = {
                val element = template ?: ValueCodec.defaultValue(elementKind, enumValues)
                controller.addInlineElement(documentId, row.path, element)
            }) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(2.dp))
                Text("元素", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun BooleanEditor(
    controller: WorkspaceController,
    documentId: DocumentId,
    row: FieldRow,
    info: FieldEditorInfo,
    editable: Boolean,
) {
    val value = (row.value as? JsonNode.BooleanNode)?.value ?: false
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(
            checked = value,
            enabled = editable,
            onCheckedChange = {
                controller.setFieldNode(documentId, row.path, jsonBoolean(it), info)
            },
        )
        Text(if (value) "true" else "false", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ScalarTextEditor(
    controller: WorkspaceController,
    documentId: DocumentId,
    row: FieldRow,
    info: FieldEditorInfo,
    editable: Boolean,
) {
    val currentText = ValueCodec.format(row.value)
    var text by remember(row.path.format(), row.value) { mutableStateOf(currentText) }
    var wasFocused by remember { mutableStateOf(false) }

    fun commit() {
        if (text == currentText) return
        if (!controller.setFieldText(documentId, row.path, text, info)) {
            text = currentText
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                if (wasFocused && !focusState.isFocused) commit()
                wasFocused = focusState.isFocused
            },
        enabled = editable,
        singleLine = info.kind != FieldKind.STRING,
        textStyle = MaterialTheme.typography.bodySmall,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        isError = text != currentText && text.isBlank() && info.kind != FieldKind.STRING,
    )
}

@Composable
private fun EnumEditor(
    controller: WorkspaceController,
    documentId: DocumentId,
    row: FieldRow,
    info: FieldEditorInfo,
    editable: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    val current = info.enumValues.firstOrNull {
        DefaultValueResolver.semanticallyEquals(it.value, row.value)
    }
    Box {
        OutlinedButton(
            onClick = { open = true },
            enabled = editable,
        ) {
            Text(
                text = current?.name ?: ValueCodec.display(row.value),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            info.enumValues.forEach { value ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "${value.name} = ${ValueCodec.format(value.value)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    onClick = {
                        open = false
                        controller.setFieldNode(documentId, row.path, value.value, info)
                    },
                )
            }
        }
    }
}

@Composable
private fun LocalizedStringEditor(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    documentId: DocumentId,
    row: FieldRow,
    editable: Boolean,
) {
    val document = state.session.documents[documentId]?.document ?: return
    val node = document.root.readPath(row.path) as? JsonNode.ObjectNode ?: return
    val parentId = (node.get("ParentObjectID") as? JsonNode.StringNode)?.value.orEmpty()
    val key = (node.get("LocalizationKey") as? JsonNode.StringNode)?.value.orEmpty()
    val defaultText = (node.get("DefaultText") as? JsonNode.StringNode)?.value.orEmpty()

    val entry = remember(state.project?.workingTree?.localization, state.pack, key) {
        controller.localizedEntry(key)
    }
    val english = entry?.english.orEmpty()
    val chinese = entry?.chinese.orEmpty()
    var expanded by remember(documentId, row.path.format()) { mutableStateOf(false) }
    var englishText by remember(key, english, defaultText) {
        mutableStateOf(english.ifBlank { defaultText })
    }
    var chineseText by remember(key, chinese) { mutableStateOf(chinese) }

    fun commitLocalized() {
        if (!editable || key.isBlank()) return
        if (englishText == english && chineseText == chinese) return
        controller.updateLocalizedText(key, englishText, chineseText)
    }

    fun commitChild(name: String, value: String) {
        if (!editable) return
        val path = row.path.child(name)
        val info = controller.fieldInfo(documentId, path)
        if (info != null) {
            controller.setFieldText(documentId, path, value, info)
        } else {
            controller.setFieldNode(documentId, path, jsonString(value))
        }
    }

    val languages = remember(state.settings.localizationInputLanguages) {
        state.settings.localizationInputLanguages
    }
    val showChinese = languages.any {
        it.equals("SimpCn", true) || it.equals("Chinese", true) || it == "中文"
    }
    val showEnglish = languages.any {
        it.equals("En", true) || it.equals("English", true)
    }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (!expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (showChinese) {
                    LocalizedTextInput(
                        label = "中文",
                        value = chineseText,
                        onValueChange = { chineseText = it },
                        onCommit = ::commitLocalized,
                        enabled = editable && key.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (showEnglish) {
                    LocalizedTextInput(
                        label = "English",
                        value = englishText,
                        onValueChange = { englishText = it },
                        onCommit = ::commitLocalized,
                        enabled = editable && key.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (key.isBlank()) {
                Text(
                    text = "LocalizationKey 为空：展开后填写 Key 才能编辑多语言文本",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            InlineStringField(
                label = "ParentObjectID (PID)",
                value = parentId,
                enabled = editable,
                onCommit = { commitChild("ParentObjectID", it) },
            )
            InlineStringField(
                label = "LocalizationKey",
                value = key,
                enabled = editable,
                onCommit = { commitChild("LocalizationKey", it) },
            )
            InlineStringField(
                label = "DefaultText（回退文本）",
                value = defaultText,
                enabled = editable,
                onCommit = { commitChild("DefaultText", it) },
            )
            if (showChinese) {
                LocalizedTextInput(
                    label = "中文",
                    value = chineseText,
                    onValueChange = { chineseText = it },
                    onCommit = ::commitLocalized,
                    enabled = editable && key.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (showEnglish) {
                LocalizedTextInput(
                    label = "English",
                    value = englishText,
                    onValueChange = { englishText = it },
                    onCommit = ::commitLocalized,
                    enabled = editable && key.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!showChinese && !showEnglish) {
                Text(
                    text = "未选择本地化输入语言，请在设置中启用中文或 English。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                text = if (expanded) "收起语言/Key/PID" else "展开语言/Key/PID",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun LocalizedTextInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var wasFocused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.onFocusChanged { focusState ->
            if (wasFocused && !focusState.isFocused) onCommit()
            wasFocused = focusState.isFocused
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        enabled = enabled,
        singleLine = false,
        maxLines = 4,
        textStyle = MaterialTheme.typography.bodySmall,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit() }),
    )
}

@Composable
private fun InlineStringField(
    label: String,
    value: String,
    enabled: Boolean,
    onCommit: (String) -> Unit,
) {
    var text by remember(label, value) { mutableStateOf(value) }
    var wasFocused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                if (wasFocused && !focusState.isFocused) onCommit(text)
                wasFocused = focusState.isFocused
            },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit(text) }),
    )
}

@Composable
private fun ReferenceScalarEditor(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    documentId: DocumentId,
    row: FieldRow,
    info: FieldEditorInfo,
    editable: Boolean,
) {
    val fieldType = info.typeName ?: info.schemaField.typeName
    val display = remember(
        state.session.documents[documentId]?.document,
        fieldType,
        row.path,
        state.project,
        state.pack,
    ) {
        controller.referenceDisplay(documentId, row.path, fieldType)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = display.key ?: "（未设置）",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusChip(
                text = display.status,
                warning = display.warning,
                error = !display.resolved && display.key != null && !display.warning,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.settings.showWarpType) {
                Text(
                    text = "WarpType=${display.warpType?.let { "${it.value} ${it.name}" } ?: "缺失"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (editable) {
                TextButton(onClick = {
                    controller.openPicker(documentId, row.path, fieldType, isList = false)
                }) {
                    Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("选择", style = MaterialTheme.typography.labelSmall)
                }
                if (display.key != null) {
                    TextButton(onClick = {
                        controller.clearReference(documentId, row.path)
                    }) {
                        Icon(Icons.Outlined.Clear, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text("清除", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceListEditor(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    documentId: DocumentId,
    row: FieldRow,
    info: FieldEditorInfo,
    editable: Boolean,
    document: org.zender.editor.document.JsonDocument?,
) {
    val elementType = info.schemaField.elementTypeName ?: info.typeName ?: info.schemaField.typeName
    val dataPath = WarpFields.dataPath(row.path)
    val typePath = WarpFields.typePath(row.path)
    val data = document?.root?.readPath(dataPath) as? JsonNode.ArrayNode
    val keys = data?.elements.orEmpty().mapNotNull { (it as? JsonNode.StringNode)?.value }
    val warpType = WarpType.fromNode(document?.root?.readPath(typePath))

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "引用列表 · ${keys.size} 项",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (state.settings.showWarpType) {
                Text(
                    text = "WarpType=${warpType?.let { "${it.value} ${it.name}" } ?: "缺失"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (editable) {
                TextButton(onClick = {
                    controller.openPicker(documentId, row.path, elementType, isList = true)
                }) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("引用", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        keys.forEachIndexed { index, key ->
            val display = remember(state.project, state.pack, key, elementType) {
                controller.describeReferenceKey(elementType, key)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "[$index]",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = key,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(
                    text = display.status,
                    warning = display.warning,
                    error = !display.resolved && !display.warning,
                )
                if (editable) {
                    IconButton(
                        onClick = { controller.moveReference(documentId, row.path, index, index - 1) },
                        enabled = index > 0,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(Icons.Outlined.ArrowUpward, contentDescription = "上移", modifier = Modifier.size(15.dp))
                    }
                    IconButton(
                        onClick = { controller.moveReference(documentId, row.path, index, index + 1) },
                        enabled = index < keys.lastIndex,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(Icons.Outlined.ArrowDownward, contentDescription = "下移", modifier = Modifier.size(15.dp))
                    }
                    IconButton(
                        onClick = { controller.removeReference(documentId, row.path, index) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除", modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
        if (keys.isEmpty()) {
            Text(
                text = "没有引用；点击“引用”从工程或数据包中选择。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ListElementActions(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    documentId: DocumentId,
    row: FieldRow,
    editable: Boolean,
) {
    if (!editable) return
    val index = (row.path.last as? JsonPathSegment.Index)?.index ?: return
    val parent = row.path.parent() ?: return
    val parentSize = (state.session.documents[documentId]?.document?.root?.readPath(parent)
        as? JsonNode.ArrayNode)?.elements?.size ?: 0
    IconButton(
        onClick = { controller.moveElement(documentId, parent, index, index - 1) },
        enabled = index > 0,
        modifier = Modifier.size(22.dp),
    ) {
        Icon(Icons.Outlined.ArrowUpward, contentDescription = "上移", modifier = Modifier.size(14.dp))
    }
    IconButton(
        onClick = { controller.moveElement(documentId, parent, index, index + 1) },
        enabled = index < parentSize - 1,
        modifier = Modifier.size(22.dp),
    ) {
        Icon(Icons.Outlined.ArrowDownward, contentDescription = "下移", modifier = Modifier.size(14.dp))
    }
    IconButton(
        onClick = { controller.removeElement(documentId, parent, index) },
        modifier = Modifier.size(22.dp),
    ) {
        Icon(Icons.Outlined.Delete, contentDescription = "删除", modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun ReadonlyValue(value: JsonNode?, label: String) {
    Column {
        Text(
            text = ValueCodec.display(value),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnknownValue(value: JsonNode?) {
    Column {
        Text(
            text = ValueCodec.display(value),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "未知字段会原样保留；可切换到原始视图编辑",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun flattenFields(
    rows: List<FieldRow>,
    expanded: Map<String, Boolean>,
    depth: Int = 0,
    forceExpand: Boolean = false,
): List<VisibleField> {
    val result = mutableListOf<VisibleField>()
    rows.forEach { row ->
        result += VisibleField(row, depth)
        val isExpanded = expanded[row.path.format()] ?: forceExpand
        if (isExpanded && row.children.isNotEmpty() && row.typeName != LOCALIZED_STRING_TYPE) {
            result += flattenFields(row.children, expanded, depth + 1, forceExpand)
        }
    }
    return result
}

/** 收藏项整体移到顶部，主树中不再保留它或它的子字段。 */
private fun removeFavoriteRows(rows: List<FieldRow>): List<FieldRow> =
    rows.mapNotNull { row ->
        if (row.isFavorite) {
            null
        } else {
            row.copy(children = removeFavoriteRows(row.children))
        }
    }

private fun favoriteVisibleRows(
    favorites: List<FieldRow>,
    expanded: Map<String, Boolean>,
): List<VisibleField> {
    val result = mutableListOf<VisibleField>()
    favorites.forEach { row ->
        result += VisibleField(row, 0)
        if (expanded[row.path.format()] == true && row.children.isNotEmpty()) {
            result += flattenFields(row.children, expanded, depth = 1)
        }
    }
    return result
}

private fun filterRows(rows: List<FieldRow>, query: String): List<FieldRow> {
    val text = query.trim()
    if (text.isEmpty()) return rows
    return rows.mapNotNull { row ->
        val children = filterRows(row.children, text)
        val match = row.name.contains(text, ignoreCase = true) ||
            row.path.format().contains(text, ignoreCase = true) ||
            row.typeName?.contains(text, ignoreCase = true) == true ||
            ValueCodec.format(row.value).contains(text, ignoreCase = true)
        if (match || children.isNotEmpty()) row.copy(children = children) else null
    }
}
