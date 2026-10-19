package org.zender.editor.edit

import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.document.jsonNumber
import org.zender.editor.document.writePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class CommandStackTest {

    private class SetFieldCommand(
        private val key: String,
        private val value: Int,
        override val label: String = "设置字段",
        override val mergeKey: String? = null,
    ) : EditCommand {
        override fun execute(document: JsonDocument): JsonDocument =
            JsonDocument.of(
                document.root.writePath(JsonPath.key(key), jsonNumber(value)),
            )
    }

    private fun document(text: String = """{"a":1,"b":2}"""): JsonDocument =
        JsonDocument.parseOrThrow(text)

    private fun valueOf(stack: CommandStack, key: String): Int =
        ((stack.current.root as JsonNode.ObjectNode).get(key) as JsonNode.NumberNode)
            .toLongOrNull()!!
            .toInt()

    @Test
    fun `执行、撤销、重做`() {
        val stack = CommandStack(document())

        stack.execute(SetFieldCommand("a", 10, label = "修改 a"))

        assertEquals(10, valueOf(stack, "a"))
        assertTrue(stack.canUndo)
        assertFalse(stack.canRedo)
        assertEquals("修改 a", stack.undoLabel)
        assertEquals(1L, stack.revision)

        assertTrue(stack.undo() != null)

        assertEquals(1, valueOf(stack, "a"))
        assertFalse(stack.canUndo)
        assertTrue(stack.canRedo)
        assertEquals("修改 a", stack.redoLabel)

        assertTrue(stack.redo() != null)

        assertEquals(10, valueOf(stack, "a"))
        assertTrue(stack.canUndo)
        assertFalse(stack.canRedo)
    }

    @Test
    fun `相同 mergeKey 的连续编辑合并为一条历史`() {
        val stack = CommandStack(document())

        stack.execute(SetFieldCommand("a", 10, mergeKey = "a"))
        stack.execute(SetFieldCommand("a", 11, mergeKey = "a"))
        stack.execute(SetFieldCommand("a", 12, mergeKey = "a"))

        assertEquals(1, stack.historyDepth)
        assertEquals(12, valueOf(stack, "a"))

        stack.undo()

        assertEquals(1, valueOf(stack, "a"))
        assertEquals(0, stack.historyDepth)
    }

    @Test
    fun `不同 mergeKey 不合并`() {
        val stack = CommandStack(document())

        stack.execute(SetFieldCommand("a", 10, mergeKey = "a"))
        stack.execute(SetFieldCommand("b", 20, mergeKey = "b"))

        assertEquals(2, stack.historyDepth)
    }

    @Test
    fun `endMergeGroup 打断合并`() {
        val stack = CommandStack(document())

        stack.execute(SetFieldCommand("a", 10, mergeKey = "a"))
        stack.endMergeGroup()
        stack.execute(SetFieldCommand("a", 11, mergeKey = "a"))

        assertEquals(2, stack.historyDepth)
    }

    @Test
    fun `合并后回到原值会移除历史`() {
        val stack = CommandStack(document())

        stack.execute(SetFieldCommand("a", 10, mergeKey = "a"))
        stack.execute(SetFieldCommand("a", 1, mergeKey = "a"))

        assertEquals(1, valueOf(stack, "a"))
        assertEquals(0, stack.historyDepth)
        assertFalse(stack.canUndo)
    }

    @Test
    fun `空操作不进入历史且不增加 revision`() {
        val stack = CommandStack(document())

        stack.execute(SetFieldCommand("a", 1))

        assertEquals(0, stack.historyDepth)
        assertEquals(0L, stack.revision)
        assertNull(stack.undoLabel)
    }

    @Test
    fun `历史深度上限会丢弃最旧记录`() {
        val stack = CommandStack(document(), maxHistory = 2)

        stack.execute(SetFieldCommand("a", 2))
        stack.execute(SetFieldCommand("b", 3))
        stack.execute(SetFieldCommand("c", 4))

        assertEquals(2, stack.historyDepth)
        stack.undo()
        stack.undo()
        assertFalse(stack.canUndo)
        assertEquals("""{"a":2,"b":2}""", stack.current.toJsonText())
    }

    @Test
    fun `新命令会清空重做栈`() {
        val stack = CommandStack(document())
        stack.execute(SetFieldCommand("a", 2))
        stack.undo()

        stack.execute(SetFieldCommand("b", 3))

        assertFalse(stack.canRedo)
    }

    @Test
    fun `命令抛异常时历史与当前文档不变`() {
        val stack = CommandStack(document())
        val failing = object : EditCommand {
            override val label: String = "失败命令"
            override fun execute(document: JsonDocument): JsonDocument =
                throw IllegalStateException("boom")
        }

        assertFailsWith<IllegalStateException> { stack.execute(failing) }

        assertEquals(0, stack.historyDepth)
        assertEquals("""{"a":1,"b":2}""", stack.current.toJsonText())
    }

    @Test
    fun `reset 清空历史并替换当前文档`() {
        val stack = CommandStack(document())
        stack.execute(SetFieldCommand("a", 2))
        val replacement = document("""{"z":9}""")

        stack.reset(replacement)

        assertEquals(replacement, stack.current)
        assertFalse(stack.canUndo)
        assertFalse(stack.canRedo)
        assertEquals(2L, stack.revision)
    }
}
