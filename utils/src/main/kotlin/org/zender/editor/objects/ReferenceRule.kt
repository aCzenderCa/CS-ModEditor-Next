package org.zender.editor.objects

/**
 * 引用种类（框架无关部分）。
 *
 * 最终引用键的编码由 D 区的 `KeyCodec` 按 ML/MC 框架完成；
 * 本层只负责「字段是否引用类型」「目标类型是否兼容」。
 */
enum class ReferenceKind {
    /** UniqueIDScriptable：以 GUID 引用。 */
    UNIQUE_ID,

    /** 无 GUID 的 ScriptableObject：以对象名引用。 */
    PLAIN_OBJECT,

    /** Sprite / Texture 等图片资源。 */
    SPRITE,

    /** AudioClip 等音频资源。 */
    AUDIO,
}

data class ReferenceCompatibility(
    val compatible: Boolean,
    val reason: String? = null,
) {
    companion object {
        val COMPATIBLE: ReferenceCompatibility = ReferenceCompatibility(true)

        fun incompatible(reason: String): ReferenceCompatibility =
            ReferenceCompatibility(false, reason)
    }
}

/**
 * 字段类型与引用目标的兼容规则。
 *
 * 数据包不提供 C# 类型继承树，因此这里只做框架无关的可判定部分：
 * - 类型名完全一致；
 * - 字段声明为 `ScriptableObject` / `Object` 等基类时接受任意引用类型；
 * - `UniqueIDScriptable` 接受带 GUID 的目标。
 *
 * ML/MC 的键编码、命名空间与资源键由后续 `KeyCodec` 处理。
 */
class ReferenceRule private constructor(
    private val kinds: Map<String, ReferenceKind>,
) {

    fun isReferenceType(typeName: String): Boolean = kinds.containsKey(typeName)

    fun kindFor(typeName: String): ReferenceKind? = kinds[typeName]

    val referenceTypes: Set<String>
        get() = kinds.keys

    fun isCompatible(fieldType: String, targetType: String): ReferenceCompatibility {
        if (fieldType == targetType) return ReferenceCompatibility.COMPATIBLE
        val targetKind = kindFor(targetType)
            ?: return ReferenceCompatibility.incompatible("目标类型 $targetType 不是引用类型")
        if (fieldType in GENERIC_OBJECT_TYPES) return ReferenceCompatibility.COMPATIBLE
        if (fieldType == UNIQUE_ID_BASE && targetKind == ReferenceKind.UNIQUE_ID) {
            return ReferenceCompatibility.COMPATIBLE
        }
        return ReferenceCompatibility.incompatible(
            "字段类型 $fieldType 与目标类型 $targetType 不兼容",
        )
    }

    companion object {
        private const val UNIQUE_ID_BASE = "UniqueIDScriptable"

        private val GENERIC_OBJECT_TYPES = setOf(
            "ScriptableObject",
            "UnityEngine.Object",
            "Object",
        )

        private val SPRITE_TYPES = setOf("Sprite", "Texture", "Texture2D")
        private const val AUDIO_TYPE = "AudioClip"

        fun of(kinds: Map<String, ReferenceKind>): ReferenceRule =
            ReferenceRule(kinds.toMap())

        /**
         * 根据数据包索引推断引用类型：
         * - 有 `GUID` 索引 → [ReferenceKind.UNIQUE_ID]；
         * - 有 `Name` 索引 → Sprite/Texture 为 [ReferenceKind.SPRITE]，
         *   AudioClip 为 [ReferenceKind.AUDIO]，其余为 [ReferenceKind.PLAIN_OBJECT]。
         */
        fun from(objectIndex: ObjectIndex): ReferenceRule {
            val kinds = LinkedHashMap<String, ReferenceKind>()
            for (type in objectIndex.nameIndexTypes) {
                kinds[type] = when (type) {
                    in SPRITE_TYPES -> ReferenceKind.SPRITE
                    AUDIO_TYPE -> ReferenceKind.AUDIO
                    else -> ReferenceKind.PLAIN_OBJECT
                }
            }
            // GUID 索引优先：同一类型如果同时出现在两个索引中，以 GUID 为准。
            for (type in objectIndex.guidIndexTypes) {
                kinds[type] = ReferenceKind.UNIQUE_ID
            }
            return ReferenceRule(kinds)
        }
    }
}
