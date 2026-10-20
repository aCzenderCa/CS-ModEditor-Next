package org.zender.editor.picker

import org.zender.editor.fixture.TestFixtures
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.model.EntryOrigin
import org.zender.editor.objects.ObjectIndex
import org.zender.editor.objects.ObjectIndexBuilder
import org.zender.editor.objects.ObjectIndexReader
import org.zender.editor.objects.ObjectScanner
import org.zender.editor.objects.ReferenceIndex
import org.zender.editor.objects.ReferenceKind
import org.zender.editor.objects.ReferenceResolution
import org.zender.editor.objects.ReferenceRule
import org.zender.editor.resource.ResourceEntry
import org.zender.editor.resource.ResourceInventory
import org.zender.editor.resource.ResourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class ReferencePickerServiceTest {

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

    private val service: ReferencePickerService by lazy {
        val resources = ResourceInventory.EMPTY
        ReferencePickerService(
            objectIndex = objectIndex,
            resources = resources,
            rule = rule,
            referenceIndex = ReferenceIndex(objectIndex, rule, resources),
        )
    }

    @Test
    fun `查询全部对象候选`() {
        val page = service.query()

        assertEquals(3, page.total)
        assertEquals(3, page.items.size)
        assertFalse(page.hasMore)
        assertTrue(page.items.all { it.origin == EntryOrigin.BASE_PACK })
    }

    @Test
    fun `按字段类型过滤`() {
        assertEquals(listOf("MiniKnife"), service.query(PickerQuery(fieldType = "CardData")).items.map { it.name })
        assertEquals(listOf("tag_Mini"), service.query(PickerQuery(fieldType = "CardTag")).items.map { it.name })
        assertEquals(3, service.query(PickerQuery(fieldType = "ScriptableObject")).total)
    }

    @Test
    fun `按引用种类过滤`() {
        val uniqueIds = service.query(PickerQuery(kind = ReferenceKind.UNIQUE_ID))

        assertEquals(2, uniqueIds.total)
        assertTrue(uniqueIds.items.all { it.referenceKind == ReferenceKind.UNIQUE_ID })
        assertTrue(uniqueIds.items.all { it.uniqueId != null })
    }

    @Test
    fun `关键字匹配对象名中文名与 GUID`() {
        assertEquals(3, service.query(PickerQuery(keyword = "迷你")).total)
        assertEquals(
            "MiniKnife",
            service.query(PickerQuery(keyword = "1111")).items.single().name,
        )
    }

    @Test
    fun `分页返回稳定顺序与剩余标记`() {
        val first = service.query(PickerQuery(limit = 1))
        val last = service.query(PickerQuery(offset = 2, limit = 1))

        assertEquals(3, first.total)
        assertEquals(1, first.items.size)
        assertTrue(first.hasMore)
        assertEquals(1, last.items.size)
        assertFalse(last.hasMore)
        assertEquals(first.items.single().name, service.query().items.first().name)
    }

    @Test
    fun `解析对象候选`() {
        val item = assertNotNull(service.query().items.find { it.name == "MiniKnife" })

        assertIs<ReferenceResolution.Resolved>(service.resolve(item))
    }

    @Test
    fun `资源候选可解析为资源引用`() {
        val resource = ResourceEntry(
            key = "spr",
            typeName = "Sprite",
            kind = ResourceKind.IMAGE,
            relativePath = "Resource/Texture2D/spr.png",
            fileName = "spr.png",
            extension = "png",
            sizeBytes = 1,
            origin = EntryOrigin.PROJECT,
        )
        val resources = ResourceInventory(listOf(resource))
        val resourceRule = ReferenceRule.of(
            mapOf(
                "CardData" to ReferenceKind.UNIQUE_ID,
                "CardTag" to ReferenceKind.PLAIN_OBJECT,
                "Sprite" to ReferenceKind.SPRITE,
                "AudioClip" to ReferenceKind.AUDIO,
            ),
        )
        val resourceIndex = ReferenceIndex(objectIndex, resourceRule, resources)
        val resourceService = ReferencePickerService(objectIndex, resources, resourceRule, resourceIndex)

        val page = resourceService.query(PickerQuery(fieldType = "Sprite"))
        val item = page.items.single()
        assertEquals(EntryOrigin.PROJECT, item.origin)
        assertEquals(ReferenceKind.SPRITE, item.referenceKind)
        val resolved = assertIs<ReferenceResolution.ResolvedResource>(resourceService.resolve(item))
        assertEquals("spr", resolved.entry.key)
    }
}
