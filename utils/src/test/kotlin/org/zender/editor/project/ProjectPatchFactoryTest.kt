package org.zender.editor.project

import org.zender.editor.fixture.TestFixtures
import org.zender.editor.pack.DataPackLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectPatchFactoryTest {

    private val pack = DataPackLoader().load(TestFixtures.miniPack()).packOrThrow()

    private fun write(root: Path, relative: String, text: String) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun delete(root: Path) {
        runCatching {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun mlProject(root: Path): ModProject {
        write(
            root,
            "ModInfo.json",
            """{"Name":"PatchMod","Author":"T","Version":"1.0","ModEditorVersion":"0.1.0"}""",
        )
        return assertNotNull(MlProjectReader().read(root, pack).project)
    }

    private fun mcProject(root: Path): ModProject {
        write(root, "ModMeta.json", """{"Namespace":"PatchMc"}""")
        write(root, "Data/ScriptableObject/CardTag/tag_New.json", """{}""")
        return assertNotNull(McProjectReader().read(root, pack).project)
    }

    @Test
    fun `ML 可以创建 GameSourceModify 但不能创建 DataObjectModify`() {
        val root = Files.createTempDirectory("csmode-patch-ml-")
        try {
            val project = mlProject(root)
            val gsm = ProjectPatchFactory(pack).create(
                project,
                NewPatchRequest(
                    kind = PatchKind.GAME_SOURCE_MODIFY,
                    type = "CardData",
                    name = "MiniKnife",
                ),
            )
            assertTrue(gsm.isSuccess, gsm.diagnostics.joinToString())
            val patch = assertNotNull(gsm.gameSourceModify)
            assertEquals("11111111111111111111111111111111", patch.targetGuid)
            assertEquals("GameSourceModify/11111111111111111111111111111111.json", patch.relativePath)
            assertNotNull(assertNotNull(gsm.project).workingTree.gameSourceModify(patch.targetGuid))

            val dom = ProjectPatchFactory(pack).create(
                project,
                NewPatchRequest(
                    kind = PatchKind.DATA_OBJECT_MODIFY,
                    type = "CardTag",
                    name = "tag_Mini",
                ),
            )
            assertFalse(dom.isSuccess)
        } finally {
            delete(root)
        }
    }

    @Test
    fun `MC 可以创建两种补丁并使用正确路径与数据键`() {
        val root = Files.createTempDirectory("csmode-patch-mc-")
        try {
            val project = mcProject(root)
            val gsm = ProjectPatchFactory(pack).create(
                project,
                NewPatchRequest(
                    kind = PatchKind.GAME_SOURCE_MODIFY,
                    type = "CardData",
                    name = "MiniKnife",
                ),
            )
            assertTrue(gsm.isSuccess, gsm.diagnostics.joinToString())
            assertEquals(
                "Data/GameSourceModify/11111111111111111111111111111111.json",
                assertNotNull(gsm.gameSourceModify).relativePath,
            )

            val dom = ProjectPatchFactory(pack).create(
                project,
                NewPatchRequest(
                    kind = PatchKind.DATA_OBJECT_MODIFY,
                    type = "CardTag",
                    name = "tag_New",
                ),
            )
            assertTrue(dom.isSuccess, dom.diagnostics.joinToString())
            val domPatch = assertNotNull(dom.dataObjectModify)
            assertEquals("PatchMc:tag_New", domPatch.dataKey)
            assertEquals(
                "Data/DataObjectModify/CardTag/PatchMc@tag_New.json",
                domPatch.relativePath,
            )

            val duplicate = ProjectPatchFactory(pack).create(
                assertNotNull(dom.project),
                NewPatchRequest(
                    kind = PatchKind.DATA_OBJECT_MODIFY,
                    type = "CardTag",
                    name = "tag_New",
                ),
            )
            assertFalse(duplicate.isSuccess)
        } finally {
            delete(root)
        }
    }
}
