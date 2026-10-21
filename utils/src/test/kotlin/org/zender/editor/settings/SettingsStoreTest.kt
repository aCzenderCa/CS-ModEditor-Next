package org.zender.editor.settings

import org.zender.editor.io.AtomicFileStore
import org.zender.editor.log.LogLevel
import org.zender.editor.log.LogRecord
import org.zender.editor.log.LogSink
import org.zender.editor.log.SimpleLogger
import java.nio.file.Path
import java.time.Clock
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class SettingsStoreTest {

    private val fileStore = AtomicFileStore()

    private class CapturingSink : LogSink {
        val records = mutableListOf<LogRecord>()

        override fun emit(record: LogRecord) {
            records += record
        }
    }

    private fun <T> withTempDir(block: (Path) -> T): T {
        val dir = createTempDirectory("csmode-settings-")
        return try {
            block(dir)
        } finally {
            fileStore.deleteRecursively(dir)
        }
    }

    private fun storeAt(dir: Path, sink: CapturingSink = CapturingSink()): SettingsStore {
        val logger = SimpleLogger("settings-test", LogLevel.TRACE, Clock.systemUTC(), sink)
        return SettingsStore(fileStore, dir.resolve("settings.json"), logger)
    }

    @Test
    fun `文件不存在时返回默认设置`() = withTempDir { dir ->
        val settings = storeAt(dir).load()

        assertEquals(AppSettings(), settings)
        assertTrue(settings.favoriteFields.isEmpty())
    }

    @Test
    fun `设置可以完整往返`() = withTempDir { dir ->
        val store = storeAt(dir)
        val original = AppSettings(
            window = WindowSettings(x = 120f, y = 80f, width = 1600f, height = 1000f, maximized = true),
            favoriteFields = listOf(FavoriteField("CardData", "CardInteractions[0].ActionName")),
            recentDirectories = listOf("D:/packs/csti", "D:/projects/demo"),
            recentProjects = listOf("D:/projects/demo"),
            recentPacks = mapOf("csti" to "D:/packs/csti"),
            language = "zh-CN",
            fileTreeWidth = 320f,
            inspectorWidth = 360f,
        )

        store.save(original)

        assertEquals(original, store.load())
    }

    @Test
    fun `主包与额外包配置可持久化并去重`() = withTempDir { dir ->
        val store = storeAt(dir)
        val base = AppSettings()
        assertTrue(base.mainPacks.none { it.path != null })

        val main = base.withMainPackPath("csti", Path.of("D:/packs/csti"))
        assertEquals(
            Path.of("D:/packs/csti").toAbsolutePath().normalize().toString(),
            main.mainPack("CSTI")?.path,
        )

        val first = ExtraPackConfig(
            id = "p1",
            name = "额外包 A",
            path = "D:/packs/a",
            mainPackKinds = listOf("CSTI"),
            framework = "MC",
        )
        val second = ExtraPackConfig(
            id = "p2",
            name = "额外包 B",
            path = "D:/packs/b",
            mainPackKinds = listOf("CSFF"),
            framework = "ML",
        )
        val settings = main
            .withExtraPack(first)
            .withExtraPack(second)
            .withExtraPack(first.copy(name = "额外包 A2"))
        assertEquals("额外包 A2", settings.extraPacks.first { it.id == "p1" }.name)
        assertEquals(1, settings.extraPacks.count { it.id == "p1" })

        val samePath = settings.withExtraPack(
            ExtraPackConfig(
                id = "p3",
                name = "额外包 B2",
                path = "D:\\packs\\b",
                mainPackKinds = listOf("CSFF"),
                framework = "ML",
            ),
        )
        assertEquals(1, samePath.extraPacks.count { it.name.startsWith("额外包 B") })

        val multi = first.copy(mainPackKinds = listOf("CSTI", "CSFF"))
        assertTrue(multi.belongsTo("csti"))
        assertTrue(multi.belongsTo("CSFF"))

        val legacy = first.copy(mainPackKind = "CSFF")
        assertTrue(legacy.belongsTo("CSFF"))

        val target = ProjectPackTarget(
            mainPackKind = "CSTI",
            mainPackPath = "D:/packs/csti",
            framework = "MC",
            extraPackPaths = listOf("D:/packs/a"),
        )
        val withTarget = settings.withProjectPackTarget(Path.of("D:/project"), target)
        assertNotNull(withTarget.projectPackTargets.values.firstOrNull())

        store.save(settings)
        assertEquals(settings, store.load())
    }

    @Test
    fun `损坏的设置文件回退默认值并记录警告`() = withTempDir { dir ->
        val sink = CapturingSink()
        val store = storeAt(dir, sink)
        fileStore.writeText(store.path, "{ not valid json")

        val settings = store.load()

        assertEquals(AppSettings(), settings)
        assertEquals(1, sink.records.size)
        assertEquals(LogLevel.WARN, sink.records.single().level)
    }

    @Test
    fun `update 读取修改后写回`() = withTempDir { dir ->
        val store = storeAt(dir)

        val updated = store.update { it.copy(language = "en-US") }

        assertEquals("en-US", updated.language)
        assertEquals("en-US", store.load().language)
    }

    @Test
    fun `最近目录去重并置顶且限制数量`() = withTempDir { dir ->
        var settings = AppSettings()
        for (index in 0..AppSettings.MAX_RECENT_ENTRIES) {
            settings = settings.withRecentDirectory(Path.of("D:/dir-$index"))
        }
        settings = settings.withRecentDirectory(Path.of("D:/dir-5"))

        assertEquals(AppSettings.MAX_RECENT_ENTRIES, settings.recentDirectories.size)
        assertEquals(fileStore.normalize(Path.of("D:/dir-5")).toString(), settings.recentDirectories.first())
        assertEquals(1, settings.recentDirectories.count { it.endsWith("dir-5") })
    }

    @Test
    fun `收藏字段可以添加与移除`() {
        val settings = AppSettings()
            .withFavoriteField("CardData", "CardName")
            .withFavoriteField("CardData", "CardDescription")
            .withFavoriteField("CardData", "CardName")

        assertEquals(2, settings.favoriteFields.size)
        assertEquals("CardName", settings.favoriteFields.first().fieldPath)

        val removed = settings.withoutFavoriteField("CardData", "CardName")

        assertEquals(1, removed.favoriteFields.size)
        assertEquals("CardDescription", removed.favoriteFields.single().fieldPath)
    }

    @Test
    fun `收藏夹可增删且字段全局唯一`() {
        val folderA = FavoriteFolder("a", "收藏夹 A")
        val folderB = FavoriteFolder("b", "收藏夹 B")
        val settings = AppSettings()
            .withFavoriteFolder(folderA)
            .withFavoriteFolder(folderB)
            .withFavoriteField("CardData", "CardType", "a")
            .withFavoriteField("CardData", "CardType", "b")

        assertEquals(1, settings.allFavoriteFields().size)
        assertEquals(
            0,
            settings.effectiveFavoriteFolders().first { it.id == "a" }.fields.size,
        )
        assertEquals(
            1,
            settings.effectiveFavoriteFolders().first { it.id == "b" }.fields.size,
        )

        val removed = settings.withoutFavoriteFolder("b")
        assertEquals(1, removed.effectiveFavoriteFolders().size)
        assertTrue(removed.allFavoriteFields().isEmpty())
    }

    @Test
    fun `旧版收藏字段迁移为默认收藏夹`() {
        val legacy = AppSettings(
            favoriteFields = listOf(FavoriteField("CardData", "CardType")),
        )
        val folders = legacy.effectiveFavoriteFolders()
        assertEquals(1, folders.size)
        assertEquals("默认收藏", folders.single().name)
        assertEquals(1, legacy.allFavoriteFields().size)
    }

    @Test
    fun `系统属性可以覆盖设置文件位置`() {
        val previous = System.getProperty("csmode.settings.file")
        val expected = Path.of("D:/portable/settings.json").toAbsolutePath().normalize()
        try {
            System.setProperty("csmode.settings.file", expected.toString())

            val actual = SettingsLocations.defaultSettingsFile()

            assertNotNull(actual)
            assertEquals(expected, actual)
        } finally {
            if (previous == null) {
                System.clearProperty("csmode.settings.file")
            } else {
                System.setProperty("csmode.settings.file", previous)
            }
        }
    }
}
