package org.zender.editor.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class DocParserTest {

    @Test
    fun `读取字段的 Alias Note Comment`() {
        val result = DocParser.parse(
            "CardData",
            """
            CardName:
              Alias: 名字
              Note: 卡片名字
              Comment: 显示在卡片标题
            CardType:
              Alias:
              Note: 卡片类型
              Comment:
            """.trimIndent(),
        )

        assertEquals("名字", result.notes.field("CardName")?.alias)
        assertEquals("卡片名字", result.notes.field("CardName")?.note)
        assertEquals("显示在卡片标题", result.notes.field("CardName")?.comment)
        assertNull(result.notes.field("CardType")?.alias)
        assertEquals("卡片类型", result.notes.field("CardType")?.note)
    }

    @Test
    fun `支持单引号值与值内逗号`() {
        val result = DocParser.parse(
            "CardData",
            """
            CardDescription:
              Note: '解锁条件提示文本,如建造木工类图纸时,需要更多的木工'
            """.trimIndent(),
        )

        assertEquals(
            "解锁条件提示文本,如建造木工类图纸时,需要更多的木工",
            result.notes.field("CardDescription")?.note,
        )
    }

    @Test
    fun `去除行尾注释`() {
        val result = DocParser.parse(
            "CardData",
            """
            CardName:
              Note: 卡片名字 # 行尾注释
            """.trimIndent(),
        )

        assertEquals("卡片名字", result.notes.field("CardName")?.note)
    }

    @Test
    fun `支持块标量`() {
        val result = DocParser.parse(
            "CardData",
            """
            CardDescription:
              Note: |
                第一行
                第二行
            """.trimIndent(),
        )

        assertEquals("第一行\n第二行", result.notes.field("CardDescription")?.note)
    }

    @Test
    fun `无法识别的行记录问题但不影响其他字段`() {
        val result = DocParser.parse(
            "CardData",
            """
            CardName:
              Note: 卡片名字
            这一行没有缩进也没有冒号
            CardType:
              Note: 卡片类型
            """.trimIndent(),
        )

        assertTrue(result.issues.isNotEmpty())
        assertEquals("卡片名字", result.notes.field("CardName")?.note)
        assertEquals("卡片类型", result.notes.field("CardType")?.note)
    }

    @Test
    fun `空文件返回空映射`() {
        val result = DocParser.parse("CardData", "")

        assertTrue(result.notes.fields.isEmpty())
    }
}
