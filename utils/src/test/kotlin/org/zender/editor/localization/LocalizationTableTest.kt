package org.zender.editor.localization

import org.zender.editor.fixture.TestFixtures
import org.zender.editor.io.AtomicFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class LocalizationTableTest {

    @Test
    fun `读取夹具 SimpCn 三列`() {
        val result = SimpCnLoader.load(
            AtomicFileStore(),
            TestFixtures.fixture("minipack/SimpCn.csv"),
        )

        assertEquals(5, result.table.size)
        assertEquals("Mini Knife", result.table.english("MiniKnife_CardName"))
        assertEquals("迷你小刀", result.table.chinese("MiniKnife_CardName"))
        assertEquals("迷你技能", result.table.chinese("MiniPerk_PerkName"))
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.joinToString())
    }

    @Test
    fun `没有表头时按位置读取`() {
        val result = SimpCnLoader.parse("key1,English,中文\nkey2,English2,中文2")

        assertEquals(2, result.table.size)
        assertEquals("English", result.table.english("key1"))
        assertEquals("中文2", result.table.chinese("key2"))
    }

    @Test
    fun `重复 Key 保留首次出现并记录警告`() {
        val result = SimpCnLoader.parse(
            "LocalizationKey,English,中文\nk,first,第一个\nk,second,第二个",
        )

        assertEquals("first", result.table.english("k"))
        assertEquals("第一个", result.table.chinese("k"))
        assertEquals(1, result.diagnostics.size)
        assertTrue(result.diagnostics.single().message.contains("重复"))
    }

    @Test
    fun `空 Key 与空行被跳过`() {
        val result = SimpCnLoader.parse(
            "LocalizationKey,English,中文\n,missing key,\n\nk,English,中文",
        )

        assertEquals(1, result.table.size)
        assertEquals("中文", result.table.chinese("k"))
        assertEquals(1, result.diagnostics.size)
    }

    @Test
    fun `列不足时缺失列使用空串`() {
        val result = SimpCnLoader.parse("LocalizationKey,English\nk,English only")

        assertEquals("English only", result.table.english("k"))
        assertEquals("", result.table.chinese("k"))
    }

    @Test
    fun `表头列顺序不同也能识别`() {
        val result = SimpCnLoader.parse("Key,中文,English\nk,中文1,English1")

        assertEquals("English1", result.table.english("k"))
        assertEquals("中文1", result.table.chinese("k"))
    }

    @Test
    fun `显示文本替换字面量换行`() {
        val entry = LocalizationEntry("k", "line1\\nline2", "第一行\\n第二行")

        assertEquals("line1\nline2", entry.displayText(LocalizationLanguage.ENGLISH))
        assertEquals("第一行\n第二行", entry.displayText(LocalizationLanguage.CHINESE))
    }

    @Test
    fun `不存在的 Key 返回 null`() {
        val table = LocalizationTable()

        assertEquals(null, table.entry("missing"))
        assertNotNull(LocalizationTable.EMPTY)
    }
}
