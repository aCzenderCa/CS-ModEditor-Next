package org.zender.editor.fixture

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class TestFixturesTest {

    @Test
    fun `迷你数据包具备完整目录约定`() {
        val pack = TestFixtures.miniPack()

        val expected = listOf(
            "PackInfo.json",
            "SimpCn.csv",
            "TypeStruct/CardData.json",
            "TypeStruct/CardOnCardAction.json",
            "TypeStruct/Enum/CardTypes.json",
            "BaseJson/CardData.json",
            "BaseJson/CardOnCardAction.json",
            "Doc/CardData.yml",
            "Doc-En/CardData.yml",
            "Objects/CardData/Item/MiniKnife.json",
            "Objects/CharacterPerk/MiniPerk.json",
            "Objects/CardTag/tag_Mini.json",
            "GUID/CardData/Item.json",
            "GUID/CharacterPerk.json",
            "Name/CardTag.json",
        )
        expected.forEach { relative ->
            assertTrue(Files.isRegularFile(pack.resolve(relative)), "缺少夹具文件：$relative")
        }
    }

    @Test
    fun `样例 Mod 清单与目录一致`() {
        val mods = TestFixtures.sampleMods()

        assertEquals(listOf("ml-sample", "mc-sample"), mods.map { it.id })
        assertEquals(listOf("ML", "MC"), mods.map { it.framework })

        mods.forEach { mod ->
            val root = TestFixtures.sampleMod(mod.id)
            assertTrue(Files.isDirectory(root), "样例 Mod 目录不存在：${mod.id}")
            val metadataName = if (mod.framework == "ML") "ModInfo.json" else "ModMeta.json"
            assertTrue(Files.isRegularFile(root.resolve(metadataName)), "${mod.id} 缺少 $metadataName")
        }
    }

    @Test
    fun `外部数据包路径可用系统属性覆盖`() {
        val temp = createTempDirectory("csmode-pack-fixture-")
        val property = "csmode.test.pack"
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, temp.toString())

            val found = TestFixtures.externalPack(
                game = "test",
                systemProperty = property,
                environmentVariable = "CS_MODEDITOR_PACK_UNUSED",
                fallback = "D:/path/that/does/not/exist",
            )

            assertEquals(temp.toAbsolutePath().normalize(), found)
        } finally {
            if (previous == null) {
                System.clearProperty(property)
            } else {
                System.setProperty(property, previous)
            }
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `本机存在真实数据包时结构可读`() {
        val packs = listOfNotNull(
            TestFixtures.cstiPack?.let { "csti" to it },
            TestFixtures.csffPack?.let { "csff" to it },
        )

        packs.forEach { (game, root) ->
            assertTrue(Files.isRegularFile(root.resolve("PackInfo.json")), "$game 缺少 PackInfo.json")
            assertTrue(Files.isDirectory(root.resolve("TypeStruct")), "$game 缺少 TypeStruct")
            assertTrue(Files.isDirectory(root.resolve("Objects")), "$game 缺少 Objects")
        }
    }
}
