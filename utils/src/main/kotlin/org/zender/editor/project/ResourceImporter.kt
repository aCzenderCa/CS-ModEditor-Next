package org.zender.editor.project

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.io.FileStore
import org.zender.editor.io.resolveWithin
import org.zender.editor.resource.ResourceInventoryScanner
import org.zender.editor.resource.ResourceKind
import java.nio.file.Path

data class ResourceImportResult(
    val project: ModProject,
    val resource: ProjectResource? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    val isSuccess: Boolean get() = resource != null && diagnostics.none { it.isError }
}

/**
 * 导入外部图片/音频到工程资源目录。
 *
 * - 根据扩展名判断图片/音频；
 * - 目标目录由 `ModFormat.resourceDirectory` 决定；
 * - 项目内重名默认拒绝，`overwrite = true` 时覆盖；
 * - 与数据包内置资源重名只告警（游戏加载顺序可能导致覆盖失效）。
 */
class ResourceImporter(
    private val fileStore: FileStore = AtomicFileStore(),
) {

    fun import(
        project: ModProject,
        source: Path,
        key: String = defaultKey(source),
        kind: ResourceKind? = null,
        overwrite: Boolean = false,
    ): ResourceImportResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val normalizedSource = fileStore.normalize(source)
        if (!fileStore.isRegularFile(normalizedSource)) {
            return failure(project, "资源文件不存在：$normalizedSource")
        }

        val extension = normalizedSource.fileName.toString()
            .substringAfterLast('.', "")
            .lowercase()
        if (extension.isEmpty()) {
            return failure(project, "资源文件缺少扩展名：$normalizedSource")
        }
        val resolvedKind = kind ?: kindOf(extension)
            ?: return failure(project, "不支持的文件类型：.$extension")
        if (!isAllowedExtension(resolvedKind, extension)) {
            return failure(
                project,
                "${resolvedKind.name} 不支持扩展名 .$extension",
            )
        }
        if (!isValidKey(key)) {
            return failure(project, "资源键不能为空或包含路径/文件名非法字符：$key")
        }

        val existing = project.workingTree.resource(resolvedKind, key)
        if (existing != null && !overwrite) {
            return failure(project, "资源键已存在：${resolvedKind.name}/$key")
        }

        val typeName = typeNameOf(resolvedKind)
        warnBasePackConflict(project, typeName, key, diagnostics)

        val relativePath = "${project.format.resourceDirectory(resolvedKind)}/$key.$extension"
        val bytes = try {
            fileStore.readBytes(normalizedSource)
        } catch (e: Exception) {
            return failure(project, "无法读取资源：${e.message}")
        }

        var sourcePath: Path = normalizedSource
        val targetRoot = project.root
        if (targetRoot != null) {
            try {
                val target = fileStore.resolveWithin(targetRoot, relativePath)
                if (fileStore.normalize(normalizedSource) != target) {
                    fileStore.writeBytes(target, bytes)
                }
                sourcePath = target
            } catch (e: Exception) {
                return failure(project, "无法写入资源：${e.message}")
            }
        }

        val resource = ProjectResource(
            id = ResourceId(resolvedKind, key),
            typeName = typeName,
            relativePath = relativePath,
            sourcePath = sourcePath,
            sizeBytes = bytes.size.toLong(),
        )
        return ResourceImportResult(
            project = project.copy(
                workingTree = project.workingTree.withResource(resource),
            ),
            resource = resource,
            diagnostics = diagnostics,
        )
    }

    private fun warnBasePackConflict(
        project: ModProject,
        typeName: String,
        key: String,
        diagnostics: MutableList<Diagnostic>,
    ) {
        val pack = project.basePack ?: return
        val builtin = pack.objectEntry(typeName, key)
        val resource = pack.resources.find(key)
        if (builtin != null || resource != null) {
            diagnostics += Diagnostic(
                DiagnosticSeverity.WARNING,
                "资源键与数据包内置资源重名：$typeName/$key",
            )
        }
    }

    private fun failure(project: ModProject, message: String): ResourceImportResult =
        ResourceImportResult(
            project = project,
            diagnostics = listOf(Diagnostic(DiagnosticSeverity.ERROR, message)),
        )

    private fun kindOf(extension: String): ResourceKind? = when {
        extension in ResourceInventoryScanner.IMAGE_EXTENSIONS -> ResourceKind.IMAGE
        extension in ResourceInventoryScanner.AUDIO_EXTENSIONS -> ResourceKind.AUDIO
        else -> null
    }

    private fun isAllowedExtension(kind: ResourceKind, extension: String): Boolean =
        extension in when (kind) {
            ResourceKind.IMAGE -> ResourceInventoryScanner.IMAGE_EXTENSIONS
            ResourceKind.AUDIO -> ResourceInventoryScanner.AUDIO_EXTENSIONS
        }

    private fun typeNameOf(kind: ResourceKind): String = when (kind) {
        ResourceKind.IMAGE -> "Sprite"
        ResourceKind.AUDIO -> "AudioClip"
    }

    private fun defaultKey(source: Path): String {
        val name = source.fileName?.toString().orEmpty()
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    private fun isValidKey(key: String): Boolean =
        key.isNotBlank() &&
            key != "." &&
            key != ".." &&
            key.none { it in "\\/:*?\"<>|" }
}
