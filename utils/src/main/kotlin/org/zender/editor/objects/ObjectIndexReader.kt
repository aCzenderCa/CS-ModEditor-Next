package org.zender.editor.objects

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.io.FileStore
import java.nio.file.Path

/**
 * 读取数据包的倒排索引：
 *
 * - `GUID/<Type>.json` 或 `GUID/<Type>/<Group>.json`：`"对象名(中文名)" -> GUID`；
 * - `Name/<Type>.json`：`"对象名(中文名)" -> 对象名`。
 *
 * 索引文件存在即代表该类型属于引用类型，即使文件为空。
 */
object ObjectIndexReader {

    data class ReadResult(
        val entries: List<ObjectIndexEntry> = emptyList(),
        val types: Set<String> = emptySet(),
        val diagnostics: List<Diagnostic> = emptyList(),
    )

    fun readGuidIndex(fileStore: FileStore, packRoot: Path): ReadResult =
        read(fileStore, packRoot, "GUID", isGuid = true)

    fun readNameIndex(fileStore: FileStore, packRoot: Path): ReadResult =
        read(fileStore, packRoot, "Name", isGuid = false)

    private fun read(
        fileStore: FileStore,
        packRoot: Path,
        directoryName: String,
        isGuid: Boolean,
    ): ReadResult {
        val directory = packRoot.resolve(directoryName)
        if (!fileStore.isDirectory(directory)) {
            return ReadResult()
        }

        val diagnostics = mutableListOf<Diagnostic>()
        val entries = mutableListOf<ObjectIndexEntry>()
        val types = LinkedHashSet<String>()

        for (child in fileStore.list(directory)) {
            if (fileStore.isDirectory(child)) {
                val typeName = child.fileName.toString()
                types += typeName
                for (file in fileStore.walkFiles(child).filter { isJson(it) }) {
                    val group = stem(file)
                    readFile(
                        fileStore = fileStore,
                        packRoot = packRoot,
                        file = file,
                        typeName = typeName,
                        group = group,
                        isGuid = isGuid,
                        entries = entries,
                        diagnostics = diagnostics,
                    )
                }
            } else if (isJson(child)) {
                val typeName = stem(child)
                types += typeName
                readFile(
                    fileStore = fileStore,
                    packRoot = packRoot,
                    file = child,
                    typeName = typeName,
                    group = null,
                    isGuid = isGuid,
                    entries = entries,
                    diagnostics = diagnostics,
                )
            }
        }

        return ReadResult(
            entries = entries,
            types = types,
            diagnostics = diagnostics,
        )
    }

    private fun readFile(
        fileStore: FileStore,
        packRoot: Path,
        file: Path,
        typeName: String,
        group: String?,
        isGuid: Boolean,
        entries: MutableList<ObjectIndexEntry>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        val relativePath = relative(packRoot, file)
        val parsed = try {
            JsonDocument.parse(fileStore.readText(file))
        } catch (e: Exception) {
            diagnostics += Diagnostic(
                DiagnosticSeverity.ERROR,
                "无法读取索引文件：${e.message}",
                relativePath,
            )
            return
        }
        parsed.issues.forEach {
            diagnostics += Diagnostic(DiagnosticSeverity.WARNING, it.message, relativePath)
        }
        val document = parsed.document
        if (document == null) {
            diagnostics += Diagnostic(
                DiagnosticSeverity.ERROR,
                "索引文件不是合法 JSON：${parsed.error?.message}",
                relativePath,
            )
            return
        }
        val root = document.root as? JsonNode.ObjectNode
        if (root == null) {
            diagnostics += Diagnostic(
                DiagnosticSeverity.ERROR,
                "索引文件顶层必须是对象",
                relativePath,
            )
            return
        }

        for (member in root.members) {
            val rawValue = member.value as? JsonNode.StringNode
            if (rawValue == null) {
                diagnostics += Diagnostic(
                    DiagnosticSeverity.WARNING,
                    "索引项 ${member.name} 的值必须是字符串",
                    relativePath,
                )
                continue
            }
            val value = rawValue.value.trim()
            if (value.isEmpty()) {
                diagnostics += Diagnostic(
                    DiagnosticSeverity.WARNING,
                    "索引项 ${member.name} 的值为空",
                    relativePath,
                )
                continue
            }
            val key = parseIndexKey(member.name)
            if (key.name.isBlank()) {
                diagnostics += Diagnostic(
                    DiagnosticSeverity.WARNING,
                    "索引项名称为空：${member.name}",
                    relativePath,
                )
                continue
            }
            val name = if (isGuid) key.name else value
            entries += ObjectIndexEntry(
                type = typeName,
                name = name,
                value = value,
                displayName = key.displayName,
                group = group,
                sourcePath = relativePath,
            )
        }
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
