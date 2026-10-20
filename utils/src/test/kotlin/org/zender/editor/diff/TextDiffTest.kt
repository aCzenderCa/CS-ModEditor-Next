package org.zender.editor.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextDiffTest {

    @Test
    fun `相同文本全部为未修改`() {
        val result = TextDiff.diff("a\nb", "a\nb")
        assertEquals(listOf(DiffKind.UNCHANGED, DiffKind.UNCHANGED), result.map { it.kind })
        assertEquals(listOf(1, 2), result.map { it.oldLine })
        assertEquals(listOf(1, 2), result.map { it.newLine })
    }

    @Test
    fun `修改一行输出删除与新增`() {
        val result = TextDiff.diff("a\nb\nc", "a\nB\nc")
        assertEquals(
            listOf(DiffKind.UNCHANGED, DiffKind.REMOVED, DiffKind.ADDED, DiffKind.UNCHANGED),
            result.map { it.kind },
        )
        assertEquals("b", result[1].text)
        assertEquals("B", result[2].text)
    }

    @Test
    fun `空文本与新增行`() {
        val result = TextDiff.diff("", "a")
        assertEquals(listOf(DiffKind.ADDED), result.map { it.kind })
        assertEquals(1, result.single().newLine)
    }

    @Test
    fun `行号在长度不同时保持正确`() {
        val result = TextDiff.diff("a\nb\nc\nd", "a\nc\nd\ne")
        val unchanged = result.filter { it.kind == DiffKind.UNCHANGED }
        assertTrue(unchanged.any { it.text == "c" && it.oldLine == 3 && it.newLine == 2 })
        assertTrue(unchanged.any { it.text == "d" && it.oldLine == 4 && it.newLine == 3 })
    }
}
