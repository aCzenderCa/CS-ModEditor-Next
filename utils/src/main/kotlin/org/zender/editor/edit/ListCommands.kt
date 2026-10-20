package org.zender.editor.edit

import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.document.JsonPathException
import org.zender.editor.document.readPath
import org.zender.editor.document.writePath

/**
 * 向数组/List 追加或插入一个内联元素。
 *
 * [arrayPath] 必须指向已存在的数组。引用列表请使用 [AddReferenceToListCommand]，
 * 因为引用元素保存在 `XxxWarpData` 中而不是数组本体。
 */
class AddInlineElementCommand(
    override val label: String,
    val arrayPath: JsonPath,
    val element: JsonNode,
    /** 插入下标；null 表示追加到末尾。 */
    val index: Int? = null,
    override val mergeKey: String? = null,
) : EditCommand {

    init {
        require(!arrayPath.isEmpty) { "AddInlineElement 数组路径不能为空" }
        require(index == null || index >= 0) { "插入下标不能为负数：$index" }
    }

    override fun execute(document: JsonDocument): JsonDocument {
        val elements = requireArray(document, arrayPath).elements.toMutableList()
        val position = index ?: elements.size
        if (position !in 0..elements.size) {
            throw JsonPathException(
                "插入下标越界：$position，长度 ${elements.size}",
            )
        }
        elements.add(position, element)
        return JsonDocument(
            document.root.writePath(arrayPath, JsonNode.ArrayNode(elements.toList())),
        )
    }
}

/**
 * 删除数组/List 中的一个元素。
 *
 * 引用列表应传入 `WarpFields.dataPath(字段路径)`，即 `XxxWarpData` 数组。
 */
class RemoveElementCommand(
    override val label: String,
    val arrayPath: JsonPath,
    val index: Int,
) : EditCommand {

    init {
        require(!arrayPath.isEmpty) { "RemoveElement 数组路径不能为空" }
        require(index >= 0) { "删除下标不能为负数：$index" }
    }

    override fun execute(document: JsonDocument): JsonDocument {
        val elements = requireArray(document, arrayPath).elements.toMutableList()
        if (index !in elements.indices) {
            throw JsonPathException("删除下标越界：$index，长度 ${elements.size}")
        }
        elements.removeAt(index)
        return JsonDocument(
            document.root.writePath(arrayPath, JsonNode.ArrayNode(elements.toList())),
        )
    }
}

/**
 * 移动数组/List 中的元素，[toIndex] 是移动后的最终下标。
 *
 * 引用列表应传入 `WarpFields.dataPath(字段路径)`。
 */
class MoveElementCommand(
    override val label: String,
    val arrayPath: JsonPath,
    val fromIndex: Int,
    val toIndex: Int,
) : EditCommand {

    init {
        require(!arrayPath.isEmpty) { "MoveElement 数组路径不能为空" }
        require(fromIndex >= 0) { "移动起始下标不能为负数：$fromIndex" }
        require(toIndex >= 0) { "移动目标下标不能为负数：$toIndex" }
    }

    override fun execute(document: JsonDocument): JsonDocument {
        val elements = requireArray(document, arrayPath).elements.toMutableList()
        if (fromIndex !in elements.indices) {
            throw JsonPathException("移动起始下标越界：$fromIndex，长度 ${elements.size}")
        }
        if (toIndex !in elements.indices) {
            throw JsonPathException("移动目标下标越界：$toIndex，长度 ${elements.size}")
        }
        if (fromIndex == toIndex) return document
        val moved = elements.removeAt(fromIndex)
        elements.add(toIndex, moved)
        return JsonDocument(
            document.root.writePath(arrayPath, JsonNode.ArrayNode(elements.toList())),
        )
    }
}

private fun requireArray(document: JsonDocument, path: JsonPath): JsonNode.ArrayNode {
    val node = document.root.readPath(path)
        ?: throw JsonPathException("路径不存在：$path")
    return node as? JsonNode.ArrayNode
        ?: throw JsonPathException("路径不是数组：$path")
}
