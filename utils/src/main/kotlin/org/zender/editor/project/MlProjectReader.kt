package org.zender.editor.project

import org.zender.editor.diagnostics.DiagnosticCollector
import org.zender.editor.framework.MlFormat
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
 * 读取 ML（ModLoader）目录型 Mod。
 *
 * 目录约定：
 * - `<Type>` 目录（递归）：UniqueIDScriptable；
 * - `ScriptableObject/<Type>` 目录：无 GUID 对象；
 * - `GameSourceModify` 目录（递归）：文件名 = 目标 GUID；
 * - `Localization/SimpCn.csv` / `SimpEn.csv`；
 * - `Resource/Picture`、`Resource/Audio`。
 */
class MlProjectReader(
    private val fileStore: FileStore = AtomicFileStore(),
) {

    fun read(root: Path, basePack: DataPack? = null): ModProjectLoadResult {
        val projectRoot = fileStore.normalize(root)
        val diagnostics = DiagnosticCollector()

        if (!fileStore.isDirectory(projectRoot)) {
            diagnostics.error("Mod 目录不存在：$projectRoot")
            return ModProjectLoadResult(diagnostics = diagnostics.build())
        }

        val metadataFile = projectRoot.resolve(MlFormat.metadataFileName)
        if (!fileStore.isRegularFile(metadataFile)) {
            diagnostics.error("缺少 ${MlFormat.metadataFileName}", MlFormat.metadataFileName)
            return ModProjectLoadResult(diagnostics = diagnostics.build())
        }

        val metadataParse = try {
            MlFormat.readMetadata(fileStore.readText(metadataFile))
        } catch (e: Exception) {
            diagnostics.error(
                "无法读取 ${MlFormat.metadataFileName}：${e.message}",
                MlFormat.metadataFileName,
            )
            return ModProjectLoadResult(diagnostics = diagnostics.build())
        }
        diagnostics.addAll(metadataParse.diagnostics)
        val metadata = metadataParse.metadata
            ?: return ModProjectLoadResult(diagnostics = diagnostics.build())

        val support = ProjectReadSupport(fileStore, projectRoot, diagnostics)
        val tree = buildTree(support, basePack)
        if (tree.gameSourceModifies.isNotEmpty() && metadata.modEditorVersion.isNullOrBlank()) {
            diagnostics.warning(
                "ModInfo.ModEditorVersion 为空，游戏会跳过 GameSourceModify",
                MlFormat.metadataFileName,
            )
        }

        return ModProjectLoadResult(
            project = ModProject(
                root = projectRoot,
                format = MlFormat,
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

        // 1. 根目录类型目录：UniqueIDScriptable。
        for (directory in support.directories(support.projectRoot)) {
            val type = directory.fileName.toString()
            if (MlFormat.isReservedRootDirectory(type)) continue
            if (!support.hasObjectContent(directory, includeJsonnet = true)) continue
            if (!acceptUniqueIdType(type, basePack, support)) continue
            objectReader.readUniqueIdType(type, directory, includeJsonnet = true)
        }

        // 2. ScriptableObject/<Type>：无 GUID 对象。
        val scriptableRoot = support.projectRoot.resolve("ScriptableObject")
        for (directory in support.directories(scriptableRoot)) {
            val type = directory.fileName.toString()
            objectReader.readScriptableObjectType(type, directory, includeJsonnet = true)
        }

        // 3. GameSourceModify。
        val gameSourceModifies = readGameSourceModifies(support, basePack)

        // 4. 本地化与资源。
        val localizationResult = ProjectLocalizationFiles.readMl(
            fileStore = support.fileStore,
            projectRoot = support.projectRoot,
            localizationRoot = MlFormat.localizationRoot(),
        )
        support.diagnostics.addAll(localizationResult.diagnostics)
        val resources = readResources(support)

        return WorkingTree(
            objects = objects.toMap(),
            gameSourceModifies = gameSourceModifies.toMap(),
            resources = resources.toMap(),
            localization = localizationResult.localization,
            folders = folders.toSet(),
        )
    }

    private fun acceptUniqueIdType(
        type: String,
        basePack: DataPack?,
        support: ProjectReadSupport,
    ): Boolean {
        if (basePack == null) return true
        if (type in basePack.objectIndex.guidIndexTypes) return true
        if (type in basePack.objectIndex.nameIndexTypes) {
            support.diagnostics.warning(
                "无 GUID 类型 $type 应放在 ScriptableObject/ 下，已跳过根目录中的文件",
                type,
            )
            return false
        }
        support.diagnostics.warning(
            "类型 $type 未出现在数据包索引中，仍按 UniqueID 对象读取",
            type,
        )
        return true
    }

    private fun readGameSourceModifies(
        support: ProjectReadSupport,
        basePack: DataPack?,
    ): Map<String, GameSourceModify> {
        val directory = support.projectRoot.resolve(MlFormat.gameSourceModifyDirectory())
        if (!support.fileStore.isDirectory(directory)) return emptyMap()

        val result = LinkedHashMap<String, GameSourceModify>()
        for (file in support.jsonFiles(directory, includeJsonnet = true)) {
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
            val targetKey = basePack?.objectEntryByGuid(targetGuid)?.key
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

    private fun readResources(support: ProjectReadSupport): Map<ResourceId, ProjectResource> {
        val roots = listOf(
            ResourceRoot(MlFormat.resourceDirectory(ResourceKind.IMAGE), ResourceKind.IMAGE, "Sprite"),
            ResourceRoot(MlFormat.resourceDirectory(ResourceKind.AUDIO), ResourceKind.AUDIO, "AudioClip"),
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
}
