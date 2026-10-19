package org.zender.editor.io

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class AtomicFileStoreTest {

    private val store = AtomicFileStore()

    private fun <T> withTempDir(block: (Path) -> T): T {
        val dir = createTempDirectory("csmode-filestore-")
        return try {
            block(dir)
        } finally {
            store.deleteRecursively(dir)
        }
    }

    @Test
    fun `文本写入自动创建父目录并可读回`() = withTempDir { dir ->
        val target = dir.resolve("nested/deeper/content.json")

        store.writeText(target, "{\"name\":\"测试\"}")

        assertTrue(store.exists(target))
        assertEquals("{\"name\":\"测试\"}", store.readText(target))
    }

    @Test
    fun `读取时去除 UTF-8 BOM`() = withTempDir { dir ->
        val target = dir.resolve("bom.csv")
        val content = "key,English,中文".toByteArray()
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        store.writeBytes(target, bom + content)

        assertEquals("key,English,中文", store.readText(target))
    }

    @Test
    fun `原子替换旧文件且不留下临时文件`() = withTempDir { dir ->
        val target = dir.resolve("file.json")
        store.writeText(target, "old")
        store.writeText(target, "new")
        store.writeText(target, "newest")

        assertEquals("newest", store.readText(target))
        val leftovers = store.list(dir).filter { it.name.startsWith(".file.json.tmp-") }
        assertTrue(leftovers.isEmpty(), "不应残留临时文件：$leftovers")
    }

    @Test
    fun `列表与递归遍历结果稳定有序`() = withTempDir { dir ->
        val b = dir.resolve("b.txt")
        val a = dir.resolve("a.txt")
        val nested = dir.resolve("sub/c.txt")
        store.writeText(b, "b")
        store.writeText(a, "a")
        store.writeText(nested, "c")

        val files = store.walkFiles(dir).map { it.name }
        assertContentEquals(listOf("a.txt", "b.txt", "c.txt"), files)

        val children = store.list(dir).map { it.name }
        assertContentEquals(listOf("a.txt", "b.txt", "sub"), children)
    }

    @Test
    fun `不存在的目录返回空列表`() = withTempDir { dir ->
        val missing = dir.resolve("missing")

        assertTrue(store.list(missing).isEmpty())
        assertTrue(store.walkFiles(missing).isEmpty())
        assertFalse(store.exists(missing))
    }

    @Test
    fun `递归删除目录`() = withTempDir { dir ->
        val nested = dir.resolve("a/b/c.txt")
        store.writeText(nested, "x")

        store.deleteRecursively(dir.resolve("a"))

        assertFalse(store.exists(dir.resolve("a")))
        assertTrue(store.exists(dir))
    }

    @Test
    fun `写入失败不会破坏原目标`() = withTempDir { dir ->
        val target = dir.resolve("is-a-directory")
        target.createDirectories()
        store.writeText(target.resolve("keep.txt"), "keep")

        assertFailsWith<FileStoreException> {
            store.writeBytes(target, byteArrayOf(1, 2, 3))
        }

        assertTrue(store.isDirectory(target))
        assertEquals("keep", store.readText(target.resolve("keep.txt")))
        val leftovers = store.list(dir).filter { it.name.startsWith(".is-a-directory.tmp-") }
        assertTrue(leftovers.isEmpty(), "失败后不应残留临时文件：$leftovers")
    }

    @Test
    fun `resolveWithin 阻止路径逃逸`() {
        withTempDir { dir ->
            val base = dir.resolve("project")
            base.createDirectories()

            assertEquals(
                store.normalize(base.resolve("Data/CardData/a.json")),
                store.resolveWithin(base, "Data/CardData/a.json"),
            )
            assertFailsWith<FileStoreException> {
                store.resolveWithin(base, "../outside.json")
            }
        }
    }

    @Test
    fun `normalize 返回绝对规范路径`() = withTempDir { dir ->
        val path = dir.resolve("a/../b")

        val normalized = store.normalize(path)

        assertTrue(normalized.isAbsolute)
        assertEquals(store.normalize(dir.resolve("b")), normalized)
    }

    @Test
    fun `delete 删除文件并返回是否存在`() = withTempDir { dir ->
        val target = dir.resolve("temp.txt")
        store.writeText(target, "x")

        assertTrue(store.delete(target))
        assertFalse(store.delete(target))
        assertFalse(Files.exists(target))
    }
}
