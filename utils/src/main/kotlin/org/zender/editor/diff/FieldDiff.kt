package org.zender.editor.diff

import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath

/**
 * 一个字段级差异。[oldValue] / [newValue] 为 null 表示该侧字段不存在。
 */
data class FieldDifference(
    val path: String,
    val oldValue: JsonNode?,
    val newValue: JsonNode?,
) {
    val isAdded: Boolean get() = oldValue == null
    val isRemoved: Boolean get() = newValue == null
}

/**
 * 语义字段级 diff。
 *
 * - 对象逐字段递归；
 * - 数组按下标递归，长度差异体现为新增/删除；
 * - 标量或类型变化时输出一条差异；
 * - 对象/数组整段新增或删除时只输出该路径一条，避免展开大量叶子。
 */
object FieldDiff {

    fun diff(oldRoot: JsonNode?, newRoot: JsonNode?): List<FieldDifference> {
        val result = mutableListOf<FieldDifference>()
        compare(JsonPath.ROOT, oldRoot, newRoot, result)
        return result
    }

    private fun compare(
        path: JsonPath,
        oldValue: JsonNode?,
        newValue: JsonNode?,
        result: MutableList<FieldDifference>,
    ) {
        if (oldValue == newValue) return
        if (oldValue == null || newValue == null) {
            result += FieldDifference(path.format(), oldValue, newValue)
            return
        }
        if (oldValue is JsonNode.ObjectNode && newValue is JsonNode.ObjectNode) {
            val names = LinkedHashSet<String>()
            oldValue.members.forEach { names += it.name }
            newValue.members.forEach { names += it.name }
            names.forEach { name ->
                compare(
                    path.child(name),
                    oldValue.get(name),
                    newValue.get(name),
                    result,
                )
            }
            return
        }
        if (oldValue is JsonNode.ArrayNode && newValue is JsonNode.ArrayNode) {
            val size = maxOf(oldValue.elements.size, newValue.elements.size)
            for (index in 0 until size) {
                compare(
                    path.child(index),
                    oldValue.elements.getOrNull(index),
                    newValue.elements.getOrNull(index),
                    result,
                )
            }
            return
        }
        result += FieldDifference(path.format(), oldValue, newValue)
    }
}
