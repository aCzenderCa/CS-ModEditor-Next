package org.zender.editor.project

import org.zender.editor.framework.FrameworkId
import org.zender.editor.framework.ModNamespace
import org.zender.editor.pack.DataPack

/**
 * E07：命名与键冲突校验。
 *
 * 覆盖：
 * - 工程内对象名重复、GUID 重复；
 * - 工程对象 GUID 与数据包内置对象冲突；
 * - ML 无 GUID 对象名与数据包同名对象冲突；
 * - MC 数据键（`Namespace:Name`）与数据包已有键冲突；
 * - 资源键非法或与数据包内置资源重名。
 *
 * 跨 Mod 冲突无法只凭一个数据包判断；这里只处理数据包与当前工程。
 */
class ConflictValidator(private val basePack: DataPack? = null) {

    fun validate(project: ModProject): List<ValidationIssue> {
        val pack = basePack ?: project.basePack
        val issues = mutableListOf<ValidationIssue>()
        validateNamespace(project, issues)
        validateObjects(project, pack, issues)
        validateResources(project, pack, issues)
        return issues
    }

    private fun validateNamespace(
        project: ModProject,
        issues: MutableList<ValidationIssue>,
    ) {
        val namespace = project.metadata.namespace
        if (namespace.isBlank()) {
            issues += ValidationIssue(
                ValidationSeverity.ERROR,
                "metadata.namespace.blank",
                "Mod 名称/Namespace 不能为空",
                project.format.metadataFileName,
            )
            return
        }
        if (project.framework == FrameworkId.MC && !ModNamespace.isValid(namespace)) {
            issues += ValidationIssue(
                ValidationSeverity.ERROR,
                "metadata.namespace.invalid",
                "MC Namespace 不能包含 : @ |",
                project.format.metadataFileName,
            )
        }
    }

    private fun validateObjects(
        project: ModProject,
        pack: DataPack?,
        issues: MutableList<ValidationIssue>,
    ) {
        val nameOwners = HashMap<Pair<String, String>, ProjectObject>()
        val guidOwners = HashMap<String, ProjectObject>()

        for (entry in project.workingTree.objects.values) {
            val nameKey = entry.type to entry.name
            val sameName = nameOwners.putIfAbsent(nameKey, entry)
            if (sameName != null && sameName.key != entry.key) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "object.name.duplicate",
                    "对象名重复：${entry.type}/${entry.name}",
                    entry.relativePath,
                )
            }

            val uniqueId = entry.uniqueId?.takeIf { it.isNotBlank() }
            if (uniqueId != null) {
                val guidOwner = guidOwners.putIfAbsent(uniqueId, entry)
                if (guidOwner != null && guidOwner.key != entry.key) {
                    issues += ValidationIssue(
                        ValidationSeverity.ERROR,
                        "object.uniqueId.duplicate",
                        "GUID 重复：$uniqueId",
                        entry.relativePath,
                    )
                }
                if (pack?.objectIndex?.findByGuid(uniqueId) != null) {
                    issues += ValidationIssue(
                        ValidationSeverity.ERROR,
                        "conflict.guid.basePack",
                        "GUID $uniqueId 已存在于数据包，游戏会先加载内置对象",
                        entry.relativePath,
                    )
                }
            }

            if (pack != null) {
                validateBasePackName(project, entry, pack, issues)
            }
        }
    }

    private fun validateBasePackName(
        project: ModProject,
        entry: ProjectObject,
        pack: DataPack,
        issues: MutableList<ValidationIssue>,
    ) {
        when (project.framework) {
            FrameworkId.ML -> {
                // ML 的 UniqueID 对象注册名带 ModName_ 前缀，不会与内置对象名冲突；
                // 无 GUID 的 ScriptableObject 使用裸对象名，先加载者优先。
                if (entry.kind != ProjectObjectKind.SCRIPTABLE_OBJECT) return
                if (pack.objectIndex.find(entry.type, entry.name) == null) return
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "conflict.objectName.basePack",
                    "对象名 ${entry.type}/${entry.name} 与数据包内置对象重名，Mod 内对象可能不会被加载",
                    entry.relativePath,
                )
            }

            FrameworkId.MC -> {
                // MC 数据键 = Namespace:Name；短文件名只在 Mod 内部使用。
                val dataKey = runCatching {
                    project.keyCodec.encodeObjectName(entry.name)
                }.getOrNull() ?: return
                if (pack.objectIndex.find(entry.type, dataKey) == null) return
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "conflict.dataKey.basePack",
                    "数据键 ${entry.type}/$dataKey 与数据包已有键冲突",
                    entry.relativePath,
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
                continue
            }
            if (pack == null) continue
            val builtinObject = pack.objectEntry(resource.typeName, resource.key)
            val builtinResource = pack.resources.find(resource.key, resource.kind)
            if (builtinObject != null || builtinResource != null) {
                issues += ValidationIssue(
                    ValidationSeverity.WARNING,
                    "resource.shadowBuiltin",
                    "资源键与数据包内置资源重名：${resource.typeName}/${resource.key}",
                    resource.relativePath,
                )
            }
        }
    }

    private fun isValidResourceKey(key: String): Boolean =
        key.isNotBlank() &&
            key != "." &&
            key != ".." &&
            key.none { it in "\\/:*?\"<>|" }
}
