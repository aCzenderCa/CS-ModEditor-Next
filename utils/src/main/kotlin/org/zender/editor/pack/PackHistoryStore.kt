package org.zender.editor.pack

import org.zender.editor.io.AtomicFileStore
import org.zender.editor.io.FileStore
import org.zender.editor.log.Log
import org.zender.editor.log.Logger
import org.zender.editor.settings.AppSettings
import org.zender.editor.settings.RecentPack
import org.zender.editor.settings.SettingsStore
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock

/**
 * 最近打开的数据包目录记录。
 *
 * - 只保存目录路径与读取到的元信息，不复制数据包内容；
 * - 同一路径去重并移动到最前，最多保留 [AppSettings.MAX_RECENT_PACKS] 条；
 * - 写入复用 [SettingsStore] 的原子写入，与收藏、窗口几何共用同一个设置文件；
 * - 是否仍是有效数据包由 [pruneMissing] / [recordDirectory] 显式检查，读取历史本身不做 IO。
 */
class PackHistoryStore(
    private val settingsStore: SettingsStore,
    private val clock: Clock = Clock.systemUTC(),
    private val fileStore: FileStore = AtomicFileStore(),
    private val logger: Logger = Log.getLogger("pack-history"),
) {

    @Synchronized
    fun recent(limit: Int = AppSettings.MAX_RECENT_PACKS): List<RecentPack> {
        require(limit >= 0) { "limit 不能为负数" }
        return settingsStore.load().recentPackDirectories.take(limit)
    }

    /** 加载成功后记录数据包根目录与元信息。 */
    @Synchronized
    fun record(pack: DataPack): RecentPack = record(pack.root, pack.info)

    @Synchronized
    fun record(root: Path, info: PackInfo): RecentPack {
        val normalized = fileStore.normalize(root)
        val record = RecentPack(
            path = normalized.toString(),
            name = info.name,
            version = info.version,
            isMainPack = info.isMainPack,
            lastOpenedEpochMillis = clock.millis(),
        )
        settingsStore.update { settings -> settings.withRecentPackDirectory(record) }
        logger.debug("记录数据包目录：$normalized")
        return record
    }

    /**
     * 先验证目录中仍存在合法的 `PackInfo.json`，再记录。
     *
     * @return 有效数据包返回写入的记录；目录不存在或 PackInfo 非法时返回 null，历史保持不变。
     */
    @Synchronized
    fun recordDirectory(root: Path): RecentPack? {
        val normalized = fileStore.normalize(root)
        if (!fileStore.isDirectory(normalized)) {
            logger.warn("数据包目录不存在，未记录：$normalized")
            return null
        }
        val result = PackInfoReader.read(
            fileStore,
            normalized.resolve(PackInfoReader.FILE_NAME),
        )
        val info = result.info
        if (info == null) {
            logger.warn("目录不是有效数据包，未记录：$normalized；${result.error?.message}")
            return null
        }
        return record(normalized, info)
    }

    @Synchronized
    fun remove(root: Path): List<RecentPack> {
        val normalized = fileStore.normalize(root).toString()
        val updated = settingsStore.update { settings ->
            settings.withoutRecentPackDirectory(normalized)
        }
        return updated.recentPackDirectories
    }

    @Synchronized
    fun clear(): List<RecentPack> {
        val updated = settingsStore.update { settings ->
            settings.copy(recentPackDirectories = emptyList())
        }
        logger.debug("已清空数据包目录历史")
        return updated.recentPackDirectories
    }

    /**
     * 移除目录不存在或缺少 `PackInfo.json` 的记录，返回保留的历史。
     *
     * 只检查目录与元信息文件是否存在，不解析 PackInfo，避免启动时遍历大型数据包。
     */
    @Synchronized
    fun pruneMissing(): List<RecentPack> {
        val kept = settingsStore.load().recentPackDirectories.filter { record ->
            val path = runCatching { Paths.get(record.path) }.getOrNull()
            path != null &&
                fileStore.isDirectory(path) &&
                fileStore.exists(path.resolve(PackInfoReader.FILE_NAME))
        }
        val updated = settingsStore.update { settings ->
            settings.copy(recentPackDirectories = kept)
        }
        return updated.recentPackDirectories
    }
}
