package org.zender.editor.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class RawJsonDocumentTest {

    @Test
    fun `有效原文保留原始文本与解析结果`() {
        val text = """{"a":1,"b":[2,3]}"""

        val raw = RawJsonDocument.parse(text)

        assertTrue(raw.isValid)
        assertEquals(text, raw.originalText)
        assertEquals(text, raw.text)
        assertNotNull(raw.document)
        assertTrue(raw.isInSync)
    }

    @Test
    fun `非法编辑保留最后一份有效文档`() {
        val raw = RawJsonDocument.parse("""{"a":1}""")
        val lastValid = raw.document

        val invalid = raw.withText("""{"a":""")

        assertFalse(invalid.isValid)
        assertNotNull(invalid.error)
        assertEquals(lastValid, invalid.document)
        assertEquals(raw.originalText, invalid.originalText)
        assertTrue(invalid.hasUnsavedTextChanges)
        assertFalse(invalid.isInSync)
    }

    @Test
    fun `错误位置可用于定位`() {
        val raw = RawJsonDocument.parse("{\n  \"a\": 1,\n  \"b\": [\n}")

        val error = raw.error

        assertNotNull(error)
        assertEquals(4, error.line)
        assertTrue(error.column > 0)
    }

    @Test
    fun `格式化与压缩不改变语义`() {
        val raw = RawJsonDocument.parse("""{"a":1,"b":[2,3]}""")

        val pretty = raw.format()
        val compact = pretty.compact()

        assertTrue(pretty.isValid)
        assertTrue(pretty.text.contains("\n"))
        assertEquals("""{"a":1,"b":[2,3]}""", compact.text)
        assertEquals(raw.originalText, pretty.originalText)
    }

    @Test
    fun `字段视图回写只更新文本不改变原文基线`() {
        val raw = RawJsonDocument.parse("""{"a":1}""")
        val updatedDocument = JsonDocument.of(jsonObject("a" to jsonNumber(2)))

        val updated = raw.withDocument(updatedDocument)

        assertEquals("""{"a":2}""", updated.text)
        assertEquals(raw.originalText, updated.originalText)
        assertTrue(updated.hasUnsavedTextChanges)
        assertTrue(updated.isValid)
    }

    @Test
    fun `还原回到原始文本`() {
        val raw = RawJsonDocument.parse("""{"a":1}""")
        val edited = raw.withText("""{"a":2}""")

        val reverted = edited.revert()

        assertEquals(raw.originalText, reverted.text)
        assertEquals(raw.document, reverted.document)
    }

    @Test
    fun `从文档创建原始文本`() {
        val document = JsonDocument.of(jsonObject("a" to jsonNumber(1)))

        val raw = RawJsonDocument.fromDocument(document, JsonFormat.Compact)

        assertEquals("""{"a":1}""", raw.text)
        assertEquals(document, raw.document)
        assertTrue(raw.isInSync)
    }

    @Test
    fun `初始非法文件没有可编辑文档`() {
        val raw = RawJsonDocument.parse("""{"a":""")

        assertFalse(raw.isValid)
        assertEquals(null, raw.document)
        assertTrue(runCatching { raw.requireDocument() }.isFailure)
    }
}
