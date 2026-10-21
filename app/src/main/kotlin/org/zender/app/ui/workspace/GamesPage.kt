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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.zender.app.platform.FilePicker
import org.zender.app.workspace.PackDirectoryAnalysis
import org.zender.app.workspace.WorkspaceController
import org.zender.app.workspace.WorkspaceUiState
import org.zender.editor.framework.FrameworkId
import org.zender.editor.pack.PackInfo
import org.zender.editor.settings.ExtraPackConfig
import org.zender.editor.settings.MainPackConfig
import java.nio.file.Path

/**
 * 数据包配置页。
 *
 * - 主包：CSTI / CSFF，本地目录由用户自行选择；
 * - 额外包：用户导入，并配置与哪个主包一起加载；
 * - 应用组合后，主包与启用的额外包会合并成一棵只读数据包，并关联到当前工程。
 */
@Composable
fun GamesPage(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    filePicker: FilePicker,
) {
    val scope = rememberCoroutineScope()
    val mainPacks = state.settings.mainPacks
    val extraPacks = state.settings.extraPacks
    var refreshNonce by remember { mutableStateOf(0) }
    var targetFramework by remember(state.projectRoot, state.project?.framework) {
        mutableStateOf(state.project?.framework ?: FrameworkId.MC)
    }
    var editingId by remember { mutableStateOf<String?>(null) }
    var extraName by remember { mutableStateOf("") }
    var extraPath by remember { mutableStateOf("") }
    var extraMainKinds by remember { mutableStateOf(setOf("CSTI")) }
    var extraFramework by remember { mutableStateOf(FrameworkId.MC) }
    var extraAnalysis by remember { mutableStateOf<PackDirectoryAnalysis?>(null) }
    var autoName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val loadInfos = remember(mainPacks, extraPacks, refreshNonce) {
        val result = HashMap<String, PackInfo?>()
        mainPacks.forEach { config ->
            result["main:${config.kind}"] = config.path?.let {
                runCatching { controller.packInfoAt(Path.of(it)) }.getOrNull()
            }
        }
        extraPacks.forEach { config ->
            val framework = runCatching { FrameworkId.valueOf(config.framework) }.getOrNull()
            result["extra:${config.id}"] = framework?.let {
                runCatching { controller.extraPackInfo(Path.of(config.path), it) }.getOrNull()
            }
        }
        result
    }
    val currentRoot = state.packRoot?.toString()?.let(::normalizeGamePath)
    val activeKind = state.pack?.info?.normalizedName

    fun analyzeExtra(showErrors: Boolean) {
        val raw = extraPath.trim()
        if (raw.isEmpty()) {
            extraAnalysis = null
            return
        }
        val target = runCatching { Path.of(raw) }.getOrNull()
        if (target == null) {
            if (showErrors) {
                extraAnalysis = PackDirectoryAnalysis(path = raw, error = "路径不合法")
            }
            return
        }
        val analysis = controller.analyzePackDirectory(target)
        extraAnalysis = if (analysis.isValid || showErrors) analysis else null
        if (!analysis.isValid) return
        val suggestedName = analysis.info?.name?.takeIf { it.isNotBlank() }
        if (suggestedName != null && (extraName.isBlank() || extraName == autoName)) {
            extraName = suggestedName
            autoName = suggestedName
        }
        analysis.framework?.let { extraFramework = it }
    }

    LaunchedEffect(extraPath) {
        analyzeExtra(showErrors = false)
    }

    fun clearExtraForm() {
        editingId = null
        extraName = ""
        extraPath = ""
        extraMainKinds = setOf("CSTI")
        extraFramework = FrameworkId.MC
        extraAnalysis = null
        autoName = ""
        error = null
    }

    fun submitExtra() {
        val target = runCatching { Path.of(extraPath.trim()) }.getOrNull()
        if (target == null) {
            error = "数据包目录路径不合法"
            return
        }
        val ok = if (editingId == null) {
            controller.addExtraPack(extraName, target, extraMainKinds, extraFramework)
        } else {
            controller.updateExtraPack(
                id = editingId!!,
                name = extraName,
                path = target,
                mainPackKinds = extraMainKinds,
                framework = extraFramework,
                enabled = extraPacks.firstOrNull { it.id == editingId }?.enabled ?: true,
            )
        }
        if (ok) clearExtraForm() else error = controller.state.value.status
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("数据包配置", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "CSTI / CSFF 是两种主包。额外数据包由用户导入，并指定归属主包与框架（MC/ML）；" +
                "不同框架的额外包格式不同，不能混用。应用组合时，主包与匹配框架下所有启用的额外包" +
                "会合并加载（同名 Schema、对象、本地化与资源由额外包覆盖）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle("当前")
        KeyValue("工程", state.projectRoot?.toString() ?: "尚未打开工程", mono = true)
        KeyValue("主包", state.packRoot?.toString() ?: "尚未加载", mono = true)
        if (state.pack != null) {
            val layers = state.pack.layers
            KeyValue(
                "PackInfo",
                "${state.pack.info.name} ${state.pack.info.version} · ${layers.size} 层",
            )
            layers.forEachIndexed { index, layer ->
                KeyValue(
                    if (index == 0) "主层" else "额外层 $index",
                    "${layer.info.name} ${layer.info.version} · ${layer.root}".trim(),
                    mono = true,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { refreshNonce += 1 }) {
                Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                Text("重新检测")
            }
        }

        SectionTitle("组合框架")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.project != null) {
                OutlinedButton(onClick = {}) { Text(targetFramework.name) }
                Text(
                    text = "跟随当前工程框架；工程必须使用同框架的额外包",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                listOf(FrameworkId.MC, FrameworkId.ML).forEach { framework ->
                    if (targetFramework == framework) {
                        OutlinedButton(onClick = {}) { Text(framework.name) }
                    } else {
                        TextButton(onClick = { targetFramework = framework }) {
                            Text(framework.name)
                        }
                    }
                }
                Text(
                    text = "尚未打开工程，可先选择框架并加载数据包",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionTitle("主包")
        mainPacks.forEach { config ->
            val info = loadInfos["main:${config.kind}"]
            val active = activeKind == config.kind &&
                config.path != null &&
                normalizeGamePath(config.path!!) == currentRoot
            MainPackRow(
                config = config,
                info = info,
                active = active,
                onPick = {
                    scope.launch {
                        runCatching {
                            filePicker.pickDirectory(title = "选择 ${config.kind} 数据包目录")
                        }.getOrNull()?.let { picked ->
                            if (controller.setMainPackPath(config.kind, picked)) {
                                refreshNonce += 1
                            } else {
                                error = controller.state.value.status
                            }
                        }
                    }
                },
                onApply = {
                    scope.launch { controller.applyMainPack(config.kind, targetFramework) }
                },
            )
        }

        SectionTitle("额外数据包（${extraPacks.size}）")
        if (extraPacks.isEmpty()) {
            Text(
                text = "尚未导入额外数据包。在下方选择目录并指定归属主包。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        extraPacks.forEach { config ->
            ExtraPackRow(
                config = config,
                info = loadInfos["extra:${config.id}"],
                onEnabledChange = {
                    controller.setExtraPackEnabled(config.id, it)
                },
                onEdit = {
                    editingId = config.id
                    extraName = config.name
                    extraPath = config.path
                    extraMainKinds = config.effectiveMainPackKinds.toSet()
                    extraFramework = runCatching { FrameworkId.valueOf(config.framework) }
                        .getOrDefault(FrameworkId.MC)
                    error = null
                },
                onRemove = { controller.removeExtraPack(config.id) },
            )
        }

        SectionTitle(if (editingId == null) "导入额外数据包" else "编辑额外数据包")
        OutlinedTextField(
            value = extraName,
            onValueChange = { extraName = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            label = { Text("显示名称") },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = extraPath,
                onValueChange = { extraPath = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                label = { Text("额外数据包目录（含 PackInfo.json）") },
            )
            OutlinedButton(onClick = {
                scope.launch {
                    runCatching {
                        filePicker.pickDirectory(title = "选择额外数据包目录")
                    }.getOrNull()?.let {
                        extraPath = it.toString()
                        analyzeExtra(showErrors = true)
                    }
                }
            }) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Text("选择…")
            }
            OutlinedButton(onClick = { analyzeExtra(showErrors = true) }) {
                Text("分析")
            }
        }
        extraAnalysis?.let { analysis ->
            if (analysis.error != null) {
                Text(
                    text = analysis.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "格式：" + when (analysis.format) {
                            "ML" -> "ModCore-ModEditor ML 额外包（模板 + 类型）"
                            "MC" -> "标准数据包（MC 额外包）"
                            else -> analysis.format
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    analysis.info?.let { info ->
                        Text(
                            text = "PackInfo：${info.name} ${info.version}".trim() +
                                " · ${analysis.typeCount} 个类型",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    analysis.mainPackKind?.let { kind ->
                        Text(
                            text = "该目录的 PackInfo 名称匹配主包 $kind。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("额外包框架", style = MaterialTheme.typography.bodySmall)
            listOf(FrameworkId.MC, FrameworkId.ML).forEach { framework ->
                if (extraFramework == framework) {
                    OutlinedButton(onClick = {}) { Text(framework.name) }
                } else {
                    TextButton(onClick = { extraFramework = framework }) {
                        Text(framework.name)
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("归属主包", style = MaterialTheme.typography.bodySmall)
            listOf("CSTI", "CSFF").forEach { kind ->
                Checkbox(
                    checked = kind in extraMainKinds,
                    onCheckedChange = { checked ->
                        extraMainKinds = if (checked) {
                            extraMainKinds + kind
                        } else {
                            extraMainKinds - kind
                        }
                    },
                )
                Text(kind, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = "可同时勾选两代游戏",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = extraName.isNotBlank() &&
                    extraPath.isNotBlank() &&
                    extraMainKinds.isNotEmpty(),
                onClick = { submitExtra() },
            ) { Text(if (editingId == null) "导入" else "保存修改") }
            if (editingId != null) {
                TextButton(onClick = { clearExtraForm() }) { Text("取消") }
            }
        }
    }
}

@Composable
private fun MainPackRow(
    config: MainPackConfig,
    info: PackInfo?,
    active: Boolean,
    onPick: () -> Unit,
    onApply: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(config.displayName, style = MaterialTheme.typography.bodyMedium)
                    if (active) StatusChip("当前")
                    if (config.path != null && info == null) {
                        StatusChip("不可用", error = true)
                    }
                }
                Text(
                    text = when {
                        config.path == null -> "尚未选择数据包目录"
                        info == null -> "目录不存在或缺少有效 PackInfo.json"
                        else -> "PackInfo：${info.name} ${info.version}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                config.path?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            OutlinedButton(onClick = onPick) { Text("选择目录…") }
            Button(enabled = info != null, onClick = onApply) { Text("应用组合") }
        }
    }
}

@Composable
private fun ExtraPackRow(
    config: ExtraPackConfig,
    info: PackInfo?,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = config.enabled, onCheckedChange = onEnabledChange)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = config.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusChip(config.framework)
                    config.effectiveMainPackKinds.forEach { kind -> StatusChip(kind) }
                    if (info == null) StatusChip("不可用", error = true)
                }
                Text(
                    text = if (info != null) {
                        "PackInfo：${info.name} ${info.version}"
                    } else {
                        "目录不存在或缺少有效 PackInfo.json"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = config.path,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Close, contentDescription = "移除")
            }
        }
    }
}

private fun normalizeGamePath(value: String): String =
    runCatching { Path.of(value).toAbsolutePath().normalize().toString() }.getOrDefault(value)
