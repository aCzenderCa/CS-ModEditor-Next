package org.zender.editor.project

import org.zender.editor.fixture.TestFixtures
import org.zender.editor.framework.FrameworkId
import org.zender.editor.pack.DataPackLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ModProjectLoaderTest {

    private val loader = ModProjectLoader()
    private val basePack = DataPackLoader().load(TestFixtures.miniPack()).packOrThrow()
    private val tempDirectories = mutableListOf<Path>()

    private fun temp(): Path =
        createTempDirectory("csmode-project-").also { tempDirectories.add(it) }

    @AfterTest
    fun cleanup() {
        tempDirectories.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `按元数据文件识别框架`() {
        assertEquals(
            setOf(FrameworkId.ML),
            loader.detectFormats(TestFixtures.sampleMod("ml-sample")),
        )
        assertEquals(
            setOf(FrameworkId.MC),
            loader.detectFormats(TestFixtures.sampleMod("mc-sample")),
        )
    }

    @Test
    fun `自动读取 ML 与 MC 工程`() {
        val ml = loader.read(TestFixtures.sampleMod("ml-sample"), basePack)
        val mc = loader.read(TestFixtures.sampleMod("mc-sample"), basePack)

        assertTrue(ml.isSuccess, ml.diagnostics.joinToString())
        assertTrue(mc.isSuccess, mc.diagnostics.joinToString())
        assertEquals(FrameworkId.ML, ml.projectOrThrow().framework)
        assertEquals(FrameworkId.MC, mc.projectOrThrow().framework)
        assertEquals(2, ml.projectOrThrow().workingTree.objectCount)
        assertEquals(2, mc.projectOrThrow().workingTree.objectCount)
    }

    @Test
    fun `没有元数据文件时失败`() {
        val result = loader.read(temp())

        assertTrue(!result.isSuccess)
        assertTrue(result.diagnostics.any { it.message.contains("ModInfo.json") })
        assertTrue(result.diagnostics.any { it.message.contains("ModMeta.json") })
    }

    @Test
    fun `同时存在两种元数据时失败`() {
        val root = temp()
        Files.writeString(root.resolve("ModInfo.json"), """{"Name":"Both"}""")
        Files.writeString(root.resolve("ModMeta.json"), """{"Namespace":"Both"}""")

        val result = loader.read(root)

        assertTrue(!result.isSuccess)
        assertTrue(result.diagnostics.any { it.message.contains("无法确定框架") })
    }

    @Test
    fun `目录不存在时失败`() {
        val result = loader.read(temp().resolve("missing"))

        assertTrue(!result.isSuccess)
        assertTrue(result.diagnostics.any { it.message.contains("不存在") })
    }
}
