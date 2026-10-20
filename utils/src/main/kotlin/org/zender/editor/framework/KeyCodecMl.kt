package org.zender.editor.framework

import org.zender.editor.objects.ObjectKey
import org.zender.editor.objects.ReferenceKind

/**
 * ML 引用键：
 * - UniqueIDScriptable → GUID；
 * - 无 GUID ScriptableObject → 裸对象名；
 * - Sprite / AudioClip → 文件名（去扩展名）。
 *
 * `ModName_` 只用于对象在游戏运行时的注册名与本地化 Key，不用于引用键。
 */
class KeyCodecMl(
    val modName: String,
) : KeyCodec {

    init {
        require(modName.isNotBlank()) { "ML Mod Name 不能为空" }
    }

    override val framework: FrameworkId get() = FrameworkId.ML

    override fun encodeObjectName(name: String, useFileSafeSeparator: Boolean): String {
        require(name.isNotBlank()) { "对象名不能为空" }
        return name
    }

    override fun decodeObjectName(raw: String, typeHint: String?): DecodedKey {
        val name = raw.trim()
        require(name.isNotEmpty()) { "对象名不能为空" }
        return DecodedKey(name = name, typeHint = typeHint)
    }

    override fun encodeReference(key: ObjectKey, kind: ReferenceKind): String =
        when (kind) {
            ReferenceKind.UNIQUE_ID -> key.uniqueId?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("对象 ${key.type}/${key.name} 缺少 UniqueID")

            ReferenceKind.PLAIN_OBJECT,
            ReferenceKind.SPRITE,
            ReferenceKind.AUDIO,
            -> key.name
        }

    override fun decodeReference(
        raw: String,
        kind: ReferenceKind,
        typeHint: String?,
    ): DecodedReference {
        val value = raw.trim()
        require(value.isNotEmpty()) { "引用键不能为空" }
        return when (kind) {
            ReferenceKind.UNIQUE_ID -> DecodedReference(
                kind = kind,
                key = value,
                typeHint = typeHint,
            )

            ReferenceKind.PLAIN_OBJECT,
            ReferenceKind.SPRITE,
            ReferenceKind.AUDIO,
            -> {
                val decoded = decodeObjectName(value, typeHint)
                DecodedReference(
                    kind = kind,
                    key = decoded.name,
                    typeHint = decoded.typeHint,
                    isShort = true,
                )
            }
        }
    }

    override fun registrationName(fileName: String): String {
        require(fileName.isNotBlank()) { "文件名不能为空" }
        return "${modName}_$fileName"
    }

    override fun localizationKey(objectFileName: String, fieldPath: String): String {
        val base = if (fieldPath.isBlank()) objectFileName else "${objectFileName}_$fieldPath"
        return normalizeLocalizationKey(base)
    }

    override fun normalizeLocalizationKey(raw: String): String {
        val value = raw.trim()
        require(value.isNotEmpty()) { "本地化 Key 不能为空" }
        val prefix = "${modName}_"
        return if (value.startsWith(prefix)) value else "$prefix$value"
    }
}
