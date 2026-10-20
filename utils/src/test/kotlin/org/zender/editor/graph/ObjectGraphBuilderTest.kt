package org.zender.editor.graph

import org.zender.editor.document.JsonDocument
import org.zender.editor.fixture.TestFixtures
import org.zender.editor.framework.FrameworkId
import org.zender.editor.framework.MlFormat
import org.zender.editor.framework.ModMetadata
import org.zender.editor.objects.ObjectKey
import org.zender.editor.pack.DataPackLoader
import org.zender.editor.project.GameSourceModify
import org.zender.editor.project.ModProject
import org.zender.editor.project.ProjectObject
import org.zender.editor.project.ProjectObjectKind
import org.zender.editor.project.WorkingTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ObjectGraphBuilderTest {

    private val pack = DataPackLoader().load(TestFixtures.probePack()).packOrThrow()

    private fun objectEntry(
        name: String,
        json: String,
    ): ProjectObject {
        val key = ObjectKey.of("Probe", name)
        return ProjectObject(
            key = key,
            kind = ProjectObjectKind.SCRIPTABLE_OBJECT,
            document = JsonDocument.parseOrThrow(json),
            relativePath = "Probe/$name.json",
        )
    }

    private fun project(
        objects: List<ProjectObject>,
        patches: List<GameSourceModify> = emptyList(),
    ): ModProject = ModProject(
        root = null,
        format = MlFormat,
        metadata = ModMetadata(framework = FrameworkId.ML, namespace = "TestMod"),
        workingTree = WorkingTree(
            objects = objects.associateBy { it.key },
            gameSourceModifies = patches.associateBy { it.targetGuid },
        ),
        basePack = pack,
    )

    private fun probeProject(): ModProject {
        val a = objectEntry(
            "ProbeA",
            """{
              "Target": {},
              "TargetWarpData": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "TargetWarpType": 3,
              "Tags": [],
              "TagsWarpData": ["ProbeTag"],
              "TagsWarpType": 3
            }""",
        )
        val b = objectEntry(
            "ProbeB",
            """{
              "Target": {},
              "TargetWarpData": "ffffffffffffffffffffffffffffffff",
              "TargetWarpType": 3
            }""",
        )
        val c = objectEntry("ProbeC", """{"Count": 1}""")
        return project(listOf(a, b, c))
    }

    @Test
    fun `解析工程引用与未解析引用`() {
        val graph = ObjectGraphBuilder(pack).build(
            probeProject(),
            GraphBuildOptions(includeBaseTargets = true),
        )
        val a = ObjectKey.of("Probe", "ProbeA")
        val b = ObjectKey.of("Probe", "ProbeB")
        val card = ObjectKey.of("CardData", "ProbeCard", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val tag = ObjectKey.of("CardTag", "ProbeTag")

        assertEquals(6, graph.nodes.size)
        assertEquals(3, graph.edges.size)
        assertTrue(graph.edges.any { it.from == a && it.to == card && it.resolved })
        assertTrue(graph.edges.any { it.from == a && it.to == tag && it.resolved })
        assertTrue(graph.edges.any { it.from == b && !it.resolved })
        assertEquals(1, graph.stats.unresolvedEdges)
        assertTrue(graph.nodes.any { it.key.type == "CardData" && it.origin == GraphNodeOrigin.BASE_TARGET })
        assertTrue(graph.nodes.any { it.origin == GraphNodeOrigin.UNRESOLVED })
    }

    @Test
    fun `默认隐藏本体目标并统计孤立节点`() {
        val graph = ObjectGraphBuilder(pack).build(probeProject())
        val a = ObjectKey.of("Probe", "ProbeA")
        val c = ObjectKey.of("Probe", "ProbeC")
        assertEquals(4, graph.nodes.size)
        assertEquals(1, graph.edges.size)
        assertTrue(graph.nodes.none { it.origin == GraphNodeOrigin.BASE_TARGET })
        assertTrue(graph.stats.isolatedNodes >= 2)
        assertTrue(graph.nodes.first { it.key == a }.origin == GraphNodeOrigin.PROJECT_ADDED)
        assertTrue(graph.nodes.first { it.key == c }.origin == GraphNodeOrigin.PROJECT_ADDED)
    }

    @Test
    fun `补丁目标在关闭本体目标时仍保留`() {
        val target = ObjectKey.of("CardData", "ProbeCard", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val patch = GameSourceModify(
            targetGuid = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            document = JsonDocument.parseOrThrow("""{"ObjectWeight":0.8}"""),
            relativePath = "GameSourceModify/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.json",
            targetKey = target,
        )
        val graph = ObjectGraphBuilder(pack).build(
            project(listOf(objectEntry("ProbeA", """{"Count":1}""")), listOf(patch)),
            GraphBuildOptions(includeBaseTargets = false),
        )
        val node = assertNotNull(graph.node(target))
        assertEquals(GraphNodeOrigin.BASE_TARGET, node.origin)
        assertTrue(node.isPatchTarget)
    }

    @Test
    fun `有 Name 字段的工程对象使用 Name 而不是文件名作为显示名`() {
        val localized = ObjectKey.of("Named", "FileName")
        val fallbackKey = ObjectKey.of("Named", "FallbackFile")
        val project = project(
            listOf(
                ProjectObject(
                    key = localized,
                    kind = ProjectObjectKind.SCRIPTABLE_OBJECT,
                    document = JsonDocument.parseOrThrow(
                        """{"Name":{"ParentObjectID":"","LocalizationKey":"Named_Name","DefaultText":"Display Name"},"Count":1}""",
                    ),
                    relativePath = "Named/FileName.json",
                ),
                ProjectObject(
                    key = fallbackKey,
                    kind = ProjectObjectKind.SCRIPTABLE_OBJECT,
                    document = JsonDocument.parseOrThrow(
                        """{"Name":{"ParentObjectID":"","LocalizationKey":"Missing_Key","DefaultText":"Fallback Name"},"Count":1}""",
                    ),
                    relativePath = "Named/FallbackFile.json",
                ),
            ),
        )

        val graph = ObjectGraphBuilder(pack).build(project)
        assertEquals("名称显示", assertNotNull(graph.node(localized)).displayName)
        assertEquals("Fallback Name", assertNotNull(graph.node(fallbackKey)).displayName)
    }

    @Test
    fun `CardData 使用 CardName 而不是文件名作为显示名`() {
        val cardPack = DataPackLoader().load(TestFixtures.miniPack()).packOrThrow()
        val key = ObjectKey.of("CardData", "MyCard")
        val card = ProjectObject(
            key = key,
            kind = ProjectObjectKind.SCRIPTABLE_OBJECT,
            document = JsonDocument.parseOrThrow(
                """{"CardName":{"ParentObjectID":"","LocalizationKey":"MiniKnife_CardName","DefaultText":"Mini Knife"},"CardType":0,"ObjectWeight":0.1}""",
            ),
            relativePath = "CardData/Item/MyCard.json",
        )
        val cardProject = ModProject(
            root = null,
            format = MlFormat,
            metadata = ModMetadata(framework = FrameworkId.ML, namespace = "TestMod"),
            workingTree = WorkingTree(objects = mapOf(key to card)),
            basePack = cardPack,
        )

        val graph = ObjectGraphBuilder(cardPack).build(cardProject)
        val node = assertNotNull(graph.node(key))
        assertEquals("迷你小刀", node.displayName)
        assertEquals("Item", node.group)
    }

    @Test
    fun `统计环与连通分量`() {
        val a = ObjectKey.of("Probe", "A")
        val b = ObjectKey.of("Probe", "B")
        val c = ObjectKey.of("Probe", "C")
        val nodes = listOf(a, b, c).map {
            GraphNode(it, it.type, it.name, GraphNodeOrigin.PROJECT_ADDED)
        }
        val edges = listOf(
            GraphEdge(a, b, "Target", org.zender.editor.objects.ReferenceKind.UNIQUE_ID, true),
            GraphEdge(b, a, "Target", org.zender.editor.objects.ReferenceKind.UNIQUE_ID, true),
        )
        val stats = GraphStatsCalculator.calculate(nodes, edges)
        assertEquals(1, stats.isolatedNodes)
        assertEquals(1, stats.cycleCount)
        assertEquals(2, stats.componentCount)
    }

    @Test
    fun `布局确定且坐标在画布内`() {
        val graph = ObjectGraphBuilder(pack).build(
            probeProject(),
            GraphBuildOptions(includeBaseTargets = true),
        )
        val options = GraphLayoutOptions(width = 900f, height = 600f, padding = 40f)
        GraphLayoutMode.entries.forEach { mode ->
            val first = GraphLayoutEngine.layout(graph, options.copy(mode = mode))
            val second = GraphLayoutEngine.layout(graph, options.copy(mode = mode))
            assertEquals(first.positions, second.positions)
            assertEquals(graph.nodes.map { it.key }.toSet(), first.positions.keys)
            first.positions.values.forEach { point ->
                assertTrue(point.x.isFinite() && point.y.isFinite())
                assertTrue(point.x in 0f..900f)
                assertTrue(point.y in 0f..600f)
            }
        }
    }

    @Test
    fun `分层布局遇到环不会无限循环`() {
        val a = ObjectKey.of("Probe", "A")
        val b = ObjectKey.of("Probe", "B")
        val nodes = listOf(a, b).map { GraphNode(it, it.type, it.name, GraphNodeOrigin.PROJECT_ADDED) }
        val edges = listOf(
            GraphEdge(a, b, "Target", org.zender.editor.objects.ReferenceKind.UNIQUE_ID, true),
            GraphEdge(b, a, "Target", org.zender.editor.objects.ReferenceKind.UNIQUE_ID, true),
        )
        val graph = ObjectGraph(nodes, edges, GraphStatsCalculator.calculate(nodes, edges))
        val result = GraphLayoutEngine.layout(
            graph,
            GraphLayoutOptions(width = 400f, height = 300f, mode = GraphLayoutMode.LAYERED),
        )
        assertEquals(2, result.positions.size)
    }
}
