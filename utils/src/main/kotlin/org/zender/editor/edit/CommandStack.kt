package org.zender.editor.edit

import org.zender.editor.document.JsonDocument

/**
 * 文档级撤销/重做栈。
 *
 * - 保存命令前后的不可变文档快照，因此撤销/重做不依赖命令反向实现；
 * - 相同 [EditCommand.mergeKey] 的连续命令合并为一条历史；
 * - [endMergeGroup] 用于在焦点切换、保存等边界打断合并；
 * - [maxHistory] 限制历史深度，避免大文件长期编辑占用过多内存。
 */
class CommandStack(
    initial: JsonDocument,
    private val maxHistory: Int = DEFAULT_MAX_HISTORY,
) {

    private data class Entry(
        val label: String,
        val mergeKey: String?,
        val before: JsonDocument,
        val after: JsonDocument,
    )

    private val undoEntries = ArrayDeque<Entry>()
    private val redoEntries = ArrayDeque<Entry>()

    /** 最近一次进入历史的合并键；为 null 时下一条命令一定新建历史。 */
    private var lastMergeKey: String? = null

    var current: JsonDocument = initial
        private set

    /** 每次执行、撤销、重做递增，供状态快照判断是否变化。 */
    var revision: Long = 0L
        private set

    val canUndo: Boolean get() = undoEntries.isNotEmpty()

    val canRedo: Boolean get() = redoEntries.isNotEmpty()

    val undoLabel: String? get() = undoEntries.lastOrNull()?.label

    val redoLabel: String? get() = redoEntries.lastOrNull()?.label

    val historyDepth: Int get() = undoEntries.size

    init {
        require(maxHistory >= 1) { "maxHistory 必须大于 0" }
    }

    fun execute(command: EditCommand): JsonDocument {
        require(command.label.isNotBlank()) { "编辑命令必须提供非空 label" }
        val before = current
        val after = command.execute(before)
        if (after == before) {
            // 空操作不进入历史，但仍打断合并，避免下一次真正的修改与旧历史合并。
            lastMergeKey = null
            return current
        }

        val top = undoEntries.lastOrNull()
        if (top != null &&
            command.mergeKey != null &&
            top.mergeKey == command.mergeKey &&
            lastMergeKey == command.mergeKey
        ) {
            undoEntries.removeLast()
            val merged = top.copy(after = after)
            if (merged.before != merged.after) {
                undoEntries.addLast(merged)
            }
        } else {
            undoEntries.addLast(Entry(command.label, command.mergeKey, before, after))
            while (undoEntries.size > maxHistory) {
                undoEntries.removeFirst()
            }
        }

        lastMergeKey = command.mergeKey
        redoEntries.clear()
        current = after
        revision++
        return current
    }

    /**
     * 撤销一条历史。没有可撤销内容时返回 null。
     */
    fun undo(): JsonDocument? {
        val entry = undoEntries.removeLastOrNull() ?: return null
        redoEntries.addLast(entry)
        current = entry.before
        lastMergeKey = null
        revision++
        return current
    }

    /**
     * 重做一条历史。没有可重做内容时返回 null。
     */
    fun redo(): JsonDocument? {
        val entry = redoEntries.removeLastOrNull() ?: return null
        undoEntries.addLast(entry)
        current = entry.after
        lastMergeKey = null
        revision++
        return current
    }

    /** 打断合并组；下一次执行即使 mergeKey 相同也会新建历史。 */
    fun endMergeGroup() {
        lastMergeKey = null
    }

    /** 清空历史但保留当前文档。 */
    fun clearHistory() {
        undoEntries.clear()
        redoEntries.clear()
        lastMergeKey = null
    }

    /** 用新文档重置栈（例如重新加载文件）。 */
    fun reset(document: JsonDocument) {
        current = document
        clearHistory()
        revision++
    }

    companion object {
        const val DEFAULT_MAX_HISTORY: Int = 200
    }
}
