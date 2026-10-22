package org.zender.app.ui.workspace

import org.zender.editor.pack.DataPack
import org.zender.editor.project.ModProject
import org.zender.editor.project.ProjectFolderPath

enum class FileTreeNodeKind {
    METADATA,
    GROUP,
    OBJECT,
    PACK_OBJECT,
    GAME_SOURCE_MODIFY,
    DATA_OBJECT_MODIFY,
    LOCALIZATION,
    RESOURCE,
}

/**
 * 文件树节点。对象/补丁叶子的 [targetId] 与 `DocumentTarget` 对应，由界面转成打开动作。
 */
data class FileTreeNode(
    val id: String,
    val label: String,
    val detail: String? = null,
    val kind: FileTreeNodeKind,
    val type: String? = null,
    val name: String? = null,
    val dataKey: String? = null,
    val targetGuid: String? = null,
    /** 类型目录下的子文件夹路径（仅文件夹节点与对象叶子使用）。 */
    val folder: String? = null,
    val children: List<FileTreeNode> = emptyList(),
) {
    val isLeaf: Boolean get() = children.isEmpty()
}

data class FileTreeFilter(
    val keyword: String = "",
    /** 只显示指定对象类型；null 表示不过滤。 */
    val type: String? = null,
    val patchesOnly: Boolean = false,
    /** 显示数据包 `Name` 索引中的非 GUID 对象（只读）。 */
    val includePackObjects: Boolean = false,
)

/**
 * 从工程工作树构建文件树。纯函数，便于单元测试与搜索过滤。
 */
object FileTreeBuilder {

    fun build(
        project: ModProject?,
        pack: DataPack?,
        filter: FileTreeFilter = FileTreeFilter(),
    ): List<FileTreeNode> {
        if (project == null) return emptyList()
        val tree = project.workingTree
        val nodes = mutableListOf<FileTreeNode>()

        if (!filter.patchesOnly && filter.type == null) {
            nodes += FileTreeNode(
                id = "metadata",
                label = project.format.metadataFileName,
                detail = "${project.framework} · ${project.namespace}",
                kind = FileTreeNodeKind.METADATA,
            )
        }

        if (!filter.patchesOnly) {
            val uniqueIdTypes = tree.objects.values
                .filter { it.kind == org.zender.editor.project.ProjectObjectKind.UNIQUE_ID }
                .groupBy { it.type }
            val scriptableTypes = tree.objects.values
                .filter { it.kind == org.zender.editor.project.ProjectObjectKind.SCRIPTABLE_OBJECT }
                .groupBy { it.type }

            val uniqueTypes = (
                uniqueIdTypes.keys + tree.folders
                    .filter { it.kind == org.zender.editor.project.ProjectObjectKind.UNIQUE_ID }
                    .map { it.type }
                ).sorted()
            val scriptableTypeNames = (
                scriptableTypes.keys + tree.folders
                    .filter { it.kind == org.zender.editor.project.ProjectObjectKind.SCRIPTABLE_OBJECT }
                    .map { it.type }
                ).sorted()

            uniqueTypes.forEach { type ->
                val entries = uniqueIdTypes[type].orEmpty().sortedBy { it.name }
                val children = buildFolderChildren(
                    idPrefix = "group:uid:$type",
                    type = type,
                    folderPaths = tree.foldersOf(type, org.zender.editor.project.ProjectObjectKind.UNIQUE_ID)
                        .map { it.path },
                    leaves = entries.map { entry ->
                        FileTreeNode(
                            id = "object:${entry.type}/${entry.name}",
                            label = entry.name,
                            detail = entry.uniqueId?.take(8)?.plus("…"),
                            kind = FileTreeNodeKind.OBJECT,
                            type = entry.type,
                            name = entry.name,
                            folder = ProjectFolderPath.folderOf(entry.relativePath, entry.type),
                        )
                    },
                )
                nodes += FileTreeNode(
                    id = "group:uid:$type",
                    label = type,
                    detail = "${entries.size}",
                    kind = FileTreeNodeKind.GROUP,
                    type = type,
                    children = children,
                )
            }

            scriptableTypeNames.forEach { type ->
                val entries = scriptableTypes[type].orEmpty().sortedBy { it.name }
                val children = buildFolderChildren(
                    idPrefix = "group:so:$type",
                    type = type,
                    folderPaths = tree.foldersOf(
                        type,
                        org.zender.editor.project.ProjectObjectKind.SCRIPTABLE_OBJECT,
                    ).map { it.path },
                    leaves = entries.map { entry ->
                        FileTreeNode(
                            id = "object:${entry.type}/${entry.name}",
                            label = entry.name,
                            kind = FileTreeNodeKind.OBJECT,
                            type = entry.type,
                            name = entry.name,
                            folder = ProjectFolderPath.folderOf(entry.relativePath, entry.type),
                        )
                    },
                )
                nodes += FileTreeNode(
                    id = "group:so:$type",
                    label = "ScriptableObject/$type",
                    detail = "${entries.size}",
                    kind = FileTreeNodeKind.GROUP,
                    type = type,
                    children = children,
                )
            }
        }

        val gameSourceChildren = buildFolderChildren(
            idPrefix = "group:gsm",
            type = null,
            folderPaths = emptyList(),
            leaves = tree.gameSourceModifies.values
                .sortedBy { it.targetGuid }
                .map { patch ->
                    val target = patch.targetKey ?: pack?.objectEntryByGuid(patch.targetGuid)?.key
                    FileTreeNode(
                        id = "gsm:${patch.targetGuid}",
                        label = patch.targetGuid,
                        detail = target?.let { "${it.type}/${it.name}" },
                        kind = FileTreeNodeKind.GAME_SOURCE_MODIFY,
                        targetGuid = patch.targetGuid,
                        folder = ProjectFolderPath.folderUnder(
                            patch.relativePath,
                            project.format.gameSourceModifyDirectory(),
                        ),
                    )
                },
        )
        // 即使没有补丁也显示 modify 目录，便于在其中直接新建第一个补丁。
        if (!filter.patchesOnly || gameSourceChildren.isNotEmpty()) {
            nodes += FileTreeNode(
                id = "group:gsm",
                label = project.format.gameSourceModifyDirectory(),
                detail = "${tree.gameSourceModifies.size}",
                kind = FileTreeNodeKind.GROUP,
                children = gameSourceChildren,
            )
        }

        val dataObjectChildren = tree.dataObjectModifies.values
            .groupBy { it.type }
            .toSortedMap()
            .map { (type, patches) ->
                FileTreeNode(
                    id = "group:dom:$type",
                    label = type,
                    detail = "${patches.size}",
                    kind = FileTreeNodeKind.GROUP,
                    type = type,
                    children = patches.sortedBy { it.dataKey }.map { patch ->
                        FileTreeNode(
                            id = "dom:${patch.type}/${patch.dataKey}",
                            label = patch.dataKey,
                            detail = patch.targetKey?.name
                                ?: pack?.objectIndex?.find(patch.type, patch.dataKey)?.name,
                            kind = FileTreeNodeKind.DATA_OBJECT_MODIFY,
                            type = patch.type,
                            dataKey = patch.dataKey,
                        )
                    },
                )
            }
        val dataObjectModifySupported =
            project.format.dataObjectModifyDirectory("CardData") != null
        if (dataObjectModifySupported && (!filter.patchesOnly || dataObjectChildren.isNotEmpty())) {
            nodes += FileTreeNode(
                id = "group:dom",
                label = "DataObjectModify",
                detail = "${tree.dataObjectModifies.size}",
                kind = FileTreeNodeKind.GROUP,
                children = dataObjectChildren,
            )
        }

        if (filter.type == null) {
            nodes += FileTreeNode(
                id = "localization",
                label = project.format.localizationRoot(),
                detail = "${tree.localization.table.size} 个 Key",
                kind = FileTreeNodeKind.LOCALIZATION,
            )
            nodes += FileTreeNode(
                id = "resources",
                label = "Resource",
                detail = "${tree.resources.size}",
                kind = FileTreeNodeKind.RESOURCE,
            )
        }

        if (!filter.patchesOnly && filter.includePackObjects) {
            val packTypes = pack?.objectIndex?.referenceTypes.orEmpty().sorted()
            val typeGroups = packTypes.mapNotNull { type ->
                val entries = pack?.objectIndex?.entriesOfType(type).orEmpty()
                if (entries.isEmpty()) return@mapNotNull null
                FileTreeNode(
                    id = "pack:group:$type",
                    label = type,
                    detail = "${entries.size}",
                    kind = FileTreeNodeKind.GROUP,
                    type = type,
                    children = entries.sortedBy { it.name }.map { entry ->
                        FileTreeNode(
                            id = "pack:${entry.type}/${entry.name}",
                            label = entry.name,
                            detail = entry.displayName?.takeIf { it.isNotBlank() }
                                ?: if (entry.hasDataFile) "只读" else "仅索引",
                            kind = FileTreeNodeKind.PACK_OBJECT,
                            type = entry.type,
                            name = entry.name,
                        )
                    },
                )
            }
            if (typeGroups.isNotEmpty()) {
                nodes += FileTreeNode(
                    id = "group:pack",
                    label = "数据包引用对象（只读）",
                    detail = "${typeGroups.sumOf { it.children.size }}",
                    kind = FileTreeNodeKind.GROUP,
                    children = typeGroups,
                )
            }
        }

        return filter(nodes, filter)
    }

    private class FolderBuilder(val name: String) {
        val folders = LinkedHashMap<String, FolderBuilder>()
        val leaves = mutableListOf<FileTreeNode>()

        fun ensure(segments: List<String>): FolderBuilder {
            var current = this
            segments.forEach { segment ->
                current = current.folders.getOrPut(segment) { FolderBuilder(segment) }
            }
            return current
        }

        fun leafCount(): Int = leaves.size + folders.values.sumOf { it.leafCount() }
    }

    /**
     * 把对象/补丁叶子按相对目录组织成多级文件夹节点。
     * [folderPaths] 用于显示尚未包含对象的空文件夹。
     */
    private fun buildFolderChildren(
        idPrefix: String,
        type: String?,
        folderPaths: Collection<String>,
        leaves: List<FileTreeNode>,
    ): List<FileTreeNode> {
        val root = FolderBuilder("")
        folderPaths.forEach { path ->
            val segments = path.split('/').filter { it.isNotBlank() }
            if (segments.isNotEmpty()) root.ensure(segments)
        }
        leaves.forEach { leaf ->
            val segments = leaf.folder?.split('/')?.filter { it.isNotBlank() }.orEmpty()
            root.ensure(segments).leaves += leaf
        }

        fun convert(builder: FolderBuilder, parentPath: String): List<FileTreeNode> {
            val result = mutableListOf<FileTreeNode>()
            builder.folders.toSortedMap().forEach { (name, child) ->
                val path = if (parentPath.isBlank()) name else "$parentPath/$name"
                val count = child.leafCount()
                result += FileTreeNode(
                    id = "$idPrefix:$path",
                    label = name,
                    detail = count.takeIf { it > 0 }?.toString(),
                    kind = FileTreeNodeKind.GROUP,
                    type = type,
                    folder = path,
                    children = convert(child, path),
                )
            }
            result += builder.leaves.sortedBy { it.label }
            return result
        }

        return convert(root, "")
    }

    fun availableTypes(project: ModProject?): List<String> {
        val tree = project?.workingTree ?: return emptyList()
        return (tree.objects.values.map { it.type } + tree.folders.map { it.type })
            .distinct()
            .sorted()
    }

    private fun filter(nodes: List<FileTreeNode>, filter: FileTreeFilter): List<FileTreeNode> {
        val keyword = filter.keyword.trim()
        val type = filter.type
        if (keyword.isEmpty() && type == null) return nodes

        fun matches(node: FileTreeNode): Boolean {
            if (keyword.isEmpty()) return true
            return node.label.contains(keyword, ignoreCase = true) ||
                node.detail?.contains(keyword, ignoreCase = true) == true ||
                node.type?.contains(keyword, ignoreCase = true) == true ||
                node.name?.contains(keyword, ignoreCase = true) == true ||
                node.targetGuid?.contains(keyword, ignoreCase = true) == true
        }

        fun visit(node: FileTreeNode): FileTreeNode? {
            if (type != null && node.type != null && node.type != type) {
                return null
            }
            val children = node.children.mapNotNull(::visit)
            if (children.isNotEmpty()) return node.copy(children = children)
            if (node.children.isNotEmpty()) return null
            return node.takeIf(::matches)
        }

        return nodes.mapNotNull(::visit)
    }
}
