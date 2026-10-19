package org.zender.editor.edit

import org.zender.editor.document.JsonDocument

/**
 * 一条文档编辑命令。
 *
 * 命令必须是无副作用的纯函数：相同输入总是产生相同输出。
 * 返回值与输入结构相等时，命令栈视为空操作，不进入历史。
 */
interface EditCommand {

    /** 撤销/重做菜单中展示的名称。 */
    val label: String

    /**
     * 连续编辑合并键。例如同一字段的连续输入使用
     * `"set:${documentId}:${fieldPath}"`，避免每个字符产生一条撤销记录。
     * `null` 表示该命令永不与相邻命令合并。
     */
    val mergeKey: String?
        get() = null

    fun execute(document: JsonDocument): JsonDocument
}

/**
 * 整体替换文档，供原始 JSON 视图提交全文、外部重载等场景使用。
 */
class ReplaceDocumentCommand(
    override val label: String,
    private val replacement: JsonDocument,
) : EditCommand {
    override fun execute(document: JsonDocument): JsonDocument = replacement
}
