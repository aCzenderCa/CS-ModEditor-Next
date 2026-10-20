package org.zender.editor.schema

import org.zender.editor.document.JsonNode
import java.math.BigDecimal

/**
 * 基于 `BaseJson` 的默认值判定。
 *
 * - 字段在 JSON 中缺失时视为默认值（模板值由界面提供）；
 * - 显式写入但语义等于模板值的字段同样视为默认值；
 * - 比较必须语义化：`0` 与 `0.0` 相等、对象成员顺序无关、数组按元素顺序比较；
 *   不能把 `0/false/""/[]` 硬编码为默认值。
 */
class DefaultValueResolver(
    private val schemaRepository: SchemaRepository,
) {

    /** 字段的模板默认值；模板中没有该字段时返回 null。 */
    fun defaultValue(typeName: String, fieldName: String): JsonNode? =
        schemaRepository.template(typeName)?.get(fieldName)

    /** 字段是否处于默认状态。 */
    fun isDefault(typeName: String, fieldName: String, value: JsonNode?): Boolean {
        val template = schemaRepository.template(typeName)?.get(fieldName)
        return isDefaultValue(template, value)
    }

    fun isDefault(field: FieldSchema, value: JsonNode?): Boolean =
        isDefaultValue(field.templateValue, value)

    companion object {

        /**
         * [template] 为 null 表示模板中不存在该字段；此时只有缺失值算默认。
         */
        fun isDefaultValue(template: JsonNode?, value: JsonNode?): Boolean {
            if (template == null) return value == null
            if (value == null) return true
            return semanticallyEquals(template, value)
        }

        /**
         * JSON 语义相等：数字按数值比较，对象忽略成员顺序，数组按顺序逐项比较。
         */
        fun semanticallyEquals(left: JsonNode?, right: JsonNode?): Boolean {
            if (left == null || right == null) return left == right
            return when (left) {
                is JsonNode.ObjectNode -> {
                    val rightObject = right as? JsonNode.ObjectNode ?: return false
                    val leftMembers = lastValueByKey(left)
                    val rightMembers = lastValueByKey(rightObject)
                    if (leftMembers.keys != rightMembers.keys) return false
                    leftMembers.all { (key, value) ->
                        semanticallyEquals(value, rightMembers[key])
                    }
                }

                is JsonNode.ArrayNode -> {
                    val rightArray = right as? JsonNode.ArrayNode ?: return false
                    if (left.elements.size != right.elements.size) return false
                    left.elements.indices.all { index ->
                        semanticallyEquals(left.elements[index], rightArray.elements[index])
                    }
                }

                is JsonNode.NumberNode -> {
                    val rightNumber = right as? JsonNode.NumberNode ?: return false
                    numbersEqual(left.raw, rightNumber.raw)
                }

                is JsonNode.StringNode -> right is JsonNode.StringNode && left.value == right.value
                is JsonNode.BooleanNode -> right is JsonNode.BooleanNode && left.value == right.value
                JsonNode.NullNode -> right == JsonNode.NullNode
            }
        }

        private fun lastValueByKey(node: JsonNode.ObjectNode): Map<String, JsonNode> {
            val result = LinkedHashMap<String, JsonNode>(node.members.size)
            node.members.forEach { result[it.name] = it.value }
            return result
        }

        private fun numbersEqual(left: String, right: String): Boolean =
            try {
                BigDecimal(left).compareTo(BigDecimal(right)) == 0
            } catch (_: NumberFormatException) {
                left == right
            }
    }
}
