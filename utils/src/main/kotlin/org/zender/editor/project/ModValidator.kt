package org.zender.editor.project

import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.io.FileStore
import org.zender.editor.pack.DataPack

/**
 * 工程校验聚合入口（E09）。
 *
 * 组合各子校验器：
 * - [SchemaValidator]（E04）：字段存在、类型、只读；
 * - [ReferenceValidator]（E05）：引用目标、类型兼容、歧义；
 * - [FrameworkValidator]（E06）：ML/MC 能力矩阵与补丁语义；
 * - [ConflictValidator]（E07）：GUID/对象名/资源名/数据键冲突；
 * - [LocalizationValidator]（E08）：缺失、重复、孤儿、列格式；
 * - 本类自身保留元数据、UniqueID 一致性与补丁目标存在性检查。
 *
 * 校验不修改工程，也不应因为单个文件问题抛出异常；所有问题以 [ValidationIssue] 返回。
 */
class ModValidator(
    private val basePack: DataPack? = null,
    private val fileStore: FileStore = AtomicFileStore(),
) {

    private val schemaValidator = SchemaValidator(basePack)
    private val referenceValidator = ReferenceValidator(basePack)
    private val frameworkValidator = FrameworkValidator(basePack)
    private val conflictValidator = ConflictValidator(basePack)
    private val localizationValidator = LocalizationValidator(basePack, fileStore)

    fun validate(project: ModProject): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        issues += validateMetadata(project)
        issues += validateObjects(project)
        issues += validatePatchTargets(project)
        issues += schemaValidator.validate(project)
        issues += referenceValidator.validate(project)
        issues += frameworkValidator.validate(project)
        issues += conflictValidator.validate(project)
        issues += localizationValidator.validate(project)

        if (project.root == null) {
            issues += ValidationIssue(
                ValidationSeverity.INFO,
                "project.root.missing",
                "工程尚未设置保存目录",
            )
        }
        return issues.distinct()
    }

    // ---------------------------------------------------------------- 元数据

    private fun validateMetadata(project: ModProject): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val metadata = project.metadata
        if (metadata.names.isNotEmpty() && metadata.names.values.all { it.isBlank() }) {
            issues += ValidationIssue(
                ValidationSeverity.WARNING,
                "metadata.name.blank",
                "所有语言的 Mod 名称都是空的",
                project.format.metadataFileName,
            )
        }
        return issues
    }

    // ---------------------------------------------------------------- 对象完整性

    private fun validateObjects(project: ModProject): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        for (entry in project.workingTree.objects.values) {
            if (!isFileSafeName(entry.name)) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "object.name.invalid",
                    "对象名包含文件名非法字符：${entry.name}",
                    entry.relativePath,
                )
            }
            val root = objectRoot(entry.document)
            if (root == null) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "object.document.invalid",
                    "对象文档顶层必须是 JSON 对象",
                    entry.relativePath,
                )
                continue
            }

            if (entry.kind == ProjectObjectKind.SCRIPTABLE_OBJECT) {
                if (entry.uniqueId != null) {
                    issues += ValidationIssue(
                        ValidationSeverity.WARNING,
                        "object.scriptable.hasGuid",
                        "无 GUID 对象不应带 UniqueID：${entry.type}/${entry.name}",
                        entry.relativePath,
                    )
                }
                continue
            }

            val uniqueId = entry.uniqueId
            if (uniqueId.isNullOrBlank()) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "object.uniqueId.missing",
                    "UniqueID 对象缺少 UniqueID：${entry.type}/${entry.name}",
                    entry.relativePath,
                )
                continue
            }

            val field = root.get("UniqueID") as? JsonNode.StringNode
            when {
                field == null -> issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "object.uniqueId.fieldMissing",
                    "JSON 缺少 UniqueID 字段：${entry.type}/${entry.name}",
                    entry.relativePath,
                )

                field.value.trim() != uniqueId -> issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "object.uniqueId.mismatch",
                    "JSON UniqueID 与对象键不一致：${field.value.trim()} != $uniqueId",
                    entry.relativePath,
                )
            }
        }
        return issues
    }

    // ---------------------------------------------------------------- 补丁目标

    private fun validatePatchTargets(project: ModProject): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val pack = basePack ?: project.basePack

        for (patch in project.workingTree.gameSourceModifies.values) {
            if (patch.targetKey == null &&
                project.objectByGuid(patch.targetGuid) == null &&
                pack?.objectEntryByGuid(patch.targetGuid) == null
            ) {
                issues += ValidationIssue(
                    ValidationSeverity.WARNING,
                    "patch.target.missing",
                    "GameSourceModify 目标 GUID 未找到：${patch.targetGuid}",
                    patch.relativePath,
                )
            }
        }

        for (patch in project.workingTree.dataObjectModifies.values) {
            val shortName = patch.dataKey
                .substringAfterLast(':')
                .substringAfterLast('@')
            val found = patch.targetKey != null ||
                project.objectOf(patch.type, shortName) != null ||
                pack?.objectEntry(patch.type, shortName) != null
            if (!found) {
                issues += ValidationIssue(
                    ValidationSeverity.WARNING,
                    "patch.target.missing",
                    "DataObjectModify 目标未找到：${patch.type}/${patch.dataKey}",
                    patch.relativePath,
                )
            }
        }
        return issues
    }

    private fun objectRoot(document: JsonDocument): JsonNode.ObjectNode? =
        document.root as? JsonNode.ObjectNode

    private fun isFileSafeName(value: String): Boolean =
        value.isNotBlank() && value.none { it in "\\/:*?\"<>|" }
}
