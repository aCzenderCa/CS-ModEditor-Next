package org.zender.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonFormat
import org.zender.editor.document.JsonNode
import org.zender.editor.document.toJsonText
import org.zender.editor.log.Log
import org.zender.editor.project.ValidationIssue
import java.nio.file.Path

/** MCP 输出统一使用的 JSON 配置：保留数字原文，便于阅读与回写。 */
internal val McpJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    prettyPrint = true
}

internal fun JsonElement.prettyText(): String =
    McpJson.encodeToString(JsonElement.serializer(), this)

internal fun JsonNode.toJsonElement(): JsonElement =
    McpJson.parseToJsonElement(toJsonText(JsonFormat.Compact))

internal fun JsonDocument.toJsonElement(): JsonElement = root.toJsonElement()

internal fun Diagnostic.toJsonObject(): JsonObject = buildJsonObject {
    put("severity", severity.name)
    put("message", message)
    path?.let { put("path", it) }
}

internal fun List<Diagnostic>.toDiagnosticsJson(): JsonArray = buildJsonArray {
    forEach { add(it.toJsonObject()) }
}

internal fun ValidationIssue.toJsonObject(): JsonObject = buildJsonObject {
    put("severity", severity.name)
    put("code", code)
    put("message", message)
    path?.let { put("path", it) }
}

internal fun List<ValidationIssue>.toIssuesJson(): JsonArray = buildJsonArray {
    forEach { add(it.toJsonObject()) }
}

/**
 * 预期内的工具失败：返回 `isError = true` 的文本给模型，而不是 JSON-RPC 协议错误。
 */
class McpToolException(message: String) : RuntimeException(message)

data class McpToolResult(
    val text: String,
    val isError: Boolean,
)

/** 一次工具调用的参数视图。 */
class ToolArgs(private val values: JsonObject) {

    fun string(name: String): String? =
        (values[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun requireString(name: String): String =
        string(name)?.takeIf { it.isNotBlank() }
            ?: throw McpToolException("缺少参数：$name")

    fun path(name: String): Path? = string(name)?.takeIf { it.isNotBlank() }?.let(::toPath)

    fun requirePath(name: String): Path = path(name)
        ?: throw McpToolException("缺少参数：$name")

    fun boolean(name: String, default: Boolean = false): Boolean =
        (values[name] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default

    fun integer(name: String, default: Int): Int =
        (values[name] as? JsonPrimitive)?.content?.toIntOrNull() ?: default

    /** 区分「未提供」与 0；提供但非法时抛工具错误。 */
    fun optionalInteger(name: String): Int? {
        val element = values[name] ?: return null
        if (element is kotlinx.serialization.json.JsonNull) return null
        return (element as? JsonPrimitive)?.content?.toIntOrNull()
            ?: throw McpToolException("参数 $name 必须是整数")
    }

    fun raw(name: String): JsonElement? = values[name]

    private fun toPath(value: String): Path {
        val path = Path.of(value)
        val absolute = if (path.isAbsolute) {
            path
        } else {
            Path.of(System.getProperty("user.dir")).resolve(path)
        }
        return absolute.normalize()
    }
}

/** 一个可被 MCP 客户端调用的工具。 */
class McpTool(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    private val handler: (ToolArgs) -> JsonElement,
) {

    fun definition(): JsonObject = buildJsonObject {
        put("name", name)
        put("title", title)
        put("description", description)
        put("inputSchema", inputSchema)
    }

    fun execute(arguments: JsonObject): McpToolResult = try {
        McpToolResult(handler(ToolArgs(arguments)).prettyText(), isError = false)
    } catch (e: McpToolException) {
        McpToolResult(e.message ?: "工具执行失败", isError = true)
    } catch (e: Exception) {
        Log.getLogger("mcp").error("工具 $name 执行异常", e)
        McpToolResult("工具执行异常：${e.message ?: e::class.simpleName}", isError = true)
    }
}

/**
 * 工具目录。会话状态在进程生命周期内共享。
 */
class McpToolRegistry(
    workspace: McpWorkspace = FileMcpWorkspace(),
) {

    private val tools: List<McpTool> =
        readOnlyTools(workspace) + editingTools(workspace)

    private val byName: Map<String, McpTool> = tools.associateBy { it.name }

    val definitions: List<JsonObject> = tools.map { it.definition() }

    fun find(name: String): McpTool? = byName[name]
}

// ---------------------------------------------------------------- Schema 辅助

internal fun objectSchema(
    properties: Map<String, JsonElement>,
    required: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    put(
        "properties",
        JsonObject(LinkedHashMap(properties)),
    )
    if (required.isNotEmpty()) {
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }
    put("additionalProperties", false)
}

internal fun stringProperty(
    description: String,
    enumValues: List<String>? = null,
    default: String? = null,
): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    enumValues?.let { values ->
        put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }
    default?.let { put("default", it) }
}

internal fun booleanProperty(description: String, default: Boolean): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
    put("default", default)
}

internal fun integerProperty(
    description: String,
    default: Int? = null,
    minimum: Int? = null,
    maximum: Int? = null,
): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    default?.let { put("default", it) }
    minimum?.let { put("minimum", it) }
    maximum?.let { put("maximum", it) }
}
