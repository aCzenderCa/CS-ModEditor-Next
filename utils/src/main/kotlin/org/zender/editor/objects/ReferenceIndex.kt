package org.zender.editor.objects

import org.zender.editor.resource.ResourceEntry
import org.zender.editor.resource.ResourceInventory
import org.zender.editor.resource.ResourceKind

/**
 * 引用目标。框架无关：GUID 或「类型 + 对象名」。
 */
sealed interface ReferenceTarget {
    val type: String

    data class UniqueId(
        override val type: String,
        val uniqueId: String,
    ) : ReferenceTarget

    data class Name(
        override val type: String,
        val name: String,
    ) : ReferenceTarget
}

sealed interface ReferenceResolution {
    data class Resolved(val entry: ObjectEntry) : ReferenceResolution

    data class ResolvedResource(val entry: ResourceEntry) : ReferenceResolution

    data class Ambiguous(
        val candidates: List<ObjectEntry> = emptyList(),
        val resources: List<ResourceEntry> = emptyList(),
    ) : ReferenceResolution {
        init {
            require(candidates.size + resources.size > 1) { "歧义结果至少需要两个候选" }
        }
    }

    data class NotFound(
        val type: String,
        val key: String,
    ) : ReferenceResolution

    data class TypeMismatch(
        val fieldType: String,
        val entry: ObjectEntry,
    ) : ReferenceResolution

    val isResolved: Boolean
        get() = this is Resolved || this is ResolvedResource
}

/**
 * 引用解析：GUID → 对象，对象名 → 类型内对象，资源名 → 图片/音频。
 *
 * 解析只返回描述，不持有对象实例；视图层根据结果渲染「已解析/缺失/歧义/类型不符」。
 */
class ReferenceIndex(
    private val objectIndex: ObjectIndex,
    private val rule: ReferenceRule,
    private val resources: ResourceInventory = ResourceInventory.EMPTY,
) {

    fun resolve(fieldType: String, target: ReferenceTarget): ReferenceResolution =
        when (target) {
            is ReferenceTarget.UniqueId -> resolveUniqueId(fieldType, target.uniqueId)
            is ReferenceTarget.Name -> resolveNameInType(fieldType, target.type, target.name)
        }

    fun resolveUniqueId(fieldType: String, uniqueId: String): ReferenceResolution {
        val entry = objectIndex.findByGuid(uniqueId)
            ?: return ReferenceResolution.NotFound(fieldType, uniqueId)
        return checkCompatibility(fieldType, entry)
    }

    /**
     * 按字段类型解析对象名。字段类型为 `ScriptableObject` 等基类时跨类型搜索，
     * 出现多个同名目标返回 [ReferenceResolution.Ambiguous]。
     */
    fun resolveName(fieldType: String, name: String): ReferenceResolution {
        val kind = rule.kindFor(fieldType)
        val candidates = when {
            kind != null -> objectIndex.find(fieldType, name)?.let(::listOf).orEmpty()
            fieldType == UNIQUE_ID_BASE ->
                objectIndex.findAllByName(name).filter {
                    rule.kindFor(it.type) == ReferenceKind.UNIQUE_ID
                }

            isGenericFieldType(fieldType) -> objectIndex.findAllByName(name)
            else -> emptyList()
        }
        return when {
            candidates.size == 1 -> checkCompatibility(fieldType, candidates.single())
            candidates.size > 1 -> ReferenceResolution.Ambiguous(candidates = candidates)
            else -> resolveResource(fieldType, name)
        }
    }

    /**
     * 按字段语义解析原始引用字符串：
     * - 已知 GUID 类型直接按 GUID；
     * - 基类字段先尝试 GUID，再按对象名；
     * - 其余按对象名/资源名。
     */
    fun resolveRaw(fieldType: String, raw: String): ReferenceResolution {
        val value = raw.trim()
        if (value.isEmpty()) return ReferenceResolution.NotFound(fieldType, value)
        val kind = rule.kindFor(fieldType)
        if (kind == ReferenceKind.UNIQUE_ID) {
            return resolveUniqueId(fieldType, value)
        }
        if (isGenericFieldType(fieldType) && objectIndex.findByGuid(value) != null) {
            return resolveUniqueId(fieldType, value)
        }
        return resolveName(fieldType, value)
    }

    /** 字段类型可选的引用目标（供选择器预热/过滤）。 */
    fun candidates(fieldType: String): List<ObjectEntry> {
        val kind = rule.kindFor(fieldType)
        if (kind != null) return objectIndex.entriesOfType(fieldType)
        if (isGenericFieldType(fieldType)) {
            return objectIndex.entries.filter { rule.isReferenceType(it.type) }
        }
        return emptyList()
    }

    /** 按 GUID 或对象名查找目标（用于搜索/调试，不校验字段类型）。 */
    fun find(key: String): List<ObjectEntry> {
        objectIndex.findByGuid(key)?.let { return listOf(it) }
        return objectIndex.findAllByName(key)
    }

    private fun resolveNameInType(
        fieldType: String,
        targetType: String,
        name: String,
    ): ReferenceResolution {
        if (targetType.isBlank()) return resolveName(fieldType, name)
        val entry = objectIndex.find(targetType, name)
            ?: return ReferenceResolution.NotFound(targetType, name)
        return checkCompatibility(fieldType, entry)
    }

    private fun resolveResource(fieldType: String, name: String): ReferenceResolution {
        val kind = rule.kindFor(fieldType)
        val resourceKind = when {
            kind == ReferenceKind.SPRITE || fieldType in SPRITE_TYPES -> ResourceKind.IMAGE
            kind == ReferenceKind.AUDIO || fieldType == AUDIO_TYPE -> ResourceKind.AUDIO
            else -> return ReferenceResolution.NotFound(fieldType, name)
        }
        val candidates = resources.findAll(name).filter { it.kind == resourceKind }
        return when (candidates.size) {
            0 -> ReferenceResolution.NotFound(fieldType, name)
            1 -> ReferenceResolution.ResolvedResource(candidates.single())
            else -> ReferenceResolution.Ambiguous(resources = candidates)
        }
    }

    private fun checkCompatibility(
        fieldType: String,
        entry: ObjectEntry,
    ): ReferenceResolution {
        if (fieldType == UNIQUE_ID_BASE) {
            return if (rule.kindFor(entry.type) == ReferenceKind.UNIQUE_ID) {
                ReferenceResolution.Resolved(entry)
            } else {
                ReferenceResolution.TypeMismatch(fieldType, entry)
            }
        }
        if (isGenericFieldType(fieldType)) {
            return if (rule.isReferenceType(entry.type)) {
                ReferenceResolution.Resolved(entry)
            } else {
                ReferenceResolution.TypeMismatch(fieldType, entry)
            }
        }
        val compatibility = rule.isCompatible(fieldType, entry.type)
        return if (compatibility.compatible) {
            ReferenceResolution.Resolved(entry)
        } else {
            ReferenceResolution.TypeMismatch(fieldType, entry)
        }
    }

    private fun isGenericFieldType(fieldType: String): Boolean =
        fieldType in GENERIC_FIELD_TYPES

    companion object {
        private const val UNIQUE_ID_BASE = "UniqueIDScriptable"
        private const val AUDIO_TYPE = "AudioClip"

        private val SPRITE_TYPES = setOf("Sprite", "Texture", "Texture2D")

        private val GENERIC_FIELD_TYPES = setOf(
            "ScriptableObject",
            "UnityEngine.Object",
            "Object",
            UNIQUE_ID_BASE,
        )
    }
}
