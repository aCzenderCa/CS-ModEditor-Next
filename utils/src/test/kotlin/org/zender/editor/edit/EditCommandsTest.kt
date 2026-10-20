package org.zender.editor.edit

import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.document.JsonPathException
import org.zender.editor.document.jsonNumber
import org.zender.editor.document.jsonString
import org.zender.editor.document.readPath
import org.zender.editor.document.toJsonText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

internal class EditCommandsTest {

    private fun document(text: String): JsonDocument = JsonDocument.parseOrThrow(text)

    @Test
    fun `SetField 替换已有字段与追加新字段`() {
        val initial = document("""{"ObjectWeight":0.5,"Name":"x"}""")

        val replaced = SetFieldCommand(
            label = "设置重量",
            path = JsonPath.parse("ObjectWeight"),
            value = jsonNumber(1.5),
        ).execute(initial)

        assertEquals("""{"ObjectWeight":1.5,"Name":"x"}""", replaced.toJsonText())

        val appended = SetFieldCommand(
            label = "新增字段",
            path = JsonPath.parse("Extra"),
            value = jsonString("y"),
        ).execute(initial)

        assertEquals("""{"ObjectWeight":0.5,"Name":"x","Extra":"y"}""", appended.toJsonText())
    }

    @Test
    fun `SetField 命令携带合并键`() {
        val command = SetFieldCommand(
            label = "输入",
            path = JsonPath.key("Name"),
            value = jsonString("abc"),
            mergeKey = "set:doc:Name",
        )

        assertEquals("set:doc:Name", command.mergeKey)
    }

    @Test
    fun `SetField 中间对象缺失时抛异常`() {
        assertFailsWith<JsonPathException> {
            SetFieldCommand(
                label = "设置",
                path = JsonPath.parse("A.B"),
                value = jsonString("v"),
            ).execute(document("{}"))
        }
    }

    @Test
    fun `SetNestedField 自动创建中间对象`() {
        val result = SetNestedFieldCommand(
            label = "设置嵌套字段",
            path = JsonPath.parse("A.B.C"),
            value = jsonString("v"),
        ).execute(document("{}"))

        assertEquals("""{"A":{"B":{"C":"v"}}}""", result.toJsonText())
    }

    @Test
    fun `SetNestedField 保留已有兄弟字段`() {
        val result = SetNestedFieldCommand(
            label = "设置嵌套字段",
            path = JsonPath.parse("A.B"),
            value = jsonNumber(2),
        ).execute(document("""{"A":{"X":1}}"""))

        assertEquals("""{"A":{"X":1,"B":2}}""", result.toJsonText())
    }

    @Test
    fun `SetNestedField 不隐式创建数组`() {
        assertFailsWith<JsonPathException> {
            SetNestedFieldCommand(
                label = "设置",
                path = JsonPath.parse("A[0].B"),
                value = jsonString("v"),
            ).execute(document("{}"))
        }
    }

    @Test
    fun `SetReference 写入 WarpData 与 WarpType 配对`() {
        val result = SetReferenceCommand(
            label = "设置图片",
            fieldPath = JsonPath.parse("CardImage"),
            referenceKey = "BoarEnclosureFemale",
        ).execute(document("""{"CardImage":{}}"""))

        assertEquals(
            """{"CardImage":{},"CardImageWarpData":"BoarEnclosureFemale","CardImageWarpType":3}""",
            result.toJsonText(),
        )
    }

    @Test
    fun `SetReference 支持嵌套字段与自定义 WarpType`() {
        val result = SetReferenceCommand(
            label = "设置引用",
            fieldPath = JsonPath.parse("Ambience.RandomNoises"),
            referenceKey = "BoarNoise1",
            warpType = WarpType.ADD_REFERENCE,
        ).execute(document("""{"Ambience":{"RandomNoises":[]}}"""))

        val root = assertIs<JsonNode.ObjectNode>(result.root)
        val ambience = assertIs<JsonNode.ObjectNode>(root.get("Ambience"))
        assertEquals("BoarNoise1", (ambience.get("RandomNoisesWarpData") as JsonNode.StringNode).value)
        assertEquals(6L, (ambience.get("RandomNoisesWarpType") as JsonNode.NumberNode).toLongOrNull())
    }

    @Test
    fun `SetReference 拒绝空引用键`() {
        assertFailsWith<IllegalArgumentException> {
            SetReferenceCommand(
                label = "设置引用",
                fieldPath = JsonPath.parse("CardImage"),
                referenceKey = "  ",
            )
        }
    }

    @Test
    fun `ClearReference 删除配对键但保留字段本体`() {
        val initial = document(
            """{"CardImage":{},"CardImageWarpData":"Boar","CardImageWarpType":3}""",
        )

        val result = ClearReferenceCommand(
            label = "清除引用",
            fieldPath = JsonPath.parse("CardImage"),
        ).execute(initial)

        assertEquals("""{"CardImage":{}}""", result.toJsonText())
        assertNull(result.root.readPath(JsonPath.parse("CardImageWarpData")))
    }

    @Test
    fun `ClearReference 在配对键不存在时是空操作`() {
        val initial = document("""{"CardImage":{}}""")

        val result = ClearReferenceCommand(
            label = "清除引用",
            fieldPath = JsonPath.parse("CardImage"),
        ).execute(initial)

        assertEquals(initial, result)
    }

    @Test
    fun `AddReferenceToList 创建并追加 WarpData`() {
        val result = AddReferenceToListCommand(
            label = "添加标签",
            listFieldPath = JsonPath.parse("CardTags"),
            referenceKey = "tag_Boar",
        ).execute(document("""{"CardTags":[]}"""))

        assertEquals(
            """{"CardTags":[],"CardTagsWarpData":["tag_Boar"],"CardTagsWarpType":3}""",
            result.toJsonText(),
        )
    }

    @Test
    fun `AddReferenceToList 按索引插入并更新 WarpType`() {
        val initial = document(
            """{"CardTags":[],"CardTagsWarpData":["b","c"],"CardTagsWarpType":3}""",
        )

        val result = AddReferenceToListCommand(
            label = "插入标签",
            listFieldPath = JsonPath.parse("CardTags"),
            referenceKey = "a",
            warpType = WarpType.ADD_REFERENCE,
            index = 0,
        ).execute(initial)

        assertEquals(
            """{"CardTags":[],"CardTagsWarpData":["a","b","c"],"CardTagsWarpType":6}""",
            result.toJsonText(),
        )
    }

    @Test
    fun `AddReferenceToList 在非数组 WarpData 上抛异常`() {
        assertFailsWith<JsonPathException> {
            AddReferenceToListCommand(
                label = "添加标签",
                listFieldPath = JsonPath.parse("CardTags"),
                referenceKey = "tag",
            ).execute(document("""{"CardTags":[],"CardTagsWarpData":"tag"}"""))
        }
    }

    @Test
    fun `AddInlineElement 追加与插入数组元素`() {
        val appended = AddInlineElementCommand(
            label = "追加互动",
            arrayPath = JsonPath.parse("CardInteractions"),
            element = JsonDocument.parseOrThrow("""{"ActionValue":1}""").root,
        ).execute(document("""{"CardInteractions":[]}"""))
        assertEquals(
            """{"CardInteractions":[{"ActionValue":1}]}""",
            appended.toJsonText(),
        )

        val inserted = AddInlineElementCommand(
            label = "插入互动",
            arrayPath = JsonPath.parse("Values"),
            element = jsonNumber(1),
            index = 0,
        ).execute(document("""{"Values":[2,3]}"""))
        assertEquals("""{"Values":[1,2,3]}""", inserted.toJsonText())
    }

    @Test
    fun `RemoveElement 删除数组元素`() {
        val result = RemoveElementCommand(
            label = "删除标签",
            arrayPath = JsonPath.parse("CardTagsWarpData"),
            index = 1,
        ).execute(document("""{"CardTagsWarpData":["a","b","c"]}"""))

        assertEquals("""{"CardTagsWarpData":["a","c"]}""", result.toJsonText())
    }

    @Test
    fun `MoveElement 移动数组元素`() {
        val command = MoveElementCommand(
            label = "移动标签",
            arrayPath = JsonPath.parse("CardTagsWarpData"),
            fromIndex = 0,
            toIndex = 2,
        )

        val result = command.execute(document("""{"CardTagsWarpData":["a","b","c"]}"""))

        assertEquals("""{"CardTagsWarpData":["b","c","a"]}""", result.toJsonText())
    }

    @Test
    fun `MoveElement 相同下标是空操作`() {
        val initial = document("""{"Values":[1,2]}""")
        val result = MoveElementCommand(
            label = "移动",
            arrayPath = JsonPath.parse("Values"),
            fromIndex = 0,
            toIndex = 0,
        ).execute(initial)

        assertEquals(initial, result)
    }

    @Test
    fun `数组命令越界时抛异常`() {
        assertFailsWith<JsonPathException> {
            RemoveElementCommand("删除", JsonPath.key("Values"), 5)
                .execute(document("""{"Values":[1]}"""))
        }
        assertFailsWith<JsonPathException> {
            MoveElementCommand("移动", JsonPath.key("Values"), 0, 5)
                .execute(document("""{"Values":[1]}"""))
        }
    }

    @Test
    fun `命令栈可以撤销与重做引用编辑`() {
        val stack = CommandStack(document("""{"CardTags":[]}"""))
        val command = AddReferenceToListCommand(
            label = "添加标签",
            listFieldPath = JsonPath.parse("CardTags"),
            referenceKey = "tag_Boar",
        )

        stack.execute(command)
        assertTrue(stack.current.toJsonText().contains("CardTagsWarpData"))

        stack.undo()
        assertEquals(document("""{"CardTags":[]}"""), stack.current)

        stack.redo()
        assertTrue(stack.current.toJsonText().contains("tag_Boar"))
    }

    @Test
    fun `WarpFields 计算配对路径与基本名`() {
        val fieldPath = JsonPath.parse("A.B")

        assertEquals("A.BWarpData", WarpFields.dataPath(fieldPath).format())
        assertEquals("A.BWarpType", WarpFields.typePath(fieldPath).format())
        assertEquals("B", WarpFields.baseNameOrNull("BWarpData"))
        assertEquals("B", WarpFields.baseNameOrNull("BWarpType"))
        assertNull(WarpFields.baseNameOrNull("B"))
    }

    @Test
    fun `WarpType 与数值互转`() {
        assertEquals(WarpType.REFERENCE, WarpType.fromValue(3))
        assertEquals(WarpType.ADD_REFERENCE, WarpType.fromNode(jsonNumber(6)))
        assertNull(WarpType.fromValue(7))
        assertNull(WarpType.fromNode(null))
        assertNull(WarpType.fromNode(jsonString("3")))
        assertNull(WarpType.fromNode(jsonNumber(4294967299L)))
    }

    @Test
    fun `命令构造参数非法时拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            SetFieldCommand("设置", JsonPath.ROOT, jsonString("v"))
        }
        assertFailsWith<IllegalArgumentException> {
            SetFieldCommand("设置", JsonPath.index(0), jsonString("v"))
        }
        assertFailsWith<IllegalArgumentException> {
            SetNestedFieldCommand("设置", JsonPath.ROOT, jsonString("v"))
        }
        assertFailsWith<IllegalArgumentException> {
            SetReferenceCommand("设置", JsonPath.index(0), "key")
        }
        assertFailsWith<IllegalArgumentException> {
            SetReferenceCommand("设置", JsonPath.key("A"), "key", WarpType.NONE)
        }
        assertFailsWith<IllegalArgumentException> {
            ClearReferenceCommand("清除", JsonPath.parse("A[0]"))
        }
        assertFailsWith<IllegalArgumentException> {
            AddReferenceToListCommand("添加", JsonPath.ROOT, "key")
        }
        assertFailsWith<IllegalArgumentException> {
            AddReferenceToListCommand("添加", JsonPath.key("A"), "  ")
        }
        assertFailsWith<IllegalArgumentException> {
            AddReferenceToListCommand("添加", JsonPath.key("A"), "key", WarpType.CUSTOM)
        }
        assertFailsWith<IllegalArgumentException> {
            AddReferenceToListCommand("添加", JsonPath.key("A"), "key", index = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            AddInlineElementCommand("添加", JsonPath.ROOT, jsonNumber(1))
        }
        assertFailsWith<IllegalArgumentException> {
            AddInlineElementCommand("添加", JsonPath.key("A"), jsonNumber(1), index = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            RemoveElementCommand("删除", JsonPath.ROOT, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            RemoveElementCommand("删除", JsonPath.key("A"), -1)
        }
        assertFailsWith<IllegalArgumentException> {
            MoveElementCommand("移动", JsonPath.ROOT, 0, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            MoveElementCommand("移动", JsonPath.key("A"), -1, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            MoveElementCommand("移动", JsonPath.key("A"), 0, -1)
        }
    }

    @Test
    fun `WarpFields 拒绝根路径与数组下标路径`() {
        assertFailsWith<IllegalArgumentException> {
            WarpFields.dataPath(JsonPath.ROOT)
        }
        assertFailsWith<JsonPathException> {
            WarpFields.typePath(JsonPath.index(0))
        }
        assertNull(WarpFields.baseNameOrNull("WarpData"))
        assertNull(WarpFields.baseNameOrNull("WarpType"))
        assertTrue(!WarpFields.isWarpDataName("WarpData"))
        assertTrue(!WarpFields.isWarpTypeName("WarpType"))
    }

    @Test
    fun `嵌套写入遇到非对象或非数组父节点时报错`() {
        assertFailsWith<JsonPathException> {
            SetNestedFieldCommand("设置", JsonPath.parse("A.B"), jsonString("v"))
                .execute(document("""{"A":1}"""))
        }
        assertFailsWith<JsonPathException> {
            SetNestedFieldCommand("设置", JsonPath.parse("A[0]"), jsonString("v"))
                .execute(document("""{"A":1}"""))
        }
        assertFailsWith<JsonPathException> {
            SetNestedFieldCommand("设置", JsonPath.parse("A[0]"), jsonString("v"))
                .execute(document("""{"A":[]}"""))
        }
    }

    @Test
    fun `数组命令处理越界与非数组路径`() {
        assertFailsWith<JsonPathException> {
            AddInlineElementCommand("添加", JsonPath.key("Values"), jsonNumber(2), index = 2)
                .execute(document("""{"Values":[1]}"""))
        }
        assertFailsWith<JsonPathException> {
            AddInlineElementCommand("添加", JsonPath.key("Missing"), jsonNumber(2))
                .execute(document("""{}"""))
        }
        assertFailsWith<JsonPathException> {
            AddInlineElementCommand("添加", JsonPath.key("Values"), jsonNumber(2))
                .execute(document("""{"Values":1}"""))
        }
        assertFailsWith<JsonPathException> {
            AddReferenceToListCommand("添加", JsonPath.key("Tags"), "tag", index = 2)
                .execute(document("""{"Tags":[],"TagsWarpData":["a"]}"""))
        }
        assertFailsWith<JsonPathException> {
            RemoveElementCommand("删除", JsonPath.key("Values"), 0)
                .execute(document("""{"Values":1}"""))
        }
        assertFailsWith<JsonPathException> {
            MoveElementCommand("移动", JsonPath.key("Values"), 0, 1)
                .execute(document("""{"Values":1}"""))
        }
    }
}
