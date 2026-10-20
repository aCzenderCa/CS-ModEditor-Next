package org.zender.editor.project

import org.zender.editor.document.JsonDocument
import org.zender.editor.fixture.TestFixtures
import org.zender.editor.objects.ObjectKey
import org.zender.editor.pack.DataPackLoader
import org.zender.editor.resource.ResourceKind
import kotlin.test.Test
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
}
