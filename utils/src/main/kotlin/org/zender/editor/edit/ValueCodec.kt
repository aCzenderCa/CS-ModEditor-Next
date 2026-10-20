package org.zender.editor.edit

import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonFormat
import org.zender.editor.document.JsonNode
import org.zender.editor.document.jsonArray
import org.zender.editor.document.jsonBoolean
import org.zender.editor.document.jsonNumber
import org.zender.editor.document.jsonObject
import org.zender.editor.document.jsonString
import org.zender.editor.document.toJsonText
import org.zender.editor.schema.DefaultValueResolver
import org.zender.editor.schema.EnumValue
import org.zender.editor.schema.FieldKind

/**
 * 字段值文本解析失败。消息可直接展示在字段视图或状态栏。
 */
class ValueParseException(message: String) : IllegalArgumentException(message)

/**
 * 字段视图/原始视图共用的值编解码。
 *
 * - 标量按 [FieldKind] 解析用户输入，不要求输入 JSON 引号；
 * - 对象、列表与未知字段按完整 JSON 文本解析；
 * - 枚举优先匹配名称，其次匹配枚举值本身；
 * - 视图层不得自行拼接/解析 JSON，统一通过本对象完成。
 */
object ValueCodec {

    /** 编辑框文本：字符串返回原文，其余返回紧凑 JSON。 */
    fun format(node: JsonNode?): String = when (node) {
        null -> ""
        is JsonNode.StringNode -> node.value
        else -> node.toJsonText(JsonFormat.Compact)
    }

    /** 只读展示：null 显示为“默认/缺失”，字符串加引号，其余紧凑 JSON。 */
    fun display(node: JsonNode?): String = when (node) {
        null -> "（默认/缺失）"
        is JsonNode.StringNode -> "\"${node.value}\""
        else -> node.toJsonText(JsonFormat.Compact)
    }

    /**
     * 把用户输入解析为 JSON 节点。
     *
     * @throws ValueParseException 输入与字段种类不匹配。
     */
    fun parse(
        kind: FieldKind,
        text: String,
        enumValues: List<EnumValue> = emptyList(),
    ): JsonNode {
        val trimmed = text.trim()
        return when (kind) {
            FieldKind.BOOLEAN -> {
                val value = trimmed.toBooleanStrictOrNull()
                    ?: throw ValueParseException("布尔字段只接受 true 或 false")
                jsonBoolean(value)
            }

            FieldKind.INTEGER -> {
                if (trimmed.isEmpty()) throw ValueParseException("整数字段不能为空")
                val node = try {
                    JsonNode.NumberNode(trimmed)
                } catch (e: IllegalArgumentException) {
                    throw ValueParseException("不是合法数字：$trimmed")
                }
                if (!node.isInteger) throw ValueParseException("整数字段不接受小数：$trimmed")
                node
            }

            FieldKind.FLOAT -> {
                if (trimmed.isEmpty()) throw ValueParseException("数字字段不能为空")
                try {
                    JsonNode.NumberNode(trimmed)
                } catch (e: IllegalArgumentException) {
                    throw ValueParseException("不是合法数字：$trimmed")
                }
            }

            FieldKind.STRING -> jsonString(text)

            FieldKind.ENUM -> enumNode(trimmed, enumValues)

            FieldKind.OBJECT -> {
                val node = parseJson(trimmed)
                if (node !is JsonNode.ObjectNode) {
                    throw ValueParseException("该字段要求 JSON 对象")
                }
                node
            }

            FieldKind.LIST -> {
                val node = parseJson(trimmed)
                if (node !is JsonNode.ArrayNode) {
                    throw ValueParseException("该字段要求 JSON 数组")
                }
                node
            }

            FieldKind.REFERENCE -> {
                val node = parseJson(trimmed)
                if (node !is JsonNode.ObjectNode && node != JsonNode.NullNode) {
                    throw ValueParseException("引用字段本体必须是 JSON 对象")
                }
                node
            }

            FieldKind.UNKNOWN -> parseJson(trimmed)
        }
    }

    /** 新建列表元素/缺失字段时使用的默认值。 */
    fun defaultValue(
        kind: FieldKind,
        enumValues: List<EnumValue> = emptyList(),
    ): JsonNode = when (kind) {
        FieldKind.BOOLEAN -> jsonBoolean(false)
        FieldKind.INTEGER -> jsonNumber(0)
        FieldKind.FLOAT -> jsonNumber(0.0)
        FieldKind.STRING -> jsonString("")
        FieldKind.ENUM -> enumValues.firstOrNull()?.value ?: JsonNode.NullNode
        FieldKind.OBJECT -> jsonObject()
        FieldKind.LIST -> jsonArray()
        FieldKind.REFERENCE -> jsonObject()
        FieldKind.UNKNOWN -> JsonNode.NullNode
    }

    private fun enumNode(text: String, enumValues: List<EnumValue>): JsonNode {
        if (enumValues.isEmpty()) return parseJson(text)
        enumValues.firstOrNull { it.name == text }?.let { return it.value }
        val parsed = parseJson(text)
        return enumValues.firstOrNull {
            DefaultValueResolver.semanticallyEquals(it.value, parsed)
        }?.value ?: throw ValueParseException(
            "不是合法枚举值；可用名称：${enumValues.joinToString("、") { it.name }}",
        )
    }

    private fun parseJson(text: String): JsonNode {
        if (text.isEmpty()) throw ValueParseException("JSON 输入不能为空")
        val result = JsonDocument.parse(text)
        return result.document?.root
            ?: throw ValueParseException(result.error?.message ?: "不是合法 JSON")
    }
}
