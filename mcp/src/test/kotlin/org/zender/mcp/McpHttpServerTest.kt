package org.zender.mcp

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpHttpServerTest {

    private val client: HttpClient = HttpClient.newHttpClient()

    private fun startServer(): McpHttpServer {
        val server = McpHttpServer(
            protocol = McpProtocol(McpToolRegistry(FileMcpWorkspace())),
            port = 0,
        )
        assertTrue(server.start(), "HTTP 服务未能启动")
        return server
    }

    private fun post(endpoint: String, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `HTTP initialize 返回协议版本与 session 头`() {
        val server = startServer()
        try {
            val response = post(
                server.endpoint,
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""",
            )

            assertEquals(200, response.statusCode())
            val result = McpJson.parseToJsonElement(response.body()).jsonObject["result"]!!.jsonObject
            assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
            assertNotNull(response.headers().firstValue("Mcp-Session-Id").orElse(null))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `HTTP 通知返回 202 且无响应体`() {
        val server = startServer()
        try {
            val response = post(
                server.endpoint,
                """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            )

            assertEquals(202, response.statusCode())
            assertTrue(response.body().isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `HTTP 工具错误以 isError 返回`() {
        val server = startServer()
        try {
            val response = post(
                server.endpoint,
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"project_summary","arguments":{}}}""",
            )

            assertEquals(200, response.statusCode())
            val result = McpJson.parseToJsonElement(response.body()).jsonObject["result"]!!.jsonObject
            assertEquals("true", result["isError"]!!.jsonPrimitive.content)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `HTTP GET 不支持并返回 405`() {
        val server = startServer()
        try {
            val request = HttpRequest.newBuilder(URI.create(server.endpoint)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(405, response.statusCode())
            assertEquals("POST", response.headers().firstValue("Allow").orElse(""))
        } finally {
            server.stop()
        }
    }
}
