package org.zender.editor.document

/**
 * 文档标识。视图层、命令栈、脏标记都以它作为键，不直接使用文件路径字符串。
 */
@JvmInline
value class DocumentId(val value: String) {
    init {
        require(value.isNotBlank()) { "DocumentId 不能为空" }
    }

    override fun toString(): String = value
}

/**
 * 可编辑 JSON 文档。
 *
 * 文档本身不可变；编辑通过产生新的 [JsonDocument] 完成。
 */
class JsonDocument(val root: JsonNode) {

    fun toJsonText(format: JsonFormat = JsonFormat.Compact): String = root.toJsonText(format)

    override fun equals(other: Any?): Boolean = other is JsonDocument && root == other.root

    override fun hashCode(): Int = root.hashCode()

    override fun toString(): String = toJsonText()

    companion object {
        val EMPTY: JsonDocument = JsonDocument(JsonNode.ObjectNode.EMPTY)

        fun of(root: JsonNode): JsonDocument = JsonDocument(root)

        /**
         * 解析 JSON 文本。
         *
         * - 整个文件只允许一个顶层值；
         * - 重复键策略由 [duplicateKeyPolicy] 决定；
         * - 解析失败返回 [JsonParseResult.error]，不会抛异常；
         * - [maxDepth] 防止恶意/损坏文件导致栈溢出。
         */
        fun parse(
            text: String,
            duplicateKeyPolicy: DuplicateKeyPolicy = DuplicateKeyPolicy.PRESERVE,
            maxDepth: Int = DEFAULT_MAX_DEPTH,
        ): JsonParseResult {
            require(maxDepth >= 1) { "maxDepth 必须大于 0" }
            val byteOrderMark = 0xFEFF.toChar()
            val source = if (text.startsWith(byteOrderMark)) text.substring(1) else text
            return JsonParser(source, duplicateKeyPolicy, maxDepth).parse()
        }

        fun parseOrThrow(
            text: String,
            duplicateKeyPolicy: DuplicateKeyPolicy = DuplicateKeyPolicy.PRESERVE,
            maxDepth: Int = DEFAULT_MAX_DEPTH,
        ): JsonDocument = parse(text, duplicateKeyPolicy, maxDepth).documentOrThrow()

        const val DEFAULT_MAX_DEPTH: Int = 512
    }
}

/**
 * 重复键处理策略。
 *
 * [PRESERVE]：保留所有成员，访问时最后一个生效，并记录 [JsonIssue]；
 * [REJECT]：遇到重复键立即判定解析失败。
 */
enum class DuplicateKeyPolicy {
    PRESERVE,
    REJECT,
}

data class JsonParseResult(
    val document: JsonDocument?,
    val error: JsonParseException? = null,
    val issues: List<JsonIssue> = emptyList(),
) {
    val isSuccess: Boolean
        get() = document != null && error == null

    fun documentOrThrow(): JsonDocument =
        document ?: throw error ?: IllegalStateException("JSON 解析失败但没有错误信息")

    companion object {
        fun success(document: JsonDocument, issues: List<JsonIssue> = emptyList()): JsonParseResult =
            JsonParseResult(document, null, issues)

        fun failure(error: JsonParseException, issues: List<JsonIssue> = emptyList()): JsonParseResult =
            JsonParseResult(null, error, issues)
    }
}

/**
 * 非致命解析问题（例如重复键）。路径使用便于展示的文本形式。
 */
data class JsonIssue(
    val message: String,
    val path: String? = null,
    val offset: Int? = null,
)

class JsonParseException(
    message: String,
    val offset: Int,
    val line: Int,
    val column: Int,
    val snippet: String? = null,
    val source: String? = null,
) : IllegalArgumentException(
    buildString {
        append(message)
        append("（第 ").append(line).append(" 行，第 ").append(column).append(" 列）")
        if (snippet != null) {
            append('\n').append(snippet)
        }
    },
) {
    companion object {
        fun at(text: String, offset: Int, message: String): JsonParseException {
            val safeOffset = offset.coerceIn(0, text.length)
            var line = 1
            var lineStart = 0
            for (index in 0 until safeOffset) {
                if (text[index] == '\n') {
                    line++
                    lineStart = index + 1
                }
            }
            val column = safeOffset - lineStart + 1
            val lineEnd = text.indexOf('\n', safeOffset).let { if (it < 0) text.length else it }
            val snippet = text.substring(lineStart, lineEnd)
            val caret = " ".repeat((safeOffset - lineStart).coerceAtLeast(0)) + "^"
            return JsonParseException(message, safeOffset, line, column, "$snippet\n$caret", text)
        }
    }
}

private class JsonParser(
    private val text: String,
    private val duplicateKeyPolicy: DuplicateKeyPolicy,
    private val maxDepth: Int,
) {
    private var index: Int = 0
    private val issues = mutableListOf<JsonIssue>()

    fun parse(): JsonParseResult {
        return try {
            skipWhitespace()
            if (index >= text.length) fail("JSON 内容为空")
            val root = parseValue(path = "", depth = 0)
            skipWhitespace()
            if (index != text.length) fail("JSON 结束后存在多余字符")
            JsonParseResult.success(JsonDocument(root), issues.toList())
        } catch (e: JsonParseException) {
            JsonParseResult.failure(e, issues.toList())
        }
    }

    private fun parseValue(path: String, depth: Int): JsonNode {
        if (depth > maxDepth) failAt(index, "JSON 嵌套层级超过上限 $maxDepth")
        skipWhitespace()
        if (index >= text.length) fail("缺少 JSON 值")
        return when (val ch = text[index]) {
            '{' -> parseObject(path, depth)
            '[' -> parseArray(path, depth)
            '"' -> JsonNode.StringNode(parseString())
            't' -> {
                expectLiteral("true")
                JsonNode.BooleanNode.TRUE
            }
            'f' -> {
                expectLiteral("false")
                JsonNode.BooleanNode.FALSE
            }
            'n' -> {
                expectLiteral("null")
                JsonNode.NullNode
            }
            else -> {
                if (ch == '-' || ch in '0'..'9') {
                    parseNumber()
                } else {
                    fail("非法 JSON 值起始字符：'$ch'")
                }
            }
        }
    }

    private fun parseObject(path: String, depth: Int): JsonNode.ObjectNode {
        expect('{')
        skipWhitespace()
        if (index < text.length && text[index] == '}') {
            index++
            return JsonNode.ObjectNode.EMPTY
        }
        val members = mutableListOf<Member>()
        val seen = HashSet<String>()
        while (true) {
            skipWhitespace()
            if (index >= text.length || text[index] != '"') {
                fail("对象键必须是字符串")
            }
            val keyOffset = index
            val key = parseString()
            val memberPath = joinPath(path, key)
            skipWhitespace()
            expect(':')
            val value = parseValue(memberPath, depth + 1)
            if (!seen.add(key)) {
                if (duplicateKeyPolicy == DuplicateKeyPolicy.REJECT) {
                    failAt(keyOffset, "重复键：$key")
                }
                issues += JsonIssue("重复键：$key", memberPath, keyOffset)
            }
            members += Member(key, value)

            skipWhitespace()
            if (index >= text.length) fail("对象未闭合")
            when (text[index]) {
                ',' -> {
                    index++
                    skipWhitespace()
                    if (index < text.length && text[index] == '}') fail("对象末尾不允许逗号")
                }
                '}' -> {
                    index++
                    return JsonNode.ObjectNode(members.toList())
                }
                else -> fail("对象成员之间缺少逗号或右花括号")
            }
        }
    }

    private fun parseArray(path: String, depth: Int): JsonNode.ArrayNode {
        expect('[')
        skipWhitespace()
        if (index < text.length && text[index] == ']') {
            index++
            return JsonNode.ArrayNode.EMPTY
        }
        val elements = mutableListOf<JsonNode>()
        while (true) {
            val elementIndex = elements.size
            elements += parseValue("$path[$elementIndex]", depth + 1)
            skipWhitespace()
            if (index >= text.length) fail("数组未闭合")
            when (text[index]) {
                ',' -> {
                    index++
                    skipWhitespace()
                    if (index < text.length && text[index] == ']') fail("数组末尾不允许逗号")
                }
                ']' -> {
                    index++
                    return JsonNode.ArrayNode(elements.toList())
                }
                else -> fail("数组元素之间缺少逗号或右方括号")
            }
        }
    }

    private fun parseString(): String {
        val start = index
        return try {
            val (value, nextIndex) = JsonText.decodeStringLiteral(text, start)
            index = nextIndex
            value
        } catch (e: IllegalArgumentException) {
            failAt(start, e.message ?: "非法字符串")
        }
    }

    private fun parseNumber(): JsonNode.NumberNode {
        val start = index
        if (text[index] == '-') index++
        if (index >= text.length) failAt(start, "数字格式不正确")
        when {
            text[index] == '0' -> index++
            text[index] in '1'..'9' -> while (index < text.length && text[index] in '0'..'9') index++
            else -> failAt(start, "数字格式不正确")
        }
        if (index < text.length && text[index] == '.') {
            index++
            if (index >= text.length || text[index] !in '0'..'9') failAt(start, "小数点后缺少数字")
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index++
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
            if (index >= text.length || text[index] !in '0'..'9') failAt(start, "指数部分缺少数字")
            while (index < text.length && text[index] in '0'..'9') index++
        }
        val raw = text.substring(start, index)
        return try {
            JsonNode.NumberNode(raw)
        } catch (_: IllegalArgumentException) {
            failAt(start, "非法数字：$raw")
        }
    }

    private fun expectLiteral(literal: String) {
        if (!text.startsWith(literal, index)) fail("期望字面量 $literal")
        index += literal.length
    }

    private fun expect(ch: Char) {
        if (index >= text.length || text[index] != ch) fail("期望 '$ch'")
        index++
    }

    private fun skipWhitespace() {
        while (index < text.length) {
            when (text[index]) {
                ' ', '\t', '\n', '\r' -> index++
                else -> return
            }
        }
    }

    private fun fail(message: String): Nothing = failAt(index, message)

    private fun failAt(offset: Int, message: String): Nothing =
        throw JsonParseException.at(text, offset, message)

    private fun joinPath(parent: String, key: String): String {
        val simple = key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '_' }
        return when {
            parent.isEmpty() && simple -> key
            parent.isEmpty() -> "[\"${JsonText.escape(key)}\"]"
            simple -> "$parent.$key"
            else -> "$parent[\"${JsonText.escape(key)}\"]"
        }
    }
}
