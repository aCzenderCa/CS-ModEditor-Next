package org.zender.editor.project

import org.zender.editor.fixture.TestFixtures
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.localization.CsvParser
import org.zender.editor.localization.LocalizationEntry
import org.zender.editor.localization.LocalizationFileFormat
import org.zender.editor.localization.LocalizationFileSpec
import org.zender.editor.localization.LocalizationLanguage
import org.zender.editor.localization.LocalizationTable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class ProjectLocalizationFilesTest {

    private val fileStore = AtomicFileStore()
    private val tempDirectories = mutableListOf<Path>()

    private fun temp(): Path =
        createTempDirectory("csmode-localization-").also { tempDirectories.add(it) }

    @AfterTest
    fun cleanup() {
        tempDirectories.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `读取 ML 三列本地化并合并两个文件`() {
        val root = TestFixtures.sampleMod("ml-sample")

        val result = ProjectLocalizationFiles.readMl(fileStore, root)

        assertEquals(3, result.localization.table.size)
        assertEquals(
            "Fixture Knife",
            assertNotNull(result.localization.entry("FixtureML_FixtureKnife_CardName")).english,
        )
        assertEquals(
            "测试小刀",
            assertNotNull(result.localization.entry("FixtureML_FixtureKnife_CardName")).chinese,
        )
        assertEquals(
            listOf("Localization/SimpCn.csv", "Localization/SimpEn.csv"),
            result.localization.files.map { it.relativePath },
        )
        assertTrue(result.localization.files.all { it.format == LocalizationFileFormat.THREE_COLUMN })
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.joinToString())
    }

    @Test
    fun `读取 MC 按语言本地化并取最后一列`() {
        val root = TestFixtures.sampleMod("mc-sample")

        val result = ProjectLocalizationFiles.readMc(fileStore, root)
        val entry = assertNotNull(result.localization.entry("FixtureMc_FixtureKnife_CardName"))

        assertEquals("MC Fixture Knife", entry.english)
        assertEquals("MC 测试小刀", entry.chinese)
        assertEquals(
            listOf("Resource/Localization/En.csv", "Resource/Localization/SimpCn.csv"),
            result.localization.files.map { it.relativePath },
        )
        assertTrue(result.localization.files.all { it.format == LocalizationFileFormat.KEY_VALUE })
    }

    @Test
    fun `MC 未知语言文件原样保留`() {
        val root = temp()
        val file = root.resolve("Resource/Localization/Ja.csv")
        Files.createDirectories(file.parent)
        Files.writeString(file, "key1,テスト\n")

        val read = ProjectLocalizationFiles.readMc(fileStore, root)
        assertEquals(mapOf("key1" to "テスト"), read.localization.extraTexts["Resource/Localization/Ja.csv"])

        val written = ProjectLocalizationFiles.writeMc(read.localization, read.localization.files)
        assertEquals("key1,テスト\n", written["Resource/Localization/Ja.csv"])
    }

    @Test
    fun `MC 文件可选表头`() {
        val root = temp()
        val file = root.resolve("Resource/Localization/En.csv")
        Files.createDirectories(file.parent)
        Files.writeString(file, "Key,English\nk1,first\nk1,second\n")

        val result = ProjectLocalizationFiles.readMc(fileStore, root)

        assertEquals("first", assertNotNull(result.localization.entry("k1")).english)
        assertTrue(result.diagnostics.any { it.message.contains("重复") })
    }

    @Test
    fun `写出 ML 三列文件`() {
        val localization = ProjectLocalization(
            table = LocalizationTable(
                listOf(
                    LocalizationEntry("FixtureML_Knife_CardName", "Knife", "小刀"),
                    LocalizationEntry("FixtureML_Tag_InGameName", "Tag", "标签"),
                ),
            ),
        )
        val specs = listOf(
            LocalizationFileSpec(
                "Localization/SimpCn.csv",
                LocalizationLanguage.CHINESE,
                LocalizationFileFormat.THREE_COLUMN,
            ),
            LocalizationFileSpec(
                "Localization/SimpEn.csv",
                LocalizationLanguage.ENGLISH,
                LocalizationFileFormat.THREE_COLUMN,
            ),
        )

        val files = ProjectLocalizationFiles.writeMl(localization, specs)

        val cn = CsvParser.parse(assertNotNull(files["Localization/SimpCn.csv"]))
        assertEquals("LocalizationKey,English,中文", cn.rows.first().joinToString(","))
        assertEquals(listOf("FixtureML_Knife_CardName", "Knife", "小刀"), cn.rows[1])

        val en = CsvParser.parse(assertNotNull(files["Localization/SimpEn.csv"]))
        assertEquals(listOf("FixtureML_Knife_CardName", "Knife", ""), en.rows[1])
    }

    @Test
    fun `写出 MC 按语言文件`() {
        val localization = ProjectLocalization(
            table = LocalizationTable(
                listOf(
                    LocalizationEntry("FixtureMc_Knife_CardName", "Knife", "小刀"),
                    LocalizationEntry("FixtureMc_Tag_InGameName", "", "标签"),
                ),
            ),
        )
        val specs = McFormatSpecs.specs()

        val files = ProjectLocalizationFiles.writeMc(localization, specs)

        val en = CsvParser.parse(assertNotNull(files["Resource/Localization/En.csv"]))
        assertEquals(listOf(listOf("FixtureMc_Knife_CardName", "Knife")), en.rows)

        val cn = CsvParser.parse(assertNotNull(files["Resource/Localization/SimpCn.csv"]))
        assertEquals(
            listOf(
                listOf("FixtureMc_Knife_CardName", "小刀"),
                listOf("FixtureMc_Tag_InGameName", "标签"),
            ),
            cn.rows,
        )
    }

    private object McFormatSpecs {
        fun specs(): List<LocalizationFileSpec> = listOf(
            LocalizationFileSpec(
                "Resource/Localization/En.csv",
                LocalizationLanguage.ENGLISH,
                LocalizationFileFormat.KEY_VALUE,
            ),
            LocalizationFileSpec(
                "Resource/Localization/SimpCn.csv",
                LocalizationLanguage.CHINESE,
                LocalizationFileFormat.KEY_VALUE,
            ),
        )
    }
}
