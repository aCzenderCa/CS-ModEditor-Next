package org.zender.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.zender.editor.objects.ObjectEntry
import org.zender.editor.project.ModProject
import org.zender.editor.project.ModValidator
import org.zender.editor.project.ProjectObject
import org.zender.editor.project.ValidationSeverity
import org.zender.editor.resource.ResourceKind
import org.zender.editor.schema.FieldKind
import org.zender.editor.schema.FieldSchema

/**
 * 只读工具：打开工程/数据包、浏览对象与 Schema、校验、查看本地化与资源。
 */
internal fun readOnlyTools(workspace: McpWorkspace): List<McpTool> = listOf(
    openProjectTool(workspace),
    openPackTool(workspace),
    projectSummaryTool(workspace),
    listObjectTypesTool(workspace),
    listObjectsTool(workspace),
    getObjectTool(workspace),
    getSchemaTool(workspace),
    validateProjectTool(workspace),
    listLocalizationTool(workspace),
    listResourcesTool(workspace),
)

private fun openProjectTool(workspace: McpWorkspace): McpTool = McpTool(
    name = "open_project",
    title = "打开 Mod 工程",
    description = "读取 ML/MC Mod 工程目录（含 ModInfo.json 或 ModMeta.json），并返回工程概要。" +
        "可选 packPath 指定游戏数据包；省略时复用当前数据包，或使用编辑器设置中记录的工程↔数据包关联。",
    inputSchema = objectSchema(
        properties = mapOf(
            "path" to stringProperty("Mod 工程根目录；相对路径按 MCP 进程工作目录解析。"),
            "packPath" to stringProperty("可选：游戏数据包目录（含 PackInfo.json）。"),
        ),
        required = listOf("path"),
    ),
) { args ->
    val project = workspace.openProject(args.requirePath("path"), args.path("packPath"))
    projectSummary(workspace, project)
}

private fun openPackTool(workspace: McpWorkspace): McpTool = McpTool(
    name = "open_pack",
    title = "打开游戏数据包",
    description = "加载只读游戏数据包（PackInfo.json、TypeStruct、BaseJson、索引、本地化与资源），" +
        "供 Schema 查询、对象浏览与工程校验使用。已打开的工程会自动绑定新数据包。",
    inputSchema = objectSchema(
        properties = mapOf(
            "path" to stringProperty("数据包根目录；相对路径按 MCP 进程工作目录解析。"),
        ),
        required = listOf("path"),
    ),
) { args ->
    val pack = workspace.openPack(args.requirePath("path"))
    buildJsonObject {
        put("opened", true)
        putJsonObject("pack") {
            put("root", pack.root.toString())
            put("name", pack.info.name)
            put("version", pack.info.version)
            put("isMainPack", pack.info.isMainPack)
            put("typeCount", pack.typeNames.size)
            put("objectCount", pack.objectIndex.entries.size)
        }
        put("diagnostics", workspace.diagnostics.toDiagnosticsJson())
    }
}

private fun projectSummaryTool(workspace: McpWorkspace): McpTool = McpTool(
    name = "project_summary",
    title = "工程概要",
    description = "返回当前工程的框架、元数据、对象/补丁/资源/本地化数量、绑定的数据包与未保存状态。",
    inputSchema = objectSchema(properties = emptyMap()),
) {
    projectSummary(workspace, workspace.requireProject())
}

private fun listObjectTypesTool(workspace: McpWorkspace): McpTool = McpTool(
    name = "list_object_types",
    title = "列出对象类型",
    description = "列出工程、数据包对象索引与 TypeStruct 中出现过的对象类型，" +
        "并给出工程/数据包中的条目数量与是否有 Schema。",
    inputSchema = objectSchema(properties = emptyMap()),
) {
    val project = workspace.project
    val pack = workspace.pack
    val projectCounts = project?.workingTree?.objects?.values
        ?.groupingBy { it.type }
        ?.eachCount()
        .orEmpty()
    val packCounts = pack?.objectIndex?.byType?.mapValues { it.value.size }.orEmpty()
    val types = (projectCounts.keys + packCounts.keys + pack?.typeNames.orEmpty())
        .distinct()
        .sortedBy { it.lowercase() }

    buildJsonObject {
        putJsonArray("types") {
            types.forEach { type ->
                add(
                    buildJsonObject {
                        put("type", type)
                        put("projectCount", projectCounts[type] ?: 0)
                        put("packCount", packCounts[type] ?: 0)
                        put("hasSchema", pack?.schema(type) != null)
                    },
                )
            }
        }
    }
}

private fun listObjectsTool(workspace: McpWorkspace): McpTool = McpTool(
    name = "list_objects",
    title = "浏览对象",
    description = "按类型/关键字浏览工程对象或数据包对象。默认同时列出两者；" +
        "同名对象以工程为准。返回分页信息与对象元数据（不返回完整 JSON）。",
    inputSchema = objectSchema(
        properties = mapOf(
            "type" to stringProperty("可选：对象类型，例如 CardData。"),
            "query" to stringProperty("可选：按对象名或索引中文名做不区分大小写的子串匹配。"),
            "source" to stringProperty(
                "对象来源。",
                enumValues = listOf("all", "project", "pack"),
                default = "all",
            ),
            "offset" to integerProperty("分页偏移。", default = 0, minimum = 0),
            "limit" to integerProperty("返回条数。", default = 200, minimum = 1, maximum = 1000),
        ),
    ),
) { args ->
    val project = workspace.project
    val pack = workspace.pack
    val source = args.string("source")?.lowercase() ?: "all"
    if (source !in setOf("all", "project", "pack")) {
        throw McpToolException("source 只能是 all / project / pack")
    }
    if (source != "project" && pack == null) {
        throw McpToolException("尚未打开数据包，无法浏览数据包对象")
    }

    val projectRows = project?.workingTree?.objects?.values?.map { it.toRow() }.orEmpty()
    val projectKeys = projectRows.mapTo(HashSet()) { it.type to it.name }
    val packRows = if (source == "project") {
        emptyList()
    } else {
        pack?.objectIndex?.entries
            ?.map { it.toRow() }
            ?.filterNot { (it.type to it.name) in projectKeys }
            .orEmpty()
    }
    val rows = when (source) {
        "project" -> projectRows
        "pack" -> packRows
        else -> projectRows + packRows
    }

    val typeFilter = args.string("type")?.trim()?.takeIf { it.isNotEmpty() }
    val query = args.string("query")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val filtered = rows
        .asSequence()
        .filter { typeFilter == null || it.type.equals(typeFilter, ignoreCase = true) }
        .filter { row ->
            query == null ||
                row.name.lowercase().contains(query) ||
                row.displayName?.lowercase()?.contains(query) == true
        }
        .sortedWith(compareBy({ it.type.lowercase() }, { it.name.lowercase() }))
        .toList()

    val offset = args.integer("offset", 0).coerceAtLeast(0)
    val limit = args.integer("limit", 200).coerceIn(1, 1000)
    val page = filtered.drop(offset).take(limit)

    buildJsonObject {
        put("total", filtered.size)
        put("offset", offset)
        put("limit", limit)
        put("truncated", offset + page.size < filtered.size)
        putJsonArray("objects") { page.forEach { add(it.toJson()) } }
    }
}

private fun getObjectTool(workspace: McpWorkspace): McpTool = McpTool(
    name = "get_object",
    title = "读取对象 JSON",
    description = "按类型与名称读取工程对象或数据包对象的完整 JSON。" +
        "source=auto 时优先工程对象，其次数据包对象。",
    inputSchema = objectSchema(
        properties = mapOf(
            "type" to stringProperty("对象类型。"),
            "name" to stringProperty("对象名。"),
            "source" to stringProperty(
                "来源：auto 优先工程；project 仅工程；pack 仅数据包。",
                enumValues = listOf("auto", "project", "pack"),
                default = "auto",
            ),
        ),
        required = listOf("type", "name"),
    ),
) { args ->
    val type = args.requireString("type")
    val name = args.requireString("name")
    val source = args.string("source")?.lowercase() ?: "auto"
    if (source !in setOf("auto", "project", "pack")) {
        throw McpToolException("source 只能是 auto / project / pack")
    }

    val objectDocument = workspace.objectDocument(type, name, source)
        ?: throw McpToolException(
            if (source == "project") {
                "工程中不存在对象：$type/$name"
            } else {
                "工程与数据包中都不存在对象：$type/$name"
            },
        )

    buildJsonObject {
        put("source", objectDocument.source)
        put("type", type)
        put("name", name)
        objectDocument.uniqueId?.let { put("uniqueId", it) }
        objectDocument.group?.let { put("group", it) }
        objectDocument.displayName?.let { put("displayName", it) }
        put("path", objectDocument.relativePath)
        put("json", objectDocument.document.toJsonElement())
    }
}

private fun getSchemaTool(workspace: McpWorkspace): McpTool = McpTool(
    name = "get_schema",
    title = "读取类型 Schema",
    description = "返回类型在数据包中的字段定义：种类、元素类型、只读标记、枚举值、模板值与中文说明。" +
        "编辑字段前可用它确认字段路径与取值。",
    inputSchema = objectSchema(
        properties = mapOf(
            "type" to stringProperty("对象类型名。"),
        ),
        required = listOf("type"),
    ),
) { args ->
    val type = args.requireString("type")
    val pack = workspace.pack ?: throw McpToolException("尚未打开数据包")
    val schema = pack.schema(type)
        ?: throw McpToolException("数据包中不存在类型 $type 的 TypeStruct")

    val repository = pack.schemaRepository
    buildJsonObject {
        put("type", schema.typeName)
        schema.template?.let { put("template", it.toJsonElement()) }
        putJsonArray("fields") {
            schema.fields.forEach { field ->
                val elementTypeName = field.elementTypeName
                val enumValues = when {
                    field.kind == FieldKind.ENUM ->
                        repository.enumValues(field.typeName)

                    field.kind == FieldKind.LIST &&
                        field.elementKind == FieldKind.ENUM &&
                        elementTypeName != null ->
                        repository.enumValues(elementTypeName)

                    else -> emptyList()
                }
                add(field.toJson(enumValues.map { it.name to it.value.toJsonElement() }))
            }
        }
    }
}

private fun FieldSchema.toJson(enumValues: List<Pair<String, JsonElement>>): JsonElement =
    buildJsonObject {
        put("name", name)
        put("typeName", typeName)
        put("kind", kind.name)
        elementTypeName?.let { put("elementTypeName", it) }
        elementKind?.let { put("elementKind", it.name) }
        put("readOnly", isReadOnly)
        if (enumValues.isNotEmpty()) {
            putJsonArray("enumValues") {
                enumValues.forEach { (enumName, value) ->
                    add(
                        buildJsonObject {
                            put("name", enumName)
                            put("value", value)
                        },
                    )
                }
            }
        }
        documentation.chinese?.alias?.let { put("alias", it) }
        note()?.let { put("note", it) }
        templateValue?.let { put("templateValue", it.toJsonElement()) }
    }

private fun validateProjectTool(workspace: McpWorkspace): McpTool = McpTool(
    name = "validate_project",
    title = "校验工程",
    description = "运行 Schema、引用、框架能力、GUID/名称冲突与本地化校验，返回按严重级别分类的问题列表。",
    inputSchema = objectSchema(properties = emptyMap()),
) {
    val project = workspace.requireProject()
    val issues = ModValidator(workspace.pack).validate(project)
    buildJsonObject {
        put("errorCount", issues.count { it.severity == ValidationSeverity.ERROR })
        put("warningCount", issues.count { it.severity == ValidationSeverity.WARNING })
        put("infoCount", issues.count { it.severity == ValidationSeverity.INFO })
        put("issues", issues.toIssuesJson())
    }
}

private fun listLocalizationTool(workspace: McpWorkspace): McpTool = McpTool(
    name = "list_localization",
    title = "浏览本地化",
    description = "列出工程本地化条目（Key / English / 中文），支持关键字过滤与分页。",
    inputSchema = objectSchema(
        properties = mapOf(
            "query" to stringProperty("可选：Key、英文或中文的不区分大小写子串匹配。"),
            "offset" to integerProperty("分页偏移。", default = 0, minimum = 0),
            "limit" to integerProperty("返回条数。", default = 200, minimum = 1, maximum = 1000),
        ),
    ),
) { args ->
    val entries = workspace.requireProject().workingTree.localization.table.entries
    val query = args.string("query")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val filtered = entries.filter { entry ->
        query == null ||
            entry.key.lowercase().contains(query) ||
            entry.english.lowercase().contains(query) ||
            entry.chinese.lowercase().contains(query)
    }
    val offset = args.integer("offset", 0).coerceAtLeast(0)
    val limit = args.integer("limit", 200).coerceIn(1, 1000)
    val page = filtered.drop(offset).take(limit)

    buildJsonObject {
        put("total", filtered.size)
        put("offset", offset)
        put("limit", limit)
        put("truncated", offset + page.size < filtered.size)
        putJsonArray("entries") {
            page.forEach { entry ->
                add(
                    buildJsonObject {
                        put("key", entry.key)
                        put("english", entry.english)
                        put("chinese", entry.chinese)
                    },
                )
            }
        }
    }
}

private fun listResourcesTool(workspace: McpWorkspace): McpTool = McpTool(
    name = "list_resources",
    title = "浏览工程资源",
    description = "列出工程中的图片/音频资源，支持关键字与类型过滤。",
    inputSchema = objectSchema(
        properties = mapOf(
            "query" to stringProperty("可选：资源键的不区分大小写子串匹配。"),
            "kind" to stringProperty(
                "资源类型。",
                enumValues = listOf("image", "audio"),
            ),
        ),
    ),
) { args ->
    val query = args.string("query")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val kind = when (args.string("kind")?.lowercase()) {
        null -> null
        "image" -> ResourceKind.IMAGE
        "audio" -> ResourceKind.AUDIO
        else -> throw McpToolException("kind 只能是 image / audio")
    }
    val resources = workspace.requireProject().workingTree.resources.values
        .asSequence()
        .filter { kind == null || it.kind == kind }
        .filter { query == null || it.key.lowercase().contains(query) }
        .sortedBy { "${it.kind}/${it.key}".lowercase() }
        .toList()

    buildJsonObject {
        put("total", resources.size)
        putJsonArray("resources") {
            resources.forEach { resource ->
                add(
                    buildJsonObject {
                        put("kind", resource.kind.name)
                        put("key", resource.key)
                        put("typeName", resource.typeName)
                        put("path", resource.relativePath)
                        put("sizeBytes", resource.sizeBytes)
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 共享输出

internal fun projectSummary(workspace: McpWorkspace, project: ModProject): JsonElement =
    buildJsonObject {
        put("root", project.root?.toString() ?: "")
        put("framework", project.framework.name)
        put("namespace", project.metadata.namespace)
        put("displayName", project.metadata.displayName())
        put("version", project.metadata.version)
        put("dirty", workspace.dirty)
        putJsonObject("counts") {
            put("objects", project.workingTree.objectCount)
            put("gameSourceModifies", project.workingTree.gameSourceModifies.size)
            put("dataObjectModifies", project.workingTree.dataObjectModifies.size)
            put("resources", project.workingTree.resourceCount)
            put("localization", project.workingTree.localization.table.size)
        }
        val pack = workspace.pack
        if (pack == null) {
            put("pack", kotlinx.serialization.json.JsonNull)
        } else {
            putJsonObject("pack") {
                put("root", pack.root.toString())
                put("name", pack.info.name)
                put("version", pack.info.version)
                put("typeCount", pack.typeNames.size)
                put("objectCount", pack.objectIndex.entries.size)
            }
        }
        put("diagnostics", workspace.diagnostics.toDiagnosticsJson())
    }

private data class ObjectRow(
    val type: String,
    val name: String,
    val uniqueId: String?,
    val group: String?,
    val displayName: String?,
    val source: String,
    val path: String?,
    val hasDataFile: Boolean,
) {
    fun toJson(): JsonElement = buildJsonObject {
        put("type", type)
        put("name", name)
        uniqueId?.let { put("uniqueId", it) }
        group?.let { put("group", it) }
        displayName?.let { put("displayName", it) }
        put("source", source)
        path?.let { put("path", it) }
        put("hasDataFile", hasDataFile)
    }
}

private fun ProjectObject.toRow(): ObjectRow = ObjectRow(
    type = type,
    name = name,
    uniqueId = uniqueId,
    group = null,
    displayName = null,
    source = "project",
    path = relativePath,
    hasDataFile = true,
)

private fun ObjectEntry.toRow(): ObjectRow = ObjectRow(
    type = type,
    name = name,
    uniqueId = uniqueId,
    group = group,
    displayName = displayName,
    source = "pack",
    path = relativePath,
    hasDataFile = hasDataFile,
)
