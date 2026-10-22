package org.zender.editor.schema

import org.zender.editor.document.JsonNode

/**
 * 字段种类。由 `TypeStruct`（声明类型）+ `BaseJson`（模板值/数组形状）+
 * `TypeStruct/Enum`（枚举）共同推断，不能只看声明类型名。
 */
enum class FieldKind {
    BOOLEAN,
    INTEGER,
    FLOAT,
    STRING,
    ENUM,
    OBJECT,
    LIST,
    REFERENCE,
    UNKNOWN;

    val isPrimitive: Boolean
        get() = this == BOOLEAN || this == INTEGER || this == FLOAT || this == STRING
}

/**
 * 枚举取值。值保留原始 JSON 节点，兼容数字与字符串形式的枚举。
 */
data class EnumValue(
    val name: String,
    val value: JsonNode,
)

data class EnumDefinition(
    val name: String,
    val values: List<EnumValue>,
) {
    fun nameOf(value: JsonNode): String? =
        values.firstOrNull { it.value == value }?.name
}

/**
 * `Doc` / `Doc-En` 中一个字段的展示信息。
 */
data class FieldNotes(
    val alias: String? = null,
    val note: String? = null,
    val comment: String? = null,
) {
    val isEmpty: Boolean
        get() = alias == null && note == null && comment == null
}

data class FieldDocumentation(
    val chinese: FieldNotes? = null,
    val english: FieldNotes? = null,
) {
    val isEmpty: Boolean
        get() = (chinese == null || chinese.isEmpty) && (english == null || english.isEmpty)
}

/**
 * 一个字段的 Schema。
 *
 * [templateValue] 为 `BaseJson` 中的模板值；模板中没有该字段时为 null。
 */
data class FieldSchema(
    val name: String,
    val typeName: String,
    val kind: FieldKind,
    /** 列表的元素类型名；非列表为 null。 */
    val elementTypeName: String? = null,
    /** 列表的元素种类；非列表为 null。 */
    val elementKind: FieldKind? = null,
    val enumValues: List<EnumValue> = emptyList(),
    val isReadOnly: Boolean = false,
    val templateValue: JsonNode? = null,
    val documentation: FieldDocumentation = FieldDocumentation(),
    /** 仅当 JSON 中实际存在该键时展示；用于 ModLoader 等框架追加的可选字段。 */
    val optionalWhenAbsent: Boolean = false,
) {
    val isList: Boolean get() = kind == FieldKind.LIST

    /** 展示名称：Doc 中的 Alias 优先，否则使用字段名。 */
    fun displayName(useEnglish: Boolean = false): String {
        val notes = if (useEnglish) documentation.english else documentation.chinese
        return notes?.alias?.takeIf { it.isNotBlank() } ?: name
    }

    /** 中文说明，回退到英文说明。 */
    fun note(useEnglish: Boolean = false): String? {
        val preferred = if (useEnglish) documentation.english else documentation.chinese
        val fallback = if (useEnglish) documentation.chinese else documentation.english
        return preferred?.note?.takeIf { it.isNotBlank() }
            ?: fallback?.note?.takeIf { it.isNotBlank() }
    }
}

/**
 * 一个类型的完整 Schema。
 */
data class TypeSchema(
    val typeName: String,
    val fields: List<FieldSchema>,
    val template: JsonNode.ObjectNode? = null,
) {
    private val fieldsByName: Map<String, FieldSchema> =
        fields.associateByTo(LinkedHashMap()) { it.name }

    fun field(name: String): FieldSchema? = fieldsByName[name]

    fun containsField(name: String): Boolean = fieldsByName.containsKey(name)

    val fieldNames: Set<String>
        get() = fieldsByName.keys
}
