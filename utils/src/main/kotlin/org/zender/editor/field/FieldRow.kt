package org.zender.editor.field

import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath

/**
 * 字段视图中的一行（字段投影结果）。
 *
 * 该模型由后续的 `FieldProjectionService` 生成：它组合 `JsonDocument`、
 * `TypeSchema` 与 `BaseJson` 默认值，视图层只渲染，不自行判断默认/未知/只读。
 */
data class FieldRow(
    /** 相对文档根节点的路径。 */
    val path: JsonPath,
    /** 展示名称：对象字段名，或数组元素的 `[索引]`。 */
    val name: String,
    /** Schema 中的类型名；未知字段为 null。 */
    val typeName: String? = null,
    /** 当前值；字段在 JSON 中缺失时为 null。 */
    val value: JsonNode? = null,
    /** `BaseJson` 模板默认值；没有模板时为 null。 */
    val defaultValue: JsonNode? = null,
    val isDefault: Boolean = false,
    val isModified: Boolean = false,
    val isUnknown: Boolean = false,
    val isReadOnly: Boolean = false,
    val isFavorite: Boolean = false,
    /** 引用字段的目标不存在或类型不匹配。 */
    val isUnresolvedReference: Boolean = false,
    /** 嵌套字段；对象成员或数组元素。 */
    val children: List<FieldRow> = emptyList(),
) {

    val isContainer: Boolean
        get() = value is JsonNode.ObjectNode || value is JsonNode.ArrayNode

    val childCount: Int
        get() = when (val node = value) {
            is JsonNode.ObjectNode -> node.members.size
            is JsonNode.ArrayNode -> node.elements.size
            else -> 0
        }

    val depth: Int
        get() = path.size

    /** 深度优先遍历自身及所有子行。 */
    fun walk(): Sequence<FieldRow> = sequence {
        yield(this@FieldRow)
        children.forEach { child -> yieldAll(child.walk()) }
    }

    /** 在子树中按路径查找字段行。 */
    fun find(path: JsonPath): FieldRow? {
        if (this.path == path) return this
        return children.firstNotNullOfOrNull { it.find(path) }
    }
}
