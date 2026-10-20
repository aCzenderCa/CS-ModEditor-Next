package org.zender.mcp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerTest {

    private fun server(): McpServer =
        McpServer(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())

    @Test
    fun `initialize 回显协议版本并声明 tools 能力`() {
        val response = server().handleMessage(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""",
        )

        val result = assertNotNull(response)["result"]?.jsonObject
        assertNotNull(result)
        assertEquals("2025-06-18", result["protocolVersion"]?.jsonPrimitive?.content)
        assertNotNull(result["capabilities"]?.jsonObject?.get("tools"))
        assertEquals(McpProtocol.SERVER_NAME, result["serverInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `未知协议版本回退到服务端默认版本`() {
        val response = server().handleMessage(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"9999-01-01"}}""",
        )

        val result = assertNotNull(response)["result"]?.jsonObject
        assertEquals(
            McpProtocol.DEFAULT_PROTOCOL_VERSION,
            result?.get("protocolVersion")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `tools-list 返回完整工具目录`() {
        val response = server().handleMessage(
            """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""",
        )

        val tools = assertNotNull(response)["result"]?.jsonObject?.get("tools")?.jsonArray
        assertNotNull(tools)
        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertTrue("open_project" in names)
        assertTrue("open_pack" in names)
        assertTrue("set_field" in names)
        assertTrue("validate_project" in names)
        assertTrue("save_project" in names)
        tools.forEach { tool ->
            assertEquals("object", tool.jsonObject["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `通知不产生响应`() {
        assertNull(
            server().handleMessage(
                """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            ),
        )
    }

    @Test
    fun `未知方法返回 method-not-found`() {
        val response = server().handleMessage(
            """{"jsonrpc":"2.0","id":3,"method":"unknown/method"}""",
        )

        val error = assertNotNull(response)["error"]?.jsonObject
        assertEquals(McpProtocol.METHOD_NOT_FOUND, error?.get("code")?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `非法 JSON 返回解析错误`() {
        val response = server().handleMessage("{not json")

        val error = assertNotNull(response)["error"]?.jsonObject
        assertEquals(McpProtocol.PARSE_ERROR, error?.get("code")?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `未知工具返回 invalid-params`() {
        val response = server().handleMessage(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"nope","arguments":{}}}""",
        )

        val error = assertNotNull(response)["error"]?.jsonObject
        assertEquals(McpProtocol.INVALID_PARAMS, error?.get("code")?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `工具业务错误以 isError 文本返回`() {
        val response = server().handleMessage(
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"project_summary","arguments":{}}}""",
        )

        val result = assertNotNull(assertNotNull(response)["result"]?.jsonObject)
        assertEquals("true", result["isError"]?.jsonPrimitive?.content)
        val text = result["content"]?.jsonArray?.first()?.jsonObject?.get("text")?.jsonPrimitive?.content
        assertTrue(text?.contains("尚未打开工程") == true)
    }
}
