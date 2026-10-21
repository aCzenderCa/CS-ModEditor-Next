package org.zender.app.ui.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.zender.app.workspace.WorkspaceController
import org.zender.app.workspace.WorkspaceUiState
import org.zender.editor.graph.GraphBuildOptions
import org.zender.editor.graph.GraphLayoutEngine
import org.zender.editor.graph.GraphLayoutMode
import org.zender.editor.graph.GraphLayoutOptions
import org.zender.editor.graph.GraphNode
import org.zender.editor.graph.GraphNodeOrigin
import org.zender.editor.graph.GraphStatsCalculator
import org.zender.editor.graph.ObjectGraph
import org.zender.editor.graph.ObjectGraphBuilder
import org.zender.editor.objects.ObjectKey
import kotlin.math.exp

private data class StarMapFilters(
    val keyword: String = "",
    val type: String? = null,
)

@Composable
fun StarMapPage(
    controller: WorkspaceController,
    state: WorkspaceUiState,
) {
    val project = state.project
    if (project == null) {
        EmptyHint(
            title = "尚未打开工程",
            hint = "打开工程后可以在星图中查看对象引用关系。",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    var includeBaseTargets by remember { mutableStateOf(false) }
    var includeResources by remember { mutableStateOf(false) }
    var includePatches by remember { mutableStateOf(true) }
    var layoutMode by remember { mutableStateOf(GraphLayoutMode.FORCE) }
    var folderBlocks by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(StarMapFilters()) }
    var layoutMenuOpen by remember { mutableStateOf(false) }
    var typeMenuOpen by remember { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf<ObjectKey?>(null) }
    var hoveredKey by remember { mutableStateOf<ObjectKey?>(null) }
    var draggingKey by remember { mutableStateOf<ObjectKey?>(null) }

    val buildOptions = remember(includeBaseTargets, includeResources, includePatches) {
        GraphBuildOptions(
            includeProjectObjects = true,
            includePatches = includePatches,
            includeBaseTargets = includeBaseTargets,
            includeResources = includeResources,
        )
    }
    val graph = remember(project, state.pack, buildOptions) {
        ObjectGraphBuilder(state.pack).build(project, buildOptions)
    }
    val types = remember(graph) { graph.nodes.map { it.type }.distinct().sorted() }
    val visibleGraph = remember(graph, filters) {
        filterGraph(graph, filters)
    }
    LaunchedEffect(visibleGraph.nodes) {
        val focusKey = state.starMapFocus?.key
        if (selectedKey != null && selectedKey != focusKey &&
            visibleGraph.node(selectedKey!!) == null
        ) {
            selectedKey = null
        }
        if (hoveredKey != null && visibleGraph.node(hoveredKey!!) == null) hoveredKey = null
    }
    LaunchedEffect(state.starMapFocus) {
        val request = state.starMapFocus ?: return@LaunchedEffect
        filters = StarMapFilters()
        hoveredKey = null
        selectedKey = request.key
        // 引用目标可能是本体对象或资源；若当前图未包含，则自动打开对应来源。
        if (graph.node(request.key) == null) {
            includeBaseTargets = true
            includeResources = true
        }
    }
    val selectedNode = selectedKey?.let(visibleGraph::node)

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = filters.keyword,
                onValueChange = { filters = filters.copy(keyword = it) },
                modifier = Modifier.width(220.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                placeholder = { Text("搜索节点", style = MaterialTheme.typography.bodySmall) },
            )
            Box {
                TextButton(onClick = { typeMenuOpen = true }) {
                    Text(filters.type ?: "全部类型", style = MaterialTheme.typography.labelMedium)
                }
                DropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("全部类型") },
                        onClick = {
                            filters = filters.copy(type = null)
                            typeMenuOpen = false
                        },
                    )
                    types.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type) },
                            onClick = {
                                filters = filters.copy(type = type)
                                typeMenuOpen = false
                            },
                        )
                    }
                }
            }
            Box {
                TextButton(onClick = { layoutMenuOpen = true }) {
                    Text(
                        text = when (layoutMode) {
                            GraphLayoutMode.FORCE -> "布局：力导向"
                            GraphLayoutMode.GROUPED -> "布局：分组"
                            GraphLayoutMode.LAYERED -> "布局：分层"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                DropdownMenu(expanded = layoutMenuOpen, onDismissRequest = { layoutMenuOpen = false }) {
                    listOf(
                        GraphLayoutMode.FORCE to "力导向",
                        GraphLayoutMode.GROUPED to "分组",
                        GraphLayoutMode.LAYERED to "分层",
                    ).forEach { (mode, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                layoutMode = mode
                                layoutMenuOpen = false
                            },
                        )
                    }
                }
            }
            GraphToggle("本体目标", includeBaseTargets) { includeBaseTargets = it }
            GraphToggle("资源", includeResources) { includeResources = it }
            GraphToggle("补丁", includePatches) { includePatches = it }
            GraphToggle("文件夹分块", folderBlocks) { folderBlocks = it }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "节点 ${visibleGraph.stats.nodeCount} · 边 ${visibleGraph.stats.edgeCount} · " +
                    "孤立 ${visibleGraph.stats.isolatedNodes} · 未解析 ${visibleGraph.stats.unresolvedEdges} · " +
                    "环 ${visibleGraph.stats.cycleCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalSeparator()
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            StarMapCanvas(
                graph = visibleGraph,
                fullGraph = graph,
                layoutMode = layoutMode,
                folderBlocks = folderBlocks,
                selectedKey = selectedKey,
                hoveredKey = hoveredKey,
                onSelect = { selectedKey = it },
                onHover = { hoveredKey = it },
                onDragState = { node, _ ->
                    draggingKey = node
                },
                onDropReference = { node ->
                    controller.state.value.session.selection.lastFocusedReferenceField?.let { ref ->
                        controller.applyGraphReference(node, ref)
                    }
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            )
            StarMapDetails(
                controller = controller,
                state = state,
                node = selectedNode,
                dragging = draggingKey != null,
                onClose = { selectedKey = null },
                modifier = Modifier.width(400.dp).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun GraphToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, modifier = Modifier.size(28.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun filterGraph(graph: ObjectGraph, filters: StarMapFilters): ObjectGraph {
    val keyword = filters.keyword.trim()
    val nodes = graph.nodes.filter { node ->
        (filters.type == null || node.type == filters.type) &&
            (keyword.isEmpty() ||
                node.displayName.contains(keyword, ignoreCase = true) ||
                node.type.contains(keyword, ignoreCase = true) ||
                node.group?.contains(keyword, ignoreCase = true) == true ||
                node.folder?.contains(keyword, ignoreCase = true) == true ||
                node.key.name.contains(keyword, ignoreCase = true))
    }
    val keys = nodes.mapTo(HashSet()) { it.key }
    val edges = graph.edges.filter { it.from in keys && it.to in keys }
    return ObjectGraph(
        nodes = nodes,
        edges = edges,
        stats = GraphStatsCalculator.calculate(nodes, edges),
    )
}

@Composable
private fun StarMapCanvas(
    graph: ObjectGraph,
    fullGraph: ObjectGraph,
    layoutMode: GraphLayoutMode,
    folderBlocks: Boolean,
    selectedKey: ObjectKey?,
    hoveredKey: ObjectKey?,
    onSelect: (ObjectKey?) -> Unit,
    onHover: (ObjectKey?) -> Unit,
    onDragState: (ObjectKey?, Offset?) -> Unit,
    onDropReference: (GraphNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (graph.nodes.isEmpty()) {
        EmptyHint(
            title = "没有可显示的节点",
            hint = "调整筛选条件，或在设置中开启本体目标/资源。",
            modifier = modifier,
        )
        return
    }

    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val nodeRadius = with(density) { 11.dp.toPx() }
    val edgeStroke = with(density) { 1.4.dp.toPx() }
    val selectedStroke = with(density) { 2.5.dp.toPx() }
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.onSurface)
    val groupStyle = TextStyle(fontSize = 9.sp, color = colors.onSurfaceVariant)
    val textMeasurer = rememberTextMeasurer()
    val labelLayouts = remember(graph.nodes, labelStyle) {
        graph.nodes.associate { node -> node.key to textMeasurer.measure(node.displayName, labelStyle) }
    }
    val groupLayouts = remember(graph.nodes, groupStyle) {
        graph.nodes
            .filter { it.type == "CardData" && !it.group.isNullOrBlank() }
            .mapNotNull { node ->
                node.group?.let { group ->
                    node.key to textMeasurer.measure(group, groupStyle)
                }
            }
            .toMap()
    }
    val colorByOrigin = remember(colors) {
        mapOf(
            GraphNodeOrigin.PROJECT_ADDED to colors.primary,
            GraphNodeOrigin.PROJECT_MODIFIED to colors.secondary,
            GraphNodeOrigin.BASE_TARGET to colors.outline,
            GraphNodeOrigin.RESOURCE to colors.tertiary,
            GraphNodeOrigin.UNRESOLVED to colors.error,
        )
    }

    // 星图绘制（节点、标签、拖动连线）必须裁剪在画布内，避免缩放/平移后盖住工具栏与详情面板。
    BoxWithConstraints(modifier = modifier.clipToBounds().background(colors.surface)) {
        val widthPx = with(density) { maxWidth.toPx().takeIf { it.isFinite() && it > 20f } } ?: 800f
        val heightPx = with(density) { maxHeight.toPx().takeIf { it.isFinite() && it > 20f } } ?: 600f
        val layout = remember(graph, layoutMode, folderBlocks, widthPx, heightPx) {
            GraphLayoutEngine.layout(
                graph,
                GraphLayoutOptions(
                    width = widthPx,
                    height = heightPx,
                    padding = with(density) { 56.dp.toPx() },
                    mode = layoutMode,
                    blockByFolder = folderBlocks,
                ),
            )
        }
        val blockLabelStyle = TextStyle(fontSize = 10.sp, color = colors.onSurfaceVariant)
        val blockLabelLayouts = remember(layout.blocks, blockLabelStyle) {
            layout.blocks.map { block ->
                block to textMeasurer.measure(block.label, blockLabelStyle)
            }
        }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var dragNode by remember { mutableStateOf<ObjectKey?>(null) }
        var dragPoint by remember { mutableStateOf<Offset?>(null) }
        val currentSelect by rememberUpdatedState(onSelect)
        val currentHover by rememberUpdatedState(onHover)
        val currentDragState by rememberUpdatedState(onDragState)
        val currentDropReference by rememberUpdatedState(onDropReference)

        val highlighted = remember(selectedKey, hoveredKey, graph.edges) {
            val focus = hoveredKey ?: selectedKey ?: return@remember emptySet<ObjectKey>()
            val keys = mutableSetOf(focus)
            graph.edges.forEach { edge ->
                if (edge.from == focus) keys += edge.to
                if (edge.to == focus) keys += edge.from
            }
            keys
        }

        fun hitTest(position: Offset): GraphNode? {
            var best: GraphNode? = null
            var bestDistance = Float.MAX_VALUE
            graph.nodes.forEach { node ->
                val point = layout.positions[node.key] ?: return@forEach
                val center = Offset(point.x * scale + offset.x, point.y * scale + offset.y)
                val distance = (center - position).getDistance()
                if (distance <= nodeRadius + 8f && distance < bestDistance) {
                    best = node
                    bestDistance = distance
                }
            }
            return best
        }

        fun zoomAt(pivot: Offset, factor: Float) {
            val next = (scale * factor).coerceIn(0.2f, 4f)
            if (next == scale) return
            offset = pivot - (pivot - offset) * (next / scale)
            scale = next
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(layout) {
                    detectTapGestures { position ->
                        currentSelect(hitTest(position)?.key)
                    }
                }
                .pointerInput(layout) {
                    detectDragGestures(
                        onDragStart = { position ->
                            val node = hitTest(position)?.key
                            dragNode = node
                            dragPoint = position
                            currentDragState(node, position)
                        },
                        onDrag = { change, amount ->
                            if (dragNode != null) {
                                dragPoint = change.position
                                currentDragState(dragNode, change.position)
                            } else {
                                offset += amount
                            }
                            change.consume()
                        },
                        onDragEnd = {
                            val dragging = dragNode
                            val pointer = dragPoint
                            if (dragging != null && pointer != null && pointer.x > size.width) {
                                fullGraph.node(dragging)?.let(currentDropReference)
                            }
                            dragNode = null
                            dragPoint = null
                            currentDragState(null, null)
                        },
                        onDragCancel = {
                            dragNode = null
                            dragPoint = null
                            currentDragState(null, null)
                        },
                    )
                }
                .pointerInput(layout) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            when (event.type) {
                                PointerEventType.Move ->
                                    currentHover(hitTest(event.changes.first().position)?.key)

                                PointerEventType.Exit -> currentHover(null)
                                else -> Unit
                            }
                        }
                    }
                }
                .pointerInput(layout) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) {
                                val change = event.changes.first()
                                // 滚轮每格约 1 单位；0.15 约等于每格 16% 的缩放，触控板连续滚动也平滑。
                                val factor = exp(-change.scrollDelta.y * 0.15f)
                                zoomAt(change.position, factor)
                                change.consume()
                            }
                        }
                    }
                },
        ) {
            blockLabelLayouts.forEach { (block, label) ->
                val topLeft = Offset(block.x * scale + offset.x, block.y * scale + offset.y)
                val size = Size(block.width * scale, block.height * scale)
                if (size.width <= 0f || size.height <= 0f) return@forEach
                drawRoundRect(
                    color = colors.surfaceVariant.copy(alpha = 0.28f),
                    topLeft = topLeft,
                    size = size,
                    cornerRadius = CornerRadius(10f),
                )
                drawRoundRect(
                    color = colors.outlineVariant.copy(alpha = 0.75f),
                    topLeft = topLeft,
                    size = size,
                    cornerRadius = CornerRadius(10f),
                    style = Stroke(1f),
                )
                if (scale >= 0.35f) {
                    drawText(
                        textLayoutResult = label,
                        topLeft = topLeft + Offset(8f, 5f),
                    )
                }
            }

            graph.edges.forEach { edge ->
                val fromPoint = layout.positions[edge.from] ?: return@forEach
                val toPoint = layout.positions[edge.to] ?: return@forEach
                val from = Offset(fromPoint.x * scale + offset.x, fromPoint.y * scale + offset.y)
                val to = Offset(toPoint.x * scale + offset.x, toPoint.y * scale + offset.y)
                val focusEdge = (selectedKey != null && (edge.from == selectedKey || edge.to == selectedKey)) ||
                    (hoveredKey != null && (edge.from == hoveredKey || edge.to == hoveredKey))
                val dim = highlighted.isNotEmpty() &&
                    edge.from !in highlighted && edge.to !in highlighted
                val color = when {
                    !edge.resolved -> colors.error.copy(alpha = if (dim) 0.15f else 0.8f)
                    focusEdge -> colors.primary.copy(alpha = 0.95f)
                    else -> colors.onSurfaceVariant.copy(alpha = if (dim) 0.12f else 0.45f)
                }
                drawLine(
                    color = color,
                    start = from,
                    end = to,
                    strokeWidth = if (focusEdge) edgeStroke * 1.6f else edgeStroke,
                    pathEffect = if (!edge.resolved) {
                        PathEffect.dashPathEffect(floatArrayOf(7f, 5f), 0f)
                    } else {
                        null
                    },
                )
            }

            graph.nodes.forEach { node ->
                val point = layout.positions[node.key] ?: return@forEach
                val center = Offset(point.x * scale + offset.x, point.y * scale + offset.y)
                val radius = (nodeRadius * scale).coerceIn(4f, nodeRadius * 2.2f)
                val dim = highlighted.isNotEmpty() && node.key !in highlighted
                val baseColor = colorByOrigin[node.origin] ?: colors.onSurface
                drawCircle(
                    color = baseColor.copy(alpha = if (dim) 0.2f else 1f),
                    radius = radius,
                    center = center,
                )
                if (node.key == selectedKey || node.key == hoveredKey) {
                    drawCircle(
                        color = colors.onSurface,
                        radius = radius + selectedStroke / 2f,
                        center = center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(selectedStroke),
                    )
                }
                if (!dim && (scale >= 0.55f || node.key == selectedKey || node.key == hoveredKey)) {
                    val label = labelLayouts[node.key] ?: return@forEach
                    val labelTopLeft = Offset(
                        center.x - label.size.width / 2f,
                        center.y + radius + 2f,
                    )
                    drawText(
                        textLayoutResult = label,
                        topLeft = labelTopLeft,
                    )
                    groupLayouts[node.key]?.let { group ->
                        drawText(
                            textLayoutResult = group,
                            topLeft = Offset(
                                center.x - group.size.width / 2f,
                                labelTopLeft.y + label.size.height + 1f,
                            ),
                        )
                    }
                }
            }

            val dragging = dragNode
            val pointer = dragPoint
            if (dragging != null && pointer != null) {
                val point = layout.positions[dragging]
                if (point != null) {
                    drawLine(
                        color = colors.primary,
                        start = Offset(point.x * scale + offset.x, point.y * scale + offset.y),
                        end = pointer,
                        strokeWidth = edgeStroke * 1.4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                    )
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GraphIconButton(Icons.Outlined.ZoomIn, "放大") {
                    zoomAt(Offset(widthPx / 2f, heightPx / 2f), 1.2f)
                }
                GraphIconButton(Icons.Outlined.ZoomOut, "缩小") {
                    zoomAt(Offset(widthPx / 2f, heightPx / 2f), 1 / 1.2f)
                }
                GraphIconButton(Icons.Outlined.CenterFocusStrong, "重置视图") {
                    scale = 1f
                    offset = Offset.Zero
                }
            }
            Text(
                text = "缩放 ${(scale * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }

        if (dragNode != null) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(10.dp),
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
            ) {
                Text(
                    text = "拖到右侧字段面板并松开以写入引用",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun GraphIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun StarMapDetails(
    controller: WorkspaceController,
    state: WorkspaceUiState,
    node: GraphNode?,
    dragging: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (dragging) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }
    Surface(
        modifier = modifier.border(2.dp, borderColor),
        color = MaterialTheme.colorScheme.surface,
    ) {
        if (node == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("节点详情", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "点击节点查看字段；从节点拖到本面板可写入最近聚焦的引用字段。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.session.selection.lastFocusedReferenceField?.let { ref ->
                    Text(
                        text = "当前引用字段：${ref.path.format()}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            return@Surface
        }

        val docId = when (node.origin) {
            GraphNodeOrigin.PROJECT_ADDED,
            GraphNodeOrigin.PROJECT_MODIFIED,
            -> if (state.project?.objectOf(node.key.type, node.key.name) != null) {
                WorkspaceController.objectDocumentId(node.key.type, node.key.name)
            } else {
                WorkspaceController.packDocumentId(node.key.type, node.key.name)
            }

            GraphNodeOrigin.BASE_TARGET ->
                WorkspaceController.packDocumentId(node.key.type, node.key.name)

            else -> null
        }
        val target = docId?.let { state.targets[it] }
        val referenceField = state.session.selection.lastFocusedReferenceField
        val referenceInfo = referenceField?.let {
            controller.fieldInfo(it.documentId, it.path)
        }
        val referenceFieldType = referenceInfo?.let { info ->
            if (info.isReferenceList) {
                info.schemaField.elementTypeName ?: info.typeName ?: info.schemaField.typeName
            } else {
                info.typeName ?: info.schemaField.typeName
            }
        }
        var showNewObject by remember(node.key) { mutableStateOf(false) }

        LaunchedEffect(node.key, node.origin) {
            if (node.origin != GraphNodeOrigin.RESOURCE &&
                node.origin != GraphNodeOrigin.UNRESOLVED
            ) {
                controller.openGraphNode(node)
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = node.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(node.type)
                            if (node.type == "CardData") {
                                node.group?.takeIf { it.isNotBlank() }?.let {
                                    append(" · ").append(it)
                                }
                            }
                            node.folder?.takeIf { it.isNotBlank() }?.let {
                                append(" · ").append(it)
                            }
                            append(" · ").append(originLabel(node.origin))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭详情", modifier = Modifier.size(16.dp))
                }
            }
            HorizontalSeparator()
            if (node.origin == GraphNodeOrigin.RESOURCE) {
                ResourceNodeDetails(controller, node, Modifier.fillMaxSize())
            } else if (node.origin == GraphNodeOrigin.UNRESOLVED) {
                EmptyHint(
                    title = "未解析引用",
                    hint = "目标 ${node.key.name} 在工程与数据包中都不存在；可在原始视图中修正。",
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (target != null) {
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = if (referenceField != null) {
                                "引用字段：${referenceField.path.format()}"
                            } else {
                                "先在字段中点击引用字段，再拖入节点"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            enabled = referenceField != null,
                            onClick = {
                                referenceField?.let { controller.applyGraphReference(node, it) }
                            },
                        ) { Text("写入引用", style = MaterialTheme.typography.labelSmall) }
                        OutlinedButton(
                            enabled = referenceField != null && referenceFieldType != null,
                            onClick = { showNewObject = true },
                        ) { Text("新建对象并引用", style = MaterialTheme.typography.labelSmall) }
                    }
                    HorizontalSeparator()
                    FieldEditorView(controller, state, docId, target)
                }
            } else {
                EmptyHint(
                    title = "该目标没有可编辑文档",
                    hint = "可能只有索引条目，或目标来自其他 Mod。",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (showNewObject && referenceField != null && referenceFieldType != null) {
            NewObjectDialog(
                controller = controller,
                state = state,
                presetType = referenceFieldType.takeIf { it !in GENERIC_REFERENCE_TYPES },
                onDismiss = { showNewObject = false },
                onCreated = { created ->
                    controller.applyGraphReference(
                        GraphNode(
                            key = created.key,
                            type = created.type,
                            displayName = created.name,
                            origin = GraphNodeOrigin.PROJECT_ADDED,
                        ),
                        referenceField,
                    )
                },
            )
        }
    }
}

private val GENERIC_REFERENCE_TYPES = setOf(
    "ScriptableObject",
    "UnityEngine.Object",
    "Object",
    "UniqueIDScriptable",
)

@Composable
private fun ResourceNodeDetails(
    controller: WorkspaceController,
    node: GraphNode,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FileImage(
            path = controller.graphResourcePath(node),
            modifier = Modifier.fillMaxWidth().height(220.dp),
            contentDescription = node.key.name,
        )
        KeyValue("资源键", node.key.name, mono = true)
        KeyValue("类型", node.type)
        KeyValue("来源", "工程/数据包资源")
        node.relativePath?.let { KeyValue("相对路径", it, mono = true) }
    }
}

private fun originLabel(origin: GraphNodeOrigin): String = when (origin) {
    GraphNodeOrigin.PROJECT_ADDED -> "工程对象"
    GraphNodeOrigin.PROJECT_MODIFIED -> "工程修改"
    GraphNodeOrigin.BASE_TARGET -> "本体目标"
    GraphNodeOrigin.RESOURCE -> "资源"
    GraphNodeOrigin.UNRESOLVED -> "未解析"
}
