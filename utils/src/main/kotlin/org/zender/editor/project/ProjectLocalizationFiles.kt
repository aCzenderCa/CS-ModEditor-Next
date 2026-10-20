package org.zender.editor.project

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.io.FileStore
import org.zender.editor.localization.CsvEncoding
import org.zender.editor.localization.CsvParser
import org.zender.editor.localization.CsvWriter
import org.zender.editor.localization.LocalizationEntry
import org.zender.editor.localization.LocalizationFileFormat
import org.zender.editor.localization.LocalizationFileSpec
import org.zender.editor.localization.LocalizationLanguage
import org.zender.editor.localization.LocalizationTable
import org.zender.editor.localization.SimpCnLoader
import java.nio.file.Path

data class ProjectLocalizationReadResult(
    val localization: ProjectLocalization = ProjectLocalization(),
    val diagnostics: List<Diagnostic> = emptyList(),
)

/**
 * 工程本地化文件读写。
 *
 * - ML：三列 `LocalizationKey,English,中文`，读取 `Localization` 下的 CSV；
 * - MC：两列 `Key,当前语言文本`，读取 `Resource/Localization` 下的 CSV，取每行最后一列。
 *
 * 未知语言文件不进入统一表，原样保留在 [ProjectLocalization.extraTexts]。
 */
object ProjectLocalizationFiles {

    const val ML_ROOT: String = "Localization"
    const val MC_ROOT: String = "Resource/Localization"

    private val THREE_COLUMN_HEADER = listOf("LocalizationKey", "English", "中文")

    fun readMl(
        fileStore: FileStore,
        projectRoot: Path,
        localizationRoot: String = ML_ROOT,
    ): ProjectLocalizationReadResult {
        val files = csvFiles(fileStore, projectRoot.resolve(localizationRoot))
        val diagnostics = mutableListOf<Diagnostic>()
        val specs = mutableListOf<LocalizationFileSpec>()
        val merged = LinkedHashMap<String, LocalizationEntry>()

        for (file in files) {
            val relativePath = relative(projectRoot, file)
            val language = detectLanguage(file.fileName.toString())
            specs += LocalizationFileSpec(
                relativePath = relativePath,
                language = language,
                format = LocalizationFileFormat.THREE_COLUMN,
            )
            val parsed = SimpCnLoader.parse(decode(fileStore, file))
            parsed.diagnostics.forEach {
                diagnostics += it.copy(path = relativePath)
            }
            parsed.table.entries.forEach { entry ->
                val existing = merged[entry.key]
                merged[entry.key] = LocalizationEntry(
                    key = entry.key,
                    english = entry.english.ifBlank { existing?.english.orEmpty() },
                    chinese = entry.chinese.ifBlank { existing?.chinese.orEmpty() },
                )
            }
        }

        return ProjectLocalizationReadResult(
            localization = ProjectLocalization(
                table = LocalizationTable(merged.values.toList()),
                files = specs,
            ),
            diagnostics = diagnostics,
        )
    }

    fun readMc(
        fileStore: FileStore,
        projectRoot: Path,
        localizationRoot: String = MC_ROOT,
    ): ProjectLocalizationReadResult {
        val files = csvFiles(fileStore, projectRoot.resolve(localizationRoot))
        val diagnostics = mutableListOf<Diagnostic>()
        val specs = mutableListOf<LocalizationFileSpec>()
        val merged = LinkedHashMap<String, LocalizationEntry>()
        val extraTexts = LinkedHashMap<String, Map<String, String>>()

        for (file in files) {
            val relativePath = relative(projectRoot, file)
            val language = detectLanguage(file.fileName.toString())
            specs += LocalizationFileSpec(
                relativePath = relativePath,
                language = language,
                format = LocalizationFileFormat.KEY_VALUE,
            )
            val rows = readKeyValue(fileStore, file, relativePath, diagnostics)
            when (language) {
                LocalizationLanguage.ENGLISH -> {
                    rows.forEach { (key, text) ->
                        val existing = merged[key]
                        merged[key] = LocalizationEntry(
                            key = key,
                            english = text,
                            chinese = existing?.chinese.orEmpty(),
                        )
                    }
                }

                LocalizationLanguage.CHINESE -> {
                    rows.forEach { (key, text) ->
                        val existing = merged[key]
                        merged[key] = LocalizationEntry(
                            key = key,
                            english = existing?.english.orEmpty(),
                            chinese = text,
                        )
                    }
                }

                null -> extraTexts[relativePath] = rows
            }
        }

        return ProjectLocalizationReadResult(
            localization = ProjectLocalization(
                table = LocalizationTable(merged.values.toList()),
                files = specs,
                extraTexts = extraTexts,
            ),
            diagnostics = diagnostics,
        )
    }

    /**
     * 生成 ML 本地化文件。调用方负责传入要写入的文件列表（通常为默认两份与已有文件的并集）。
     */
    fun writeMl(
        localization: ProjectLocalization,
        specs: List<LocalizationFileSpec>,
    ): Map<String, String> {
        if (localization.table.entries.isEmpty() && specs.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (spec in specs) {
            val rows = mutableListOf(THREE_COLUMN_HEADER)
            localization.table.entries.forEach { entry ->
                if (entry.key.isBlank()) return@forEach
                val english = entry.english
                val chinese = if (spec.language == LocalizationLanguage.ENGLISH) "" else entry.chinese
                if (spec.language == LocalizationLanguage.ENGLISH && english.isBlank()) {
                    return@forEach
                }
                if (spec.language == LocalizationLanguage.CHINESE &&
                    english.isBlank() && entry.chinese.isBlank()
                ) {
                    return@forEach
                }
                rows += listOf(entry.key, english, chinese)
            }
            result[spec.relativePath] = CsvWriter.write(rows)
        }
        return result
    }

    /**
     * 生成 MC 按语言本地化文件。已识别语言从统一表取对应列，未知语言从 `extraTexts` 原样写回。
     */
    fun writeMc(
        localization: ProjectLocalization,
        specs: List<LocalizationFileSpec>,
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (spec in specs) {
            val rows = mutableListOf<List<String>>()
            when (spec.language) {
                LocalizationLanguage.ENGLISH -> {
                    localization.table.entries.forEach { entry ->
                        if (entry.key.isNotBlank() && entry.english.isNotBlank()) {
                            rows += listOf(entry.key, entry.english)
                        }
                    }
                }

                LocalizationLanguage.CHINESE -> {
                    localization.table.entries.forEach { entry ->
                        if (entry.key.isNotBlank() && entry.chinese.isNotBlank()) {
                            rows += listOf(entry.key, entry.chinese)
                        }
                    }
                }

                null -> {
                    val extras = localization.extraTexts[spec.relativePath]
                    if (extras == null) continue
                    extras.forEach { (key, text) ->
                        if (key.isNotBlank()) rows += listOf(key, text)
                    }
                }
            }
            // 已知语言文件即使没有文本也保留文件，避免工程布局在保存后丢失。
            result[spec.relativePath] = CsvWriter.write(rows)
        }
        return result
    }

    private fun csvFiles(fileStore: FileStore, directory: Path): List<Path> {
        if (!fileStore.isDirectory(directory)) return emptyList()
        return fileStore.walkFiles(directory)
            .filter { it.fileName.toString().endsWith(".csv", ignoreCase = true) }
    }

    private fun decode(fileStore: FileStore, file: Path): String =
        CsvEncoding.decode(fileStore.readBytes(file))

    private fun readKeyValue(
        fileStore: FileStore,
        file: Path,
        relativePath: String,
        diagnostics: MutableList<Diagnostic>,
    ): Map<String, String> {
        val parsed = CsvParser.parse(decode(fileStore, file))
        parsed.error?.let {
            diagnostics += Diagnostic(
                DiagnosticSeverity.ERROR,
                it.message ?: "CSV 解析失败",
                relativePath,
            )
        }
        val rows = parsed.rows
        if (rows.isEmpty()) return emptyMap()

        var start = 0
        val firstCell = rows.first().firstOrNull()?.trim()?.trimStart('﻿')
        if (firstCell.equals("Key", ignoreCase = true) ||
            firstCell.equals("LocalizationKey", ignoreCase = true)
        ) {
            start = 1
        }

        val result = LinkedHashMap<String, String>()
        for (index in start until rows.size) {
            val row = rows[index]
            if (row.all { it.isBlank() }) continue
            val key = row.firstOrNull()?.trim().orEmpty()
            if (key.isEmpty()) {
                diagnostics += Diagnostic(
                    DiagnosticSeverity.WARNING,
                    "第 ${index + 1} 行 Key 为空，已跳过",
                    relativePath,
                )
                continue
            }
            if (result.containsKey(key)) {
                diagnostics += Diagnostic(
                    DiagnosticSeverity.WARNING,
                    "Key 重复：$key（保留首次出现的值）",
                    relativePath,
                )
                continue
            }
            result[key] = row.lastOrNull().orEmpty()
        }
        return result
    }

    private fun detectLanguage(fileName: String): LocalizationLanguage? {
        val name = fileName.lowercase()
        return when {
            name == "cn.csv" ||
                name.endsWith("_cn.csv") ||
                name.contains("simpcn") ||
                name.contains("chinese") -> LocalizationLanguage.CHINESE

            name == "en.csv" ||
                name.endsWith("_en.csv") ||
                name.contains("simpen") ||
                name.contains("english") -> LocalizationLanguage.ENGLISH

            else -> null
        }
    }

    private fun relative(root: Path, path: Path): String =
        try {
            root.relativize(path).toString().replace('\\', '/')
        } catch (_: IllegalArgumentException) {
            path.toString()
        }
}
