package org.zender.app.workspace

import kotlinx.coroutines.runBlocking
import org.zender.app.ui.theme.ThemeMode
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.document.readPath
import org.zender.editor.edit.WarpFields
import org.zender.editor.model.EntryOrigin
import org.zender.editor.objects.ReferenceKind
import org.zender.editor.picker.PickerItem
import org.zender.editor.settings.FavoriteField
import org.zender.editor.window.DocumentViewMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceControllerTest {

    private fun withFixture(block: suspend (WorkspaceTestFixture) -> Unit) {
        val fixture = WorkspaceTestFixture.create()
        try {
            runBlocking { block(fixture) }
        } finally {
            fixture.close()
        }
    }

    private suspend fun openedController(fixture: WorkspaceTestFixture): WorkspaceController {
        val controller = fixture.controller()
        controller.loadPack(fixture.packRoot)
        controller.loadProject(fixture.projectRoot)
        assertNotNull(controller.state.value.pack)
        assertNotNull(controller.state.value.project)
        assertTrue(controller.openObject("CardData", "ProjectCard"))
        return controller
    }

    @Test
    fun `字段编辑写入文档并保存到工程`() {
        withFixture { fixture ->
            val controller = openedController(fixture)
            val id = WorkspaceController.objectDocumentId("CardData", "ProjectCard")
            val path = JsonPath.parse("ObjectWeight")
            val info = assertNotNull(controller.fieldInfo(id, path))
            assertTrue(controller.setFieldText(id, path, "0.9", info))

            val document = assertNotNull(controller.state.value.session.documents[id]).document
            val value = (document.root.readPath(path) as JsonNode.NumberNode).toDouble()
            assertEquals(0.9, value)
            assertTrue(controller.state.value.session.documents.getValue(id).isDirty)

            assertTrue(controller.saveProject())
            assertFalse(controller.state.value.session.documents.getValue(id).isDirty)
            val text = Files.readString(fixture.projectRoot.resolve("CardData/ProjectCard.json"))
            assertTrue(text.contains("\"ObjectWeight\": 0.9"), text)
        }
    }

    @Test
    fun `非法原始 JSON 保留字段视图并阻止切换`() {
        withFixture { fixture ->
            val controller = openedController(fixture)
            val id = WorkspaceController.objectDocumentId("CardData", "ProjectCard")
            val before = controller.state.value.session.documents.getValue(id).document

            assertTrue(controller.setViewMode(id, DocumentViewMode.RAW))
            controller.updateRawText(id, "{ broken json")
            assertFalse(controller.state.value.rawDocuments.getValue(id).isValid)
            assertEquals(
                before.root,
                controller.state.value.session.documents.getValue(id).document.root,
            )
            assertFalse(controller.setViewMode(id, DocumentViewMode.FIELD))
            assertTrue(controller.state.value.statusIsError)
        }
    }

    @Test
    fun `原始 JSON 编辑同步字段视图并原样保存`() {
        withFixture { fixture ->
            val controller = openedController(fixture)
            val id = WorkspaceController.objectDocumentId("CardData", "ProjectCard")
            val rawText = """{
  "CardName": {"ParentObjectID": "44444444444444444444444444444444", "LocalizationKey": "TestMod_ProjectCard_CardName", "DefaultText": "Raw Edited"},
  "CardType": 0,
  "ObjectWeight": 0.6,
  "CardTags": [],
  "UniqueID": "44444444444444444444444444444444"
}"""
            assertTrue(controller.setViewMode(id, DocumentViewMode.RAW))
            controller.updateRawText(id, rawText)
            assertTrue(controller.state.value.rawDocuments.getValue(id).isValid)

            val document = controller.state.value.session.documents.getValue(id).document
            val defaultText = (document.root.readPath(JsonPath.parse("CardName.DefaultText")) as JsonNode.StringNode)
            assertEquals("Raw Edited", defaultText.value)

            assertTrue(controller.saveProject())
            assertEquals(rawText, Files.readString(fixture.projectRoot.resolve("CardData/ProjectCard.json")))
        }
    }

    @Test
    fun `引用列表通过选择器添加与删除`() {
        withFixture { fixture ->
            val controller = openedController(fixture)
            val id = WorkspaceController.objectDocumentId("CardData", "ProjectCard")
            val listPath = JsonPath.parse("CardTags")
            val info = assertNotNull(controller.fieldInfo(id, listPath))
            assertTrue(info.isReferenceList)

            controller.openPicker(id, listPath, "CardTag", isList = true)
            val item = PickerItem(
                type = "CardTag",
                name = "tag_Test",
                referenceKind = ReferenceKind.PLAIN_OBJECT,
                origin = EntryOrigin.BASE_PACK,
            )
            assertTrue(controller.applyPickerItem(item))
            val document = controller.state.value.session.documents.getValue(id).document
            val data = document.root.readPath(WarpFields.dataPath(listPath)) as JsonNode.ArrayNode
            assertEquals(listOf("tag_Test"), data.elements.map { (it as JsonNode.StringNode).value })
            val warpType = document.root.readPath(WarpFields.typePath(listPath)) as JsonNode.NumberNode
            assertEquals(3L, warpType.toLongOrNull())

            assertTrue(controller.removeReference(id, listPath, 0))
            val cleared = controller.state.value.session.documents.getValue(id).document
            val empty = cleared.root.readPath(WarpFields.dataPath(listPath)) as JsonNode.ArrayNode
            assertTrue(empty.elements.isEmpty())
        }
    }

    @Test
    fun `收藏字段立即持久化并在新会话恢复`() {
        withFixture { fixture ->
            val controller = openedController(fixture)
            val favorite = FavoriteField("CardData", "ObjectWeight")
            assertTrue(controller.toggleFavorite(favorite.type, favorite.fieldPath))
            assertTrue(favorite in controller.state.value.session.favorites)
            assertTrue(favorite in fixture.settingsStore().load().favoriteFields)

            val second = fixture.controller()
            assertTrue(favorite in second.state.value.session.favorites)
        }
    }

    @Test
    fun `主题模式持久化`() {
        withFixture { fixture ->
            val controller = fixture.controller()
            controller.setThemeMode(ThemeMode.DARK)
            assertEquals("DARK", controller.state.value.settings.themeMode)
            assertEquals("DARK", fixture.settingsStore().load().themeMode)
        }
    }

    @Test
    fun `已分离窗口记录内容并持久化几何`() {
        withFixture { fixture ->
            val controller = openedController(fixture)
            val id = WorkspaceController.objectDocumentId("CardData", "ProjectCard")
            assertTrue(controller.openInNewWindow(id))
            val window = controller.state.value.detachedWindows.single()
            assertEquals(id, window.documentId)
            assertTrue(fixture.settingsStore().load().detachedWindows.any { it.documentId == id.value })

            controller.closeDetachedWindow(window.id)
            assertTrue(controller.state.value.detachedWindows.isEmpty())
            assertTrue(fixture.settingsStore().load().detachedWindows.isEmpty())
        }
    }

    @Test
    fun `工程校验无错误并可导出到目标目录`() {
        withFixture { fixture ->
            val controller = openedController(fixture)
            val issues = controller.validateProject()
            assertTrue(issues.none { it.isError }, issues.joinToString("\n"))

            val target = fixture.root.resolve("export")
            assertTrue(controller.exportTo(target))
            assertTrue(Files.isRegularFile(target.resolve("ModInfo.json")))
            assertTrue(Files.isRegularFile(target.resolve("CardData/ProjectCard.json")))
            assertTrue(Files.isRegularFile(target.resolve("Localization/SimpCn.csv")))
        }
    }

    @Test
    fun `文件树按类型分组并支持搜索`() {
        withFixture { fixture ->
            val controller = openedController(fixture)
            val state = controller.state.value
            val tree = org.zender.app.ui.workspace.FileTreeBuilder.build(state.project, state.pack)
            assertTrue(tree.any { it.id == "metadata" })
            val cardGroup = tree.first { it.id == "group:uid:CardData" }
            assertTrue(cardGroup.children.any { it.name == "ProjectCard" })
            assertTrue(tree.any { it.id == "group:gsm" })

            val filtered = org.zender.app.ui.workspace.FileTreeBuilder.build(
                state.project,
                state.pack,
                org.zender.app.ui.workspace.FileTreeFilter(keyword = "ProjectCard"),
            )
            assertTrue(
                filtered.any { node -> node.children.any { it.name == "ProjectCard" } },
            )
            val patches = org.zender.app.ui.workspace.FileTreeBuilder.build(
                state.project,
                state.pack,
                org.zender.app.ui.workspace.FileTreeFilter(patchesOnly = true),
            )
            assertFalse(patches.any { it.id == "metadata" })
            assertTrue(patches.any { it.id == "group:gsm" })
        }
    }
}
