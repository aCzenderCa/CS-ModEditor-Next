package org.zender.editor.framework

import org.zender.editor.objects.ObjectKey
import org.zender.editor.objects.ReferenceKind

/**
 * 解析后的对象名/数据键。
 *
 * - ML：裸对象名，[namespace] 为 null；
 * - MC：`Namespace:Name` / `Namespace@Name`；短键时 [isShort] 为 true，
 *   [namespace] 为当前 Mod 的命名空间；
 * - 多态引用 `TypeName|Namespace:Name` 的 [typeHint] 为 `TypeName`。
 */
data class DecodedKey(
    val name: String,
    val namespace: String? = null,
    val typeHint: String? = null,
    val isShort: Boolean = false,
)

/**
 * 解析后的引用。引用种类与字段类型一致；[key] 为 GUID、对象名或资源键。
 */
data class DecodedReference(
    val kind: ReferenceKind,
    val key: String,
    val namespace: String? = null,
    val typeHint: String? = null,
    val isShort: Boolean = false,
)

/**
 * 框架键编码。视图层不得自行拼接 GUID / 对象名 / 命名空间键。
 */
interface KeyCodec {

    val framework: FrameworkId

    /** 对象名/数据键。MC 的 [useFileSafeSeparator] 使用 `@` 代替 `:`。 */
    fun encodeObjectName(name: String, useFileSafeSeparator: Boolean = false): String

    fun decodeObjectName(raw: String, typeHint: String? = null): DecodedKey

    /** 引用键：GUID 类型写 GUID；普通对象/资源写对象名/资源名。 */
    fun encodeReference(key: ObjectKey, kind: ReferenceKind): String

    fun decodeReference(
        raw: String,
        kind: ReferenceKind,
        typeHint: String? = null,
    ): DecodedReference

    /** 资源键：ML 裸文件名，MC `Namespace:文件名`。 */
    fun encodeResourceKey(key: String): String = encodeObjectName(key)

    fun decodeResourceKey(
        raw: String,
        kind: ReferenceKind,
        typeHint: String? = null,
    ): DecodedReference = decodeReference(raw, kind, typeHint)

    /** 可用于文件名的数据键（MC 使用 `@`，避免 Windows 不允许的 `:`）。 */
    fun encodeFileNameKey(name: String): String = encodeObjectName(name, useFileSafeSeparator = true)

    /** 新对象在游戏运行时注册的名称（ML `ModName_文件名`，MC `Namespace:文件名`）。 */
    fun registrationName(fileName: String): String

    /** 本地化 Key 约定：ML `ModName_对象_字段`，MC `Namespace_对象_字段`。 */
    fun localizationKey(objectFileName: String, fieldPath: String): String

    /** 把已有的本地化 Key 规范化为当前框架约定。 */
    fun normalizeLocalizationKey(raw: String): String
}
