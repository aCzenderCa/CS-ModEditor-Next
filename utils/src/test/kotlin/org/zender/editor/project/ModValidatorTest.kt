package org.zender.editor.project

import org.zender.editor.document.JsonDocument
import org.zender.editor.fixture.TestFixtures
import org.zender.editor.localization.LocalizationEntry
import org.zender.editor.objects.ObjectKey
import org.zender.editor.pack.DataPackLoader
import org.zender.editor.resource.ResourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ModValidatorTest {

    private val basePack = DataPackLoader().load(TestFixtures.miniPack()).packOrThrow()
    private val validator = ModValidator(basePack)

    private fun mlProject(): ModProject =
        MlProjectReader().read(TestFixtures.sampleMod("ml-sample"), basePack).projectOrThrow()

    private fun mcProject(): ModProject =
        McProjectReader().read(TestFixtures.sampleMod("mc-sample"), basePack).projectOrThrow()

    @Test
    fun `样例工程通过基础校验`() {
        assertTrue(validator.validate(mlProject()).isEmpty())
        assertTrue(validator.validate(mcProject()).isEmpty())
    }

    @Test
    fun `MC 命名空间非法时报错`() {
        val project = mcProject()
        val invalid = project.copy(metadata = project.metadata.copy(namespace = "Bad:Name"))

        val issues = validator.validate(invalid)

        assertTrue(issues.any { it.code == "metadata.namespace.invalid" && it.isError })
    }

    @Test
    fun `UniqueID 对象缺少 GUID 或与 JSON 不一致时报错`() {
        val project = mlProject()
        val missing = ProjectObject(
            key = ObjectKey.of("CardData", "NoGuid"),
            kind = ProjectObjectKind.UNIQUE_ID,
            document = JsonDocument.parseOrThrow("""{"UniqueID":"1111"}"""),
            relativePath = "CardData/NoGuid.json",
        )
        val tree = project.workingTree.withObject(missing)
        assertTrue(
            validator.validate(project.copy(workingTree = tree))
                .any { it.code == "object.uniqueId.missing" },
        )

        val mismatch = missing.copy(
            key = ObjectKey.of("CardData", "NoGuid", "2222"),
            document = JsonDocument.parseOrThrow("""{"UniqueID":"1111"}"""),
        )
        val mismatchTree = project.workingTree.withObject(mismatch)
        assertTrue(
            validator.validate(project.copy(workingTree = mismatchTree))
                .any { it.code == "object.uniqueId.mismatch" },
        )
    }

    @Test
    fun `GUID 重复时报错`() {
        val project = mlProject()
        val first = ProjectObject(
            key = ObjectKey.of("CardData", "First", "9999"),
            kind = ProjectObjectKind.UNIQUE_ID,
            document = JsonDocument.parseOrThrow("""{"UniqueID":"9999"}"""),
            relativePath = "CardData/First.json",
        )
        val second = first.copy(
            key = ObjectKey.of("CardData", "Second", "9999"),
            relativePath = "CardData/Second.json",
        )
        val objects = LinkedHashMap(project.workingTree.objects)
        objects[first.key] = first
        objects[second.key] = second
        val tree = project.workingTree.copy(objects = objects)

        val issues = validator.validate(project.copy(workingTree = tree))

        assertTrue(issues.any { it.code == "object.uniqueId.duplicate" })
    }

    @Test
    fun `ML 不支持 DataObjectModify 与 override`() {
        val project = mlProject()
        val withDataModify = project.workingTree.withDataObjectModify(
            DataObjectModify(
                type = "CardTag",
                dataKey = "tag_X",
                document = JsonDocument.parseOrThrow("""{"A":1}"""),
                relativePath = "Data/DataObjectModify/CardTag/tag_X.json",
            ),
        )
        assertTrue(
            validator.validate(project.copy(workingTree = withDataModify))
                .any { it.code == "patch.dataObjectModify.unsupported" },
        )

        val patches = LinkedHashMap(project.workingTree.gameSourceModifies)
        val existing = patches.values.first()
        patches[existing.targetGuid] = existing.copy(
            document = JsonDocument.parseOrThrow("""{"${'$'}override":{"A":1}}"""),
        )
        val withOverride = project.workingTree.copy(gameSourceModifies = patches)
        assertTrue(
            validator.validate(project.copy(workingTree = withOverride))
                .any { it.code == "patch.override.unsupported" },
        )

        patches[existing.targetGuid] = existing.copy(
            document = JsonDocument.parseOrThrow("""{"${'$'}matchTargets":["x"]}"""),
        )
        assertTrue(
            validator.validate(project.copy(workingTree = withOverride))
                .any { it.code == "patch.matchTargets.unsupported" },
        )
    }

    @Test
    fun `缺失补丁目标只告警`() {
        val project = mlProject()
        val tree = project.workingTree.withGameSourceModify(
            GameSourceModify(
                targetGuid = "not-found-guid",
                document = JsonDocument.parseOrThrow("""{"A":1}"""),
                relativePath = "GameSourceModify/not-found-guid.json",
            ),
        )

        val issue = validator.validate(project.copy(workingTree = tree))
            .first { it.code == "patch.target.missing" }

        assertTrue(issue.severity == ValidationSeverity.WARNING)
    }

    @Test
    fun `资源键非法时报错`() {
        val project = mlProject()
        val tree = project.workingTree.withResource(
            ProjectResource(
                id = ResourceId(ResourceKind.IMAGE, "bad:key"),
                typeName = "Sprite",
                relativePath = "Resource/Picture/bad:key.png",
            ),
        )

        assertTrue(
            validator.validate(project.copy(workingTree = tree))
                .any { it.code == "resource.key.invalid" },
        )
    }

    @Test
    fun `没有保存目录时给出提示`() {
        val project = mlProject().withRoot(null)

        val issue = validator.validate(project).first { it.code == "project.root.missing" }

        assertTrue(issue.severity == ValidationSeverity.INFO)
    }

    @Test
    fun `Mod 名称为空时告警`() {
        val project = mlProject()
        val updated = project.copy(
            metadata = project.metadata.copy(
                names = project.metadata.names.mapValues { "" },
            ),
        )

        assertTrue(
            validator.validate(updated).any { it.code == "metadata.name.blank" },
        )
    }

    @Test
    fun `对象名包含非法字符时报错`() {
        val project = mlProject()
        val invalid = ProjectObject(
            key = ObjectKey.of("CardData", "bad:name", "66666666666666666666666666666666"),
            kind = ProjectObjectKind.UNIQUE_ID,
            document = JsonDocument.parseOrThrow(
                """{"UniqueID":"66666666666666666666666666666666"}""",
            ),
            relativePath = "CardData/bad:name.json",
        )

        val issues = validator.validate(
            project.copy(workingTree = project.workingTree.withObject(invalid)),
        )

        assertTrue(issues.any { it.code == "object.name.invalid" && it.isError })
    }

    @Test
    fun `对象文档根节点不是对象时报错`() {
        val project = mlProject()
        val invalid = ProjectObject(
            key = ObjectKey.of("CardTag", "BadDoc"),
            kind = ProjectObjectKind.SCRIPTABLE_OBJECT,
            document = JsonDocument.parseOrThrow("[]"),
            relativePath = "ScriptableObject/CardTag/BadDoc.json",
        )

        assertTrue(
            validator.validate(
                project.copy(workingTree = project.workingTree.withObject(invalid)),
            ).any { it.code == "object.document.invalid" },
        )
    }

    @Test
    fun `无 GUID 对象带 UniqueID 时告警`() {
        val project = mlProject()
        val invalid = ProjectObject(
            key = ObjectKey.of("CardTag", "WithGuid", "77777777777777777777777777777777"),
            kind = ProjectObjectKind.SCRIPTABLE_OBJECT,
            document = JsonDocument.parseOrThrow(
                """{"UniqueID":"77777777777777777777777777777777"}""",
            ),
            relativePath = "ScriptableObject/CardTag/WithGuid.json",
        )

        assertTrue(
            validator.validate(
                project.copy(workingTree = project.workingTree.withObject(invalid)),
            ).any { it.code == "object.scriptable.hasGuid" },
        )
    }

    @Test
    fun `JSON 缺少 UniqueID 字段时报错`() {
        val project = mlProject()
        val invalid = ProjectObject(
            key = ObjectKey.of("CardData", "NoField", "88888888888888888888888888888888"),
            kind = ProjectObjectKind.UNIQUE_ID,
            document = JsonDocument.parseOrThrow("""{"ObjectWeight":1.0}"""),
            relativePath = "CardData/NoField.json",
        )

        assertTrue(
            validator.validate(
                project.copy(workingTree = project.workingTree.withObject(invalid)),
            ).any { it.code == "object.uniqueId.fieldMissing" },
        )
    }

    @Test
    fun `补丁目标可从工程对象解析时不再告警`() {
        val project = mlProject()
        val patch = GameSourceModify(
            targetGuid = "33333333333333333333333333333333",
            document = JsonDocument.parseOrThrow("""{"ObjectWeight":0.9}"""),
            relativePath = "GameSourceModify/Fixture/33333333333333333333333333333333.json",
            targetKey = null,
        )
        val updated = project.copy(
            workingTree = project.workingTree.withGameSourceModify(patch),
        )

        assertTrue(
            validator.validate(updated).none {
                it.code == "patch.target.missing" && it.message.contains(patch.targetGuid)
            },
        )
    }

    @Test
    fun `DataObjectModify 工程内目标缺失时告警`() {
        val project = mcProject()
        val patch = DataObjectModify(
            type = "CardTag",
            dataKey = "missing_tag",
            document = JsonDocument.parseOrThrow("""{"UniqueOnBoard":true}"""),
            relativePath = "Data/DataObjectModify/CardTag/missing_tag.json",
        )
        val updated = project.copy(
            workingTree = project.workingTree.withDataObjectModify(patch),
        )

        assertTrue(
            validator.validate(updated).any {
                it.code == "patch.target.missing" && it.message.contains("missing_tag")
            },
        )
    }

    @Test
    fun `聚合校验覆盖各子校验器且没有重复结果`() {
        val project = mlProject()
        val clash = ProjectObject(
            key = ObjectKey.of("CardData", "Clash", "11111111111111111111111111111111"),
            kind = ProjectObjectKind.UNIQUE_ID,
            document = JsonDocument.parseOrThrow(
                """{
                  "UniqueID": "11111111111111111111111111111111",
                  "ObjectWeight": "heavy",
                  "UnknownField": 1
                }""",
            ),
            relativePath = "CardData/Clash.json",
        )
        val withObject = project.copy(
            workingTree = project.workingTree.withObject(clash),
        )
        val localization = withObject.workingTree.localization.withEntry(
            LocalizationEntry("FixtureML_Orphan", "orphan", "孤儿"),
        )
        val updated = withObject.copy(
            workingTree = withObject.workingTree.withLocalization(localization),
        )

        val issues = validator.validate(updated)

        assertTrue(issues.any { it.code == "conflict.guid.basePack" })
        assertTrue(issues.any { it.code == "schema.field.type" })
        assertTrue(issues.any { it.code == "schema.field.unknown" })
        assertTrue(issues.any { it.code == "localization.key.orphan" })
        assertEquals(issues.size, issues.distinct().size)
    }
}
