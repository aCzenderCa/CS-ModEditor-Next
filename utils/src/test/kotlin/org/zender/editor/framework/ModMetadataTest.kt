package org.zender.editor.framework

import org.zender.editor.document.JsonNode
import org.zender.editor.fixture.TestFixtures
import org.zender.editor.io.AtomicFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ModMetadataTest {

    private val fileStore = AtomicFileStore()

    private fun read(relative: String): String =
        fileStore.readText(TestFixtures.fixture("mods/$relative"))

    @Test
    fun `解析 ML ModInfo`() {
        val result = MlFormat.readMetadata(read("ml-sample/ModInfo.json"))

        val metadata = assertNotNull(result.metadata)
        assertEquals(FrameworkId.ML, metadata.framework)
        assertEquals("FixtureML", metadata.namespace)
        assertEquals("FixtureML", metadata.names[ModMetadata.DEFAULT_LANGUAGE])
        assertEquals("Fixture", metadata.author)
        assertEquals("1.0.0", metadata.version)
        assertEquals("1.3.0", metadata.modLoaderVersion)
        assertEquals("0.1.0", metadata.modEditorVersion)
        assertTrue(metadata.raw?.contains("CustomMeta") == true)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.joinToString())
    }

    @Test
    fun `解析 MC ModMeta`() {
        val result = McFormat.readMetadata(read("mc-sample/ModMeta.json"))

        val metadata = assertNotNull(result.metadata)
        assertEquals(FrameworkId.MC, metadata.framework)
        assertEquals("FixtureMc", metadata.namespace)
        assertEquals("Fixture MC", metadata.names["En"])
        assertEquals("MC 测试 Mod", metadata.names["SimpCn"])
        assertEquals("一个 MC 测试 Mod。", metadata.descriptions["SimpCn"])
        assertEquals("1.0.0", metadata.version)
        assertEquals("", metadata.icon)
        assertTrue(metadata.pluginGuids.isEmpty())
        assertTrue(metadata.raw?.contains("CustomMeta") == true)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.joinToString())
    }

    @Test
    fun `展示名优先中文`() {
        val metadata = assertNotNull(
            McFormat.readMetadata(read("mc-sample/ModMeta.json")).metadata,
        )

        assertEquals("MC 测试 Mod", metadata.displayName())
        assertEquals("Fixture MC", metadata.displayName(preferredKeys = listOf("En")))
    }

    @Test
    fun `缺少必需字段时解析失败`() {
        val ml = MlFormat.readMetadata("""{"Author":"x"}""")
        val mc = McFormat.readMetadata("""{"Name":{"En":"x"}}""")

        assertNull(ml.metadata)
        assertNull(mc.metadata)
        assertTrue(ml.diagnostics.any { it.isError })
        assertTrue(mc.diagnostics.any { it.isError })
    }

    @Test
    fun `非法 MC 命名空间记录警告但保留元数据`() {
        val result = McFormat.readMetadata(
            """{"Namespace":"Bad:Name","Name":{"En":"Bad"}}""",
        )

        assertNotNull(result.metadata)
        assertTrue(result.diagnostics.any { it.message.contains("非法字符") })
    }

    @Test
    fun `ML 元数据写回保留未知字段并更新已知字段`() {
        val metadata = assertNotNull(
            MlFormat.readMetadata(read("ml-sample/ModInfo.json")).metadata,
        ).copy(version = "2.0.0")

        val text = MlFormat.writeMetadata(metadata)
        val reparsed = assertNotNull(MlFormat.readMetadata(text).metadata)
        val root = assertNotNull(reparsed.raw)

        assertEquals("2.0.0", reparsed.version)
        assertEquals("FixtureML", reparsed.namespace)
        assertTrue(root.contains("CustomMeta"))
        assertEquals(
            JsonNode.BooleanNode.TRUE,
            ((root.get("CustomMeta") as JsonNode.ObjectNode).get("Keep")),
        )
    }

    @Test
    fun `清空 ML 可选版本字段会从文件中移除`() {
        val metadata = assertNotNull(
            MlFormat.readMetadata(read("ml-sample/ModInfo.json")).metadata,
        ).copy(modLoaderVersion = null, modEditorVersion = null)

        val text = MlFormat.writeMetadata(metadata)
        assertTrue(!text.contains("ModLoaderVerison"), text)
        assertTrue(!text.contains("ModLoaderVersion"), text)
        assertTrue(!text.contains("ModEditorVersion"), text)
        assertTrue(text.contains("CustomMeta"), text)
    }

    @Test
    fun `MC 删除语言后写回不再保留该语言`() {
        val metadata = assertNotNull(
            McFormat.readMetadata(read("mc-sample/ModMeta.json")).metadata,
        ).copy(names = mapOf("SimpCn" to "仅中文"))

        val reparsed = assertNotNull(
            McFormat.readMetadata(McFormat.writeMetadata(metadata)).metadata,
        )
        assertEquals(mapOf("SimpCn" to "仅中文"), reparsed.names)
    }

    @Test
    fun `MC 元数据写回保留未知字段与未知语言`() {
        val metadata = assertNotNull(
            McFormat.readMetadata(read("mc-sample/ModMeta.json")).metadata,
        ).copy(
            names = mapOf("En" to "Renamed", "SimpCn" to "改名", "Ja" to "テスト"),
            version = "2.0.0",
        )

        val text = McFormat.writeMetadata(metadata)
        val reparsed = assertNotNull(McFormat.readMetadata(text).metadata)
        val root = assertNotNull(reparsed.raw)

        assertEquals("Renamed", reparsed.names["En"])
        assertEquals("改名", reparsed.names["SimpCn"])
        assertEquals("テスト", reparsed.names["Ja"])
        assertEquals("2.0.0", reparsed.version)
        assertTrue(root.contains("CustomPluginField"))
        assertTrue(root.contains("CustomMeta"))
    }
}
