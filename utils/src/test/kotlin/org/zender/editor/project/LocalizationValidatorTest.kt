package org.zender.editor.project

import org.zender.editor.document.JsonDocument
import org.zender.editor.fixture.TestFixtures
import org.zender.editor.localization.LocalizationEntry
import org.zender.editor.localization.LocalizationFileFormat
import org.zender.editor.localization.LocalizationFileSpec
import org.zender.editor.localization.LocalizationLanguage
import org.zender.editor.pack.DataPackLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

internal class LocalizationValidatorTest {

    private val pack = DataPackLoader().load(TestFixtures.miniPack()).packOrThrow()
    private val validator = LocalizationValidator(pack)
    private val tempDirectories = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempDirectories.forEach { it.toFile().deleteRecursively() }
    }

    private fun mlProject(): ModProject =
        MlProjectReader().read(TestFixtures.sampleMod("ml-sample"), pack).projectOrThrow()

    private fun mcProject(): ModProject =
        McProjectReader().read(TestFixtures.sampleMod("mc-sample"), pack).projectOrThrow()

    private fun copyProject(id: String): Path {
        val source = TestFixtures.sampleMod(id)
        val target = createTempDirectory("csmode-localization-").also { tempDirectories.add(it) }
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        return target
    }

    private fun appendLine(path: Path, line: String) {
        Files.writeString(
            path,
            if (Files.exists(path)) "\n$line\n" else "$line\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun withObjectDocument(project: ModProject, text: String): ModProject {
        val entry = project.workingTree.objects.values.first { it.name == "FixtureKnife" }
        return project.copy(
            workingTree = project.workingTree.withObject(
                entry.copy(document = JsonDocument.parseOrThrow(text)),
            ),
        )
    }

    private fun addEntry(project: ModProject, entry: LocalizationEntry): ModProject {
        val localization = project.workingTree.localization.withEntry(entry)
        return project.copy(
            workingTree = project.workingTree.withLocalization(localization),
        )
    }

    private fun withFiles(
        project: ModProject,
        files: List<LocalizationFileSpec>,
    ): ModProject {
        val localization = project.workingTree.localization.withFiles(files)
        return project.copy(
            workingTree = project.workingTree.withLocalization(localization),
        )
    }

    @Test
    fun `样例工程本地化校验通过`() {
        assertTrue(validator.validate(mlProject()).isEmpty())
        assertTrue(validator.validate(mcProject()).isEmpty())
    }

    @Test
    fun `LocalizedString 引用的 Key 缺失时告警`() {
        val project = withObjectDocument(
            mlProject(),
            """{
              "UniqueID": "33333333333333333333333333333333",
              "CardName": {
                "ParentObjectID": "33333333333333333333333333333333",
                "LocalizationKey": "FixtureML_Missing",
                "DefaultText": "Missing"
              }
            }""",
        )

        val issue = validator.validate(project)
            .first { it.code == "localization.key.missing" }

        assertTrue(issue.isWarning)
        assertTrue(issue.message.contains("FixtureML_Missing"))
    }

    @Test
    fun `默认文本非空但 Key 为空时告警`() {
        val project = withObjectDocument(
            mlProject(),
            """{
              "UniqueID": "33333333333333333333333333333333",
              "CardName": {
                "ParentObjectID": "33333333333333333333333333333333",
                "LocalizationKey": "",
                "DefaultText": "Untranslated"
              }
            }""",
        )

        assertTrue(
            validator.validate(project).any { it.code == "localization.reference.blank" },
        )
    }

    @Test
    fun `未被引用的 Key 告警`() {
        val project = addEntry(
            mlProject(),
            LocalizationEntry("FixtureML_Orphan", "orphan", "孤儿"),
        )

        assertTrue(
            validator.validate(project).any { it.code == "localization.key.orphan" },
        )
    }

    @Test
    fun `Key 前缀不符合命名空间约定时告警`() {
        val project = addEntry(
            mlProject(),
            LocalizationEntry("WrongPrefix_Key", "text", "文本"),
        )

        assertTrue(
            validator.validate(project).any { it.code == "localization.key.prefix" },
        )
    }

    @Test
    fun `英文与中文都为空时告警`() {
        val project = addEntry(
            mlProject(),
            LocalizationEntry("FixtureML_Empty", "", ""),
        )

        assertTrue(
            validator.validate(project).any { it.code == "localization.text.empty" },
        )
    }

    @Test
    fun `文本未写入对应语言文件时告警`() {
        val project = addEntry(
            mlProject(),
            LocalizationEntry("FixtureML_OnlyTable", "English text", "中文文本"),
        )

        assertTrue(
            validator.validate(project).any { it.code == "localization.language.missing" },
        )
    }

    @Test
    fun `本地化文件内重复 Key 报错`() {
        val root = copyProject("ml-sample")
        appendLine(
            root.resolve("Localization/SimpCn.csv"),
            "FixtureML_FixtureKnife_CardName,duplicate,重复",
        )
        val project = MlProjectReader().read(root, pack).projectOrThrow()

        val issue = validator.validate(project)
            .first { it.code == "localization.key.duplicate" }

        assertTrue(issue.isError)
    }

    @Test
    fun `ML 三列文件列数不足告警`() {
        val root = copyProject("ml-sample")
        appendLine(
            root.resolve("Localization/SimpCn.csv"),
            "FixtureML_ShortRow,Only English",
        )
        val project = MlProjectReader().read(root, pack).projectOrThrow()

        assertTrue(
            validator.validate(project).any { it.code == "localization.row.columns" },
        )
    }

    @Test
    fun `MC 两列文件列数不足告警`() {
        val root = copyProject("mc-sample")
        appendLine(
            root.resolve("Resource/Localization/En.csv"),
            "FixtureMc_OnlyKey",
        )
        val project = McProjectReader().read(root, pack).projectOrThrow()

        assertTrue(
            validator.validate(project).any { it.code == "localization.row.columns" },
        )
    }

    @Test
    fun `没有保存目录时跳过文件级检查`() {
        val issues = validator.validate(mlProject().withRoot(null))

        assertTrue(issues.none { it.code.startsWith("localization.file.") })
    }

    @Test
    fun `本地化文件缺失时告警`() {
        val project = mlProject()
        val files = project.workingTree.localization.files + LocalizationFileSpec(
            relativePath = "Localization/DoesNotExist.csv",
            language = LocalizationLanguage.ENGLISH,
            format = LocalizationFileFormat.THREE_COLUMN,
        )

        assertTrue(
            validator.validate(withFiles(project, files))
                .any { it.code == "localization.file.missing" },
        )
    }

    @Test
    fun `本地化路径越界时报错`() {
        val project = mlProject()
        val files = project.workingTree.localization.files + LocalizationFileSpec(
            relativePath = "../outside.csv",
            language = LocalizationLanguage.ENGLISH,
            format = LocalizationFileFormat.THREE_COLUMN,
        )

        assertTrue(
            validator.validate(withFiles(project, files))
                .any { it.code == "localization.file.invalid" && it.isError },
        )
    }

    @Test
    fun `CSV 未闭合引号时报文件错误`() {
        val root = copyProject("ml-sample")
        appendLine(root.resolve("Localization/SimpCn.csv"), "\"unclosed")
        val project = MlProjectReader().read(root, pack).projectOrThrow()

        assertTrue(
            validator.validate(project)
                .any { it.code == "localization.file.invalid" && it.isError },
        )
    }

    @Test
    fun `ML 文件缺少表头时告警`() {
        val root = copyProject("ml-sample")
        val file = root.resolve("Localization/SimpEn.csv")
        val text = Files.readString(file)
        val withoutHeader = text.substringAfter('\n')
        Files.writeString(file, withoutHeader)
        val project = MlProjectReader().read(root, pack).projectOrThrow()

        assertTrue(
            validator.validate(project).any { it.code == "localization.header.missing" },
        )
    }

    @Test
    fun `空 Key 行告警`() {
        val root = copyProject("ml-sample")
        appendLine(root.resolve("Localization/SimpCn.csv"), ",English,中文")
        val project = MlProjectReader().read(root, pack).projectOrThrow()

        assertTrue(
            validator.validate(project).any { it.code == "localization.row.keyBlank" },
        )
    }

    @Test
    fun `未知语言文件仍检查重复 Key`() {
        val root = copyProject("mc-sample")
        val file = root.resolve("Resource/Localization/Fr.csv")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            "FixtureMc_FixtureKnife_CardName,Couteau\n" +
                "FixtureMc_FixtureKnife_CardName,Couteau 2\n",
        )
        val project = McProjectReader().read(root, pack).projectOrThrow()

        assertTrue(
            validator.validate(project)
                .any { it.code == "localization.key.duplicate" },
        )
    }

    @Test
    fun `extraTexts 中的 Key 不报告缺失`() {
        val project = withObjectDocument(
            mcProject(),
            """{
              "UniqueID": "44444444444444444444444444444444",
              "CardName": {
                "ParentObjectID": "44444444444444444444444444444444",
                "LocalizationKey": "FixtureMc_ExtraKey",
                "DefaultText": "Extra"
              }
            }""",
        )
        val localization = project.workingTree.localization.copy(
            extraTexts = mapOf(
                "Resource/Localization/Fr.csv" to mapOf("FixtureMc_ExtraKey" to "Extra"),
            ),
        )
        val updated = project.copy(
            workingTree = project.workingTree.withLocalization(localization),
        )

        assertTrue(
            validator.validate(updated).none {
                it.code == "localization.key.missing" &&
                    it.message.contains("FixtureMc_ExtraKey")
            },
        )
    }

    @Test
    fun `数据包已有的 Key 不报告缺失`() {
        val project = withObjectDocument(
            mlProject(),
            """{
              "UniqueID": "33333333333333333333333333333333",
              "CardName": {
                "ParentObjectID": "33333333333333333333333333333333",
                "LocalizationKey": "MiniKnife_CardName",
                "DefaultText": "Mini Knife"
              }
            }""",
        )

        assertTrue(
            validator.validate(project).none {
                it.code == "localization.key.missing" &&
                    it.message.contains("MiniKnife_CardName")
            },
        )
    }

    @Test
    fun `缺少默认语言文件时告警`() {
        val project = mlProject()
        val withoutEnglish = project.workingTree.localization.files
            .filterNot { it.relativePath.endsWith("SimpEn.csv") }

        assertTrue(
            validator.validate(withFiles(project, withoutEnglish))
                .any { it.code == "localization.file.missing" },
        )
    }
}
