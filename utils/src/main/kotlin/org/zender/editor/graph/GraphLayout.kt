package org.zender.editor.graph

import org.zender.editor.objects.ObjectKey
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class GraphLayoutMode {
    /** 力导向布局。 */
    FORCE,

    /** 按类型/分组排布。 */
    GROUPED,

    /** 按引用方向分层。 */
    LAYERED,
}

data class GraphLayoutOptions(
    val width: Float = 1200f,
    val height: Float = 800f,
    val padding: Float = 70f,
    val mode: GraphLayoutMode = GraphLayoutMode.FORCE,
    val iterations: Int = 180,
    /** 按子文件夹把画布划分为区块；与 [mode] 独立，块内仍使用 [mode] 排列。 */
    val blockByFolder: Boolean = false,
) {
    init {
        require(width > 0f && height > 0f) { "布局尺寸必须大于 0" }
        require(padding >= 0f) { "padding 不能为负数" }
        require(iterations >= 1) { "iterations 必须大于 0" }
    }
}

data class GraphPoint(val x: Float, val y: Float)

/**
 * 分块布局中的一个文件夹区块。坐标与尺寸使用画布坐标（未缩放）。
 */
data class GraphBlock(
    val label: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

data class GraphLayoutResult(
    val positions: Map<ObjectKey, GraphPoint>,
    val width: Float,
    val height: Float,
    /** 开启 `blockByFolder` 时返回文件夹区块；否则为空。 */
    val blocks: List<GraphBlock> = emptyList(),
)

/**
 * 纯数据的确定性布局。相同图与选项总是产生相同坐标。
 */
object GraphLayoutEngine {

    fun layout(
        graph: ObjectGraph,
        options: GraphLayoutOptions = GraphLayoutOptions(),
    ): GraphLayoutResult {
        val nodes = graph.nodes.sortedWith(
            compareBy<GraphNode> { it.key.type }.thenBy { it.key.name },
        )
        if (nodes.isEmpty()) {
            return GraphLayoutResult(emptyMap(), options.width, options.height)
        }
        val safePadding = options.padding.coerceIn(
            0f,
            max(0f, min(options.width, options.height) / 2f - 1f),
        )
        val safeOptions = options.copy(
            padding = safePadding,
            width = options.width.coerceAtLeast(safePadding * 2f + 2f),
            height = options.height.coerceAtLeast(safePadding * 2f + 2f),
        )
        val blocks = mutableListOf<GraphBlock>()
        val positions = if (safeOptions.blockByFolder) {
            blockByFolderLayout(graph, nodes, safeOptions, blocks)
        } else {
            arrange(graph, nodes, safeOptions)
        }
        return GraphLayoutResult(
            positions = positions,
            width = safeOptions.width,
            height = safeOptions.height,
            blocks = blocks,
        )
    }

    private fun forceLayout(
        graph: ObjectGraph,
        nodes: List<GraphNode>,
        options: GraphLayoutOptions,
    ): Map<ObjectKey, GraphPoint> {
        val n = nodes.size
        val width = options.width
        val height = options.height
        val padding = options.padding
        if (n == 1) {
            return mapOf(nodes.single().key to GraphPoint(width / 2f, height / 2f))
        }

        val indexByKey = nodes.mapIndexed { index, node -> node.key to index }.toMap()
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.34f
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        nodes.forEachIndexed { index, _ ->
            val angle = (2.0 * Math.PI * index / n).toFloat()
            xs[index] = cx + cos(angle) * radius
            ys[index] = cy + sin(angle) * radius
        }

        val area = max(1f, (width - padding * 2) * (height - padding * 2))
        val idealDistance = sqrt(area / n) * 0.85f
        val repulsion = idealDistance * idealDistance * 0.55f
        val springLength = idealDistance
        val springStrength = 0.045f
        val centerStrength = 0.012f
        val maxStep = idealDistance.coerceAtLeast(8f)
        val effectiveIterations = if (n > 400) min(options.iterations, 80) else options.iterations

        val fx = FloatArray(n)
        val fy = FloatArray(n)
        repeat(effectiveIterations) { iteration ->
            java.util.Arrays.fill(fx, 0f)
            java.util.Arrays.fill(fy, 0f)

            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    var dx = xs[i] - xs[j]
                    var dy = ys[i] - ys[j]
                    var distSq = dx * dx + dy * dy
                    if (distSq < 0.01f) {
                        dx = ((i * 31 + j * 17) % 7 - 3).toFloat() * 0.1f
                        dy = ((i * 13 + j * 29) % 7 - 3).toFloat() * 0.1f
                        distSq = dx * dx + dy * dy + 0.01f
                    }
                    val dist = sqrt(distSq)
                    val force = repulsion / distSq
                    val ux = dx / dist
                    val uy = dy / dist
                    fx[i] += ux * force
                    fy[i] += uy * force
                    fx[j] -= ux * force
                    fy[j] -= uy * force
                }
            }

            graph.edges.forEach { edge ->
                val from = indexByKey[edge.from] ?: return@forEach
                val to = indexByKey[edge.to] ?: return@forEach
                if (from == to) return@forEach
                val dx = xs[to] - xs[from]
                val dy = ys[to] - ys[from]
                val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
                val force = (dist - springLength) * springStrength
                val ux = dx / dist
                val uy = dy / dist
                fx[from] += ux * force
                fy[from] += uy * force
                fx[to] -= ux * force
                fy[to] -= uy * force
            }

            for (i in 0 until n) {
                fx[i] += (cx - xs[i]) * centerStrength
                fy[i] += (cy - ys[i]) * centerStrength
            }

            val cooling = 1f - iteration.toFloat() / effectiveIterations * 0.85f
            for (i in 0 until n) {
                val stepX = (fx[i] * cooling).coerceIn(-maxStep, maxStep)
                val stepY = (fy[i] * cooling).coerceIn(-maxStep, maxStep)
                xs[i] = (xs[i] + stepX).coerceIn(padding, width - padding)
                ys[i] = (ys[i] + stepY).coerceIn(padding, height - padding)
            }
        }

        return nodes.mapIndexed { index, node ->
            node.key to GraphPoint(xs[index], ys[index])
        }.toMap()
    }

    private fun groupedLayout(
        nodes: List<GraphNode>,
        options: GraphLayoutOptions,
    ): Map<ObjectKey, GraphPoint> {
        val groups = nodes.groupBy { it.group ?: it.type }
            .toSortedMap()
        val columns = ceil(sqrt(groups.size.toDouble())).toInt().coerceAtLeast(1)
        val rows = ceil(groups.size.toDouble() / columns).toInt().coerceAtLeast(1)
        val cellWidth = options.width / columns
        val cellHeight = options.height / rows
        val result = LinkedHashMap<ObjectKey, GraphPoint>()

        groups.entries.forEachIndexed { groupIndex, (_, groupNodes) ->
            val column = groupIndex % columns
            val row = groupIndex / columns
            val centerX = cellWidth * column + cellWidth / 2f
            val centerY = cellHeight * row + cellHeight / 2f
            val sorted = groupNodes.sortedWith(
                compareBy<GraphNode> { it.key.type }.thenBy { it.key.name },
            )
            if (sorted.size == 1) {
                result[sorted.single().key] = GraphPoint(
                    centerX.coerceIn(options.padding, options.width - options.padding),
                    centerY.coerceIn(options.padding, options.height - options.padding),
                )
                return@forEachIndexed
            }
            val radius = min(cellWidth, cellHeight) * 0.32f
            sorted.forEachIndexed { index, node ->
                val angle = (2.0 * Math.PI * index / sorted.size).toFloat() - Math.PI.toFloat() / 2f
                result[node.key] = GraphPoint(
                    (centerX + cos(angle) * radius)
                        .coerceIn(options.padding, options.width - options.padding),
                    (centerY + sin(angle) * radius)
                        .coerceIn(options.padding, options.height - options.padding),
                )
            }
        }
        return result
    }

    private fun arrange(
        graph: ObjectGraph,
        nodes: List<GraphNode>,
        options: GraphLayoutOptions,
    ): Map<ObjectKey, GraphPoint> = when (options.mode) {
        GraphLayoutMode.FORCE -> forceLayout(graph, nodes, options)
        GraphLayoutMode.GROUPED -> groupedLayout(nodes, options)
        GraphLayoutMode.LAYERED -> layeredLayout(graph, nodes, options)
    }

    /**
     * 按「类型 + 子文件夹」把画布划分为区块；块内仍按 [GraphLayoutOptions.mode] 排列。
     * 块标题写入 [blocks]，由视图层绘制到底层。
     */
    private fun blockByFolderLayout(
        graph: ObjectGraph,
        nodes: List<GraphNode>,
        options: GraphLayoutOptions,
        blocks: MutableList<GraphBlock>,
    ): Map<ObjectKey, GraphPoint> {
        val groups = nodes
            .groupBy { node -> node.type to (node.folder ?: "") }
            .toSortedMap(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        val columns = ceil(sqrt(groups.size.toDouble())).toInt().coerceAtLeast(1)
        val rows = ceil(groups.size.toDouble() / columns).toInt().coerceAtLeast(1)
        val contentWidth = (options.width - options.padding * 2f).coerceAtLeast(1f)
        val contentHeight = (options.height - options.padding * 2f).coerceAtLeast(1f)
        val cellWidth = contentWidth / columns
        val cellHeight = contentHeight / rows
        val blockGap = 8f
        val headerHeight = 18f
        val innerPadding = 10f
        val result = LinkedHashMap<ObjectKey, GraphPoint>()

        groups.entries.forEachIndexed { groupIndex, (groupKey, groupNodes) ->
            val column = groupIndex % columns
            val row = groupIndex / columns
            val left = options.padding + column * cellWidth
            val top = options.padding + row * cellHeight
            val width = (cellWidth - blockGap).coerceAtLeast(1f)
            val height = (cellHeight - blockGap).coerceAtLeast(1f)
            val label = if (groupKey.second.isBlank()) {
                groupKey.first
            } else {
                "${groupKey.first} / ${groupKey.second}"
            }
            blocks += GraphBlock(label, left, top, width, height)

            val areaLeft = left + innerPadding
            val areaTop = top + headerHeight
            val areaWidth = (width - innerPadding * 2f).coerceAtLeast(1f)
            val areaHeight = (height - headerHeight - innerPadding).coerceAtLeast(1f)
            val keys = groupNodes.mapTo(HashSet()) { it.key }
            val subGraph = ObjectGraph(
                nodes = groupNodes,
                edges = graph.edges.filter { it.from in keys && it.to in keys },
            )
            val subOptions = options.copy(
                width = areaWidth,
                height = areaHeight,
                padding = min(options.padding, min(areaWidth, areaHeight) / 4f),
                blockByFolder = false,
            )
            arrange(subGraph, groupNodes, subOptions).forEach { (key, point) ->
                result[key] = GraphPoint(point.x + areaLeft, point.y + areaTop)
            }
        }
        return result
    }

    private fun layeredLayout(
        graph: ObjectGraph,
        nodes: List<GraphNode>,
        options: GraphLayoutOptions,
    ): Map<ObjectKey, GraphPoint> {
        val keys = nodes.mapTo(LinkedHashSet()) { it.key }
        val incoming = HashMap<ObjectKey, MutableList<ObjectKey>>()
        val outgoing = HashMap<ObjectKey, MutableList<ObjectKey>>()
        keys.forEach {
            incoming[it] = mutableListOf()
            outgoing[it] = mutableListOf()
        }
        graph.edges.forEach { edge ->
            if (edge.from in keys && edge.to in keys && edge.from != edge.to) {
                outgoing.getOrPut(edge.from) { mutableListOf() } += edge.to
                incoming.getOrPut(edge.to) { mutableListOf() } += edge.from
            }
        }

        val layer = HashMap<ObjectKey, Int>()
        val queue = ArrayDeque<ObjectKey>()
        keys.filter { incoming[it].isNullOrEmpty() }
            .sortedWith(compareBy({ it.type }, { it.name }))
            .forEach {
                layer[it] = 0
                queue += it
            }
        if (queue.isEmpty()) {
            val first = keys.sortedWith(compareBy({ it.type }, { it.name })).first()
            layer[first] = 0
            queue += first
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val nextLayer = (layer[current] ?: 0) + 1
            outgoing[current].orEmpty().forEach { next ->
                if (layer[next] == null) {
                    layer[next] = nextLayer
                    queue += next
                }
            }
        }
        keys.forEach { key -> if (layer[key] == null) layer[key] = (layer.values.maxOrNull() ?: 0) + 1 }

        val maxLayer = layer.values.maxOrNull() ?: 0
        val usableWidth = options.width - options.padding * 2
        val stepX = if (maxLayer == 0) 0f else usableWidth / maxLayer
        val result = LinkedHashMap<ObjectKey, GraphPoint>()
        val byLayer = keys.groupBy { layer[it] ?: 0 }.toSortedMap()
        byLayer.forEach { (layerIndex, layerKeys) ->
            val sorted = layerKeys.sortedWith(compareBy({ it.type }, { it.name }))
            val x = (options.padding + stepX * layerIndex)
                .coerceIn(options.padding, options.width - options.padding)
            sorted.forEachIndexed { index, key ->
                val y = if (sorted.size == 1) {
                    options.height / 2f
                } else {
                    options.padding + (options.height - options.padding * 2) * index / (sorted.size - 1)
                }
                result[key] = GraphPoint(x, y)
            }
        }
        return result
    }

    private fun cos(value: Float): Float = kotlin.math.cos(value)

    private fun sin(value: Float): Float = kotlin.math.sin(value)
}
