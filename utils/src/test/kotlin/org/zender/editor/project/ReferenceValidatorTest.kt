package org.zender.editor.project

import org.zender.editor.document.JsonDocument
import org.zender.editor.fixture.TestFixtures
import org.zender.editor.objects.ObjectKey
import org.zender.editor.pack.DataPackLoader
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class ReferenceValidatorTest {

    private val pack = DataPackLoader().load(TestFixtures.miniPack()).packOrThrow()
    private val validator = ReferenceValidator(pack)
    private val probePack = DataPackLoader().load(TestFixtures.probePack()).packOrThrow()

    private fun mlProject(): ModProject =
        MlProjectReader().read(TestFixtures.sampleMod("ml-sample"), pack).projectOrThrow()

    private fun mcProject(): ModProject =
        McProjectReader().read(TestFixtures.sampleMod("mc-sample"), pack).projectOrThrow()

    private fun withObjectDocument(project: ModProject, text: String): ModProject {
        val entry = project.workingTree.objects.values.first { it.name == "FixtureKnife" }
        return project.copy(
            workingTree = project.workingTree.withObject(
                entry.copy(document = JsonDocument.parseOrThrow(text)),
            ),
        )
    }

    private fun withGameSourcePatch(project: ModProject, text: String): ModProject {
        val patch = project.workingTree.gameSourceModifies.values.first()
        return project.copy(
            workingTree = project.workingTree.withGameSourceModify(
                patch.copy(document = JsonDocument.parseOrThrow(text)),
            ),
        )
    }

    private fun probeProject(text: String): ModProject {
        val base = mlProject()
        val probe = ProjectObject(
            key = ObjectKey.of("Probe", "Thing"),
            kind = ProjectObjectKind.SCRIPTABLE_OBJECT,
            document = JsonDocument.parseOrThrow(text),
            relativePath = "Probe/Thing.json",
        )
        return base.copy(
            basePack = probePack,
            workingTree = base.workingTree.withObject(probe),
        )
    }

    @Test
    fun `样例工程引用校验通过`() {
        assertTrue(validator.validate(mlProject()).isEmpty())
        assertTrue(validator.validate(mcProject()).isEmpty())
    }

    @Test
    fun `按 GUID 解析工程对象与数据包对象`() {
        val project = mlProject()

        val projectTarget = validator.check(project, "CardData", "33333333333333333333333333333333")
        val packTarget = validator.check(project, "CardData", "11111111111111111111111111111111")

        assertIs<ReferenceCheck.Resolved>(projectTarget)
        assertTrue(projectTarget.origin == ReferenceOrigin.PROJECT)
        assertIs<ReferenceCheck.Resolved>(packTarget)
        assertTrue(packTarget.origin == ReferenceOrigin.BASE_PACK)
    }

    @Test
    fun `按对象名解析无 GUID 对象`() {
        val result = validator.check(mlProject(), "CardTag", "tag_Mini")

        assertIs<ReferenceCheck.Resolved>(result)
        assertTrue(result.origin == ReferenceOrigin.BASE_PACK)
    }

    @Test
    fun `按对象名解析工程资源`() {
        val result = validator.check(mlProject(), "Sprite", "fixture")

        assertIs<ReferenceCheck.Resolved>(result)
        assertTrue(result.origin == ReferenceOrigin.PROJECT_RESOURCE)
    }

    @Test
    fun `缺失引用目标只告警`() {
        val project = withObjectDocument(
            mlProject(),
            """{
              "UniqueID": "33333333333333333333333333333333",
              "SpawningBlockedBy": [],
              "SpawningBlockedByWarpData": ["11111111111111111111111111111111", "missing-guid"],
              "SpawningBlockedByWarpType": 3
            }""",
        )

        val issues = validator.validate(project)
        val issue = issues.first { it.code == "reference.target.missing" }

        assertTrue(issue.isWarning)
        assertTrue(issue.message.contains("missing-guid"))
    }

    @Test
    fun `引用类型不兼容时报错`() {
        val project = withObjectDocument(
            mlProject(),
            """{
              "UniqueID": "33333333333333333333333333333333",
              "CardTags": [],
              "CardTagsWarpData": ["MiniKnife"],
              "CardTagsWarpType": 3
            }""",
        )

        val issue = validator.validate(project).first { it.code == "reference.type.mismatch" }

        assertTrue(issue.isError)
        assertTrue(issue.message.contains("MiniKnife"))
    }

    @Test
    fun `ML 引用缺少 WarpType 时告警`() {
        val project = withObjectDocument(
            mlProject(),
            """{
              "UniqueID": "33333333333333333333333333333333",
              "SpawningBlockedBy": [],
              "SpawningBlockedByWarpData": ["11111111111111111111111111111111"]
            }""",
        )

        assertTrue(
            validator.validate(project).any { it.code == "reference.warpType.missing" },
        )
    }

    @Test
    fun `WarpType 超出范围时报错`() {
        val project = withObjectDocument(
            mlProject(),
            """{
              "UniqueID": "33333333333333333333333333333333",
              "SpawningBlockedBy": [],
              "SpawningBlockedByWarpData": ["11111111111111111111111111111111"],
              "SpawningBlockedByWarpType": 9
            }""",
        )

        assertTrue(
            validator.validate(project).any { it.code == "reference.warpType.invalid" },
        )
    }

    @Test
    fun `引用键为空时报错`() {
        val project = withObjectDocument(
            mlProject(),
            """{
              "UniqueID": "33333333333333333333333333333333",
              "SpawningBlockedBy": [],
              "SpawningBlockedByWarpData": [""],
              "SpawningBlockedByWarpType": 3
            }""",
        )

        assertTrue(
            validator.validate(project).any { it.code == "reference.key.blank" },
        )
    }

    @Test
    fun `其他命名空间的 MC 引用只告警`() {
        val project = withObjectDocument(
            mcProject(),
            """{
              "UniqueID": "44444444444444444444444444444444",
              "CardTags": [],
              "CardTagsWarpData": ["OtherMod:tag_X"],
              "CardTagsWarpType": 3
            }""",
        )

        val issue = validator.validate(project).first { it.code == "reference.target.external" }

        assertTrue(issue.isWarning)
    }

    @Test
    fun `基类字段同名目标存在时报告歧义`() {
        val project = mlProject()
        val first = ProjectObject(
            key = ObjectKey.of("CardTag", "same"),
            kind = ProjectObjectKind.SCRIPTABLE_OBJECT,
            document = JsonDocument.parseOrThrow("""{}"""),
            relativePath = "ScriptableObject/CardTag/same.json",
        )
        val second = first.copy(
            key = ObjectKey.of("EquipmentTag", "same"),
            relativePath = "ScriptableObject/EquipmentTag/same.json",
        )
        val tree = project.workingTree.withObject(first).withObject(second)
        val resolver = ProjectReferenceResolver(
            project.copy(workingTree = tree),
            pack,
        )

        val result = resolver.resolve("ScriptableObject", "same")

        assertIs<ReferenceCheck.Ambiguous>(result)
        assertTrue(result.candidates.size >= 2)
    }

    @Test
    fun `未知引用字段类型给出无法确认结果`() {
        val result = validator.check(mlProject(), "NotAReferenceType", "anything")

        assertIs<ReferenceCheck.Unverified>(result)
    }

    @Test
    fun `补丁只写 WarpData 时仍校验引用`() {
        val project = withGameSourcePatch(
            mcProject(),
            """{
              "CardTagsWarpData": ["missing-tag"],
              "CardTagsWarpType": 6
            }""",
        )

        assertTrue(
            validator.validate(project).any {
                it.code == "reference.target.missing" && it.message.contains("missing-tag")
            },
        )
    }

    @Test
    fun `MC 完整自身数据键不回退到数据包裸名`() {
        val result = validator.check(mcProject(), "ScriptableObject", "FixtureMc:tag_Mini")

        assertIs<ReferenceCheck.NotFound>(result)
    }

    @Test
    fun `MC 短键仍可按运行时顺序命中数据包`() {
        val result = validator.check(mcProject(), "ScriptableObject", "tag_Mini")

        val resolved = assertIs<ReferenceCheck.Resolved>(result)
        assertTrue(resolved.origin == ReferenceOrigin.BASE_PACK)
    }

    @Test
    fun `空引用键返回 NotFound`() {
        assertIs<ReferenceCheck.NotFound>(
            validator.check(mlProject(), "CardData", "   "),
        )
    }

    @Test
    fun `工程内对象名引用解析为 PROJECT`() {
        val project = mlProject()
        val tag = ProjectObject(
            key = ObjectKey.of("CardTag", "tag_Project"),
            kind = ProjectObjectKind.SCRIPTABLE_OBJECT,
            document = JsonDocument.parseOrThrow("""{}"""),
            relativePath = "ScriptableObject/CardTag/tag_Project.json",
        )
        val updated = project.copy(
            workingTree = project.workingTree.withObject(tag),
        )

        val result = validator.check(updated, "CardTag", "tag_Project")

        val resolved = assertIs<ReferenceCheck.Resolved>(result)
        assertTrue(resolved.origin == ReferenceOrigin.PROJECT)
    }

    @Test
    fun `工程内同名对象类型不符时返回 TypeMismatch`() {
        val result = validator.check(mlProject(), "CardTag", "FixtureKnife")

        val mismatch = assertIs<ReferenceCheck.TypeMismatch>(result)
        assertTrue(mismatch.targetType == "CardData")
    }

    @Test
    fun `MC 完整自身数据键未命中时返回 NotFound`() {
        assertIs<ReferenceCheck.NotFound>(
            validator.check(mcProject(), "CardTag", "FixtureMc:MissingTag"),
        )
    }

    @Test
    fun `其他命名空间的资源引用返回 Unverified`() {
        assertIs<ReferenceCheck.Unverified>(
            validator.check(mcProject(), "Sprite", "OtherMod:fixture"),
        )
    }

    @Test
    fun `数据包资源引用可解析`() {
        val project = mlProject().withBasePack(probePack)
        val result = ReferenceValidator(probePack).check(project, "Sprite", "ProbeImage")

        val resolved = assertIs<ReferenceCheck.Resolved>(result)
        assertTrue(resolved.origin == ReferenceOrigin.BASE_PACK_RESOURCE)
    }

    @Test
    fun `UniqueIDScriptable 基类可按 GUID 解析工程与数据包对象`() {
        val project = mlProject().withBasePack(probePack)
        val probeValidator = ReferenceValidator(probePack)

        assertTrue(
            probeValidator.check(project, "UniqueIDScriptable", "33333333333333333333333333333333")
                is ReferenceCheck.Resolved,
        )
        assertTrue(
            probeValidator.check(project, "UniqueIDScriptable", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                is ReferenceCheck.Resolved,
        )
    }

    @Test
    fun `Object 基类字段可以解析资源`() {
        val project = mlProject().withBasePack(probePack)
        val result = ReferenceValidator(probePack).check(project, "Object", "ProbeSound")

        val resolved = assertIs<ReferenceCheck.Resolved>(result)
        assertTrue(resolved.origin == ReferenceOrigin.BASE_PACK_RESOURCE)
    }

    @Test
    fun `命名空间为空时仍可按裸名解析数据包对象`() {
        val project = mlProject().let {
            it.copy(metadata = it.metadata.copy(namespace = ""))
        }

        val result = validator.check(project, "CardTag", "tag_Mini")

        assertIs<ReferenceCheck.Resolved>(result)
    }

    @Test
    fun `没有数据包时引用校验返回空结果`() {
        assertTrue(ReferenceValidator().validate(mlProject().withBasePack(null)).isEmpty())
    }

    @Test
    fun `Probe 标量引用可解析且缺少 WarpType 时告警`() {
        val project = probeProject(
            """{
              "Target": {},
              "TargetWarpData": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            }""",
        )

        val issues = ReferenceValidator(probePack).validate(project)

        assertTrue(issues.none { it.code == "reference.target.missing" })
        assertTrue(issues.any { it.code == "reference.warpType.missing" })
    }

    @Test
    fun `Probe 标量 WarpData 类型非法时报错`() {
        val project = probeProject(
            """{
              "Target": {},
              "TargetWarpData": [],
              "TargetWarpType": 3
            }""",
        )

        assertTrue(
            ReferenceValidator(probePack).validate(project)
                .any { it.code == "reference.data.invalid" },
        )
    }

    @Test
    fun `Probe 只有 WarpType 缺少 WarpData 时告警`() {
        val project = probeProject(
            """{"Target": {}, "TargetWarpType": 3}""",
        )

        assertTrue(
            ReferenceValidator(probePack).validate(project)
                .any { it.code == "reference.data.missing" },
        )
    }

    @Test
    fun `Probe 列表引用可解析且非法元素带下标`() {
        val valid = probeProject(
            """{
              "Tags": [],
              "TagsWarpData": ["ProbeTag"],
              "TagsWarpType": 3
            }""",
        )
        assertTrue(
            ReferenceValidator(probePack).validate(valid)
                .none { it.code.startsWith("reference.data") },
        )

        val invalid = probeProject(
            """{
              "Tags": [],
              "TagsWarpData": ["ProbeTag", 1],
              "TagsWarpType": 3
            }""",
        )
        val issue = ReferenceValidator(probePack).validate(invalid)
            .first { it.code == "reference.data.invalid" }
        assertTrue(issue.path?.contains("[1]") == true)
    }

    @Test
    fun `Probe 列表 WarpData 不是数组时报错`() {
        val project = probeProject(
            """{
              "Tags": [],
              "TagsWarpData": "ProbeTag",
              "TagsWarpType": 3
            }""",
        )

        assertTrue(
            ReferenceValidator(probePack).validate(project)
                .any { it.code == "reference.data.invalid" },
        )
    }

    @Test
    fun `Probe WarpType 非数字时报错`() {
        val project = probeProject(
            """{
              "Target": {},
              "TargetWarpData": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "TargetWarpType": "3"
            }""",
        )

        assertTrue(
            ReferenceValidator(probePack).validate(project)
                .any { it.code == "reference.warpType.invalid" },
        )
    }
}
