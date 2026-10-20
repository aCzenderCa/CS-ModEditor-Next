package org.zender.editor.diff

enum class DiffKind {
    UNCHANGED,
    ADDED,
    REMOVED,
}

/**
 * 行级差异的一行。[oldLine] / [newLine] 为从 1 开始的行号，缺失侧为 null。
 */
data class DiffLine(
    val kind: DiffKind,
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null,
)

/**
 * 轻量行级 diff。
 *
 * - 先裁剪公共前后缀，只对中间区域做 LCS；
 * - 单侧超过 [MAX_LCS_LINES] 行时退化为“全部删除 + 全部新增”，
 *   避免大文件在 UI 线程产生不可接受的内存/耗时；
 * - 结果为原始顺序，视图层直接渲染。
 */
object TextDiff {

    const val MAX_LCS_LINES: Int = 2_000

    fun diff(oldText: String, newText: String): List<DiffLine> {
        if (oldText == newText) {
            return lines(oldText).mapIndexed { index, text ->
                DiffLine(DiffKind.UNCHANGED, text, index + 1, index + 1)
            }
        }
        val oldLines = lines(oldText)
        val newLines = lines(newText)

        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size &&
            oldLines[prefix] == newLines[prefix]
        ) {
            prefix++
        }
        var suffix = 0
        while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix &&
            oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]
        ) {
            suffix++
        }

        val result = mutableListOf<DiffLine>()
        for (index in 0 until prefix) {
            result += DiffLine(DiffKind.UNCHANGED, oldLines[index], index + 1, index + 1)
        }

        val oldMiddle = oldLines.subList(prefix, oldLines.size - suffix)
        val newMiddle = newLines.subList(prefix, newLines.size - suffix)
        if (oldMiddle.size > MAX_LCS_LINES || newMiddle.size > MAX_LCS_LINES) {
            oldMiddle.forEachIndexed { index, text ->
                result += DiffLine(DiffKind.REMOVED, text, prefix + index + 1, null)
            }
            newMiddle.forEachIndexed { index, text ->
                result += DiffLine(DiffKind.ADDED, text, null, prefix + index + 1)
            }
        } else {
            result += lcsDiff(oldMiddle, newMiddle, prefix)
        }

        for (index in suffix - 1 downTo 0) {
            val oldLine = oldLines.size - index
            val newLine = newLines.size - index
            result += DiffLine(DiffKind.UNCHANGED, oldLines[oldLine - 1], oldLine, newLine)
        }
        return result
    }

    private fun lcsDiff(
        oldLines: List<String>,
        newLines: List<String>,
        offset: Int,
    ): List<DiffLine> {
        val n = oldLines.size
        val m = newLines.size
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                table[i][j] = if (oldLines[i] == newLines[j]) {
                    table[i + 1][j + 1] + 1
                } else {
                    maxOf(table[i + 1][j], table[i][j + 1])
                }
            }
        }

        val reversed = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                oldLines[i] == newLines[j] -> {
                    reversed += DiffLine(
                        DiffKind.UNCHANGED,
                        oldLines[i],
                        offset + i + 1,
                        offset + j + 1,
                    )
                    i++
                    j++
                }

                table[i + 1][j] >= table[i][j + 1] -> {
                    reversed += DiffLine(DiffKind.REMOVED, oldLines[i], offset + i + 1, null)
                    i++
                }

                else -> {
                    reversed += DiffLine(DiffKind.ADDED, newLines[j], null, offset + j + 1)
                    j++
                }
            }
        }
        while (i < n) {
            reversed += DiffLine(DiffKind.REMOVED, oldLines[i], offset + i + 1, null)
            i++
        }
        while (j < m) {
            reversed += DiffLine(DiffKind.ADDED, newLines[j], null, offset + j + 1)
            j++
        }
        return reversed
    }

    private fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return text.split('\n')
    }
}
