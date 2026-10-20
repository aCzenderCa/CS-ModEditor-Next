package org.zender.app.workspace

import kotlinx.coroutines.runBlocking
import org.zender.app.ui.theme.ThemeMode
import org.zender.editor.document.DocumentId
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.document.readPath
import org.zender.editor.edit.WarpFields
import org.zender.editor.graph.GraphNode
import org.zender.editor.graph.GraphNodeOrigin
import org.zender.editor.model.EntryOrigin
import org.zender.editor.objects.ObjectKey
import org.zender.editor.objects.ReferenceKind
import org.zender.editor.project.ProjectObjectKind
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
    fun `新建对象进入工程并打开文档`() {
        withFixture { fixture ->
            val controller = openedController(fixture)
            val created = assertNotNull(
                controller.createProjectObject(
                    type = "CardData",
                    name = "CreatedCard",
                    kind = ProjectObjectKind.UNIQUE_ID,
                ),
            )
            assertEquals("CreatedCard", created.name)
            assertNotNull(controller.state.value.project?.objectOf("CardData", "CreatedCard"))
            assertTrue(controller.state.value.session.projectDirty)

            val id = WorkspaceController.objectDocumentId("CardData", "CreatedCard")
            assertNotNull(controller.state.value.session.documents[id])

            assertTrue(controller.saveProject())
            assertTrue(Files.isRegularFile(fixture.projectRoot.resolve("CardData/Item/CreatedCard.json")))
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
    fun `资源选择器包含 Name 索引中的内置图片并可按裸名称解析`() {
        withFixture { fixture ->
            val controller = openedController(fixture)
            val request = PickerRequest(
                kind = PickerWindowKind.RESOURCE,
                documentId = DocumentId("test-document"),
                fieldPath = JsonPath.parse("CardImage"),
                fieldType = "Sprite",
                isList = false,
            )
            val items = controller.pickerItems(request)
            val icon = assertNotNull(items.firstOrNull { it.name == "GameIcon" })
            assertEquals(EntryOrigin.BASE_PACK, icon.origin)
            assertEquals(ReferenceKind.SPRITE, icon.referenceKind)

            val display = controller.previewPickerItem(icon, "Sprite")
            assertTrue(display.resolved, display.status)
            assertEquals("GameIcon", display.key)
        }
    }

    @Test
    fun `星图节点可写入最近聚焦的引用列表`() {
        withFixture { fixture ->
            val controller = openedController(fixture)
            val id = WorkspaceController.objectDocumentId("CardData", "ProjectCard")
            val listPath = JsonPath.parse("CardTags")
            controller.selectField(id, listPath, reference = true)
            val ref = assertNotNull(controller.state.value.session.selection.lastFocusedReferenceField)
            val node = GraphNode(
                key = ObjectKey.of("CardTag", "tag_Test"),
                type = "CardTag",
                displayName = "tag_Test",
                origin = GraphNodeOrigin.BASE_TARGET,
            )
            assertTrue(controller.applyGraphReference(node, ref))
            val document = controller.state.value.session.documents.getValue(id).document
            val data = document.root.readPath(WarpFields.dataPath(listPath)) as JsonNode.ArrayNode
            assertEquals(
                listOf("tag_Test"),
                data.elements.map { (it as JsonNode.StringNode).value },
            )
        }
    }

    @Test
    fun `引用目标可打开到新 Tab 并在星图定位`() {
        withFixture { fixture ->
            val controller = openedController(fixture)
            val before = controller.state.value.session.documentOrder.size

            assertTrue(controller.openReferenceInTab("CardTag", "tag_Test"))
            assertEquals(before + 1, controller.state.value.session.documentOrder.size)

            assertTrue(controller.focusReferenceInStarMap("CardTag", "tag_Test"))
            assertEquals(WorkspaceScreen.STAR_MAP, controller.state.value.screen)
            assertEquals(
                ObjectKey.of("CardTag", "tag_Test"),
                controller.state.value.starMapFocus?.key,
            )
            val nonce = controller.state.value.starMapFocus?.nonce
            assertTrue(controller.focusReferenceInStarMap("CardTag", "tag_Test"))
            assertTrue(controller.state.value.starMapFocus?.nonce != nonce)
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
    fun `工程与数据包目录关联后再次打开工程自动加载数据包`() {
        withFixture { fixture ->
            val first = fixture.controller()
            first.loadPack(fixture.packRoot)
            first.loadProject(fixture.projectRoot)
            assertNotNull(first.state.value.pack)

            val second = fixture.controller()
            second.loadProject(fixture.projectRoot)
            assertNotNull(second.state.value.pack)
            assertEquals(
                fixture.packRoot.toAbsolutePath().normalize(),
                second.state.value.packRoot,
            )
            assertNotNull(second.state.value.project)
        }
    }

    @Test
    fun `选择器预览 UniqueID 对象使用 GUID 而不是对象名`() {
        withFixture { fixture ->
            val controller = openedController(fixture)
            val item = PickerItem(
                type = "CardData",
                name = "BaseCard",
                uniqueId = WorkspaceTestFixture.PACK_CARD_GUID,
                referenceKind = ReferenceKind.UNIQUE_ID,
                origin = EntryOrigin.BASE_PACK,
            )
            val display = controller.previewPickerItem(item, "CardData")
            assertTrue(display.resolved, display.status)
            assertEquals(WorkspaceTestFixture.PACK_CARD_GUID, display.key)
        }
    }

    @Test
    fun `LocalizedString 的 Key 与 PID 可编辑并维护本地化文本`() {
        withFixture { fixture ->
            val controller = openedController(fixture)
            val id = WorkspaceController.objectDocumentId("CardData", "ProjectCard")
            val keyPath = JsonPath.parse("CardName.LocalizationKey")
            val keyInfo = assertNotNull(controller.fieldInfo(id, keyPath))
            assertFalse(keyInfo.isReadOnly)

            val oldKey = "TestMod_ProjectCard_CardName"
            val entry = assertNotNull(controller.localizedEntry(oldKey))
            assertEquals("Project Card", entry.english)
            assertEquals("工程卡", entry.chinese)

            assertTrue(controller.setFieldText(id, keyPath, "TestMod_ProjectCard_NewKey", keyInfo))
            val document = controller.state.value.session.documents.getValue(id).document
            val newKey = (document.root.readPath(keyPath) as JsonNode.StringNode).value
            assertEquals("TestMod_ProjectCard_NewKey", newKey)

            assertTrue(controller.updateLocalizedText(newKey, "New English", "新中文"))
            val updated = assertNotNull(controller.localizedEntry(newKey))
            assertEquals("New English", updated.english)
            assertEquals("新中文", updated.chinese)
            assertTrue(controller.state.value.session.projectDirty)

            val pidInfo = assertNotNull(controller.fieldInfo(id, JsonPath.parse("CardName.ParentObjectID")))
            assertFalse(pidInfo.isReadOnly)
            val pid = WorkspaceTestFixture.PROJECT_CARD_GUID
            assertTrue(controller.setFieldText(id, JsonPath.parse("CardName.ParentObjectID"), pid, pidInfo))
        }
    }

    @Test
    fun `打开工程窗口行为持久化`() {
        withFixture { fixture ->
            val controller = fixture.controller()
            assertEquals("ASK", fixture.settingsStore().load().openProjectBehavior)
            controller.setOpenProjectBehavior(OpenProjectBehavior.NEW_WINDOW)
            assertEquals("NEW_WINDOW", controller.state.value.settings.openProjectBehavior)
            assertEquals("NEW_WINDOW", fixture.settingsStore().load().openProjectBehavior)
            controller.setOpenProjectBehavior(OpenProjectBehavior.CURRENT_WINDOW)
            assertEquals("CURRENT_WINDOW", fixture.settingsStore().load().openProjectBehavior)
        }
    }

    @Test
    fun `WarpType 显示开关持久化且默认关闭`() {
        withFixture { fixture ->
            val controller = fixture.controller()
            assertFalse(controller.state.value.settings.showWarpType)
            controller.setShowWarpType(true)
            assertTrue(controller.state.value.settings.showWarpType)
            assertTrue(fixture.settingsStore().load().showWarpType)
        }
    }

    @Test
    fun `分栏宽度持久化`() {
        withFixture { fixture ->
            val controller = fixture.controller()
            controller.setPaneWidths(320f, 360f)
            assertEquals(320f, controller.state.value.settings.fileTreeWidth)
            assertEquals(360f, controller.state.value.settings.inspectorWidth)
            val stored = fixture.settingsStore().load()
            assertEquals(320f, stored.fileTreeWidth)
            assertEquals(360f, stored.inspectorWidth)
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

            val withPack = org.zender.app.ui.workspace.FileTreeBuilder.build(
                state.project,
                state.pack,
                org.zender.app.ui.workspace.FileTreeFilter(includePackObjects = true),
            )
            val packGroup = withPack.first { it.id == "group:pack" }
            assertTrue(packGroup.children.any { it.type == "CardTag" })
        }
    }
}
