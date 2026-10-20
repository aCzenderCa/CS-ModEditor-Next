package org.zender.editor.schema

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.io.FileStore
import java.nio.file.Path

/**
 * Schema 读取选项。
 *
 * [readOnlyFields] 按「类型 → 字段名」标记只读字段。默认只读 `LocalizedString` 中
 * 由编辑器自动维护的 `ParentObjectID` / `LocalizationKey`；这些值不应由用户手填。
 */
data class SchemaLoadOptions(
    val readOnlyFields: Map<String, Set<String>> = DEFAULT_READ_ONLY_FIELDS,
) {
    companion object {
        val DEFAULT_READ_ONLY_FIELDS: Map<String, Set<String>> = mapOf(
            "LocalizedString" to setOf("ParentObjectID", "LocalizationKey"),
        )
    }
}

data class SchemaLoadResult(
    val repository: SchemaRepository,
    val diagnostics: List<Diagnostic> = emptyList(),
)

/**
 * 数据包 Schema 仓库。
 *
 * 完整字段结构 = `TypeStruct`（字段名与声明类型）+ `BaseJson`（模板值/数组形状）+
 * `Doc`/`Doc-En`（展示信息）+ `TypeStruct/Enum`（枚举取值）。
 */
class SchemaRepository private constructor(
    private val typeStructs: Map<String, TypeStructDefinition>,
    private val templates: Map<String, JsonNode.ObjectNode>,
    private val docs: Map<String, TypeDocNotes>,
    private val docsEn: Map<String, TypeDocNotes>,
    private val enums: Map<String, EnumDefinition>,
    private val referenceTypes: Set<String>,
    private val options: SchemaLoadOptions,
) {

    val typeNames: Set<String> = typeStructs.keys

    val enumNames: Set<String> = enums.keys

    val schemas: Map<String, TypeSchema> = typeStructs.keys.associateWithTo(LinkedHashMap()) { buildSchema(it) }

    fun typeStruct(typeName: String): TypeStructDefinition? = typeStructs[typeName]

    fun template(typeName: String): JsonNode.ObjectNode? = templates[typeName]

    fun schema(typeName: String): TypeSchema? = schemas[typeName]

    fun hasType(typeName: String): Boolean = typeStructs.containsKey(typeName)

    fun isEnumType(typeName: String): Boolean = enums.containsKey(typeName)

    fun enumDefinition(typeName: String): EnumDefinition? = enums[typeName]

    fun enumValues(typeName: String): List<EnumValue> = enums[typeName]?.values ?: emptyList()

    fun isReferenceType(typeName: String): Boolean = referenceTypes.contains(typeName)

    fun typeDoc(typeName: String): TypeDocNotes? = docs[typeName]

    fun typeDocEn(typeName: String): TypeDocNotes? = docsEn[typeName]

    private fun buildSchema(typeName: String): TypeSchema {
        val typeStruct = typeStructs.getValue(typeName)
        val template = templates[typeName]
        val readOnly = options.readOnlyFields[typeName].orEmpty()
        val chineseDoc = docs[typeName]
        val englishDoc = docsEn[typeName]

        val fields = typeStruct.fields.map { definition ->
            val templateValue = template?.get(definition.name)
            val inferred = infer(definition.typeName, templateValue)
            val documentation = FieldDocumentation(
                chinese = chineseDoc?.field(definition.name),
                english = englishDoc?.field(definition.name),
            )
            FieldSchema(
                name = definition.name,
                typeName = definition.typeName,
                kind = inferred.kind,
                elementTypeName = inferred.elementTypeName,
                elementKind = inferred.elementKind,
                enumValues = if (inferred.kind == FieldKind.ENUM) {
                    enums[definition.typeName]?.values.orEmpty()
                } else {
                    emptyList()
                },
                isReadOnly = definition.name in readOnly,
                templateValue = templateValue,
                documentation = documentation,
            )
        }.toMutableList()

        // CSFF 早期版本使用 DefaultEnvCards，后续改为 OldDefaultEnvCards + DefaultEnvCardDrops。
        // 新 Schema 没有旧字段时补一个兼容字段，让旧工程/旧数据包仍能按引用列表编辑。
        if (typeName == "CardData" && fields.none { it.name == "DefaultEnvCards" }) {
            val oldEnvCards = fields.firstOrNull { it.name == "OldDefaultEnvCards" }
            if (oldEnvCards != null) {
                val offset = fields.indexOf(oldEnvCards) + 1
                fields.add(
                    offset,
                    FieldSchema(
                        name = "DefaultEnvCards",
                        typeName = oldEnvCards.typeName,
                        kind = FieldKind.LIST,
                        elementTypeName = oldEnvCards.elementTypeName ?: oldEnvCards.typeName,
                        elementKind = FieldKind.REFERENCE,
                        templateValue = JsonNode.ArrayNode(emptyList()),
                        documentation = FieldDocumentation(
                            chinese = FieldNotes(
                                alias = "环境卡牌（旧字段）",
                                note = "CSFF 早期版本的 DefaultEnvCards；引用存放在 DefaultEnvCardsWarpData。",
                            ),
                            english = FieldNotes(
                                alias = "Env Cards (Legacy)",
                                note = "Legacy DefaultEnvCards field; references live in DefaultEnvCardsWarpData.",
                            ),
                        ),
                    ),
                )
            }
        }
        return TypeSchema(typeName = typeName, fields = fields, template = template)
    }

    private fun infer(typeName: String, sample: JsonNode?): InferredFieldType {
        if (sample is JsonNode.ArrayNode) {
            val element = infer(typeName, sample.elements.firstOrNull())
            return InferredFieldType(
                kind = FieldKind.LIST,
                elementTypeName = typeName,
                elementKind = element.kind,
            )
        }
        if (typeName in PRIMITIVE_KINDS) {
            return InferredFieldType(PRIMITIVE_KINDS.getValue(typeName))
        }
        if (typeName in referenceTypes) {
            return InferredFieldType(FieldKind.REFERENCE)
        }
        if (typeName in enums) {
            return InferredFieldType(FieldKind.ENUM)
        }
        if (typeName in typeStructs || typeName in templates) {
            return InferredFieldType(FieldKind.OBJECT)
        }
        // 声明类型未知时按模板值兜底，避免把可编辑字段标成不可编辑。
        return when (sample) {
            is JsonNode.BooleanNode -> InferredFieldType(FieldKind.BOOLEAN)
            is JsonNode.NumberNode ->
                InferredFieldType(if (sample.isInteger) FieldKind.INTEGER else FieldKind.FLOAT)

            is JsonNode.StringNode -> InferredFieldType(FieldKind.STRING)
            is JsonNode.ObjectNode -> InferredFieldType(FieldKind.OBJECT)
            else -> InferredFieldType(FieldKind.UNKNOWN)
        }
    }

    companion object {
        private val PRIMITIVE_KINDS: Map<String, FieldKind> = mapOf(
            "Boolean" to FieldKind.BOOLEAN,
            "Int32" to FieldKind.INTEGER,
            "Int64" to FieldKind.INTEGER,
            "Int16" to FieldKind.INTEGER,
            "UInt32" to FieldKind.INTEGER,
            "UInt64" to FieldKind.INTEGER,
            "Byte" to FieldKind.INTEGER,
            "SByte" to FieldKind.INTEGER,
            "Long" to FieldKind.INTEGER,
            "Short" to FieldKind.INTEGER,
            "Single" to FieldKind.FLOAT,
            "Double" to FieldKind.FLOAT,
            "Decimal" to FieldKind.FLOAT,
            "Float" to FieldKind.FLOAT,
            "String" to FieldKind.STRING,
            "Char" to FieldKind.STRING,
        )

        val EMPTY: SchemaRepository = SchemaRepository(
            typeStructs = emptyMap(),
            templates = emptyMap(),
            docs = emptyMap(),
            docsEn = emptyMap(),
            enums = emptyMap(),
            referenceTypes = emptySet(),
            options = SchemaLoadOptions(),
        )

        /**
         * 从数据包目录读取全部 Schema。
         *
         * 单个文件失败只产生 [Diagnostic]，不会中断整个数据包加载。
         */
        fun load(
            fileStore: FileStore,
            packRoot: Path,
            referenceTypes: Set<String>,
            options: SchemaLoadOptions = SchemaLoadOptions(),
        ): SchemaLoadResult {
            val diagnostics = mutableListOf<Diagnostic>()
            fun warn(message: String, path: String? = null) {
                diagnostics += Diagnostic(DiagnosticSeverity.WARNING, message, path)
            }
            fun error(message: String, path: String? = null) {
                diagnostics += Diagnostic(DiagnosticSeverity.ERROR, message, path)
            }

            val typeStructs = LinkedHashMap<String, TypeStructDefinition>()
            val templates = LinkedHashMap<String, JsonNode.ObjectNode>()
            val enums = LinkedHashMap<String, EnumDefinition>()

            val typeStructDir = packRoot.resolve("TypeStruct")
            val typeStructFiles = fileStore.list(typeStructDir)
                .filter { fileStore.isRegularFile(it) && isJson(it) }
            for (file in typeStructFiles) {
                val typeName = stem(file)
                try {
                    val parsed = TypeStructReader.parse(typeName, fileStore.readText(file))
                    parsed.issues.forEach { warn(it, relative(packRoot, file)) }
                    val definition = parsed.definition
                    if (definition == null) {
                        error("TypeStruct 无法解析", relative(packRoot, file))
                    } else {
                        typeStructs[typeName] = definition
                    }
                } catch (e: Exception) {
                    error("读取 TypeStruct 失败：${e.message}", relative(packRoot, file))
                }
            }

            for (subdirectory in fileStore.list(typeStructDir)) {
                if (!fileStore.isDirectory(subdirectory)) continue
                if (!subdirectory.fileName.toString().equals("Enum", ignoreCase = true)) continue
                for (file in fileStore.list(subdirectory)) {
                    if (!fileStore.isRegularFile(file) || !isJson(file)) continue
                    val enumName = stem(file)
                    try {
                        val parsed = EnumReader.parse(enumName, fileStore.readText(file))
                        parsed.issues.forEach { warn(it, relative(packRoot, file)) }
                        val definition = parsed.definition
                        if (definition == null) {
                            error("枚举文件无法解析", relative(packRoot, file))
                        } else {
                            enums[enumName] = definition
                        }
                    } catch (e: Exception) {
                        error("读取枚举失败：${e.message}", relative(packRoot, file))
                    }
                }
            }

            val baseJsonDir = packRoot.resolve("BaseJson")
            for (file in fileStore.list(baseJsonDir)) {
                if (!fileStore.isRegularFile(file) || !isJson(file)) continue
                val typeName = stem(file)
                try {
                    val parsed = JsonDocument.parse(fileStore.readText(file))
                    parsed.issues.forEach { warn(it.message, relative(packRoot, file)) }
                    val document = parsed.document
                    if (document == null) {
                        error("BaseJson 不是合法 JSON：${parsed.error?.message}", relative(packRoot, file))
                        continue
                    }
                    val root = document.root as? JsonNode.ObjectNode
                    if (root == null) {
                        error("BaseJson 顶层必须是对象", relative(packRoot, file))
                        continue
                    }
                    templates[typeName] = root
                } catch (e: Exception) {
                    error("读取 BaseJson 失败：${e.message}", relative(packRoot, file))
                }
            }

            val docs = readDocs(fileStore, packRoot, "Doc", ::warn, ::error)
            val docsEn = readDocs(fileStore, packRoot, "Doc-En", ::warn, ::error)

            return SchemaLoadResult(
                repository = SchemaRepository(
                    typeStructs = typeStructs,
                    templates = templates,
                    docs = docs,
                    docsEn = docsEn,
                    enums = enums,
                    referenceTypes = referenceTypes,
                    options = options,
                ),
                diagnostics = diagnostics,
            )
        }

        private fun readDocs(
            fileStore: FileStore,
            packRoot: Path,
            directoryName: String,
            warn: (String, String?) -> Unit,
            error: (String, String?) -> Unit,
        ): Map<String, TypeDocNotes> {
            val directory = packRoot.resolve(directoryName)
            val result = LinkedHashMap<String, TypeDocNotes>()
            for (file in fileStore.list(directory)) {
                if (!fileStore.isRegularFile(file)) continue
                val lowerName = file.fileName.toString().lowercase()
                if (!lowerName.endsWith(".yml") && !lowerName.endsWith(".yaml")) continue
                val typeName = stem(file)
                try {
                    val parsed = DocParser.parse(typeName, fileStore.readText(file))
                    parsed.issues.forEach { warn(it, relative(packRoot, file)) }
                    result[typeName] = parsed.notes
                } catch (e: Exception) {
                    error("读取 Doc 失败：${e.message}", relative(packRoot, file))
                }
            }
            return result
        }

        private fun isJson(path: Path): Boolean =
            path.fileName.toString().endsWith(".json", ignoreCase = true)

        private fun stem(path: Path): String {
            val name = path.fileName.toString()
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(0, dot) else name
        }

        private fun relative(root: Path, path: Path): String =
            try {
                root.relativize(path).toString().replace('\\', '/')
            } catch (_: IllegalArgumentException) {
                path.toString()
            }
    }
}

private data class InferredFieldType(
    val kind: FieldKind,
    val elementTypeName: String? = null,
    val elementKind: FieldKind? = null,
)
