package org.zender.editor.field

import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.schema.DefaultValueResolver
import org.zender.editor.schema.FieldKind
import org.zender.editor.schema.FieldSchema
import org.zender.editor.schema.SchemaRepository
import org.zender.editor.schema.TypeSchema
import org.zender.editor.settings.FavoriteField

data class FieldProjectionOptions(
    /** 是否显示等于模板默认值的字段；关闭后只保留有实际修改的字段。 */
    val includeDefaults: Boolean = true,
    /** 是否把 Schema 未声明的字段作为「未知字段」展示。 */
    val includeUnknown: Boolean = true,
    val includeReadOnly: Boolean = true,
    /** 收藏字段，按「类型 + 文档根相对路径」匹配。 */
    val favorites: Set<FavoriteField> = emptySet(),
    /** 收藏字段使用的类型名；默认与投影的根类型一致。 */
    val favoriteTypeName: String? = null,
)

data class FieldProjectionIssue(
    val path: JsonPath,
    val message: String,
)

data class FieldProjectionResult(
    val typeName: String,
    val rows: List<FieldRow>,
    val issues: List<FieldProjectionIssue> = emptyList(),
)

/**
 * 把 `JsonDocument + TypeSchema + BaseJson` 投影为字段行快照。
 *
 * 视图层只渲染 [FieldRow]：字段顺序、默认值判定、只读标记、未知字段都已在数据层完成。
 * 同一份文档可以生成不同选项的快照（显示/隐藏默认字段、收藏过滤由视图按需调用）。
 */
class FieldProjectionService(
    private val schemaRepository: SchemaRepository,
    private val defaultValueResolver: DefaultValueResolver = DefaultValueResolver(schemaRepository),
) {

    fun project(
        document: JsonDocument,
        typeName: String,
        options: FieldProjectionOptions = FieldProjectionOptions(),
    ): FieldProjectionResult {
        val root = document.root as? JsonNode.ObjectNode
        if (root == null) {
            return FieldProjectionResult(
                typeName = typeName,
                rows = emptyList(),
                issues = listOf(
                    FieldProjectionIssue(JsonPath.ROOT, "文档根节点必须是对象，无法按类型 $typeName 投影"),
                ),
            )
        }

        val issues = mutableListOf<FieldProjectionIssue>()
        val context = ProjectionContext(
            options = options,
            favorites = options.favorites,
            favoriteType = options.favoriteTypeName ?: typeName,
        )
        val schema = schemaRepository.schema(typeName)
        val rows = if (schema == null) {
            issues += FieldProjectionIssue(
                JsonPath.ROOT,
                "数据包中不存在类型 $typeName 的 TypeStruct，按未知字段展示",
            )
            unknownRows(root, JsonPath.ROOT, context)
        } else {
            objectRows(root, schema, JsonPath.ROOT, context, issues)
        }
        return FieldProjectionResult(typeName = typeName, rows = rows, issues = issues)
    }

    private fun objectRows(
        node: JsonNode.ObjectNode,
        schema: TypeSchema,
        basePath: JsonPath,
        context: ProjectionContext,
        issues: MutableList<FieldProjectionIssue>,
    ): List<FieldRow> {
        val rows = mutableListOf<FieldRow>()
        for (field in schema.fields) {
            if (!context.options.includeReadOnly && field.isReadOnly) continue
            val path = basePath.child(field.name)
            val value = node.get(field.name)
            val isDefault = defaultValueResolver.isDefault(field, value)
            if (!context.options.includeDefaults && isDefault) continue

            rows += FieldRow(
                path = path,
                name = field.name,
                typeName = field.typeName,
                value = value,
                defaultValue = field.templateValue,
                isDefault = isDefault,
                isModified = !isDefault,
                isUnknown = false,
                isReadOnly = field.isReadOnly,
                isFavorite = context.isFavorite(path),
                children = children(field, value, path, context, issues),
            )
        }
        if (context.options.includeUnknown) {
            rows += unknownRows(node, basePath, context, schema.fieldNames)
        }
        return rows
    }

    private fun children(
        field: FieldSchema,
        value: JsonNode?,
        path: JsonPath,
        context: ProjectionContext,
        issues: MutableList<FieldProjectionIssue>,
    ): List<FieldRow> {
        if (value == null) return emptyList()
        return when (field.kind) {
            FieldKind.OBJECT -> {
                val objectNode = value as? JsonNode.ObjectNode
                if (objectNode == null) {
                    issues += FieldProjectionIssue(path, "字段 ${field.name} 声明为对象，但实际值不是对象")
                    return emptyList()
                }
                val nestedSchema = schemaRepository.schema(field.typeName)
                if (nestedSchema == null) {
                    unknownRows(objectNode, path, context)
                } else {
                    objectRows(objectNode, nestedSchema, path, context, issues)
                }
            }

            FieldKind.LIST -> {
                val arrayNode = value as? JsonNode.ArrayNode
                if (arrayNode == null) {
                    issues += FieldProjectionIssue(path, "字段 ${field.name} 声明为列表，但实际值不是数组")
                    return emptyList()
                }
                val templateElements = (field.templateValue as? JsonNode.ArrayNode)?.elements.orEmpty()
                arrayNode.elements.mapIndexed { index, element ->
                    val elementPath = path.child(index)
                    val templateElement = templateElements.getOrNull(index)
                    val isDefault = templateElement != null &&
                        DefaultValueResolver.semanticallyEquals(templateElement, element)
                    FieldRow(
                        path = elementPath,
                        name = "[$index]",
                        typeName = field.elementTypeName,
                        value = element,
                        defaultValue = templateElement,
                        isDefault = isDefault,
                        isModified = !isDefault,
                        isUnknown = false,
                        isReadOnly = field.isReadOnly,
                        isFavorite = context.isFavorite(elementPath),
                        children = elementChildren(
                            field = field,
                            element = element,
                            path = elementPath,
                            context = context,
                            issues = issues,
                        ),
                    )
                }
            }

            // 声明类型未在数据包中注册时仍提供可浏览的子树（例如插件自定义结构）。
            FieldKind.UNKNOWN -> unknownValueRows(value, path, context)

            else -> emptyList()
        }
    }

    private fun elementChildren(
        field: FieldSchema,
        element: JsonNode,
        path: JsonPath,
        context: ProjectionContext,
        issues: MutableList<FieldProjectionIssue>,
    ): List<FieldRow> = when (field.elementKind) {
        FieldKind.OBJECT -> {
            val objectNode = element as? JsonNode.ObjectNode
            if (objectNode == null) {
                issues += FieldProjectionIssue(path, "列表 ${field.name} 的元素声明为对象，但实际值不是对象")
                emptyList()
            } else {
                val elementSchema = field.elementTypeName?.let(schemaRepository::schema)
                if (elementSchema == null) {
                    unknownRows(objectNode, path, context)
                } else {
                    objectRows(objectNode, elementSchema, path, context, issues)
                }
            }
        }

        FieldKind.LIST -> {
            val arrayNode = element as? JsonNode.ArrayNode
            if (arrayNode == null) {
                emptyList()
            } else {
                arrayNode.elements.mapIndexed { index, nested ->
                    val nestedPath = path.child(index)
                    FieldRow(
                        path = nestedPath,
                        name = "[$index]",
                        typeName = field.elementTypeName,
                        value = nested,
                        isUnknown = false,
                        isFavorite = context.isFavorite(nestedPath),
                        children = elementChildren(field, nested, nestedPath, context, issues),
                    )
                }
            }
        }

        FieldKind.UNKNOWN -> unknownValueRows(element, path, context)

        else -> emptyList()
    }

    private fun unknownRows(
        node: JsonNode.ObjectNode,
        basePath: JsonPath,
        context: ProjectionContext,
        knownNames: Set<String> = emptySet(),
    ): List<FieldRow> {
        val seen = HashSet<String>()
        val rows = mutableListOf<FieldRow>()
        for (member in node.members) {
            if (member.name in knownNames || !seen.add(member.name)) continue
            val value = node.get(member.name)
            val path = basePath.child(member.name)
            rows += FieldRow(
                path = path,
                name = member.name,
                typeName = null,
                value = value,
                isDefault = false,
                isModified = true,
                isUnknown = true,
                isFavorite = context.isFavorite(path),
                children = unknownChildren(value, path, context),
            )
        }
        return rows
    }

    private fun unknownChildren(
        value: JsonNode?,
        path: JsonPath,
        context: ProjectionContext,
    ): List<FieldRow> = unknownValueRows(value, path, context)

    private fun unknownValueRows(
        value: JsonNode?,
        path: JsonPath,
        context: ProjectionContext,
    ): List<FieldRow> = when (value) {
        is JsonNode.ObjectNode -> unknownRows(value, path, context)
        is JsonNode.ArrayNode -> value.elements.mapIndexed { index, element ->
            val elementPath = path.child(index)
            FieldRow(
                path = elementPath,
                name = "[$index]",
                value = element,
                isModified = true,
                isUnknown = true,
                isFavorite = context.isFavorite(elementPath),
                children = unknownChildren(element, elementPath, context),
            )
        }

        else -> emptyList()
    }

    private data class ProjectionContext(
        val options: FieldProjectionOptions,
        val favorites: Set<FavoriteField>,
        val favoriteType: String,
    ) {
        fun isFavorite(path: JsonPath): Boolean {
            if (favorites.isEmpty()) return false
            val text = path.format()
            return favorites.any { it.type == favoriteType && it.fieldPath == text }
        }
    }
}
