package org.zender.editor.session

import org.zender.editor.document.DocumentId
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.document.jsonNumber
import org.zender.editor.document.writePath
import org.zender.editor.edit.EditCommand
import org.zender.editor.objects.ObjectKey
import org.zender.editor.selection.FieldRef
import org.zender.editor.settings.FavoriteField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class EditorSessionTest {

    private val idA = DocumentId("card-a")
    private val idB = DocumentId("perk-b")

    private class SetFieldCommand(
        private val key: String,
        private val value: Int,
        override val mergeKey: String? = null,
    ) : EditCommand {
        override val label: String = "设置 $key"
        override fun execute(document: JsonDocument): JsonDocument =
            JsonDocument.of(document.root.writePath(JsonPath.key(key), jsonNumber(value)))
    }

    private fun document(text: String): JsonDocument = JsonDocument.parseOrThrow(text)

    private fun EditorSession.number(id: DocumentId, key: String): Int =
        ((currentState.documents[id]!!.document.root as JsonNode.ObjectNode)
            .get(key) as JsonNode.NumberNode)
            .toLongOrNull()!!
            .toInt()

    private fun openedSession(): EditorSession = EditorSession().apply {
        openDocument(idA, document("""{"a":1}"""), title = "Card A")
        openDocument(idB, document("""{"b":1}"""), title = "Perk B")
    }

    @Test
    fun `打开文档维护顺序、激活与选择`() {
        val session = openedSession()
        val state = session.currentState

        assertEquals(listOf(idA, idB), state.documentOrder)
        assertEquals(idB, state.activeDocumentId)
        assertEquals(setOf(idA, idB), state.documents.keys)
        assertEquals("Card A", state.documents[idA]!!.title)
        assertEquals(idB, state.selection.currentDocument)
        assertFalse(state.hasUnsavedChanges)
    }

    @Test
    fun `重复打开同一文档不会重复登记`() {
        val session = openedSession()

        session.openDocument(idA, document("""{"a":99}"""), title = "Should Not Replace")

        assertEquals(listOf(idA, idB), session.currentState.documentOrder)
        assertEquals(1, session.number(idA, "a"))
        assertEquals("Card A", session.currentState.documents[idA]!!.title)
    }

    @Test
    fun `执行命令更新文档与脏状态，撤销恢复`() {
        val session = openedSession()

        session.execute(idA, SetFieldCommand("a", 10))

        val state = session.currentState
        assertEquals(10, session.number(idA, "a"))
        assertTrue(state.documents[idA]!!.isDirty)
        assertTrue(state.documents[idA]!!.canUndo)
        assertEquals("设置 a", state.documents[idA]!!.undoLabel)
        assertTrue(state.hasUnsavedChanges)

        assertTrue(session.undo(idA))

        assertEquals(1, session.number(idA, "a"))
        assertFalse(session.currentState.documents[idA]!!.isDirty)
        assertTrue(session.currentState.documents[idA]!!.canRedo)

        assertTrue(session.redo(idA))

        assertEquals(10, session.number(idA, "a"))
        assertTrue(session.currentState.documents[idA]!!.isDirty)
    }

    @Test
    fun `空操作不改变状态`() {
        val session = openedSession()

        session.execute(idA, SetFieldCommand("a", 1))

        assertFalse(session.currentState.documents[idA]!!.isDirty)
        assertFalse(session.currentState.documents[idA]!!.canUndo)
    }

    @Test
    fun `保存后变干净，撤销后再次变脏`() {
        val session = openedSession()
        session.execute(idA, SetFieldCommand("a", 2))

        assertTrue(session.markSaved(idA))
        assertFalse(session.currentState.documents[idA]!!.isDirty)

        session.undo(idA)

        assertEquals(1, session.number(idA, "a"))
        assertTrue(session.currentState.documents[idA]!!.isDirty)
    }

    @Test
    fun `保存全部同时清除文档与工程脏状态`() {
        val session = openedSession()
        session.execute(idA, SetFieldCommand("a", 2))
        session.execute(idB, SetFieldCommand("b", 2))
        session.markProjectDirty()

        session.markAllSaved()

        val state = session.currentState
        assertFalse(state.hasUnsavedChanges)
        assertFalse(state.projectDirty)
        assertTrue(state.documents.values.none { it.isDirty })
    }

    @Test
    fun `关闭脏文档需要 force`() {
        val session = openedSession()
        session.execute(idB, SetFieldCommand("b", 2))

        assertFalse(session.closeDocument(idB))
        assertEquals(idB, session.currentState.activeDocumentId)

        assertTrue(session.closeDocument(idB, force = true))

        val state = session.currentState
        assertEquals(listOf(idA), state.documentOrder)
        assertEquals(idA, state.activeDocumentId)
        assertEquals(idA, state.selection.currentDocument)
        assertNull(state.documents[idB])
    }

    @Test
    fun `关闭全部后活动文档为空`() {
        val session = openedSession()
        session.closeDocument(idA, force = true)
        session.closeDocument(idB, force = true)

        val state = session.currentState
        assertTrue(state.documents.isEmpty())
        assertNull(state.activeDocumentId)
        assertNull(state.selection.currentDocument)
    }

    @Test
    fun `整体替换文档可以撤销`() {
        val session = openedSession()
        val replacement = document("""{"z":9}""")

        session.replaceDocument(idA, replacement, label = "原始 JSON 提交")

        assertEquals(replacement, session.document(idA))
        assertEquals("原始 JSON 提交", session.documentSnapshot(idA)!!.undoLabel)

        session.undo(idA)

        assertEquals(document("""{"a":1}"""), session.document(idA))
    }

    @Test
    fun `选择对象、字段与最近引用字段`() {
        val session = openedSession()
        val card = ObjectKey("CardData", "Knife", "1111")
        val ref = FieldRef(idA, JsonPath.parse("CardInteractions[0].Card"))

        session.selectObject(card, idA)
        assertEquals(card, session.currentState.selection.currentObject)
        assertEquals(idA, session.currentState.activeDocumentId)

        session.focusReferenceField(ref)
        assertEquals(ref, session.currentState.selection.lastFocusedReferenceField)

        session.selectField(FieldRef(idA, JsonPath.parse("CardName")))
        assertEquals(ref, session.currentState.selection.lastFocusedReferenceField)

        session.clearSelection()
        assertEquals(org.zender.editor.selection.SelectionState(), session.currentState.selection)
    }

    @Test
    fun `收藏字段切换与初始收藏`() {
        val initial = FavoriteField("CardData", "CardName")
        val session = EditorSession(initialFavorites = setOf(initial))

        assertTrue(session.isFavorite("CardData", "CardName"))
        assertFalse(session.toggleFavorite("CardData", "CardName"))
        assertFalse(session.isFavorite("CardData", "CardName"))

        assertTrue(session.toggleFavorite("CardData", "CardDescription"))
        assertEquals(
            setOf(FavoriteField("CardData", "CardDescription")),
            session.currentState.favorites,
        )
    }

    @Test
    fun `工程级脏标记`() {
        val session = openedSession()

        session.markProjectDirty()
        assertTrue(session.currentState.projectDirty)
        assertTrue(session.currentState.hasUnsavedChanges)

        session.markProjectSaved()
        assertFalse(session.currentState.hasUnsavedChanges)
    }

    @Test
    fun `合并键相同的连续编辑只产生一条历史`() {
        val session = openedSession()
        session.execute(idA, SetFieldCommand("a", 2, mergeKey = "a"))
        session.execute(idA, SetFieldCommand("a", 3, mergeKey = "a"))

        session.undo(idA)

        assertEquals(1, session.number(idA, "a"))
        assertFalse(session.currentState.documents[idA]!!.canUndo)
    }

    @Test
    fun `endMergeGroup 打断连续编辑`() {
        val session = openedSession()
        session.execute(idA, SetFieldCommand("a", 2, mergeKey = "a"))
        session.endMergeGroup(idA)
        session.execute(idA, SetFieldCommand("a", 3, mergeKey = "a"))

        session.undo(idA)

        assertEquals(2, session.number(idA, "a"))
    }

    @Test
    fun `状态流给每个观察者相同快照`() {
        val session = openedSession()
        val before = session.currentState
        session.execute(idA, SetFieldCommand("a", 5))
        val after = session.state.value

        assertNotNull(after.documents[idA])
        assertTrue(after.documents[idA]!!.revision > before.documents[idA]!!.revision)
        assertEquals(after, session.currentState)
    }
}
