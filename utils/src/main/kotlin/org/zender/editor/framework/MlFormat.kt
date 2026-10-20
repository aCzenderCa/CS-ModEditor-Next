package org.zender.editor.framework

import org.zender.editor.localization.LocalizationFileFormat
import org.zender.editor.localization.LocalizationFileSpec
import org.zender.editor.localization.LocalizationLanguage
import org.zender.editor.resource.ResourceKind

/**
 * ModLoader（ML）目录布局。
 */
data object MlFormat : ModFormat {

    override val id: FrameworkId get() = FrameworkId.ML

    override val metadataFileName: String = "ModInfo.json"

    override val capabilities: Set<FrameworkCapability> = setOf(
        FrameworkCapability.UNIQUE_ID_OBJECTS,
        FrameworkCapability.SCRIPTABLE_OBJECTS,
        FrameworkCapability.GAME_SOURCE_MODIFY,
        FrameworkCapability.ASSET_BUNDLE,
        FrameworkCapability.MOD_ARCH,
        FrameworkCapability.IMAGE_RESOURCES,
        FrameworkCapability.AUDIO_RESOURCES,
        FrameworkCapability.LOCALIZATION_THREE_COLUMN,
        FrameworkCapability.MATCH_TAG_INTERSECTION,
        FrameworkCapability.JSONNET,
    )

    override fun readMetadata(text: String): ModMetadataParseResult =
        ModMetadataReader.parseMl(text)

    override fun writeMetadata(metadata: ModMetadata): String =
        ModMetadataWriter.writeMl(metadata)

    override fun keyCodec(metadata: ModMetadata): KeyCodec {
        require(metadata.framework == FrameworkId.ML) { "元数据不是 ML 格式" }
        return KeyCodecMl(metadata.namespace)
    }

    override fun uniqueIdObjectDirectory(type: String): String = type

    override fun scriptableObjectDirectory(type: String): String = "ScriptableObject/$type"

    override fun gameSourceModifyDirectory(): String = "GameSourceModify"

    override fun dataObjectModifyDirectory(type: String): String? = null

    override fun localizationRoot(): String = "Localization"

    override fun defaultLocalizationFiles(): List<LocalizationFileSpec> = listOf(
        LocalizationFileSpec(
            relativePath = "Localization/SimpCn.csv",
            language = LocalizationLanguage.CHINESE,
            format = LocalizationFileFormat.THREE_COLUMN,
        ),
        LocalizationFileSpec(
            relativePath = "Localization/SimpEn.csv",
            language = LocalizationLanguage.ENGLISH,
            format = LocalizationFileFormat.THREE_COLUMN,
        ),
    )

    override fun resourceDirectory(kind: ResourceKind): String = when (kind) {
        ResourceKind.IMAGE -> "Resource/Picture"
        ResourceKind.AUDIO -> "Resource/Audio"
    }

    override fun isReservedRootDirectory(name: String): Boolean =
        name.startsWith(".") || name in RESERVED_ROOT_DIRECTORIES

    private val RESERVED_ROOT_DIRECTORIES = setOf(
        "ScriptableObject",
        "GameSourceModify",
        "Localization",
        "Resource",
        "plugins",
        "BepInEx",
    )
}
