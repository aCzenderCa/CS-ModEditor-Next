package org.zender.editor.project

import org.zender.editor.document.JsonDocument
import org.zender.editor.fixture.TestFixtures
import org.zender.editor.framework.FrameworkCapability
import org.zender.editor.framework.MlFormat
import org.zender.editor.framework.ModFormat
import org.zender.editor.pack.DataPackLoader
import kotlin.test.Test
import kotlin.test.assertTrue

internal class FrameworkValidatorTest {

    private val pack = DataPackLoader().load(TestFixtures.miniPack()).packOrThrow()
    private val validator = FrameworkValidator(pack)

    private fun mlProject(): ModProject =
        MlProjectReader().read(TestFixtures.sampleMod("ml-sample"), pack).projectOrThrow()

    private fun mcProject(): ModProject =
        McProjectReader().read(TestFixtures.sampleMod("mc-sample"), pack).projectOrThrow()

    private fun withMlPatch(project: ModProject, text: String): ModProject {
        val patch = project.workingTree.gameSourceModifies.values.first()
        return project.copy(
            workingTree = project.workingTree.withGameSourceModify(
                patch.copy(document = JsonDocument.parseOrThrow(text)),
            ),
        )
    }

    private fun withMcPatch(project: ModProject, text: String): ModProject {
        val patch = project.workingTree.gameSourceModifies.values.first()
        return project.copy(
            workingTree = project.workingTree.withGameSourceModify(
                patch.copy(document = JsonDocument.parseOrThrow(text)),
            ),
        )
    }

    private fun withMcDataObjectPatch(project: ModProject, text: String): ModProject {
        val patch = project.workingTree.dataObjectModifies.values.first()
        return project.copy(
            workingTree = project.workingTree.withDataObjectModify(
                patch.copy(document = JsonDocument.parseOrThrow(text)),
            ),
        )
    }

    @Test
    fun `样例工程框架校验通过`() {
        assertTrue(validator.validate(mlProject()).isEmpty())
        assertTrue(validator.validate(mcProject()).isEmpty())
    }

    @Test
    fun `ML 不支持 override 与 matchTargets`() {
        val project = mlProject()
        val override = withMlPatch(project, """{"${'$'}override":{"ObjectWeight":0.8}}""")
        assertTrue(
            validator.validate(override).any { it.code == "patch.override.unsupported" },
        )

        val matchTargets = withMlPatch(project, """{"${'$'}matchTargets":["x"]}""")
        assertTrue(
            validator.validate(matchTargets).any { it.code == "patch.matchTargets.unsupported" },
        )
    }

    @Test
    fun `MC 补丁顶层普通字段会被忽略`() {
        val project = withMcPatch(mcProject(), """{"ObjectWeight":0.8}""")

        val issue = validator.validate(project)
            .first { it.code == "framework.patch.plainFieldIgnored" }

        assertTrue(issue.isError)
        assertTrue(issue.message.contains("ObjectWeight"))
    }

    @Test
    fun `MC 补丁引用 WarpType 3 不会生效`() {
        val project = withMcPatch(
            mcProject(),
            """{
              "CardTags": [],
              "CardTagsWarpData": ["FixtureMc:tag_McFixture"],
              "CardTagsWarpType": 3
            }""",
        )

        assertTrue(
            validator.validate(project).any { it.code == "framework.warpType.ignored" },
        )
    }

    @Test
    fun `补丁缺少 WarpType 时报错`() {
        val project = withMcPatch(
            mcProject(),
            """{"CardTags": [], "CardTagsWarpData": ["FixtureMc:tag_McFixture"]}""",
        )

        assertTrue(
            validator.validate(project).any { it.code == "framework.warpType.missing" },
        )
    }

    @Test
    fun `ML 补丁不支持 COPY 与 CUSTOM`() {
        val project = withMlPatch(
            mlProject(),
            """{
              "CardTags": [],
              "CardTagsWarpData": ["tag_Mini"],
              "CardTagsWarpType": 1
            }""",
        )

        assertTrue(
            validator.validate(project).any { it.code == "framework.warpType.unsupported" },
        )
    }

    @Test
    fun `MatchTagWarpData 只对 CardData 生效`() {
        val project = withMcDataObjectPatch(
            mcProject(),
            """{"MatchTagWarpData":["tag_Mini"]}""",
        )

        assertTrue(
            validator.validate(project).any { it.code == "framework.matchTag.nonCardData" },
        )
    }

    @Test
    fun `MatchTagWarpData 必须是字符串数组`() {
        val project = withMlPatch(mlProject(), """{"MatchTagWarpData":[1]}""")

        val issues = validator.validate(project)

        assertTrue(issues.any { it.code == "patch.matchTag.invalid" })
        assertTrue(issues.none { it.code == "framework.warpType.missing" })
    }

    @Test
    fun `override 不是对象时报错`() {
        val project = withMcPatch(mcProject(), """{"${'$'}override":[]}""")

        assertTrue(
            validator.validate(project).any { it.code == "patch.override.invalid" },
        )
    }

    @Test
    fun `matchTargets 必须是字符串数组`() {
        val project = withMcPatch(mcProject(), """{"${'$'}matchTargets":[1]}""")

        assertTrue(
            validator.validate(project).any { it.code == "patch.matchTargets.invalid" },
        )
    }

    @Test
    fun `DataObjectModify 类型未注册时告警`() {
        val project = mcProject().let { original ->
            original.copy(
                workingTree = original.workingTree.withDataObjectModify(
                    DataObjectModify(
                        type = "UnknownType",
                        dataKey = "X",
                        document = JsonDocument.parseOrThrow("""{"A":1}"""),
                        relativePath = "Data/DataObjectModify/UnknownType/X.json",
                    ),
                ),
            )
        }

        val issue = validator.validate(project)
            .first { it.code == "framework.dataObjectModify.typeUnknown" }

        assertTrue(issue.isWarning)
    }

    @Test
    fun `ML 存在 GameSourceModify 但缺少 ModEditorVersion 时告警`() {
        val project = mlProject()
        val updated = project.copy(
            metadata = project.metadata.copy(modEditorVersion = null),
        )

        assertTrue(
            validator.validate(updated)
                .any { it.code == "framework.gameSourceModify.editorVersionMissing" },
        )
    }

    @Test
    fun `MC 合法 matchTargets 不报错`() {
        val project = withMcPatch(
            mcProject(),
            """{"${'$'}matchTargets":["FixtureMc:tag_McFixture"],"${'$'}override":{}}""",
        )

        val issues = validator.validate(project)

        assertTrue(issues.none { it.code == "patch.matchTargets.unsupported" })
        assertTrue(issues.none { it.code == "patch.matchTargets.invalid" })
    }

    @Test
    fun `MatchTypeWarpData 结构与目标类型都会校验`() {
        val ml = withMlPatch(mlProject(), """{"MatchTypeWarpData":[1]}""")
        assertTrue(
            validator.validate(ml).any { it.code == "patch.matchType.invalid" },
        )

        val mc = withMcDataObjectPatch(
            mcProject(),
            """{"MatchTypeWarpData":["x"]}""",
        )
        assertTrue(
            validator.validate(mc).any { it.code == "framework.matchType.nonCardData" },
        )
    }

    @Test
    fun `CardData 目标上的 MatchTag 不报目标类型错误`() {
        val project = withMcPatch(
            mcProject(),
            """{"MatchTagWarpData":["tag_Mini"]}""",
        )

        assertTrue(
            validator.validate(project).none { it.code == "framework.matchTag.nonCardData" },
        )
    }

    @Test
    fun `补丁根节点不是对象时报错`() {
        val project = withMlPatch(mlProject(), "[]")

        assertTrue(
            validator.validate(project).any { it.code == "patch.document.invalid" && it.isError },
        )
    }

    @Test
    fun `WarpType 非数字时报错`() {
        val project = withMlPatch(
            mlProject(),
            """{
              "CardTags": [],
              "CardTagsWarpData": ["tag_Mini"],
              "CardTagsWarpType": "3"
            }""",
        )

        assertTrue(
            validator.validate(project).any { it.code == "framework.warpType.invalid" },
        )
    }

    @Test
    fun `ML WarpType 0 告警而 4 到 6 合法`() {
        val none = withMlPatch(
            mlProject(),
            """{
              "CardTags": [],
              "CardTagsWarpData": ["tag_Mini"],
              "CardTagsWarpType": 0
            }""",
        )
        assertTrue(
            validator.validate(none).any { it.code == "framework.warpType.none" },
        )

        val addReference = withMlPatch(
            mlProject(),
            """{
              "CardTags": [],
              "CardTagsWarpData": ["tag_Mini"],
              "CardTagsWarpType": 6
            }""",
        )
        assertTrue(
            validator.validate(addReference).none { it.code.startsWith("framework.warpType.") },
        )
    }

    @Test
    fun `MC WarpType 0 与 6 分别报错和放行`() {
        val none = withMcPatch(
            mcProject(),
            """{
              "CardTags": [],
              "CardTagsWarpData": ["FixtureMc:tag_McFixture"],
              "CardTagsWarpType": 0
            }""",
        )
        assertTrue(
            validator.validate(none).any { it.code == "framework.warpType.unsupported" },
        )

        val addReference = withMcPatch(
            mcProject(),
            """{
              "CardTags": [],
              "CardTagsWarpData": ["FixtureMc:tag_McFixture"],
              "CardTagsWarpType": 6
            }""",
        )
        assertTrue(
            validator.validate(addReference).none { it.code.startsWith("framework.warpType.") },
        )
    }

    @Test
    fun `override 内的嵌套 Warp 配对也会校验`() {
        val project = withMcPatch(
            mcProject(),
            """{
              "${'$'}override": {
                "Ambience": {
                  "RandomNoises": [],
                  "RandomNoisesWarpData": ["FixtureMc:tag_McFixture"],
                  "RandomNoisesWarpType": 3
                }
              }
            }""",
        )

        val issues = validator.validate(project)

        assertTrue(issues.any { it.code == "framework.warpType.ignored" })
        assertTrue(issues.none { it.code == "framework.patch.plainFieldIgnored" })
    }

    @Test
    fun `框架不支持资源类型时报错`() {
        val limited = object : ModFormat by MlFormat {
            override val capabilities: Set<FrameworkCapability> = emptySet()
            override fun supports(capability: FrameworkCapability): Boolean = false
        }
        val project = mlProject().let { it.copy(format = limited) }

        val issues = validator.validate(project)

        assertTrue(issues.any { it.code == "framework.resource.unsupported" && it.isError })
    }
}
