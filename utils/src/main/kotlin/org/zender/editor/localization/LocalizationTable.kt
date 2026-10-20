package org.zender.editor.localization

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.io.FileStore
import java.nio.file.Path

enum class LocalizationLanguage {
    ENGLISH,
    CHINESE,
}

/**
 * 一条本地化文本：Key + 英文 + 中文。
 *
 * 值保持 CSV 中的原文，不做 `\n` 反转义；需要换行文本时调用 [textFor] 并自行决定是否替换。
 */
data class LocalizationEntry(
    val key: String,
    val english: String = "",
    val chinese: String = "",
) {
    fun textFor(language: LocalizationLanguage): String = when (language) {
        LocalizationLanguage.ENGLISH -> english
        LocalizationLanguage.CHINESE -> chinese
    }

    /** ML 加载语义：字面量 `\n` 会替换为真实换行。 */
    fun displayText(language: LocalizationLanguage): String =
        textFor(language).replace("\\n", "\n")
}

class LocalizationTable(
    entries: List<LocalizationEntry> = emptyList(),
) {
    private val byKey: Map<String, LocalizationEntry> =
        entries.associateByTo(LinkedHashMap()) { it.key }

    val entries: List<LocalizationEntry>
        get() = byKey.values.toList()

    val size: Int get() = byKey.size

    fun entry(key: String): LocalizationEntry? = byKey[key]

    fun english(key: String): String? = byKey[key]?.english

    fun chinese(key: String): String? = byKey[key]?.chinese

    fun text(key: String, language: LocalizationLanguage): String? =
        byKey[key]?.textFor(language)

    fun contains(key: String): Boolean = byKey.containsKey(key)

    companion object {
        val EMPTY: LocalizationTable = LocalizationTable()
    }
}

data class LocalizationLoadResult(
    val table: LocalizationTable,
    val diagnostics: List<Diagnostic> = emptyList(),
)

/**
 * 数据包本地化加载：`SimpCn.csv`，三列 `LocalizationKey,English,中文`。
 *
 * 本机真实数据包没有表头，且存在多个 BOM；因此表头可选：
 * - 首列是 `LocalizationKey` / `Key` 时按表头定位列；
 * - 否则按位置取第 1/2/3 列（缺失列按空串处理）。
 *
 * 重复 Key 保持「先出现者优先」，与游戏本体优先的合并语义一致，并记录警告。
 */
object SimpCnLoader {

    const val FILE_NAME: String = "SimpCn.csv"

    fun load(fileStore: FileStore, path: Path): LocalizationLoadResult {
        if (!fileStore.exists(path)) {
            return LocalizationLoadResult(
                table = LocalizationTable.EMPTY,
                diagnostics = listOf(
                    Diagnostic(DiagnosticSeverity.WARNING, "本地化文件不存在：${path.fileName}", FILE_NAME),
                ),
            )
        }
        val text = try {
            CsvEncoding.decode(fileStore.readBytes(path))
        } catch (e: Exception) {
            return LocalizationLoadResult(
                table = LocalizationTable.EMPTY,
                diagnostics = listOf(
                    Diagnostic(
                        DiagnosticSeverity.ERROR,
                        "无法读取本地化文件：${e.message}",
                        FILE_NAME,
                    ),
                ),
            )
        }
        return parse(text)
    }

    fun parse(text: String): LocalizationLoadResult {
        val parsed = CsvParser.parse(text)
        val diagnostics = mutableListOf<Diagnostic>()
        parsed.error?.let {
            diagnostics += Diagnostic(DiagnosticSeverity.ERROR, it.message ?: "CSV 解析失败", FILE_NAME)
        }
        if (parsed.rows.isEmpty()) {
            return LocalizationLoadResult(LocalizationTable.EMPTY, diagnostics)
        }

        val header = detectHeader(parsed.rows.first())
        val startIndex = if (header != null) 1 else 0
        val keyColumn = header?.keyColumn ?: 0
        val englishColumn = header?.englishColumn ?: 1
        val chineseColumn = header?.chineseColumn ?: 2

        val entries = mutableListOf<LocalizationEntry>()
        val seen = HashSet<String>()
        for (rowIndex in startIndex until parsed.rows.size) {
            val row = parsed.rows[rowIndex]
            if (row.all { it.isBlank() }) continue
            val key = row.getOrNull(keyColumn)?.trim().orEmpty()
            if (key.isEmpty()) {
                diagnostics += Diagnostic(
                    DiagnosticSeverity.WARNING,
                    "第 ${rowIndex + 1} 行本地化 Key 为空，已跳过",
                    FILE_NAME,
                )
                continue
            }
            if (!seen.add(key)) {
                diagnostics += Diagnostic(
                    DiagnosticSeverity.WARNING,
                    "本地化 Key 重复：$key（保留首次出现的值）",
                    FILE_NAME,
                )
                continue
            }
            entries += LocalizationEntry(
                key = key,
                english = row.getOrNull(englishColumn).orEmpty(),
                chinese = row.getOrNull(chineseColumn).orEmpty(),
            )
        }
        return LocalizationLoadResult(LocalizationTable(entries), diagnostics)
    }

    private data class Header(
        val keyColumn: Int,
        val englishColumn: Int?,
        val chineseColumn: Int?,
    )

    private fun detectHeader(row: List<String>): Header? {
        val first = row.firstOrNull()?.trim()?.trimStart('﻿') ?: return null
        if (!first.equals("LocalizationKey", ignoreCase = true) && !first.equals("Key", ignoreCase = true)) {
            return null
        }
        val normalized = row.map { it.trim().trimStart('﻿').lowercase() }
        val keyColumn = normalized.indexOfFirst { it == "localizationkey" || it == "key" }
            .takeIf { it >= 0 } ?: 0
        val englishColumn = normalized.indexOfFirst { it == "english" || it == "en" }
            .takeIf { it >= 0 }
        val chineseColumn = normalized.indexOfFirst {
            it == "中文" || it == "chinese" || it == "simplified chinese" || it == "simpcn"
        }.takeIf { it >= 0 }
        return Header(keyColumn, englishColumn, chineseColumn)
    }
}
