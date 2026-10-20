package org.zender.editor.field

import org.zender.editor.document.JsonPath
import org.zender.editor.fixture.TestFixtures
import org.zender.editor.pack.DataPackLoader
import org.zender.editor.schema.FieldKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldSchemaLocatorTest {

    private val schemas = DataPackLoader().load(TestFixtures.miniPack()).packOrThrow().schemaRepository

    @Test
    fun `解析顶层标量字段`() {
        val info = FieldSchemaLocator.resolve(schemas, "CardData", JsonPath.parse("ObjectWeight"))
        assertEquals(FieldKind.FLOAT, info?.kind)
        assertEquals("Single", info?.typeName)
        assertEquals(false, info?.isElement)
    }

    @Test
    fun `解析嵌套对象字段`() {
        val info = FieldSchemaLocator.resolve(
            schemas,
            "CardData",
            JsonPath.parse("CardName.DefaultText"),
        )
        assertEquals(FieldKind.STRING, info?.kind)
        assertEquals("String", info?.schemaField?.typeName)
    }

    @Test
    fun `枚举返回枚举值`() {
        val info = FieldSchemaLocator.resolve(schemas, "CardData", JsonPath.parse("CardType"))
        assertEquals(FieldKind.ENUM, info?.kind)
        assertTrue(info!!.enumValues.any { it.name == "Item" })
    }

    @Test
    fun `引用列表标记为引用列表`() {
        val info = FieldSchemaLocator.resolve(schemas, "CardData", JsonPath.parse("CardTags"))
        assertEquals(FieldKind.LIST, info?.kind)
        assertTrue(info!!.isReferenceList)
    }

    @Test
    fun `解析列表元素与元素内字段`() {
        val element = FieldSchemaLocator.resolve(
            schemas,
            "CardData",
            JsonPath.parse("CardInteractions[0]"),
        )
        assertEquals(FieldKind.OBJECT, element?.kind)
        assertEquals(true, element?.isElement)

        val nested = FieldSchemaLocator.resolve(
            schemas,
            "CardData",
            JsonPath.parse("CardInteractions[0].ActionName.DefaultText"),
        )
        assertEquals(FieldKind.STRING, nested?.kind)
        assertEquals("String", nested?.typeName)
    }

    @Test
    fun `未知路径与错误形状返回 null`() {
        assertNull(FieldSchemaLocator.resolve(schemas, "CardData", JsonPath.parse("Nope")))
        assertNull(FieldSchemaLocator.resolve(schemas, "CardData", JsonPath.parse("ObjectWeight[0]")))
        assertNull(FieldSchemaLocator.resolve(schemas, "MissingType", JsonPath.parse("A")))
    }
}
