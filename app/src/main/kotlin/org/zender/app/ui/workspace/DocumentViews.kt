package org.zender.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.zender.app.workspace.DocumentTarget
import org.zender.app.workspace.WorkspaceController
import org.zender.app.workspace.WorkspaceUiState
import org.zender.editor.diff.DiffKind
import org.zender.editor.diff.FieldDiff
import org.zender.editor.diff.TextDiff
import org.zender.editor.document.DocumentId
import org.zender.editor.document.JsonFormat

@Composable
fun RawJsonEditorView(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    documentId: DocumentId,
    target: DocumentTarget,
) {
    val raw = state.rawDocuments[documentId] ?: return
    var text by remember(documentId) { mutableStateOf(raw.text) }
    var search by remember(documentId) { mutableStateOf("") }

    LaunchedEffect(raw.text) {
        if (text != raw.text) text = raw.text
    }

    val lineNumbers = remember(text) {
        val count = if (text.isEmpty()) 1 else text.count { it == '\n' } + 1
        (1..count).joinToString("\n")
    }
    val matches = remember(text, search) {
        val keyword = search.trim()
        if (keyword.isEmpty()) emptyList() else {
            text.lineSequence().mapIndexedNotNull { index, line ->
                if (line.contains(keyword, ignoreCase = true)) index + 1 else null
            }.take(200).toList()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.width(240.dp).height(42.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.height(16.dp))
                },
                placeholder = { Text("搜索原文", style = MaterialTheme.typography.bodySmall) },
            )
            Text(
                text = if (search.isBlank()) {
                    "${text.lines().size} 行"
                } else {
                    "匹配 ${matches.size} 行" + matches.take(8).joinToString("、", prefix = "：")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                enabled = !target.readOnly,
                onClick = { controller.formatRaw(documentId, pretty = true) },
            ) { Text("格式化") }
            TextButton(
                enabled = !target.readOnly,
                onClick = { controller.formatRaw(documentId, pretty = false) },
            ) { Text("压缩") }
            TextButton(
                enabled = !target.readOnly,
                onClick = { controller.revertRaw(documentId) },
            ) { Text("还原") }
        }
        if (raw.error != null) {
            Text(
                text = "JSON 错误：${raw.error?.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        } else if (raw.issues.isNotEmpty()) {
            Text(
                text = "解析提示：${raw.issues.first().message}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        HorizontalSeparator()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = lineNumbers,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    ),
                    modifier = Modifier.padding(end = 10.dp),
                )
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        if (!target.readOnly) controller.updateRawText(documentId, it)
                    },
                    readOnly = target.readOnly,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.width(900.dp),
                )
            }
        }
    }
}

private enum class DiffMode { LINE, FIELD }

@Composable
fun DiffView(
    state: WorkspaceUiState,
    documentId: DocumentId,
) {
    val original = state.originalDocuments[documentId] ?: return
    val current = state.session.documents[documentId]?.document ?: return
    var mode by remember(documentId) { mutableStateOf(DiffMode.LINE) }
    val lineDiff = remember(original, current) {
        TextDiff.diff(
            original.toJsonText(JsonFormat.Pretty()),
            current.toJsonText(JsonFormat.Pretty()),
        )
    }
    val fieldDiff = remember(original, current) {
        FieldDiff.diff(original.root, current.root)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { mode = DiffMode.LINE }) {
                Text(if (mode == DiffMode.LINE) "● 行级差异" else "行级差异")
            }
            TextButton(onClick = { mode = DiffMode.FIELD }) {
                Text(if (mode == DiffMode.FIELD) "● 字段级差异" else "字段级差异")
            }
            Spacer(modifier = Modifier.weight(1f))
            val added = lineDiff.count { it.kind == DiffKind.ADDED }
            val removed = lineDiff.count { it.kind == DiffKind.REMOVED }
            Text(
                text = "新增 $added 行 · 删除 $removed 行 · 字段变化 ${fieldDiff.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalSeparator()
        if (mode == DiffMode.LINE) {
            if (lineDiff.all { it.kind == DiffKind.UNCHANGED }) {
                EmptyHint(title = "与打开时一致", modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(lineDiff) { line ->
                        val background = when (line.kind) {
                            DiffKind.ADDED -> Color(0x3322A06B)
                            DiffKind.REMOVED -> Color(0x33D14343)
                            DiffKind.UNCHANGED -> Color.Transparent
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(background)
                                .padding(horizontal = 8.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = (line.oldLine?.toString() ?: "").padStart(5),
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = (line.newLine?.toString() ?: "").padStart(5),
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = when (line.kind) {
                                    DiffKind.ADDED -> "+ "
                                    DiffKind.REMOVED -> "- "
                                    DiffKind.UNCHANGED -> "  "
                                } + line.text,
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                            )
                        }
                    }
                }
            }
        } else {
            if (fieldDiff.isEmpty()) {
                EmptyHint(title = "没有字段变化", modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(fieldDiff) { change ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = change.path.ifEmpty { "$" },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(0.35f),
                            )
                            Text(
                                text = "${org.zender.editor.edit.ValueCodec.display(change.oldValue)}  →  " +
                                    org.zender.editor.edit.ValueCodec.display(change.newValue),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(0.65f),
                            )
                        }
                    }
                }
            }
        }
    }
}
