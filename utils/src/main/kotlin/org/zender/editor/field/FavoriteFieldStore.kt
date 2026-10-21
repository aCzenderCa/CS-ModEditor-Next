package org.zender.editor.field

import org.zender.editor.log.Log
import org.zender.editor.log.Logger
import org.zender.editor.settings.FavoriteField
import org.zender.editor.settings.FavoriteFolder
import org.zender.editor.settings.SettingsStore
import java.util.UUID

/**
 * 收藏字段与收藏夹的持久化。
 *
 * 收藏以「类型 + 字段路径」为键，存储在应用设置中，与具体工程无关；
 * 字段可以放进不同的收藏夹，同一字段在所有收藏夹中保持唯一。
 */
class FavoriteFieldStore(
    private val settingsStore: SettingsStore,
    private val logger: Logger = Log.getLogger("favorites"),
) {

    @Synchronized
    fun folders(): List<FavoriteFolder> = settingsStore.load().effectiveFavoriteFolders()

    @Synchronized
    fun favorites(): List<FavoriteField> = settingsStore.load().allFavoriteFields()

    @Synchronized
    fun favoritePaths(type: String): Set<String> =
        favorites().filter { it.type == type }.mapTo(LinkedHashSet()) { it.fieldPath }

    @Synchronized
    fun isFavorite(type: String, fieldPath: String): Boolean =
        favorites().any { it.type == type && it.fieldPath == fieldPath }

    /** 设置或取消收藏，返回最新的完整收藏列表。 */
    @Synchronized
    fun setFavorite(
        type: String,
        fieldPath: String,
        favorite: Boolean,
        folderId: String? = null,
    ): List<FavoriteField> {
        require(type.isNotBlank()) { "收藏字段的类型不能为空" }
        require(fieldPath.isNotBlank()) { "收藏字段的路径不能为空" }
        val updated = settingsStore.update { settings ->
            if (favorite) {
                settings.withFavoriteField(type, fieldPath, folderId)
            } else {
                settings.withoutFavoriteField(type, fieldPath)
            }
        }
        return updated.allFavoriteFields()
    }

    /** 切换收藏状态，返回切换后的状态。 */
    @Synchronized
    fun toggle(type: String, fieldPath: String, folderId: String? = null): Boolean {
        val favorite = !isFavorite(type, fieldPath)
        setFavorite(type, fieldPath, favorite, folderId)
        return favorite
    }

    /** 把字段放进指定收藏夹。 */
    @Synchronized
    fun addToFolder(type: String, fieldPath: String, folderId: String): List<FavoriteField> =
        setFavorite(type, fieldPath, favorite = true, folderId = folderId)

    @Synchronized
    fun addFolder(name: String): FavoriteFolder {
        val trimmed = name.trim().ifBlank { "新收藏夹" }
        val folder = FavoriteFolder(
            id = "folder-" + UUID.randomUUID().toString().take(8),
            name = trimmed,
        )
        settingsStore.update { it.withFavoriteFolder(folder) }
        logger.debug("已创建收藏夹：$trimmed")
        return folder
    }

    @Synchronized
    fun removeFolder(id: String) {
        settingsStore.update { it.withoutFavoriteFolder(id) }
    }

    @Synchronized
    fun renameFolder(id: String, name: String) {
        settingsStore.update { it.renameFavoriteFolder(id, name) }
    }

    @Synchronized
    fun removeAll(type: String): List<FavoriteField> {
        val updated = settingsStore.update { settings ->
            settings.effectiveFavoriteFolders().fold(settings) { current, folder ->
                folder.fields.filter { it.type == type }.fold(current) { acc, field ->
                    acc.withoutFavoriteField(field.type, field.fieldPath)
                }
            }
        }
        return updated.allFavoriteFields()
    }

    @Synchronized
    fun clear(): List<FavoriteField> {
        val updated = settingsStore.update { it.clearFavoriteFields() }
        logger.debug("已清空字段收藏")
        return updated.allFavoriteFields()
    }
}
