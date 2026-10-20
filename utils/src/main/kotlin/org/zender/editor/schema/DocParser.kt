package org.zender.editor.schema

/**
 * `Doc/<Type>.yml` 与 `Doc-En/<Type>.yml` 的受限 YAML 解析器。
 *
 * 数据包中的 Doc 只有两级结构：
 *
 * ```yaml
 * FieldName:
 *   Alias:
 *   Note: 说明文本
 *   Comment:
 * ```
 *
 * 支持普通/单引号/双引号标量、`|`/`>` 块标量、`#` 行注释；无法识别的行记录问题后跳过，
 * 不影响其他字段。这里刻意不引入完整 YAML 库，保持 `utils` 依赖最小。
 */
object DocParser {

    fun parse(typeName: String, text: String): TypeDocParseResult {
        val issues = mutableListOf<String>()
        val lines = normalizeLines(text)
        val result = LinkedHashMap<String, FieldNotes>()
        var currentField: String? = null
        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) {
                index++
                continue
            }
            val indent = line.indexOfFirst { !it.isWhitespace() }
            if (indent <= 0) {
                val separator = findKeySeparator(trimmed)
                if (separator <= 0) {
                    issues += "第 ${index + 1} 行无法识别：$trimmed"
                    index++
                    continue
                }
                val fieldName = unquote(trimmed.substring(0, separator).trim())
                if (fieldName.isBlank()) {
                    issues += "第 ${index + 1} 行字段名为空"
                    currentField = null
                } else {
                    currentField = fieldName
                    result.putIfAbsent(fieldName, FieldNotes())
                }
                index++
                continue
            }

            val fieldName = currentField
            if (fieldName == null) {
                issues += "第 ${index + 1} 行缺少所属字段"
                index++
                continue
            }

            val separator = findKeySeparator(trimmed)
            if (separator <= 0) {
                issues += "第 ${index + 1} 行无法识别：$trimmed"
                index++
                continue
            }
            val key = trimmed.substring(0, separator).trim()
            val rawValue = trimmed.substring(separator + 1).trim()
            if (key !in KNOWN_KEYS) {
                index++
                continue
            }

            val (value, nextIndex) = if (rawValue.startsWith('|') || rawValue.startsWith('>')) {
                readBlockScalar(lines, index, indent, rawValue, issues)
            } else {
                parseScalar(rawValue) to (index + 1)
            }

            val existing = result[fieldName] ?: FieldNotes()
            val updated = when (key) {
                "Alias" -> existing.copy(alias = value)
                "Note" -> existing.copy(note = value)
                "Comment" -> existing.copy(comment = value)
                else -> existing
            }
            result[fieldName] = updated
            index = nextIndex
        }

        return TypeDocParseResult(
            notes = TypeDocNotes(typeName = typeName, fields = result.toMap()),
            issues = issues,
        )
    }

    private val KNOWN_KEYS = setOf("Alias", "Note", "Comment")

    private fun normalizeLines(text: String): List<String> {
        val withoutBom = text.trimStart('﻿')
        return withoutBom.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    }

    /** 返回 `key: value` 中冒号的下标；键内的冒号不在支持范围内。 */
    private fun findKeySeparator(line: String): Int {
        var inSingle = false
        var inDouble = false
        line.forEachIndexed { index, ch ->
            when {
                ch == '\'' && !inDouble -> inSingle = !inSingle
                ch == '"' && !inSingle -> inDouble = !inDouble
                ch == ':' && !inSingle && !inDouble -> return index
            }
        }
        return -1
    }

    private fun parseScalar(raw: String): String? {
        if (raw.isEmpty()) return null
        val withoutComment = stripTrailingComment(raw).trim()
        if (withoutComment.isEmpty()) return null
        return unquote(withoutComment).takeIf { it.isNotBlank() }
    }

    private fun stripTrailingComment(raw: String): String {
        var inSingle = false
        var inDouble = false
        raw.forEachIndexed { index, ch ->
            when {
                ch == '\'' && !inDouble -> inSingle = !inSingle
                ch == '"' && !inSingle -> inDouble = !inDouble
                ch == '#' && !inSingle && !inDouble && index > 0 && raw[index - 1].isWhitespace() ->
                    return raw.substring(0, index)
            }
        }
        return raw
    }

    private fun unquote(value: String): String {
        if (value.length >= 2 && value.first() == '\'' && value.last() == '\'') {
            return value.substring(1, value.length - 1).replace("''", "'")
        }
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            return unescapeDoubleQuoted(value.substring(1, value.length - 1))
        }
        return value
    }

    private fun unescapeDoubleQuoted(value: String): String {
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index++]
            if (ch != '\\' || index >= value.length) {
                builder.append(ch)
                continue
            }
            when (val escaped = value[index++]) {
                'n' -> builder.append('\n')
                'r' -> builder.append('\r')
                't' -> builder.append('\t')
                '"' -> builder.append('"')
                '\\' -> builder.append('\\')
                else -> builder.append('\\').append(escaped)
            }
        }
        return builder.toString()
    }

    private fun readBlockScalar(
        lines: List<String>,
        headerIndex: Int,
        headerIndent: Int,
        header: String,
        issues: MutableList<String>,
    ): Pair<String?, Int> {
        val style = header[0]
        val chomping = header.drop(1).firstOrNull { it == '-' || it == '+' }
        val builder = StringBuilder()
        var index = headerIndex + 1
        var first = true
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                builder.append('\n')
                index++
                continue
            }
            val indent = line.indexOfFirst { !it.isWhitespace() }
            if (indent <= headerIndent) break
            if (!first) {
                builder.append(if (style == '|') '\n' else ' ')
            }
            builder.append(line.trim())
            first = false
            index++
        }
        if (builder.isEmpty()) {
            issues += "第 ${headerIndex + 1} 行块标量为空"
            return null to index
        }
        if (chomping != '-') {
            builder.append('\n')
        }
        // `>` 折叠标量按行折叠后再去掉首尾空白。
        return builder.toString().trim('\n').takeIf { it.isNotBlank() } to index
    }
}

data class TypeDocNotes(
    val typeName: String,
    val fields: Map<String, FieldNotes>,
) {
    fun field(name: String): FieldNotes? = fields[name]
}

data class TypeDocParseResult(
    val notes: TypeDocNotes,
    val issues: List<String> = emptyList(),
)
