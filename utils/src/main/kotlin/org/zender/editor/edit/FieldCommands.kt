package org.zender.editor.edit

import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.document.JsonPathException
import org.zender.editor.document.JsonPathSegment
import org.zender.editor.document.readPath
import org.zender.editor.document.removePath
import org.zender.editor.document.writePath
import org.zender.editor.document.jsonNumber
import org.zender.editor.document.jsonString

/**
 * 设置一个已存在的字段（或对象顶层新字段）。
 *
 * 中间对象必须已经存在；需要自动创建中间对象时使用 [SetNestedFieldCommand]。
 * 连续输入同一字段时由调用方传入相同的 [mergeKey]（例如
 * `"set:${documentId}:${path.format()}"`），让编辑历史合并为一条。
 */
class SetFieldCommand(
    override val label: String,
    val path: JsonPath,
    val value: JsonNode,
    override val mergeKey: String? = null,
) : EditCommand {

    init {
        require(!path.isEmpty) { "SetField 路径不能为空" }
        require(path.last is JsonPathSegment.Key) { "SetField 路径必须以对象键结尾：$path" }
    }

    override fun execute(document: JsonDocument): JsonDocument =
        JsonDocument(document.root.writePath(path, value))
}

/**
 * 设置嵌套对象字段，中间对象缺失时自动创建。
 *
 * 示例：对空文档执行 `path = A.B.C` 会得到 `{"A":{"B":{"C":value}}}`。
 * 数组下标仍必须已经存在，不会隐式创建数组。
 */
class SetNestedFieldCommand(
    override val label: String,
    val path: JsonPath,
    val value: JsonNode,
    override val mergeKey: String? = null,
) : EditCommand {

    init {
        require(!path.isEmpty) { "SetNestedField 路径不能为空" }
    }

    override fun execute(document: JsonDocument): JsonDocument =
        JsonDocument(JsonEdits.writeWithParents(document.root, path, value))
}

/**
 * 设置标量引用字段的 `WarpData` / `WarpType` 配对。
 *
 * [fieldPath] 指向字段本体（例如 `CardImage`），命令写入同级的
 * `CardImageWarpData` 与 `CardImageWarpType`，不修改字段本体。
 *
 * 列表引用请使用 [AddReferenceToListCommand]。
 */
class SetReferenceCommand(
    override val label: String,
    val fieldPath: JsonPath,
    val referenceKey: String,
    val warpType: WarpType = WarpType.REFERENCE,
    override val mergeKey: String? = null,
) : EditCommand {

    init {
        require(!fieldPath.isEmpty) { "SetReference 字段路径不能为空" }
        require(fieldPath.last is JsonPathSegment.Key) {
            "SetReference 字段路径必须以对象键结尾：$fieldPath"
        }
        require(referenceKey.isNotBlank()) { "引用键不能为空" }
        require(warpType.value in 3..6) {
            "SetReference 只接受引用类 WarpType（3..6），实际为 ${warpType.value}"
        }
    }

    override fun execute(document: JsonDocument): JsonDocument {
        var root = JsonEdits.writeWithParents(
            document.root,
            WarpFields.dataPath(fieldPath),
            jsonString(referenceKey.trim()),
        )
        root = JsonEdits.writeWithParents(
            root,
            WarpFields.typePath(fieldPath),
            jsonNumber(warpType.value),
        )
        return JsonDocument(root)
    }
}

/**
 * 清除标量/列表引用字段的 `WarpData` 与 `WarpType` 配对。
 *
 * 不修改字段本体；两个陪伴键都不存在时命令是空操作。
 */
class ClearReferenceCommand(
    override val label: String,
    val fieldPath: JsonPath,
) : EditCommand {

    init {
        require(!fieldPath.isEmpty) { "ClearReference 字段路径不能为空" }
        require(fieldPath.last is JsonPathSegment.Key) {
            "ClearReference 字段路径必须以对象键结尾：$fieldPath"
        }
    }

    override fun execute(document: JsonDocument): JsonDocument {
        var root = document.root
        val typePath = WarpFields.typePath(fieldPath)
        if (root.readPath(typePath) != null) {
            root = root.removePath(typePath)
        }
        val dataPath = WarpFields.dataPath(fieldPath)
        if (root.readPath(dataPath) != null) {
            root = root.removePath(dataPath)
        }
        return JsonDocument(root)
    }
}

/**
 * 向列表引用追加/插入一个引用键。
 *
 * - [listFieldPath] 指向列表字段本体（例如 `CardTags`）；
 * - 命令读写 `CardTagsWarpData` 数组，并同步 `CardTagsWarpType`；
 * - 新建对象中的引用列表使用默认 [WarpType.REFERENCE]（整体替换语义）；
 * - 在 GameSourceModify / DataObjectModify 中追加时应显式传入
 *   [WarpType.ADD_REFERENCE]（MC）或按目标框架选择 3/6。
 *
 * 列表本体（通常为 `[]`）不会被修改。
 */
class AddReferenceToListCommand(
    override val label: String,
    val listFieldPath: JsonPath,
    val referenceKey: String,
    val warpType: WarpType = WarpType.REFERENCE,
    /** 插入下标；null 表示追加到末尾。 */
    val index: Int? = null,
    override val mergeKey: String? = null,
) : EditCommand {

    init {
        require(!listFieldPath.isEmpty) { "AddReferenceToList 字段路径不能为空" }
        require(listFieldPath.last is JsonPathSegment.Key) {
            "AddReferenceToList 字段路径必须以对象键结尾：$listFieldPath"
        }
        require(referenceKey.isNotBlank()) { "引用键不能为空" }
        require(warpType.value in 3..6) {
            "AddReferenceToList 只接受引用类 WarpType（3..6），实际为 ${warpType.value}"
        }
        require(index == null || index >= 0) { "插入下标不能为负数：$index" }
    }

    override fun execute(document: JsonDocument): JsonDocument {
        val dataPath = WarpFields.dataPath(listFieldPath)
        val existing = document.root.readPath(dataPath)
        val array = when (existing) {
            null -> JsonNode.ArrayNode.EMPTY
            is JsonNode.ArrayNode -> existing
            else -> throw JsonPathException("WarpData 不是数组：$dataPath")
        }
        val position = index ?: array.elements.size
        if (position !in 0..array.elements.size) {
            throw JsonPathException(
                "插入下标越界：$position，长度 ${array.elements.size}",
            )
        }
        val elements = array.elements.toMutableList()
        elements.add(position, jsonString(referenceKey.trim()))

        var root = JsonEdits.writeWithParents(
            document.root,
            dataPath,
            JsonNode.ArrayNode(elements.toList()),
        )
        root = JsonEdits.writeWithParents(
            root,
            WarpFields.typePath(listFieldPath),
            jsonNumber(warpType.value),
        )
        return JsonDocument(root)
    }
}
