package org.zender.app.ui.workspace

import org.zender.editor.pack.DataPack
import org.zender.editor.project.ModProject

enum class FileTreeNodeKind {
    METADATA,
    GROUP,
    OBJECT,
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
    val children: List<FileTreeNode> = emptyList(),
) {
    val isLeaf: Boolean get() = children.isEmpty()
}

data class FileTreeFilter(
    val keyword: String = "",
    /** 只显示指定对象类型；null 表示不过滤。 */
    val type: String? = null,
    val patchesOnly: Boolean = false,
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

            uniqueIdTypes.keys.sorted().forEach { type ->
                val children = uniqueIdTypes.getValue(type)
                    .sortedBy { it.name }
                    .map { entry ->
                        FileTreeNode(
                            id = "object:${entry.type}/${entry.name}",
                            label = entry.name,
                            detail = entry.uniqueId?.take(8)?.plus("…"),
                            kind = FileTreeNodeKind.OBJECT,
                            type = entry.type,
                            name = entry.name,
                        )
                    }
                nodes += FileTreeNode(
                    id = "group:uid:$type",
                    label = type,
                    detail = "${children.size}",
                    kind = FileTreeNodeKind.GROUP,
                    type = type,
                    children = children,
                )
            }

            scriptableTypes.keys.sorted().forEach { type ->
                val children = scriptableTypes.getValue(type)
                    .sortedBy { it.name }
                    .map { entry ->
                        FileTreeNode(
                            id = "object:${entry.type}/${entry.name}",
                            label = entry.name,
                            kind = FileTreeNodeKind.OBJECT,
                            type = entry.type,
                            name = entry.name,
                        )
                    }
                nodes += FileTreeNode(
                    id = "group:so:$type",
                    label = "ScriptableObject/$type",
                    detail = "${children.size}",
                    kind = FileTreeNodeKind.GROUP,
                    type = type,
                    children = children,
                )
            }
        }

        val gameSourceChildren = tree.gameSourceModifies.values
            .sortedBy { it.targetGuid }
            .map { patch ->
                val target = patch.targetKey ?: pack?.objectEntryByGuid(patch.targetGuid)?.key
                FileTreeNode(
                    id = "gsm:${patch.targetGuid}",
                    label = patch.targetGuid,
                    detail = target?.let { "${it.type}/${it.name}" },
                    kind = FileTreeNodeKind.GAME_SOURCE_MODIFY,
                    targetGuid = patch.targetGuid,
                )
            }
        if (gameSourceChildren.isNotEmpty()) {
            nodes += FileTreeNode(
                id = "group:gsm",
                label = project.format.gameSourceModifyDirectory(),
                detail = "${gameSourceChildren.size}",
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
        if (dataObjectChildren.isNotEmpty()) {
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

        return filter(nodes, filter)
    }

    fun availableTypes(project: ModProject?): List<String> =
        project?.workingTree?.objects?.values?.map { it.type }?.distinct()?.sorted().orEmpty()

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
            if (type != null && node.kind == FileTreeNodeKind.OBJECT && node.type != type) {
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
