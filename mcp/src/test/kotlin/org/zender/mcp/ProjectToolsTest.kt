package org.zender.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.document.readPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectToolsTest {

    @Test
    fun `create_project 创建并打开新工程`() {
        val harness = ToolHarness()
        val root = Files.createTempDirectory("csmode-mcp-create-")
        val projectRoot = root.resolve("new-mod")
        try {
            val created = harness.call(
                "create_project",
                buildJsonObject {
                    put("path", projectRoot.toString())
                    put("framework", "MC")
                    put("name", "MCP 测试 Mod")
                    put("namespace", "McpTestMod")
                    put("author", "Claude")
                    put("version", "1.2.3")
                },
            )
            assertEquals("McpTestMod", created["namespace"]!!.jsonPrimitive.content)
            assertEquals("MC", created["framework"]!!.jsonPrimitive.content)
            assertTrue(Files.isRegularFile(projectRoot.resolve("ModMeta.json")))

            val summary = harness.call("project_summary")
            assertEquals("MC", summary["framework"]!!.jsonPrimitive.content)
            assertEquals("McpTestMod", summary["namespace"]!!.jsonPrimitive.content)
            assertEquals(0, summary["counts"]!!.jsonObject["objects"]!!.jsonPrimitive.int)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `create_patch 创建 GameSourceModify 并写回`() {
        val harness = ToolHarness()
        val (_, projectRoot) = harness.openSampleProject()
        val created = harness.call(
            "create_patch",
            buildJsonObject {
                put("kind", "game_source_modify")
                put("type", "CharacterPerk")
                put("name", "MiniPerk")
                put("save", true)
            },
        )
        assertEquals("game_source_modify", created["kind"]!!.jsonPrimitive.content)
        assertEquals("22222222222222222222222222222222", created["target"]!!.jsonPrimitive.content)
        assertTrue(
            created["path"]!!.jsonPrimitive.content.endsWith(
                "GameSourceModify/22222222222222222222222222222222.json",
            ),
        )
        assertTrue(
            Files.isRegularFile(
                projectRoot.resolve("GameSourceModify/22222222222222222222222222222222.json"),
            ),
        )
        val summary = harness.call("project_summary")
        assertEquals(2, summary["counts"]!!.jsonObject["gameSourceModifies"]!!.jsonPrimitive.int)
    }

    @Test
    fun `create_patch 拒绝 ML DataObjectModify`() {
        val harness = ToolHarness()
        harness.openSampleProject()
        val error = harness.callExpectError(
            "create_patch",
            buildJsonObject {
                put("kind", "data_object_modify")
                put("type", "CardTag")
                put("name", "tag_Mini")
            },
        )
        assertTrue(error.contains("DataObjectModify"), error)
    }

    @Test
    fun `create_project 拒绝非空目录与非法框架`() {
        val harness = ToolHarness()
        val root = Files.createTempDirectory("csmode-mcp-create-fail-")
        try {
            Files.writeString(root.resolve("keep.txt"), "x")
            val nonEmpty = harness.callExpectError(
                "create_project",
                buildJsonObject {
                    put("path", root.toString())
                    put("framework", "ML")
                    put("name", "X")
                },
            )
            assertTrue(nonEmpty.contains("非空") || nonEmpty.contains("空目录"), nonEmpty)

            harness.callExpectError(
                "create_project",
                buildJsonObject {
                    put("path", root.resolve("other").toString())
                    put("framework", "XX")
                    put("name", "X")
                },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `打开工程后返回框架与对象计数`() {
        val harness = ToolHarness()
        harness.openSampleProject()

        val summary = harness.call("project_summary")

        assertEquals("ML", summary["framework"]!!.jsonPrimitive.content)
        assertEquals("FixtureML", summary["namespace"]!!.jsonPrimitive.content)
        assertEquals(2, summary["counts"]!!.jsonObject["objects"]!!.jsonPrimitive.int)
        assertEquals(1, summary["counts"]!!.jsonObject["resources"]!!.jsonPrimitive.int)
        assertEquals(3, summary["counts"]!!.jsonObject["localization"]!!.jsonPrimitive.int)
        assertTrue(summary["pack"]!!.jsonObject["name"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `list_objects 合并工程与数据包并去重`() {
        val harness = ToolHarness()
        harness.openSampleProject()

        val result = harness.call(
            "list_objects",
            buildJsonObject { put("type", "CardData") },
        )
        val names = result["objects"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

        assertTrue("FixtureKnife" in names)
        assertTrue("MiniKnife" in names)
        assertTrue(result["total"]!!.jsonPrimitive.int >= 2)
    }

    @Test
    fun `get_object 默认读取工程对象`() {
        val harness = ToolHarness()
        harness.openSampleProject()

        val result = harness.call(
            "get_object",
            buildJsonObject {
                put("type", "CardData")
                put("name", "FixtureKnife")
            },
        )

        assertEquals("project", result["source"]!!.jsonPrimitive.content)
        val cardName = result["json"]!!.jsonObject["CardName"]!!.jsonObject
        assertEquals("Fixture Knife", cardName["DefaultText"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get_object 可显式读取数据包对象`() {
        val harness = ToolHarness()
        harness.openSampleProject()

        val result = harness.call(
            "get_object",
            buildJsonObject {
                put("type", "CardData")
                put("name", "MiniKnife")
                put("source", "pack")
            },
        )

        assertEquals("pack", result["source"]!!.jsonPrimitive.content)
        assertTrue(result["path"]!!.jsonPrimitive.content.startsWith("Objects/CardData"))
    }

    @Test
    fun `get_schema 返回枚举与模板信息`() {
        val harness = ToolHarness()
        harness.openSampleProject()

        val schema = harness.call(
            "get_schema",
            buildJsonObject { put("type", "CardData") },
        )
        val fields = schema["fields"]!!.jsonArray.map { it.jsonObject }
        val cardType = assertNotNull(fields.firstOrNull { it["name"]!!.jsonPrimitive.content == "CardType" })
        assertEquals("ENUM", cardType["kind"]!!.jsonPrimitive.content)
        assertTrue(cardType["enumValues"]!!.jsonArray.isNotEmpty())
        assertNotNull(fields.firstOrNull { it["name"]!!.jsonPrimitive.content == "ObjectWeight" })
    }

    @Test
    fun `list_object_types 汇总工程与数据包类型`() {
        val harness = ToolHarness()
        harness.openSampleProject()

        val result = harness.call("list_object_types")
        val types = result["types"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }

        assertTrue("CardData" in types)
        assertTrue("CardTag" in types)
    }

    @Test
    fun `validate_project 返回问题列表`() {
        val harness = ToolHarness()
        harness.openSampleProject()

        val result = harness.call("validate_project")

        assertNotNull(result["errorCount"])
        assertNotNull(result["warningCount"])
        assertNotNull(result["issues"]!!.jsonArray)
    }

    @Test
    fun `list_localization 支持关键字过滤`() {
        val harness = ToolHarness()
        harness.openSampleProject()

        val result = harness.call(
            "list_localization",
            buildJsonObject { put("query", "FixtureKnife") },
        )

        val entries = result["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, entries.size)
        assertTrue(entries.all { it["key"]!!.jsonPrimitive.content.contains("FixtureKnife") })
    }

    @Test
    fun `list_resources 支持类型过滤`() {
        val harness = ToolHarness()
        harness.openSampleProject()

        val result = harness.call(
            "list_resources",
            buildJsonObject { put("kind", "image") },
        )

        assertEquals(1, result["total"]!!.jsonPrimitive.int)
        assertEquals("IMAGE", result["resources"]!!.jsonArray.first().jsonObject["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `set_field 修改内存中的对象并标记 dirty`() {
        val harness = ToolHarness()
        harness.openSampleProject()

        harness.call(
            "set_field",
            buildJsonObject {
                put("type", "CardData")
                put("name", "FixtureKnife")
                put("path", "ObjectWeight")
                put("value", 0.75)
            },
        )

        val summary = harness.call("project_summary")
        assertEquals("true", summary["dirty"]!!.jsonPrimitive.content)

        val objectJson = harness.call(
            "get_object",
            buildJsonObject {
                put("type", "CardData")
                put("name", "FixtureKnife")
            },
        )["json"]!!.jsonObject
        assertEquals("0.75", objectJson["ObjectWeight"]!!.jsonPrimitive.content)
    }

    @Test
    fun `set_field 拒绝不存在的字段`() {
        val harness = ToolHarness()
        harness.openSampleProject()

        val message = harness.callExpectError(
            "set_field",
            buildJsonObject {
                put("type", "CardData")
                put("name", "FixtureKnife")
                put("path", "NoSuchField")
                put("value", 1)
            },
        )

        assertTrue(message.contains("字段不存在"), message)
    }

    @Test
    fun `save_project 把修改写回磁盘`() {
        val harness = ToolHarness()
        val (_, projectRoot) = harness.openSampleProject()

        harness.call(
            "set_field",
            buildJsonObject {
                put("type", "CardData")
                put("name", "FixtureKnife")
                put("path", "ObjectWeight")
                put("value", 0.9)
                put("save", true)
            },
        )

        val file = projectRoot.resolve("CardData/FixtureKnife.json")
        val root = JsonDocument.parseOrThrow(Files.readString(file)).root
        val weight = root.readPath(JsonPath.key("ObjectWeight"))
        assertEquals("0.9", (weight as JsonNode.NumberNode).raw)
    }

    @Test
    fun `set_reference 写入列表引用的 Warp 配对`() {
        val harness = ToolHarness()
        harness.openSampleProject()

        harness.call(
            "set_reference",
            buildJsonObject {
                put("type", "CardData")
                put("name", "FixtureKnife")
                put("field", "CardTags")
                put("referenceKey", "tag_Mini")
            },
        )

        val objectJson = harness.call(
            "get_object",
            buildJsonObject {
                put("type", "CardData")
                put("name", "FixtureKnife")
            },
        )["json"]!!.jsonObject

        assertEquals(
            "tag_Mini",
            objectJson["CardTagsWarpData"]!!.jsonArray.first().jsonPrimitive.content,
        )
        assertEquals("3", objectJson["CardTagsWarpType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `clear_reference 删除 Warp 配对`() {
        val harness = ToolHarness()
        harness.openSampleProject()

        val args = buildJsonObject {
            put("type", "CardData")
            put("name", "FixtureKnife")
            put("field", "CardTags")
        }
        harness.call("set_reference", buildJsonObject {
            put("type", "CardData")
            put("name", "FixtureKnife")
            put("field", "CardTags")
            put("referenceKey", "tag_Mini")
        })
        harness.call("clear_reference", args)

        val objectJson = harness.call(
            "get_object",
            buildJsonObject {
                put("type", "CardData")
                put("name", "FixtureKnife")
            },
        )["json"]!!.jsonObject
        assertTrue("CardTagsWarpData" !in objectJson)
        assertTrue("CardTagsWarpType" !in objectJson)
    }

    @Test
    fun `create_object 从数据包模板生成新对象`() {
        val harness = ToolHarness()
        harness.openSampleProject()

        val created = harness.call(
            "create_object",
            buildJsonObject {
                put("type", "CardData")
                put("name", "NewKnife")
                put("group", "Item")
            },
        )

        assertEquals("NewKnife", created["name"]!!.jsonPrimitive.content)
        assertTrue(created["uniqueId"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(created["path"]!!.jsonPrimitive.content.endsWith("NewKnife.json"))

        val summary = harness.call("project_summary")
        assertEquals(3, summary["counts"]!!.jsonObject["objects"]!!.jsonPrimitive.int)

        val objectJson = harness.call(
            "get_object",
            buildJsonObject {
                put("type", "CardData")
                put("name", "NewKnife")
            },
        )["json"]!!.jsonObject
        assertTrue("CardName" in objectJson.keys)
        assertEquals(created["uniqueId"]!!.jsonPrimitive.content, objectJson["UniqueID"]!!.jsonPrimitive.content)
    }

    @Test
    fun `set_localization 新增条目`() {
        val harness = ToolHarness()
        harness.openSampleProject()

        harness.call(
            "set_localization",
            buildJsonObject {
                put("key", "FixtureML_New_Key")
                put("english", "New text")
                put("chinese", "新文本")
            },
        )

        val result = harness.call(
            "list_localization",
            buildJsonObject { put("query", "FixtureML_New_Key") },
        )
        val entry = result["entries"]!!.jsonArray.single().jsonObject
        assertEquals("New text", entry["english"]!!.jsonPrimitive.content)
        assertEquals("新文本", entry["chinese"]!!.jsonPrimitive.content)
    }

    @Test
    fun `import_resource 复制图片到工程目录`() {
        val harness = ToolHarness()
        val (_, projectRoot) = harness.openSampleProject()
        val source = Files.createTempFile("fixture-import-", ".png")
        Files.write(source, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

        val imported = harness.call(
            "import_resource",
            buildJsonObject {
                put("sourcePath", source.toString())
                put("key", "fixture_import")
            },
        )

        assertEquals("IMAGE", imported["kind"]!!.jsonPrimitive.content)
        assertEquals("fixture_import", imported["key"]!!.jsonPrimitive.content)
        assertTrue(Files.isRegularFile(projectRoot.resolve(imported["path"]!!.jsonPrimitive.content)))
    }

    @Test
    fun `未打开工程时工具返回可读错误`() {
        val harness = ToolHarness()

        val message = harness.callExpectError("validate_project")

        assertTrue(message.contains("尚未打开工程"), message)
    }
}
