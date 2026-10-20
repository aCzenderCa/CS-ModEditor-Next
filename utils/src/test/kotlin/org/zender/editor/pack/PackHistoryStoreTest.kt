package org.zender.editor.pack

import org.zender.editor.fixture.TestFixtures
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.settings.AppSettings
import org.zender.editor.settings.SettingsStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PackHistoryStoreTest {

    private val tempDirectory: Path = createTempDirectory("csmode-pack-history-")
    private val settingsPath: Path = tempDirectory.resolve("settings.json")

    private fun store(clockMillis: Long = 1_000L): PackHistoryStore =
        PackHistoryStore(
            settingsStore = SettingsStore(AtomicFileStore(), settingsPath),
            clock = Clock.fixed(Instant.ofEpochMilli(clockMillis), ZoneOffset.UTC),
        )

    private fun packDirectory(relative: String): Path {
        val directory = tempDirectory.resolve(relative)
        Files.createDirectories(directory)
        return directory
    }

    @AfterTest
    fun cleanup() {
        tempDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `记录数据包目录与元信息`() {
        val root = packDirectory("packs/csti")
        val info = PackInfo(name = "CSTI", version = "1.05ae", isMainPack = true)

        val record = store().record(root, info)
        val recent = store().recent()

        assertEquals(1, recent.size)
        assertEquals(record.path, recent.single().path)
        assertEquals(root.toAbsolutePath().normalize().toString(), record.path)
        assertEquals("CSTI", record.name)
        assertEquals("1.05ae", record.version)
        assertTrue(record.isMainPack)
        assertEquals(1_000L, record.lastOpenedEpochMillis)
        assertEquals("CSTI", record.displayName)
    }

    @Test
    fun `同一路径重复记录时去重并移到最前`() {
        val first = packDirectory("packs/a")
        val second = packDirectory("packs/b")

        store(1_000L).record(first, PackInfo("A", "1"))
        store(2_000L).record(second, PackInfo("B", "1"))
        store(3_000L).record(first, PackInfo("A", "2"))

        val recent = store().recent()

        assertEquals(listOf("A", "B"), recent.map { it.name })
        assertEquals("2", recent.first().version)
        assertEquals(3_000L, recent.first().lastOpenedEpochMillis)
    }

    @Test
    fun `历史数量受上限约束`() {
        repeat(AppSettings.MAX_RECENT_PACKS + 2) { index ->
            val root = packDirectory("packs/$index")
            store(index.toLong()).record(root, PackInfo("Pack$index"))
        }

        val recent = store().recent()

        assertEquals(AppSettings.MAX_RECENT_PACKS, recent.size)
        assertEquals("Pack${AppSettings.MAX_RECENT_PACKS + 1}", recent.first().name)
        assertTrue(recent.none { it.name == "Pack0" })
    }

    @Test
    fun `移除与清空记录`() {
        val first = packDirectory("packs/a")
        val second = packDirectory("packs/b")
        store().record(first, PackInfo("A"))
        store().record(second, PackInfo("B"))

        val afterRemove = store().remove(first)
        assertEquals(listOf("B"), afterRemove.map { it.name })

        val afterClear = store().clear()
        assertTrue(afterClear.isEmpty())
        assertTrue(store().recent().isEmpty())
    }

    @Test
    fun `recordDirectory 只记录有效数据包`() {
        val invalid = packDirectory("not-a-pack")

        assertNull(store().recordDirectory(invalid))
        assertTrue(store().recent().isEmpty())

        val record = assertNotNull(store().recordDirectory(TestFixtures.miniPack()))
        assertEquals("MiniPack", record.name)
        assertEquals("0.0.1-fixture", record.version)
        assertEquals(1, store().recent().size)
    }

    @Test
    fun `pruneMissing 移除不存在或缺少 PackInfo 的目录`() {
        val valid = packDirectory("packs/valid")
        Files.writeString(valid.resolve("PackInfo.json"), """{"Name":"Valid"}""")
        val missingInfo = packDirectory("packs/no-info")
        val missingDirectory = tempDirectory.resolve("packs/gone")

        store().record(valid, PackInfo("Valid"))
        store().record(missingInfo, PackInfo("NoInfo"))
        store().record(missingDirectory, PackInfo("Gone"))

        val kept = store().pruneMissing()

        assertEquals(listOf("Valid"), kept.map { it.name })
        assertEquals(1, store().recent().size)
    }

    @Test
    fun `加载成功后自动记录数据包目录`() {
        val history = store()
        val loader = DataPackLoader(historyStore = history)

        val result = loader.load(TestFixtures.miniPack())

        assertTrue(result.isSuccess, result.diagnostics.joinToString())
        val recent = history.recent()
        assertEquals(1, recent.size)
        assertEquals("MiniPack", recent.single().name)
        assertEquals(
            TestFixtures.miniPack().toAbsolutePath().normalize().toString(),
            recent.single().path,
        )
    }

    @Test
    fun `加载失败时不记录目录`() {
        val history = store()
        val loader = DataPackLoader(historyStore = history)
        val notAPack = packDirectory("empty")

        val result = loader.load(notAPack)

        assertTrue(!result.isSuccess)
        assertTrue(history.recent().isEmpty())
    }
}
