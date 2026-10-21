package org.zender.editor.project

import org.zender.editor.framework.FrameworkId
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModProjectCreatorTest {

    private fun tempDirectory(): Path = Files.createTempDirectory("csmode-new-project")

    private fun delete(root: Path) {
        runCatching {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `新建 MC 工程写入 ModMeta 并可重新读取`() {
        val root = tempDirectory()
        try {
            val result = ModProjectCreator().create(
                NewProjectRequest(
                    root = root.resolve("McMod"),
                    framework = FrameworkId.MC,
                    name = "我的 Mod",
                    namespace = "MyMod",
                    author = "Tester",
                    version = "1.2.0",
                ),
            )
            assertTrue(result.isSuccess, result.diagnostics.joinToString())
            val project = assertNotNull(result.project)
            assertEquals("MyMod", project.namespace)
            assertTrue(Files.isRegularFile(project.root!!.resolve("ModMeta.json")))

            val reloaded = ModProjectLoader().read(project.root!!).projectOrThrow()
            assertEquals(FrameworkId.MC, reloaded.framework)
            assertEquals("MyMod", reloaded.namespace)
            assertEquals("我的 Mod", reloaded.metadata.names["SimpCn"])
            assertEquals("Tester", reloaded.metadata.author)
        } finally {
            delete(root)
        }
    }

    @Test
    fun `新建 ML 工程使用 Mod 名称作为前缀`() {
        val root = tempDirectory()
        try {
            val result = ModProjectCreator().create(
                NewProjectRequest(
                    root = root.resolve("MlMod"),
                    framework = FrameworkId.ML,
                    name = "MyMod",
                ),
            )
            assertTrue(result.isSuccess, result.diagnostics.joinToString())
            val project = assertNotNull(result.project)
            assertEquals("MyMod", project.namespace)
            assertTrue(Files.isRegularFile(project.root!!.resolve("ModInfo.json")))
        } finally {
            delete(root)
        }
    }

    @Test
    fun `非空目录与非法 Namespace 被拒绝`() {
        val root = tempDirectory()
        try {
            Files.writeString(root.resolve("keep.txt"), "x")
            val nonEmpty = ModProjectCreator().create(
                NewProjectRequest(
                    root = root,
                    framework = FrameworkId.MC,
                    name = "Mod",
                    namespace = "Mod",
                ),
            )
            assertFalse(nonEmpty.isSuccess)

            val invalidNamespace = ModProjectCreator().create(
                NewProjectRequest(
                    root = root.resolve("Other"),
                    framework = FrameworkId.MC,
                    name = "Mod",
                    namespace = "Bad:Name",
                ),
            )
            assertFalse(invalidNamespace.isSuccess)
        } finally {
            delete(root)
        }
    }

    @Test
    fun `保存工程会创建空文件夹目录`() {
        val root = tempDirectory()
        try {
            val created = assertNotNull(
                ModProjectCreator().create(
                    NewProjectRequest(
                        root = root.resolve("McMod"),
                        framework = FrameworkId.MC,
                        name = "Mod",
                        namespace = "Mod",
                    ),
                ).project,
            )
            val folder = ProjectFolder(
                type = "CardData",
                path = "Item/Weapons",
                kind = ProjectObjectKind.UNIQUE_ID,
            )
            val withFolder = created.withWorkingTree(created.workingTree.withFolder(folder))
            val write = ModProjectWriter().write(withFolder)
            assertTrue(write.isSuccess, write.diagnostics.joinToString())
            assertTrue(
                Files.isDirectory(
                    created.root!!.resolve("Data/CardData/Item/Weapons"),
                ),
            )
            val reloaded = assertNotNull(ModProjectLoader().read(created.root!!).project).workingTree
            assertTrue(folder in reloaded.folders)
            assertTrue(
                ProjectFolder("CardData", "Item", ProjectObjectKind.UNIQUE_ID) in reloaded.folders,
            )
        } finally {
            delete(root)
        }
    }
}
