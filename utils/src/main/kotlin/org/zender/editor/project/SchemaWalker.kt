package org.zender.editor.project

import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.schema.FieldKind
import org.zender.editor.schema.FieldSchema
import org.zender.editor.schema.SchemaRepository

/**
 * 按 Schema 遍历一个对象文档，供 Schema / 引用 / 框架 / 本地化校验复用。
 *
 * 访问顺序：
 * 1. [SchemaVisitor.onField]：Schema 中声明的每个字段（缺失字段的 value 为 null）；
 * 2. 嵌套对象与对象数组继续递归；
 * 3. 引用字段额外回调 [SchemaVisitor.onReference]（附带同级的 WarpData/WarpType）；
 * 4. JSON 中存在但 Schema 未声明的成员回调 [SchemaVisitor.onUnknownField]。
 *
 * 补丁文档中的 `$override`、`$matchTargets`、`MatchTagWarpData` 等特殊键不会作为未知字段上报；
 * 需要校验 `$override` 内容时，调用方对它的值再次调用 [walkObject]。
 */
internal class SchemaWalker(private val schemas: SchemaRepository) {

    interface SchemaVisitor {
        /** Schema 字段回调；[value] 为 null 表示文档中缺失该字段。 */
        fun onField(
            typeName: String,
            field: FieldSchema,
            path: JsonPath,
            value: JsonNode?,
        ) {
        }

        /**
         * 引用字段回调。字段本体在 [owner] 中，配对键通过
         * [org.zender.editor.edit.WarpFields] 计算。
         */
        fun onReference(
            typeName: String,
            field: FieldSchema,
            path: JsonPath,
            owner: JsonNode.ObjectNode,
        ) {
        }

        /** 文档中存在但 Schema 未声明的成员。 */
        fun onUnknownField(
            typeName: String,
            name: String,
            path: JsonPath,
            value: JsonNode,
        ) {
        }
    }

    fun walk(typeName: String, document: JsonDocument, visitor: SchemaVisitor) {
        val root = document.root as? JsonNode.ObjectNode ?: return
        walkObject(typeName, root, JsonPath.ROOT, visitor)
    }

    fun walkObject(
        typeName: String,
        node: JsonNode.ObjectNode,
        basePath: JsonPath,
        visitor: SchemaVisitor,
        depth: Int = 0,
    ) {
        if (depth > MAX_DEPTH) return
        val schema = schemas.schema(typeName) ?: return

        for (field in schema.fields) {
            val path = basePath.child(field.name)
            val value = node.get(field.name)
            visitor.onField(typeName, field, path, value)

            when (field.kind) {
                FieldKind.OBJECT -> {
                    if (value is JsonNode.ObjectNode) {
                        walkObject(field.typeName, value, path, visitor, depth + 1)
                    }
                }

                FieldKind.LIST -> {
                    if (field.elementKind == FieldKind.REFERENCE) {
                        // 补丁可能只写 WarpData/WarpType、省略数组本体。
                        visitor.onReference(typeName, field, path, node)
                        continue
                    }
                    val array = value as? JsonNode.ArrayNode ?: continue
                    if (field.elementKind == FieldKind.OBJECT) {
                        val elementType = field.elementTypeName ?: continue
                        array.elements.forEachIndexed { index, element ->
                            if (element is JsonNode.ObjectNode) {
                                walkObject(
                                    elementType,
                                    element,
                                    path.child(index),
                                    visitor,
                                    depth + 1,
                                )
                            }
                        }
                    }
                }

                FieldKind.REFERENCE ->
                    visitor.onReference(typeName, field, path, node)

                else -> Unit
            }
        }

        reportUnknownFields(typeName, node, basePath, schema.fieldNames, visitor)
    }

    private fun reportUnknownFields(
        typeName: String,
        node: JsonNode.ObjectNode,
        basePath: JsonPath,
        knownNames: Set<String>,
        visitor: SchemaVisitor,
    ) {
        val seen = HashSet<String>()
        for (member in node.members) {
            if (!seen.add(member.name)) continue
            if (member.name in knownNames) continue
            if (isWarpCompanionOfKnown(member.name, knownNames)) continue
            if (member.name in SPECIAL_KEYS) continue
            visitor.onUnknownField(
                typeName,
                member.name,
                basePath.child(member.name),
                member.value,
            )
        }
    }

    private fun isWarpCompanionOfKnown(name: String, knownNames: Set<String>): Boolean {
        val base = warpBaseName(name) ?: return false
        return base in knownNames
    }

    companion object {
        private const val MAX_DEPTH = 256

        /** 补丁/框架特殊键，不属于对象字段。 */
        val SPECIAL_KEYS: Set<String> = setOf(
            "\$override",
            "\$matchTargets",
            "MatchTagWarpData",
            "MatchTypeWarpData",
            "额外数据ExtraData",
            "ExtraData",
        )

        fun warpBaseName(name: String): String? {
            val dataSuffix = "WarpData"
            val typeSuffix = "WarpType"
            return when {
                name.length > dataSuffix.length && name.endsWith(dataSuffix) ->
                    name.removeSuffix(dataSuffix)

                name.length > typeSuffix.length && name.endsWith(typeSuffix) ->
                    name.removeSuffix(typeSuffix)

                else -> null
            }
        }
    }
}
