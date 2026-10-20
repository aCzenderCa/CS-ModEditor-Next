package org.zender.editor.project

import org.zender.editor.fixture.TestFixtures
import org.zender.editor.objects.ObjectKey
import org.zender.editor.pack.DataPackLoader
import org.zender.editor.resource.ResourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class McProjectReaderTest {

    private val basePack = DataPackLoader().load(TestFixtures.miniPack()).packOrThrow()

    private fun read() = McProjectReader().read(TestFixtures.sampleMod("mc-sample"), basePack)

    @Test
    fun `读取 MC 元数据与工作树`() {
        val result = read()

        assertTrue(result.isSuccess, result.diagnostics.joinToString())
        val project = result.projectOrThrow()
        assertEquals(org.zender.editor.framework.FrameworkId.MC, project.framework)
        assertEquals("FixtureMc", project.namespace)
        assertEquals("MC 测试 Mod", project.metadata.names["SimpCn"])
        assertTrue(project.metadata.raw?.contains("CustomPluginField") == true)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.joinToString())
    }

    @Test
    fun `读取 Data 下的对象`() {
        val tree = read().projectOrThrow().workingTree

        val card = assertNotNull(tree.objectOf("CardData", "FixtureKnife"))
        assertEquals(ProjectObjectKind.UNIQUE_ID, card.kind)
        assertEquals("44444444444444444444444444444444", card.uniqueId)
        assertEquals("Data/CardData/FixtureKnife.json", card.relativePath)

        val tag = assertNotNull(tree.objectOf("CardTag", "tag_McFixture"))
        assertEquals(ProjectObjectKind.SCRIPTABLE_OBJECT, tag.kind)
        assertEquals("Data/ScriptableObject/CardTag/tag_McFixture.json", tag.relativePath)
        assertEquals(2, tree.objectCount)
    }

    @Test
    fun `读取 GameSourceModify 并解析目标`() {
        val tree = read().projectOrThrow().workingTree
        val patch = assertNotNull(tree.gameSourceModify("11111111111111111111111111111111"))

        assertEquals(
            ObjectKey.of("CardData", "MiniKnife", "11111111111111111111111111111111"),
            patch.targetKey,
        )
    }

    @Test
    fun `读取 DataObjectModify 的 @ 文件名并解析目标`() {
        val tree = read().projectOrThrow().workingTree

        val baseTarget = assertNotNull(tree.dataObjectModify("CardTag", "tag_Mini"))
        assertEquals(
            ObjectKey.of("CardTag", "tag_Mini"),
            baseTarget.targetKey,
        )
        assertEquals(
            "Data/DataObjectModify/CardTag/tag_Mini.json",
            baseTarget.relativePath,
        )

        val projectTarget = assertNotNull(
            tree.dataObjectModify("CardTag", "FixtureMc:tag_McFixture"),
        )
        assertEquals(
            ObjectKey.of("CardTag", "tag_McFixture"),
            projectTarget.targetKey,
        )
        assertEquals(
            "Data/DataObjectModify/CardTag/FixtureMc@tag_McFixture.json",
            projectTarget.relativePath,
        )
        assertEquals(2, tree.dataObjectModifies.size)
    }

    @Test
    fun `读取按语言本地化与资源`() {
        val tree = read().projectOrThrow().workingTree
        val entry = assertNotNull(tree.localization.entry("FixtureMc_FixtureKnife_CardName"))

        assertEquals("MC Fixture Knife", entry.english)
        assertEquals("MC 测试小刀", entry.chinese)
        assertEquals(
            listOf("Resource/Localization/En.csv", "Resource/Localization/SimpCn.csv"),
            tree.localization.files.map { it.relativePath },
        )

        val resource = assertNotNull(tree.resource(ResourceKind.IMAGE, "fixture"))
        assertEquals("Resource/Texture2D/fixture.png", resource.relativePath)
    }

    @Test
    fun `缺少 ModMeta 时读取失败`() {
        val result = McProjectReader().read(TestFixtures.fixture("mods"))

        assertTrue(!result.isSuccess)
        assertTrue(result.diagnostics.any { it.message.contains("ModMeta.json") })
    }
}
