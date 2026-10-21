package org.zender.editor.objects

import org.zender.editor.model.EntryOrigin
import java.nio.file.Path

/**
 * 数据包中的一个对象条目。
 *
 * 条目可能来自 `Objects/<Type>/...` 文件，也可能只存在于 `GUID` / `Name` 索引中
 * （例如游戏内置资源）：此时 [relativePath] 为 null，但对象仍然可以作为引用目标。
 */
data class ObjectEntry(
    val key: ObjectKey,
    /** 相对数据包根目录的路径（`Objects/...`）；仅索引条目为 null。 */
    val relativePath: String? = null,
    /** 对象分组。`CardData` 下为 CardType（如 `Item`），其他类型通常为 null。 */
    val group: String? = null,
    /** 索引中的中文显示名，例如 `迷你小刀`。 */
    val displayName: String? = null,
    val origin: EntryOrigin = EntryOrigin.BASE_PACK,
    /** 条目所在的数据包根目录；合并主包/额外包后用于解析 [relativePath]。 */
    val sourceRoot: Path? = null,
) {
    val type: String get() = key.type

    val name: String get() = key.name

    val uniqueId: String? get() = key.uniqueId

    val hasDataFile: Boolean get() = relativePath != null

    val displayText: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: name
}

/**
 * `GUID/<Type>.json`、`GUID/<Type>/<Group>.json` 与 `Name/<Type>.json` 中的一条索引记录。
 */
data class ObjectIndexEntry(
    val type: String,
    val name: String,
    /** GUID 索引为目标 GUID；Name 索引为目标对象名。 */
    val value: String,
    val displayName: String? = null,
    val group: String? = null,
    /** 相对数据包根目录的索引文件路径。 */
    val sourcePath: String = "",
)

/**
 * 索引键 `对象名(中文名)` 的拆分结果。
 */
data class IndexKeyName(
    val name: String,
    val displayName: String? = null,
)

/**
 * 拆分 `BedWoodenDismantled(木床（未放置）)`。
 *
 * 数据包的中文名可能包含全角括号，因此只取第一个 `(` 与最后一个 `)`。
 * 没有括号时整个键就是对象名。
 */
fun parseIndexKey(raw: String): IndexKeyName {
    val trimmed = raw.trim()
    val open = trimmed.indexOf('(')
    if (open > 0 && trimmed.endsWith(')')) {
        val name = trimmed.substring(0, open).trim()
        val display = trimmed.substring(open + 1, trimmed.length - 1).trim()
        if (name.isNotEmpty()) {
            return IndexKeyName(name, display.ifBlank { null })
        }
    }
    return IndexKeyName(trimmed, null)
}
