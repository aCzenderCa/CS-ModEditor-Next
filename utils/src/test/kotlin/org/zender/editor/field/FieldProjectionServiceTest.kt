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
            FieldProjectionOptions(defaultFilter = DefaultValueFilter.ALL),
        )

        val names = result.rows.map { it.name }
        assertFalse("CardTags" in names)
        assertFalse("SpawningBlockedBy" in names)
        assertFalse("CardInteractions" in names)
        assertTrue("CardName" in names)
        assertTrue("ObjectWeight" in names)
    }

    @Test
    fun `仅过滤顶级默认值字段`() {
        val repository = SchemaRepository.fromDefinitions(
            typeStructs = mapOf(
                "Outer" to org.zender.editor.schema.TypeStructDefinition(
                    typeName = "Outer",
                    fields = listOf(
                        org.zender.editor.schema.FieldDefinition("Inner", "InnerType"),
                        org.zender.editor.schema.FieldDefinition("Other", "Int32"),
                    ),
                ),
                "InnerType" to org.zender.editor.schema.TypeStructDefinition(
                    typeName = "InnerType",
                    fields = listOf(
                        org.zender.editor.schema.FieldDefinition("Flag", "Boolean"),
                        org.zender.editor.schema.FieldDefinition("Count", "Int32"),
                    ),
                ),
            ),
            templates = mapOf(
                "Outer" to JsonDocument.parseOrThrow(
                    """{"Inner":{"Flag":false,"Count":0},"Other":0}""",
                ).root as org.zender.editor.document.JsonNode.ObjectNode,
                "InnerType" to JsonDocument.parseOrThrow(
                    """{"Flag":false,"Count":0}""",
                ).root as org.zender.editor.document.JsonNode.ObjectNode,
            ),
        )
        val service = FieldProjectionService(repository)
        val document = JsonDocument.parseOrThrow(
            """{"Inner":{"Flag":false,"Count":5},"Other":0}""",
        )

        val topLevelOnly = service.project(
            document,
            "Outer",
            FieldProjectionOptions(defaultFilter = DefaultValueFilter.TOP_LEVEL),
        )
        val names = topLevelOnly.rows.map { it.name }
        // 顶级默认字段被隐藏。
        assertFalse("Other" in names)
        // 非顶级字段中的默认子字段仍保留。
        val inner = assertNotNull(topLevelOnly.rows.find { it.name == "Inner" })
        assertTrue(inner.children.any { it.name == "Flag" })

        // 全部过滤时子字段中的默认值也会被隐藏。
        val all = service.project(
            document,
            "Outer",
            FieldProjectionOptions(defaultFilter = DefaultValueFilter.ALL),
        )
        val filteredInner = assertNotNull(all.rows.find { it.name == "Inner" })
        assertTrue(filteredInner.children.none { it.name == "Flag" })
        assertTrue(filteredInner.children.any { it.name == "Count" })
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
                defaultFilter = DefaultValueFilter.ALL,
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
            FieldProjectionOptions(defaultFilter = DefaultValueFilter.ALL),
        )

        val tags = assertNotNull(result.rows.find { it.name == "CardTags" })
        assertFalse(tags.isDefault)
        assertTrue(tags.isModified)
        assertTrue(
            result.rows.none {
                it.name.endsWith("WarpData") || it.name.endsWith("WarpType")
            },
        )
    }

    @Test
    fun `ML ModLoader 扩展字段仅在 JSON 中存在时显示`() {
        val schema = assertNotNull(repository.schema("CardData"))
        assertEquals(
            org.zender.editor.schema.FieldKind.LIST,
            assertNotNull(schema.field("MatchTagWarpData")).kind,
        )
        assertEquals(
            org.zender.editor.schema.FieldKind.BOOLEAN,
            assertNotNull(schema.field("ModLoaderSpecialOverwrite")).kind,
        )

        val without = service.project(miniKnifeDocument(), "CardData")
        assertTrue(without.rows.none { it.name == "MatchTagWarpData" })
        assertTrue(without.rows.none { it.name == "ModLoaderSpecialOverwrite" })

        val withFields = service.project(
            JsonDocument.parseOrThrow(
                """{
                  "MatchTagWarpData": ["tag_Mini"],
                  "MatchTypeWarpData": "Base",
                  "ModLoaderSpecialOverwrite": true
                }""",
            ),
            "CardData",
        )
        assertTrue(withFields.rows.any { it.name == "MatchTagWarpData" })
        assertTrue(withFields.rows.any { it.name == "MatchTypeWarpData" })
        assertTrue(withFields.rows.any { it.name == "ModLoaderSpecialOverwrite" })
    }

    @Test
    fun `图片与音频引用的 Warp 陪伴字段不再显示`() {
        val repository = SchemaRepository.fromDefinitions(
            typeStructs = mapOf(
                "TestSprite" to org.zender.editor.schema.TypeStructDefinition(
                    typeName = "TestSprite",
                    fields = listOf(
                        org.zender.editor.schema.FieldDefinition("CardImage", "Sprite"),
                        org.zender.editor.schema.FieldDefinition("WhenCreatedSounds", "AudioClip"),
                    ),
                ),
            ),
            referenceTypes = setOf("Sprite", "AudioClip"),
        )
        val service = FieldProjectionService(repository)
        val result = service.project(
            JsonDocument.parseOrThrow(
                """{
                  "CardImage": {},
                  "CardImageWarpData": "img_Card",
                  "CardImageWarpType": 3,
                  "WhenCreatedSounds": {},
                  "WhenCreatedSoundsWarpData": "snd_Card",
                  "WhenCreatedSoundsWarpType": 3
                }""",
            ),
            "TestSprite",
        )

        assertEquals(listOf("CardImage", "WhenCreatedSounds"), result.rows.map { it.name })
    }

    @Test
    fun `补丁模式展示只有 Warp 配对的引用字段`() {
        val repository = SchemaRepository.fromDefinitions(
            typeStructs = mapOf(
                "CharacterPerk" to org.zender.editor.schema.TypeStructDefinition(
                    typeName = "CharacterPerk",
                    fields = listOf(
                        org.zender.editor.schema.FieldDefinition("ContainedPerks", "PerkTag"),
                    ),
                ),
            ),
            templates = mapOf(
                "CharacterPerk" to JsonDocument.parseOrThrow(
                    """{"ContainedPerks":[]}""",
                ).root as org.zender.editor.document.JsonNode.ObjectNode,
            ),
            referenceTypes = setOf("PerkTag"),
        )
        val service = FieldProjectionService(repository)
        val document = JsonDocument.parseOrThrow(
            """{"ContainedPerksWarpData":[],"ContainedPerksWarpType":6}""",
        )

        val patch = service.project(
            document,
            "CharacterPerk",
            FieldProjectionOptions(
                defaultFilter = DefaultValueFilter.ALL,
                patchMode = true,
            ),
        )
        val row = assertNotNull(patch.rows.find { it.name == "ContainedPerks" })
        assertFalse(row.isDefault)
        assertTrue(row.isModified)

        val objectDocument = service.project(
            document,
            "CharacterPerk",
            FieldProjectionOptions(defaultFilter = DefaultValueFilter.ALL),
        )
        assertTrue(objectDocument.rows.none { it.name == "ContainedPerks" })
    }

    @Test
    fun `未激活的 DurabilityStat 视作默认值`() {
        val repository = SchemaRepository.fromDefinitions(
            typeStructs = mapOf(
                "CardData" to org.zender.editor.schema.TypeStructDefinition(
                    typeName = "CardData",
                    fields = listOf(
                        org.zender.editor.schema.FieldDefinition("SpoilageTime", "DurabilityStat"),
                    ),
                ),
                "DurabilityStat" to org.zender.editor.schema.TypeStructDefinition(
                    typeName = "DurabilityStat",
                    fields = listOf(
                        org.zender.editor.schema.FieldDefinition("Active", "Boolean"),
                        org.zender.editor.schema.FieldDefinition("MaxValue", "Single"),
                    ),
                ),
            ),
            templates = mapOf(
                "CardData" to JsonDocument.parseOrThrow(
                    """{"SpoilageTime":{"Active":false,"MaxValue":0.0}}""",
                ).root as org.zender.editor.document.JsonNode.ObjectNode,
            ),
        )
        val service = FieldProjectionService(repository)

        val inactive = service.project(
            JsonDocument.parseOrThrow(
                """{"SpoilageTime":{"Active":false,"MaxValue":42.0}}""",
            ),
            "CardData",
        ).rows.single { it.name == "SpoilageTime" }
        assertTrue(inactive.isDefault, "未激活的 DurabilityStat 应视作默认值")
        assertFalse(inactive.isModified)

        val active = service.project(
            JsonDocument.parseOrThrow(
                """{"SpoilageTime":{"Active":true,"MaxValue":42.0}}""",
            ),
            "CardData",
        ).rows.single { it.name == "SpoilageTime" }
        assertFalse(active.isDefault)
        assertTrue(active.isModified)
    }

    @Test
    fun `图片与音频字段没有引用时视作默认值`() {
        val repository = SchemaRepository.fromDefinitions(
            typeStructs = mapOf(
                "CardData" to org.zender.editor.schema.TypeStructDefinition(
                    typeName = "CardData",
                    fields = listOf(
                        org.zender.editor.schema.FieldDefinition("CardImage", "Sprite"),
                        org.zender.editor.schema.FieldDefinition("WhenCreatedSounds", "AudioClip"),
                    ),
                ),
            ),
            templates = mapOf(
                "CardData" to JsonDocument.parseOrThrow(
                    """{"CardImage":{},"WhenCreatedSounds":{}}""",
                ).root as org.zender.editor.document.JsonNode.ObjectNode,
            ),
            referenceTypes = setOf("Sprite", "AudioClip"),
        )
        val service = FieldProjectionService(repository)

        val unreferenced = service.project(
            JsonDocument.parseOrThrow(
                """{
                  "CardImage": {},
                  "CardImageWarpData": "",
                  "CardImageWarpType": 3,
                  "WhenCreatedSounds": {"m_FileID": 7}
                }""",
            ),
            "CardData",
        ).rows
        assertTrue(
            unreferenced.single { it.name == "CardImage" }.isDefault,
            "没有引用的图片字段应视作默认值",
        )
        assertTrue(
            unreferenced.single { it.name == "WhenCreatedSounds" }.isDefault,
            "没有引用的音频字段应视作默认值",
        )

        val referenced = service.project(
            JsonDocument.parseOrThrow(
                """{
                  "CardImage": {},
                  "CardImageWarpData": "img_Card",
                  "CardImageWarpType": 3
                }""",
            ),
            "CardData",
        ).rows.single { it.name == "CardImage" }
        assertFalse(referenced.isDefault)
        assertTrue(referenced.isModified)
    }

    @Test
    fun `根节点不是对象时返回问题`() {
        val result = service.project(JsonDocument.parseOrThrow("[1,2]"), "CardData")

        assertTrue(result.rows.isEmpty())
        assertEquals(1, result.issues.size)
    }
}
