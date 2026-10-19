package org.zender.editor.objects

/**
 * 游戏对象的三元键：类型名 + 对象名 + 可选 GUID。
 *
 * 该模型在后续的 Mod 工程、引用解析与星图中复用；
 * 无 GUID 的 `ScriptableObject` 将 [uniqueId] 保持为 null。
 */
data class ObjectKey(
    val type: String,
    val name: String,
    val uniqueId: String? = null,
) {
    init {
        require(type.isNotBlank()) { "对象类型不能为空" }
        require(name.isNotBlank()) { "对象名不能为空" }
    }

    val hasUniqueId: Boolean
        get() = !uniqueId.isNullOrBlank()

    companion object {
        /**
         * 把空白 GUID 规范化为 null，避免同一对象出现两种键。
         */
        fun of(type: String, name: String, uniqueId: String? = null): ObjectKey =
            ObjectKey(type, name, uniqueId?.takeIf { it.isNotBlank() })
    }
}
