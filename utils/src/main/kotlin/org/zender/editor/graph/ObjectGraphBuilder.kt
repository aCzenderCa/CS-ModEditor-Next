package org.zender.editor.graph

import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.edit.WarpFields
import org.zender.editor.objects.ObjectIndex
import org.zender.editor.objects.ObjectKey
import org.zender.editor.objects.ReferenceKind
import org.zender.editor.pack.DataPack
import org.zender.editor.project.GameSourceModify
import org.zender.editor.project.ModProject
import org.zender.editor.project.ProjectFolderPath
import org.zender.editor.project.ProjectReferenceResolver
import org.zender.editor.project.ReferenceCheck
import org.zender.editor.project.ReferenceOrigin
import org.zender.editor.project.SchemaWalker
import org.zender.editor.schema.FieldKind
import org.zender.editor.schema.FieldSchema
import org.zender.editor.schema.SchemaRepository

/**
 * 从 Mod 工程构建对象引用图。
 *
 * - 节点：工程对象、补丁目标、被引用的数据包目标、资源、未解析目标；
 * - 边：字段/列表元素中的 `WarpData` 引用；
 * - 补丁中的引用从补丁目标节点出发；
 * - 未解析/类型不符的引用生成 UNRESOLVED 节点，边标记为未解析。
 */
class ObjectGraphBuilder(
    private val basePack: DataPack? = null,
) {

    fun build(
        project: ModProject,
        options: GraphBuildOptions = GraphBuildOptions(),
    ): ObjectGraph {
        val pack = basePack ?: project.basePack
        val schemas = pack?.schemaRepository
        val rule = pack?.referenceRule
        val resolver = ProjectReferenceResolver(project, pack)

        val rawNodes = LinkedHashMap<ObjectKey, GraphNode>()
        val rawEdges = mutableListOf<GraphEdge>()
        val baseNameCache = HashMap<ObjectKey, String>()

        fun localizedNameText(key: String): String? {
            if (key.isBlank()) return null
            val projectEntry = project.workingTree.localization.entry(key)
            val table = pack?.localization
            return projectEntry?.chinese?.takeIf { it.isNotBlank() }
                ?: table?.chinese(key)?.takeIf { it.isNotBlank() }
                ?: projectEntry?.english?.takeIf { it.isNotBlank() }
                ?: table?.english(key)?.takeIf { it.isNotBlank() }
        }

        fun nameFieldText(node: JsonNode?): String? = when (node) {
            is JsonNode.StringNode -> node.value.trim().takeIf { it.isNotEmpty() }
            is JsonNode.ObjectNode -> {
                val key = (node.get("LocalizationKey") as? JsonNode.StringNode)?.value
                localizedNameText(key.orEmpty())
                    ?: (node.get("DefaultText") as? JsonNode.StringNode)
                        ?.value
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
            }

            else -> null
        }

        /**
         * 类型 Schema 中的“类 Name”字段，例如 `Name`、`CardName`、`InGameName`。
         * 精确 `Name` 优先，其次 `CardName` / `InGameName`，其余保持 Schema 字段顺序。
         */
        fun nameFieldNames(typeName: String): List<String> {
            val schema = schemas?.schema(typeName) ?: return emptyList()
            return schema.fields
                .map { it.name }
                .filter { it == "Name" || it.endsWith("Name") }
                .distinct()
                .sortedBy { name ->
                    when (name) {
                        "Name" -> 0
                        "CardName" -> 1
                        "InGameName" -> 2
                        "DisplayName" -> 3
                        else -> 4
                    }
                }
        }

        /** `CardData` 的分组即 `CardType` 枚举名（Item/Event/...），供星图额外显示。 */
        fun cardTypeGroup(typeName: String, document: JsonDocument): String? {
            if (typeName != ObjectIndex.CARD_DATA_TYPE) return null
            val field = schemas?.schema(typeName)?.field("CardType") ?: return null
            val root = document.root as? JsonNode.ObjectNode ?: return null
            val value = root.get("CardType") ?: return null
            return field.enumValues.firstOrNull { it.value == value }?.name
        }

        /** 用对象数据里的类 Name 字段作为显示名，避免星图显示文件名。 */
        fun displayNameFor(
            typeName: String,
            document: JsonDocument,
            fallback: String,
        ): String {
            val fields = nameFieldNames(typeName)
            if (fields.isEmpty()) return fallback
            val root = document.root as? JsonNode.ObjectNode ?: return fallback
            fields.forEach { field ->
                nameFieldText(root.get(field))?.let { return it }
            }
            return fallback
        }

        fun baseDisplayName(entry: org.zender.editor.objects.ObjectEntry): String {
            entry.displayName?.takeIf { it.isNotBlank() }?.let { return it }
            return baseNameCache.getOrPut(entry.key) {
                if (nameFieldNames(entry.type).isEmpty() || entry.relativePath == null) {
                    entry.name
                } else {
                    runCatching {
                        displayNameFor(entry.type, pack!!.readObject(entry), entry.name)
                    }.getOrDefault(entry.name)
                }
            }
        }

        fun addNode(
            key: ObjectKey,
            origin: GraphNodeOrigin,
            relativePath: String? = null,
            isPatchTarget: Boolean = false,
            group: String? = null,
            displayName: String? = null,
        ) {
            val folder = ProjectFolderPath.folderOf(relativePath, key.type)
            val existing = rawNodes[key]
            if (existing == null) {
                rawNodes[key] = GraphNode(
                    key = key,
                    type = key.type,
                    displayName = displayName ?: key.name,
                    origin = origin,
                    group = group,
                    folder = folder,
                    relativePath = relativePath,
                    isPatchTarget = isPatchTarget,
                )
                return
            }
            val mergedOrigin = when {
                existing.origin == GraphNodeOrigin.UNRESOLVED &&
                    origin != GraphNodeOrigin.UNRESOLVED -> origin

                existing.origin == GraphNodeOrigin.PROJECT_ADDED &&
                    origin == GraphNodeOrigin.PROJECT_MODIFIED -> GraphNodeOrigin.PROJECT_MODIFIED

                existing.origin == GraphNodeOrigin.BASE_TARGET &&
                    origin == GraphNodeOrigin.PROJECT_MODIFIED -> GraphNodeOrigin.PROJECT_MODIFIED

                else -> existing.origin
            }
            rawNodes[key] = existing.copy(
                origin = mergedOrigin,
                displayName = existing.displayName.takeIf { it.isNotBlank() }
                    ?: displayName?.takeIf { it.isNotBlank() }
                    ?: existing.displayName,
                group = existing.group ?: group,
                folder = existing.folder ?: folder,
                relativePath = existing.relativePath ?: relativePath,
                isPatchTarget = existing.isPatchTarget || isPatchTarget,
            )
        }

        fun targetInfo(key: ObjectKey, origin: ReferenceOrigin): Pair<String?, String?> {
            return when (origin) {
                ReferenceOrigin.PROJECT -> {
                    val entry = project.workingTree.objects[key]
                    if (entry == null) {
                        key.name to null
                    } else {
                        displayNameFor(entry.type, entry.document, entry.name) to entry.relativePath
                    }
                }

                ReferenceOrigin.BASE_PACK -> {
                    val entry = pack?.objectEntry(key.type, key.name)
                    (entry?.let(::baseDisplayName) ?: key.name) to entry?.relativePath
                }

                ReferenceOrigin.PROJECT_RESOURCE -> {
                    val resource = project.workingTree.resources.values
                        .firstOrNull { it.key == key.name }
                    key.name to resource?.relativePath
                }

                ReferenceOrigin.BASE_PACK_RESOURCE -> {
                    val resource = pack?.resources?.find(key.name)
                    val indexed = pack?.objectIndex?.find(key.type, key.name)
                    (indexed?.displayText ?: key.name) to
                        (resource?.relativePath ?: indexed?.relativePath)
                }
            }
        }

        fun addResolvedTarget(check: ReferenceCheck.Resolved) {
            val key = check.targetKey ?: return
            val origin = when (check.origin) {
                ReferenceOrigin.PROJECT -> GraphNodeOrigin.PROJECT_ADDED
                ReferenceOrigin.BASE_PACK -> GraphNodeOrigin.BASE_TARGET
                ReferenceOrigin.PROJECT_RESOURCE,
                ReferenceOrigin.BASE_PACK_RESOURCE,
                -> GraphNodeOrigin.RESOURCE
            }
            val (name, relativePath) = targetInfo(key, check.origin)
            val group = if (check.origin == ReferenceOrigin.BASE_PACK) {
                pack?.objectEntry(key.type, key.name)?.group
            } else {
                project.workingTree.objects[key]?.let { entry ->
                    cardTypeGroup(entry.type, entry.document)
                        ?: pack?.objectEntry(entry.type, entry.name)?.group
                }
            }
            addNode(
                key = key,
                origin = origin,
                relativePath = relativePath,
                group = group,
                displayName = name,
            )
        }

        fun walkReferences(
            ownerKey: ObjectKey,
            typeName: String,
            document: JsonDocument,
            sourcePath: String?,
        ) {
            val repository: SchemaRepository = schemas ?: return
            val root = document.root as? JsonNode.ObjectNode ?: return
            val walker = SchemaWalker(repository)
            val visitor = object : SchemaWalker.SchemaVisitor {
                    override fun onReference(
                        typeName: String,
                        field: FieldSchema,
                        path: JsonPath,
                        owner: JsonNode.ObjectNode,
                    ) {
                        val isList = field.kind == FieldKind.LIST
                        val fieldType = if (isList) {
                            field.elementTypeName ?: field.typeName
                        } else {
                            field.typeName
                        }
                        val data = owner.get(WarpFields.dataName(field.name))
                        val keys = when (data) {
                            is JsonNode.StringNode -> listOf(data.value)
                            is JsonNode.ArrayNode -> data.elements.mapNotNull {
                                (it as? JsonNode.StringNode)?.value
                            }

                            else -> emptyList()
                        }
                        if (keys.isEmpty()) return
                        val kind = rule?.kindFor(fieldType) ?: guessKind(fieldType)
                        keys.forEachIndexed { index, rawKey ->
                            val key = rawKey.trim()
                            if (key.isEmpty()) return@forEachIndexed
                            val edgePath = if (isList) {
                                "${path.format()}[$index]"
                            } else {
                                path.format()
                            }
                            when (val check = resolver.resolve(fieldType, key)) {
                                is ReferenceCheck.Resolved -> {
                                    if (check.targetKey == null) return@forEachIndexed
                                    addResolvedTarget(check)
                                    rawEdges += GraphEdge(
                                        from = ownerKey,
                                        to = check.targetKey,
                                        fieldPath = edgePath,
                                        referenceKind = kind,
                                        resolved = true,
                                        sourceRelativePath = sourcePath,
                                    )
                                }

                                else -> {
                                    val ghost = ObjectKey.of(fieldType, key)
                                    addNode(
                                        key = ghost,
                                        origin = GraphNodeOrigin.UNRESOLVED,
                                        displayName = key,
                                    )
                                    rawEdges += GraphEdge(
                                        from = ownerKey,
                                        to = ghost,
                                        fieldPath = edgePath,
                                        referenceKind = kind,
                                        resolved = false,
                                        sourceRelativePath = sourcePath,
                                    )
                                }
                            }
                        }
                    }
                }
            walker.walkObject(typeName, root, JsonPath.ROOT, visitor)
            val override = root.get("\$override")
            if (override is JsonNode.ObjectNode) {
                walker.walkObject(typeName, override, JsonPath.key("\$override"), visitor)
            }
        }

        if (options.includeProjectObjects) {
            project.workingTree.objects.values.forEach { entry ->
                addNode(
                    key = entry.key,
                    origin = GraphNodeOrigin.PROJECT_ADDED,
                    relativePath = entry.relativePath,
                    group = cardTypeGroup(entry.type, entry.document)
                        ?: pack?.objectEntry(entry.type, entry.name)?.group,
                    displayName = displayNameFor(entry.type, entry.document, entry.name),
                )
            }
            project.workingTree.objects.values.forEach { entry ->
                walkReferences(entry.key, entry.type, entry.document, entry.relativePath)
            }
        }

        if (options.includePatches) {
            project.workingTree.gameSourceModifies.values.forEach { patch ->
                buildGameSourceModifyNode(project, pack, patch, ::addNode)
                val targetKey = resolveGameSourceModifyTarget(project, pack, patch)
                val type = targetKey?.type
                    ?: project.objectByGuid(patch.targetGuid)?.type
                    ?: pack?.objectEntryByGuid(patch.targetGuid)?.type
                if (targetKey != null && type != null) {
                    walkReferences(
                        ownerKey = targetKey,
                        typeName = type,
                        document = patch.document,
                        sourcePath = patch.relativePath,
                    )
                }
            }
            project.workingTree.dataObjectModifies.values.forEach { patch ->
                val targetKey = resolveDataObjectModifyTarget(project, pack, patch)
                val shortName = patch.dataKey
                    .substringAfterLast(':')
                    .substringAfterLast('@')
                if (targetKey != null) {
                    val projectEntry = project.workingTree.objects[targetKey]
                    addNode(
                        key = targetKey,
                        origin = if (projectEntry != null) {
                            GraphNodeOrigin.PROJECT_MODIFIED
                        } else {
                            GraphNodeOrigin.BASE_TARGET
                        },
                        relativePath = projectEntry?.relativePath,
                        group = projectEntry
                            ?.let { cardTypeGroup(it.type, it.document) }
                            ?: pack?.objectEntry(targetKey.type, targetKey.name)?.group,
                        isPatchTarget = true,
                        displayName = projectEntry
                            ?.let { displayNameFor(it.type, it.document, it.name) }
                            ?: pack?.objectEntry(targetKey.type, targetKey.name)?.let(::baseDisplayName)
                            ?: targetKey.name,
                    )
                    walkReferences(
                        ownerKey = targetKey,
                        typeName = patch.type,
                        document = patch.document,
                        sourcePath = patch.relativePath,
                    )
                } else {
                    val ghost = ObjectKey.of("Unresolved", "${patch.type}/$shortName")
                    addNode(ghost, GraphNodeOrigin.UNRESOLVED, displayName = patch.dataKey)
                }
            }
        }

        val nodes = rawNodes.values.filter { node ->
            when (node.origin) {
                GraphNodeOrigin.PROJECT_ADDED -> options.includeProjectObjects
                GraphNodeOrigin.PROJECT_MODIFIED ->
                    options.includeProjectObjects || options.includePatches || node.isPatchTarget

                GraphNodeOrigin.BASE_TARGET -> options.includeBaseTargets || node.isPatchTarget
                GraphNodeOrigin.RESOURCE -> options.includeResources
                GraphNodeOrigin.UNRESOLVED -> true
            }
        }
        val kept = nodes.mapTo(HashSet()) { it.key }
        val edges = rawEdges.filter { it.from in kept && it.to in kept }
        return ObjectGraph(
            nodes = nodes,
            edges = edges,
            stats = GraphStatsCalculator.calculate(nodes, edges),
        )
    }

    private fun buildGameSourceModifyNode(
        project: ModProject,
        pack: DataPack?,
        patch: GameSourceModify,
        addNode: (
            ObjectKey,
            GraphNodeOrigin,
            String?,
            Boolean,
            String?,
            String?,
        ) -> Unit,
    ) {
        val key = resolveGameSourceModifyTarget(project, pack, patch)
        if (key != null) {
            val isProject = project.workingTree.objects[key] != null
            addNode(
                key,
                if (isProject) GraphNodeOrigin.PROJECT_MODIFIED else GraphNodeOrigin.BASE_TARGET,
                if (isProject) {
                    project.workingTree.objects[key]?.relativePath
                } else {
                    pack?.objectEntry(key.type, key.name)?.relativePath
                },
                true,
                pack?.objectEntry(key.type, key.name)?.group,
                key.name,
            )
        } else {
            addNode(
                ObjectKey.of("Unresolved", patch.targetGuid),
                GraphNodeOrigin.UNRESOLVED,
                null,
                false,
                null,
                patch.targetGuid,
            )
        }
    }

    private fun resolveGameSourceModifyTarget(
        project: ModProject,
        pack: DataPack?,
        patch: GameSourceModify,
    ): ObjectKey? =
        patch.targetKey
            ?: project.workingTree.objectByGuid(patch.targetGuid)?.key
            ?: pack?.objectEntryByGuid(patch.targetGuid)?.key

    private fun resolveDataObjectModifyTarget(
        project: ModProject,
        pack: DataPack?,
        patch: org.zender.editor.project.DataObjectModify,
    ): ObjectKey? {
        if (patch.targetKey != null) return patch.targetKey
        val shortName = patch.dataKey.substringAfterLast(':').substringAfterLast('@')
        return project.workingTree.objectOf(patch.type, shortName)?.key
            ?: pack?.objectIndex?.find(patch.type, shortName)?.key
    }

    private fun guessKind(typeName: String): ReferenceKind = when (typeName) {
        "Sprite", "Texture", "Texture2D" -> ReferenceKind.SPRITE
        "AudioClip" -> ReferenceKind.AUDIO
        else -> ReferenceKind.PLAIN_OBJECT
    }
}

/**
 * 统计孤立节点、未解析边、环与连通分量。
 */
object GraphStatsCalculator {

    fun calculate(nodes: List<GraphNode>, edges: List<GraphEdge>): GraphStats {
        if (nodes.isEmpty()) return GraphStats()
        val degree = HashMap<ObjectKey, Int>()
        nodes.forEach { degree[it.key] = 0 }
        edges.forEach { edge ->
            degree[edge.from] = (degree[edge.from] ?: 0) + 1
            degree[edge.to] = (degree[edge.to] ?: 0) + 1
        }
        return GraphStats(
            nodeCount = nodes.size,
            edgeCount = edges.size,
            isolatedNodes = degree.values.count { it == 0 },
            unresolvedEdges = edges.count { !it.resolved },
            cycleCount = countCycles(nodes, edges),
            componentCount = countComponents(nodes, edges),
        )
    }

    private fun countCycles(nodes: List<GraphNode>, edges: List<GraphEdge>): Int {
        if (nodes.size > MAX_DFS_NODES) return 0
        val adjacency = edges.groupBy({ it.from }, { it.to })
        val state = HashMap<ObjectKey, Int>()
        var cycles = 0

        fun visit(key: ObjectKey) {
            when (state[key]) {
                1 -> {
                    cycles++
                    return
                }

                2 -> return
            }
            state[key] = 1
            adjacency[key].orEmpty().forEach(::visit)
            state[key] = 2
        }

        nodes.forEach { node ->
            if (state[node.key] == null) visit(node.key)
        }
        return cycles
    }

    private fun countComponents(nodes: List<GraphNode>, edges: List<GraphEdge>): Int {
        val adjacency = HashMap<ObjectKey, MutableList<ObjectKey>>()
        nodes.forEach { adjacency[it.key] = mutableListOf() }
        edges.forEach { edge ->
            adjacency.getOrPut(edge.from) { mutableListOf() } += edge.to
            adjacency.getOrPut(edge.to) { mutableListOf() } += edge.from
        }
        val visited = HashSet<ObjectKey>()
        var components = 0
        val queue = ArrayDeque<ObjectKey>()
        nodes.forEach { node ->
            if (!visited.add(node.key)) return@forEach
            components++
            queue.clear()
            queue += node.key
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                adjacency[current].orEmpty().forEach { next ->
                    if (visited.add(next)) queue += next
                }
            }
        }
        return components
    }

    private const val MAX_DFS_NODES = 5_000
}
