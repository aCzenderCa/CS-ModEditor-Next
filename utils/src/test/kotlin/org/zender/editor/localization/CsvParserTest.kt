package org.zender.editor.localization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class CsvParserTest {

    @Test
    fun `解析基础行与空列`() {
        val result = CsvParser.parse("a,b,c\n1,,3")

        assertTrue(result.isSuccess)
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "", "3")), result.rows)
    }

    @Test
    fun `支持引号内的逗号`() {
        val result = CsvParser.parse("""key,"hello, world",中文""")

        assertEquals(listOf("key", "hello, world", "中文"), result.rows.single())
    }

    @Test
    fun `支持双引号转义`() {
        val result = CsvParser.parse("\"say \"\"hi\"\"\",x")

        assertEquals(listOf("say \"hi\"", "x"), result.rows.single())
    }

    @Test
    fun `支持引号内的换行`() {
        val result = CsvParser.parse("\"line1\nline2\",x")

        assertEquals(listOf(listOf("line1\nline2", "x")), result.rows)
    }

    @Test
    fun `支持 CRLF 且末尾换行不产生额外行`() {
        val result = CsvParser.parse("a,b\r\n1,2\r\n")

        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), result.rows)
    }

    @Test
    fun `末尾分隔符保留空字段`() {
        val result = CsvParser.parse("a,b,")

        assertEquals(listOf("a", "b", ""), result.rows.single())
    }

    @Test
    fun `未闭合引号返回错误与已解析行`() {
        val result = CsvParser.parse("a,b\n\"unclosed")

        assertFalse(result.isSuccess)
        assertNotNull(result.error)
        assertEquals(listOf(listOf("a", "b")), result.rows)
    }

    @Test
    fun `引号闭合后有多余字符时报错`() {
        val result = CsvParser.parse("\"a\"b,c")

        assertFalse(result.isSuccess)
    }

    @Test
    fun `解码 UTF-8 多重 BOM`() {
        val bytes = byteArrayOf(
            0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(),
            0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(),
        ) + "key,值".toByteArray(Charsets.UTF_8)

        assertEquals("key,值", CsvEncoding.decode(bytes))
    }

    @Test
    fun `解码 UTF-16LE BOM`() {
        val body = "key,值".toByteArray(Charsets.UTF_16LE)
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body

        assertEquals("key,值", CsvEncoding.decode(bytes))
    }

    @Test
    fun `空文本解析为空行列表`() {
        assertTrue(CsvParser.parse("").rows.isEmpty())
    }

    @Test
    fun `保留字段内容中的空白`() {
        val result = CsvParser.parse(" a , b ")

        assertEquals(listOf(" a ", " b "), result.rows.single())
    }

    @Test
    fun `可指定分隔符与引号`() {
        val result = CsvParser.parse("a|'b|c'", separator = '|', quote = '\'')

        assertEquals(listOf("a", "b|c"), result.rows.single())
    }
}
