package org.zender.editor.pack

import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.document.JsonDocument
import org.zender.editor.fixture.TestFixtures
import org.zender.editor.objects.ReferenceResolution
import org.zender.editor.schema.FieldKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class DataPackLoaderTest {

    private val loader = DataPackLoader()
    private val tempDirectories = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempDirectories.forEach { it.toFile().deleteRecursively() }
    }

    private fun copyMiniPack(): Path {
        val destination = createTempDirectory("csmode-pack-")
        tempDirectories.add(destination)
        val source = TestFixtures.miniPack()
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                val target = destination.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        return destination
    }

    @Test
    fun `加载迷你数据包`() {
        val result = loader.load(TestFixtures.miniPack())

        assertTrue(result.isSuccess, result.diagnostics.joinToString())
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.joinToString())
        val pack = result.packOrThrow()

        assertEquals("MiniPack", pack.info.name)
        assertEquals("0.0.1-fixture", pack.info.version)
        assertTrue(pack.info.isMainPack)
        assertContains(pack.typeNames, "CardData")
        assertEquals(3, pack.objectIndex.entries.size)
        assertEquals(5, pack.localization.size)
        assertEquals(3, pack.picker.query().total)
    }

    @Test
    fun `按对象读取并往返序列化`() {
        val pack = loader.load(TestFixtures.miniPack()).packOrThrow()
        val entry = assertNotNull(pack.objectEntry("CardData", "MiniKnife"))

        val document = pack.readObject(entry)
        val compact = document.toJsonText()
        val pretty = document.toJsonText(org.zender.editor.document.JsonFormat.Pretty())
        val reparsedCompact = JsonDocument.parseOrThrow(compact)
        val reparsedPretty = JsonDocument.parseOrThrow(pretty)

        assertEquals(document.root, reparsedCompact.root)
        assertEquals(document.root, reparsedPretty.root)
    }

    @Test
    fun `缺少 PackInfo 时加载失败`() {
        val destination = copyMiniPack()
        Files.delete(destination.resolve("PackInfo.json"))

        val result = loader.load(destination)

        assertFalse(result.isSuccess)
        assertTrue(result.errors.any { it.message.contains("PackInfo") })
    }

    @Test
    fun `单个 TypeStruct 损坏时继续加载其他类型`() {
        val destination = copyMiniPack()
        Files.writeString(destination.resolve("TypeStruct/CardData.json"), """{"Fields":""")

        val result = loader.load(destination)

        assertTrue(result.isSuccess, result.diagnostics.joinToString())
        val pack = result.packOrThrow()
        assertTrue(result.diagnostics.any { it.severity == DiagnosticSeverity.ERROR })
        assertNull(pack.schema("CardData"))
        assertNotNull(pack.schema("CardTag"))
        assertNotNull(pack.objectEntry("CardData", "MiniKnife"))
    }

    @Test
    fun `引用解析与 Schema 在加载后可用`() {
        val pack = loader.load(TestFixtures.miniPack()).packOrThrow()

        val resolved = pack.referenceIndex.resolveUniqueId(
            "CardData",
            "11111111111111111111111111111111",
        )
        assertIs<ReferenceResolution.Resolved>(resolved)
        assertEquals("MiniKnife", resolved.entry.name)

        val schema = assertNotNull(pack.schema("CardData"))
        assertEquals(FieldKind.REFERENCE, schema.field("CardTags")?.elementKind)
        assertEquals("卡片名字", schema.field("CardName")?.documentation?.chinese?.note)
    }

    @Test
    fun `真实 CSTI 数据包可加载`() {
        val root = TestFixtures.cstiPack ?: return

        val pack = loader.load(root).packOrThrow()

        assertEquals("CSTI", pack.info.name)
        assertTrue(pack.objectIndex.entries.size > 1000)
        assertTrue(pack.localization.size > 10000)
        val cardData = assertNotNull(pack.schema("CardData"))
        assertEquals(FieldKind.REFERENCE, cardData.field("CardTags")?.elementKind)
        assertEquals(FieldKind.ENUM, cardData.field("CardType")?.kind)
        assertTrue((cardData.field("CardType")?.enumValues?.size ?: 0) > 10)
        assertEquals("卡片名字", cardData.field("CardName")?.documentation?.chinese?.note)
        assertTrue(pack.objectIndex.guidIndexTypes.contains("CardData"))
        assertTrue(pack.objectIndex.nameIndexTypes.contains("CardTag"))
    }

    @Test
    fun `真实 CSFF 数据包可加载`() {
        val root = TestFixtures.csffPack ?: return

        val pack = loader.load(root).packOrThrow()

        assertEquals("CSFF", pack.info.name)
        assertTrue(pack.objectIndex.entries.size > 3000)
        assertTrue(pack.localization.size > 20000)
        assertNotNull(pack.schema("CardData"))
        assertTrue(pack.typeNames.size > 400)
    }
}
