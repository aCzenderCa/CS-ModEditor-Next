package org.zender.editor.project

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.diagnostics.DiagnosticCollector
import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.framework.FrameworkId
import org.zender.editor.framework.McFormat
import org.zender.editor.framework.MlFormat
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.io.FileStore
import org.zender.editor.pack.DataPack
import java.nio.file.Path

/**
 * 根据元数据文件自动识别并读取 ML/MC Mod 工程。
 */
class ModProjectLoader(
    private val fileStore: FileStore = AtomicFileStore(),
) {

    fun detectFormats(root: Path): Set<FrameworkId> {
        val normalized = fileStore.normalize(root)
        val result = LinkedHashSet<FrameworkId>()
        if (fileStore.isRegularFile(normalized.resolve(MlFormat.metadataFileName))) {
            result += FrameworkId.ML
        }
        if (fileStore.isRegularFile(normalized.resolve(McFormat.metadataFileName))) {
            result += FrameworkId.MC
        }
        return result
    }

    fun read(root: Path, basePack: DataPack? = null): ModProjectLoadResult {
        val normalized = fileStore.normalize(root)
        if (!fileStore.isDirectory(normalized)) {
            return ModProjectLoadResult(
                diagnostics = listOf(
                    Diagnostic(
                        DiagnosticSeverity.ERROR,
                        "Mod 工程目录不存在：$normalized",
                    ),
                ),
            )
        }
        val formats = detectFormats(normalized)
        return when {
            formats.isEmpty() -> ModProjectLoadResult(
                diagnostics = listOf(
                    Diagnostic(
                        DiagnosticSeverity.ERROR,
                        "目录中既没有 ${MlFormat.metadataFileName}，也没有 ${McFormat.metadataFileName}",
                    ),
                ),
            )

            formats.size > 1 -> {
                val diagnostics = DiagnosticCollector()
                diagnostics.error(
                    "同时存在 ${MlFormat.metadataFileName} 与 ${McFormat.metadataFileName}，" +
                        "无法确定框架",
                )
                ModProjectLoadResult(diagnostics = diagnostics.build())
            }

            FrameworkId.ML in formats ->
                MlProjectReader(fileStore).read(normalized, basePack)

            else ->
                McProjectReader(fileStore).read(normalized, basePack)
        }
    }
}
