package org.zender.editor.project

import org.zender.editor.document.DocumentId
import org.zender.editor.document.JsonDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class DirtyTrackerTest {

    private val id = DocumentId("card")
    private val otherId = DocumentId("perk")

    private fun doc(text: String): JsonDocument = JsonDocument.parseOrThrow(text)

    @Test
    fun `打开文档时是干净的`() {
        val tracker = DirtyTracker()
        tracker.open(id, doc("""{"a":1}"""))

        assertFalse(tracker.isDirty(id))
        assertTrue(tracker.dirtyDocuments().isEmpty())
        assertFalse(tracker.hasUnsavedChanges)
    }

    @Test
    fun `内容变化后变脏，保存后恢复干净`() {
        val tracker = DirtyTracker()
        tracker.open(id, doc("""{"a":1}"""))

        tracker.update(id, doc("""{"a":2}"""))
        assertTrue(tracker.isDirty(id))
        assertTrue(tracker.hasDirtyDocuments)

        tracker.markSaved(id, doc("""{"a":2}"""))
        assertFalse(tracker.isDirty(id))
        assertFalse(tracker.hasUnsavedChanges)
    }

    @Test
    fun `撤销回保存点后恢复干净`() {
        val tracker = DirtyTracker()
        val saved = doc("""{"a":1}""")
        tracker.open(id, saved)

        tracker.update(id, doc("""{"a":2}"""))
        assertTrue(tracker.isDirty(id))

        tracker.update(id, saved)
        assertFalse(tracker.isDirty(id))
    }

    @Test
    fun `保存全部与多个文档`() {
        val tracker = DirtyTracker()
        tracker.open(id, doc("""{"a":1}"""))
        tracker.open(otherId, doc("""{"b":1}"""))
        tracker.update(id, doc("""{"a":2}"""))
        tracker.update(otherId, doc("""{"b":2}"""))

        assertEquals(setOf(id, otherId), tracker.dirtyDocuments())

        tracker.markAllSaved(
            mapOf(
                id to doc("""{"a":2}"""),
                otherId to doc("""{"b":2}"""),
            ),
        )

        assertTrue(tracker.dirtyDocuments().isEmpty())
    }

    @Test
    fun `工程级脏标记`() {
        val tracker = DirtyTracker()

        tracker.markProjectDirty()
        assertTrue(tracker.hasUnsavedProjectChanges)
        assertTrue(tracker.hasUnsavedChanges)

        tracker.markProjectSaved()
        assertFalse(tracker.hasUnsavedProjectChanges)
        assertFalse(tracker.hasUnsavedChanges)
    }

    @Test
    fun `关闭文档移除其状态`() {
        val tracker = DirtyTracker()
        tracker.open(id, doc("""{"a":1}"""))
        tracker.update(id, doc("""{"a":2}"""))

        tracker.close(id)

        assertFalse(tracker.isDirty(id))
        assertTrue(tracker.openDocuments().isEmpty())
        assertTrue(tracker.dirtyDocuments().isEmpty())
    }

    @Test
    fun `reset 清空文档与工程状态`() {
        val tracker = DirtyTracker()
        tracker.open(id, doc("""{"a":1}"""))
        tracker.update(id, doc("""{"a":2}"""))
        tracker.markProjectDirty()

        tracker.reset()

        assertTrue(tracker.openDocuments().isEmpty())
        assertFalse(tracker.hasUnsavedChanges)
    }
}
