package org.zender.editor.project

import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.document.JsonPathSegment
import org.zender.editor.pack.DataPack
import org.zender.editor.schema.DefaultValueResolver
import org.zender.editor.schema.FieldKind
import org.zender.editor.schema.FieldSchema
import org.zender.editor.schema.SchemaRepository
import org.zender.editor.schema.TypeSchema

/**
 * E04：Schema 校验。
 *
 * - 对象/补丁中的字段必须存在于 `TypeStruct`（未知字段按扩展字段处理，补丁中视为错误）；
 * - 字段值必须符合推断出的 [FieldKind]；
 * - 枚举值必须出现在 `TypeStruct/Enum` 中；
 * - 写入前可通过 [validateFieldWrite] 检查字段存在、只读与类型。
 *
 * 只读字段在文档中正常存在（如 `LocalizedString.ParentObjectID` 由编辑器维护），
 * 因此文档级校验不报只读；只读限制在写入校验中体现。
 */
class SchemaValidator(private val basePack: DataPack? = null) {

    fun validate(project: ModProject): List<ValidationIssue> {
        val pack = basePack ?: project.basePack ?: return emptyList()
        val schemas = pack.schemaRepository
        val issues = mutableListOf<ValidationIssue>()

        for (entry in project.workingTree.objects.values) {
            if (schemas.schema(entry.type) == null) {
                issues += ValidationIssue(
                    ValidationSeverity.WARNING,
                    "schema.type.missing",
                    "数据包中不存在类型 ${entry.type} 的 TypeStruct，只能按原始 JSON 编辑",
                    entry.relativePath,
                )
                continue
            }
            issues += validateDocument(
                schemas = schemas,
                typeName = entry.type,
                document = entry.document,
                relativePath = entry.relativePath,
                isPatch = false,
            )
        }

        for (patch in project.workingTree.gameSourceModifies.values) {
            val type = gameSourceModifyTargetType(project, pack, patch) ?: continue
            if (schemas.schema(type) == null) continue
            issues += validateDocument(
                schemas = schemas,
                typeName = type,
                document = patch.document,
                relativePath = patch.relativePath,
                isPatch = true,
            )
        }

        for (patch in project.workingTree.dataObjectModifies.values) {
            if (schemas.schema(patch.type) == null) continue
            issues += validateDocument(
                schemas = schemas,
                typeName = patch.type,
                document = patch.document,
                relativePath = patch.relativePath,
                isPatch = true,
            )
        }

        return issues
    }

    /**
     * 校验一份对象或补丁文档。
     *
     * @param isPatch 补丁中的未知字段视为 ERROR（运行时合并会失败/跳过）；
     *   普通对象中的未知字段只告警，并原样保留。
     */
    fun validateDocument(
        schemas: SchemaRepository,
        typeName: String,
        document: JsonDocument,
        relativePath: String,
        isPatch: Boolean,
    ): List<ValidationIssue> {
        val root = document.root as? JsonNode.ObjectNode
            ?: return listOf(
                ValidationIssue(
                    ValidationSeverity.ERROR,
                    "schema.document.invalid",
                    "对象文档顶层必须是 JSON 对象",
                    relativePath,
                ),
            )

        val issues = mutableListOf<ValidationIssue>()
        val walker = SchemaWalker(schemas)
        val visitor = object : SchemaWalker.SchemaVisitor {
            override fun onField(
                typeName: String,
                field: FieldSchema,
                path: JsonPath,
                value: JsonNode?,
            ) {
                if (value == null) return
                val problem = typeProblem(schemas, field, value) ?: return
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "schema.field.type",
                    "字段 ${path.format()} ${problem}",
                    fieldPathText(relativePath, path),
                )
            }

            override fun onUnknownField(
                typeName: String,
                name: String,
                path: JsonPath,
                value: JsonNode,
            ) {
                issues += ValidationIssue(
                    if (isPatch) ValidationSeverity.ERROR else ValidationSeverity.WARNING,
                    "schema.field.unknown",
                    if (isPatch) {
                        "补丁字段 $name 不在 ${typeName} 的 Schema 中，运行时会被忽略或报错"
                    } else {
                        "字段 $name 不在 ${typeName} 的 Schema 中，按扩展字段保留"
                    },
                    fieldPathText(relativePath, path),
                )
            }
        }

        walker.walkObject(typeName, root, JsonPath.ROOT, visitor)

        // 补丁的 $override 内容按同一 Schema 再遍历一次。
        val override = root.get(OVERRIDE_KEY)
        if (override is JsonNode.ObjectNode) {
            walker.walkObject(
                typeName,
                override,
                JsonPath.key(OVERRIDE_KEY),
                visitor,
            )
        }
        return issues
    }

    /**
     * 写入前校验：字段是否存在、是否只读、值是否符合类型。
     *
     * [value] 为 null 时只检查字段与只读标记。路径可以指向嵌套字段、列表元素，
     * 或引用字段的 `WarpData` / `WarpType` 陪伴键。
     *
     * 需要工程自带 `basePack` 时优先使用 [validateFieldWrite] 的 `project` 重载。
     */
    fun validateFieldWrite(
        typeName: String,
        path: JsonPath,
        value: JsonNode? = null,
    ): List<ValidationIssue> {
        val schemas = basePack?.schemaRepository ?: return emptyList()
        return validateFieldWrite(schemas, typeName, path, value)
    }

    /** 写入前校验，数据包取自 [project]（构造时传入的 `basePack` 优先）。 */
    fun validateFieldWrite(
        project: ModProject,
        typeName: String,
        path: JsonPath,
        value: JsonNode? = null,
    ): List<ValidationIssue> {
        val schemas = basePack?.schemaRepository
            ?: project.basePack?.schemaRepository
            ?: return emptyList()
        return validateFieldWrite(schemas, typeName, path, value)
    }

    private fun validateFieldWrite(
        schemas: SchemaRepository,
        typeName: String,
        path: JsonPath,
        value: JsonNode?,
    ): List<ValidationIssue> {
        val target = resolveWriteTarget(schemas, typeName, path)
            ?: return listOf(
                ValidationIssue(
                    ValidationSeverity.ERROR,
                    "schema.field.missing",
                    "字段不存在：${typeName}/${path.format()}",
                    path.format(),
                ),
            )
        val effective = target.effectiveField(schemas)
        if (target.field.isReadOnly) {
            return listOf(
                ValidationIssue(
                    ValidationSeverity.ERROR,
                    "schema.field.readOnly",
                    "字段 ${target.field.name} 只读，应由编辑器自动维护",
                    path.format(),
                ),
            )
        }
        if (value == null || target.isWarpCompanion) return emptyList()
        val problem = typeProblem(schemas, effective, value) ?: return emptyList()
        return listOf(
            ValidationIssue(
                ValidationSeverity.ERROR,
                "schema.field.type",
                "字段 ${path.format()} $problem",
                path.format(),
            ),
        )
    }

    private fun typeProblem(
        schemas: SchemaRepository,
        field: FieldSchema,
        value: JsonNode,
    ): String? = when (field.kind) {
        FieldKind.BOOLEAN ->
            if (value !is JsonNode.BooleanNode) "期望布尔值，实际为 ${describe(value)}" else null

        FieldKind.INTEGER ->
            if (value !is JsonNode.NumberNode || !value.isInteger) {
                "期望整数，实际为 ${describe(value)}"
            } else {
                null
            }

        FieldKind.FLOAT ->
            if (value !is JsonNode.NumberNode) "期望数字，实际为 ${describe(value)}" else null

        FieldKind.STRING ->
            if (value !is JsonNode.StringNode) "期望字符串，实际为 ${describe(value)}" else null

        FieldKind.ENUM -> enumProblem(field.enumValues, value)

        FieldKind.OBJECT ->
            if (value !is JsonNode.ObjectNode) "期望对象，实际为 ${describe(value)}" else null

        FieldKind.LIST -> {
            if (value !is JsonNode.ArrayNode) {
                "期望数组，实际为 ${describe(value)}"
            } else {
                listElementProblem(schemas, field, value)
            }
        }

        FieldKind.REFERENCE ->
            if (value !is JsonNode.ObjectNode && value != JsonNode.NullNode) {
                "引用字段本体期望空对象，实际为 ${describe(value)}"
            } else {
                null
            }

        FieldKind.UNKNOWN -> null
    }

    private fun listElementProblem(
        schemas: SchemaRepository,
        field: FieldSchema,
        array: JsonNode.ArrayNode,
    ): String? {
        val elementKind = field.elementKind ?: return null
        if (elementKind == FieldKind.OBJECT ||
            elementKind == FieldKind.REFERENCE ||
            elementKind == FieldKind.LIST ||
            elementKind == FieldKind.UNKNOWN
        ) {
            return null
        }
        val enumValues = field.elementTypeName
            ?.takeIf { elementKind == FieldKind.ENUM }
            ?.let(schemas::enumValues)
            .orEmpty()
        array.elements.forEachIndexed { index, element ->
            val problem = when (elementKind) {
                FieldKind.BOOLEAN ->
                    if (element !is JsonNode.BooleanNode) "期望布尔值" else null

                FieldKind.INTEGER ->
                    if (element !is JsonNode.NumberNode || !element.isInteger) "期望整数" else null

                FieldKind.FLOAT ->
                    if (element !is JsonNode.NumberNode) "期望数字" else null

                FieldKind.STRING ->
                    if (element !is JsonNode.StringNode) "期望字符串" else null

                FieldKind.ENUM -> enumProblem(enumValues, element)
            } ?: return@forEachIndexed
            return "[$index] $problem，实际为 ${describe(element)}"
        }
        return null
    }

    private fun enumProblem(
        values: List<org.zender.editor.schema.EnumValue>,
        value: JsonNode,
    ): String? {
        if (values.isEmpty()) return null
        val known = values.any { DefaultValueResolver.semanticallyEquals(it.value, value) }
        return if (known) null else "不是合法枚举值（${describe(value)}）"
    }

    private fun resolveWriteTarget(
        schemas: SchemaRepository,
        typeName: String,
        path: JsonPath,
    ): WriteTarget? {
        if (path.isEmpty) return null
        var schema = schemas.schema(typeName) ?: return null
        var target: FieldSchema? = null
        var position = 0

        while (position < path.segments.size) {
            val segment = path.segments[position]
            if (segment !is JsonPathSegment.Key) return null

            val name = normalizeWarpKey(segment.key, schema)
            val field = schema.field(name) ?: return null
            target = field
            position++
            if (position == path.segments.size) {
                return WriteTarget(
                    field = field,
                    element = false,
                    isWarpCompanion = name != segment.key,
                )
            }

            val next = path.segments[position]
            when {
                field.kind == FieldKind.OBJECT && next is JsonPathSegment.Key ->
                    schema = schemas.schema(field.typeName) ?: return null

                field.kind == FieldKind.LIST && next is JsonPathSegment.Index -> {
                    position++
                    if (position == path.segments.size) {
                        return WriteTarget(field, element = true, isWarpCompanion = false)
                    }
                    val elementKind = field.elementKind
                    val elementType = field.elementTypeName
                    if (elementKind != FieldKind.OBJECT || elementType == null) return null
                    schema = schemas.schema(elementType) ?: return null
                }

                else -> return null
            }
        }
        return target?.let { WriteTarget(it, element = false, isWarpCompanion = false) }
    }

    private fun normalizeWarpKey(name: String, schema: TypeSchema): String {
        val base = SchemaWalker.warpBaseName(name) ?: return name
        return if (schema.containsField(base)) base else name
    }

    private fun describe(value: JsonNode): String = when (value) {
        is JsonNode.ObjectNode -> "对象"
        is JsonNode.ArrayNode -> "数组"
        is JsonNode.StringNode -> "字符串"
        is JsonNode.NumberNode -> "数字"
        is JsonNode.BooleanNode -> "布尔值"
        JsonNode.NullNode -> "null"
    }

    private data class WriteTarget(
        val field: FieldSchema,
        val element: Boolean,
        val isWarpCompanion: Boolean,
    ) {
        fun effectiveField(schemas: SchemaRepository): FieldSchema {
            if (!element) return field
            val typeName = field.elementTypeName ?: field.typeName
            return FieldSchema(
                name = field.name,
                typeName = typeName,
                kind = field.elementKind ?: FieldKind.UNKNOWN,
                enumValues = if (field.elementKind == FieldKind.ENUM) {
                    schemas.enumValues(typeName)
                } else {
                    emptyList()
                },
                isReadOnly = field.isReadOnly,
            )
        }
    }

    companion object {
        private const val OVERRIDE_KEY = "\$override"
    }
}
