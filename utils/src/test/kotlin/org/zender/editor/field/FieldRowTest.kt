package org.zender.editor.field

import org.zender.editor.document.JsonPath
import org.zender.editor.document.jsonArray
import org.zender.editor.document.jsonNumber
import org.zender.editor.document.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class FieldRowTest {

    private val nested = FieldRow(
        path = JsonPath.parse("card.tags"),
        name = "tags",
        typeName = "CardTag",
        value = jsonArray(jsonNumber(1), jsonNumber(2)),
        isFavorite = true,
        children = listOf(
            FieldRow(
                path = JsonPath.parse("card.tags[0]"),
                name = "[0]",
                value = jsonNumber(1),
            ),
            FieldRow(
                path = JsonPath.parse("card.tags[1]"),
                name = "[1]",
                value = jsonNumber(2),
                isUnresolvedReference = true,
            ),
        ),
    )

    private val root = FieldRow(
        path = JsonPath.parse("card"),
        name = "card",
        typeName = "CardData",
        value = jsonObject("tags" to nested.value!!),
        isModified = true,
        children = listOf(nested),
    )

    @Test
    fun `容器与深度信息`() {
        assertTrue(root.isContainer)
        assertEquals(1, root.childCount)
        assertEquals(1, root.depth)
        assertTrue(nested.isContainer)
        assertEquals(2, nested.childCount)
        assertEquals(2, nested.depth)
    }

    @Test
    fun `遍历与查找`() {
        assertEquals(
            listOf("card", "tags", "[0]", "[1]"),
            root.walk().map { it.name }.toList(),
        )
        assertEquals(nested, root.find(JsonPath.parse("card.tags")))
        assertEquals("[1]", root.find(JsonPath.parse("card.tags[1]"))?.name)
        assertNull(root.find(JsonPath.parse("missing")))
    }

    @Test
    fun `标记位原样保留`() {
        assertTrue(root.isModified)
        assertTrue(nested.isFavorite)
        assertTrue(nested.children[1].isUnresolvedReference)
        assertFalse(nested.children[0].isUnresolvedReference)
    }

    @Test
    fun `标量字段不是容器`() {
        val scalar = FieldRow(path = JsonPath.parse("a"), name = "a", value = jsonNumber(1))

        assertFalse(scalar.isContainer)
        assertEquals(0, scalar.childCount)
    }
}
