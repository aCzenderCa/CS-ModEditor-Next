package org.zender.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.document.readPath
import org.zender.editor.edit.WarpFields
import org.zender.editor.edit.WarpType
import org.zender.editor.field.FieldEditorInfo
import org.zender.editor.field.FieldSchemaLocator
import org.zender.editor.project.NewObjectRequest
import org.zender.editor.project.ProjectObjectKind
import org.zender.editor.resource.ResourceKind
import org.zender.editor.schema.FieldKind

/**
 * 可写工具：字段、引用、新建对象、保存、本地化与资源导入。
 *
 * 工具只做参数解析与 Schema 提示；真正的修改交给 [McpWorkspace]，
 * 因此编辑器内嵌模式下所有改动都会进入正在运行的编辑会话。
 */
internal fun editingTools(workspace: McpWorkspace): List<McpTool> = listOf(
    setFieldTool(workspace),
    setReferenceTool(workspace),
    clearReferenceTool(workspace),
    removeReferenceTool(workspace),
    createObjectTool(workspace),
    saveProjectTool(workspace),
    setLocalizationTool(workspace),
    importResourceTool(workspace),
)

private fun setFieldTool(workspace: McpWorkspace): McpTool = McpTool(
    name = "set_field",
    title = "修改字段",
    description = "修改工程对象中某个字段的 JSON 值。path 使用 JsonPath 文本，" +
        "例如 `CardInteractions[0].ActionName` 或 `Name`。修改会立即进入编辑器会话；" +
        "save=true 时同时写回工程目录。写入前会按数据包 Schema 校验字段是否存在、是否只读与类型是否匹配。",
    inputSchema = objectSchema(
        properties = mapOf(
            "type" to stringProperty("对象类型。"),
            "name" to stringProperty("对象名。"),
            "path" to stringProperty("字段路径（JsonPath 文本），不能为空。"),
            "value" to stringProperty(
                "要写入的值。按 valueType 解析；默认 auto 时按字段 Schema 推断，" +
                    "例如字符串字段直接传原文，数字字段传数字。",
            ),
            "valueType" to stringProperty(
                "值解析方式。auto 按字段 Schema 推断；string/number/boolean/json 强制按对应类型解析。",
                enumValues = listOf("auto", "json", "string", "number", "boolean"),
                default = "auto",
            ),
            "save" to booleanProperty("是否立即写回工程目录。", default = false),
        ),
        required = listOf("type", "name", "path", "value"),
    ),
) { args ->
    val type = args.requireString("type")
    val name = args.requireString("name")
    val path = parseFieldPath(args.requireString("path"))
    val save = args.boolean("save", false)

    workspace.requireProject()
    val info = workspace.pack?.schemaRepository?.let {
        FieldSchemaLocator.resolve(it, type, path)
    }
    if (info?.isReadOnly == true) {
        throw McpToolException("字段只读：${path.format()}")
    }

    val node = parseValue(args.raw("value"), args.string("valueType"), info)
    val oldNode = workspace.objectDocument(type, name, "project")
        ?.document
        ?.root
        ?.readPath(path)

    val outcome = workspace.setField(type, name, path, node)
    if (!outcome.changed) {
        throw McpToolException(outcome.message ?: "字段没有变化")
    }
    val saved = if (save) {
        workspace.saveProject(null)
        true
    } else {
        false
    }

    buildJsonObject {
        put("changed", true)
        put("type", type)
        put("name", name)
        put("path", path.format())
        if (oldNode == null) put("oldValue", JsonNull) else put("oldValue", oldNode.toJsonElement())
        put("newValue", node.toJsonElement())
        put("saved", saved)
    }
}

private fun setReferenceTool(workspace: McpWorkspace): McpTool = McpTool(
    name = "set_reference",
    title = "设置引用字段",
    description = "设置标量或列表引用字段的 WarpData/WarpType 配对。field 指向字段本体" +
        "（如 `CardImage` 或 `CardTags`）。warpType 使用游戏语义：3=REFERENCE、" +
        "4=ADD、5=MODIFY、6=ADD_REFERENCE；普通工程对象通常使用 3。",
    inputSchema = objectSchema(
        properties = mapOf(
            "type" to stringProperty("对象类型。"),
            "name" to stringProperty("对象名。"),
            "field" to stringProperty("引用字段路径，必须以对象键结尾。"),
            "referenceKey" to stringProperty(
                "引用键：GUID 或对象名。MC 工程内引用非 GUID 对象时可只写对象名，" +
                    "编辑器会规范化为 `Namespace:Name`。",
            ),
            "warpType" to integerProperty(
                "WarpType 数值（3..6）。",
                default = 3,
                minimum = 3,
                maximum = 6,
            ),
            "index" to integerProperty("列表引用插入下标；省略表示追加到末尾。", minimum = 0),
            "save" to booleanProperty("是否立即写回工程目录。", default = false),
        ),
        required = listOf("type", "name", "field", "referenceKey"),
    ),
) { args ->
    val type = args.requireString("type")
    val name = args.requireString("name")
    val field = parseFieldPath(args.requireString("field"))
    val referenceKey = args.requireString("referenceKey")
    val warpType = WarpType.fromValue(args.integer("warpType", WarpType.REFERENCE.value))
        ?: throw McpToolException("warpType 必须是 3..6")
    val save = args.boolean("save", false)

    val info = workspace.pack?.schemaRepository?.let {
        FieldSchemaLocator.resolve(it, type, field)
    } ?: throw McpToolException("无法解析引用字段：${field.format()}")
    if (!info.isReference && !info.isReferenceList) {
        throw McpToolException("字段 ${field.format()} 不是引用字段（Schema: ${info.kind}）")
    }

    val outcome = workspace.setReference(
        type = type,
        name = name,
        field = field,
        referenceKey = referenceKey,
        warpType = warpType,
        index = args.optionalInteger("index"),
    )
    if (!outcome.changed) {
        throw McpToolException(outcome.message ?: "引用没有变化")
    }
    val saved = if (save) {
        workspace.saveProject(null)
        true
    } else {
        false
    }

    buildJsonObject {
        put("changed", true)
        put("field", field.format())
        put("warpDataPath", WarpFields.dataPath(field).format())
        put("warpTypePath", WarpFields.typePath(field).format())
        put("referenceKey", referenceKey)
        put("warpType", warpType.value)
        put("saved", saved)
    }
}

private fun clearReferenceTool(workspace: McpWorkspace): McpTool = McpTool(
    name = "clear_reference",
    title = "清除引用字段",
    description = "删除引用字段的 WarpData/WarpType 配对，不修改字段本体（通常保持 `{}` 或 `[]`）。",
    inputSchema = objectSchema(
        properties = mapOf(
            "type" to stringProperty("对象类型。"),
            "name" to stringProperty("对象名。"),
            "field" to stringProperty("引用字段路径，必须以对象键结尾。"),
            "save" to booleanProperty("是否立即写回工程目录。", default = false),
        ),
        required = listOf("type", "name", "field"),
    ),
) { args ->
    val type = args.requireString("type")
    val name = args.requireString("name")
    val field = parseFieldPath(args.requireString("field"))
    val save = args.boolean("save", false)

    val outcome = workspace.clearReference(type, name, field)
    if (!outcome.changed) {
        throw McpToolException(outcome.message ?: "引用没有变化")
    }
    val saved = if (save) {
        workspace.saveProject(null)
        true
    } else {
        false
    }

    buildJsonObject {
        put("changed", true)
        put("field", field.format())
        put("saved", saved)
    }
}

private fun removeReferenceTool(workspace: McpWorkspace): McpTool = McpTool(
    name = "remove_reference",
    title = "移除列表引用",
    description = "从列表引用字段的 WarpData 数组中移除指定下标的引用键。",
    inputSchema = objectSchema(
        properties = mapOf(
            "type" to stringProperty("对象类型。"),
            "name" to stringProperty("对象名。"),
            "field" to stringProperty("列表引用字段路径。"),
            "index" to integerProperty("WarpData 数组下标。", minimum = 0),
            "save" to booleanProperty("是否立即写回工程目录。", default = false),
        ),
        required = listOf("type", "name", "field", "index"),
    ),
) { args ->
    val type = args.requireString("type")
    val name = args.requireString("name")
    val field = parseFieldPath(args.requireString("field"))
    val index = args.integer("index", -1)
    if (index < 0) throw McpToolException("缺少参数：index")
    val save = args.boolean("save", false)

    val outcome = workspace.removeReference(type, name, field, index)
    if (!outcome.changed) {
        throw McpToolException(outcome.message ?: "引用没有变化")
    }
    val saved = if (save) {
        workspace.saveProject(null)
        true
    } else {
        false
    }

    buildJsonObject {
        put("changed", true)
        put("field", field.format())
        put("index", index)
        put("saved", saved)
    }
}

private fun createObjectTool(workspace: McpWorkspace): McpTool = McpTool(
    name = "create_object",
    title = "新建对象",
    description = "在工程中新建 ML/MC 对象。默认按数据包索引推断 UniqueID/ScriptableObject，并从 " +
        "BaseJson 模板生成完整 JSON；提供 copyFromType/copyFromName 时复制已有对象（重新生成 GUID 与本地化 Key）。" +
        "对象会立即出现在编辑器中；save=true 时同时写回工程目录。",
    inputSchema = objectSchema(
        properties = mapOf(
            "type" to stringProperty("对象类型，例如 CardData。"),
            "name" to stringProperty("对象名；省略时由类型推导。"),
            "kind" to stringProperty(
                "对象种类；省略时按数据包索引推断。",
                enumValues = listOf("unique_id", "scriptable"),
            ),
            "group" to stringProperty(
                "可选分组子目录，支持多级（用 / 分隔，例如 `Item/Weapons`）；" +
                    "CardData 的第一段通常为 CardType。",
            ),
            "uniqueId" to stringProperty("可选 GUID；省略时自动生成。"),
            "copyFromType" to stringProperty("可选：复制来源对象类型。"),
            "copyFromName" to stringProperty("可选：复制来源对象名。"),
            "save" to booleanProperty("是否立即写回工程目录。", default = false),
        ),
        required = listOf("type"),
    ),
) { args ->
    val type = args.requireString("type")
    val project = workspace.requireProject()

    val copyType = args.string("copyFromType")
    val copyName = args.string("copyFromName")
    val sourceDocument = if (copyType != null || copyName != null) {
        if (copyType == null || copyName == null) {
            throw McpToolException("copyFromType 与 copyFromName 必须同时提供")
        }
        project.objectOf(copyType, copyName)?.document
            ?: throw McpToolException("复制来源不存在：$copyType/$copyName")
    } else {
        null
    }

    val kind = when (args.string("kind")?.lowercase()) {
        null -> null
        "unique_id", "uniqueid" -> ProjectObjectKind.UNIQUE_ID
        "scriptable", "scriptable_object" -> ProjectObjectKind.SCRIPTABLE_OBJECT
        else -> throw McpToolException("kind 只能是 unique_id / scriptable")
    }

    val created = workspace.createObject(
        NewObjectRequest(
            type = type,
            name = args.string("name"),
            kind = kind,
            group = args.string("group"),
            uniqueId = args.string("uniqueId"),
            sourceDocument = sourceDocument,
        ),
    )

    val saved = if (args.boolean("save", false)) {
        workspace.saveProject(null)
        true
    } else {
        false
    }

    buildJsonObject {
        put("created", true)
        put("type", created.type)
        put("name", created.name)
        created.uniqueId?.let { put("uniqueId", it) }
        put("path", created.relativePath)
        put("saved", saved)
        put("diagnostics", workspace.diagnostics.toDiagnosticsJson())
    }
}

private fun saveProjectTool(workspace: McpWorkspace): McpTool = McpTool(
    name = "save_project",
    title = "保存工程",
    description = "把编辑器当前工程（含未保存修改）写入工程目录。所有文件通过原子写入完成；" +
        "不会删除磁盘上已存在但工程中不存在的文件。",
    inputSchema = objectSchema(
        properties = mapOf(
            "targetPath" to stringProperty("可选：另存为的目录；省略时写回工程根目录。"),
        ),
    ),
) { args ->
    val result = workspace.saveProject(args.path("targetPath"))
    buildJsonObject {
        put("saved", true)
        put("writtenFileCount", result.writtenFiles.size)
        putJsonArray("writtenFiles") {
            result.writtenFiles.forEach { add(JsonPrimitive(it)) }
        }
        put("diagnostics", result.diagnostics.toDiagnosticsJson())
    }
}

private fun setLocalizationTool(workspace: McpWorkspace): McpTool = McpTool(
    name = "set_localization",
    title = "设置本地化",
    description = "新增或修改工程本地化条目。省略 english/chinese 时保留已有值。",
    inputSchema = objectSchema(
        properties = mapOf(
            "key" to stringProperty("本地化 Key。"),
            "english" to stringProperty("英文文本；省略则保留原值。"),
            "chinese" to stringProperty("中文文本；省略则保留原值。"),
            "save" to booleanProperty("是否立即写回工程目录。", default = false),
        ),
        required = listOf("key"),
    ),
) { args ->
    val key = args.requireString("key")
    val entry = workspace.updateLocalization(
        key = key,
        english = args.string("english"),
        chinese = args.string("chinese"),
    )

    val saved = if (args.boolean("save", false)) {
        workspace.saveProject(null)
        true
    } else {
        false
    }

    buildJsonObject {
        put("changed", true)
        put("key", entry.key)
        put("english", entry.english)
        put("chinese", entry.chinese)
        put("saved", saved)
    }
}

private fun importResourceTool(workspace: McpWorkspace): McpTool = McpTool(
    name = "import_resource",
    title = "导入资源",
    description = "把外部图片/音频导入工程资源目录。key 省略时使用文件名（不含扩展名）。" +
        "资源文件会立即复制到工程目录，其余修改可通过 save=true 一并保存。",
    inputSchema = objectSchema(
        properties = mapOf(
            "sourcePath" to stringProperty("外部资源文件路径。"),
            "key" to stringProperty("可选：资源键；省略时使用文件名。"),
            "kind" to stringProperty(
                "资源类型；省略时按扩展名推断。",
                enumValues = listOf("image", "audio"),
            ),
            "overwrite" to booleanProperty("资源键已存在时是否覆盖。", default = false),
            "save" to booleanProperty("是否在导入后保存工程。", default = false),
        ),
        required = listOf("sourcePath"),
    ),
) { args ->
    val source = args.requirePath("sourcePath")
    val kind = when (args.string("kind")?.lowercase()) {
        null -> null
        "image" -> ResourceKind.IMAGE
        "audio" -> ResourceKind.AUDIO
        else -> throw McpToolException("kind 只能是 image / audio")
    }

    val result = workspace.importResource(
        source = source,
        key = args.string("key"),
        kind = kind,
        overwrite = args.boolean("overwrite", false),
    )
    val imported = result.resource
        ?: throw McpToolException("导入资源失败：没有返回资源信息")

    val saved = if (args.boolean("save", false)) {
        workspace.saveProject(null)
        true
    } else {
        false
    }

    buildJsonObject {
        put("imported", true)
        put("kind", imported.kind.name)
        put("key", imported.key)
        put("path", imported.relativePath)
        put("sizeBytes", imported.sizeBytes)
        put("saved", saved)
        put("diagnostics", result.diagnostics.toDiagnosticsJson())
    }
}

// ---------------------------------------------------------------- 内部辅助

private fun parseFieldPath(text: String): JsonPath {
    val path = try {
        JsonPath.parse(text)
    } catch (e: Exception) {
        throw McpToolException("字段路径不合法：${e.message}")
    }
    if (path.isEmpty) throw McpToolException("字段路径不能为空")
    return path
}

/**
 * 把 MCP 参数解析为 JSON 节点。
 *
 * `valueType=auto` 时优先按字段 Schema 解释：字符串字段保留原文，
 * 数字/布尔字段强制对应类型，其余按 JSON 解析；解析失败再回退为字符串。
 */
private fun parseValue(
    raw: JsonElement?,
    valueType: String?,
    info: FieldEditorInfo?,
): JsonNode {
    if (raw == null || raw is JsonNull) return JsonNode.NullNode
    val text = when (raw) {
        is JsonPrimitive -> raw.content
        else -> raw.toString()
    }
    return when (valueType?.lowercase() ?: "auto") {
        "string" -> JsonNode.StringNode(text)
        "number" -> parseNumber(text)
        "boolean" -> parseBoolean(text)
        "json" -> parseJson(text)
        "auto" -> autoValue(raw, info)
        else -> throw McpToolException("valueType 只能是 auto / json / string / number / boolean")
    }
}

private fun autoValue(raw: JsonElement, info: FieldEditorInfo?): JsonNode {
    val text = when (raw) {
        is JsonPrimitive -> raw.content
        else -> raw.toString()
    }
    return when (info?.kind) {
        FieldKind.STRING -> JsonNode.StringNode(text)
        FieldKind.INTEGER, FieldKind.FLOAT -> parseNumber(text)
        FieldKind.BOOLEAN -> parseBoolean(text)
        FieldKind.OBJECT, FieldKind.LIST, FieldKind.REFERENCE -> parseJson(text)
        FieldKind.ENUM -> runCatching { parseJson(text) }.getOrElse { JsonNode.StringNode(text) }
        FieldKind.UNKNOWN, null -> when {
            raw !is JsonPrimitive -> parseJson(text)
            raw.isString -> runCatching { parseJson(text) }.getOrElse { JsonNode.StringNode(text) }
            else -> parseJson(text)
        }
    }
}

private fun parseJson(text: String): JsonNode {
    val parsed = JsonDocument.parse(text)
    return parsed.document?.root
        ?: throw McpToolException("不是合法 JSON：${parsed.error?.message}")
}

private fun parseNumber(text: String): JsonNode.NumberNode =
    try {
        JsonNode.NumberNode(text.trim())
    } catch (e: Exception) {
        throw McpToolException("不是合法数字：$text")
    }

private fun parseBoolean(text: String): JsonNode.BooleanNode =
    text.trim().toBooleanStrictOrNull()?.let(JsonNode.BooleanNode::of)
        ?: throw McpToolException("布尔值只接受 true / false：$text")
