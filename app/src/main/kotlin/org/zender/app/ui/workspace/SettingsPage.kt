package org.zender.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import org.zender.app.ui.theme.ThemeMode
import org.zender.app.workspace.OpenProjectBehavior
import org.zender.app.workspace.WorkspaceController
import org.zender.app.workspace.WorkspaceUiState
import org.zender.editor.settings.FavoriteField
import java.nio.file.Path

@Composable
fun SettingsPage(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    filePicker: FilePicker,
) {
    val scope = rememberCoroutineScope()
    var languageMenu by remember { mutableStateOf(false) }
    var themeMenu by remember { mutableStateOf(false) }
    var openBehaviorMenu by remember { mutableStateOf(false) }
    val settings = state.settings
    val themeMode = ThemeMode.fromStorage(settings.themeMode)
    val openBehavior = OpenProjectBehavior.fromStorage(settings.openProjectBehavior)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "设置立即写入 ${controller.settingsPath}。",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        SectionTitle("外观")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("主题", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.width(10.dp))
            androidx.compose.foundation.layout.Box {
                TextButton(onClick = { themeMenu = true }) {
                    Text(
                        when (themeMode) {
                            ThemeMode.SYSTEM -> "跟随系统"
                            ThemeMode.LIGHT -> "浅色"
                            ThemeMode.DARK -> "深色"
                        },
                    )
                }
                DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                    listOf(
                        ThemeMode.SYSTEM to "跟随系统",
                        ThemeMode.LIGHT to "浅色",
                        ThemeMode.DARK to "深色",
                    ).forEach { (mode, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                themeMenu = false
                                controller.setThemeMode(mode)
                            },
                        )
                    }
                }
            }
            Text(
                text = "工具栏右侧的太阳/月亮按钮可在浅色与深色之间快速切换。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionTitle("编辑器")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = settings.showWarpType,
                onCheckedChange = { controller.setShowWarpType(it) },
            )
            Column {
                Text("显示 WarpType", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "默认关闭。开启后在引用字段与检查器中显示 3/6 等运行时常量。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "LocalizedString 输入语言（默认中文 + English）",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            listOf("SimpCn" to "中文", "En" to "English").forEach { (code, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = code in settings.localizationInputLanguages,
                        onCheckedChange = {
                            controller.setLocalizationInputLanguage(code, it)
                        },
                    )
                    Text(label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionTitle("数据包")
        KeyValue("当前数据包", state.packRoot?.toString() ?: "尚未选择", mono = true)
        KeyValue(
            "版本",
            state.pack?.let { "${it.info.name} ${it.info.version} · ${it.typeNames.size} 个类型" }
                ?: "—",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    runCatching { filePicker.pickDirectory(title = "选择数据包目录（csti / csff）") }
                        .getOrNull()
                        ?.let { controller.loadPack(it) }
                }
            }) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("选择数据包目录")
            }
            OutlinedButton(
                enabled = state.packRoot != null,
                onClick = {
                    state.packRoot?.let { root ->
                        scope.launch { controller.loadPack(root) }
                    }
                },
            ) {
                Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("重新加载")
            }
        }
        if (settings.recentPackDirectories.isNotEmpty()) {
            Text(
                text = "最近数据包",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            settings.recentPackDirectories.forEach { recent ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch { controller.loadPack(Path.of(recent.path)) }
                        }
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${recent.displayName} ${recent.version}".trim(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = recent.path,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = { controller.removeRecentPack(recent.path) },
                        modifier = Modifier.size(26.dp),
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "移除", modifier = Modifier.size(15.dp))
                    }
                }
            }
        }

        SectionTitle("工程")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("打开工程时的窗口", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.width(10.dp))
            androidx.compose.foundation.layout.Box {
                TextButton(onClick = { openBehaviorMenu = true }) {
                    Text(
                        when (openBehavior) {
                            OpenProjectBehavior.ASK -> "每次询问"
                            OpenProjectBehavior.NEW_WINDOW -> "新窗口"
                            OpenProjectBehavior.CURRENT_WINDOW -> "当前窗口"
                        },
                    )
                }
                DropdownMenu(
                    expanded = openBehaviorMenu,
                    onDismissRequest = { openBehaviorMenu = false },
                ) {
                    listOf(
                        OpenProjectBehavior.ASK to "每次询问",
                        OpenProjectBehavior.NEW_WINDOW to "新窗口",
                        OpenProjectBehavior.CURRENT_WINDOW to "当前窗口",
                    ).forEach { (behavior, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                openBehaviorMenu = false
                                controller.setOpenProjectBehavior(behavior)
                            },
                        )
                    }
                }
            }
        }
        KeyValue("当前工程", state.projectRoot?.toString() ?: "尚未打开", mono = true)
        KeyValue(
            "关联数据包",
            state.projectRoot?.let { settings.projectPackDirectories[it.toString()] } ?: "尚未记录",
            mono = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    runCatching { filePicker.pickDirectory(title = "选择 Mod 工程目录") }
                        .getOrNull()
                        ?.let { controller.loadProject(it) }
                }
            }) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("打开工程目录")
            }
            Button(
                enabled = state.project != null,
                onClick = { controller.saveProject() },
            ) { Text("保存工程") }
        }
        if (settings.recentProjects.isNotEmpty()) {
            Text(
                text = "最近工程",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            settings.recentProjects.forEach { path ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { controller.loadProject(Path.of(path)) } }
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = path,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { controller.removeRecentProject(path) },
                        modifier = Modifier.size(26.dp),
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "移除", modifier = Modifier.size(15.dp))
                    }
                }
            }
        }

        SectionTitle("界面语言")
        androidx.compose.foundation.layout.Box {
            TextButton(onClick = { languageMenu = true }) {
                Text(
                    when (settings.language) {
                        null, "" -> "跟随系统"
                        "zh-CN" -> "简体中文"
                        "en" -> "English"
                        else -> settings.language.orEmpty()
                    },
                )
            }
            DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                listOf<Pair<String?, String>>(
                    null to "跟随系统",
                    "zh-CN" to "简体中文",
                    "en" to "English",
                ).forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            languageMenu = false
                            controller.setLanguage(value)
                        },
                    )
                }
            }
        }

        SectionTitle("收藏字段（${state.session.favorites.size}）")
        if (state.session.favorites.isEmpty()) {
            Text(
                text = "在字段视图点击星标即可收藏。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.session.favorites.sortedWith(compareBy({ it.type }, { it.fieldPath })).forEach { favorite ->
                FavoriteRow(
                    favorite = favorite,
                    onRemove = { controller.removeFavorite(favorite) },
                )
            }
            TextButton(onClick = { controller.clearFavorites() }) {
                Text("清空全部收藏")
            }
        }

        SectionTitle("多窗口")
        if (state.detachedWindows.isEmpty()) {
            Text(
                text = "在文件 Tab 右侧点击“在新窗口打开”，窗口位置与内容会随工程恢复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.detachedWindows.forEach { window ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(window.title, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "${window.viewMode} · ${window.geometry.width.toInt()}×${window.geometry.height.toInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { controller.activateDocument(window.documentId) }) {
                        Text("定位")
                    }
                    IconButton(
                        onClick = { controller.closeDetachedWindow(window.id) },
                        modifier = Modifier.size(26.dp),
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭窗口", modifier = Modifier.size(15.dp))
                    }
                }
            }
        }

        SectionTitle("诊断")
        KeyValue("数据包诊断", "${state.diagnostics.size}")
        KeyValue("工程校验", "${state.validation.size} 条（${state.errorCount} 错误）")
        KeyValue("最近保存", state.lastSavedAt?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "—")
        TextButton(onClick = { controller.validateProject() }) {
            Text("运行完整校验")
        }
    }
}

@Composable
private fun FavoriteRow(favorite: FavoriteField, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.StarBorder,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(favorite.type, style = MaterialTheme.typography.bodySmall)
            Text(
                text = favorite.fieldPath,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = "取消收藏", modifier = Modifier.size(15.dp))
        }
    }
}
