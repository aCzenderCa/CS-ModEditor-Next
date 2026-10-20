package org.zender.editor.project

import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.framework.FrameworkCapability
import org.zender.editor.framework.FrameworkId
import org.zender.editor.framework.ModNamespace
import org.zender.editor.pack.DataPack

enum class ValidationSeverity {
    INFO,
    WARNING,
    ERROR,
}

data class ValidationIssue(
    val severity: ValidationSeverity,
    val code: String,
    val message: String,
    val path: String? = null,
) {
    val isError: Boolean get() = severity == ValidationSeverity.ERROR
}

/**
 * Mod 工程基础校验（E 区 Schema/引用校验的入口）。
 *
 * 当前覆盖：元数据命名空间、UniqueID、GUID/对象名重复、补丁目标与框架能力、
 * 资源键、本地化 Key。字段级 Schema 与引用兼容性校验在 E04–E09 接入。
 */
class ModValidator(
    private val basePack: DataPack? = null,
) {

    fun validate(project: ModProject): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val pack = basePack ?: project.basePack

        validateMetadata(project, issues)
        validateObjects(project, issues)
        validateGameSourceModifies(project, pack, issues)
        validateDataObjectModifies(project, pack, issues)
        validateResources(project, pack, issues)
        validateLocalization(project, issues)
        if (project.root == null) {
            issues += ValidationIssue(
                ValidationSeverity.INFO,
                "project.root.missing",
                "工程尚未设置保存目录",
            )
        }
        return issues
    }

    private fun validateMetadata(project: ModProject, issues: MutableList<ValidationIssue>) {
        val metadata = project.metadata
        if (metadata.namespace.isBlank()) {
            issues += ValidationIssue(
                ValidationSeverity.ERROR,
                "metadata.namespace.blank",
                "Mod 名称/Namespace 不能为空",
                project.format.metadataFileName,
            )
        }
        if (project.framework == FrameworkId.MC &&
            !ModNamespace.isValid(metadata.namespace)
        ) {
            issues += ValidationIssue(
                ValidationSeverity.ERROR,
                "metadata.namespace.invalid",
                "MC Namespace 不能包含 : @ |",
                project.format.metadataFileName,
            )
        }
        if (metadata.names.isNotEmpty() && metadata.names.values.all { it.isBlank() }) {
            issues += ValidationIssue(
                ValidationSeverity.WARNING,
                "metadata.name.blank",
                "所有语言的 Mod 名称都是空的",
                project.format.metadataFileName,
            )
        }
    }

    private fun validateObjects(project: ModProject, issues: MutableList<ValidationIssue>) {
        val objects = project.workingTree.objects.values
        val guidOwners = HashMap<String, ProjectObject>()
        val names = HashMap<Pair<String, String>, ProjectObject>()

        for (entry in objects) {
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

            val nameKey = entry.type to entry.name
            val sameName = names.putIfAbsent(nameKey, entry)
            if (sameName != null && sameName.key != entry.key) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "object.name.duplicate",
                    "对象名重复：${entry.type}/${entry.name}",
                    entry.relativePath,
                )
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
            } else {
                val owner = guidOwners.putIfAbsent(uniqueId, entry)
                if (owner != null && owner.key != entry.key) {
                    issues += ValidationIssue(
                        ValidationSeverity.ERROR,
                        "object.uniqueId.duplicate",
                        "GUID 重复：$uniqueId",
                        entry.relativePath,
                    )
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
        }
    }

    private fun validateGameSourceModifies(
        project: ModProject,
        pack: DataPack?,
        issues: MutableList<ValidationIssue>,
    ) {
        for (patch in project.workingTree.gameSourceModifies.values) {
            val root = objectRoot(patch.document)
            if (root == null) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "patch.document.invalid",
                    "GameSourceModify 顶层必须是 JSON 对象",
                    patch.relativePath,
                )
                continue
            }
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
            if (root.contains("\$override") &&
                !project.format.supports(FrameworkCapability.PATCH_OVERRIDE)
            ) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "patch.override.unsupported",
                    "${project.framework} 不支持 \$override",
                    patch.relativePath,
                )
            }
            if (root.contains("\$matchTargets") &&
                !project.format.supports(FrameworkCapability.PATCH_MATCH_TARGETS)
            ) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "patch.matchTargets.unsupported",
                    "${project.framework} 不支持 \$matchTargets",
                    patch.relativePath,
                )
            }
            val matchTags = root.get("MatchTagWarpData")
            val invalidMatchTags = matchTags != null && (
                matchTags !is JsonNode.ArrayNode ||
                    matchTags.elements.any { it !is JsonNode.StringNode }
                )
            if (invalidMatchTags) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "patch.matchTag.invalid",
                    "MatchTagWarpData 必须是字符串数组",
                    patch.relativePath,
                )
            }
        }
    }

    private fun validateDataObjectModifies(
        project: ModProject,
        pack: DataPack?,
        issues: MutableList<ValidationIssue>,
    ) {
        for (patch in project.workingTree.dataObjectModifies.values) {
            if (project.format.dataObjectModifyDirectory(patch.type) == null) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "patch.dataObjectModify.unsupported",
                    "${project.framework} 不支持 DataObjectModify",
                    patch.relativePath,
                )
                continue
            }
            if (objectRoot(patch.document) == null) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "patch.document.invalid",
                    "DataObjectModify 顶层必须是 JSON 对象",
                    patch.relativePath,
                )
            }
            if (patch.targetKey == null &&
                pack?.objectEntry(patch.type, patch.dataKey.substringAfterLast(':')) == null
            ) {
                issues += ValidationIssue(
                    ValidationSeverity.WARNING,
                    "patch.target.missing",
                    "DataObjectModify 目标未找到：${patch.type}/${patch.dataKey}",
                    patch.relativePath,
                )
            }
        }
    }

    private fun validateResources(
        project: ModProject,
        pack: DataPack?,
        issues: MutableList<ValidationIssue>,
    ) {
        for (resource in project.workingTree.resources.values) {
            if (!isValidResourceKey(resource.key)) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "resource.key.invalid",
                    "资源键包含非法字符：${resource.key}",
                    resource.relativePath,
                )
            }
            val typeName = resource.typeName
            if (pack?.objectEntry(typeName, resource.key) != null ||
                pack?.resources?.find(resource.key, resource.kind) != null
            ) {
                issues += ValidationIssue(
                    ValidationSeverity.WARNING,
                    "resource.shadowBuiltin",
                    "资源键与数据包内置资源重名：$typeName/${resource.key}",
                    resource.relativePath,
                )
            }
        }
    }

    private fun validateLocalization(project: ModProject, issues: MutableList<ValidationIssue>) {
        for (entry in project.workingTree.localization.table.entries) {
            if (entry.key.isBlank()) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "localization.key.blank",
                    "本地化 Key 不能为空",
                )
            }
        }
    }

    private fun objectRoot(document: JsonDocument): JsonNode.ObjectNode? =
        document.root as? JsonNode.ObjectNode

    private fun isValidResourceKey(key: String): Boolean = isFileSafeName(key)

    private fun isFileSafeName(value: String): Boolean =
        value.isNotBlank() && value.none { it in "\\/:*?\"<>|" }
}
