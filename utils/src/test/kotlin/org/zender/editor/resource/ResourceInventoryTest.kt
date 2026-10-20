package org.zender.editor.resource

import org.zender.editor.io.AtomicFileStore
import org.zender.editor.model.EntryOrigin
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ResourceInventoryTest {

    private val fileStore = AtomicFileStore()
    private val roots = mutableListOf<Path>()

    private fun tempRoot(): Path =
        createTempDirectory("csmode-resource-").also { roots.add(it) }

    private fun write(base: Path, relative: String, content: String = "x"): Path {
        val path = base.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        return path
    }

    @AfterTest
    fun cleanup() {
        roots.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `扫描图片与音频并生成键和元数据`() {
        val base = tempRoot()
        write(base, "Resource/Texture2D/b.png", "png")
        write(base, "Resource/Texture2D/sub/c.jpg", "jpg")
        write(base, "Resource/Picture/d.jpeg", "jpeg")
        write(base, "Resource/AudioClip/e.wav", "wav")
        write(base, "Resource/AudioClip/sub/f.mp3", "mp3")
        write(base, "Resource/Audio/g.ogg", "ogg")
        write(base, "Resource/Texture2D/note.txt", "ignored")

        val result = ResourceInventoryScanner.scan(fileStore, base)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.joinToString())

        assertEquals(6, result.inventory.size)
        val png = assertNotNull(result.inventory.find("b", ResourceKind.IMAGE))
        assertEquals("Sprite", png.typeName)
        assertEquals("png", png.extension)
        assertEquals("Resource/Texture2D/b.png", png.relativePath)
        assertEquals(3L, png.sizeBytes)
        assertEquals(EntryOrigin.BASE_PACK, png.origin)

        assertEquals(3, result.inventory.entriesOf(ResourceKind.IMAGE).size)
        assertEquals(3, result.inventory.entriesOf(ResourceKind.AUDIO).size)
        assertEquals("AudioClip", result.inventory.find("g", ResourceKind.AUDIO)?.typeName)
    }

    @Test
    fun `重复资源键保留首个并记录警告`() {
        val base = tempRoot()
        write(base, "Resource/Texture2D/dup.png")
        write(base, "Resource/Picture/dup.jpg")

        val result = ResourceInventoryScanner.scan(fileStore, base)

        assertEquals(1, result.inventory.size)
        assertEquals(
            "Resource/Texture2D/dup.png",
            result.inventory.find("dup", ResourceKind.IMAGE)?.relativePath,
        )
        assertEquals(1, result.diagnostics.size)
        assertTrue(result.diagnostics.single().message.contains("重复"))
    }

    @Test
    fun `缺少资源目录时返回空清单`() {
        val base = tempRoot()

        val result = ResourceInventoryScanner.scan(fileStore, base)

        assertTrue(result.inventory.entries.isEmpty())
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `资源键为文件名去扩展名`() {
        val base = tempRoot()
        write(base, "Resource/Texture2D/with.multiple.dots.png")

        val result = ResourceInventoryScanner.scan(fileStore, base)

        assertNotNull(result.inventory.find("with.multiple.dots", ResourceKind.IMAGE))
        assertNull(result.inventory.find("with", ResourceKind.IMAGE))
    }

    @Test
    fun `可指定来源与自定义根目录`() {
        val base = tempRoot()
        write(base, "MyResources/a.png")

        val result = ResourceInventoryScanner.scan(
            fileStore = fileStore,
            baseRoot = base,
            roots = listOf(ResourceRoot("MyResources", ResourceKind.IMAGE, "Sprite")),
            origin = EntryOrigin.PROJECT,
        )

        assertEquals(EntryOrigin.PROJECT, result.inventory.find("a")?.origin)
    }
}
