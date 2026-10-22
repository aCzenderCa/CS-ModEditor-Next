package org.zender.editor.project

import org.zender.editor.document.JsonDocument
import org.zender.editor.localization.LocalizationEntry
import org.zender.editor.localization.LocalizationTable
import org.zender.editor.objects.ObjectKey
import org.zender.editor.resource.ResourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class WorkingTreeTest {

    private val document = JsonDocument.parseOrThrow("""{"A":1}""")

    private fun card(name: String, guid: String? = null): ProjectObject =
        ProjectObject(
            key = ObjectKey.of("CardData", name, guid),
            kind = ProjectObjectKind.UNIQUE_ID,
            document = document,
            relativePath = "CardData/$name.json",
        )

    @Test
    fun `按名称与 GUID 查询对象`() {
        val knife = card("Knife", "1111")
        val tree = WorkingTree().withObject(knife)

        assertEquals(knife, tree.objectOf("CardData", "Knife"))
        assertEquals(knife, tree.objectByGuid("1111"))
        assertNull(tree.objectOf("CardData", "Missing"))
        assertNull(tree.objectByGuid("missing"))
    }

    @Test
    fun `对象名冲突时拒绝写入`() {
        val first = card("Knife", "1111")
        val second = card("Knife", "2222")
        val tree = WorkingTree().withObject(first)

        assertFailsWith<IllegalArgumentException> {
            tree.withObject(second)
        }
    }

    @Test
    fun `GUID 冲突时拒绝写入`() {
        val first = card("Knife", "1111")
        val second = card("Fork", "1111")
        val tree = WorkingTree().withObject(first)

        assertFailsWith<IllegalArgumentException> {
            tree.withObject(second)
        }
    }

    @Test
    fun `删除对象`() {
        val tree = WorkingTree()
            .withObject(card("Knife", "1111"))
            .withoutObject("CardData", "Knife")

        assertTrue(tree.objects.isEmpty())
        assertEquals(tree, tree.withoutObject("CardData", "Missing"))
    }

    @Test
    fun `补丁与资源的增删`() {
        val patch = GameSourceModify(
            targetGuid = "1111",
            document = document,
            relativePath = "GameSourceModify/1111.json",
        )
        val dataPatch = DataObjectModify(
            type = "CardTag",
            dataKey = "FixtureMc:tag_X",
            document = document,
            relativePath = "Data/DataObjectModify/CardTag/FixtureMc@tag_X.json",
        )
        val resource = ProjectResource(
            id = ResourceId(ResourceKind.IMAGE, "knife"),
            typeName = "Sprite",
            relativePath = "Resource/Picture/knife.png",
        )

        val tree = WorkingTree()
            .withGameSourceModify(patch)
            .withDataObjectModify(dataPatch)
            .withResource(resource)

        assertEquals(patch, tree.gameSourceModify("1111"))
        assertEquals(dataPatch, tree.dataObjectModify("CardTag", "FixtureMc:tag_X"))
        assertEquals(resource, tree.resource(ResourceKind.IMAGE, "knife"))
        assertEquals(1, tree.resourceCount)

        val cleared = tree
            .withoutGameSourceModify("1111")
            .withoutDataObjectModify("CardTag", "FixtureMc:tag_X")
            .withoutResource(ResourceId(ResourceKind.IMAGE, "knife"))
        assertTrue(cleared.gameSourceModifies.isEmpty())
        assertTrue(cleared.dataObjectModifies.isEmpty())
        assertTrue(cleared.resources.isEmpty())
        // 删除的补丁路径会记录到 deletedPaths，保存工程时清理磁盘文件。
        assertTrue("GameSourceModify/1111.json" in cleared.deletedPaths)
        assertTrue("Data/DataObjectModify/CardTag/FixtureMc@tag_X.json" in cleared.deletedPaths)
    }

    @Test
    fun `本地化条目增删保持顺序`() {
        val localization = ProjectLocalization()
            .withEntry(LocalizationEntry("k1", "one", "一"))
            .withEntry(LocalizationEntry("k2", "two", "二"))
            .withEntry(LocalizationEntry("k1", "one2", "一"))

        assertEquals(listOf("k1", "k2"), localization.keys)
        assertEquals("one2", assertNotNull(localization.entry("k1")).english)

        val removed = localization.withoutKey("k1")
        assertEquals(listOf("k2"), removed.keys)
    }

    @Test
    fun `不修改原有快照`() {
        val base = WorkingTree()
        val updated = base.withObject(card("Knife", "1111"))

        assertTrue(base.objects.isEmpty())
        assertEquals(1, updated.objectCount)
        assertEquals(LocalizationTable.EMPTY, base.localization.table)
    }
}
