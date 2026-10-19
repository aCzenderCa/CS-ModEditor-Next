package org.zender.editor.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class JsonPathTest {

    @Test
    fun `解析并格式化对象与数组路径`() {
        val path = JsonPath.parse("CardInteractions[0].ActionName")

        assertEquals(
            listOf(
                JsonPathSegment.Key("CardInteractions"),
                JsonPathSegment.Index(0),
                JsonPathSegment.Key("ActionName"),
            ),
            path.segments,
        )
        assertEquals("CardInteractions[0].ActionName", path.format())
        assertEquals(path, JsonPath.parse(path.format()))
    }

    @Test
    fun `特殊键使用方括号引用`() {
        val path = JsonPath.parse("""foo["a.b"]["c[0]"]""")

        assertEquals("""foo["a.b"]["c[0]"]""", path.format())
        assertEquals(path, JsonPath.parse(path.format()))
    }

    @Test
    fun `根路径接受空字符串与美元符号`() {
        assertTrue(JsonPath.parse("").isRoot)
        assertTrue(JsonPath.parse("$").isRoot)
        assertEquals("", JsonPath.ROOT.format())
        assertEquals(JsonPath.ROOT, JsonPath.parse(""))
    }

    @Test
    fun `美元前缀路径`() {
        val path = JsonPath.parse("$.a[1].b")

        assertEquals(
            listOf(
                JsonPathSegment.Key("a"),
                JsonPathSegment.Index(1),
                JsonPathSegment.Key("b"),
            ),
            path.segments,
        )
    }

    @Test
    fun `父子路径`() {
        val path = JsonPath.ROOT.child("a").child(0).child("b")

        assertEquals("a[0].b", path.format())
        assertEquals("a[0]", path.parent()?.format())
        assertEquals(3, path.size)
        assertEquals(JsonPathSegment.Key("b"), path.last)
    }

    @Test
    fun `非法路径被拒绝`() {
        listOf("a..b", "a.", "a[", "a[-1]", "[abc]", "a]b", "a[0]b").forEach { invalid ->
            assertFailsWith<JsonPathException>("应当拒绝：$invalid") {
                JsonPath.parse(invalid)
            }
        }
    }

    @Test
    fun `读取嵌套路径`() {
        val root = JsonDocument.parseOrThrow("""{"a":{"b":[10,20]},"x":1}""").root

        assertEquals(jsonNumber(20), root.readPath(JsonPath.parse("a.b[1]")))
        assertNull(root.readPath(JsonPath.parse("a.b[2]")))
        assertNull(root.readPath(JsonPath.parse("missing")))
    }

    @Test
    fun `写入已有路径`() {
        val document = JsonDocument.parseOrThrow("""{"a":{"b":[10,20]},"x":1}""")

        val updated = document.root.writePath(JsonPath.parse("a.b[0]"), jsonNumber(11))

        assertEquals(jsonNumber(11), updated.readPath(JsonPath.parse("a.b[0]")))
        assertEquals(
            """{"a":{"b":[11,20]},"x":1}""",
            updated.toJsonText(),
        )
    }

    @Test
    fun `写入不存在的顶层键会追加`() {
        val root = JsonDocument.parseOrThrow("""{"a":1}""").root

        val updated = root.writePath(JsonPath.key("b"), jsonNumber(2))

        assertEquals("""{"a":1,"b":2}""", updated.toJsonText())
    }

    @Test
    fun `重复键写入只替换最后一个`() {
        val root = JsonDocument.parseOrThrow("""{"a":1,"a":2}""").root

        val updated = root.writePath(JsonPath.key("a"), jsonNumber(3))

        assertEquals("""{"a":1,"a":3}""", updated.toJsonText())
    }

    @Test
    fun `删除路径与重复键`() {
        val root = JsonDocument.parseOrThrow("""{"a":1,"a":2,"b":{"c":3}}""").root

        val removedA = root.removePath(JsonPath.key("a"))
        val removedC = removedA.removePath(JsonPath.parse("b.c"))

        assertEquals("""{"b":{"c":3}}""", removedA.toJsonText())
        assertEquals("""{"b":{}}""", removedC.toJsonText())
    }

    @Test
    fun `数组追加与插入`() {
        val root = JsonDocument.parseOrThrow("""{"list":[1,3]}""").root
        val listPath = JsonPath.key("list")

        val appended = root.appendToArray(listPath, jsonNumber(4))
        val inserted = appended.insertToArray(listPath, 1, jsonNumber(2))

        assertEquals("[1,2,3,4]", inserted.readPath(listPath)!!.toJsonText())
    }

    @Test
    fun `路径不存在或类型不匹配时抛出异常`() {
        val root = JsonDocument.parseOrThrow("""{"a":1,"b":[1]}""").root

        assertFailsWith<JsonPathException> {
            root.writePath(JsonPath.parse("missing.child"), jsonNumber(1))
        }
        assertFailsWith<JsonPathException> {
            root.writePath(JsonPath.parse("b[2]"), jsonNumber(1))
        }
        assertFailsWith<JsonPathException> {
            root.removePath(JsonPath.ROOT)
        }
    }
}
