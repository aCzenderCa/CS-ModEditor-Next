package org.zender.editor.project

import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.io.FileStore
import org.zender.editor.io.resolveWithin
import org.zender.editor.localization.CsvEncoding
import org.zender.editor.localization.CsvParser
import org.zender.editor.localization.LocalizationFileFormat
import org.zender.editor.localization.LocalizationFileSpec
import org.zender.editor.localization.LocalizationLanguage
import org.zender.editor.localization.LocalizationTable
import org.zender.editor.pack.DataPack

/**
 * E08：本地化校验。
 *
 * 覆盖：
 * - `LocalizedString.LocalizationKey` 为空、缺失、重复、孤儿；
 * - Key 前缀是否符合 `ModName_` / `Namespace_` 约定；
 * - 本地化文件是否存在、CSV 是否合法、列数是否符合框架格式；
 * - 同一文件中重复 Key；
 * - 英文/中文文件是否覆盖了统一表中对应语言的文本。
 *
 * 文件级检查需要 [ModProject.root]；没有保存目录时只做内存模型检查。
 */
class LocalizationValidator(
    private val basePack: DataPack? = null,
    private val fileStore: FileStore = AtomicFileStore(),
) {

    fun validate(project: ModProject): List<ValidationIssue> {
        val pack = basePack ?: project.basePack
        val issues = mutableListOf<ValidationIssue>()
        val references = collectReferences(project, issues)
        val localization = project.workingTree.localization
        validateTable(project, pack, localization.table, references, issues)
        validateFiles(project, localization, localization.table, issues)
        return issues
    }

    // ---------------------------------------------------------------- 引用集合

    private fun collectReferences(
        project: ModProject,
        issues: MutableList<ValidationIssue>,
    ): Set<String> {
        val keys = LinkedHashSet<String>()

        fun scan(node: JsonNode, path: JsonPath, relativePath: String) {
            when (node) {
                is JsonNode.ObjectNode -> {
                    val keyNode = node.get("LocalizationKey") as? JsonNode.StringNode
                    if (keyNode != null) {
                        val key = keyNode.value.trim()
                        if (key.isEmpty()) {
                            val defaultText = (node.get("DefaultText") as? JsonNode.StringNode)
                                ?.value
                                .orEmpty()
                            if (defaultText.isNotBlank()) {
                                issues += ValidationIssue(
                                    ValidationSeverity.WARNING,
                                    "localization.reference.blank",
                                    "LocalizedString 有默认文本但 LocalizationKey 为空",
                                    fieldPathText(relativePath, path.child("LocalizationKey")),
                                )
                            }
                        } else {
                            keys += key
                        }
                    }
                    node.members.forEach { member ->
                        scan(member.value, path.child(member.name), relativePath)
                    }
                }

                is JsonNode.ArrayNode -> node.elements.forEachIndexed { index, element ->
                    scan(element, path.child(index), relativePath)
                }

                else -> Unit
            }
        }

        for (entry in project.workingTree.objects.values) {
            scan(entry.document.root, JsonPath.ROOT, entry.relativePath)
        }
        for (patch in project.workingTree.gameSourceModifies.values) {
            scan(patch.document.root, JsonPath.ROOT, patch.relativePath)
        }
        for (patch in project.workingTree.dataObjectModifies.values) {
            scan(patch.document.root, JsonPath.ROOT, patch.relativePath)
        }
        return keys
    }

    // ---------------------------------------------------------------- 统一表

    private fun validateTable(
        project: ModProject,
        pack: DataPack?,
        table: LocalizationTable,
        references: Set<String>,
        issues: MutableList<ValidationIssue>,
    ) {
        val prefix = project.namespace
            .takeIf { it.isNotBlank() }
            ?.let { "${it}_" }

        val extraKeys = project.workingTree.localization.extraTexts
            .values
            .flatMapTo(HashSet()) { it.keys }

        for (entry in table.entries) {
            if (entry.key.isBlank()) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "localization.key.blank",
                    "本地化 Key 不能为空",
                )
                continue
            }
            if (prefix != null && !entry.key.startsWith(prefix)) {
                issues += ValidationIssue(
                    ValidationSeverity.WARNING,
                    "localization.key.prefix",
                    "本地化 Key 建议以 ${prefix} 开头：${entry.key}",
                )
            }
            if (entry.key !in references) {
                issues += ValidationIssue(
                    ValidationSeverity.WARNING,
                    "localization.key.orphan",
                    "本地化 Key 没有被任何对象或补丁引用：${entry.key}",
                )
            }
            if (entry.english.isBlank() && entry.chinese.isBlank()) {
                issues += ValidationIssue(
                    ValidationSeverity.WARNING,
                    "localization.text.empty",
                    "本地化文本为空：${entry.key}",
                )
            }
        }

        for (key in references) {
            if (table.contains(key)) continue
            if (key in extraKeys) continue
            if (pack?.localization?.contains(key) == true) continue
            issues += ValidationIssue(
                ValidationSeverity.WARNING,
                "localization.key.missing",
                "LocalizedString 引用的 Key 不在本地化表中：$key",
            )
        }
    }

    // ---------------------------------------------------------------- 文件

    private fun validateFiles(
        project: ModProject,
        localization: ProjectLocalization,
        table: LocalizationTable,
        issues: MutableList<ValidationIssue>,
    ) {
        val root = project.root ?: return
        val specs = localization.files
        val keysByLanguage = HashMap<LocalizationLanguage, MutableSet<String>>()

        for (spec in specs) {
            val path = try {
                fileStore.resolveWithin(root, spec.relativePath)
            } catch (e: Exception) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "localization.file.invalid",
                    "本地化路径非法：${e.message}",
                    spec.relativePath,
                )
                continue
            }
            if (!fileStore.isRegularFile(path)) {
                issues += ValidationIssue(
                    ValidationSeverity.WARNING,
                    "localization.file.missing",
                    "本地化文件不存在：${spec.relativePath}",
                    spec.relativePath,
                )
                continue
            }
            val text = try {
                CsvEncoding.decode(fileStore.readBytes(path))
            } catch (e: Exception) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "localization.file.invalid",
                    "无法读取本地化文件：${e.message}",
                    spec.relativePath,
                )
                continue
            }
            val parsed = CsvParser.parse(text)
            parsed.error?.let { error ->
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "localization.file.invalid",
                    error.message ?: "CSV 解析失败",
                    spec.relativePath,
                )
            }
            val keys = readFileKeys(spec, parsed.rows, issues)
            spec.language?.let { language ->
                keysByLanguage.getOrPut(language) { LinkedHashSet() } += keys
            }
        }

        validateDefaultFiles(project, specs, table, issues)
        validateLanguageCoverage(table, keysByLanguage, issues)
    }

    private fun readFileKeys(
        spec: LocalizationFileSpec,
        rows: List<List<String>>,
        issues: MutableList<ValidationIssue>,
    ): Set<String> {
        if (rows.isEmpty()) return emptySet()
        val headerPresent = isHeader(rows.first())
        if (!headerPresent && spec.format == LocalizationFileFormat.THREE_COLUMN) {
            issues += ValidationIssue(
                ValidationSeverity.WARNING,
                "localization.header.missing",
                "ML 本地化文件建议包含表头 LocalizationKey,English,中文",
                spec.relativePath,
            )
        }

        val keys = LinkedHashSet<String>()
        val start = if (headerPresent) 1 else 0
        for (index in start until rows.size) {
            val row = rows[index]
            if (row.all { it.isBlank() }) continue
            val key = row.firstOrNull()?.trim().orEmpty()
            if (key.isEmpty()) {
                issues += ValidationIssue(
                    ValidationSeverity.WARNING,
                    "localization.row.keyBlank",
                    "第 ${index + 1} 行 Key 为空",
                    spec.relativePath,
                )
                continue
            }
            if (!keys.add(key)) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "localization.key.duplicate",
                    "本地化文件内 Key 重复：$key（游戏只保留首次出现的值）",
                    spec.relativePath,
                )
            }
            validateColumns(spec, row, index, issues)
        }
        return keys
    }

    private fun validateColumns(
        spec: LocalizationFileSpec,
        row: List<String>,
        rowIndex: Int,
        issues: MutableList<ValidationIssue>,
    ) {
        val valid = when (spec.format) {
            LocalizationFileFormat.THREE_COLUMN -> row.size == 3
            LocalizationFileFormat.KEY_VALUE -> row.size >= 2
        }
        if (valid) return
        issues += ValidationIssue(
            ValidationSeverity.WARNING,
            "localization.row.columns",
            when (spec.format) {
                LocalizationFileFormat.THREE_COLUMN ->
                    "第 ${rowIndex + 1} 行应为 3 列（Key,English,中文），实际 ${row.size} 列"

                LocalizationFileFormat.KEY_VALUE ->
                    "第 ${rowIndex + 1} 行至少需要 2 列（Key,文本），实际 ${row.size} 列"
            },
            spec.relativePath,
        )
    }

    private fun validateDefaultFiles(
        project: ModProject,
        specs: List<LocalizationFileSpec>,
        table: LocalizationTable,
        issues: MutableList<ValidationIssue>,
    ) {
        val existing = specs.mapTo(HashSet()) { it.relativePath }
        for (default in project.format.defaultLocalizationFiles()) {
            val present = default.relativePath in existing ||
                // MC 的语言文件按游戏语言相对路径命名，只要同语言文件存在即满足。
                (default.format == LocalizationFileFormat.KEY_VALUE &&
                    specs.any { it.language == default.language })
            if (present) continue
            val expectedText = when (default.language) {
                LocalizationLanguage.ENGLISH -> table.entries.any { it.english.isNotBlank() }
                LocalizationLanguage.CHINESE -> table.entries.any { it.chinese.isNotBlank() }
                null -> false
            }
            if (!expectedText) continue
            issues += ValidationIssue(
                ValidationSeverity.WARNING,
                "localization.file.missing",
                "缺少本地化文件：${default.relativePath}",
                default.relativePath,
            )
        }
    }

    private fun validateLanguageCoverage(
        table: LocalizationTable,
        keysByLanguage: Map<LocalizationLanguage, Set<String>>,
        issues: MutableList<ValidationIssue>,
    ) {
        for (entry in table.entries) {
            if (entry.key.isBlank()) continue
            if (entry.english.isNotBlank()) {
                val keys = keysByLanguage[LocalizationLanguage.ENGLISH]
                if (keys != null && entry.key !in keys) {
                    issues += ValidationIssue(
                        ValidationSeverity.WARNING,
                        "localization.language.missing",
                        "英文本地化文件缺少 Key：${entry.key}",
                    )
                }
            }
            if (entry.chinese.isNotBlank()) {
                val keys = keysByLanguage[LocalizationLanguage.CHINESE]
                if (keys != null && entry.key !in keys) {
                    issues += ValidationIssue(
                        ValidationSeverity.WARNING,
                        "localization.language.missing",
                        "中文本地化文件缺少 Key：${entry.key}",
                    )
                }
            }
        }
    }

    private fun isHeader(row: List<String>): Boolean {
        val first = row.firstOrNull()?.trim()?.trimStart('﻿') ?: return false
        return first.equals("LocalizationKey", ignoreCase = true) ||
            first.equals("Key", ignoreCase = true)
    }
}
