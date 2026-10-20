package org.zender.editor.field

import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonPath
import org.zender.editor.fixture.TestFixtures
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.schema.SchemaRepository
import org.zender.editor.settings.FavoriteField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class FieldProjectionServiceTest {

    private val repository = SchemaRepository.load(
        fileStore = AtomicFileStore(),
        packRoot = TestFixtures.miniPack(),
        referenceTypes = setOf("CardData", "CharacterPerk", "CardTag"),
    ).repository

    private val service = FieldProjectionService(repository)

    private fun miniKnifeDocument(): JsonDocument =
        JsonDocument.parseOrThrow(
            AtomicFileStore().readText(
                TestFixtures.fixture("minipack/Objects/CardData/Item/MiniKnife.json"),
            ),
        )

    @Test
    fun `按 Schema 顺序投影并标记默认与修改`() {
        val result = service.project(miniKnifeDocument(), "CardData")

        assertEquals(
            listOf(
                "CardName",
                "CardDescription",
                "CardType",
                "ObjectWeight",
                "CardTags",
                "SpawningBlockedBy",
                "CardInteractions",
                "UniqueID",
            ),
            result.rows.map { it.name },
        )
        assertTrue(result.issues.isEmpty(), result.issues.joinToString())

        val cardType = assertNotNull(result.rows.find { it.name == "CardType" })
        assertFalse(cardType.isDefault)
        assertTrue(cardType.isModified)

        val tags = assertNotNull(result.rows.find { it.name == "CardTags" })
        assertTrue(tags.isDefault)
        assertFalse(tags.isModified)
    }

    @Test
    fun `嵌套对象展开为子行并标记只读字段`() {
        val result = service.project(miniKnifeDocument(), "CardData")
        val cardName = assertNotNull(result.rows.find { it.name == "CardName" })

        assertEquals("LocalizedString", cardName.typeName)
        assertEquals(
            listOf("ParentObjectID", "LocalizationKey", "DefaultText"),
            cardName.children.map { it.name },
        )
        assertTrue(cardName.children.single { it.name == "ParentObjectID" }.isReadOnly)
        assertTrue(cardName.children.single { it.name == "LocalizationKey" }.isReadOnly)
        assertFalse(cardName.children.single { it.name == "DefaultText" }.isReadOnly)
        assertEquals(JsonPath.parse("CardName.DefaultText"), cardName.children[2].path)
    }

    @Test
    fun `隐藏默认值字段`() {
        val result = service.project(
            miniKnifeDocument(),
            "CardData",
            FieldProjectionOptions(includeDefaults = false),
        )

        val names = result.rows.map { it.name }
        assertFalse("CardTags" in names)
        assertFalse("SpawningBlockedBy" in names)
        assertFalse("CardInteractions" in names)
        assertTrue("CardName" in names)
        assertTrue("ObjectWeight" in names)
    }

    @Test
    fun `列表对象元素递归投影并保留未知字段`() {
        val document = JsonDocument.parseOrThrow(
            """
            {
              "CardName": {
                "ParentObjectID": "11111111111111111111111111111111",
                "LocalizationKey": "MiniKnife_CardName",
                "DefaultText": "Mini Knife"
              },
              "CardDescription": {
                "ParentObjectID": "11111111111111111111111111111111",
                "LocalizationKey": "MiniKnife_CardDescription",
                "DefaultText": "A tiny fixture knife."
              },
              "CardType": 3,
              "ObjectWeight": 0.5,
              "CardTags": [],
              "SpawningBlockedBy": [],
              "CardInteractions": [
                {
                  "ActionName": {
                    "ParentObjectID": "",
                    "LocalizationKey": "act",
                    "DefaultText": "Act"
                  },
                  "ActionValue": 2.0,
                  "Extra": "kept"
                }
              ],
              "UniqueID": "11111111111111111111111111111111",
              "Mystery": { "a": [1, 2] }
            }
            """.trimIndent(),
        )

        val result = service.project(document, "CardData")
        assertTrue(result.issues.isEmpty(), result.issues.joinToString())

        val interactions = assertNotNull(result.rows.find { it.name == "CardInteractions" })
        val element = interactions.children.single()
        assertEquals("[0]", element.name)
        assertEquals("CardOnCardAction", element.typeName)
        assertEquals(
            listOf("ActionName", "ActionValue", "Extra"),
            element.children.map { it.name },
        )
        assertTrue(element.children.single { it.name == "Extra" }.isUnknown)

        val mystery = assertNotNull(result.rows.find { it.name == "Mystery" })
        assertTrue(mystery.isUnknown)
        assertEquals(listOf("[0]", "[1]"), mystery.children.single().children.map { it.name })
    }

    @Test
    fun `未知元素类型的列表仍展开可浏览子树`() {
        val document = JsonDocument.parseOrThrow(
            """
            {
              "InGameName": {
                "ParentObjectID": "",
                "LocalizationKey": "tag_Mini_InGameName",
                "DefaultText": "Mini"
              },
              "UniqueOnBoard": false,
              "ActionModifiers": [
                { "Kind": 1, "Note": "kept" }
              ]
            }
            """.trimIndent(),
        )

        val result = service.project(document, "CardTag")
        val modifiers = assertNotNull(result.rows.find { it.name == "ActionModifiers" })
        val element = modifiers.children.single()

        assertEquals("ActionModifier", modifiers.typeName)
        assertEquals("[0]", element.name)
        assertEquals(listOf("Kind", "Note"), element.children.map { it.name })
        assertTrue(element.children.all { it.isUnknown })
    }

    @Test
    fun `没有 Schema 的类型全部按未知字段展示`() {
        val document = JsonDocument.parseOrThrow("""{"A":1,"B":{"C":true}}""")

        val result = service.project(document, "NoSuchType")

        assertEquals(1, result.issues.size)
        assertEquals(listOf("A", "B"), result.rows.map { it.name })
        assertTrue(result.rows.all { it.isUnknown })
        assertEquals(listOf("C"), result.rows[1].children.map { it.name })
    }

    @Test
    fun `收藏字段按类型与完整路径匹配`() {
        val result = service.project(
            miniKnifeDocument(),
            "CardData",
            FieldProjectionOptions(
                favorites = setOf(
                    FavoriteField("CardData", "CardType"),
                    FavoriteField("CardData", "CardName.DefaultText"),
                ),
            ),
        )

        assertTrue(assertNotNull(result.rows.find { it.name == "CardType" }).isFavorite)
        val cardName = assertNotNull(result.rows.find { it.name == "CardName" })
        assertTrue(cardName.children.single { it.name == "DefaultText" }.isFavorite)
        assertFalse(cardName.children.single { it.name == "ParentObjectID" }.isFavorite)
    }

    @Test
    fun `关闭默认值显示时收藏字段及其祖先仍然保留`() {
        val template = assertNotNull(repository.template("CardData"))
        val result = service.project(
            JsonDocument(template),
            "CardData",
            FieldProjectionOptions(
                includeDefaults = false,
                favorites = setOf(
                    FavoriteField("CardData", "CardType"),
                    FavoriteField("CardData", "CardName.DefaultText"),
                ),
                favoriteTypeName = "CardData",
            ),
        )

        assertNotNull(result.rows.find { it.name == "CardType" })
        val cardName = assertNotNull(result.rows.find { it.name == "CardName" })
        assertTrue(cardName.children.any { it.name == "DefaultText" && it.isFavorite })
        assertTrue(result.rows.none { it.name == "CardTags" })
    }

    @Test
    fun `引用列表的 WarpData 非空时即使容器为空也保留字段`() {
        val document = JsonDocument.parseOrThrow(
            """{"CardTags":[],"CardTagsWarpData":["tag_Mini"],"CardTagsWarpType":3}""",
        )
        val result = service.project(
            document,
            "CardData",
            FieldProjectionOptions(includeDefaults = false),
        )

        val tags = assertNotNull(result.rows.find { it.name == "CardTags" })
        assertFalse(tags.isDefault)
        assertTrue(tags.isModified)
    }

    @Test
    fun `根节点不是对象时返回问题`() {
        val result = service.project(JsonDocument.parseOrThrow("[1,2]"), "CardData")

        assertTrue(result.rows.isEmpty())
        assertEquals(1, result.issues.size)
    }
}
