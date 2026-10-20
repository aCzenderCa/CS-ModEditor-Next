package org.zender.editor.objects

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.io.FileStore
import org.zender.editor.model.EntryOrigin
import java.nio.file.Path

/**
 * 扫描 `Objects/<Type>/**/*.json`。
 *
 * 规则与游戏加载一致：
 * - 目录名 = 类型名；
 * - 文件名（去扩展名）= 对象名；
 * - 一个文件一个对象；
 * - `CardData` 等类型允许再分子目录，第一级子目录记为分组（CardData 中即 CardType）。
 */
object ObjectScanner {

    data class ScanResult(
        val entries: List<ObjectEntry> = emptyList(),
        val diagnostics: List<Diagnostic> = emptyList(),
    )

    fun scan(
        fileStore: FileStore,
        packRoot: Path,
        origin: EntryOrigin = EntryOrigin.BASE_PACK,
    ): ScanResult {
        val objectsRoot = packRoot.resolve("Objects")
        if (!fileStore.isDirectory(objectsRoot)) {
            return ScanResult(
                diagnostics = listOf(
                    Diagnostic(DiagnosticSeverity.WARNING, "数据包缺少 Objects 目录", "Objects"),
                ),
            )
        }

        val diagnostics = mutableListOf<Diagnostic>()
        val entries = mutableListOf<ObjectEntry>()
        val seenByType = LinkedHashMap<String, MutableSet<String>>()

        val typeDirectories = fileStore.list(objectsRoot).filter { fileStore.isDirectory(it) }
        for (typeDir in typeDirectories) {
            val typeName = typeDir.fileName.toString()
            val names = seenByType.getOrPut(typeName) { LinkedHashSet() }
            val files = fileStore.walkFiles(typeDir).filter { isJson(it) }
            for (file in files) {
                val name = stem(file)
                if (name.isBlank()) continue
                if (!names.add(name)) {
                    diagnostics += Diagnostic(
                        DiagnosticSeverity.WARNING,
                        "类型 $typeName 下对象名重复：$name（保留首个文件）",
                        relative(packRoot, file),
                    )
                    continue
                }
                val relativeUnderType = typeDir.relativize(file)
                val group = if (relativeUnderType.nameCount > 1) {
                    relativeUnderType.getName(0).toString()
                } else {
                    null
                }
                entries += ObjectEntry(
                    key = ObjectKey.of(typeName, name),
                    relativePath = relative(packRoot, file),
                    group = group,
                    origin = origin,
                )
            }
        }

        return ScanResult(
            entries = entries.sortedWith(compareBy({ it.type }, { it.name })),
            diagnostics = diagnostics,
        )
    }

    private fun isJson(path: Path): Boolean =
        path.fileName.toString().endsWith(".json", ignoreCase = true)

    private fun stem(path: Path): String {
        val name = path.fileName.toString()
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    private fun relative(root: Path, path: Path): String =
        try {
            root.relativize(path).toString().replace('\\', '/')
        } catch (_: IllegalArgumentException) {
            path.toString()
        }
}
