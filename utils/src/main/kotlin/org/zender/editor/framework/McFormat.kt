package org.zender.editor.framework

import org.zender.editor.localization.LocalizationFileFormat
import org.zender.editor.localization.LocalizationFileSpec
import org.zender.editor.localization.LocalizationLanguage
import org.zender.editor.resource.ResourceKind

/**
 * ModCore（MC）目录布局。
 */
data object McFormat : ModFormat {

    override val id: FrameworkId get() = FrameworkId.MC

    override val metadataFileName: String = "ModMeta.json"

    override val capabilities: Set<FrameworkCapability> = setOf(
        FrameworkCapability.UNIQUE_ID_OBJECTS,
        FrameworkCapability.SCRIPTABLE_OBJECTS,
        FrameworkCapability.GAME_SOURCE_MODIFY,
        FrameworkCapability.DATA_OBJECT_MODIFY,
        FrameworkCapability.IMAGE_RESOURCES,
        FrameworkCapability.AUDIO_RESOURCES,
        FrameworkCapability.LOCALIZATION_LAST_COLUMN,
        FrameworkCapability.PATCH_OVERRIDE,
        FrameworkCapability.PATCH_MATCH_TARGETS,
        FrameworkCapability.MATCH_TAG_UNION,
    )

    override fun readMetadata(text: String): ModMetadataParseResult =
        ModMetadataReader.parseMc(text)

    override fun writeMetadata(metadata: ModMetadata): String =
        ModMetadataWriter.writeMc(metadata)

    override fun keyCodec(metadata: ModMetadata): KeyCodec {
        require(metadata.framework == FrameworkId.MC) { "元数据不是 MC 格式" }
        return KeyCodecMc(metadata.namespace)
    }

    override fun uniqueIdObjectDirectory(type: String): String = "Data/$type"

    override fun scriptableObjectDirectory(type: String): String =
        "Data/ScriptableObject/$type"

    override fun gameSourceModifyDirectory(): String = "Data/GameSourceModify"

    override fun dataObjectModifyDirectory(type: String): String =
        "Data/DataObjectModify/$type"

    override fun localizationRoot(): String = "Resource/Localization"

    override fun defaultLocalizationFiles(): List<LocalizationFileSpec> = listOf(
        LocalizationFileSpec(
            relativePath = "Resource/Localization/En.csv",
            language = LocalizationLanguage.ENGLISH,
            format = LocalizationFileFormat.KEY_VALUE,
        ),
        LocalizationFileSpec(
            relativePath = "Resource/Localization/SimpCn.csv",
            language = LocalizationLanguage.CHINESE,
            format = LocalizationFileFormat.KEY_VALUE,
        ),
    )

    override fun resourceDirectory(kind: ResourceKind): String = when (kind) {
        ResourceKind.IMAGE -> "Resource/Texture2D"
        ResourceKind.AUDIO -> "Resource/AudioClip"
    }

    override fun isReservedRootDirectory(name: String): Boolean =
        name.startsWith(".") || name in RESERVED_ROOT_DIRECTORIES

    private val RESERVED_ROOT_DIRECTORIES = setOf(
        "Data",
        "Resource",
        "plugins",
        "BepInEx",
    )
}
