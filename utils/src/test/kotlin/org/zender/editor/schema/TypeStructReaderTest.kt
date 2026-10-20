package org.zender.editor.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TypeStructReaderTest {

    @Test
    fun `保留字段顺序与声明类型`() {
        val result = TypeStructReader.parse(
            "CardData",
            """{"Fields":{"CardName":"LocalizedString","CardType":"CardTypes","UniqueID":"String"}}""",
        )

        val definition = assertNotNull(result.definition)
        assertEquals(
            listOf("CardName", "CardType", "UniqueID"),
            definition.fields.map { it.name },
        )
        assertEquals("LocalizedString", definition.field("CardName")?.typeName)
        assertEquals("CardTypes", definition.field("CardType")?.typeName)
    }

    @Test
    fun `重复字段保留首次位置并采用最后一个类型`() {
        val result = TypeStructReader.parse(
            "CardData",
            """{"Fields":{"A":"String","B":"Int32","A":"Single"}}""",
        )

        val definition = assertNotNull(result.definition)
        assertEquals(listOf("A", "B"), definition.fields.map { it.name })
        assertEquals("Single", definition.field("A")?.typeName)
        assertTrue(result.issues.any { it.contains("重复") })
    }

    @Test
    fun `非字符串类型记录问题并跳过字段`() {
        val result = TypeStructReader.parse(
            "CardData",
            """{"Fields":{"A":1,"B":"String"}}""",
        )

        val definition = assertNotNull(result.definition)
        assertEquals(listOf("B"), definition.fields.map { it.name })
        assertTrue(result.issues.any { it.contains("A") })
    }

    @Test
    fun `缺少 Fields 时按空结构处理`() {
        val result = TypeStructReader.parse("Empty", """{}""")

        val definition = assertNotNull(result.definition)
        assertTrue(definition.fields.isEmpty())
        assertTrue(result.issues.any { it.contains("Fields") })
    }

    @Test
    fun `非法 JSON 不产生定义`() {
        val result = TypeStructReader.parse("CardData", """{"Fields": }""")

        assertNull(result.definition)
        assertTrue(result.issues.isNotEmpty())
    }
}
