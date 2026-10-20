package org.zender.editor.objects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ReferenceRuleTest {

    private val rule = ReferenceRule.of(
        mapOf(
            "CardData" to ReferenceKind.UNIQUE_ID,
            "CardTag" to ReferenceKind.PLAIN_OBJECT,
            "Sprite" to ReferenceKind.SPRITE,
            "AudioClip" to ReferenceKind.AUDIO,
        ),
    )

    @Test
    fun `查询引用种类`() {
        assertEquals(ReferenceKind.UNIQUE_ID, rule.kindFor("CardData"))
        assertEquals(ReferenceKind.PLAIN_OBJECT, rule.kindFor("CardTag"))
        assertEquals(ReferenceKind.SPRITE, rule.kindFor("Sprite"))
        assertEquals(ReferenceKind.AUDIO, rule.kindFor("AudioClip"))
        assertNull(rule.kindFor("LocalizedString"))
        assertFalse(rule.isReferenceType("LocalizedString"))
    }

    @Test
    fun `同类型引用兼容`() {
        assertTrue(rule.isCompatible("CardData", "CardData").compatible)
        assertTrue(rule.isCompatible("CardTag", "CardTag").compatible)
    }

    @Test
    fun `基类字段接受任意引用类型`() {
        assertTrue(rule.isCompatible("ScriptableObject", "CardTag").compatible)
        assertTrue(rule.isCompatible("Object", "Sprite").compatible)
        assertTrue(rule.isCompatible("UniqueIDScriptable", "CardData").compatible)
        assertFalse(rule.isCompatible("UniqueIDScriptable", "CardTag").compatible)
    }

    @Test
    fun `不同类型不兼容并给出原因`() {
        val result = rule.isCompatible("CardTag", "CardData")

        assertFalse(result.compatible)
        assertTrue(result.reason?.contains("不兼容") == true)
    }

    @Test
    fun `目标不是引用类型时给出原因`() {
        val result = rule.isCompatible("CardTag", "LocalizedString")

        assertFalse(result.compatible)
        assertTrue(result.reason?.contains("不是引用类型") == true)
    }

    @Test
    fun `根据索引推断引用种类`() {
        val objectIndex = ObjectIndex(
            nameIndexTypes = setOf("CardTag", "Sprite", "Texture2D", "AudioClip"),
            guidIndexTypes = setOf("CardData"),
        )
        val inferred = ReferenceRule.from(objectIndex)

        assertEquals(ReferenceKind.UNIQUE_ID, inferred.kindFor("CardData"))
        assertEquals(ReferenceKind.PLAIN_OBJECT, inferred.kindFor("CardTag"))
        assertEquals(ReferenceKind.SPRITE, inferred.kindFor("Sprite"))
        assertEquals(ReferenceKind.SPRITE, inferred.kindFor("Texture2D"))
        assertEquals(ReferenceKind.AUDIO, inferred.kindFor("AudioClip"))
    }
}
