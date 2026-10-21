package org.zender.editor.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProjectFoldersTest {

    @Test
    fun `文件夹路径规范化支持多级与反斜杠`() {
        assertNull(ProjectFolderPath.normalize(""))
        assertNull(ProjectFolderPath.normalize("   "))
        assertEquals("Item/Weapons", ProjectFolderPath.normalize(" Item\\Weapons "))
        assertEquals("Item/Weapons", ProjectFolderPath.normalize("/Item//Weapons/"))
    }

    @Test
    fun `文件夹路径拒绝非法段`() {
        assertFailsWith<IllegalArgumentException> {
            ProjectFolderPath.normalize("..")
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectFolderPath.normalize("Item/../Weapons")
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectFolderPath.normalize("Bad:Name")
        }
    }

    @Test
    fun `上级目录包含自身`() {
        assertEquals(listOf("Item", "Item/Weapons", "Item/Weapons/Melee"), ProjectFolderPath.ancestors("Item/Weapons/Melee"))
    }

    @Test
    fun `从对象路径推导子文件夹`() {
        assertEquals("Item/Weapons", ProjectFolderPath.folderOf("Data/CardData/Item/Weapons/Knife.json", "CardData"))
        assertEquals(null, ProjectFolderPath.folderOf("Data/CardData/Knife.json", "CardData"))
        assertEquals(
            "Melee",
            ProjectFolderPath.folderOf("ScriptableObject/CardTag/Melee/tag_X.json", "CardTag"),
        )
        assertEquals(
            null,
            ProjectFolderPath.folderOf("Data/GameSourceModify/x.json", "CardData"),
        )
    }

    @Test
    fun `从固定基准目录推导文件夹`() {
        assertEquals(
            "Fixture",
            ProjectFolderPath.folderUnder(
                "Data/GameSourceModify/Fixture/1111.json",
                "Data/GameSourceModify",
            ),
        )
        assertEquals(
            null,
            ProjectFolderPath.folderUnder("Data/GameSourceModify/1111.json", "Data/GameSourceModify"),
        )
    }
}
