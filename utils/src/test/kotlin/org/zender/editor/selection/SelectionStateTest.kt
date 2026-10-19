package org.zender.editor.selection

import org.zender.editor.document.DocumentId
import org.zender.editor.document.JsonPath
import org.zender.editor.objects.ObjectKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

internal class SelectionStateTest {

    private val documentA = DocumentId("a")
    private val documentB = DocumentId("b")
    private val card = ObjectKey("CardData", "Knife", "1111")
    private val perk = ObjectKey("CharacterPerk", "Perk", "2222")
    private val fieldA = FieldRef(documentA, JsonPath.parse("a.b"))
    private val fieldB = FieldRef(documentB, JsonPath.parse("x.y"))

    @Test
    fun `选择对象会切换文档并清空字段`() {
        val selection = SelectionState()
            .selectField(fieldA)
            .selectObject(perk, documentB)

        assertEquals(documentB, selection.currentDocument)
        assertEquals(perk, selection.currentObject)
        assertNull(selection.currentField)
    }

    @Test
    fun `选择字段会同步当前文档`() {
        val selection = SelectionState().selectField(fieldA)

        assertEquals(documentA, selection.currentDocument)
        assertEquals(fieldA, selection.currentField)
    }

    @Test
    fun `聚焦引用字段记录最近目标`() {
        val selection = SelectionState().focusReferenceField(fieldA)

        assertEquals(fieldA, selection.currentField)
        assertEquals(fieldA, selection.lastFocusedReferenceField)
    }

    @Test
    fun `同一文档内切换字段保留最近引用字段`() {
        val reference = FieldRef(documentA, JsonPath.parse("ref"))
        val otherField = FieldRef(documentA, JsonPath.parse("other"))

        val selection = SelectionState()
            .focusReferenceField(reference)
            .selectField(otherField)

        assertEquals(otherField, selection.currentField)
        assertEquals(reference, selection.lastFocusedReferenceField)
    }

    @Test
    fun `切换文档会清空最近引用字段`() {
        val selection = SelectionState()
            .focusReferenceField(fieldA)
            .selectField(fieldB)

        assertEquals(documentB, selection.currentDocument)
        assertNull(selection.lastFocusedReferenceField)
    }

    @Test
    fun `同一文档选择对象保留最近引用字段`() {
        val selection = SelectionState()
            .focusReferenceField(fieldA)
            .selectObject(card, documentA)

        assertEquals(card, selection.currentObject)
        assertEquals(fieldA, selection.lastFocusedReferenceField)
    }

    @Test
    fun `切换到同一文档不会改变对象`() {
        val selection = SelectionState().selectObject(card, documentA)

        val same = selection.selectDocument(documentA)

        assertSame(selection, same)
        assertNotNull(same.currentObject)
    }

    @Test
    fun `clear 重置全部选择`() {
        val selection = SelectionState()
            .focusReferenceField(fieldA)
            .selectObject(card, documentA)

        assertEquals(SelectionState(), selection.clear())
    }
}
