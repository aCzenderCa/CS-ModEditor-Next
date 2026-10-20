package org.zender.app.ui.workspace

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.zender.app.workspace.WorkspaceController
import org.zender.app.workspace.WorkspaceUiState
import org.zender.editor.localization.LocalizationEntry

@Composable
fun LocalizationPage(
    controller: WorkspaceController,
    state: WorkspaceUiState,
) {
    val project = state.project
    if (project == null) {
        EmptyHint(
            title = "尚未打开工程",
            hint = "打开 ML / MC 工程后维护本地化表。",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    var search by remember { mutableStateOf("") }
    var newKey by remember { mutableStateOf("") }
    var newEnglish by remember { mutableStateOf("") }
    var newChinese by remember { mutableStateOf("") }
    var showPreview by remember { mutableStateOf(false) }

    val entries = remember(project.workingTree.localization, search) {
        val keyword = search.trim()
        project.workingTree.localization.table.entries.filter { entry ->
            keyword.isEmpty() ||
                entry.key.contains(keyword, ignoreCase = true) ||
                entry.english.contains(keyword, ignoreCase = true) ||
                entry.chinese.contains(keyword, ignoreCase = true)
        }
    }
    val issues = state.validation.filter { it.code.startsWith("localization.") }

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
                placeholder = { Text("搜索 Key / 英文 / 中文", style = MaterialTheme.typography.bodySmall) },
            )
            Text(
                text = "${project.workingTree.localization.table.size} 个 Key · ${project.framework} · " +
                    project.format.defaultLocalizationFiles().joinToString(" / ") { it.relativePath },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { controller.validateProject() }) {
                Text("校验")
            }
            OutlinedButton(onClick = { showPreview = !showPreview }) {
                Text(if (showPreview) "隐藏格式预览" else "格式预览")
            }
            Button(onClick = { controller.saveProject() }) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("保存工程")
            }
        }
        HorizontalSeparator()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TableHeader("LocalizationKey", Modifier.weight(0.34f))
            TableHeader("English", Modifier.weight(0.28f))
            TableHeader("中文", Modifier.weight(0.28f))
            Spacer(modifier = Modifier.width(32.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(entries, key = { it.key }) { entry ->
                LocalizationRow(controller = controller, entry = entry)
                HorizontalSeparator()
            }
            if (entries.isEmpty()) {
                item {
                    EmptyHint(
                        title = if (search.isBlank()) "本地化表为空" else "没有匹配的 Key",
                        hint = "在下方添加 Key，或检查工程本地化文件。",
                    )
                }
            }
        }

        HorizontalSeparator()
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = newKey,
                onValueChange = { newKey = it },
                modifier = Modifier.weight(0.34f).height(42.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text("新增 Key", style = MaterialTheme.typography.bodySmall) },
            )
            OutlinedTextField(
                value = newEnglish,
                onValueChange = { newEnglish = it },
                modifier = Modifier.weight(0.28f).height(42.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text("English", style = MaterialTheme.typography.bodySmall) },
            )
            OutlinedTextField(
                value = newChinese,
                onValueChange = { newChinese = it },
                modifier = Modifier.weight(0.28f).height(42.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text("中文", style = MaterialTheme.typography.bodySmall) },
            )
            Button(
                enabled = newKey.isNotBlank(),
                onClick = {
                    if (controller.updateLocalizationEntry(newKey, newEnglish, newChinese)) {
                        newKey = ""
                        newEnglish = ""
                        newChinese = ""
                    }
                },
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("添加")
            }
        }

        if (issues.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "本地化校验：${issues.size} 个问题",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                issues.take(6).forEach { issue ->
                    Text(
                        text = "${issue.severity}: ${issue.message}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (showPreview) {
            HorizontalSeparator()
            val preview = remember(project.workingTree.localization, project.framework) {
                controller.localizationPreview()
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))
                    .padding(10.dp),
            ) {
                Text("框架格式预览", style = MaterialTheme.typography.labelMedium)
                LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
                    items(preview.entries.toList(), key = { it.key }) { (path, text) ->
                        Text(
                            text = path,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = text.take(2000),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalizationRow(controller: WorkspaceController, entry: LocalizationEntry) {
    var english by remember(entry.key, entry.english) { mutableStateOf(entry.english) }
    var chinese by remember(entry.key, entry.chinese) { mutableStateOf(entry.chinese) }

    fun commit() {
        if (english != entry.english || chinese != entry.chinese) {
            controller.updateLocalizationEntry(entry.key, english, chinese)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = entry.key,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.34f),
        )
        LocalizationCell(
            value = english,
            onValueChange = { english = it },
            onCommit = ::commit,
            modifier = Modifier.weight(0.28f),
        )
        LocalizationCell(
            value = chinese,
            onValueChange = { chinese = it },
            onCommit = ::commit,
            modifier = Modifier.weight(0.28f),
        )
        IconButton(
            onClick = { controller.removeLocalizationEntry(entry.key) },
            modifier = Modifier.size(30.dp),
        ) {
            Icon(Icons.Outlined.Delete, contentDescription = "删除 Key", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun LocalizationCell(
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var wasFocused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .height(40.dp)
            .onFocusChanged { focusState ->
                if (wasFocused && !focusState.isFocused) onCommit()
                wasFocused = focusState.isFocused
            },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit() }),
    )
}

@Composable
private fun TableHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
