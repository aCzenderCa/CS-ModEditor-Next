package org.zender.editor.pack

import org.zender.editor.fixture.TestFixtures
import org.zender.editor.framework.FrameworkId
import org.zender.editor.framework.MlFormat
import org.zender.editor.framework.ModMetadata
import org.zender.editor.picker.PickerQuery
import org.zender.editor.project.ModProject
import org.zender.editor.project.ProjectReferenceResolver
import org.zender.editor.project.ReferenceCheck
import org.zender.editor.project.ReferenceOrigin
import org.zender.editor.project.WorkingTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NameIndexTest {

    @Test
    fun `CSFF Name 索引中的非 GUID 对象进入对象索引与选择器`() {
        val root = TestFixtures.csffPack ?: return
        val pack = DataPackLoader().load(root).packOrThrow()
        assertTrue(pack.objectIndex.nameIndexTypes.isNotEmpty())

        val type = pack.objectIndex.nameIndexTypes
            .sorted()
            .firstOrNull { candidate ->
                pack.objectIndex.entriesOfType(candidate).any { it.hasDataFile }
            } ?: return
        val entries = pack.objectIndex.entriesOfType(type).filter { it.hasDataFile }
        assertTrue(entries.isNotEmpty(), "类型 $type 没有带数据文件的 Name 索引对象")
        val sample = entries.first()
        assertNull(sample.uniqueId)
        assertNotNull(pack.referenceRule.kindFor(type))
        assertTrue(pack.referenceRule.isReferenceType(type))
        assertTrue(pack.referenceIndex.resolveName(type, sample.name).isResolved)
        assertTrue(pack.picker.query(PickerQuery(fieldType = type, limit = 10)).total > 0)
        assertTrue(
            pack.picker.query(PickerQuery(fieldType = type, limit = PickerQuery.MAX_LIMIT))
                .items.count { it.type == type } >= entries.size,
        )

        // 游戏本体图片通常只有 Name/Sprite.json，没有独立资源文件。
        val sprite = pack.objectIndex.entriesOfType("Sprite").firstOrNull()
        if (sprite != null) {
            val project = ModProject(
                root = null,
                format = MlFormat,
                metadata = ModMetadata(framework = FrameworkId.ML, namespace = "NameTest"),
                workingTree = WorkingTree(),
                basePack = pack,
            )
            val check = ProjectReferenceResolver(project, pack).resolve("Sprite", sprite.name)
            assertTrue(check is ReferenceCheck.Resolved, check.toString())
            assertEquals(ReferenceOrigin.BASE_PACK_RESOURCE, (check as ReferenceCheck.Resolved).origin)
            assertEquals(sprite.name, check.key)
        }
    }
}
