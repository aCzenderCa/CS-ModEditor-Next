package org.zender.editor.project

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonFormat
import org.zender.editor.document.toJsonText
import org.zender.editor.framework.FrameworkId
import org.zender.editor.framework.ModNamespace
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.io.FileStore
import org.zender.editor.io.resolveWithin
import org.zender.editor.localization.LocalizationFileSpec
import java.nio.file.Path

/**
 * Mod 工程写入。
 *
 * - 所有文件通过 [FileStore] 原子写入；
 * - 对象按工程中记录的相对路径写回，新对象使用 [ModProject.defaultObjectPath]；
 * - 元数据保留未知字段（由框架写入器负责）；
 * - 本地化按框架布局生成：ML 三列双文件，MC 按语言单文件取最后一列；
 * - 资源从 [ProjectResource.sourcePath] 复制到目标工程。
 *
 * 本写入器不删除工程中已存在但本次快照中不存在的文件；删除/重命名同步留给后续增量保存。
 */
class ModProjectWriter(
    private val fileStore: FileStore = AtomicFileStore(),
) {

    fun write(
        project: ModProject,
        targetRoot: Path? = project.root,
    ): ModProjectWriteResult {
        val root = targetRoot ?: return result(
            diagnostics = listOf(error("工程没有保存目录")),
        )
        val normalizedRoot = fileStore.normalize(root)
        val diagnostics = mutableListOf<Diagnostic>()
        val written = mutableListOf<String>()

        if (!validateMetadata(project, diagnostics)) {
            return ModProjectWriteResult(diagnostics = diagnostics)
        }

        try {
            fileStore.createDirectories(normalizedRoot)
        } catch (e: Exception) {
            diagnostics += error("无法创建工程目录：${e.message}")
            return ModProjectWriteResult(diagnostics = diagnostics)
        }

        writeText(
            root = normalizedRoot,
            relativePath = project.format.metadataFileName,
            text = project.format.writeMetadata(project.metadata),
            written = written,
            diagnostics = diagnostics,
        )

        project.workingTree.folders.forEach { folder ->
            val base = if (folder.kind == ProjectObjectKind.UNIQUE_ID) {
                project.format.uniqueIdObjectDirectory(folder.type)
            } else {
                project.format.scriptableObjectDirectory(folder.type)
            }
            try {
                fileStore.createDirectories(
                    fileStore.resolveWithin(normalizedRoot, "$base/${folder.path}"),
                )
            } catch (e: Exception) {
                diagnostics += error("无法创建文件夹：${e.message}", "$base/${folder.path}")
            }
        }

        // 删除本次会话移除的对象文件；同一路径如果被重新创建则不删。
        val livePaths = project.workingTree.objects.values.mapTo(HashSet()) { it.relativePath }
        project.workingTree.deletedPaths
            .filter { it.isNotBlank() && it !in livePaths }
            .forEach { relativePath ->
                try {
                    val target = fileStore.resolveWithin(normalizedRoot, relativePath)
                    if (fileStore.exists(target)) fileStore.delete(target)
                } catch (e: Exception) {
                    diagnostics += Diagnostic(
                        DiagnosticSeverity.WARNING,
                        "无法删除对象文件：${e.message}",
                        relativePath,
                    )
                }
            }

        project.workingTree.objects.values.forEach { entry ->
            if (entry.kind == ProjectObjectKind.UNIQUE_ID && entry.uniqueId == null) {
                diagnostics += Diagnostic(
                    DiagnosticSeverity.WARNING,
                    "UniqueID 对象缺少 UniqueID：${entry.type}/${entry.name}",
                    entry.relativePath,
                )
            }
            val relativePath = entry.relativePath.ifBlank {
                project.defaultObjectPath(entry.type, entry.name, entry.kind)
            }
            writeDocument(
                root = normalizedRoot,
                relativePath = relativePath,
                document = entry.document,
                written = written,
                diagnostics = diagnostics,
            )
        }

        project.workingTree.gameSourceModifies.values.forEach { entry ->
            val relativePath = entry.relativePath.ifBlank {
                project.format.gameSourceModifyPath(entry.targetGuid)
            }
            writeDocument(
                root = normalizedRoot,
                relativePath = relativePath,
                document = entry.document,
                written = written,
                diagnostics = diagnostics,
            )
        }

        project.workingTree.dataObjectModifies.values.forEach { entry ->
            val directory = project.format.dataObjectModifyDirectory(entry.type)
            if (directory == null) {
                diagnostics += error(
                    "${project.framework} 不支持 DataObjectModify：${entry.type}/${entry.dataKey}",
                    entry.relativePath,
                )
                return@forEach
            }
            val relativePath = entry.relativePath.ifBlank {
                project.format.dataObjectModifyPath(entry.type, entry.dataKey)
                    ?: "$directory/${entry.dataKey}.json"
            }
            writeDocument(
                root = normalizedRoot,
                relativePath = relativePath,
                document = entry.document,
                written = written,
                diagnostics = diagnostics,
            )
        }

        writeLocalization(project, normalizedRoot, written, diagnostics)
        writeResources(project, normalizedRoot, written, diagnostics)

        return ModProjectWriteResult(
            writtenFiles = written.distinct().sorted(),
            diagnostics = diagnostics,
        )
    }

    private fun validateMetadata(
        project: ModProject,
        diagnostics: MutableList<Diagnostic>,
    ): Boolean {
        if (project.metadata.namespace.isBlank()) {
            diagnostics += error("Mod 名称/Namespace 不能为空", project.format.metadataFileName)
            return false
        }
        if (project.framework == FrameworkId.MC &&
            !ModNamespace.isValid(project.metadata.namespace)
        ) {
            diagnostics += error(
                "MC Namespace 不能包含 : @ |：${project.metadata.namespace}",
                project.format.metadataFileName,
            )
            return false
        }
        return true
    }

    private fun writeLocalization(
        project: ModProject,
        root: Path,
        written: MutableList<String>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        val localization = project.workingTree.localization
        val hasLocalization = !localization.isEmpty || localization.files.isNotEmpty()
        if (!hasLocalization) return

        val files = when (project.framework) {
            FrameworkId.ML -> {
                val specs = mergeSpecs(
                    localization.files,
                    project.format.defaultLocalizationFiles(),
                )
                ProjectLocalizationFiles.writeMl(localization, specs)
            }

            FrameworkId.MC -> {
                val specs = localization.files.ifEmpty {
                    project.format.defaultLocalizationFiles()
                }
                ProjectLocalizationFiles.writeMc(localization, specs)
            }
        }
        files.forEach { (relativePath, text) ->
            writeText(root, relativePath, text, written, diagnostics)
        }
    }

    private fun writeResources(
        project: ModProject,
        root: Path,
        written: MutableList<String>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        project.workingTree.resources.values.forEach { resource ->
            val source = resource.sourcePath
                ?: project.root?.resolve(resource.relativePath)
            if (source == null || !fileStore.isRegularFile(source)) {
                diagnostics += Diagnostic(
                    DiagnosticSeverity.WARNING,
                    "资源源文件不存在，已跳过：${resource.relativePath}",
                    resource.relativePath,
                )
                return@forEach
            }
            try {
                val target = fileStore.resolveWithin(root, resource.relativePath)
                if (fileStore.normalize(source) != target) {
                    fileStore.writeBytes(target, fileStore.readBytes(source))
                }
                written += resource.relativePath
            } catch (e: Exception) {
                diagnostics += error(
                    "无法写入资源：${e.message}",
                    resource.relativePath,
                )
            }
        }
    }

    private fun writeDocument(
        root: Path,
        relativePath: String,
        document: JsonDocument,
        written: MutableList<String>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        writeText(
            root = root,
            relativePath = relativePath,
            text = document.toJsonText(JsonFormat.Pretty()),
            written = written,
            diagnostics = diagnostics,
        )
    }

    private fun writeText(
        root: Path,
        relativePath: String,
        text: String,
        written: MutableList<String>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        try {
            val target = fileStore.resolveWithin(root, relativePath)
            fileStore.writeText(target, text)
            written += relativePath
        } catch (e: Exception) {
            diagnostics += error("无法写入文件：${e.message}", relativePath)
        }
    }

    private fun mergeSpecs(
        existing: List<LocalizationFileSpec>,
        defaults: List<LocalizationFileSpec>,
    ): List<LocalizationFileSpec> {
        val merged = LinkedHashMap<String, LocalizationFileSpec>()
        existing.forEach { merged[it.relativePath] = it }
        defaults.forEach { merged.putIfAbsent(it.relativePath, it) }
        return merged.values.toList()
    }

    private fun result(diagnostics: List<Diagnostic>): ModProjectWriteResult =
        ModProjectWriteResult(diagnostics = diagnostics)

    private fun error(message: String, path: String? = null): Diagnostic =
        Diagnostic(DiagnosticSeverity.ERROR, message, path)
}
