package org.zender.editor.document

/**
 * 字段路径的一段：对象键或数组下标。
 */
sealed interface JsonPathSegment {

    data class Key(val key: String) : JsonPathSegment

    data class Index(val index: Int) : JsonPathSegment {
        init {
            require(index >= 0) { "数组下标不能为负数：$index" }
        }
    }
}

class JsonPathException(
    message: String,
    val position: Int = -1,
) : IllegalArgumentException(
    if (position >= 0) "$message（位置 $position）" else message,
)

/**
 * 有序字段路径。
 *
 * 文本形式：
 * - `CardInteractions[0].ActionName`
 * - `foo["a.b"]`（键包含 `.`、`[` 等特殊字符时使用 JSON 字符串转义）
 *
 * 根路径的文本形式为空字符串，也接受 `$`。
 */
class JsonPath private constructor(val segments: List<JsonPathSegment>) {

    val size: Int get() = segments.size

    val isEmpty: Boolean get() = segments.isEmpty()

    val isRoot: Boolean get() = segments.isEmpty()

    val last: JsonPathSegment? get() = segments.lastOrNull()

    fun child(key: String): JsonPath = JsonPath(segments + JsonPathSegment.Key(key))

    fun child(index: Int): JsonPath = JsonPath(segments + JsonPathSegment.Index(index))

    fun parent(): JsonPath? =
        if (segments.isEmpty()) null else JsonPath(segments.dropLast(1))

    /** 去掉第一段，供 `writePath`/`removePath` 递归使用。 */
    internal fun dropFirst(): JsonPath = JsonPath(segments.drop(1))

    fun format(): String = buildString {
        segments.forEachIndexed { index, segment ->
            when (segment) {
                is JsonPathSegment.Key -> {
                    if (isSimpleKey(segment.key)) {
                        if (index > 0) append('.')
                        append(segment.key)
                    } else {
                        append("[\"")
                        append(JsonText.escape(segment.key))
                        append("\"]")
                    }
                }
                is JsonPathSegment.Index -> append('[').append(segment.index).append(']')
            }
        }
    }

    override fun toString(): String = format()

    override fun equals(other: Any?): Boolean = other is JsonPath && segments == other.segments

    override fun hashCode(): Int = segments.hashCode()

    companion object {
        val ROOT: JsonPath = JsonPath(emptyList())

        fun of(vararg segments: JsonPathSegment): JsonPath = JsonPath(segments.toList())

        fun of(segments: List<JsonPathSegment>): JsonPath = JsonPath(segments.toList())

        fun key(name: String): JsonPath = JsonPath(listOf(JsonPathSegment.Key(name)))

        fun index(index: Int): JsonPath = JsonPath(listOf(JsonPathSegment.Index(index)))

        fun parse(text: String): JsonPath {
            if (text.isEmpty() || text == "$") return ROOT
            val segments = mutableListOf<JsonPathSegment>()
            var index = if (text.startsWith('$')) 1 else 0
            var expectSegment = index == 0

            while (index < text.length) {
                when (text[index]) {
                    '.' -> {
                        if (expectSegment) {
                            throw JsonPathException("路径中出现空的键", index)
                        }
                        index++
                        if (index >= text.length) {
                            throw JsonPathException("路径不能以 '.' 结尾", index)
                        }
                        val start = index
                        index = parseBareKey(text, start, segments)
                        expectSegment = false
                    }
                    '[' -> {
                        index++
                        index = parseBracket(text, index, segments)
                        expectSegment = false
                    }
                    else -> {
                        if (!expectSegment) {
                            throw JsonPathException("缺少 '.' 或 '['", index)
                        }
                        index = parseBareKey(text, index, segments)
                        expectSegment = false
                    }
                }
                if (index < text.length) {
                    val ch = text[index]
                    if (ch != '.' && ch != '[') {
                        throw JsonPathException("路径段之间缺少分隔符", index)
                    }
                }
            }
            if (segments.isEmpty()) return ROOT
            return JsonPath(segments)
        }

        private fun parseBareKey(
            text: String,
            start: Int,
            segments: MutableList<JsonPathSegment>,
        ): Int {
            var index = start
            while (index < text.length && text[index] != '.' && text[index] != '[') {
                if (text[index] == ']') {
                    throw JsonPathException("未匹配的 ']'", index)
                }
                index++
            }
            if (index == start) throw JsonPathException("路径键不能为空", start)
            segments += JsonPathSegment.Key(text.substring(start, index))
            return index
        }

        private fun parseBracket(
            text: String,
            start: Int,
            segments: MutableList<JsonPathSegment>,
        ): Int {
            if (start >= text.length) throw JsonPathException("'[' 后缺少内容", start)
            var index = start
            if (text[index] == '"') {
                val (key, nextIndex) = try {
                    JsonText.decodeStringLiteral(text, index)
                } catch (e: IllegalArgumentException) {
                    throw JsonPathException(e.message ?: "非法字符串键", index)
                }
                index = nextIndex
                if (index >= text.length || text[index] != ']') {
                    throw JsonPathException("字符串键后缺少 ']'", index)
                }
                segments += JsonPathSegment.Key(key)
                return index + 1
            }

            val digitStart = index
            while (index < text.length && text[index] in '0'..'9') index++
            if (index == digitStart) {
                throw JsonPathException("方括号内必须是数组下标或双引号键", start)
            }
            val numberText = text.substring(digitStart, index)
            val arrayIndex = numberText.toIntOrNull()
                ?: throw JsonPathException("数组下标超出范围：$numberText", digitStart)
            if (index >= text.length || text[index] != ']') {
                throw JsonPathException("数组下标后缺少 ']'", index)
            }
            segments += JsonPathSegment.Index(arrayIndex)
            return index + 1
        }

        private fun isSimpleKey(key: String): Boolean =
            key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '_' }
    }
}

/**
 * 读取路径对应的节点；路径不存在或类型不匹配时返回 null。
 */
fun JsonNode.readPath(path: JsonPath): JsonNode? {
    var current: JsonNode = this
    for (segment in path.segments) {
        current = when (segment) {
            is JsonPathSegment.Key -> (current as? JsonNode.ObjectNode)?.get(segment.key)
                ?: return null
            is JsonPathSegment.Index -> (current as? JsonNode.ArrayNode)
                ?.elements
                ?.getOrNull(segment.index)
                ?: return null
        }
    }
    return current
}

/**
 * 写入路径，返回新的根节点。
 *
 * - 空路径替换整个根节点；
 * - 对象键存在时替换最后一个同名成员，不存在时追加到末尾；
 * - 数组下标必须已存在（追加/插入使用数组编辑命令）；
 * - 中间路径不存在或类型不匹配时抛出 [JsonPathException]，不会隐式创建对象。
 */
fun JsonNode.writePath(path: JsonPath, value: JsonNode): JsonNode {
    if (path.isEmpty) return value
    return when (val head = path.segments.first()) {
        is JsonPathSegment.Key -> {
            val objectNode = this as? JsonNode.ObjectNode
                ?: throw JsonPathException("路径 ${head.key} 的父节点不是对象")
            val memberIndex = objectNode.lastIndexOf(head.key)
            val tail = path.dropFirst()
            if (tail.isEmpty) {
                if (memberIndex >= 0) {
                    val members = objectNode.members.toMutableList()
                    members[memberIndex] = Member(head.key, value)
                    JsonNode.ObjectNode(members.toList())
                } else {
                    JsonNode.ObjectNode(objectNode.members + Member(head.key, value))
                }
            } else {
                if (memberIndex < 0) throw JsonPathException("路径不存在：$head")
                val updated = objectNode.members[memberIndex].value.writePath(tail, value)
                val members = objectNode.members.toMutableList()
                members[memberIndex] = Member(head.key, updated)
                JsonNode.ObjectNode(members.toList())
            }
        }

        is JsonPathSegment.Index -> {
            val arrayNode = this as? JsonNode.ArrayNode
                ?: throw JsonPathException("路径 [$head] 的父节点不是数组")
            if (head.index !in arrayNode.elements.indices) {
                throw JsonPathException("数组下标越界：${head.index}，长度 ${arrayNode.elements.size}")
            }
            val tail = path.dropFirst()
            val elements = arrayNode.elements.toMutableList()
            elements[head.index] = if (tail.isEmpty) {
                value
            } else {
                arrayNode.elements[head.index].writePath(tail, value)
            }
            JsonNode.ArrayNode(elements.toList())
        }
    }
}

/**
 * 删除路径，返回新的根节点。
 *
 * 删除对象键时移除**所有**同名成员，避免重复键残留；数组删除指定下标。
 */
fun JsonNode.removePath(path: JsonPath): JsonNode {
    if (path.isEmpty) throw JsonPathException("不能删除根节点")
    return when (val head = path.segments.first()) {
        is JsonPathSegment.Key -> {
            val objectNode = this as? JsonNode.ObjectNode
                ?: throw JsonPathException("路径 ${head.key} 的父节点不是对象")
            val tail = path.dropFirst()
            if (tail.isEmpty) {
                if (!objectNode.contains(head.key)) throw JsonPathException("路径不存在：$head")
                JsonNode.ObjectNode(objectNode.members.filterNot { it.name == head.key })
            } else {
                val memberIndex = objectNode.lastIndexOf(head.key)
                if (memberIndex < 0) throw JsonPathException("路径不存在：$head")
                val updated = objectNode.members[memberIndex].value.removePath(tail)
                val members = objectNode.members.toMutableList()
                members[memberIndex] = Member(head.key, updated)
                JsonNode.ObjectNode(members.toList())
            }
        }

        is JsonPathSegment.Index -> {
            val arrayNode = this as? JsonNode.ArrayNode
                ?: throw JsonPathException("路径 [$head] 的父节点不是数组")
            if (head.index !in arrayNode.elements.indices) {
                throw JsonPathException("数组下标越界：${head.index}，长度 ${arrayNode.elements.size}")
            }
            val tail = path.dropFirst()
            val elements = arrayNode.elements.toMutableList()
            if (tail.isEmpty) {
                elements.removeAt(head.index)
            } else {
                elements[head.index] = elements[head.index].removePath(tail)
            }
            JsonNode.ArrayNode(elements.toList())
        }
    }
}

/** 在数组路径末尾追加元素；[path] 必须指向数组。 */
fun JsonNode.appendToArray(path: JsonPath, value: JsonNode): JsonNode {
    val target = readPath(path)
        ?: throw JsonPathException("路径不存在：$path")
    val arrayNode = target as? JsonNode.ArrayNode
        ?: throw JsonPathException("路径不是数组：$path")
    return writePath(path, JsonNode.ArrayNode(arrayNode.elements + value))
}

/** 在数组路径的指定下标插入元素；[path] 必须指向数组。 */
fun JsonNode.insertToArray(path: JsonPath, index: Int, value: JsonNode): JsonNode {
    val target = readPath(path)
        ?: throw JsonPathException("路径不存在：$path")
    val arrayNode = target as? JsonNode.ArrayNode
        ?: throw JsonPathException("路径不是数组：$path")
    if (index !in 0..arrayNode.elements.size) {
        throw JsonPathException("插入下标越界：$index，长度 ${arrayNode.elements.size}")
    }
    val elements = arrayNode.elements.toMutableList()
    elements.add(index, value)
    return writePath(path, JsonNode.ArrayNode(elements.toList()))
}
