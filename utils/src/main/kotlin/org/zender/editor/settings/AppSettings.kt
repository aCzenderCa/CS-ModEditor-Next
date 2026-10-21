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
 * 收藏夹：一组收藏字段。[id] 稳定标识，[name] 由用户设置。
 */
@Serializable
data class FavoriteFolder(
    val id: String,
    val name: String,
    val fields: List<FavoriteField> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "收藏夹 id 不能为空" }
        require(name.isNotBlank()) { "收藏夹名称不能为空" }
    }

    val displayName: String get() = name.ifBlank { "收藏夹" }
}

/**
 * 最近打开的数据包目录记录。
 *
 * 保存路径与打开时读取到的元信息，设置页/数据包选择页无需重新扫描即可展示。
 */
@Serializable
data class RecentPack(
    val path: String,
    val name: String = "",
    val version: String = "",
    val isMainPack: Boolean = false,
    /** 最后打开时间，Unix 毫秒。0 表示旧数据或未知。 */
    val lastOpenedEpochMillis: Long = 0L,
) {
    /** 没有记录名称时回退到目录名。 */
    val displayName: String
        get() = name.ifBlank {
            runCatching { Paths.get(path).fileName?.toString().orEmpty() }.getOrDefault(path)
        }
}

/**
 * 主数据包：CSTI 或 CSFF。路径由用户自行选择，不提供默认本机路径。
 */
@Serializable
data class MainPackConfig(
    /** 主包标识：`CSTI` / `CSFF`。 */
    val kind: String,
    val name: String = kind,
    val path: String? = null,
) {
    val displayName: String
        get() = name.ifBlank { kind }
}

/**
 * 用户导入的额外数据包。额外包归属于某个主包，加载时叠加在主包之上。
 *
 * [mainPackKind] 为 `CSTI` 或 `CSFF`；[enabled] 关闭时不会参与合并加载。
 */
@Serializable
data class ExtraPackConfig(
    /** 规范化后的数据包目录，作为稳定 id。 */
    val id: String,
    val name: String,
    val path: String,
    /** 归属的主包，可同时属于 CSTI 与 CSFF。 */
    val mainPackKinds: List<String> = emptyList(),
    /** 框架：`MC` 或 `ML`。不同框架的额外包格式不同，不能混用。 */
    val framework: String,
    val enabled: Boolean = true,
    /** 旧版本的单主包字段；读取时并入 [mainPackKinds]，写回时不再使用。 */
    val mainPackKind: String? = null,
) {
    val displayName: String
        get() = name.ifBlank {
            runCatching { Paths.get(path).fileName?.toString().orEmpty() }.getOrDefault(path)
        }

    /** 去重并规范化为大写；兼容旧版 [mainPackKind]。 */
    val effectiveMainPackKinds: List<String>
        get() = (mainPackKinds + listOfNotNull(mainPackKind))
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .distinct()

    fun belongsTo(kind: String): Boolean =
        effectiveMainPackKinds.any { it.equals(kind, ignoreCase = true) }
}

/**
 * 某个工程选定的数据包组合：主包 + 额外包。
 */
@Serializable
data class ProjectPackTarget(
    val mainPackKind: String,
    val mainPackPath: String,
    /** 组合适用的框架：`MC` / `ML`。 */
    val framework: String = "MC",
    val extraPackPaths: List<String> = emptyList(),
)

/**
 * 已分离的编辑窗口。内容键是 `DocumentId`，窗口几何单位为 dp。
 */
@Serializable
data class DetachedWindowSettings(
    val documentId: String,
    val x: Float? = null,
    val y: Float? = null,
    val width: Float = 1200f,
    val height: Float = 800f,
    val maximized: Boolean = false,
    /** `FIELD` / `RAW` / `DIFF`，读取失败时回退 FIELD。 */
    val viewMode: String = "FIELD",
)

/**
 * 应用级持久化设置。所有列表都有上限，避免长期使用后无限增长。
 */
@Serializable
data class AppSettings(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val window: WindowSettings = WindowSettings(),
    /** 收藏夹；字段收藏按收藏夹组织。 */
    val favoriteFolders: List<FavoriteFolder> = emptyList(),
    /** 旧版单一收藏列表；读取时迁移到 [favoriteFolders]，写回时作为镜像保留。 */
    val favoriteFields: List<FavoriteField> = emptyList(),
    val recentDirectories: List<String> = emptyList(),
    val recentProjects: List<String> = emptyList(),
    val recentPacks: Map<String, String> = emptyMap(),
    /** 两种主包（CSTI / CSFF）的本地目录配置。 */
    val mainPacks: List<MainPackConfig> = DEFAULT_MAIN_PACKS,
    /** 用户导入的额外数据包。 */
    val extraPacks: List<ExtraPackConfig> = emptyList(),
    /** 工程目录 → 主包 + 额外包组合。 */
    val projectPackTargets: Map<String, ProjectPackTarget> = emptyMap(),
    /** 最近打开的数据包目录，按最后打开时间从新到旧排列。 */
    val recentPackDirectories: List<RecentPack> = emptyList(),
    val language: String? = null,
    /** 主题模式：SYSTEM / LIGHT / DARK。 */
    val themeMode: String = "SYSTEM",
    /** 是否在字段视图/检查器显示 WarpType 数值，默认关闭。 */
    val showWarpType: Boolean = false,
    /** 本地化字段展开时默认显示的语言输入框，取值如 `SimpCn` / `En`。 */
    val localizationInputLanguages: List<String> = DEFAULT_LOCALIZATION_LANGUAGES,
    /** 工程目录 → 关联数据包目录，避免每次打开工程重复选择。 */
    val projectPackDirectories: Map<String, String> = emptyMap(),
    /** 已打开工程时再次打开项目的默认行为：ASK / NEW_WINDOW / CURRENT_WINDOW。 */
    val openProjectBehavior: String = "ASK",
    /** 多窗口布局：启动后打开工程时恢复内容键与几何。 */
    val detachedWindows: List<DetachedWindowSettings> = emptyList(),
    /** 文件树分栏宽度（dp），拖动分隔线后保存。 */
    val fileTreeWidth: Float = 260f,
    /** 检查器分栏宽度（dp），拖动分隔线后保存。 */
    val inspectorWidth: Float = 300f,
) {
    fun withRecentDirectory(directory: Path, limit: Int = MAX_RECENT_ENTRIES): AppSettings =
        copy(recentDirectories = prependUnique(directory.toString(), recentDirectories, limit))

    fun withRecentProject(project: Path, limit: Int = MAX_RECENT_ENTRIES): AppSettings =
        copy(recentProjects = prependUnique(project.toString(), recentProjects, limit))

    fun withRecentPack(game: String, pack: Path): AppSettings =
        copy(recentPacks = recentPacks + (game to pack.toString()))

    fun mainPack(kind: String): MainPackConfig? =
        mainPacks.firstOrNull { it.kind.equals(kind, ignoreCase = true) }

    /** 设置某个主包的本地目录。 */
    fun withMainPackPath(kind: String, path: Path?): AppSettings {
        val normalizedKind = kind.uppercase()
        val value = path?.let { normalizePackPath(it.toString()) }
        val existing = mainPacks
        val updated = if (existing.any { it.kind.equals(normalizedKind, ignoreCase = true) }) {
            existing.map {
                if (it.kind.equals(normalizedKind, ignoreCase = true)) it.copy(path = value) else it
            }
        } else {
            existing + MainPackConfig(kind = normalizedKind, path = value)
        }
        return copy(mainPacks = updated)
    }

    /** 新增或按 id / 数据包路径覆盖一条额外数据包配置。 */
    fun withExtraPack(config: ExtraPackConfig): AppSettings {
        val normalized = normalizePackPath(config.path)
        return copy(
            extraPacks = listOf(config) + extraPacks.filterNot {
                it.id == config.id || normalizePackPath(it.path) == normalized
            },
        )
    }

    fun withoutExtraPack(id: String): AppSettings =
        copy(extraPacks = extraPacks.filterNot { it.id == id })

    fun withProjectPackTarget(project: Path, target: ProjectPackTarget): AppSettings {
        val key = normalizePackPath(project.toString())
        return copy(projectPackTargets = projectPackTargets + (key to target))
    }

    private fun normalizePackPath(path: String): String =
        runCatching { Paths.get(path).toAbsolutePath().normalize().toString() }
            .getOrDefault(path)

    /**
     * 把数据包目录移到最前；同一路径只保留最新一条。
     */
    fun withRecentPackDirectory(
        pack: RecentPack,
        limit: Int = MAX_RECENT_PACKS,
    ): AppSettings =
        copy(
            recentPackDirectories = (
                listOf(pack) + recentPackDirectories.filterNot { it.path == pack.path }
            ).take(limit.coerceAtLeast(0)),
        )

    fun withoutRecentPackDirectory(path: String): AppSettings =
        copy(recentPackDirectories = recentPackDirectories.filterNot { it.path == path })

    /** 兼容旧版单一收藏列表：没有收藏夹时把旧字段放进「默认收藏」。 */
    fun effectiveFavoriteFolders(): List<FavoriteFolder> =
        if (favoriteFolders.isNotEmpty() || favoriteFields.isEmpty()) {
            favoriteFolders
        } else {
            listOf(FavoriteFolder(DEFAULT_FAVORITE_FOLDER_ID, "默认收藏", favoriteFields))
        }

    fun allFavoriteFields(): List<FavoriteField> =
        effectiveFavoriteFolders().flatMap { it.fields }.distinct()

    /**
     * 收藏字段。省略 [folderId] 时放进第一个收藏夹；没有收藏夹时自动创建默认收藏夹。
     * 同一字段会从其他收藏夹移除，保证全局唯一。
     */
    fun withFavoriteField(
        type: String,
        fieldPath: String,
        folderId: String? = null,
    ): AppSettings {
        val favorite = FavoriteField(type, fieldPath)
        val folders = effectiveFavoriteFolders().ifEmpty {
            listOf(FavoriteFolder(DEFAULT_FAVORITE_FOLDER_ID, "默认收藏"))
        }
        val targetId = folderId?.takeIf { id -> folders.any { it.id == id } }
            ?: folders.first().id
        val updated = folders.map { folder ->
            val remaining = folder.fields.filterNot { it == favorite }
            if (folder.id == targetId) {
                folder.copy(fields = (listOf(favorite) + remaining).take(MAX_FAVORITE_FIELDS))
            } else {
                folder.copy(fields = remaining)
            }
        }
        return copyWithFavorites(updated)
    }

    fun withoutFavoriteField(type: String, fieldPath: String): AppSettings {
        val favorite = FavoriteField(type, fieldPath)
        return copyWithFavorites(
            effectiveFavoriteFolders().map { folder ->
                folder.copy(fields = folder.fields.filterNot { it == favorite })
            },
        )
    }

    /** 新增收藏夹；同 id 时覆盖。 */
    fun withFavoriteFolder(folder: FavoriteFolder): AppSettings {
        val folders = effectiveFavoriteFolders()
        val updated = if (folders.any { it.id == folder.id }) {
            folders.map { if (it.id == folder.id) folder else it }
        } else {
            folders + folder
        }
        return copyWithFavorites(updated)
    }

    fun withoutFavoriteFolder(id: String): AppSettings =
        copyWithFavorites(effectiveFavoriteFolders().filterNot { it.id == id })

    fun renameFavoriteFolder(id: String, name: String): AppSettings {
        if (name.isBlank()) return this
        return copyWithFavorites(
            effectiveFavoriteFolders().map {
                if (it.id == id) it.copy(name = name.trim()) else it
            },
        )
    }

    fun clearFavoriteFields(): AppSettings =
        copyWithFavorites(effectiveFavoriteFolders().map { it.copy(fields = emptyList()) })

    private fun copyWithFavorites(folders: List<FavoriteFolder>): AppSettings =
        copy(
            favoriteFolders = folders,
            favoriteFields = folders.flatMap { it.fields }.distinct(),
        )

    fun withDetachedWindows(windows: List<DetachedWindowSettings>): AppSettings =
        copy(detachedWindows = windows.take(MAX_DETACHED_WINDOWS))

    fun withoutDetachedWindow(documentId: String): AppSettings =
        copy(detachedWindows = detachedWindows.filterNot { it.documentId == documentId })

    fun withProjectPackDirectory(project: Path, pack: Path): AppSettings {
        val key = project.toAbsolutePath().normalize().toString()
        val value = pack.toAbsolutePath().normalize().toString()
        return copy(projectPackDirectories = projectPackDirectories + (key to value))
    }

    fun withoutProjectPackDirectory(project: String): AppSettings =
        copy(projectPackDirectories = projectPackDirectories - project)

    fun withLocalizationInputLanguage(language: String, enabled: Boolean): AppSettings {
        require(language.isNotBlank()) { "语言标识不能为空" }
        val updated = if (enabled) {
            (localizationInputLanguages + language).distinct()
        } else {
            localizationInputLanguages.filterNot { it == language }
        }
        return copy(localizationInputLanguages = updated)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val MAX_RECENT_ENTRIES: Int = 10
        const val MAX_RECENT_PACKS: Int = 10
        const val MAX_FAVORITE_FIELDS: Int = 500
        const val DEFAULT_FAVORITE_FOLDER_ID: String = "default"
        const val MAX_DETACHED_WINDOWS: Int = 8
        val DEFAULT_LOCALIZATION_LANGUAGES: List<String> = listOf("SimpCn", "En")

        /**
         * 两种主包的标识；本地目录必须由用户自行选择，不预置任何本机路径。
         */
        val DEFAULT_MAIN_PACKS: List<MainPackConfig> = listOf(
            MainPackConfig(kind = "CSTI", name = "Card Survival: Tropical Island"),
            MainPackConfig(kind = "CSFF", name = "Card Survival: Fantasy Forest"),
        )

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

    @Synchronized
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

    @Synchronized
    fun save(settings: AppSettings) {
        fileStore.writeText(path, json.encodeToString(settings))
    }

    /**
     * 读取 → 变换 → 写回，返回写入后的设置。
     */
    @Synchronized
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
