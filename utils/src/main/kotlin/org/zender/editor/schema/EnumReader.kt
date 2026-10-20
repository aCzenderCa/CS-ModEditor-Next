package org.zender.editor.schema

import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode

/**
 * 读取 `TypeStruct/Enum/<EnumName>.json`：
 *
 * ```json
 * { "Item": 0, "Base": 1, "Location": 2 }
 * ```
 *
 * 枚举值保留原始 JSON 节点（数字或字符串），顺序即文件顺序。
 */
object EnumReader {

    fun parse(enumName: String, text: String): EnumParseResult {
        val issues = mutableListOf<String>()
        val parsed = JsonDocument.parse(text)
        val document = parsed.document
            ?: return EnumParseResult(
                definition = null,
                issues = listOf("枚举文件不是合法 JSON：${parsed.error?.message}"),
            )
        parsed.issues.forEach { issues += it.message }
        val root = document.root as? JsonNode.ObjectNode
            ?: return EnumParseResult(definition = null, issues = issues + "枚举顶层必须是对象")

        root.duplicateKeys().forEach { issues += "枚举项重复：$it" }

        val values = mutableListOf<EnumValue>()
        val indexByName = LinkedHashMap<String, Int>()
        for (member in root.members) {
            if (member.value is JsonNode.ObjectNode || member.value is JsonNode.ArrayNode) {
                issues += "枚举项 ${member.name} 的值必须是基础类型"
                continue
            }
            if (member.name.isBlank()) {
                issues += "枚举项名称不能为空"
                continue
            }
            val definition = EnumValue(member.name, member.value)
            val existing = indexByName[member.name]
            if (existing == null) {
                indexByName[member.name] = values.size
                values += definition
            } else {
                values[existing] = definition
            }
        }
        return EnumParseResult(
            definition = EnumDefinition(enumName, values.toList()),
            issues = issues,
        )
    }
}

data class EnumParseResult(
    val definition: EnumDefinition? = null,
    val issues: List<String> = emptyList(),
) {
    val isSuccess: Boolean get() = definition != null
}
