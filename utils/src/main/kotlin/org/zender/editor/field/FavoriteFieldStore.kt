package org.zender.editor.field

import org.zender.editor.log.Log
import org.zender.editor.log.Logger
import org.zender.editor.settings.FavoriteField
import org.zender.editor.settings.SettingsStore

/**
 * 收藏字段持久化。
 *
 * 收藏以「类型 + 字段路径」为键，存储在应用设置中，与具体工程无关；
 * 字段路径相对对象根节点，例如 `CardType` 或 `CardName.DefaultText`。
 *
 * 所有修改立即通过 [SettingsStore] 原子写回，保证切换对象、重开工程后仍然有效。
 */
class FavoriteFieldStore(
    private val settingsStore: SettingsStore,
    private val logger: Logger = Log.getLogger("favorites"),
) {

    @Synchronized
    fun favorites(): List<FavoriteField> = settingsStore.load().favoriteFields

    @Synchronized
    fun favoritePaths(type: String): Set<String> =
        settingsStore.load().favoriteFields
            .filter { it.type == type }
            .mapTo(LinkedHashSet()) { it.fieldPath }

    @Synchronized
    fun isFavorite(type: String, fieldPath: String): Boolean =
        settingsStore.load().favoriteFields.any { it.type == type && it.fieldPath == fieldPath }

    /**
     * 设置或取消收藏，返回最新的完整收藏列表。
     */
    @Synchronized
    fun setFavorite(type: String, fieldPath: String, favorite: Boolean): List<FavoriteField> {
        require(type.isNotBlank()) { "收藏字段的类型不能为空" }
        require(fieldPath.isNotBlank()) { "收藏字段的路径不能为空" }
        val updated = settingsStore.update { settings ->
            if (favorite) {
                settings.withFavoriteField(type, fieldPath)
            } else {
                settings.withoutFavoriteField(type, fieldPath)
            }
        }
        return updated.favoriteFields
    }

    /**
     * 切换收藏状态，返回切换后的状态。
     */
    @Synchronized
    fun toggle(type: String, fieldPath: String): Boolean {
        val favorite = !isFavorite(type, fieldPath)
        setFavorite(type, fieldPath, favorite)
        return favorite
    }

    @Synchronized
    fun removeAll(type: String): List<FavoriteField> {
        val updated = settingsStore.update { settings ->
            settings.copy(favoriteFields = settings.favoriteFields.filterNot { it.type == type })
        }
        return updated.favoriteFields
    }

    @Synchronized
    fun clear(): List<FavoriteField> {
        val updated = settingsStore.update { it.copy(favoriteFields = emptyList()) }
        logger.debug("已清空字段收藏")
        return updated.favoriteFields
    }
}
