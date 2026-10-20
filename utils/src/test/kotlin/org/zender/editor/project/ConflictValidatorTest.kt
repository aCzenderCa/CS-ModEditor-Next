package org.zender.editor.project

import org.zender.editor.document.JsonDocument
import org.zender.editor.fixture.TestFixtures
import org.zender.editor.objects.ObjectEntry
import org.zender.editor.objects.ObjectIndex
import org.zender.editor.objects.ObjectKey
import org.zender.editor.objects.ReferenceIndex
import org.zender.editor.objects.ReferenceRule
import org.zender.editor.pack.DataPack
import org.zender.editor.pack.DataPackLoader
import org.zender.editor.picker.ReferencePickerService
import org.zender.editor.resource.ResourceKind
import kotlin.test.Test
import kotlin.test.assertTrue

internal class ConflictValidatorTest {

    private val pack = DataPackLoader().load(TestFixtures.miniPack()).packOrThrow()
    private val validator = ConflictValidator(pack)

    private fun mlProject(): ModProject =
        MlProjectReader().read(TestFixtures.sampleMod("ml-sample"), pack).projectOrThrow()

    private fun mcProject(): ModProject =
        McProjectReader().read(TestFixtures.sampleMod("mc-sample"), pack).projectOrThrow()

    @Test
    fun `样例工程没有名称冲突`() {
        assertTrue(validator.validate(mlProject()).isEmpty())
        assertTrue(validator.validate(mcProject()).isEmpty())
    }

    @Test
    fun `Namespace 为空或非法时报错`() {
        val ml = mlProject()
        assertTrue(
            validator.validate(ml.copy(metadata = ml.metadata.copy(namespace = "")))
                .any { it.code == "metadata.namespace.blank" },
        )

        val mc = mcProject()
        assertTrue(
            validator.validate(mc.copy(metadata = mc.metadata.copy(namespace = "Bad:Name")))
                .any { it.code == "metadata.namespace.invalid" },
        )
    }

    @Test
    fun `GUID 与数据包内置对象冲突时报错`() {
        val project = mlProject()
        val clash = ProjectObject(
            key = ObjectKey.of("CardData", "Clash", "11111111111111111111111111111111"),
            kind = ProjectObjectKind.UNIQUE_ID,
            document = JsonDocument.parseOrThrow(
                """{"UniqueID":"11111111111111111111111111111111"}""",
            ),
            relativePath = "CardData/Clash.json",
        )

        val issues = validator.validate(
            project.copy(workingTree = project.workingTree.withObject(clash)),
        )

        assertTrue(issues.any { it.code == "conflict.guid.basePack" && it.isError })
    }

    @Test
    fun `工程内 GUID 重复时报错`() {
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

        val issues = validator.validate(
            project.copy(workingTree = project.workingTree.copy(objects = objects)),
        )

        assertTrue(issues.any { it.code == "object.uniqueId.duplicate" })
    }

    @Test
    fun `ML 无 GUID 对象与数据包重名时报错`() {
        val project = mlProject()
        val clash = ProjectObject(
            key = ObjectKey.of("CardTag", "tag_Mini"),
            kind = ProjectObjectKind.SCRIPTABLE_OBJECT,
            document = JsonDocument.parseOrThrow("""{"UniqueOnBoard":false}"""),
            relativePath = "ScriptableObject/CardTag/tag_Mini.json",
        )

        val issues = validator.validate(
            project.copy(workingTree = project.workingTree.withObject(clash)),
        )

        assertTrue(issues.any { it.code == "conflict.objectName.basePack" && it.isError })
    }

    @Test
    fun `ML UniqueID 对象名与数据包相同不算冲突`() {
        val project = mlProject()
        val sameName = ProjectObject(
            key = ObjectKey.of("CardData", "MiniKnife", "55555555555555555555555555555555"),
            kind = ProjectObjectKind.UNIQUE_ID,
            document = JsonDocument.parseOrThrow(
                """{"UniqueID":"55555555555555555555555555555555"}""",
            ),
            relativePath = "CardData/MiniKnife.json",
        )

        val issues = validator.validate(
            project.copy(workingTree = project.workingTree.withObject(sameName)),
        )

        assertTrue(issues.none { it.code == "conflict.objectName.basePack" })
    }

    @Test
    fun `MC 数据键与数据包已有键冲突时报错`() {
        val entry = ObjectEntry(ObjectKey.of("CardData", "FixtureMc:FixtureKnife"))
        val index = ObjectIndex(
            entries = (pack.objectIndex.entries + entry)
                .sortedWith(compareBy({ it.type }, { it.name })),
            guidIndexTypes = pack.objectIndex.guidIndexTypes,
            nameIndexTypes = pack.objectIndex.nameIndexTypes,
        )
        val rule = ReferenceRule.from(index)
        val referenceIndex = ReferenceIndex(index, rule, pack.resources)
        val customPack = DataPack(
            root = pack.root,
            info = pack.info,
            schemaRepository = pack.schemaRepository,
            objectIndex = index,
            referenceRule = rule,
            referenceIndex = referenceIndex,
            localization = pack.localization,
            resources = pack.resources,
            picker = ReferencePickerService(index, pack.resources, rule, referenceIndex),
            defaultValueResolver = pack.defaultValueResolver,
            fieldProjection = pack.fieldProjection,
            diagnostics = pack.diagnostics,
        )
        val project = McProjectReader()
            .read(TestFixtures.sampleMod("mc-sample"), customPack)
            .projectOrThrow()

        val issues = ConflictValidator(customPack).validate(project)

        assertTrue(issues.any { it.code == "conflict.dataKey.basePack" && it.isError })
    }

    @Test
    fun `资源键非法时报错`() {
        val project = mlProject()
        val updated = project.copy(
            workingTree = project.workingTree.withResource(
                ProjectResource(
                    id = ResourceId(ResourceKind.IMAGE, "bad:key"),
                    typeName = "Sprite",
                    relativePath = "Resource/Picture/bad:key.png",
                ),
            ),
        )

        assertTrue(
            validator.validate(updated).any {
                it.code == "resource.key.invalid" && it.isError
            },
        )
    }

    @Test
    fun `对象名重复时报错`() {
        val project = mlProject()
        val first = ProjectObject(
            key = ObjectKey.of("CardData", "Same", "aaaa"),
            kind = ProjectObjectKind.UNIQUE_ID,
            document = JsonDocument.parseOrThrow("""{"UniqueID":"aaaa"}"""),
            relativePath = "CardData/Same.json",
        )
        val second = first.copy(
            key = ObjectKey.of("CardData", "Same", "bbbb"),
            relativePath = "CardData/Same2.json",
        )
        val objects = LinkedHashMap(project.workingTree.objects)
        objects[first.key] = first
        objects[second.key] = second

        val issues = validator.validate(
            project.copy(workingTree = project.workingTree.copy(objects = objects)),
        )

        assertTrue(issues.any { it.code == "object.name.duplicate" && it.isError })
    }

    @Test
    fun `资源键与数据包对象同名时告警`() {
        val project = mlProject()
        val updated = project.copy(
            workingTree = project.workingTree.withResource(
                ProjectResource(
                    id = ResourceId(ResourceKind.IMAGE, "MiniKnife"),
                    typeName = "CardData",
                    relativePath = "Resource/Picture/MiniKnife.png",
                ),
            ),
        )

        assertTrue(
            validator.validate(updated).any {
                it.code == "resource.shadowBuiltin" && it.isWarning
            },
        )
    }

    @Test
    fun `没有数据包时只做工程内冲突检查`() {
        val issues = ConflictValidator().validate(mlProject().withBasePack(null))

        assertTrue(issues.isEmpty(), issues.joinToString())
    }

    @Test
    fun `MC 命名空间为空时不尝试编码数据键`() {
        val project = mcProject().let {
            it.copy(metadata = it.metadata.copy(namespace = ""))
        }

        val issues = ConflictValidator(pack).validate(project)

        assertTrue(issues.any { it.code == "metadata.namespace.blank" })
        assertTrue(issues.none { it.code.startsWith("conflict.") })
    }

    @Test
    fun `资源键为点号时非法`() {
        val project = mlProject()
        val updated = project.copy(
            workingTree = project.workingTree.withResource(
                ProjectResource(
                    id = ResourceId(ResourceKind.IMAGE, "."),
                    typeName = "Sprite",
                    relativePath = "Resource/Picture/.",
                ),
            ),
        )

        assertTrue(
            validator.validate(updated).any { it.code == "resource.key.invalid" },
        )
    }
}
