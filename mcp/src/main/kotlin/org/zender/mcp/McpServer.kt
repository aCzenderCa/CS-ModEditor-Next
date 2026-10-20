package org.zender.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.zender.editor.log.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * 与传输无关的 MCP JSON-RPC 处理核心。
 *
 * HTTP 与 stdio 两种传输共用同一份会话与工具目录；
 * stdout/HTTP 只写协议消息，日志由各入口配置为 stderr。
 */
class McpProtocol(
    private val registry: McpToolRegistry = McpToolRegistry(),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun handleLine(line: String): JsonObject? {
        val element = try {
            json.parseToJsonElement(line)
        } catch (e: Exception) {
            return errorResponse(JsonNull, PARSE_ERROR, "JSON 解析失败：${e.message}")
        }
        return handleElement(element)
    }

    fun handleElement(element: JsonElement): JsonObject? {
        val message = element as? JsonObject
            ?: return errorResponse(JsonNull, INVALID_REQUEST, "请求必须是 JSON 对象")
        return handleObject(message)
    }

    /** 处理一条 JSON-RPC 消息；通知（无 id）返回 null。 */
    fun handleObject(message: JsonObject): JsonObject? {
        val id = message["id"]?.takeUnless { it is JsonNull }
        val method = (message["method"] as? JsonPrimitive)?.content
        if (method == null) {
            return if (id == null) {
                null
            } else {
                errorResponse(id, INVALID_REQUEST, "缺少字符串字段 method")
            }
        }

        // 通知不需要响应；当前只处理 initialized，其余安全忽略。
        if (id == null) return null

        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            when (method) {
                "initialize" -> successResponse(id, initialize(params))
                "ping" -> successResponse(id, JsonObject(emptyMap()))
                "tools/list" -> successResponse(id, listTools())
                "tools/call" -> callTool(id, params)
                "logging/setLevel" -> successResponse(id, JsonObject(emptyMap()))
                else -> errorResponse(id, METHOD_NOT_FOUND, "未知方法：$method")
            }
        } catch (e: Exception) {
            Log.getLogger("mcp").error("处理 $method 失败", e)
            errorResponse(id, INTERNAL_ERROR, "服务器内部错误：${e.message ?: e::class.simpleName}")
        }
    }

    fun toolDefinitions(): List<JsonObject> = registry.definitions

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.content
        val protocolVersion = requested
            ?.takeIf { it in SUPPORTED_PROTOCOL_VERSIONS }
            ?: DEFAULT_PROTOCOL_VERSION
        return buildJsonObject {
            put("protocolVersion", protocolVersion)
            putJsonObject("capabilities") {
                putJsonObject("tools") {
                    put("listChanged", false)
                }
            }
            putJsonObject("serverInfo") {
                put("name", SERVER_NAME)
                put("version", SERVER_VERSION)
            }
            put(
                "instructions",
                "CS-ModEditor-Next 编辑器内嵌的 MCP 服务。典型流程：open_pack 加载游戏数据包 → " +
                    "open_project 打开 Mod 工程 → list_objects/get_object 浏览 → " +
                    "get_schema 确认字段 → set_field/set_reference 修改（会立即反映到编辑器）→ " +
                    "validate_project 校验 → save_project 落盘。",
            )
        }
    }

    private fun listTools(): JsonObject = buildJsonObject {
        put(
            "tools",
            buildJsonArray {
                registry.definitions.forEach { add(it) }
            },
        )
    }

    private fun callTool(id: JsonElement, params: JsonObject): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.content
            ?: return errorResponse(id, INVALID_PARAMS, "缺少参数：name")
        val tool = registry.find(name)
            ?: return errorResponse(id, INVALID_PARAMS, "未知工具：$name")
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val result = tool.execute(arguments)
        return successResponse(
            id,
            buildJsonObject {
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", result.text)
                            },
                        )
                    },
                )
                if (result.isError) put("isError", true)
            },
        )
    }

    private fun successResponse(id: JsonElement, result: JsonElement): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

    private fun errorResponse(id: JsonElement, code: Int, message: String): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }

    companion object {
        const val SERVER_NAME: String = "cs-modeditor"
        const val SERVER_VERSION: String = "0.1.0"
        const val DEFAULT_PROTOCOL_VERSION: String = "2025-06-18"
        val SUPPORTED_PROTOCOL_VERSIONS: Set<String> = setOf(
            "2024-11-05",
            "2025-03-26",
            "2025-06-18",
            "2025-11-25",
        )

        const val PARSE_ERROR: Int = -32700
        const val INVALID_REQUEST: Int = -32600
        const val METHOD_NOT_FOUND: Int = -32601
        const val INVALID_PARAMS: Int = -32602
        const val INTERNAL_ERROR: Int = -32603
    }
}

/**
 * stdio 传输：换行分隔的 JSON-RPC（NDJSON）。主要用于测试与无界面调试。
 */
class McpServer(
    input: InputStream = System.`in`,
    output: OutputStream = System.out,
    private val protocol: McpProtocol = McpProtocol(),
) {

    private val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
    private val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))

    fun run() {
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val response = handleMessage(line) ?: continue
            writeMessage(response)
        }
    }

    fun handleMessage(line: String): JsonObject? = protocol.handleLine(line)

    private fun writeMessage(message: JsonObject) {
        writer.write(message.toString())
        writer.newLine()
        writer.flush()
    }
}
