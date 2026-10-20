package org.zender.editor.edit

import org.zender.editor.document.JsonNode
import org.zender.editor.schema.EnumValue
import org.zender.editor.schema.FieldKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ValueCodecTest {

    @Test
    fun `字符串保持原文不要求 JSON 引号`() {
        assertEquals(JsonNode.StringNode("a b"), ValueCodec.parse(FieldKind.STRING, "a b"))
        assertEquals("\"a b\"", ValueCodec.display(JsonNode.StringNode("a b")))
    }

    @Test
    fun `整数拒绝小数`() {
        assertEquals(JsonNode.NumberNode("42"), ValueCodec.parse(FieldKind.INTEGER, "42"))
        val error = assertFailsWith<ValueParseException> {
            ValueCodec.parse(FieldKind.INTEGER, "4.2")
        }
        assertTrue(error.message!!.contains("小数"))
    }

    @Test
    fun `浮点接受整数与科学计数法`() {
        assertEquals(JsonNode.NumberNode("1"), ValueCodec.parse(FieldKind.FLOAT, "1"))
        assertEquals(JsonNode.NumberNode("1e3"), ValueCodec.parse(FieldKind.FLOAT, " 1e3 "))
    }

    @Test
    fun `布尔只接受 true 或 false`() {
        assertEquals(JsonNode.BooleanNode.TRUE, ValueCodec.parse(FieldKind.BOOLEAN, "true"))
        assertFailsWith<ValueParseException> { ValueCodec.parse(FieldKind.BOOLEAN, "1") }
    }

    @Test
    fun `枚举优先按名称解析`() {
        val values = listOf(
            EnumValue("Item", JsonNode.NumberNode("0")),
            EnumValue("Base", JsonNode.NumberNode("1")),
        )
        assertEquals(JsonNode.NumberNode("1"), ValueCodec.parse(FieldKind.ENUM, "Base", values))
        assertEquals(JsonNode.NumberNode("0"), ValueCodec.parse(FieldKind.ENUM, "0", values))
        assertFailsWith<ValueParseException> {
            ValueCodec.parse(FieldKind.ENUM, "Unknown", values)
        }
    }

    @Test
    fun `对象与列表要求对应 JSON 形状`() {
        assertTrue(ValueCodec.parse(FieldKind.OBJECT, """{"a":1}""") is JsonNode.ObjectNode)
        assertTrue(ValueCodec.parse(FieldKind.LIST, "[1,2]") is JsonNode.ArrayNode)
        assertFailsWith<ValueParseException> { ValueCodec.parse(FieldKind.OBJECT, "[]") }
        assertFailsWith<ValueParseException> { ValueCodec.parse(FieldKind.LIST, "{}") }
    }

    @Test
    fun `默认值按种类生成`() {
        assertEquals(JsonNode.BooleanNode.FALSE, ValueCodec.defaultValue(FieldKind.BOOLEAN))
        assertEquals(JsonNode.NumberNode("0"), ValueCodec.defaultValue(FieldKind.INTEGER))
        assertEquals(JsonNode.NumberNode("0.0"), ValueCodec.defaultValue(FieldKind.FLOAT))
        assertEquals(JsonNode.StringNode(""), ValueCodec.defaultValue(FieldKind.STRING))
        assertTrue(ValueCodec.defaultValue(FieldKind.LIST) is JsonNode.ArrayNode)
        assertEquals(JsonNode.NullNode, ValueCodec.defaultValue(FieldKind.UNKNOWN))
    }

    @Test
    fun `null 节点展示为默认或缺失`() {
        assertEquals("（默认/缺失）", ValueCodec.display(null))
        assertEquals("[1]", ValueCodec.display(JsonNode.ArrayNode.of(JsonNode.NumberNode("1"))))
    }
}
