package org.zender.editor.window

import org.zender.editor.document.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class WindowManagerTest {

    private val card = DocumentId("card")
    private val perk = DocumentId("perk")

    private fun content(documentId: DocumentId, title: String = documentId.value): WindowContent =
        WindowContent(WindowContentKey.Document(documentId), title)

    @Test
    fun `打开主窗口与附加窗口`() {
        val manager = WindowManager()

        val main = manager.openMain(content(card, "Card"))
        val second = manager.open(content(perk, "Perk"))

        val state = manager.currentState
        assertEquals(2, state.windows.size)
        assertTrue(main.isMain)
        assertFalse(second.isMain)
        assertEquals(second.id, state.activeWindowId)
        assertEquals(main, state.mainWindow)
        assertEquals(second, state.activeWindow)
    }

    @Test
    fun `只允许一个主窗口`() {
        val manager = WindowManager()
        manager.openMain(content(card))

        assertFailsWith<IllegalArgumentException> {
            manager.openMain(content(perk))
        }
    }

    @Test
    fun `同一内容可以在多个窗口打开`() {
        val manager = WindowManager()
        val first = manager.openMain(content(card))
        val second = manager.open(content(card))

        assertTrue(first.id != second.id)
        assertEquals(2, manager.windowsForDocument(card).size)
    }

    @Test
    fun `更新几何、视图、滚动与标题`() {
        val manager = WindowManager()
        val window = manager.openMain(content(card))

        val geometry = WindowGeometry(x = 10f, y = 20f, width = 1200f, height = 800f, maximized = true)
        assertTrue(manager.updateGeometry(window.id, geometry))
        assertTrue(manager.updateViewMode(window.id, DocumentViewMode.RAW))
        assertTrue(manager.updateScroll(window.id, ScrollPosition(horizontal = 5f, vertical = 9f)))
        assertTrue(manager.updateTitle(window.id, "新标题"))

        val updated = manager.window(window.id)
        assertNotNull(updated)
        assertEquals(geometry, updated.geometry)
        assertEquals(DocumentViewMode.RAW, updated.content.viewMode)
        assertEquals(ScrollPosition(5f, 9f), updated.content.scrollPosition)
        assertEquals("新标题", updated.content.title)
    }

    @Test
    fun `未知窗口更新返回 false`() {
        val manager = WindowManager()
        val missing = WindowId("missing")

        assertFalse(manager.close(missing))
        assertFalse(manager.activate(missing))
        assertFalse(manager.updateGeometry(missing, WindowGeometry()))
        assertFalse(manager.updateViewMode(missing, DocumentViewMode.FIELD))
        assertNull(manager.window(missing))
    }

    @Test
    fun `激活与关闭窗口`() {
        val manager = WindowManager()
        val main = manager.openMain(content(card))
        val second = manager.open(content(perk))

        assertTrue(manager.activate(main.id))
        assertEquals(main.id, manager.currentState.activeWindowId)

        assertTrue(manager.close(second.id))
        assertEquals(main.id, manager.currentState.activeWindowId)

        assertTrue(manager.close(main.id))
        assertNull(manager.currentState.activeWindowId)
        assertTrue(manager.currentState.windows.isEmpty())
    }

    @Test
    fun `按文档查找并批量关闭`() {
        val manager = WindowManager()
        manager.openMain(content(card))
        manager.open(content(card))
        manager.open(content(perk))

        assertEquals(2, manager.windowsForDocument(card).size)

        assertEquals(2, manager.closeAllForDocument(card))

        assertTrue(manager.windowsForDocument(card).isEmpty())
        assertEquals(1, manager.currentState.windows.size)
        assertEquals(0, manager.closeAllForDocument(card))
    }

    @Test
    fun `内容键可持久化并解析`() {
        val keys = listOf(
            WindowContentKey.Document(card),
            WindowContentKey.StarMap("main"),
            WindowContentKey.Settings,
            WindowContentKey.Custom("diff:a|b"),
        )

        keys.forEach { key ->
            assertEquals(key, WindowContentKey.fromStorageKey(key.storageKey))
        }
        assertEquals(WindowContentKey.Custom("unknown"), WindowContentKey.fromStorageKey("unknown"))
    }

    @Test
    fun `几何参数校验`() {
        assertFailsWith<IllegalArgumentException> { WindowGeometry(width = 0f) }
        assertFailsWith<IllegalArgumentException> { WindowGeometry(height = -1f) }
    }

    @Test
    fun `自定义 id 生成器保证唯一`() {
        var next = 0
        val manager = WindowManager { "w${next++}" }

        val first = manager.openMain(content(card))
        val second = manager.open(content(perk))

        assertEquals(WindowId("w0"), first.id)
        assertEquals(WindowId("w1"), second.id)
    }
}
