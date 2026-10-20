package org.zender.editor.objects

import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.fixture.TestFixtures
import org.zender.editor.io.AtomicFileStore
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ObjectIndexTest {

    private val fileStore = AtomicFileStore()
    private val packRoot = TestFixtures.miniPack()

    private fun buildIndex(): ObjectIndex {
        val scan = ObjectScanner.scan(fileStore, packRoot)
        val guid = ObjectIndexReader.readGuidIndex(fileStore, packRoot)
        val name = ObjectIndexReader.readNameIndex(fileStore, packRoot)
        val build = ObjectIndexBuilder.build(
            scanned = scan.entries,
            guidEntries = guid.entries,
            nameEntries = name.entries,
            guidTypes = guid.types,
            nameTypes = name.types,
        )
        assertTrue(
            scan.diagnostics.isEmpty() && guid.diagnostics.isEmpty() && name.diagnostics.isEmpty(),
            (scan.diagnostics + guid.diagnostics + name.diagnostics).joinToString(),
        )
        return build.index
    }

    @Test
    fun `扫描 Objects 目录得到类型目录与文件名对象名`() {
        val result = ObjectScanner.scan(fileStore, packRoot)

        assertEquals(3, result.entries.size)
        val knife = assertNotNull(result.entries.find { it.name == "MiniKnife" })
        assertEquals("CardData", knife.type)
        assertEquals("Item", knife.group)
        assertEquals("Objects/CardData/Item/MiniKnife.json", knife.relativePath)
        assertTrue(knife.hasDataFile)
    }

    @Test
    fun `GUID 与 Name 索引补充唯一 ID 和中文名`() {
        val index = buildIndex()

        val knife = assertNotNull(index.find("CardData", "MiniKnife"))
        assertEquals("11111111111111111111111111111111", knife.uniqueId)
        assertEquals("迷你小刀", knife.displayName)
        assertEquals("迷你小刀", knife.displayText)

        val perk = assertNotNull(index.find("CharacterPerk", "MiniPerk"))
        assertEquals("22222222222222222222222222222222", perk.uniqueId)
        assertEquals("迷你技能", perk.displayName)

        val tag = assertNotNull(index.find("CardTag", "tag_Mini"))
        assertNull(tag.uniqueId)
        assertEquals("迷你标签", tag.displayName)
    }

    @Test
    fun `索引类型覆盖引用类型`() {
        val index = buildIndex()

        assertEquals(setOf("CardData", "CharacterPerk"), index.guidIndexTypes)
        assertEquals(setOf("CardTag"), index.nameIndexTypes)
        assertEquals(setOf("CardData", "CardTag", "CharacterPerk"), index.referenceTypes)
    }

    @Test
    fun `CardData 按 CardType 分组`() {
        val index = buildIndex()

        assertEquals(listOf("MiniKnife"), index.entriesOfCardType("Item").map { it.name })
        assertTrue(index.entriesOfCardType("Blueprint").isEmpty())
    }

    @Test
    fun `按 GUID 与对象名查询`() {
        val index = buildIndex()

        assertEquals("MiniKnife", index.findByGuid("11111111111111111111111111111111")?.name)
        assertEquals("MiniPerk", index.findByGuid("22222222222222222222222222222222")?.name)
        assertNull(index.findByGuid("not-a-guid"))
        assertEquals("tag_Mini", index.findAllByName("tag_Mini").single().name)
        assertTrue(index.entriesOfType("NoSuchType").isEmpty())
    }

    @Test
    fun `只有索引没有对象文件的条目仍然保留`() {
        val index = ObjectIndexBuilder.build(
            scanned = emptyList(),
            guidEntries = listOf(
                ObjectIndexEntry(
                    type = "CharacterPerk",
                    name = "BuiltinPerk",
                    value = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    displayName = "内置技能",
                    sourcePath = "GUID/CharacterPerk.json",
                ),
            ),
            guidTypes = setOf("CharacterPerk"),
        ).index

        val entry = assertNotNull(index.find("CharacterPerk", "BuiltinPerk"))
        assertNull(entry.relativePath)
        assertTrue(!entry.hasDataFile)
        assertEquals("内置技能", entry.displayText)
    }

    @Test
    fun `重复 GUID 记录诊断`() {
        val build = ObjectIndexBuilder.build(
            scanned = emptyList(),
            guidEntries = listOf(
                ObjectIndexEntry("CharacterPerk", "First", "dup-guid"),
                ObjectIndexEntry("CharacterPerk", "Second", "dup-guid"),
            ),
            guidTypes = setOf("CharacterPerk"),
        )

        assertTrue(build.diagnostics.any { it.message.contains("GUID 重复") })
        assertEquals(DiagnosticSeverity.WARNING, build.diagnostics.single().severity)
    }

    @Test
    fun `同一对象名出现不同 GUID 时保留首个并记录诊断`() {
        val build = ObjectIndexBuilder.build(
            scanned = listOf(
                ObjectEntry(
                    key = ObjectKey.of("CharacterPerk", "Same"),
                    relativePath = "Objects/CharacterPerk/Same.json",
                ),
            ),
            guidEntries = listOf(
                ObjectIndexEntry("CharacterPerk", "Same", "first-guid"),
                ObjectIndexEntry("CharacterPerk", "Same", "second-guid"),
            ),
            guidTypes = setOf("CharacterPerk"),
        )

        assertEquals("first-guid", build.index.find("CharacterPerk", "Same")?.uniqueId)
        assertTrue(build.diagnostics.any { it.message.contains("已有关联 GUID") })
    }

    @Test
    fun `拆分索引键中的中文名`() {
        val parsed = parseIndexKey("BedWoodenDismantled(木床（未放置）)")

        assertEquals("BedWoodenDismantled", parsed.name)
        assertEquals("木床（未放置）", parsed.displayName)
        assertEquals("plain", parseIndexKey("plain").name)
        assertNull(parseIndexKey("plain").displayName)
    }

    @Test
    fun `Name 索引读取类型与条目`() {
        val result = ObjectIndexReader.readNameIndex(fileStore, packRoot)

        assertContains(result.types, "CardTag")
        assertTrue(result.entries.isNotEmpty())
    }
}
