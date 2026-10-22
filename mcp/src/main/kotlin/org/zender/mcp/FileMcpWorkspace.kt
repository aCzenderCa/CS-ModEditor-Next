package org.zender.mcp

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.document.writePath
import org.zender.editor.edit.AddReferenceToListCommand
import org.zender.editor.edit.ClearReferenceCommand
import org.zender.editor.edit.RemoveElementCommand
import org.zender.editor.edit.SetReferenceCommand
import org.zender.editor.edit.WarpFields
import org.zender.editor.edit.WarpType
import org.zender.editor.field.FieldSchemaLocator
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.io.FileStore
import org.zender.editor.localization.LocalizationEntry
import org.zender.editor.pack.DataPack
import org.zender.editor.pack.DataPackLoader
import org.zender.editor.project.ModProject
import org.zender.editor.project.ModProjectCreator
import org.zender.editor.project.ModProjectLoader
import org.zender.editor.project.ModProjectWriter
import org.zender.editor.project.NewObjectRequest
import org.zender.editor.project.NewPatchRequest
import org.zender.editor.project.NewProjectRequest
import org.zender.editor.project.PatchKind
import org.zender.editor.project.ProjectPatchFactory
import org.zender.editor.project.ProjectObject
import org.zender.editor.project.ProjectObjectFactory
import org.zender.editor.project.ResourceImportResult
import org.zender.editor.project.ResourceImporter
import org.zender.editor.project.SchemaValidator
import org.zender.editor.resource.ResourceKind
import org.zender.editor.settings.SettingsLocations
import org.zender.editor.settings.SettingsStore
import java.nio.file.Path

/**
 * 无界面的工作区实现：直接通过数据层读写磁盘。
 *
 * 用于测试、调试或需要在没有编辑器进程时批量处理工程的场景；
 * 编辑器内嵌的 MCP 服务使用 app 模块的 `EditorMcpWorkspace`。
 */
class FileMcpWorkspace(
    private val fileStore: FileStore = AtomicFileStore(),
    private val projectLoader: ModProjectLoader = ModProjectLoader(fileStore),
    private val projectWriter: ModProjectWriter = ModProjectWriter(fileStore),
    private val packLoader: DataPackLoader = DataPackLoader(fileStore),
) : McpWorkspace {

    override var pack: DataPack? = null
        private set

    var packRoot: Path? = null
        private set

    override var project: ModProject? = null
        private set

    override var diagnostics: List<Diagnostic> = emptyList()
        private set

    override var dirty: Boolean = false
        private set

    override fun openPack(root: Path): DataPack {
        val normalized = fileStore.normalize(root)
        val result = packLoader.load(normalized)
        if (!result.isSuccess) {
            throw McpToolException(
                "数据包加载失败：" + result.diagnostics.joinToString("；") { it.message },
            )
        }
        val loaded = result.packOrThrow()
        pack = loaded
        packRoot = normalized
        diagnostics = result.diagnostics
        project = project?.withBasePack(loaded)
        return loaded
    }

    override fun openProject(root: Path, packPath: Path?): ModProject {
        val normalized = fileStore.normalize(root)
        val effectivePack = when {
            packPath != null -> openPack(packPath)
            pack != null -> pack
            else -> associatedPackRoot(normalized)?.let { runCatching { openPack(it) }.getOrNull() }
        }

        val result = projectLoader.read(normalized, effectivePack)
        if (!result.isSuccess) {
            throw McpToolException(
                "工程读取失败：" + result.diagnostics.joinToString("；") { it.message },
            )
        }
        val loaded = result.projectOrThrow()
        project = loaded
        diagnostics = result.diagnostics
        dirty = false
        return loaded
    }

    override fun createProject(request: NewProjectRequest): ModProject {
        val result = ModProjectCreator(fileStore).create(request)
        val created = result.project
        if (created == null || result.diagnostics.any { it.isError }) {
            throw McpToolException(
                "创建工程失败：" + result.diagnostics.joinToString("；") { it.message },
            )
        }
        val loaded = created.withBasePack(pack)
        project = loaded
        diagnostics = result.diagnostics
        dirty = false
        return loaded
    }

    override fun saveProject(targetRoot: Path?): SaveOutcome {
        val current = requireProject()
        val target = targetRoot ?: current.root
            ?: throw McpToolException("工程没有保存目录，请传入 targetPath")
        val normalizedTarget = fileStore.normalize(target)
        val result = projectWriter.write(current, normalizedTarget)
        diagnostics = result.diagnostics
        if (!result.isSuccess) {
            throw McpToolException(
                "保存失败：" + result.diagnostics.joinToString("；") { it.message },
            )
        }
        project = current.withRoot(normalizedTarget)
        dirty = false
        return SaveOutcome(result.writtenFiles, result.diagnostics)
    }

    override fun objectDocument(type: String, name: String, source: String): ObjectDocument? {
        val projectDocument = project?.objectOf(type, name)
        if (source == "pack") return packDocument(type, name)
        if (projectDocument != null) {
            return ObjectDocument(
                source = "project",
                document = projectDocument.document,
                relativePath = projectDocument.relativePath,
                uniqueId = projectDocument.uniqueId,
            )
        }
        if (source == "project") return null
        return packDocument(type, name)
    }

    override fun setField(
        type: String,
        name: String,
        path: JsonPath,
        value: JsonNode,
    ): EditOutcome {
        val current = requireProject()
        val entry = current.objectOf(type, name)
            ?: throw McpToolException("工程中不存在对象：$type/$name")
        val issues = SchemaValidator(pack)
            .validateFieldWrite(current, type, path, value)
            .filter { it.isError }
        if (issues.isNotEmpty()) {
            throw McpToolException(issues.joinToString("；") { it.message })
        }
        val updatedRoot = try {
            entry.document.root.writePath(path, value)
        } catch (e: Exception) {
            throw McpToolException("写入字段失败：${e.message}")
        }
        replaceObject(current, entry, JsonDocument(updatedRoot))
        return EditOutcome(changed = true)
    }

    override fun setReference(
        type: String,
        name: String,
        field: JsonPath,
        referenceKey: String,
        warpType: WarpType,
        index: Int?,
    ): EditOutcome {
        val current = requireProject()
        val entry = current.objectOf(type, name)
            ?: throw McpToolException("工程中不存在对象：$type/$name")
        val info = pack?.schemaRepository?.let {
            FieldSchemaLocator.resolve(it, type, field)
        } ?: throw McpToolException("无法解析引用字段：${field.format()}")
        val fieldType = if (info.isReferenceList) {
            info.schemaField.elementTypeName ?: info.typeName ?: info.schemaField.typeName
        } else {
            info.typeName ?: info.schemaField.typeName
        }
        // MC 引用本工程的非 GUID 对象时统一写成 `Namespace:Name`。
        val key = current.normalizeReferenceKey(fieldType, referenceKey)

        val command = when {
            info.isReference -> SetReferenceCommand(
                label = "设置引用 ${field.format()}",
                fieldPath = field,
                referenceKey = key,
                warpType = warpType,
            )

            info.isReferenceList -> AddReferenceToListCommand(
                label = "添加引用 ${field.format()}",
                listFieldPath = field,
                referenceKey = key,
                warpType = warpType,
                index = index,
            )

            else -> throw McpToolException(
                "字段 ${field.format()} 不是引用字段（Schema: ${info.kind}）",
            )
        }
        replaceObject(current, entry, command.execute(entry.document))
        return EditOutcome(changed = true)
    }

    override fun clearReference(type: String, name: String, field: JsonPath): EditOutcome {
        val current = requireProject()
        val entry = current.objectOf(type, name)
            ?: throw McpToolException("工程中不存在对象：$type/$name")
        val command = ClearReferenceCommand("清除引用 ${field.format()}", field)
        replaceObject(current, entry, command.execute(entry.document))
        return EditOutcome(changed = true)
    }

    override fun removeReference(
        type: String,
        name: String,
        field: JsonPath,
        index: Int,
    ): EditOutcome {
        val current = requireProject()
        val entry = current.objectOf(type, name)
            ?: throw McpToolException("工程中不存在对象：$type/$name")
        val command = RemoveElementCommand(
            label = "移除引用 ${field.format()}[$index]",
            arrayPath = WarpFields.dataPath(field),
            index = index,
        )
        replaceObject(current, entry, command.execute(entry.document))
        return EditOutcome(changed = true)
    }

    override fun createObject(request: NewObjectRequest): ProjectObject {
        val current = requireProject()
        val result = ProjectObjectFactory(pack).create(current, request)
        if (!result.isSuccess) {
            throw McpToolException(
                "新建对象失败：" + result.diagnostics.joinToString("；") { it.message },
            )
        }
        val created = result.objectEntry
            ?: throw McpToolException("新建对象失败：没有返回对象")
        val updated = result.project
            ?: throw McpToolException("新建对象失败：没有返回工程快照")
        project = updated
        diagnostics = result.diagnostics
        dirty = true
        return created
    }

    override fun createPatch(request: NewPatchRequest): PatchOutcome {
        val current = requireProject()
        val result = ProjectPatchFactory(pack).create(current, request)
        if (!result.isSuccess) {
            throw McpToolException(
                "新建补丁失败：" + result.diagnostics.joinToString("；") { it.message },
            )
        }
        project = result.project
            ?: throw McpToolException("新建补丁失败：没有返回工程快照")
        diagnostics = result.diagnostics
        dirty = true
        val gameSourceModify = result.gameSourceModify
        if (gameSourceModify != null) {
            return PatchOutcome(
                kind = PatchKind.GAME_SOURCE_MODIFY,
                relativePath = gameSourceModify.relativePath,
                target = gameSourceModify.targetGuid,
            )
        }
        val dataObjectModify = result.dataObjectModify
        if (dataObjectModify != null) {
            return PatchOutcome(
                kind = PatchKind.DATA_OBJECT_MODIFY,
                relativePath = dataObjectModify.relativePath,
                target = "${dataObjectModify.type}/${dataObjectModify.dataKey}",
            )
        }
        throw McpToolException("新建补丁失败：没有返回补丁")
    }

    override fun updateLocalization(
        key: String,
        english: String?,
        chinese: String?,
    ): LocalizationEntry {
        val current = requireProject()
        val localization = current.workingTree.localization
        val existing = localization.entry(key)
        val entry = LocalizationEntry(
            key = key,
            english = english ?: existing?.english.orEmpty(),
            chinese = chinese ?: existing?.chinese.orEmpty(),
        )
        project = current.withWorkingTree(
            current.workingTree.withLocalization(localization.withEntry(entry)),
        )
        dirty = true
        return entry
    }

    override fun importResource(
        source: Path,
        key: String?,
        kind: ResourceKind?,
        overwrite: Boolean,
    ): ResourceImportResult {
        val current = requireProject()
        val importer = ResourceImporter(fileStore)
        val result = if (key == null) {
            importer.import(current, source, kind = kind, overwrite = overwrite)
        } else {
            importer.import(current, source, key = key, kind = kind, overwrite = overwrite)
        }
        diagnostics = result.diagnostics
        if (!result.isSuccess) {
            throw McpToolException(
                "导入资源失败：" + result.diagnostics.joinToString("；") { it.message },
            )
        }
        project = result.project
        dirty = true
        return result
    }

    // ---------------------------------------------------------------- 内部

    private fun packDocument(type: String, name: String): ObjectDocument {
        val currentPack = pack
            ?: throw McpToolException("尚未打开数据包，且工程中不存在对象：$type/$name")
        val entry = currentPack.objectEntry(type, name)
            ?: throw McpToolException("数据包中不存在对象：$type/$name")
        if (!entry.hasDataFile) {
            throw McpToolException("对象 $type/$name 只存在于索引中，没有可读取的 JSON 文件")
        }
        return ObjectDocument(
            source = "pack",
            document = currentPack.readObject(entry),
            relativePath = entry.relativePath.orEmpty(),
            uniqueId = entry.uniqueId,
            group = entry.group,
            displayName = entry.displayName,
        )
    }

    private fun replaceObject(
        current: ModProject,
        entry: ProjectObject,
        document: JsonDocument,
    ) {
        val updatedObject = entry.copy(document = document)
        project = try {
            current.withWorkingTree(current.workingTree.withObject(updatedObject))
        } catch (e: Exception) {
            throw McpToolException("更新工程失败：${e.message}")
        }
        dirty = true
    }

    private fun associatedPackRoot(projectRoot: Path): Path? = runCatching {
        val settingsStore = SettingsStore(fileStore, SettingsLocations.defaultSettingsFile())
        settingsStore.load()
            .projectPackDirectories[projectRoot.toString()]
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?.takeIf { fileStore.isDirectory(it) }
    }.getOrNull()
}
