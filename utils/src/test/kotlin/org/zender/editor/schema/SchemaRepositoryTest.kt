package org.zender.editor.schema

import org.zender.editor.fixture.TestFixtures
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.document.JsonNode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class SchemaRepositoryTest {

    private fun loadRepository(): SchemaRepository {
        val result = SchemaRepository.load(
            fileStore = AtomicFileStore(),
            packRoot = TestFixtures.miniPack(),
            referenceTypes = setOf("CardData", "CharacterPerk", "CardTag"),
        )
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.joinToString())
        return result.repository
    }

    @Test
    fun `读取 TypeStruct BaseJson Doc 与枚举`() {
        val repository = loadRepository()

        assertContains(repository.typeNames, "CardData")
        assertContains(repository.typeNames, "LocalizedString")
        assertContains(repository.typeNames, "CardOnCardAction")
        assertContains(repository.enumNames, "CardTypes")
        assertEquals(
            listOf("Item", "Base", "Location", "Event"),
            repository.enumValues("CardTypes").take(4).map { it.name },
        )
    }

    @Test
    fun `CardData 字段种类按模板形状推断`() {
        val schema = assertNotNull(loadRepository().schema("CardData"))

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
            schema.fields.map { it.name },
        )
        assertEquals(FieldKind.OBJECT, schema.field("CardName")?.kind)
        assertEquals(FieldKind.ENUM, schema.field("CardType")?.kind)
        assertEquals(FieldKind.FLOAT, schema.field("ObjectWeight")?.kind)
        assertEquals(FieldKind.STRING, schema.field("UniqueID")?.kind)

        val tags = assertNotNull(schema.field("CardTags"))
        assertEquals(FieldKind.LIST, tags.kind)
        assertEquals("CardTag", tags.elementTypeName)
        assertEquals(FieldKind.REFERENCE, tags.elementKind)

        val blocked = assertNotNull(schema.field("SpawningBlockedBy"))
        assertEquals(FieldKind.REFERENCE, blocked.elementKind)

        val interactions = assertNotNull(schema.field("CardInteractions"))
        assertEquals("CardOnCardAction", interactions.elementTypeName)
        assertEquals(FieldKind.OBJECT, interactions.elementKind)
    }

    @Test
    fun `枚举字段带取值`() {
        val schema = assertNotNull(loadRepository().schema("CardData"))
        val cardType = assertNotNull(schema.field("CardType"))

        assertEquals(12, cardType.enumValues.size)
        assertEquals(JsonNode.NumberNode("0"), cardType.enumValues.first().value)
        assertEquals("Item", cardType.enumValues.first().name)
    }

    @Test
    fun `Docs 合并中英文说明`() {
        val schema = assertNotNull(loadRepository().schema("CardData"))
        val cardName = assertNotNull(schema.field("CardName"))

        assertEquals("卡片名字", cardName.documentation.chinese?.note)
        assertEquals("Card name", cardName.documentation.english?.note)
        assertEquals("卡片名字", cardName.note())
        assertEquals("Card name", cardName.note(useEnglish = true))
    }

    @Test
    fun `LocalizedString 自动字段只读`() {
        val schema = assertNotNull(loadRepository().schema("LocalizedString"))

        assertTrue(schema.field("ParentObjectID")?.isReadOnly == true)
        assertTrue(schema.field("LocalizationKey")?.isReadOnly == true)
        assertTrue(schema.field("DefaultText")?.isReadOnly == false)
    }

    @Test
    fun `缺少声明类型的字段回退为未知`() {
        val schema = assertNotNull(loadRepository().schema("CardTag"))
        val modifiers = assertNotNull(schema.field("ActionModifiers"))

        assertEquals(FieldKind.LIST, modifiers.kind)
        assertEquals("ActionModifier", modifiers.elementTypeName)
        assertEquals(FieldKind.UNKNOWN, modifiers.elementKind)
    }

    @Test
    fun `模板值随字段保存`() {
        val schema = assertNotNull(loadRepository().schema("CardData"))

        assertEquals(JsonNode.NumberNode("0.0"), schema.field("ObjectWeight")?.templateValue)
        assertTrue(schema.field("CardTags")?.templateValue is JsonNode.ArrayNode)
        assertTrue(schema.field("CardName")?.templateValue is JsonNode.ObjectNode)
    }
}
