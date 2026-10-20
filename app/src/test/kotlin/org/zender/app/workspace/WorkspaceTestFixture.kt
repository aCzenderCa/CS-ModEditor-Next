package org.zender.app.workspace

import org.zender.editor.io.AtomicFileStore
import org.zender.editor.settings.SettingsStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * 测试用最小 ML 工程与数据包。所有文件写入临时目录，不依赖 utils 测试资源。
 */
class WorkspaceTestFixture private constructor(
    val root: Path,
    val packRoot: Path,
    val projectRoot: Path,
    val settingsFile: Path,
) {
    fun settingsStore(): SettingsStore = SettingsStore(AtomicFileStore(), settingsFile)

    fun controller(): WorkspaceController = WorkspaceController(settingsStore())

    fun close() {
        runCatching {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    companion object {
        const val PACK_CARD_GUID = "11111111111111111111111111111111"
        const val PROJECT_CARD_GUID = "44444444444444444444444444444444"

        fun create(): WorkspaceTestFixture {
            val root = Files.createTempDirectory("csmode-workspace-test")
            val packRoot = root.resolve("pack")
            val projectRoot = root.resolve("project")
            val settingsFile = root.resolve("settings.json")
            createPack(packRoot)
            createProject(projectRoot)
            return WorkspaceTestFixture(root, packRoot, projectRoot, settingsFile)
        }

        private fun createPack(root: Path) {
            write(root, "PackInfo.json", """{"Name":"TestPack","Version":"1.0","IsMainPack":true}""")
            write(
                root,
                "TypeStruct/CardData.json",
                """{"Fields":{"CardName":"LocalizedString","CardType":"CardTypes","ObjectWeight":"Single","CardTags":"CardTag","UniqueID":"String"}}""",
            )
            write(
                root,
                "BaseJson/CardData.json",
                """{"CardName":{"ParentObjectID":"","LocalizationKey":"","DefaultText":""},"CardType":0,"ObjectWeight":0.0,"CardTags":[],"UniqueID":""}""",
            )
            write(
                root,
                "TypeStruct/LocalizedString.json",
                """{"Fields":{"ParentObjectID":"String","LocalizationKey":"String","DefaultText":"String"}}""",
            )
            write(
                root,
                "BaseJson/LocalizedString.json",
                """{"ParentObjectID":"","LocalizationKey":"","DefaultText":""}""",
            )
            write(
                root,
                "TypeStruct/CardTag.json",
                """{"Fields":{"InGameName":"LocalizedString","UniqueOnBoard":"Boolean","ActionModifiers":"String"}}""",
            )
            write(
                root,
                "BaseJson/CardTag.json",
                """{"InGameName":{"ParentObjectID":"","LocalizationKey":"","DefaultText":""},"UniqueOnBoard":false,"ActionModifiers":[]}""",
            )
            write(root, "TypeStruct/Enum/CardTypes.json", """{"Item":0,"Base":1,"Event":3}""")
            write(root, "Name/CardTag.json", """{"tag_Test(测试标签)":"tag_Test"}""")
            // 游戏本体图片通常只存在于 Name 索引，没有独立资源文件。
            write(root, "Name/Sprite.json", """{"GameIcon(游戏图标)":"GameIcon"}""")
            write(
                root,
                "Objects/CardTag/tag_Test.json",
                """{"InGameName":{"ParentObjectID":"","LocalizationKey":"tag_Test_InGameName","DefaultText":"Test Tag"},"UniqueOnBoard":false,"ActionModifiers":[]}""",
            )
            write(
                root,
                "GUID/CardData.json",
                """{"BaseCard(基础卡)":"$PACK_CARD_GUID"}""",
            )
            write(
                root,
                "Objects/CardData/Item/BaseCard.json",
                """{"CardName":{"ParentObjectID":"$PACK_CARD_GUID","LocalizationKey":"TestPack_BaseCard_CardName","DefaultText":"Base Card"},"CardType":0,"ObjectWeight":0.2,"CardTags":[],"UniqueID":"$PACK_CARD_GUID"}""",
            )
            write(
                root,
                "SimpCn.csv",
                "LocalizationKey,English,中文\nTestPack_BaseCard_CardName,Base Card,基础卡\n",
            )
        }

        private fun createProject(root: Path) {
            write(
                root,
                "ModInfo.json",
                """{"Name":"TestMod","Author":"Tester","Version":"1.0","ModLoaderVerison":"1.3.0","ModEditorVersion":"0.1.0"}""",
            )
            write(
                root,
                "CardData/ProjectCard.json",
                """{
  "CardName": {"ParentObjectID": "$PROJECT_CARD_GUID", "LocalizationKey": "TestMod_ProjectCard_CardName", "DefaultText": "Project Card"},
  "CardType": 0,
  "ObjectWeight": 0.5,
  "CardTags": [],
  "UniqueID": "$PROJECT_CARD_GUID"
}""",
            )
            write(
                root,
                "GameSourceModify/$PACK_CARD_GUID.json",
                """{"ObjectWeight":0.75}""",
            )
            write(
                root,
                "Localization/SimpCn.csv",
                "LocalizationKey,English,中文\nTestMod_ProjectCard_CardName,Project Card,工程卡\n",
            )
            write(
                root,
                "Localization/SimpEn.csv",
                "LocalizationKey,English,中文\nTestMod_ProjectCard_CardName,Project Card,工程卡\n",
            )
        }

        private fun write(root: Path, relative: String, content: String) {
            val path = root.resolve(relative)
            Files.createDirectories(path.parent)
            Files.writeString(path, content)
        }
    }
}
