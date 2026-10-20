package org.zender.editor.project

import org.zender.editor.fixture.TestFixtures
import org.zender.editor.pack.DataPackLoader
import org.zender.editor.resource.ResourceKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class ModProjectWriterTest {

    private val basePack = DataPackLoader().load(TestFixtures.miniPack()).packOrThrow()
    private val tempDirectories = mutableListOf<Path>()

    private fun temp(): Path =
        createTempDirectory("csmode-writer-").also { tempDirectories.add(it) }

    @AfterTest
    fun cleanup() {
        tempDirectories.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `ML 工程写入后可重新读取`() {
        val project = MlProjectReader().read(TestFixtures.sampleMod("ml-sample"), basePack).projectOrThrow()
        val target = temp().resolve("ml-out")

        val write = ModProjectWriter().write(project, target)

        assertTrue(write.isSuccess, write.diagnostics.joinToString())
        assertTrue("ModInfo.json" in write.writtenFiles)
        assertTrue("CardData/FixtureKnife.json" in write.writtenFiles)
        assertTrue("ScriptableObject/CardTag/tag_Fixture.json" in write.writtenFiles)
        assertTrue("GameSourceModify/Fixture/11111111111111111111111111111111.json" in write.writtenFiles)
        assertTrue("Localization/SimpCn.csv" in write.writtenFiles)
        assertTrue("Localization/SimpEn.csv" in write.writtenFiles)
        assertTrue("Resource/Picture/fixture.png" in write.writtenFiles)

        val reread = MlProjectReader().read(target, basePack)
        assertTrue(reread.isSuccess, reread.diagnostics.joinToString())
        val copy = reread.projectOrThrow()

        assertEquals(project.metadata.namespace, copy.metadata.namespace)
        assertEquals(project.metadata.version, copy.metadata.version)
        assertTrue(copy.metadata.raw?.contains("CustomMeta") == true)
        assertEquals(project.workingTree.objectCount, copy.workingTree.objectCount)
        assertEquals(
            project.objectOf("CardData", "FixtureKnife")?.document?.root,
            copy.objectOf("CardData", "FixtureKnife")?.document?.root,
        )
        assertEquals(
            project.workingTree.gameSourceModify("11111111111111111111111111111111")
                ?.document?.root,
            copy.workingTree.gameSourceModify("11111111111111111111111111111111")
                ?.document?.root,
        )
        assertEquals(
            project.workingTree.localization.table.entries,
            copy.workingTree.localization.table.entries,
        )
        assertNotNull(copy.workingTree.resource(ResourceKind.IMAGE, "fixture"))
    }

    @Test
    fun `MC 工程写入后可重新读取`() {
        val project = McProjectReader().read(TestFixtures.sampleMod("mc-sample"), basePack).projectOrThrow()
        val target = temp().resolve("mc-out")

        val write = ModProjectWriter().write(project, target)

        assertTrue(write.isSuccess, write.diagnostics.joinToString())
        assertTrue("ModMeta.json" in write.writtenFiles)
        assertTrue("Data/CardData/FixtureKnife.json" in write.writtenFiles)
        assertTrue("Data/ScriptableObject/CardTag/tag_McFixture.json" in write.writtenFiles)
        assertTrue("Data/GameSourceModify/Fixture/11111111111111111111111111111111.json" in write.writtenFiles)
        assertTrue("Data/DataObjectModify/CardTag/FixtureMc@tag_McFixture.json" in write.writtenFiles)
        assertTrue("Resource/Localization/En.csv" in write.writtenFiles)
        assertTrue("Resource/Localization/SimpCn.csv" in write.writtenFiles)
        assertTrue("Resource/Texture2D/fixture.png" in write.writtenFiles)

        val reread = McProjectReader().read(target, basePack)
        assertTrue(reread.isSuccess, reread.diagnostics.joinToString())
        val copy = reread.projectOrThrow()

        assertEquals(project.metadata.namespace, copy.metadata.namespace)
        assertTrue(copy.metadata.raw?.contains("CustomPluginField") == true)
        assertEquals(project.workingTree.objectCount, copy.workingTree.objectCount)
        assertEquals(project.workingTree.dataObjectModifies.size, copy.workingTree.dataObjectModifies.size)
        assertNotNull(copy.workingTree.dataObjectModify("CardTag", "FixtureMc:tag_McFixture"))
        assertEquals(
            project.workingTree.localization.table.entries,
            copy.workingTree.localization.table.entries,
        )
        assertNotNull(copy.workingTree.resource(ResourceKind.IMAGE, "fixture"))
    }

    @Test
    fun `没有保存目录且未指定目标时失败`() {
        val project = MlProjectReader()
            .read(TestFixtures.sampleMod("ml-sample"), basePack)
            .projectOrThrow()
            .withRoot(null)

        val write = ModProjectWriter().write(project)

        assertTrue(!write.isSuccess)
        assertTrue(write.diagnostics.any { it.message.contains("保存目录") })
    }

    @Test
    fun `非法 MC 命名空间拒绝写入`() {
        val project = McProjectReader()
            .read(TestFixtures.sampleMod("mc-sample"), basePack)
            .projectOrThrow()
        val invalid = project.copy(metadata = project.metadata.copy(namespace = "Bad:Name"))
        val target = temp().resolve("invalid")

        val write = ModProjectWriter().write(invalid, target)

        assertTrue(!write.isSuccess)
        assertTrue(write.diagnostics.any { it.message.contains("Namespace") })
        assertTrue(!Files.exists(target.resolve("ModMeta.json")))
    }
}
