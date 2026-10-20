package org.zender.editor.localization

import kotlin.test.Test
import kotlin.test.assertEquals

internal class CsvWriterTest {

    @Test
    fun `写入基础行`() {
        val text = CsvWriter.write(
            listOf(listOf("key", "English", "中文")),
        )

        assertEquals("key,English,中文\n", text)
    }

    @Test
    fun `包含逗号时加引号`() {
        val text = CsvWriter.write(listOf(listOf("k", "hello, world", "中文")))

        assertEquals("k,\"hello, world\",中文\n", text)
    }

    @Test
    fun `引号使用双写转义`() {
        val text = CsvWriter.write(listOf(listOf("k", "say \"hi\"")))

        assertEquals("k,\"say \"\"hi\"\"\"\n", text)
    }

    @Test
    fun `包含换行时加引号`() {
        val text = CsvWriter.write(listOf(listOf("k", "line1\nline2")))

        assertEquals("k,\"line1\nline2\"\n", text)
    }

    @Test
    fun `首尾空白时加引号`() {
        assertEquals("\" a \"", CsvWriter.formatField(" a "))
        assertEquals("a", CsvWriter.formatField("a"))
    }

    @Test
    fun `空字段与空行`() {
        assertEquals("a,,c\n", CsvWriter.write(listOf(listOf("a", "", "c"))))
        assertEquals("", CsvWriter.write(emptyList()))
    }

    @Test
    fun `可指定分隔符并往返解析`() {
        val rows = listOf(
            listOf("a", "b|c", "d\"e", "line\nbreak"),
            listOf("", "x", "", "y"),
        )

        val text = CsvWriter.write(rows, separator = '|')
        val parsed = CsvParser.parse(text, separator = '|')

        assertEquals(rows, parsed.rows)
    }
}
