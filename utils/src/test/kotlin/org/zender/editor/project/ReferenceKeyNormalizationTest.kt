package org.zender.editor.project

import org.zender.editor.document.JsonDocument
import org.zender.editor.framework.FrameworkId
import org.zender.editor.framework.McFormat
import org.zender.editor.framework.ModMetadata
import org.zender.editor.objects.ObjectKey
import kotlin.test.Test
import kotlin.test.assertEquals

class ReferenceKeyNormalizationTest {

    private val document = JsonDocument.parseOrThrow("{}")

    private fun project(tree: WorkingTree): ModProject = ModProject(
        root = null,
        format = McFormat,
        metadata = ModMetadata(framework = FrameworkId.MC, namespace = "FixtureMc"),
        workingTree = tree,
    )

    private fun scriptable(type: String, name: String): ProjectObject = ProjectObject(
        key = ObjectKey.of(type, name),
        kind = ProjectObjectKind.SCRIPTABLE_OBJECT,
        document = document,
        relativePath = "Data/ScriptableObject/$type/$name.json",
    )

    private fun unique(type: String, name: String, guid: String): ProjectObject = ProjectObject(
        key = ObjectKey.of(type, name, guid),
        kind = ProjectObjectKind.UNIQUE_ID,
        document = document,
        relativePath = "Data/$type/$name.json",
    )

    @Test
    fun `工程内非 GUID 对象的短键补全命名空间`() {
        val project = project(
            WorkingTree().withObject(scriptable("CardTag", "tag_X")),
        )
        assertEquals(
            "FixtureMc:tag_X",
            project.normalizeReferenceKey("CardTag", "tag_X"),
        )
        assertEquals(
            "FixtureMc:tag_X",
            project.normalizeReferenceKey("ScriptableObject", "tag_X"),
        )
    }

    @Test
    fun `已带命名空间与其他目标保持原样`() {
        val project = project(
            WorkingTree().withObject(scriptable("CardTag", "tag_X")),
        )
        assertEquals("Other:tag_X", project.normalizeReferenceKey("CardTag", "Other:tag_X"))
        assertEquals("CardTag|FixtureMc:tag_X", project.normalizeReferenceKey("CardTag", "CardTag|FixtureMc:tag_X"))
        // 不存在于工程中的短键不加前缀（可能来自游戏本体或其他 Mod）。
        assertEquals("tag_Missing", project.normalizeReferenceKey("CardTag", "tag_Missing"))
    }

    @Test
    fun `GUID 对象不使用命名空间引用`() {
        val project = project(
            WorkingTree().withObject(
                unique("CardData", "Knife", "44444444444444444444444444444444"),
            ),
        )
        assertEquals(
            "Knife",
            project.normalizeReferenceKey("UniqueIDScriptable", "Knife"),
        )
        assertEquals(
            "44444444444444444444444444444444",
            project.normalizeReferenceKey("UniqueIDScriptable", "44444444444444444444444444444444"),
        )
    }
}
