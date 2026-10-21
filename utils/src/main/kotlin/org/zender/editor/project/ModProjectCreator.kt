package org.zender.editor.project

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.framework.FrameworkId
import org.zender.editor.framework.McFormat
import org.zender.editor.framework.MlFormat
import org.zender.editor.framework.ModFormat
import org.zender.editor.framework.ModMetadata
import org.zender.editor.framework.ModNamespace
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.io.FileStore
import java.nio.file.Path

/**
 * 新建 Mod 工程。
 *
 * 只写入元数据文件（`ModInfo.json` / `ModMeta.json`）并返回内存工程快照；
 * 其余目录与文件在勾选保存或新建对象时创建。
 */
data class NewProjectRequest(
    /** 工程根目录；必须为空目录或尚不存在。 */
    val root: Path,
    val framework: FrameworkId,
    /** 展示名；ML 同时作为 `ModInfo.Name`。 */
    val name: String,
    /** 仅 MC：`ModMeta.Namespace`；为空时使用 [name]。 */
    val namespace: String? = null,
    val author: String = "",
    val version: String = "",
)

data class NewProjectResult(
    val project: ModProject? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    val isSuccess: Boolean get() = project != null && diagnostics.none { it.isError }
}

class ModProjectCreator(
    private val fileStore: FileStore = AtomicFileStore(),
) {

    fun create(request: NewProjectRequest): NewProjectResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val name = request.name.trim()
        if (name.isBlank()) {
            return failure("Mod 名称不能为空")
        }

        val format: ModFormat = when (request.framework) {
            FrameworkId.ML -> MlFormat
            FrameworkId.MC -> McFormat
        }
        val namespace = when (request.framework) {
            FrameworkId.ML -> name
            FrameworkId.MC -> request.namespace?.trim()?.takeIf { it.isNotEmpty() } ?: name
        }
        if (request.framework == FrameworkId.MC && !ModNamespace.isValid(namespace)) {
            return failure("MC Namespace 不能为空，且不能包含 : @ |：$namespace")
        }

        val root = fileStore.normalize(request.root)
        if (fileStore.exists(root)) {
            if (!fileStore.isDirectory(root)) {
                return failure("目标路径不是目录：$root")
            }
            if (fileStore.list(root).isNotEmpty()) {
                return failure("目标目录不是空目录：$root")
            }
        }

        val metadata = when (request.framework) {
            FrameworkId.ML -> ModMetadata(
                framework = FrameworkId.ML,
                namespace = name,
                names = mapOf(ModMetadata.DEFAULT_LANGUAGE to name),
                author = request.author.trim(),
                version = request.version.trim(),
            )

            FrameworkId.MC -> ModMetadata(
                framework = FrameworkId.MC,
                namespace = namespace,
                names = linkedMapOf(
                    "SimpCn" to name,
                    "En" to name,
                ),
                author = request.author.trim(),
                version = request.version.trim(),
                icon = "",
                pluginGuids = emptyList(),
            )
        }

        return try {
            fileStore.createDirectories(root)
            fileStore.writeText(
                root.resolve(format.metadataFileName),
                format.writeMetadata(metadata),
            )
            NewProjectResult(
                project = ModProject(
                    root = root,
                    format = format,
                    metadata = metadata,
                    workingTree = WorkingTree(),
                    diagnostics = diagnostics.toList(),
                ),
                diagnostics = diagnostics,
            )
        } catch (e: Exception) {
            failure("创建工程失败：${e.message}")
        }
    }

    private fun failure(message: String): NewProjectResult = NewProjectResult(
        diagnostics = listOf(Diagnostic(DiagnosticSeverity.ERROR, message)),
    )
}
