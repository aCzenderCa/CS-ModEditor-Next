package org.zender.editor.project

import org.zender.editor.diagnostics.DiagnosticCollector
import org.zender.editor.framework.McFormat
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.io.FileStore
import org.zender.editor.model.EntryOrigin
import org.zender.editor.objects.ObjectKey
import org.zender.editor.pack.DataPack
import org.zender.editor.resource.ResourceInventoryScanner
import org.zender.editor.resource.ResourceKind
import org.zender.editor.resource.ResourceRoot
import java.nio.file.Path

/**
 * 读取 MC（ModCore）目录型 Mod。
 *
 * 目录约定：
 * - `Data/<Type>` 目录（递归）：UniqueIDScriptable；`Data` 不存在时允许回退根目录；
 * - `Data/ScriptableObject/<Type>`：无 GUID 对象；
 * - `Data/GameSourceModify` 目录（递归）：文件名 = 目标 GUID；
 * - `Data/DataObjectModify/<Type>/<数据键>.json`：`@` 可代替 `:`；
 * - `Resource/Localization` 下的 CSV：按语言、取最后一列；
 * - `Resource/Texture2D`、`Resource/AudioClip`。
 */
class McProjectReader(
    private val fileStore: FileStore = AtomicFileStore(),
) {

    fun read(root: Path, basePack: DataPack? = null): ModProjectLoadResult {
        val projectRoot = fileStore.normalize(root)
        val diagnostics = DiagnosticCollector()

        if (!fileStore.isDirectory(projectRoot)) {
            diagnostics.error("Mod 目录不存在：$projectRoot")
            return ModProjectLoadResult(diagnostics = diagnostics.build())
        }

        val metadataFile = projectRoot.resolve(McFormat.metadataFileName)
        if (!fileStore.isRegularFile(metadataFile)) {
            diagnostics.error("缺少 ${McFormat.metadataFileName}", McFormat.metadataFileName)
            return ModProjectLoadResult(diagnostics = diagnostics.build())
        }

        val metadataParse = try {
            McFormat.readMetadata(fileStore.readText(metadataFile))
        } catch (e: Exception) {
            diagnostics.error(
                "无法读取 ${McFormat.metadataFileName}：${e.message}",
                McFormat.metadataFileName,
            )
            return ModProjectLoadResult(diagnostics = diagnostics.build())
        }
        diagnostics.addAll(metadataParse.diagnostics)
        val metadata = metadataParse.metadata
            ?: return ModProjectLoadResult(diagnostics = diagnostics.build())

        val support = ProjectReadSupport(fileStore, projectRoot, diagnostics)
        val tree = buildTree(support, basePack)

        return ModProjectLoadResult(
            project = ModProject(
                root = projectRoot,
                format = McFormat,
                metadata = metadata,
                workingTree = tree,
                basePack = basePack,
                diagnostics = diagnostics.build(),
            ),
            diagnostics = diagnostics.build(),
        )
    }

    private fun buildTree(support: ProjectReadSupport, basePack: DataPack?): WorkingTree {
        val objects = LinkedHashMap<ObjectKey, ProjectObject>()
        val folders = LinkedHashSet<ProjectFolder>()
        val objectReader = ProjectObjectReader(support, objects, folders)
        val dataRoot = support.projectRoot.resolve("Data")
        val dataTypeNames = LinkedHashSet<String>()

        // 1. Data/<Type>：UniqueIDScriptable。
        if (support.fileStore.isDirectory(dataRoot)) {
            for (directory in support.directories(dataRoot)) {
                val type = directory.fileName.toString()
                if (type in DATA_RESERVED) continue
                if (!support.hasObjectContent(directory, includeJsonnet = false)) continue
                dataTypeNames += type
                objectReader.readUniqueIdType(type, directory, includeJsonnet = false)
            }
        }

        // 2. 回退到根目录 <Type>（仅已注册类型；Data/<Type> 优先）。
        readRootFallbackUniqueIdTypes(support, basePack, objectReader, dataTypeNames)

        // 3. ScriptableObject：Data/ScriptableObject 优先，根目录 ScriptableObject 回退。
        readScriptableObjects(support, objectReader)

        // 4. 补丁。
        val gameSourceModifies = readGameSourceModifies(support, basePack, objects)
        val dataObjectModifies = readDataObjectModifies(support, basePack, objects)

        // 5. 本地化与资源。
        val localizationResult = ProjectLocalizationFiles.readMc(
            fileStore = support.fileStore,
            projectRoot = support.projectRoot,
            localizationRoot = McFormat.localizationRoot(),
        )
        support.diagnostics.addAll(localizationResult.diagnostics)
        val resources = readResources(support)

        return WorkingTree(
            objects = objects.toMap(),
            gameSourceModifies = gameSourceModifies.toMap(),
            dataObjectModifies = dataObjectModifies.toMap(),
            resources = resources.toMap(),
            localization = localizationResult.localization,
            folders = folders.toSet(),
        )
    }

    private fun readRootFallbackUniqueIdTypes(
        support: ProjectReadSupport,
        basePack: DataPack?,
        reader: ProjectObjectReader,
        dataTypeNames: Set<String>,
    ) {
        if (basePack == null) return
        for (directory in support.directories(support.projectRoot)) {
            val type = directory.fileName.toString()
            if (type in dataTypeNames) continue
            if (type == "ScriptableObject") continue
            if (McFormat.isReservedRootDirectory(type)) continue
            if (type !in basePack.objectIndex.guidIndexTypes) continue
            if (!support.hasObjectContent(directory, includeJsonnet = false)) continue
            reader.readUniqueIdType(type, directory, includeJsonnet = false)
        }
    }

    private fun readScriptableObjects(
        support: ProjectReadSupport,
        reader: ProjectObjectReader,
    ) {
        val readTypes = HashSet<String>()
        val dataSoRoot = support.projectRoot.resolve("Data/ScriptableObject")
        for (directory in support.directories(dataSoRoot)) {
            val type = directory.fileName.toString()
            readTypes += type
            reader.readScriptableObjectType(type, directory, includeJsonnet = false)
        }

        val rootSoRoot = support.projectRoot.resolve("ScriptableObject")
        for (directory in support.directories(rootSoRoot)) {
            val type = directory.fileName.toString()
            if (type in readTypes) continue
            reader.readScriptableObjectType(type, directory, includeJsonnet = false)
        }
    }

    private fun readGameSourceModifies(
        support: ProjectReadSupport,
        basePack: DataPack?,
        objects: Map<ObjectKey, ProjectObject>,
    ): Map<String, GameSourceModify> {
        val directory = support.projectRoot.resolve(McFormat.gameSourceModifyDirectory())
        if (!support.fileStore.isDirectory(directory)) return emptyMap()

        val result = LinkedHashMap<String, GameSourceModify>()
        for (file in support.jsonFiles(directory, includeJsonnet = false)) {
            val relativePath = support.relative(file)
            val targetGuid = ProjectReadSupport.stem(file)
            if (targetGuid.isBlank()) continue
            if (targetGuid in result) {
                support.diagnostics.warning(
                    "GameSourceModify 目标 GUID 重复：$targetGuid（保留首个文件）",
                    relativePath,
                )
                continue
            }
            val document = support.readDocument(file, relativePath) ?: continue
            val targetKey = objects.values.firstOrNull { it.uniqueId == targetGuid }?.key
                ?: basePack?.objectEntryByGuid(targetGuid)?.key
            if (basePack != null && targetKey == null) {
                support.diagnostics.warning(
                    "GameSourceModify 目标 GUID 未在数据包中找到：$targetGuid",
                    relativePath,
                )
            }
            result[targetGuid] = GameSourceModify(
                targetGuid = targetGuid,
                document = document,
                relativePath = relativePath,
                targetKey = targetKey,
            )
        }
        return result
    }

    private fun readDataObjectModifies(
        support: ProjectReadSupport,
        basePack: DataPack?,
        objects: Map<ObjectKey, ProjectObject>,
    ): Map<DataObjectModifyId, DataObjectModify> {
        val root = support.projectRoot.resolve("Data/DataObjectModify")
        if (!support.fileStore.isDirectory(root)) return emptyMap()

        val result = LinkedHashMap<DataObjectModifyId, DataObjectModify>()
        for (typeDirectory in support.directories(root)) {
            val type = typeDirectory.fileName.toString()
            for (file in support.jsonFiles(typeDirectory, includeJsonnet = false)) {
                val relativePath = support.relative(file)
                val dataKey = ProjectReadSupport.stem(file).replace('@', ':')
                val id = DataObjectModifyId(type, dataKey)
                if (id in result) {
                    support.diagnostics.warning(
                        "DataObjectModify 目标重复：$type/$dataKey（保留首个文件）",
                        relativePath,
                    )
                    continue
                }
                val document = support.readDocument(file, relativePath) ?: continue
                val targetKey = resolveDataObjectTarget(objects, basePack, type, dataKey)
                if (targetKey == null && basePack != null) {
                    support.diagnostics.warning(
                        "DataObjectModify 目标未找到：$type/$dataKey",
                        relativePath,
                    )
                }
                result[id] = DataObjectModify(
                    type = type,
                    dataKey = dataKey,
                    document = document,
                    relativePath = relativePath,
                    targetKey = targetKey,
                )
            }
        }
        return result
    }

    private fun resolveDataObjectTarget(
        objects: Map<ObjectKey, ProjectObject>,
        basePack: DataPack?,
        type: String,
        dataKey: String,
    ): ObjectKey? {
        val shortName = dataKey.substringAfterLast(':').substringAfterLast('@')
        objects.values.firstOrNull { it.type == type && it.name == shortName }?.let { return it.key }
        val pack = basePack ?: return null
        pack.objectIndex.find(type, dataKey)?.let { return it.key }
        pack.objectIndex.find(type, shortName)?.let { return it.key }
        return pack.objectIndex.findAllByName(shortName).firstOrNull { it.type == type }?.key
    }

    private fun readResources(support: ProjectReadSupport): Map<ResourceId, ProjectResource> {
        val roots = listOf(
            ResourceRoot(McFormat.resourceDirectory(ResourceKind.IMAGE), ResourceKind.IMAGE, "Sprite"),
            ResourceRoot(McFormat.resourceDirectory(ResourceKind.AUDIO), ResourceKind.AUDIO, "AudioClip"),
        )
        val scan = ResourceInventoryScanner.scan(
            fileStore = support.fileStore,
            baseRoot = support.projectRoot,
            roots = roots,
            origin = EntryOrigin.PROJECT,
        )
        support.diagnostics.addAll(scan.diagnostics)
        return scan.inventory.entries.associateTo(LinkedHashMap()) { entry ->
            val id = ResourceId(entry.kind, entry.key)
            id to ProjectResource(
                id = id,
                typeName = entry.typeName,
                relativePath = entry.relativePath,
                sourcePath = support.projectRoot.resolve(entry.relativePath),
                sizeBytes = entry.sizeBytes,
            )
        }
    }

    companion object {
        private val DATA_RESERVED = setOf(
            "ScriptableObject",
            "GameSourceModify",
            "DataObjectModify",
        )
    }
}
