package org.zender.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.zender.app.workspace.WorkspaceController
import org.zender.app.workspace.WorkspaceUiState
import org.zender.editor.framework.FrameworkId
import org.zender.editor.framework.ModMetadata
import org.zender.editor.framework.ModNamespace

/**
 * `ModInfo.json` / `ModMeta.json` 编辑页。
 *
 * 写回时保留原文件中的未知字段与顺序（[ModMetadata.raw]）。
 */
@Composable
fun MetadataPage(
    controller: WorkspaceController,
    state: WorkspaceUiState,
) {
    val project = state.project
    if (project == null) {
        EmptyHint(
            title = "尚未打开工程",
            hint = "打开或新建工程后可以编辑元数据。",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    val metadata = project.metadata
    val scope = rememberCoroutineScope()
    var resetNonce by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    var namespace by remember(metadata, resetNonce) { mutableStateOf(metadata.namespace) }
    var author by remember(metadata, resetNonce) { mutableStateOf(metadata.author) }
    var version by remember(metadata, resetNonce) { mutableStateOf(metadata.version) }
    var modLoaderVersion by remember(metadata, resetNonce) {
        mutableStateOf(metadata.modLoaderVersion.orEmpty())
    }
    var modEditorVersion by remember(metadata, resetNonce) {
        mutableStateOf(metadata.modEditorVersion.orEmpty())
    }
    var icon by remember(metadata, resetNonce) { mutableStateOf(metadata.icon.orEmpty()) }
    var pluginGuids by remember(metadata, resetNonce) {
        mutableStateOf(metadata.pluginGuids.joinToString("\n"))
    }

    val languages = remember(metadata) {
        (metadata.names.keys + metadata.descriptions.keys + listOf("SimpCn", "En"))
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareBy({ if (it == "SimpCn") 0 else if (it == "En") 1 else 2 }, { it }))
    }
    val nameValues = remember(metadata, languages, resetNonce) {
        mutableStateMapOf<String, String>().apply {
            languages.forEach { language -> put(language, metadata.names[language].orEmpty()) }
        }
    }
    val descriptionValues = remember(metadata, languages, resetNonce) {
        mutableStateMapOf<String, String>().apply {
            languages.forEach { language ->
                put(language, metadata.descriptions[language].orEmpty())
            }
        }
    }

    fun buildMetadata(): ModMetadata {
        val trimmedNamespace = namespace.trim()
        val names = LinkedHashMap<String, String>()
        if (project.framework == FrameworkId.MC) {
            languages.forEach { language ->
                val text = nameValues[language].orEmpty().trim()
                if (text.isNotEmpty()) names[language] = text
            }
            if (names.isEmpty()) names["SimpCn"] = trimmedNamespace
        } else {
            names[ModMetadata.DEFAULT_LANGUAGE] = trimmedNamespace
        }
        val descriptions = LinkedHashMap<String, String>()
        if (project.framework == FrameworkId.MC) {
            languages.forEach { language ->
                val text = descriptionValues[language].orEmpty().trim()
                if (text.isNotEmpty()) descriptions[language] = text
            }
        } else {
            descriptions.putAll(metadata.descriptions)
        }
        return metadata.copy(
            namespace = trimmedNamespace,
            names = names,
            descriptions = descriptions,
            author = author.trim(),
            version = version.trim(),
            modLoaderVersion = if (project.framework == FrameworkId.ML) {
                modLoaderVersion.trim().takeIf { it.isNotEmpty() }
            } else {
                metadata.modLoaderVersion
            },
            modEditorVersion = if (project.framework == FrameworkId.ML) {
                modEditorVersion.trim().takeIf { it.isNotEmpty() }
            } else {
                metadata.modEditorVersion
            },
            icon = if (project.framework == FrameworkId.MC) {
                icon.trim()
            } else {
                metadata.icon
            },
            pluginGuids = if (project.framework == FrameworkId.MC) {
                pluginGuids.lines().map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                metadata.pluginGuids
            },
        )
    }

    fun apply(): Boolean {
        val updated = buildMetadata()
        val ok = controller.updateMetadata(updated)
        error = if (ok) null else controller.state.value.status
        return ok
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("工程元数据", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "${project.format.metadataFileName} · ${project.framework}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "修改会进入当前工程；点击顶部“保存”或下方“应用并保存”写回磁盘。" +
                "文件中未识别的字段会原样保留。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle("基本信息")
        OutlinedTextField(
            value = namespace,
            onValueChange = { namespace = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            label = {
                Text(if (project.framework == FrameworkId.MC) "Namespace" else "Mod 名称（Name）")
            },
            isError = namespace.isBlank() ||
                (project.framework == FrameworkId.MC && !ModNamespace.isValid(namespace)),
            supportingText = {
                Text(
                    text = if (project.framework == FrameworkId.MC) {
                        "数据键前缀，不能包含 : @ |。修改后已有引用与本地化 Key 不会自动迁移。"
                    } else {
                        "同时作为运行时前缀与本地化 Key 前缀。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = author,
                onValueChange = { author = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                label = { Text("作者") },
            )
            OutlinedTextField(
                value = version,
                onValueChange = { version = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                label = { Text("版本") },
            )
        }

        if (project.framework == FrameworkId.MC) {
            SectionTitle("名称与描述（按语言）")
            languages.forEach { language ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = nameValues[language].orEmpty(),
                        onValueChange = { nameValues[language] = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        label = { Text("Name · $language") },
                    )
                    OutlinedTextField(
                        value = descriptionValues[language].orEmpty(),
                        onValueChange = { descriptionValues[language] = it },
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        label = { Text("Description · $language") },
                    )
                }
            }
            SectionTitle("其他")
            OutlinedTextField(
                value = icon,
                onValueChange = { icon = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                label = { Text("Icon 路径") },
            )
            OutlinedTextField(
                value = pluginGuids,
                onValueChange = { pluginGuids = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                minLines = 2,
                maxLines = 6,
                label = { Text("PluginGuid（每行一个）") },
            )
        } else {
            SectionTitle("加载器")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = modLoaderVersion,
                    onValueChange = { modLoaderVersion = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    label = { Text("ModLoader 版本") },
                )
                OutlinedTextField(
                    value = modEditorVersion,
                    onValueChange = { modEditorVersion = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    label = { Text("ModEditor 版本") },
                )
            }
        }

        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { apply() }) { Text("应用") }
            OutlinedButton(
                onClick = {
                    if (apply()) {
                        scope.launch { controller.saveProject() }
                    }
                },
            ) { Text("应用并保存") }
            OutlinedButton(onClick = { resetNonce += 1 }) { Text("重置") }
        }
    }
}
