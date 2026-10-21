package org.zender.editor.project

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectFolderRoundTripTest {

    private fun write(root: Path, relative: String, content: String) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    private fun delete(root: Path) {
        runCatching {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `读取多级对象目录与空文件夹`() {
        val root = Files.createTempDirectory("csmode-folders")
        try {
            write(
                root,
                "ModMeta.json",
                """{"Namespace":"FolderMod","Name":{"SimpCn":"文件夹测试"}}""",
            )
            write(
                root,
                "Data/CardData/Item/Weapons/Knife.json",
                """{"UniqueID":"55555555555555555555555555555555"}""",
            )
            write(
                root,
                "Data/ScriptableObject/CardTag/tag_X.json",
                """{"UniqueOnBoard":false}""",
            )
            Files.createDirectories(root.resolve("Data/CardData/Empty"))
            Files.createDirectories(root.resolve("Data/ScriptableObject/CardTag/Labels/Empty"))

            val tree = assertNotNull(McProjectReader().read(root).project).workingTree
            assertTrue(
                ProjectFolder("CardData", "Item", ProjectObjectKind.UNIQUE_ID) in tree.folders,
            )
            assertTrue(
                ProjectFolder("CardData", "Item/Weapons", ProjectObjectKind.UNIQUE_ID) in tree.folders,
            )
            assertTrue(
                ProjectFolder("CardData", "Empty", ProjectObjectKind.UNIQUE_ID) in tree.folders,
            )
            assertTrue(
                ProjectFolder(
                    "CardTag",
                    "Labels/Empty",
                    ProjectObjectKind.SCRIPTABLE_OBJECT,
                ) in tree.folders,
            )
            assertNotNull(tree.objectOf("CardData", "Knife"))
            assertNotNull(tree.objectOf("CardTag", "tag_X"))
        } finally {
            delete(root)
        }
    }

    @Test
    fun `新建对象写入多级目录并可重新读取`() {
        val root = Files.createTempDirectory("csmode-factory-folders")
        try {
            write(root, "ModMeta.json", """{"Namespace":"FolderMod"}""")
            val project = assertNotNull(McProjectReader().read(root).project)
            val created = assertNotNull(
                ProjectObjectFactory().create(
                    project,
                    NewObjectRequest(
                        type = "CardTag",
                        name = "tag_Deep",
                        kind = ProjectObjectKind.SCRIPTABLE_OBJECT,
                        group = "Labels/Melee",
                    ),
                ).objectEntry,
            )
            assertEquals(
                "Data/ScriptableObject/CardTag/Labels/Melee/tag_Deep.json",
                created.relativePath,
            )
            val write = ModProjectWriter().write(
                project.withWorkingTree(project.workingTree.withObject(created)),
            )
            assertTrue(write.isSuccess, write.diagnostics.joinToString())
            val reloaded = assertNotNull(McProjectReader().read(root).project).workingTree
            assertEquals("Labels/Melee", reloaded.objectOf("CardTag", "tag_Deep")?.let {
                ProjectFolderPath.folderOf(it.relativePath, it.type)
            })
        } finally {
            delete(root)
        }
    }
}
