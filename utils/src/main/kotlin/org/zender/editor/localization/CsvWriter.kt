package org.zender.editor.localization

/**
 * CSV 写入器，与 [CsvParser] 对称。
 *
 * - 字段包含分隔符、引号、换行或首尾空白时自动加引号；
 * - 引号使用 `""` 转义；
 * - 每行以 [lineSeparator] 结束（默认 LF，与仓库行尾约定一致）。
 */
object CsvWriter {

    fun write(
        rows: List<List<String>>,
        separator: Char = ',',
        quote: Char = '"',
        lineSeparator: String = "\n",
    ): String {
        if (rows.isEmpty()) return ""
        return buildString {
            rows.forEach { row ->
                row.forEachIndexed { index, value ->
                    if (index > 0) append(separator)
                    append(formatField(value, separator, quote))
                }
                append(lineSeparator)
            }
        }
    }

    fun formatField(
        value: String,
        separator: Char = ',',
        quote: Char = '"',
    ): String {
        val needsQuote = value.any { it == separator || it == quote || it == '\r' || it == '\n' } ||
            value != value.trim()
        if (!needsQuote) return value
        return buildString(value.length + 2) {
            append(quote)
            value.forEach { ch ->
                if (ch == quote) {
                    append(quote).append(quote)
                } else {
                    append(ch)
                }
            }
            append(quote)
        }
    }
}
