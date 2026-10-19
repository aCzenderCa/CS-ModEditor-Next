package org.zender.editor.document

/**
 * 有序 JSON 节点模型。
 *
 * 与 `kotlinx.serialization.json.JsonElement` 的区别：
 * - 对象使用成员列表而不是 Map，字段顺序与重复键都会原样保留；
 * - 数字保存原始文本（`1.0`、`1e3` 不会被归一化）；
 * - 文档模型不可变，修改操作返回新节点。
 *
 * 访问对象字段时遵循 JSON 解析器的通用约定：同名键以**最后一个**为准；
 * 需要检查重复键时使用 [JsonNode.ObjectNode.duplicateKeys]。
 */
sealed interface JsonNode {

    data class ObjectNode(val members: List<Member>) : JsonNode {

        fun get(name: String): JsonNode? = members.lastOrNull { it.name == name }?.value

        fun contains(name: String): Boolean = members.any { it.name == name }

        fun lastIndexOf(name: String): Int = members.indexOfLast { it.name == name }

        /** 出现次数大于 1 的键，按首次出现顺序返回。 */
        fun duplicateKeys(): List<String> {
            val counts = LinkedHashMap<String, Int>()
            members.forEach { counts[it.name] = (counts[it.name] ?: 0) + 1 }
            return counts.filterValues { it > 1 }.keys.toList()
        }

        companion object {
            val EMPTY = ObjectNode(emptyList())

            fun of(vararg members: Pair<String, JsonNode>): ObjectNode =
                ObjectNode(members.map { Member(it.first, it.second) })

            fun of(members: List<Member>): ObjectNode = ObjectNode(members.toList())
        }
    }

    data class ArrayNode(val elements: List<JsonNode>) : JsonNode {

        companion object {
            val EMPTY = ArrayNode(emptyList())

            fun of(vararg elements: JsonNode): ArrayNode = ArrayNode(elements.toList())

            fun of(elements: List<JsonNode>): ArrayNode = ArrayNode(elements.toList())
        }
    }

    data class StringNode(val value: String) : JsonNode

    data class NumberNode(val raw: String) : JsonNode {
        init {
            require(JSON_NUMBER_PATTERN.matches(raw)) { "非法 JSON 数字：$raw" }
        }

        val isInteger: Boolean
            get() = INTEGER_NUMBER_PATTERN.matches(raw)

        fun toLongOrNull(): Long? = if (isInteger) raw.toLongOrNull() else null

        fun toDouble(): Double = raw.toDouble()

        companion object {
            private val JSON_NUMBER_PATTERN =
                Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")
            private val INTEGER_NUMBER_PATTERN =
                Regex("""-?(?:0|[1-9][0-9]*)""")

            fun of(value: Long): NumberNode = NumberNode(value.toString())

            fun of(value: Int): NumberNode = NumberNode(value.toString())

            fun of(value: Double): NumberNode {
                require(value.isFinite()) { "JSON 数字不能是 NaN 或 Infinity" }
                return NumberNode(value.toString())
            }
        }
    }

    data class BooleanNode(val value: Boolean) : JsonNode {
        companion object {
            val TRUE = BooleanNode(true)
            val FALSE = BooleanNode(false)

            fun of(value: Boolean): BooleanNode = if (value) TRUE else FALSE
        }
    }

    data object NullNode : JsonNode
}

data class Member(
    val name: String,
    val value: JsonNode,
)

/**
 * JSON 输出格式。
 */
sealed interface JsonFormat {

    data object Compact : JsonFormat

    data class Pretty(val indent: String = "  ") : JsonFormat {
        init {
            require(indent.isNotEmpty() && indent.all { it == ' ' || it == '\t' }) {
                "缩进只能由空格或制表符组成"
            }
        }
    }
}

/** 便捷构造：`jsonString("a")`、`jsonNumber(1)`、`jsonObject("k" to node)`。 */
fun jsonString(value: String): JsonNode.StringNode = JsonNode.StringNode(value)

fun jsonNumber(value: Int): JsonNode.NumberNode = JsonNode.NumberNode.of(value)

fun jsonNumber(value: Long): JsonNode.NumberNode = JsonNode.NumberNode.of(value)

fun jsonNumber(value: Double): JsonNode.NumberNode = JsonNode.NumberNode.of(value)

fun jsonBoolean(value: Boolean): JsonNode.BooleanNode = JsonNode.BooleanNode.of(value)

fun jsonObject(vararg members: Pair<String, JsonNode>): JsonNode.ObjectNode =
    JsonNode.ObjectNode.of(*members)

fun jsonArray(vararg elements: JsonNode): JsonNode.ArrayNode = JsonNode.ArrayNode.of(*elements)

/**
 * 把节点序列化为 JSON 文本。对象成员顺序与重复键保持不变。
 */
fun JsonNode.toJsonText(format: JsonFormat = JsonFormat.Compact): String =
    buildString { appendJson(this, this@toJsonText, format, 0) }

private fun appendJson(builder: StringBuilder, node: JsonNode, format: JsonFormat, depth: Int) {
    when (node) {
        is JsonNode.ObjectNode -> appendObject(builder, node, format, depth)
        is JsonNode.ArrayNode -> appendArray(builder, node, format, depth)
        is JsonNode.StringNode -> {
            builder.append('"')
            builder.append(JsonText.escape(node.value))
            builder.append('"')
        }
        is JsonNode.NumberNode -> builder.append(node.raw)
        is JsonNode.BooleanNode -> builder.append(if (node.value) "true" else "false")
        JsonNode.NullNode -> builder.append("null")
    }
}

private fun appendObject(
    builder: StringBuilder,
    node: JsonNode.ObjectNode,
    format: JsonFormat,
    depth: Int,
) {
    if (node.members.isEmpty()) {
        builder.append("{}")
        return
    }
    val pretty = format as? JsonFormat.Pretty
    builder.append('{')
    node.members.forEachIndexed { index, member ->
        if (pretty != null) {
            builder.append('\n')
            repeat(depth + 1) { builder.append(pretty.indent) }
        }
        builder.append('"').append(JsonText.escape(member.name)).append('"').append(':')
        if (pretty != null) builder.append(' ')
        appendJson(builder, member.value, format, depth + 1)
        if (index != node.members.lastIndex) builder.append(',')
    }
    if (pretty != null) {
        builder.append('\n')
        repeat(depth) { builder.append(pretty.indent) }
    }
    builder.append('}')
}

private fun appendArray(
    builder: StringBuilder,
    node: JsonNode.ArrayNode,
    format: JsonFormat,
    depth: Int,
) {
    if (node.elements.isEmpty()) {
        builder.append("[]")
        return
    }
    val pretty = format as? JsonFormat.Pretty
    builder.append('[')
    node.elements.forEachIndexed { index, element ->
        if (pretty != null) {
            builder.append('\n')
            repeat(depth + 1) { builder.append(pretty.indent) }
        }
        appendJson(builder, element, format, depth + 1)
        if (index != node.elements.lastIndex) builder.append(',')
    }
    if (pretty != null) {
        builder.append('\n')
        repeat(depth) { builder.append(pretty.indent) }
    }
    builder.append(']')
}

/**
 * JSON 字符串字面量工具，供路径解析与序列化共用。
 */
object JsonText {

    fun escape(value: String): String = buildString(value.length + 8) {
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch < ' ') {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
    }

    /**
     * 解析从 [start] 开始的 JSON 字符串字面量（[start] 必须指向开头的双引号）。
     *
     * @return 解码后的字符串与右引号之后的下标。
     */
    fun decodeStringLiteral(text: String, start: Int): Pair<String, Int> {
        require(start in text.indices && text[start] == '"') { "字符串字面量必须从双引号开始" }
        val builder = StringBuilder()
        var index = start + 1
        while (index < text.length) {
            val ch = text[index++]
            when {
                ch == '"' -> return builder.toString() to index
                ch == '\\' -> {
                    if (index >= text.length) throw IllegalArgumentException("字符串转义未完成")
                    when (val escaped = text[index++]) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) {
                                throw IllegalArgumentException("\\u 转义需要 4 位十六进制数字")
                            }
                            val hex = text.substring(index, index + 4)
                            if (!hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
                                throw IllegalArgumentException("非法 \\u 转义：$hex")
                            }
                            builder.append(hex.toInt(16).toChar())
                            index += 4
                        }
                        else -> throw IllegalArgumentException("非法转义：\\$escaped")
                    }
                }
                ch < ' ' -> throw IllegalArgumentException("字符串中不允许未转义的控制字符")
                else -> builder.append(ch)
            }
        }
        throw IllegalArgumentException("字符串未闭合")
    }
}
