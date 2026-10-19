package org.zender.editor.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.zender.editor.io.FileStore
import org.zender.editor.log.Log
import org.zender.editor.log.Logger
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 主窗口上一次的几何状态（单位：dp）。
 */
@Serializable
data class WindowSettings(
    val x: Float? = null,
    val y: Float? = null,
    val width: Float = 1440f,
    val height: Float = 900f,
    val maximized: Boolean = false,
)

/**
 * 收藏字段：按「类型 + 字段路径」保存，和具体工程无关。
 */
@Serializable
data class FavoriteField(
    val type: String,
    val fieldPath: String,
)

/**
 * 应用级持久化设置。所有列表都有上限，避免长期使用后无限增长。
 */
@Serializable
data class AppSettings(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val window: WindowSettings = WindowSettings(),
    val favoriteFields: List<FavoriteField> = emptyList(),
    val recentDirectories: List<String> = emptyList(),
    val recentProjects: List<String> = emptyList(),
    val recentPacks: Map<String, String> = emptyMap(),
    val language: String? = null,
) {
    fun withRecentDirectory(directory: Path, limit: Int = MAX_RECENT_ENTRIES): AppSettings =
        copy(recentDirectories = prependUnique(directory.toString(), recentDirectories, limit))

    fun withRecentProject(project: Path, limit: Int = MAX_RECENT_ENTRIES): AppSettings =
        copy(recentProjects = prependUnique(project.toString(), recentProjects, limit))

    fun withRecentPack(game: String, pack: Path): AppSettings =
        copy(recentPacks = recentPacks + (game to pack.toString()))

    fun withFavoriteField(type: String, fieldPath: String): AppSettings {
        val favorite = FavoriteField(type, fieldPath)
        return copy(favoriteFields = (listOf(favorite) + favoriteFields.filterNot { it == favorite }).take(MAX_FAVORITE_FIELDS))
    }

    fun withoutFavoriteField(type: String, fieldPath: String): AppSettings =
        copy(favoriteFields = favoriteFields.filterNot { it.type == type && it.fieldPath == fieldPath })

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val MAX_RECENT_ENTRIES: Int = 10
        const val MAX_FAVORITE_FIELDS: Int = 500

        private fun prependUnique(value: String, values: List<String>, limit: Int): List<String> =
            (listOf(value) + values.filterNot { it == value }).take(limit.coerceAtLeast(0))
    }
}

/**
 * 读写 [AppSettings]。
 *
 * - 文件不存在时返回默认设置；
 * - JSON 损坏或版本不兼容时记录警告并回退到默认设置，不阻塞应用启动；
 * - 保存走 [FileStore] 的原子写入，避免中途退出留下半截文件。
 */
class SettingsStore(
    private val fileStore: FileStore,
    val path: Path,
    private val logger: Logger = Log.getLogger("settings"),
) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun load(): AppSettings {
        if (!fileStore.exists(path)) return AppSettings()
        return try {
            val decoded = json.decodeFromString<AppSettings>(fileStore.readText(path))
            if (decoded.schemaVersion > AppSettings.CURRENT_SCHEMA_VERSION) {
                logger.warn(
                    "设置文件版本 ${decoded.schemaVersion} 高于当前支持的 " +
                        "${AppSettings.CURRENT_SCHEMA_VERSION}，将按兼容模式读取：$path",
                )
            }
            decoded
        } catch (e: Exception) {
            logger.warn("设置文件无法解析，使用默认设置：$path", e)
            AppSettings()
        }
    }

    fun save(settings: AppSettings) {
        fileStore.writeText(path, json.encodeToString(settings))
    }

    /**
     * 读取 → 变换 → 写回，返回写入后的设置。
     */
    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val updated = transform(load())
        save(updated)
        return updated
    }
}

/**
 * 平台默认设置文件位置。
 */
object SettingsLocations {

    /**
     * `csmode.settings.file` 系统属性优先，便于测试和便携模式覆盖。
     */
    fun defaultSettingsFile(): Path {
        System.getProperty("csmode.settings.file")
            ?.takeIf { it.isNotBlank() }
            ?.let { return Paths.get(it).toAbsolutePath().normalize() }

        val home = Paths.get(System.getProperty("user.home"))
        val os = System.getProperty("os.name").lowercase()
        val base = when {
            os.contains("win") ->
                System.getenv("APPDATA")?.let { Paths.get(it) } ?: home.resolve("AppData/Roaming")

            os.contains("mac") -> home.resolve("Library/Application Support")

            else ->
                System.getenv("XDG_CONFIG_HOME")?.let { Paths.get(it) } ?: home.resolve(".config")
        }
        return base.resolve("CS-ModEditor-Next").resolve("settings.json").toAbsolutePath().normalize()
    }
}
