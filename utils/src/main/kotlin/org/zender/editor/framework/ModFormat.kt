package org.zender.editor.framework

import org.zender.editor.localization.LocalizationFileSpec
import org.zender.editor.resource.ResourceKind

/**
 * Mod 框架格式：目录布局、元数据、引用键与本地化约定。
 *
 * 框架差异集中在这里；读取器/写入器/校验器只通过该接口取路径与能力，
 * 不在业务代码中写死 `Data/`、`Localization/` 等字符串。
 */
interface ModFormat {

    val id: FrameworkId

    val metadataFileName: String

    val capabilities: Set<FrameworkCapability>

    fun supports(capability: FrameworkCapability): Boolean =
        capability in capabilities

    fun readMetadata(text: String): ModMetadataParseResult

    fun writeMetadata(metadata: ModMetadata): String

    fun keyCodec(metadata: ModMetadata): KeyCodec

    /** UniqueIDScriptable 对象目录，例如 ML `CardData` / MC `Data/CardData`。 */
    fun uniqueIdObjectDirectory(type: String): String

    /** 无 GUID ScriptableObject 目录。 */
    fun scriptableObjectDirectory(type: String): String

    fun gameSourceModifyDirectory(): String

    /** 仅 MC 支持；ML 返回 null。 */
    fun dataObjectModifyDirectory(type: String): String?

    fun localizationRoot(): String

    fun defaultLocalizationFiles(): List<LocalizationFileSpec>

    fun resourceDirectory(kind: ResourceKind): String

    /** 读取工程时用于跳过非对象目录。 */
    fun isReservedRootDirectory(name: String): Boolean

    fun objectPath(type: String, name: String, uniqueId: Boolean): String =
        if (uniqueId) {
            "${uniqueIdObjectDirectory(type)}/$name.json"
        } else {
            "${scriptableObjectDirectory(type)}/$name.json"
        }

    fun gameSourceModifyPath(targetGuid: String, category: String? = null): String {
        val directory = gameSourceModifyDirectory()
        val prefix = category?.takeIf { it.isNotBlank() }?.let { "${it.replace('\\', '/')}/" }.orEmpty()
        return "$directory/${prefix}$targetGuid.json"
    }

    fun dataObjectModifyPath(type: String, dataKey: String): String? {
        val directory = dataObjectModifyDirectory(type) ?: return null
        return "$directory/${dataKey.replace(':', '@')}.json"
    }
}
