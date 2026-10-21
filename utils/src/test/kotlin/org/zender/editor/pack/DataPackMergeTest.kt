package org.zender.editor.pack

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataPackMergeTest {

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

    @Test
    fun `读取 ML 额外数据包的 ModCore 格式`() {
        val base = Files.createTempDirectory("csmode-merge-ml")
        val main = base.resolve("main")
        val extra = base.resolve("ml-extra")
        try {
            write(main, "PackInfo.json", """{"Name":"CSFF","Version":"1.0","IsMainPack":true}""")
            write(main, "TypeStruct/CardData.json", """{"Fields":{"Weight":"Single"}}""")
            write(main, "BaseJson/CardData.json", """{"Weight":1.0}""")

            write(
                extra,
                "ScriptableObjectTypeJsonData/ModEncounter.json",
                """{"ThisId":"String","ModEncounterNodes":"ModEncounterNode"}""",
            )
            write(
                extra,
                "UniqueIDScriptableBaseJsonData/CommonBase/ModEncounter.json",
                """{"ThisId":"","ModEncounterNodes":[]}""",
            )
            write(
                extra,
                "ScriptableObjectJsonDataWithWarpLitAllInOne/ModEncounter/BaseModEncounter.json",
                """{"ThisId":"","ModEncounterNodes":[]}""",
            )
            write(extra, "ScriptableObjectObjectName/ModEncounter.txt", "BaseModEncounter\n")

            val result = DataPackLoader().loadCombined(main, listOf(extra))
            assertTrue(result.isSuccess, result.diagnostics.joinToString())
            val pack = result.packOrThrow()
            assertEquals(2, pack.layers.size)
            val schema = assertNotNull(pack.schema("ModEncounter"))
            assertNotNull(schema.template)
            // Base*.json 只作为模板，不进入对象索引。
            assertNull(pack.objectEntry("ModEncounter", "BaseModEncounter"))
            assertTrue(pack.objectIndex.nameIndexTypes.contains("ModEncounter"))
        } finally {
            delete(base)
        }
    }

    @Test
    fun `主包与额外包合并 Schema 对象与覆盖项`() {
        val base = Files.createTempDirectory("csmode-merge")
        val main = base.resolve("main")
        val extra = base.resolve("extra")
        try {
            write(main, "PackInfo.json", """{"Name":"CSTI","Version":"1.0","IsMainPack":true}""")
            write(
                main,
                "TypeStruct/CardData.json",
                """{"Fields":{"Weight":"Single","UniqueID":"String"}}""",
            )
            write(
                main,
                "BaseJson/CardData.json",
                """{"Weight":1.0,"UniqueID":""}""",
            )
            write(
                main,
                "Objects/CardData/Knife.json",
                """{"Weight":1.0,"UniqueID":"11111111111111111111111111111111","From":"main"}""",
            )
            write(
                main,
                "Objects/CardData/Shared.json",
                """{"Weight":1.0,"From":"main"}""",
            )

            write(extra, "PackInfo.json", """{"Name":"ExtraCore","Version":"3.4.0"}""")
            write(
                extra,
                "TypeStruct/CardTag.json",
                """{"Fields":{"UniqueOnBoard":"Boolean"}}""",
            )
            write(extra, "BaseJson/CardTag.json", """{"UniqueOnBoard":false}""")
            write(
                extra,
                "Objects/CardTag/tag_Extra.json",
                """{"UniqueOnBoard":true}""",
            )
            write(
                extra,
                "Objects/CardData/Shared.json",
                """{"Weight":2.0,"From":"extra"}""",
            )

            val result = DataPackLoader().loadCombined(main, listOf(extra))
            assertTrue(result.isSuccess, result.diagnostics.joinToString())
            val pack = result.packOrThrow()

            assertEquals("CSTI", pack.info.name)
            assertEquals(2, pack.layers.size)
            assertEquals(listOf("CSTI", "ExtraCore"), pack.layers.map { it.info.name })

            assertNotNull(pack.schema("CardData"))
            assertNotNull(pack.schema("CardTag"))

            val knife = assertNotNull(pack.objectEntry("CardData", "Knife"))
            assertEquals("main", knife.sourceRoot?.fileName?.toString())
            assertTrue(pack.readObjectText(knife).contains("main"))

            val extraTag = assertNotNull(pack.objectEntry("CardTag", "tag_Extra"))
            assertEquals("extra", extraTag.sourceRoot?.fileName?.toString())
            assertTrue(pack.readObjectText(extraTag).contains("true"))

            val shared = assertNotNull(pack.objectEntry("CardData", "Shared"))
            assertEquals("extra", shared.sourceRoot?.fileName?.toString())
            assertTrue(pack.readObjectText(shared).contains("extra"))
            assertTrue(result.diagnostics.any { it.message.contains("覆盖对象") })
        } finally {
            delete(base)
        }
    }
}
