package org.zender.editor.framework

import org.zender.editor.localization.LocalizationFileFormat
import org.zender.editor.localization.LocalizationLanguage
import org.zender.editor.resource.ResourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ModFormatTest {

    @Test
    fun `ML 与 MC 元数据文件名`() {
        assertEquals("ModInfo.json", MlFormat.metadataFileName)
        assertEquals("ModMeta.json", McFormat.metadataFileName)
        assertEquals(FrameworkId.ML, MlFormat.id)
        assertEquals(FrameworkId.MC, McFormat.id)
    }

    @Test
    fun `框架能力矩阵`() {
        assertTrue(MlFormat.supports(FrameworkCapability.MOD_ARCH))
        assertTrue(MlFormat.supports(FrameworkCapability.ASSET_BUNDLE))
        assertTrue(MlFormat.supports(FrameworkCapability.LOCALIZATION_THREE_COLUMN))
        assertTrue(MlFormat.supports(FrameworkCapability.MATCH_TAG_INTERSECTION))
        assertFalse(MlFormat.supports(FrameworkCapability.DATA_OBJECT_MODIFY))
        assertFalse(MlFormat.supports(FrameworkCapability.PATCH_OVERRIDE))

        assertTrue(McFormat.supports(FrameworkCapability.DATA_OBJECT_MODIFY))
        assertTrue(McFormat.supports(FrameworkCapability.LOCALIZATION_LAST_COLUMN))
        assertTrue(McFormat.supports(FrameworkCapability.PATCH_OVERRIDE))
        assertTrue(McFormat.supports(FrameworkCapability.PATCH_MATCH_TARGETS))
        assertTrue(McFormat.supports(FrameworkCapability.MATCH_TAG_UNION))
        assertFalse(McFormat.supports(FrameworkCapability.MOD_ARCH))
        assertFalse(McFormat.supports(FrameworkCapability.ASSET_BUNDLE))
    }

    @Test
    fun `目录布局`() {
        assertEquals("CardData", MlFormat.uniqueIdObjectDirectory("CardData"))
        assertEquals("ScriptableObject/CardTag", MlFormat.scriptableObjectDirectory("CardTag"))
        assertEquals("GameSourceModify", MlFormat.gameSourceModifyDirectory())
        assertNull(MlFormat.dataObjectModifyDirectory("CardTag"))
        assertEquals("Localization", MlFormat.localizationRoot())
        assertEquals("Resource/Picture", MlFormat.resourceDirectory(ResourceKind.IMAGE))
        assertEquals("Resource/Audio", MlFormat.resourceDirectory(ResourceKind.AUDIO))

        assertEquals("Data/CardData", McFormat.uniqueIdObjectDirectory("CardData"))
        assertEquals("Data/ScriptableObject/CardTag", McFormat.scriptableObjectDirectory("CardTag"))
        assertEquals("Data/GameSourceModify", McFormat.gameSourceModifyDirectory())
        assertEquals("Data/DataObjectModify/CardTag", McFormat.dataObjectModifyDirectory("CardTag"))
        assertEquals("Resource/Localization", McFormat.localizationRoot())
        assertEquals("Resource/Texture2D", McFormat.resourceDirectory(ResourceKind.IMAGE))
        assertEquals("Resource/AudioClip", McFormat.resourceDirectory(ResourceKind.AUDIO))
    }

    @Test
    fun `对象路径与补丁路径`() {
        assertEquals("CardData/Knife.json", MlFormat.objectPath("CardData", "Knife", true))
        assertEquals(
            "ScriptableObject/CardTag/tag_X.json",
            MlFormat.objectPath("CardTag", "tag_X", false),
        )
        assertEquals(
            "GameSourceModify/Category/1111.json",
            MlFormat.gameSourceModifyPath("1111", "Category"),
        )

        assertEquals(
            "Data/DataObjectModify/CardTag/FixtureMc@tag_X.json",
            McFormat.dataObjectModifyPath("CardTag", "FixtureMc:tag_X"),
        )
        assertEquals(
            "Data/GameSourceModify/1111.json",
            McFormat.gameSourceModifyPath("1111"),
        )
    }

    @Test
    fun `默认本地化文件布局`() {
        val ml = MlFormat.defaultLocalizationFiles()
        assertEquals(
            listOf("Localization/SimpCn.csv", "Localization/SimpEn.csv"),
            ml.map { it.relativePath },
        )
        assertTrue(ml.all { it.format == LocalizationFileFormat.THREE_COLUMN })
        assertEquals(LocalizationLanguage.CHINESE, ml.first().language)

        val mc = McFormat.defaultLocalizationFiles()
        assertEquals(
            listOf("Resource/Localization/En.csv", "Resource/Localization/SimpCn.csv"),
            mc.map { it.relativePath },
        )
        assertTrue(mc.all { it.format == LocalizationFileFormat.KEY_VALUE })
        assertEquals(LocalizationLanguage.ENGLISH, mc.first().language)
    }

    @Test
    fun `保留目录识别`() {
        assertTrue(MlFormat.isReservedRootDirectory("ScriptableObject"))
        assertTrue(MlFormat.isReservedRootDirectory("Resource"))
        assertTrue(MlFormat.isReservedRootDirectory(".git"))
        assertFalse(MlFormat.isReservedRootDirectory("CardData"))

        assertTrue(McFormat.isReservedRootDirectory("Data"))
        assertTrue(McFormat.isReservedRootDirectory("Resource"))
        assertFalse(McFormat.isReservedRootDirectory("CardData"))
    }

    @Test
    fun `KeyCodec 框架类型`() {
        val mlMetadata = ModMetadata(
            framework = FrameworkId.ML,
            namespace = "FixtureML",
            names = mapOf(ModMetadata.DEFAULT_LANGUAGE to "FixtureML"),
        )
        val mcMetadata = ModMetadata(
            framework = FrameworkId.MC,
            namespace = "FixtureMc",
            names = mapOf("En" to "Fixture MC"),
        )

        assertTrue(MlFormat.keyCodec(mlMetadata) is KeyCodecMl)
        assertTrue(McFormat.keyCodec(mcMetadata) is KeyCodecMc)
        assertFailsWith<IllegalArgumentException> {
            MlFormat.keyCodec(mcMetadata)
        }
    }
}
