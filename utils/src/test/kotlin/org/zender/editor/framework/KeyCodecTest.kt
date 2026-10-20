package org.zender.editor.framework

import org.zender.editor.objects.ObjectKey
import org.zender.editor.objects.ReferenceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class KeyCodecTest {

    private val ml = KeyCodecMl("FixtureML")
    private val mc = KeyCodecMc("FixtureMc")

    @Test
    fun `ML 引用键使用 GUID 或裸对象名`() {
        val card = ObjectKey.of("CardData", "FixtureKnife", "33333333333333333333333333333333")
        val tag = ObjectKey.of("CardTag", "tag_Fixture")

        assertEquals(
            "33333333333333333333333333333333",
            ml.encodeReference(card, ReferenceKind.UNIQUE_ID),
        )
        assertEquals("tag_Fixture", ml.encodeReference(tag, ReferenceKind.PLAIN_OBJECT))
        assertEquals("fixture", ml.encodeReference(ObjectKey.of("Sprite", "fixture"), ReferenceKind.SPRITE))
    }

    @Test
    fun `ML UniqueID 对象缺少 GUID 时拒绝编码`() {
        assertFailsWith<IllegalArgumentException> {
            ml.encodeReference(ObjectKey.of("CardData", "NoGuid"), ReferenceKind.UNIQUE_ID)
        }
    }

    @Test
    fun `ML 解码与本地化 Key`() {
        val decoded = ml.decodeReference("tag_Fixture", ReferenceKind.PLAIN_OBJECT)
        assertEquals("tag_Fixture", decoded.key)
        assertTrue(decoded.isShort)

        assertEquals("FixtureML_FixtureKnife", ml.registrationName("FixtureKnife"))
        assertEquals(
            "FixtureML_FixtureKnife_CardName",
            ml.localizationKey("FixtureKnife", "CardName"),
        )
        assertEquals(
            "FixtureML_FixtureKnife_CardName",
            ml.normalizeLocalizationKey("FixtureKnife_CardName"),
        )
        assertEquals(
            "FixtureML_FixtureKnife_CardName",
            ml.normalizeLocalizationKey("FixtureML_FixtureKnife_CardName"),
        )
    }

    @Test
    fun `ML 对象名不添加前缀`() {
        assertEquals("tag_Fixture", ml.encodeObjectName("tag_Fixture"))
        assertEquals("tag_Fixture", ml.encodeFileNameKey("tag_Fixture"))
    }

    @Test
    fun `MC 对象名编码为命名空间数据键`() {
        assertEquals("FixtureMc:tag_Fixture", mc.encodeObjectName("tag_Fixture"))
        assertEquals("FixtureMc@tag_Fixture", mc.encodeObjectName("tag_Fixture", useFileSafeSeparator = true))
        assertEquals("FixtureMc@tag_Fixture", mc.encodeFileNameKey("tag_Fixture"))
        assertEquals("FixtureMc:FixtureKnife", mc.registrationName("FixtureKnife"))
    }

    @Test
    fun `MC 解码完整键短键与多态键`() {
        val full = mc.decodeObjectName("Other:tag_X")
        assertEquals("Other", full.namespace)
        assertEquals("tag_X", full.name)
        assertFalse(full.isShort)

        val short = mc.decodeObjectName("tag_X")
        assertEquals("FixtureMc", short.namespace)
        assertTrue(short.isShort)

        val at = mc.decodeObjectName("Other@tag_X")
        assertEquals("Other", at.namespace)
        assertEquals("tag_X", at.name)

        val poly = mc.decodeObjectName("CardTag|Other:tag_X")
        assertEquals("CardTag", poly.typeHint)
        assertEquals("Other", poly.namespace)
        assertEquals("tag_X", poly.name)
    }

    @Test
    fun `MC 引用与资源键`() {
        val card = ObjectKey.of("CardData", "FixtureKnife", "44444444444444444444444444444444")
        val tag = ObjectKey.of("CardTag", "tag_Fixture")

        assertEquals(
            "44444444444444444444444444444444",
            mc.encodeReference(card, ReferenceKind.UNIQUE_ID),
        )
        assertEquals("FixtureMc:tag_Fixture", mc.encodeReference(tag, ReferenceKind.PLAIN_OBJECT))
        assertEquals("FixtureMc:fixture", mc.encodeResourceKey("fixture"))

        val uid = mc.decodeReference(
            "CardData|44444444444444444444444444444444",
            ReferenceKind.UNIQUE_ID,
        )
        assertEquals("44444444444444444444444444444444", uid.key)
        assertEquals("CardData", uid.typeHint)

        val resource = mc.decodeResourceKey("FixtureMc@fixture", ReferenceKind.SPRITE)
        assertEquals("fixture", resource.key)
        assertEquals("FixtureMc", resource.namespace)
    }

    @Test
    fun `MC 本地化 Key 使用命名空间前缀`() {
        assertEquals(
            "FixtureMc_FixtureKnife_CardName",
            mc.localizationKey("FixtureKnife", "CardName"),
        )
        assertEquals(
            "FixtureMc_FixtureKnife_CardName",
            mc.normalizeLocalizationKey("FixtureMc_FixtureKnife_CardName"),
        )
    }

    @Test
    fun `MC 命名空间合法性`() {
        assertTrue(ModNamespace.isValid("FixtureMc"))
        assertFalse(ModNamespace.isValid(""))
        assertFalse(ModNamespace.isValid("Bad:Name"))
        assertFalse(ModNamespace.isValid("Bad@Name"))
        assertFalse(ModNamespace.isValid("Bad|Name"))
    }
}
