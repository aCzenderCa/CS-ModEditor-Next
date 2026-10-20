package org.zender.editor.schema

import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode

/**
 * `TypeStruct/<Type>.json` 的一项：字段名 → 声明类型名。
 */
data class FieldDefinition(
    val name: String,
    val typeName: String,
)

data class TypeStructDefinition(
    val typeName: String,
    val fields: List<FieldDefinition>,
) {
    init {
        require(typeName.isNotBlank()) { "类型名不能为空" }
    }

    private val fieldsByName: Map<String, FieldDefinition> =
        fields.associateByTo(LinkedHashMap()) { it.name }

    fun field(name: String): FieldDefinition? = fieldsByName[name]

    val fieldNames: Set<String>
        get() = fieldsByName.keys
}

data class TypeStructParseResult(
    val definition: TypeStructDefinition? = null,
    val issues: List<String> = emptyList(),
) {
    val isSuccess: Boolean get() = definition != null
}

/**
 * 读取 `{"Fields":{"字段":"类型"}}`。
 *
 * 字段顺序按文件顺序保留（JSON 对象在数据包中是手写/生成的，顺序稳定）。
 */
object TypeStructReader {

    fun parse(typeName: String, text: String): TypeStructParseResult {
        val issues = mutableListOf<String>()
        val parsed = JsonDocument.parse(text)
        val document = parsed.document
            ?: return TypeStructParseResult(
                issues = listOf("TypeStruct 不是合法 JSON：${parsed.error?.message}"),
            )
        val root = document.root as? JsonNode.ObjectNode
            ?: return TypeStructParseResult(issues = listOf("TypeStruct 顶层必须是对象"))
        parsed.issues.forEach { issues += it.message }

        val fieldsNode = root.get("Fields")
        if (fieldsNode == null) {
            issues += "缺少 Fields 对象，按空结构处理"
            return TypeStructParseResult(TypeStructDefinition(typeName, emptyList()), issues)
        }
        val fieldsObject = fieldsNode as? JsonNode.ObjectNode
            ?: return TypeStructParseResult(
                issues = listOf("Fields 必须是对象"),
            )

        fieldsObject.duplicateKeys().forEach { issues += "字段重复：$it" }

        val fields = mutableListOf<FieldDefinition>()
        val indexByName = LinkedHashMap<String, Int>()
        for (member in fieldsObject.members) {
            val value = member.value as? JsonNode.StringNode
            if (value == null) {
                issues += "字段 ${member.name} 的类型必须是字符串"
                continue
            }
            if (value.value.isBlank()) {
                issues += "字段 ${member.name} 的类型不能为空"
                continue
            }
            val existing = indexByName[member.name]
            val definition = FieldDefinition(member.name, value.value)
            if (existing == null) {
                indexByName[member.name] = fields.size
                fields += definition
            } else {
                // 重复键访问最后一个，同时保留首次出现的位置。
                fields[existing] = definition
            }
        }
        return TypeStructParseResult(TypeStructDefinition(typeName, fields.toList()), issues)
    }
}
