package org.zender.editor.field

import org.zender.editor.document.JsonPath
import org.zender.editor.document.JsonPathSegment
import org.zender.editor.schema.EnumValue
import org.zender.editor.schema.FieldKind
import org.zender.editor.schema.FieldSchema
import org.zender.editor.schema.SchemaRepository

/**
 * 字段路径对应的 Schema 信息。
 *
 * [schemaField] 是路径最后一段所属的字段（列表元素时仍是列表字段本身）；
 * [kind] / [typeName] / [enumValues] 已按列表元素展开，供字段视图选择控件。
 */
data class FieldEditorInfo(
    val schemaField: FieldSchema,
    val kind: FieldKind,
    val typeName: String?,
    val enumValues: List<EnumValue> = emptyList(),
    val isReadOnly: Boolean,
    /** 路径指向列表元素时为 true。 */
    val isElement: Boolean = false,
) {
    val isReference: Boolean get() = kind == FieldKind.REFERENCE

    val isReferenceList: Boolean get() = kind == FieldKind.LIST && schemaField.elementKind == FieldKind.REFERENCE
}

/**
 * 按「根类型 + 文档相对路径」定位字段 Schema。
 *
 * 字段视图只拿到 [FieldRow]（路径、展示值），具体控件必须依赖完整 [FieldSchema]
 * （枚举、元素种类、模板默认值）。视图层不得自行遍历 Schema，统一调用本对象。
 */
object FieldSchemaLocator {

    fun resolve(
        schemas: SchemaRepository,
        rootType: String,
        path: JsonPath,
    ): FieldEditorInfo? {
        if (path.isEmpty) return null
        var schema = schemas.schema(rootType) ?: return null
        var position = 0
        while (position < path.segments.size) {
            val segment = path.segments[position]
            if (segment !is JsonPathSegment.Key) return null
            val field = schema.field(segment.key) ?: return null
            position++
            if (position == path.segments.size) {
                return fieldInfo(schemas, field, isElement = false)
            }

            val next = path.segments[position]
            when (field.kind) {
                FieldKind.OBJECT -> {
                    if (next !is JsonPathSegment.Key) return null
                    schema = schemas.schema(field.typeName) ?: return null
                }

                FieldKind.LIST -> {
                    if (next !is JsonPathSegment.Index) return null
                    position++
                    if (position == path.segments.size) {
                        return fieldInfo(schemas, field, isElement = true)
                    }
                    if (field.elementKind != FieldKind.OBJECT) return null
                    val elementType = field.elementTypeName ?: return null
                    schema = schemas.schema(elementType) ?: return null
                    if (path.segments[position] !is JsonPathSegment.Key) return null
                }

                else -> return null
            }
        }
        return null
    }

    private fun fieldInfo(
        schemas: SchemaRepository,
        field: FieldSchema,
        isElement: Boolean,
    ): FieldEditorInfo {
        if (!isElement) {
            return FieldEditorInfo(
                schemaField = field,
                kind = field.kind,
                typeName = field.typeName,
                enumValues = if (field.kind == FieldKind.ENUM) {
                    schemas.enumValues(field.typeName)
                } else {
                    emptyList()
                },
                isReadOnly = field.isReadOnly,
                isElement = false,
            )
        }
        val kind = field.elementKind ?: FieldKind.UNKNOWN
        val typeName = field.elementTypeName
        return FieldEditorInfo(
            schemaField = field,
            kind = kind,
            typeName = typeName,
            enumValues = if (kind == FieldKind.ENUM && typeName != null) {
                schemas.enumValues(typeName)
            } else {
                emptyList()
            },
            isReadOnly = field.isReadOnly,
            isElement = true,
        )
    }
}
