package org.zender.editor.edit

import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.document.JsonPathException
import org.zender.editor.document.JsonPathSegment
import org.zender.editor.document.Member

/**
 * `WarpType` 枚举，数值与游戏运行时一致（ML `WarpperFunction.WarpType` / MC `Loader.WarpType`）。
 */
enum class WarpType(val value: Int) {
    NONE(0),
    COPY(1),
    CUSTOM(2),

    /** 按名字/GUID 查对象并赋值；数组/List 整体替换。 */
    REFERENCE(3),

    /** 向 List/数组追加由内联 JSON 创建的新实例。 */
    ADD(4),

    /** 按索引递归修改已有元素或子对象。 */
    MODIFY(5),

    /** 向 List/数组追加引用。 */
    ADD_REFERENCE(6),
    ;

    companion object {
        fun fromValue(value: Int): WarpType? = entries.firstOrNull { it.value == value }

        fun fromNode(node: JsonNode?): WarpType? {
            val number = node as? JsonNode.NumberNode ?: return null
            val value = number.toLongOrNull() ?: return null
            if (value !in 0L..6L) return null
            return fromValue(value.toInt())
        }
    }
}

/**
 * 引用字段在 JSON 中的配对约定：
 *
 * ```json
 * "CardImage": {},
 * "CardImageWarpData": "BoarEnclosureFemale",
 * "CardImageWarpType": 3
 * ```
 *
 * 列表引用同理，只是 `WarpData` 是字符串数组、字段本体通常为 `[]`。
 * 视图层与命令层统一通过本对象计算配对路径，禁止手拼后缀。
 */
object WarpFields {

    const val DATA_SUFFIX: String = "WarpData"

    const val TYPE_SUFFIX: String = "WarpType"

    fun dataName(fieldName: String): String = fieldName + DATA_SUFFIX

    fun typeName(fieldName: String): String = fieldName + TYPE_SUFFIX

    /** 字段路径对应的 `XxxWarpData` 路径。字段路径必须以对象键结尾。 */
    fun dataPath(fieldPath: JsonPath): JsonPath = appendSuffix(fieldPath, DATA_SUFFIX)

    /** 字段路径对应的 `XxxWarpType` 路径。字段路径必须以对象键结尾。 */
    fun typePath(fieldPath: JsonPath): JsonPath = appendSuffix(fieldPath, TYPE_SUFFIX)

    /** 若 [name] 是某个字段的 `WarpData` / `WarpType` 陪伴键，返回字段名。 */
    fun baseNameOrNull(name: String): String? {
        val base = when {
            name.length > DATA_SUFFIX.length && name.endsWith(DATA_SUFFIX) ->
                name.removeSuffix(DATA_SUFFIX)

            name.length > TYPE_SUFFIX.length && name.endsWith(TYPE_SUFFIX) ->
                name.removeSuffix(TYPE_SUFFIX)

            else -> return null
        }
        return base.takeIf { it.isNotEmpty() }
    }

    fun isWarpDataName(name: String): Boolean =
        name.length > DATA_SUFFIX.length && name.endsWith(DATA_SUFFIX)

    fun isWarpTypeName(name: String): Boolean =
        name.length > TYPE_SUFFIX.length && name.endsWith(TYPE_SUFFIX)

    private fun appendSuffix(path: JsonPath, suffix: String): JsonPath {
        require(!path.isEmpty) { "引用字段路径不能为空" }
        val last = path.last as? JsonPathSegment.Key
            ?: throw JsonPathException("引用字段路径必须以对象键结尾：$path")
        val segments = path.segments.dropLast(1) + JsonPathSegment.Key(last.key + suffix)
        return JsonPath.of(segments)
    }
}

/**
 * 命令内部使用的写入工具。
 *
 * 与 [org.zender.editor.document.writePath] 的区别：允许在中间对象缺失时自动创建空对象，
 * 供 [SetNestedFieldCommand] 与引用命令写入嵌套路径；数组下标仍必须已存在。
 */
internal object JsonEdits {

    fun writeWithParents(root: JsonNode, path: JsonPath, value: JsonNode): JsonNode {
        require(!path.isEmpty) { "写入路径不能为空" }
        return write(root, path.segments, value)
    }

    private fun write(
        node: JsonNode,
        segments: List<JsonPathSegment>,
        value: JsonNode,
    ): JsonNode {
        val head = segments.first()
        val tail = segments.drop(1)
        return when (head) {
            is JsonPathSegment.Key -> {
                val objectNode = node as? JsonNode.ObjectNode
                    ?: throw JsonPathException("路径 ${head.key} 的父节点不是对象")
                val memberIndex = objectNode.lastIndexOf(head.key)
                if (tail.isEmpty()) {
                    replaceOrAppend(objectNode, memberIndex, head.key, value)
                } else {
                    val existing = if (memberIndex >= 0) {
                        objectNode.members[memberIndex].value
                    } else {
                        null
                    }
                    val child = if (existing == null) {
                        if (tail.first() is JsonPathSegment.Index) {
                            throw JsonPathException("数组字段不存在：${head.key}")
                        }
                        JsonNode.ObjectNode.EMPTY
                    } else {
                        existing
                    }
                    val updated = write(child, tail, value)
                    replaceOrAppend(objectNode, memberIndex, head.key, updated)
                }
            }

            is JsonPathSegment.Index -> {
                val arrayNode = node as? JsonNode.ArrayNode
                    ?: throw JsonPathException("路径 [${head.index}] 的父节点不是数组")
                if (head.index !in arrayNode.elements.indices) {
                    throw JsonPathException(
                        "数组下标越界：${head.index}，长度 ${arrayNode.elements.size}",
                    )
                }
                val elements = arrayNode.elements.toMutableList()
                elements[head.index] = if (tail.isEmpty()) {
                    value
                } else {
                    write(elements[head.index], tail, value)
                }
                JsonNode.ArrayNode(elements.toList())
            }
        }
    }

    private fun replaceOrAppend(
        node: JsonNode.ObjectNode,
        memberIndex: Int,
        name: String,
        value: JsonNode,
    ): JsonNode.ObjectNode {
        if (memberIndex < 0) {
            return JsonNode.ObjectNode(node.members + Member(name, value))
        }
        val members = node.members.toMutableList()
        members[memberIndex] = Member(name, value)
        return JsonNode.ObjectNode(members.toList())
    }
}
