package org.zender.editor.project

import org.zender.editor.document.JsonNode
import org.zender.editor.fixture.TestFixtures
import org.zender.editor.framework.FrameworkId
import org.zender.editor.framework.MlFormat
import org.zender.editor.framework.ModMetadata
import org.zender.editor.pack.DataPackLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectObjectFactoryTest {

    private val pack = DataPackLoader().load(TestFixtures.miniPack()).packOrThrow()

    private fun emptyProject(): ModProject = ModProject(
        root = null,
        format = MlFormat,
        metadata = ModMetadata(framework = FrameworkId.ML, namespace = "TestMod"),
        workingTree = WorkingTree(),
        basePack = pack,
    )

    @Test
    fun `新建 UniqueID 对象生成 GUID 与本地化键`() {
        val project = emptyProject()
        val result = ProjectObjectFactory(pack) { "abcdefabcdefabcdefabcdefabcdefab" }
            .create(project, NewObjectRequest(type = "CardData", name = "NewCard"))
        assertTrue(result.isSuccess, result.diagnostics.joinToString())
        val entry = assertNotNull(result.objectEntry)
        assertEquals(ProjectObjectKind.UNIQUE_ID, entry.kind)
        assertEquals("abcdefabcdefabcdefabcdefabcdefab", entry.uniqueId)
        assertEquals("CardData/Item/NewCard.json", entry.relativePath)

        val root = entry.document.root as JsonNode.ObjectNode
        assertEquals(
            "abcdefabcdefabcdefabcdefabcdefab",
            (root.get("UniqueID") as JsonNode.StringNode).value,
        )
        val cardName = root.get("CardName") as JsonNode.ObjectNode
        assertEquals(
            "abcdefabcdefabcdefabcdefabcdefab",
            (cardName.get("ParentObjectID") as JsonNode.StringNode).value,
        )
        assertEquals(
            "TestMod_NewCard_CardName",
            (cardName.get("LocalizationKey") as JsonNode.StringNode).value,
        )
    }

    @Test
    fun `新建 ScriptableObject 使用无 GUID 目录`() {
        val result = ProjectObjectFactory(pack).create(
            emptyProject(),
            NewObjectRequest(type = "CardTag", name = "NewTag"),
        )
        assertTrue(result.isSuccess, result.diagnostics.joinToString())
        val entry = assertNotNull(result.objectEntry)
        assertEquals(ProjectObjectKind.SCRIPTABLE_OBJECT, entry.kind)
        assertEquals(null, entry.uniqueId)
        assertEquals("ScriptableObject/CardTag/NewTag.json", entry.relativePath)
        val root = entry.document.root as JsonNode.ObjectNode
        val name = root.get("InGameName") as JsonNode.ObjectNode
        assertEquals("", (name.get("ParentObjectID") as JsonNode.StringNode).value)
        assertEquals("TestMod_NewTag_InGameName", (name.get("LocalizationKey") as JsonNode.StringNode).value)
    }

    @Test
    fun `从数据包对象复制时生成新 GUID 与本地化 Key`() {
        val sourceEntry = assertNotNull(pack.objectEntry("CardData", "MiniKnife"))
        val source = pack.readObject(sourceEntry)
        val result = ProjectObjectFactory(pack) { "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" }
            .create(
                emptyProject(),
                NewObjectRequest(
                    type = "CardData",
                    name = "CopiedKnife",
                    kind = ProjectObjectKind.UNIQUE_ID,
                    sourceDocument = source,
                ),
            )
        assertTrue(result.isSuccess, result.diagnostics.joinToString())
        val entry = assertNotNull(result.objectEntry)
        // MiniKnife 夹具的 CardType 为 3（Event），复制时目录必须跟随 CardType。
        assertEquals("CardData/Event/CopiedKnife.json", entry.relativePath)
        val root = entry.document.root as JsonNode.ObjectNode
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", (root.get("UniqueID") as JsonNode.StringNode).value)
        assertEquals(
            0.5,
            (root.get("ObjectWeight") as JsonNode.NumberNode).toDouble(),
        )
        val cardName = root.get("CardName") as JsonNode.ObjectNode
        assertEquals(
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            (cardName.get("ParentObjectID") as JsonNode.StringNode).value,
        )
        assertEquals(
            "TestMod_CopiedKnife_CardName",
            (cardName.get("LocalizationKey") as JsonNode.StringNode).value,
        )
    }

    @Test
    fun `重复对象名被拒绝`() {
        val first = ProjectObjectFactory(pack).create(
            emptyProject(),
            NewObjectRequest(type = "CardData", name = "Duplicate"),
        )
        val project = assertNotNull(first.project)
        val second = ProjectObjectFactory(pack).create(
            project,
            NewObjectRequest(type = "CardData", name = "Duplicate"),
        )
        assertFalse(second.isSuccess)
        assertTrue(second.diagnostics.any { it.isError })
    }

    @Test
    fun `未指定名称时自动生成唯一名称`() {
        val first = ProjectObjectFactory(pack).create(
            emptyProject(),
            NewObjectRequest(type = "CardData"),
        )
        val project = assertNotNull(first.project)
        val second = ProjectObjectFactory(pack).create(
            project,
            NewObjectRequest(type = "CardData"),
        )
        assertEquals("NewCardData", first.objectEntry?.name)
        assertEquals("NewCardData2", second.objectEntry?.name)
    }

    @Test
    fun `分组子目录写入对象路径`() {
        val result = ProjectObjectFactory(pack).create(
            emptyProject(),
            NewObjectRequest(type = "CardData", name = "GroupedCard", group = "Item"),
        )
        assertTrue(result.isSuccess, result.diagnostics.joinToString())
        assertEquals("CardData/Item/GroupedCard.json", result.objectEntry?.relativePath)
    }

    @Test
    fun `指定 CardType 分组时同步 CardType 字段`() {
        val result = ProjectObjectFactory(pack).create(
            emptyProject(),
            NewObjectRequest(type = "CardData", name = "EventCard", group = "Event"),
        )
        assertTrue(result.isSuccess, result.diagnostics.joinToString())
        val entry = assertNotNull(result.objectEntry)
        assertEquals("CardData/Event/EventCard.json", entry.relativePath)
        val root = entry.document.root as JsonNode.ObjectNode
        assertEquals(3L, (root.get("CardType") as JsonNode.NumberNode).toLongOrNull())
    }

    @Test
    fun `非法名称与冲突 GUID 被拒绝`() {
        val factory = ProjectObjectFactory(pack)
        val badName = factory.create(
            emptyProject(),
            NewObjectRequest(type = "CardData", name = "bad/name"),
        )
        assertFalse(badName.isSuccess)

        val conflict = factory.create(
            emptyProject(),
            NewObjectRequest(
                type = "CardData",
                name = "Conflict",
                uniqueId = "11111111111111111111111111111111",
            ),
        )
        assertFalse(conflict.isSuccess)
    }
}
