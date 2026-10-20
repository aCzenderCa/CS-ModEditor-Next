package org.zender.editor.objects

import org.zender.editor.fixture.TestFixtures
import org.zender.editor.io.AtomicFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class ReferenceIndexTest {

    private val fileStore = AtomicFileStore()
    private val packRoot = TestFixtures.miniPack()

    private val objectIndex: ObjectIndex by lazy {
        val scan = ObjectScanner.scan(fileStore, packRoot)
        val guid = ObjectIndexReader.readGuidIndex(fileStore, packRoot)
        val name = ObjectIndexReader.readNameIndex(fileStore, packRoot)
        ObjectIndexBuilder.build(
            scanned = scan.entries,
            guidEntries = guid.entries,
            nameEntries = name.entries,
            guidTypes = guid.types,
            nameTypes = name.types,
        ).index
    }

    private val rule: ReferenceRule by lazy { ReferenceRule.from(objectIndex) }

    private val index: ReferenceIndex by lazy { ReferenceIndex(objectIndex, rule) }

    @Test
    fun `按 GUID 解析 UniqueID 对象`() {
        val result = index.resolveUniqueId("CardData", "11111111111111111111111111111111")

        val resolved = assertIs<ReferenceResolution.Resolved>(result)
        assertEquals("MiniKnife", resolved.entry.name)
    }

    @Test
    fun `按对象名解析无 GUID 对象`() {
        val result = index.resolveName("CardTag", "tag_Mini")

        val resolved = assertIs<ReferenceResolution.Resolved>(result)
        assertEquals("tag_Mini", resolved.entry.name)
    }

    @Test
    fun `基类字段跨类型解析对象名`() {
        val result = index.resolveName("ScriptableObject", "tag_Mini")

        val resolved = assertIs<ReferenceResolution.Resolved>(result)
        assertEquals("CardTag", resolved.entry.type)
    }

    @Test
    fun `原始字符串按字段语义区分 GUID 与对象名`() {
        assertIs<ReferenceResolution.Resolved>(
            index.resolveRaw("CardData", "11111111111111111111111111111111"),
        )
        assertIs<ReferenceResolution.Resolved>(index.resolveRaw("CardTag", "tag_Mini"))
        assertIs<ReferenceResolution.NotFound>(index.resolveRaw("CardTag", "tag_Missing"))
    }

    @Test
    fun `缺失目标返回 NotFound`() {
        val result = index.resolveUniqueId("CardData", "missing")

        val notFound = assertIs<ReferenceResolution.NotFound>(result)
        assertEquals("CardData", notFound.type)
        assertEquals("missing", notFound.key)
    }

    @Test
    fun `类型不兼容返回 TypeMismatch`() {
        val result = index.resolve(
            fieldType = "CardTag",
            target = ReferenceTarget.Name("CardData", "MiniKnife"),
        )

        val mismatch = assertIs<ReferenceResolution.TypeMismatch>(result)
        assertEquals("CardTag", mismatch.fieldType)
        assertEquals("MiniKnife", mismatch.entry.name)
    }

    @Test
    fun `基类字段出现同名对象时返回歧义`() {
        val duplicated = ObjectIndex(
            entries = listOf(
                ObjectEntry(ObjectKey.of("CardTag", "same"), displayName = "标签"),
                ObjectEntry(ObjectKey.of("EquipmentTag", "same"), displayName = "装备标签"),
            ),
            nameIndexTypes = setOf("CardTag", "EquipmentTag"),
        )
        val duplicatedIndex = ReferenceIndex(duplicated, ReferenceRule.from(duplicated))

        val result = duplicatedIndex.resolveName("ScriptableObject", "same")

        val ambiguous = assertIs<ReferenceResolution.Ambiguous>(result)
        assertEquals(2, ambiguous.candidates.size)
    }

    @Test
    fun `UniqueIDScriptable 基类只接受带 GUID 的目标`() {
        assertIs<ReferenceResolution.Resolved>(
            index.resolveName("UniqueIDScriptable", "MiniKnife"),
        )
        assertIs<ReferenceResolution.NotFound>(
            index.resolveName("UniqueIDScriptable", "tag_Mini"),
        )
        assertIs<ReferenceResolution.TypeMismatch>(
            index.resolve(
                fieldType = "UniqueIDScriptable",
                target = ReferenceTarget.Name("CardTag", "tag_Mini"),
            ),
        )
    }

    @Test
    fun `候选按字段类型返回`() {
        val cardData = index.candidates("CardData")
        val scriptableObjects = index.candidates("ScriptableObject")

        assertEquals(listOf("MiniKnife"), cardData.map { it.name })
        assertTrue(scriptableObjects.any { it.name == "tag_Mini" })
        assertTrue(scriptableObjects.any { it.name == "MiniKnife" })
        assertTrue(index.candidates("CardTag").all { it.type == "CardTag" })
    }
}
