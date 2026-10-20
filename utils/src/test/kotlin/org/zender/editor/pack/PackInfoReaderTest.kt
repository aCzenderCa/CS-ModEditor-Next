package org.zender.editor.pack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PackInfoReaderTest {

    @Test
    fun `读取 Name Version IsMainPack`() {
        val result = PackInfoReader.parse(
            """
            {
              "Name": "CSTI",
              "Version": "1.05ae",
              "IsMainPack": true,
              "Unknown": 1
            }
            """.trimIndent(),
        )

        val info = assertNotNull(result.info)
        assertEquals("CSTI", info.name)
        assertEquals("1.05ae", info.version)
        assertTrue(info.isMainPack)
        assertEquals("CSTI", info.normalizedName)
    }

    @Test
    fun `缺少可选字段时使用默认值`() {
        val result = PackInfoReader.parse("""{"Name":"CSFF"}""")

        val info = assertNotNull(result.info)
        assertEquals("", info.version)
        assertEquals(false, info.isMainPack)
    }

    @Test
    fun `缺少 Name 时返回错误`() {
        val result = PackInfoReader.parse("""{"Version":"1.0"}""")

        assertNull(result.info)
        assertTrue(result.error?.isError == true)
        assertTrue(result.error?.message?.contains("Name") == true)
    }

    @Test
    fun `Version 类型错误时返回错误`() {
        val result = PackInfoReader.parse("""{"Name":"CSTI","Version":2}""")

        assertNull(result.info)
        assertTrue(result.error?.message?.contains("Version") == true)
    }

    @Test
    fun `非法 JSON 返回带路径的错误`() {
        val result = PackInfoReader.parse("""{"Name": }""")

        assertNull(result.info)
        assertEquals("PackInfo.json", result.error?.path)
    }
}
