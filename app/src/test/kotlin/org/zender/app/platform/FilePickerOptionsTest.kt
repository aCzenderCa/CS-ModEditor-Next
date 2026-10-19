package org.zender.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

internal class FilePickerOptionsTest {

    @Test
    fun `扩展名统一去点并转小写`() {
        val normalized = normalizeExtensions(setOf(".PNG", " jpg ", "Jpeg", "png"))

        assertEquals(setOf("png", "jpg", "jpeg"), normalized)
    }

    @Test
    fun `null 表示不过滤`() {
        assertNull(normalizeExtensions(null))
    }

    @Test
    fun `空集合或全部为空项会被拒绝`() {
        assertFailsWith<IllegalArgumentException> { normalizeExtensions(emptySet()) }
        assertFailsWith<IllegalArgumentException> { normalizeExtensions(setOf("", "  ", ".")) }
    }

    @Test
    fun `非法字符会被拒绝`() {
        assertFailsWith<IllegalArgumentException> { normalizeExtensions(setOf("p*n*g")) }
        assertFailsWith<IllegalArgumentException> { normalizeExtensions(setOf("../json")) }
    }

    @Test
    fun `默认扩展名规范化`() {
        assertEquals("json", normalizeExtension(".JSON"))
        assertEquals("json", normalizeExtension("json"))
        assertNull(normalizeExtension("."))
        assertNull(normalizeExtension(null))
    }
}
