package org.zender.editor.project

/**
 * 类型目录下的子文件夹（相对路径，统一使用 `/`）。
 *
 * 例如 MC 的 `Data/CardData/Item/Weapons/Knife.json` 对应
 * `ProjectFolder(type = "CardData", path = "Item/Weapons", kind = UNIQUE_ID)`。
 * 空文件夹也保存在工作树中，保存工程时会真实创建目录。
 */
data class ProjectFolder(
    val type: String,
    val path: String,
    val kind: ProjectObjectKind = ProjectObjectKind.UNIQUE_ID,
) {
    init {
        require(type.isNotBlank()) { "文件夹类型不能为空" }
        require(path.isNotBlank()) { "文件夹路径不能为空" }
    }
}

/**
 * 工程文件夹路径的解析与规范化。
 *
 * 所有路径都相对「类型目录」，例如 `Data/CardData` 或 `Data/ScriptableObject/CardTag`。
 */
object ProjectFolderPath {

    /**
     * 规范化用户输入的相对目录。
     *
     * - 空字符串返回 null（表示直接放在类型目录下）；
     * - `\` 统一为 `/`，重复的 `/` 会被合并；
     * - 每一段都必须是非空、非 `.`/`..` 的合法文件名。
     *
     * 非法输入抛出 [IllegalArgumentException]，错误消息可直接展示给用户。
     */
    fun normalize(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val segments = value.replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() }
        require(segments.isNotEmpty()) { "文件夹路径不能为空" }
        segments.forEach { segment ->
            require(segment != "." && segment != "..") { "文件夹路径不能包含 . 或 .." }
            require(ProjectObjectFactory.isFileSafeName(segment)) {
                "文件夹名包含非法字符：$segment"
            }
        }
        return segments.joinToString("/")
    }

    /** 返回 `path` 及其所有上级目录（含自身），例如 `a/b` → `a`、`a/b`。 */
    fun ancestors(path: String): List<String> {
        val segments = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        return segments.indices.map { index ->
            segments.subList(0, index + 1).joinToString("/")
        }
    }

    /**
     * 从对象/资源相对路径推导类型目录下的子文件夹。
     *
     * 取路径中最后一个与 [type] 相同的目录段之后的层级；
     * 找不到类型目录时返回 null。
     */
    fun folderOf(relativePath: String?, type: String): String? {
        val segments = relativePath
            ?.replace('\\', '/')
            ?.split('/')
            ?.filter { it.isNotEmpty() }
            ?: return null
        if (segments.size <= 1) return null
        val directories = segments.dropLast(1)
        val index = directories.indexOf(type)
        if (index < 0) return null
        return directories.drop(index + 1).joinToString("/").ifBlank { null }
    }

    /**
     * 从相对路径推导某个固定基准目录下的子文件夹，例如 GameSourceModify。
     */
    fun folderUnder(relativePath: String?, baseDirectory: String): String? {
        val path = relativePath?.replace('\\', '/') ?: return null
        val base = baseDirectory.replace('\\', '/').trimEnd('/')
        val prefix = if (base.isEmpty()) "" else "$base/"
        if (!path.startsWith(prefix)) return null
        val remainder = path.removePrefix(prefix)
        return remainder.substringBeforeLast('/', "").ifBlank { null }
    }
}
