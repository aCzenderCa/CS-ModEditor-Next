package org.zender.editor.project

import org.zender.editor.fixture.TestFixtures
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.pack.DataPack
import org.zender.editor.pack.DataPackLoader
import org.zender.editor.resource.ResourceKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class ResourceImporterTest {

    private val fileStore = AtomicFileStore()
    private val basePack = DataPackLoader().load(TestFixtures.miniPack()).packOrThrow()
    private val importer = ResourceImporter(fileStore)
    private val tempDirectories = mutableListOf<Path>()

    private fun temp(): Path =
        createTempDirectory("csmode-resource-import-").also { tempDirectories.add(it) }

    @AfterTest
    fun cleanup() {
        tempDirectories.forEach { it.toFile().deleteRecursively() }
    }

    private fun copyDirectory(source: Path, target: Path): Path {
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        return target
    }

    private fun project(format: String): ModProject {
        val source = TestFixtures.sampleMod(format)
        val copy = copyDirectory(source, temp().resolve(format))
        return if (format == "ml-sample") {
            MlProjectReader(fileStore).read(copy, basePack).projectOrThrow()
        } else {
            McProjectReader(fileStore).read(copy, basePack).projectOrThrow()
        }
    }

    private fun externalFile(name: String, content: String = "data"): Path {
        val path = temp().resolve("external/$name")
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        return path
    }

    @Test
    fun `导入图片到 ML 工程`() {
        val project = project("ml-sample")
        val source = externalFile("new_pic.png", "png-bytes")

        val result = importer.import(project, source)

        assertTrue(result.isSuccess, result.diagnostics.joinToString())
        val resource = assertNotNull(result.resource)
        assertEquals(ResourceKind.IMAGE, resource.kind)
        assertEquals("new_pic", resource.key)
        assertEquals("Sprite", resource.typeName)
        assertEquals("Resource/Picture/new_pic.png", resource.relativePath)
        assertEquals(9L, resource.sizeBytes)
        assertTrue(Files.isRegularFile(project.root!!.resolve("Resource/Picture/new_pic.png")))
        assertNotNull(result.project.workingTree.resource(ResourceKind.IMAGE, "new_pic"))
    }

    @Test
    fun `导入音频到 MC 工程`() {
        val project = project("mc-sample")
        val source = externalFile("sound.wav", "wav")

        val result = importer.import(project, source)

        assertTrue(result.isSuccess, result.diagnostics.joinToString())
        assertEquals(
            "Resource/AudioClip/sound.wav",
            assertNotNull(result.resource).relativePath,
        )
        assertEquals("AudioClip", result.resource.typeName)
        assertTrue(Files.isRegularFile(project.root!!.resolve("Resource/AudioClip/sound.wav")))
    }

    @Test
    fun `项目内重名默认拒绝覆盖`() {
        val project = project("ml-sample")
        val source = externalFile("fixture.png", "new")

        val result = importer.import(project, source)

        assertTrue(!result.isSuccess)
        assertTrue(result.diagnostics.any { it.message.contains("已存在") })
        assertTrue(result.project.workingTree.resource(ResourceKind.IMAGE, "fixture") != null)
    }

    @Test
    fun `允许显式覆盖`() {
        val project = project("ml-sample")
        val source = externalFile("fixture.png", "new-bytes")

        val result = importer.import(project, source, overwrite = true)

        assertTrue(result.isSuccess, result.diagnostics.joinToString())
        assertEquals(
            "new-bytes",
            fileStore.readText(project.root!!.resolve("Resource/Picture/fixture.png")),
        )
    }

    @Test
    fun `不支持的扩展名与非法键被拒绝`() {
        val project = project("ml-sample")
        val text = externalFile("note.txt")

        val unsupported = importer.import(project, text)
        assertTrue(!unsupported.isSuccess)
        assertTrue(unsupported.diagnostics.any { it.message.contains("不支持") })

        val invalidKey = importer.import(project, externalFile("ok.png"), key = "bad:key")
        assertTrue(!invalidKey.isSuccess)
        assertTrue(invalidKey.diagnostics.any { it.message.contains("非法字符") })
    }

    @Test
    fun `没有保存目录时只登记资源`() {
        val project = project("ml-sample").withRoot(null)
        val source = externalFile("virtual.png")

        val result = importer.import(project, source)

        assertTrue(result.isSuccess, result.diagnostics.joinToString())
        val resource = assertNotNull(result.resource)
        assertEquals(source.toAbsolutePath().normalize(), resource.sourcePath)
        assertEquals("Resource/Picture/virtual.png", resource.relativePath)
    }

    @Test
    fun `与数据包内置资源重名时告警`() {
        val packRoot = temp().resolve("pack")
        copyDirectory(TestFixtures.miniPack(), packRoot)
        val builtin = packRoot.resolve("Resource/Texture2D/conflict.png")
        Files.createDirectories(builtin.parent)
        Files.writeString(builtin, "builtin")
        val pack: DataPack = DataPackLoader().load(packRoot).packOrThrow()

        val mcSource = copyDirectory(TestFixtures.sampleMod("mc-sample"), temp().resolve("mc"))
        val project = McProjectReader(fileStore).read(mcSource, pack).projectOrThrow()
        val source = externalFile("conflict.png")

        val result = importer.import(project, source)

        assertTrue(result.isSuccess, result.diagnostics.joinToString())
        assertTrue(result.diagnostics.any { it.message.contains("重名") })
    }
}
