package org.zender.editor.framework

import org.zender.editor.objects.ObjectKey
import org.zender.editor.objects.ReferenceKind

/**
 * MC 数据键：
 * - UniqueIDScriptable → 原始 GUID；
 * - 其他对象/资源 → `Namespace:Name`（也接受 `Namespace@Name`）；
 * - 多态引用 `TypeName|Namespace:Name`。
 *
 * 写入文件名时使用 [encodeFileNameKey]（`@` 代替 `:`），避免 Windows 文件名限制。
 */
class KeyCodecMc(
    val namespace: String,
) : KeyCodec {

    init {
        require(namespace.isNotBlank()) { "MC Namespace 不能为空" }
    }

    override val framework: FrameworkId get() = FrameworkId.MC

    override fun encodeObjectName(name: String, useFileSafeSeparator: Boolean): String {
        require(name.isNotBlank()) { "对象名不能为空" }
        val separator = if (useFileSafeSeparator) '@' else ':'
        if (name.contains(':') || name.contains('@')) {
            return if (useFileSafeSeparator) name.replace(':', '@') else name
        }
        return "$namespace$separator$name"
    }

    override fun decodeObjectName(raw: String, typeHint: String?): DecodedKey {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "对象名不能为空" }

        var value = trimmed
        var hint = typeHint
        val pipe = value.indexOf('|')
        if (pipe > 0) {
            hint = value.substring(0, pipe).trim().ifEmpty { hint }
            value = value.substring(pipe + 1).trim()
        }

        val separatorIndex = value.indexOfFirst { it == ':' || it == '@' }
        if (separatorIndex > 0) {
            return DecodedKey(
                name = value.substring(separatorIndex + 1).trim(),
                namespace = value.substring(0, separatorIndex).trim(),
                typeHint = hint,
                isShort = false,
            )
        }
        return DecodedKey(
            name = value,
            namespace = namespace,
            typeHint = hint,
            isShort = true,
        )
    }

    override fun encodeReference(key: ObjectKey, kind: ReferenceKind): String =
        when (kind) {
            ReferenceKind.UNIQUE_ID -> key.uniqueId?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("对象 ${key.type}/${key.name} 缺少 UniqueID")

            ReferenceKind.PLAIN_OBJECT,
            ReferenceKind.SPRITE,
            ReferenceKind.AUDIO,
            -> encodeObjectName(key.name)
        }

    override fun decodeReference(
        raw: String,
        kind: ReferenceKind,
        typeHint: String?,
    ): DecodedReference {
        val value = raw.trim()
        require(value.isNotEmpty()) { "引用键不能为空" }
        if (kind == ReferenceKind.UNIQUE_ID) {
            var hint = typeHint
            var guid = value
            val pipe = value.indexOf('|')
            if (pipe > 0) {
                hint = value.substring(0, pipe).trim().ifEmpty { hint }
                guid = value.substring(pipe + 1).trim()
            }
            return DecodedReference(kind = kind, key = guid, typeHint = hint)
        }
        val decoded = decodeObjectName(value, typeHint)
        return DecodedReference(
            kind = kind,
            key = decoded.name,
            namespace = decoded.namespace,
            typeHint = decoded.typeHint,
            isShort = decoded.isShort,
        )
    }

    override fun encodeFileNameKey(name: String): String =
        encodeObjectName(name, useFileSafeSeparator = true)

    override fun registrationName(fileName: String): String {
        require(fileName.isNotBlank()) { "文件名不能为空" }
        return "$namespace:$fileName"
    }

    override fun localizationKey(objectFileName: String, fieldPath: String): String {
        val base = if (fieldPath.isBlank()) objectFileName else "${objectFileName}_$fieldPath"
        return normalizeLocalizationKey(base)
    }

    override fun normalizeLocalizationKey(raw: String): String {
        val value = raw.trim()
        require(value.isNotEmpty()) { "本地化 Key 不能为空" }
        val prefix = "${namespace}_"
        return if (value.startsWith(prefix)) value else "$prefix$value"
    }

    companion object {
        fun isValidNamespace(value: String): Boolean = ModNamespace.isValid(value)
    }
}
