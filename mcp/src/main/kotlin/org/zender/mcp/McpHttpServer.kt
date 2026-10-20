package org.zender.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import org.zender.editor.log.Log
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * MCP Streamable HTTP 传输（localhost）。
 *
 * - 端点：`http://127.0.0.1:<port>/mcp`，只监听回环地址；
 * - POST 请求体为单个 JSON-RPC 对象或数组；请求返回 `application/json`；
 * - 通知与批量通知返回 202 Accepted；
 * - GET/DELETE 返回 405（本服务端不主动推送）；
 * - 不支持按会话保存状态，`Mcp-Session-Id` 仅作为标识返回。
 */
class McpHttpServer(
    private val protocol: McpProtocol,
    private val port: Int = defaultPort(),
    private val bindHost: String = DEFAULT_BIND_HOST,
) {

    private var server: HttpServer? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionId: String = UUID.randomUUID().toString()

    val endpoint: String
        get() = "http://$bindHost:${server?.address?.port ?: port}/mcp"

    /** 启动服务；端口被占用等失败返回 false，不抛异常（编辑器仍可正常使用）。 */
    fun start(): Boolean {
        if (server != null) return true
        return try {
            val http = HttpServer.create(InetSocketAddress(bindHost, port), 0)
            http.createContext("/mcp") { exchange -> handle(exchange) }
            http.executor = Executors.newFixedThreadPool(2, ThreadFactory { runnable ->
                Thread(runnable, "csmode-mcp-http").apply { isDaemon = true }
            })
            http.start()
            server = http
            Log.getLogger("mcp").info("MCP HTTP 服务已启动：$endpoint")
            true
        } catch (e: Exception) {
            Log.getLogger("mcp").warn("MCP HTTP 服务启动失败（$endpoint）：${e.message}", e)
            false
        }
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handle(exchange: HttpExchange) {
        try {
            when (exchange.requestMethod.uppercase()) {
                "POST" -> handlePost(exchange)
                "OPTIONS" -> respondEmpty(exchange, 204)
                "GET", "DELETE", "PUT", "PATCH" ->
                    respondText(exchange, 405, "Method Not Allowed", allow = "POST")
                else -> respondText(exchange, 405, "Method Not Allowed", allow = "POST")
            }
        } catch (e: Exception) {
            Log.getLogger("mcp").error("处理 MCP HTTP 请求失败", e)
            runCatching { respondText(exchange, 500, "Internal Server Error") }
        } finally {
            exchange.close()
        }
    }

    private fun handlePost(exchange: HttpExchange) {
        if (!isOriginAllowed(exchange)) {
            respondText(exchange, 403, "Origin not allowed")
            return
        }

        val body = exchange.requestBody.readNBytes(MAX_BODY_BYTES + 1)
        if (body.size > MAX_BODY_BYTES) {
            respondText(exchange, 413, "Request body too large")
            return
        }
        val text = body.toString(StandardCharsets.UTF_8).trim()
        if (text.isEmpty()) {
            respondText(exchange, 400, "Empty request body")
            return
        }

        val response: JsonElement? = try {
            when (val element = json.parseToJsonElement(text)) {
                is JsonArray -> {
                    val responses = element.mapNotNull { protocol.handleElement(it) }
                    if (responses.isEmpty()) null else JsonArray(responses)
                }

                else -> protocol.handleElement(element)
            }
        } catch (_: Exception) {
            // 交给协议层生成标准 JSON-RPC 解析错误。
            protocol.handleLine(text)
        }

        if (response == null) {
            respondEmpty(exchange, 202)
        } else {
            respondJson(exchange, 200, response)
        }
    }

    private fun isOriginAllowed(exchange: HttpExchange): Boolean {
        val origin = exchange.requestHeaders.getFirst("Origin") ?: return true
        return origin == "null" ||
            origin.startsWith("http://127.0.0.1") ||
            origin.startsWith("http://localhost") ||
            origin.startsWith("https://127.0.0.1") ||
            origin.startsWith("https://localhost")
    }

    private fun respondJson(exchange: HttpExchange, status: Int, element: JsonElement) {
        val bytes = element.toString().toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        addCommonHeaders(exchange)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respondText(
        exchange: HttpExchange,
        status: Int,
        text: String,
        allow: String? = null,
    ) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        allow?.let { exchange.responseHeaders.add("Allow", it) }
        addCommonHeaders(exchange)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respondEmpty(exchange: HttpExchange, status: Int) {
        addCommonHeaders(exchange)
        exchange.sendResponseHeaders(status, -1)
    }

    private fun addCommonHeaders(exchange: HttpExchange) {
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.responseHeaders.add("Mcp-Session-Id", sessionId)
    }

    companion object {
        const val DEFAULT_PORT: Int = 47831
        const val DEFAULT_BIND_HOST: String = "127.0.0.1"
        const val MAX_BODY_BYTES: Int = 8 * 1024 * 1024

        fun defaultPort(): Int =
            System.getenv("CSMODE_MCP_PORT")
                ?.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: DEFAULT_PORT
    }
}
