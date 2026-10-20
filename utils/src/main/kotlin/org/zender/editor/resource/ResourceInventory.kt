package org.zender.editor.resource

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.io.FileStore
import org.zender.editor.model.EntryOrigin
import java.nio.file.Path

enum class ResourceKind {
    IMAGE,
    AUDIO,
}

/**
 * 一个图片/音频资源条目。
 *
 * [key] 为文件名去扩展名，是数据包/ML 的引用键；MC 的 `Namespace:文件名`
 * 由后续 `KeyCodec` 拼接。
 */
data class ResourceEntry(
    val key: String,
    val typeName: String,
    val kind: ResourceKind,
    val relativePath: String,
    val fileName: String,
    val extension: String,
    val sizeBytes: Long,
    val origin: EntryOrigin = EntryOrigin.BASE_PACK,
)

class ResourceInventory(
    entries: List<ResourceEntry> = emptyList(),
) {
    val entries: List<ResourceEntry> = entries.toList()

    val byKey: Map<String, List<ResourceEntry>> =
        this.entries.groupByTo(LinkedHashMap()) { it.key }

    val byKind: Map<ResourceKind, List<ResourceEntry>> =
        this.entries.groupByTo(LinkedHashMap()) { it.kind }

    fun entriesOf(kind: ResourceKind): List<ResourceEntry> = byKind[kind].orEmpty()

    fun findAll(key: String): List<ResourceEntry> = byKey[key].orEmpty()

    fun find(key: String, kind: ResourceKind? = null): ResourceEntry? =
        findAll(key).firstOrNull { kind == null || it.kind == kind }

    val size: Int get() = entries.size

    companion object {
        val EMPTY: ResourceInventory = ResourceInventory()
    }
}

/**
 * 资源目录定义。数据包与不同框架使用不同资源目录，但扫描逻辑一致。
 */
data class ResourceRoot(
    val relativePath: String,
    val kind: ResourceKind,
    val typeName: String,
)

/**
 * 扫描图片/音频资源。只统计文件元数据，不解码内容。
 */
object ResourceInventoryScanner {

    val PACK_ROOTS: List<ResourceRoot> = listOf(
        ResourceRoot("Resource/Texture2D", ResourceKind.IMAGE, "Sprite"),
        ResourceRoot("Resource/Picture", ResourceKind.IMAGE, "Sprite"),
        ResourceRoot("Resource/AudioClip", ResourceKind.AUDIO, "AudioClip"),
        ResourceRoot("Resource/Audio", ResourceKind.AUDIO, "AudioClip"),
    )

    val IMAGE_EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg")
    val AUDIO_EXTENSIONS: Set<String> = setOf("wav", "mp3", "ogg")

    data class ScanResult(
        val inventory: ResourceInventory = ResourceInventory.EMPTY,
        val diagnostics: List<Diagnostic> = emptyList(),
    )

    fun scan(
        fileStore: FileStore,
        baseRoot: Path,
        roots: List<ResourceRoot> = PACK_ROOTS,
        origin: EntryOrigin = EntryOrigin.BASE_PACK,
    ): ScanResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val entries = mutableListOf<ResourceEntry>()
        val seenKeys = HashSet<Pair<ResourceKind, String>>()

        for (root in roots) {
            val directory = baseRoot.resolve(root.relativePath)
            if (!fileStore.isDirectory(directory)) continue
            for (file in fileStore.walkFiles(directory)) {
                val fileName = file.fileName.toString()
                val extension = fileName.substringAfterLast('.', "").lowercase()
                val allowed = when (root.kind) {
                    ResourceKind.IMAGE -> IMAGE_EXTENSIONS
                    ResourceKind.AUDIO -> AUDIO_EXTENSIONS
                }
                if (extension !in allowed) continue

                val key = fileName.substringBeforeLast('.')
                if (key.isBlank()) continue
                if (!seenKeys.add(root.kind to key)) {
                    diagnostics += Diagnostic(
                        DiagnosticSeverity.WARNING,
                        "资源键重复：$key（保留首个文件）",
                        relative(baseRoot, file),
                    )
                    continue
                }
                val size = try {
                    fileStore.size(file)
                } catch (e: Exception) {
                    diagnostics += Diagnostic(
                        DiagnosticSeverity.WARNING,
                        "无法读取资源大小：${e.message}",
                        relative(baseRoot, file),
                    )
                    0L
                }
                entries += ResourceEntry(
                    key = key,
                    typeName = root.typeName,
                    kind = root.kind,
                    relativePath = relative(baseRoot, file),
                    fileName = fileName,
                    extension = extension,
                    sizeBytes = size,
                    origin = origin,
                )
            }
        }

        return ScanResult(
            inventory = ResourceInventory(
                entries.sortedWith(compareBy({ it.kind }, { it.key }, { it.relativePath })),
            ),
            diagnostics = diagnostics,
        )
    }

    private fun relative(root: Path, path: Path): String =
        try {
            root.relativize(path).toString().replace('\\', '/')
        } catch (_: IllegalArgumentException) {
            path.toString()
        }
}
