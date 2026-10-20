package org.zender.app.ui.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenProjectRegistryTest {

    @Test
    fun `已打开项目可注册、列出并聚焦`() {
        val registry = OpenProjectRegistry()
        var focused = 0
        registry.update("main", "/tmp/project-a", "Project A") { focused++ }

        assertEquals(1, registry.all.size)
        assertEquals("Project A", registry.all.single().title)
        assertTrue(registry.focus("/tmp/project-a"))
        assertEquals(1, focused)

        registry.remove("main")
        assertTrue(registry.all.isEmpty())
        assertFalse(registry.focus("/tmp/project-a"))
    }

    @Test
    fun `同一窗口切换工程只更新路径`() {
        val registry = OpenProjectRegistry()
        registry.update("main", "/tmp/a", "A") {}
        registry.update("main", "/tmp/b", "B") {}

        assertEquals(1, registry.all.size)
        assertEquals("/tmp/b", registry.all.single().path)
        assertEquals("B", registry.all.single().title)
    }
}
