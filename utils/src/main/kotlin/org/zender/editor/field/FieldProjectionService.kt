package org.zender.editor.field

import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.edit.WarpFields
import org.zender.editor.schema.DefaultValueResolver
import org.zender.editor.schema.FieldKind
import org.zender.editor.schema.FieldSchema
import org.zender.editor.schema.SchemaRepository
import org.zender.editor.schema.TypeSchema
import org.zender.editor.settings.FavoriteField

/** 默认值字段的过滤范围。 */
enum class DefaultValueFilter {
    /** 不过滤，显示所有字段。 */
    NONE,

    /** 只隐藏等于默认值的顶级字段；子字段全部保留。 */
    TOP_LEVEL,

    /** 所有层级都隐藏等于默认值的字段。 */
    ALL,
}

data class FieldProjectionOptions(
    /** 默认值字段的过滤范围；[DefaultValueFilter.NONE] 表示全部显示。 */
    val defaultFilter: DefaultValueFilter = DefaultValueFilter.NONE,
    /** 是否把 Schema 未声明的字段作为「未知字段」展示。 */
    val includeUnknown: Boolean = true,
    /**
     * 补丁模式：存在 `XxxWarpData` / `XxxWarpType` 配对即说明该引用字段是补丁内容，
     * 即使数据为空也要展示，便于选择引用。
     */
    val patchMode: Boolean = false,
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
            objectRows(root, schema, JsonPath.ROOT, context, issues, topLevel = true)
        }
        return FieldProjectionResult(typeName = typeName, rows = rows, issues = issues)
    }

    private fun objectRows(
        node: JsonNode.ObjectNode,
        schema: TypeSchema,
        basePath: JsonPath,
        context: ProjectionContext,
        issues: MutableList<FieldProjectionIssue>,
        topLevel: Boolean,
    ): List<FieldRow> {
        val rows = mutableListOf<FieldRow>()
        val filterDefaults = when (context.options.defaultFilter) {
            DefaultValueFilter.NONE -> false
            DefaultValueFilter.TOP_LEVEL -> topLevel
            DefaultValueFilter.ALL -> true
        }
        for (field in schema.fields) {
            if (!context.options.includeReadOnly && field.isReadOnly) continue
            val path = basePath.child(field.name)
            val value = node.get(field.name)
            // 框架追加的可选字段只在 JSON 中实际存在时展示。
            if (field.optionalWhenAbsent && value == null) continue
            // 引用字段的容器通常是 `{}` / `[]`，实际值在配对的 `XxxWarpData` 中。
            // 有引用时必须视为非默认，否则关闭“默认值”后字段会连同引用一起消失。
            val isDefault = isDefaultValue(
                node = node,
                field = field,
                value = value,
                patchMode = context.options.patchMode,
            )
            // 收藏字段及其祖先始终保留，即使当前值等于模板默认值。
            if (filterDefaults && isDefault &&
                !context.isFavoriteOrContainsFavorite(path)
            ) {
                continue
            }

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
            // 引用字段的 WarpData / WarpType 由引用编辑器统一处理，不再作为未知字段展示。
            val hiddenWarpNames = schema.fields.asSequence()
                .filter { field ->
                    field.kind == FieldKind.REFERENCE ||
                        (field.kind == FieldKind.LIST && field.elementKind == FieldKind.REFERENCE)
                }
                .flatMap { field ->
                    sequenceOf(
                        WarpFields.dataName(field.name),
                        WarpFields.typeName(field.name),
                    )
                }
                .toSet()
            rows += unknownRows(
                node,
                basePath,
                context,
                schema.fieldNames + hiddenWarpNames,
            )
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
                    objectRows(objectNode, nestedSchema, path, context, issues, topLevel = false)
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
                    objectRows(objectNode, elementSchema, path, context, issues, topLevel = false)
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

    /**
     * 字段是否处于默认状态。
     *
     * 除模板比较外有两条运行时语义：
     * - `DurabilityStat` 未激活（`Active != true`）时游戏会忽略内部数据，整体视为默认值；
     * - 图片/音频字段没有 `WarpData` 引用时视为默认值，字段本体的空容器不代表修改。
     */
    private fun isDefaultValue(
        node: JsonNode.ObjectNode,
        field: FieldSchema,
        value: JsonNode?,
        patchMode: Boolean,
    ): Boolean {
        // 补丁里显式写出 Warp 配对就代表要修改该引用字段，即使引用列表暂为空。
        if (patchMode && isReferenceField(field) && hasWarpPair(node, field)) {
            return false
        }
        if (field.typeName == DURABILITY_STAT_TYPE && value is JsonNode.ObjectNode) {
            val active = (value.get(ACTIVE_FIELD) as? JsonNode.BooleanNode)?.value == true
            if (!active) return true
        }
        if (isResourceReference(field) && !hasWarpReferences(node, field)) {
            return true
        }
        return defaultValueResolver.isDefault(field, value) &&
            !hasWarpReferences(node, field)
    }

    private fun isReferenceField(field: FieldSchema): Boolean =
        field.kind == FieldKind.REFERENCE ||
            (field.kind == FieldKind.LIST && field.elementKind == FieldKind.REFERENCE)

    /** 字段是否显式带有 `XxxWarpData` / `XxxWarpType` 配对键。 */
    private fun hasWarpPair(node: JsonNode.ObjectNode, field: FieldSchema): Boolean =
        node.get(WarpFields.dataName(field.name)) != null ||
            node.get(WarpFields.typeName(field.name)) != null

    private fun isResourceReference(field: FieldSchema): Boolean = when (field.kind) {
        FieldKind.REFERENCE -> field.typeName in RESOURCE_REFERENCE_TYPES

        FieldKind.LIST ->
            field.elementKind == FieldKind.REFERENCE &&
                field.elementTypeName in RESOURCE_REFERENCE_TYPES

        else -> false
    }

    private fun hasWarpReferences(node: JsonNode.ObjectNode, field: FieldSchema): Boolean {
        val data = node.get(WarpFields.dataName(field.name)) ?: return false
        return when {
            field.kind == FieldKind.REFERENCE ->
                (data as? JsonNode.StringNode)?.value?.isNotBlank() == true

            field.kind == FieldKind.LIST && field.elementKind == FieldKind.REFERENCE ->
                (data as? JsonNode.ArrayNode)?.elements?.any {
                    (it as? JsonNode.StringNode)?.value?.isNotBlank() == true
                } == true

            else -> false
        }
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

        /** 当前路径是收藏字段，或收藏字段位于其子路径。 */
        fun isFavoriteOrContainsFavorite(path: JsonPath): Boolean {
            if (favorites.isEmpty()) return false
            val text = path.format()
            return favorites.any { favorite ->
                favorite.type == favoriteType &&
                    (
                        favorite.fieldPath == text ||
                            favorite.fieldPath.startsWith("$text.") ||
                            favorite.fieldPath.startsWith("$text[")
                        )
            }
        }
    }

    private companion object {
        const val DURABILITY_STAT_TYPE = "DurabilityStat"
        const val ACTIVE_FIELD = "Active"

        /** 图片/音频资源引用类型；没有 `WarpData` 时不构成有效修改。 */
        val RESOURCE_REFERENCE_TYPES = setOf("Sprite", "Texture", "Texture2D", "AudioClip")
    }
}
