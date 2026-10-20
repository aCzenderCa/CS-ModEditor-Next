package org.zender.editor.project

import org.zender.editor.fixture.TestFixtures
import org.zender.editor.objects.ObjectKey
import org.zender.editor.pack.DataPackLoader
import org.zender.editor.resource.ResourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class MlProjectReaderTest {

    private val basePack = DataPackLoader().load(TestFixtures.miniPack()).packOrThrow()

    private fun read() = MlProjectReader().read(TestFixtures.sampleMod("ml-sample"), basePack)

    @Test
    fun `读取 ML 元数据与工作树`() {
        val result = read()

        assertTrue(result.isSuccess, result.diagnostics.joinToString())
        val project = result.projectOrThrow()
        assertEquals(org.zender.editor.framework.FrameworkId.ML, project.framework)
        assertEquals("FixtureML", project.namespace)
        assertEquals("Fixture", project.metadata.author)
        assertTrue(project.metadata.raw?.contains("CustomMeta") == true)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.joinToString())
    }

    @Test
    fun `读取 UniqueID 与 ScriptableObject 对象`() {
        val tree = read().projectOrThrow().workingTree

        val card = assertNotNull(tree.objectOf("CardData", "FixtureKnife"))
        assertEquals(ProjectObjectKind.UNIQUE_ID, card.kind)
        assertEquals("33333333333333333333333333333333", card.uniqueId)
        assertEquals("CardData/FixtureKnife.json", card.relativePath)

        val tag = assertNotNull(tree.objectOf("CardTag", "tag_Fixture"))
        assertEquals(ProjectObjectKind.SCRIPTABLE_OBJECT, tag.kind)
        assertEquals(null, tag.uniqueId)
        assertEquals("ScriptableObject/CardTag/tag_Fixture.json", tag.relativePath)
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
        assertEquals(
            "GameSourceModify/Fixture/11111111111111111111111111111111.json",
            patch.relativePath,
        )
    }

    @Test
    fun `读取本地化与资源`() {
        val tree = read().projectOrThrow().workingTree

        assertEquals(3, tree.localization.table.size)
        assertEquals(
            "测试小刀",
            assertNotNull(tree.localization.entry("FixtureML_FixtureKnife_CardName")).chinese,
        )
        assertEquals(
            listOf("Localization/SimpCn.csv", "Localization/SimpEn.csv"),
            tree.localization.files.map { it.relativePath },
        )

        assertEquals(1, tree.resourceCount)
        val resource = assertNotNull(tree.resource(ResourceKind.IMAGE, "fixture"))
        assertEquals("Sprite", resource.typeName)
        assertEquals("Resource/Picture/fixture.png", resource.relativePath)
        assertTrue(resource.sizeBytes > 0)
    }

    @Test
    fun `缺少 ModInfo 时读取失败`() {
        val result = MlProjectReader().read(TestFixtures.fixture("mods"))

        assertTrue(!result.isSuccess)
        assertTrue(result.diagnostics.any { it.message.contains("ModInfo.json") })
    }
}
