package org.zender.editor.diff

import org.zender.editor.document.JsonDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FieldDiffTest {

    private fun doc(text: String) = JsonDocument.parseOrThrow(text).root

    @Test
    fun `标量变化输出字段路径`() {
        val changes = FieldDiff.diff(doc("""{"a":1,"b":2}"""), doc("""{"a":1,"b":3}"""))
        assertEquals(1, changes.size)
        assertEquals("b", changes.single().path)
    }

    @Test
    fun `嵌套对象与数组按下标比较`() {
        val old = doc("""{"n":{"x":1},"list":[1,2,3]}""")
        val new = doc("""{"n":{"x":2},"list":[1,3]}""")
        val paths = FieldDiff.diff(old, new).map { it.path }
        assertTrue("n.x" in paths)
        assertTrue("list[1]" in paths)
        assertTrue("list[2]" in paths)
    }

    @Test
    fun `新增与删除字段标记方向`() {
        val changes = FieldDiff.diff(doc("""{"a":1}"""), doc("""{"b":2}"""))
        assertEquals(2, changes.size)
        val removed = changes.first { it.path == "a" }
        val added = changes.first { it.path == "b" }
        assertTrue(removed.isRemoved)
        assertTrue(added.isAdded)
    }

    @Test
    fun `对象整体新增只输出一条`() {
        val changes = FieldDiff.diff(doc("{}"), doc("""{"a":{"x":1,"y":2}}"""))
        assertEquals(1, changes.size)
        assertEquals("a", changes.single().path)
    }

    @Test
    fun `无变化返回空列表`() {
        assertTrue(FieldDiff.diff(doc("""{"a":1}"""), doc("""{"a":1}""")).isEmpty())
    }
}
