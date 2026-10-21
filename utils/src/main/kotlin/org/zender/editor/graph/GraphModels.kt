package org.zender.editor.graph

import org.zender.editor.objects.ObjectKey
import org.zender.editor.objects.ReferenceKind

/** 星图节点来源。 */
enum class GraphNodeOrigin {
    /** 工程中新建/编辑的对象。 */
    PROJECT_ADDED,

    /** 工程补丁修改的对象。 */
    PROJECT_MODIFIED,

    /** 游戏本体目标（只读幽灵节点）。 */
    BASE_TARGET,

    /** 图片/音频资源。 */
    RESOURCE,

    /** 未解析的引用目标。 */
    UNRESOLVED,
}

data class GraphNode(
    val key: ObjectKey,
    val type: String,
    val displayName: String,
    val origin: GraphNodeOrigin,
    val group: String? = null,
    /** 类型目录下的子文件夹路径（如 `Item/Weapons`），用于按文件夹分块布局。 */
    val folder: String? = null,
    /** 工程内相对路径或数据包内相对路径，供打开/预览。 */
    val relativePath: String? = null,
    /** 该节点是补丁目标；即使关闭“显示本体目标”也应保留。 */
    val isPatchTarget: Boolean = false,
)

data class GraphEdge(
    val from: ObjectKey,
    val to: ObjectKey,
    /** 引用字段路径；列表元素附带 `[index]`。 */
    val fieldPath: String,
    val referenceKind: ReferenceKind,
    /** 目标是否存在且类型兼容。 */
    val resolved: Boolean,
    /** 边来源（工程对象或补丁）的相对路径。 */
    val sourceRelativePath: String? = null,
)

data class GraphStats(
    val nodeCount: Int = 0,
    val edgeCount: Int = 0,
    val isolatedNodes: Int = 0,
    val unresolvedEdges: Int = 0,
    val cycleCount: Int = 0,
    val componentCount: Int = 0,
)

/**
 * 项目对象引用图快照。纯数据，不依赖 Compose。
 */
data class ObjectGraph(
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList(),
    val stats: GraphStats = GraphStats(),
) {
    private val nodeByKeyMap: Map<ObjectKey, GraphNode> = nodes.associateBy { it.key }

    fun node(key: ObjectKey): GraphNode? = nodeByKeyMap[key]

    val isEmpty: Boolean get() = nodes.isEmpty()

    companion object {
        val EMPTY: ObjectGraph = ObjectGraph()
    }
}

/**
 * 星图构建选项。默认只显示工程对象与补丁目标，避免真实数据包把图撑得过大。
 */
data class GraphBuildOptions(
    val includeProjectObjects: Boolean = true,
    val includePatches: Boolean = true,
    val includeBaseTargets: Boolean = false,
    val includeResources: Boolean = false,
)
