package org.zender.editor.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class JsonDocumentTest {

    @Test
    fun `解析保留字段顺序与未知字段`() {
        val text = """{"z":1,"a":{"b":true,"c":null},"m":[1,2.0,"x"]}"""

        val document = JsonDocument.parseOrThrow(text)
        val root = document.root as JsonNode.ObjectNode

        assertEquals(listOf("z", "a", "m"), root.members.map { it.name })
        assertEquals(text, document.toJsonText())
    }

    @Test
    fun `数字保留原始写法`() {
        val text = """{"a":1.0,"b":1e3,"c":-0.5}"""

        val document = JsonDocument.parseOrThrow(text)

        assertEquals(text, document.toJsonText())
    }

    @Test
    fun `重复键默认保留并记录问题，访问时最后一个生效`() {
        val result = JsonDocument.parse("""{"a":1,"a":2}""")

        assertTrue(result.isSuccess)
        assertEquals(1, result.issues.size)
        assertEquals("a", result.issues.single().path)
        val root = result.document!!.root as JsonNode.ObjectNode
        assertEquals(jsonNumber(2), root.get("a"))
        assertEquals(listOf("a"), root.duplicateKeys())
        assertEquals("""{"a":1,"a":2}""", result.document.toJsonText())
    }

    @Test
    fun `重复键可配置为拒绝`() {
        val result = JsonDocument.parse(
            """{"a":1,"a":2}""",
            duplicateKeyPolicy = DuplicateKeyPolicy.REJECT,
        )

        assertFalse(result.isSuccess)
        assertNotNull(result.error)
        assertTrue(result.error!!.message.orEmpty().contains("重复键"))
        assertNull(result.document)
    }

    @Test
    fun `格式化输出可再次解析`() {
        val document = JsonDocument.parseOrThrow("""{"a":[1,{"b":2}],"c":{}}""")

        val pretty = document.toJsonText(JsonFormat.Pretty("  "))

        assertTrue(pretty.contains("\n  \"a\""))
        assertTrue(pretty.endsWith("}"))
        assertEquals(document, JsonDocument.parseOrThrow(pretty))
    }

    @Test
    fun `字符串转义往返一致`() {
        val document = JsonDocument.of(
            jsonObject("k" to jsonString("a\"b\\c\n\t\u0001中文")),
        )

        val text = document.toJsonText()

        assertTrue(text.contains("\\n"))
        assertEquals(document, JsonDocument.parseOrThrow(text))
    }

    @Test
    fun `解析失败报告行号与列号`() {
        val result = JsonDocument.parse("{\n  \"a\": 1,\n  \"b\": [1,]\n}")

        assertFalse(result.isSuccess)
        val error = result.error
        assertNotNull(error)
        assertEquals(3, error.line)
        assertTrue(error.column > 0)
        assertTrue(error.offset > 0)
        assertNotNull(error.snippet)
    }

    @Test
    fun `顶层多余内容被拒绝`() {
        val result = JsonDocument.parse("""{"a":1} trailing""")

        assertFalse(result.isSuccess)
        assertTrue(result.error!!.message.orEmpty().contains("多余字符"))
    }

    @Test
    fun `UTF-8 BOM 被忽略`() {
        val bom = 0xFEFF.toChar()

        val document = JsonDocument.parseOrThrow("${bom}{\"a\":1}")

        assertEquals(jsonNumber(1), (document.root as JsonNode.ObjectNode).get("a"))
    }

    @Test
    fun `空对象与空数组格式化`() {
        val document = JsonDocument.parseOrThrow("""{"a":{},"b":[]}""")

        assertEquals("{\n  \"a\": {},\n  \"b\": []\n}", document.toJsonText(JsonFormat.Pretty()))
    }

    @Test
    fun `嵌套深度超限时失败而不是栈溢出`() {
        val text = "[".repeat(20) + "]".repeat(20)

        val result = JsonDocument.parse(text, maxDepth = 5)

        assertFalse(result.isSuccess)
        assertTrue(result.error!!.message.orEmpty().contains("嵌套层级"))
    }
}
