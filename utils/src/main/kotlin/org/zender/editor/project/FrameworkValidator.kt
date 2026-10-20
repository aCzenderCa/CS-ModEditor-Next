package org.zender.editor.project

import org.zender.editor.document.JsonNode
import org.zender.editor.framework.FrameworkCapability
import org.zender.editor.framework.FrameworkId
import org.zender.editor.pack.DataPack
import org.zender.editor.resource.ResourceKind

/**
 * E06：框架能力与补丁语义校验。
 *
 * 重点覆盖 ML / MC 不能互换的语义：
 * - `$override` / `$matchTargets` 仅 MC；
 * - MC 补丁只处理 `WarpData` 的 4/5/6，普通字段必须放进 `$override`；
 * - ML 补丁的 `WarpType` 3..6 有效，1/2 未实现；
 * - `MatchTagWarpData` / `MatchTypeWarpData` 只对 `CardData` 生效；
 * - 资源类型必须被目标框架支持。
 */
class FrameworkValidator(private val basePack: DataPack? = null) {

    fun validate(project: ModProject): List<ValidationIssue> {
        val pack = basePack ?: project.basePack
        val issues = mutableListOf<ValidationIssue>()

        validateCapabilities(project, issues)
        for (patch in project.workingTree.gameSourceModifies.values) {
            val targetType = gameSourceModifyTargetType(project, pack, patch)
            validatePatch(
                project = project,
                root = patch.document.root as? JsonNode.ObjectNode,
                relativePath = patch.relativePath,
                targetType = targetType,
                dataObjectModify = false,
                pack = pack,
                issues = issues,
            )
        }
        for (patch in project.workingTree.dataObjectModifies.values) {
            validatePatch(
                project = project,
                root = patch.document.root as? JsonNode.ObjectNode,
                relativePath = patch.relativePath,
                targetType = patch.type,
                dataObjectModify = true,
                pack = pack,
                issues = issues,
            )
        }
        return issues
    }

    private fun validateCapabilities(
        project: ModProject,
        issues: MutableList<ValidationIssue>,
    ) {
        if (project.workingTree.dataObjectModifies.isNotEmpty() &&
            !project.format.supports(FrameworkCapability.DATA_OBJECT_MODIFY)
        ) {
            for (patch in project.workingTree.dataObjectModifies.values) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "patch.dataObjectModify.unsupported",
                    "${project.framework} 不支持 DataObjectModify",
                    patch.relativePath,
                )
            }
        }

        for (resource in project.workingTree.resources.values) {
            val capability = when (resource.kind) {
                ResourceKind.IMAGE -> FrameworkCapability.IMAGE_RESOURCES
                ResourceKind.AUDIO -> FrameworkCapability.AUDIO_RESOURCES
            }
            if (!project.format.supports(capability)) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "framework.resource.unsupported",
                    "${project.framework} 不支持 ${resource.kind} 资源",
                    resource.relativePath,
                )
            }
        }

        if (project.framework == FrameworkId.ML &&
            project.workingTree.gameSourceModifies.isNotEmpty() &&
            project.metadata.modEditorVersion.isNullOrBlank()
        ) {
            issues += ValidationIssue(
                ValidationSeverity.WARNING,
                "framework.gameSourceModify.editorVersionMissing",
                "ModInfo.ModEditorVersion 为空，游戏会跳过整个 GameSourceModify",
                project.format.metadataFileName,
            )
        }
    }

    private fun validatePatch(
        project: ModProject,
        root: JsonNode.ObjectNode?,
        relativePath: String,
        targetType: String?,
        dataObjectModify: Boolean,
        pack: DataPack?,
        issues: MutableList<ValidationIssue>,
    ) {
        if (root == null) {
            issues += ValidationIssue(
                ValidationSeverity.ERROR,
                "patch.document.invalid",
                "${if (dataObjectModify) "DataObjectModify" else "GameSourceModify"} 顶层必须是 JSON 对象",
                relativePath,
            )
            return
        }

        validateDirectives(project, root, relativePath, issues)
        validateMatchDirectives(root, relativePath, targetType, issues)
        validateWarpPairs(project.framework, root, relativePath, issues)

        if (dataObjectModify && pack != null && !pack.schemaRepository.hasType(targetType.orEmpty())) {
            issues += ValidationIssue(
                ValidationSeverity.WARNING,
                "framework.dataObjectModify.typeUnknown",
                "DataObjectModify 类型 $targetType 未出现在数据包 TypeStruct 中",
                relativePath,
            )
        }

        if (project.framework == FrameworkId.MC) {
            validateMcPlainFields(root, relativePath, issues)
        }
    }

    private fun validateDirectives(
        project: ModProject,
        root: JsonNode.ObjectNode,
        relativePath: String,
        issues: MutableList<ValidationIssue>,
    ) {
        val override = root.get(OVERRIDE_KEY)
        if (override != null) {
            if (!project.format.supports(FrameworkCapability.PATCH_OVERRIDE)) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "patch.override.unsupported",
                    "${project.framework} 不支持 \$override",
                    relativePath,
                )
            }
            if (override !is JsonNode.ObjectNode) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "patch.override.invalid",
                    "\$override 必须是 JSON 对象",
                    relativePath,
                )
            }
        }

        val matchTargets = root.get(MATCH_TARGETS_KEY)
        if (matchTargets != null) {
            if (!project.format.supports(FrameworkCapability.PATCH_MATCH_TARGETS)) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "patch.matchTargets.unsupported",
                    "${project.framework} 不支持 \$matchTargets",
                    relativePath,
                )
            }
            if (matchTargets !is JsonNode.ArrayNode ||
                matchTargets.elements.any { it !is JsonNode.StringNode }
            ) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "patch.matchTargets.invalid",
                    "\$matchTargets 必须是字符串数组",
                    relativePath,
                )
            }
        }
    }

    private fun validateMatchDirectives(
        root: JsonNode.ObjectNode,
        relativePath: String,
        targetType: String?,
        issues: MutableList<ValidationIssue>,
    ) {
        val matchTags = root.get(MATCH_TAG_KEY)
        if (matchTags != null) {
            val valid = matchTags is JsonNode.ArrayNode &&
                matchTags.elements.all { it is JsonNode.StringNode }
            if (!valid) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "patch.matchTag.invalid",
                    "MatchTagWarpData 必须是字符串数组",
                    relativePath,
                )
            }
            if (targetType != null && targetType != CARD_DATA) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "framework.matchTag.nonCardData",
                    "MatchTagWarpData 只对 CardData 生效，当前目标为 $targetType",
                    relativePath,
                )
            }
        }

        val matchTypes = root.get(MATCH_TYPE_KEY)
        if (matchTypes != null) {
            val valid = matchTypes is JsonNode.ArrayNode &&
                matchTypes.elements.all { it is JsonNode.StringNode }
            if (!valid) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "patch.matchType.invalid",
                    "MatchTypeWarpData 必须是字符串数组",
                    relativePath,
                )
            }
            if (targetType != null && targetType != CARD_DATA) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "framework.matchType.nonCardData",
                    "MatchTypeWarpData 只对 CardData 生效，当前目标为 $targetType",
                    relativePath,
                )
            }
        }
    }

    private fun validateWarpPairs(
        framework: FrameworkId,
        root: JsonNode.ObjectNode,
        relativePath: String,
        issues: MutableList<ValidationIssue>,
    ) {
        val names = root.members.mapTo(HashSet()) { it.name }
        for (member in root.members) {
            // MatchTagWarpData / MatchTypeWarpData 虽以 WarpData 结尾，但不是字段引用配对。
            if (member.name in SchemaWalker.SPECIAL_KEYS) continue
            val base = when {
                member.name.endsWith(WARP_TYPE_SUFFIX) ->
                    member.name.removeSuffix(WARP_TYPE_SUFFIX)

                member.name.endsWith(WARP_DATA_SUFFIX) ->
                    member.name.removeSuffix(WARP_DATA_SUFFIX)

                else -> null
            } ?: continue
            if (base.isEmpty()) continue

            val hasData = "${base}WarpData" in names
            if (member.name.endsWith(WARP_DATA_SUFFIX)) {
                if ("${base}WarpType" !in names) {
                    issues += ValidationIssue(
                        ValidationSeverity.ERROR,
                        "framework.warpType.missing",
                        "${member.name} 缺少对应的 ${base}WarpType",
                        relativePath,
                    )
                }
                continue
            }

            val value = (member.value as? JsonNode.NumberNode)?.toLongOrNull()
            if (value == null || value !in 0L..6L) {
                issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "framework.warpType.invalid",
                    "${member.name} 必须是 0..6 的整数",
                    relativePath,
                )
                continue
            }
            validateWarpSemantics(
                framework = framework,
                value = value.toInt(),
                hasData = hasData,
                name = member.name,
                relativePath = relativePath,
                issues = issues,
            )
        }

        // 嵌套对象/数组中的引用同样适用补丁语义。
        for (member in root.members) {
            when (val value = member.value) {
                is JsonNode.ObjectNode ->
                    validateWarpPairs(framework, value, relativePath, issues)

                is JsonNode.ArrayNode -> value.elements.forEach { element ->
                    if (element is JsonNode.ObjectNode) {
                        validateWarpPairs(framework, element, relativePath, issues)
                    }
                }

                else -> Unit
            }
        }
    }

    private fun validateWarpSemantics(
        framework: FrameworkId,
        value: Int,
        hasData: Boolean,
        name: String,
        relativePath: String,
        issues: MutableList<ValidationIssue>,
    ) {
        if (!hasData) return
        when (framework) {
            FrameworkId.ML -> when (value) {
                0 -> issues += ValidationIssue(
                    ValidationSeverity.WARNING,
                    "framework.warpType.none",
                    "$name = 0（NONE），对应的 WarpData 不会被处理",
                    relativePath,
                )

                1, 2 -> issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "framework.warpType.unsupported",
                    "ML 未实现 WarpType $value（COPY/CUSTOM）",
                    relativePath,
                )
            }

            FrameworkId.MC -> when (value) {
                3 -> issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "framework.warpType.ignored",
                    "$name = 3（REFERENCE）在 MC 补丁中不会处理，请改用 4/5/6 或 \$override",
                    relativePath,
                )

                0, 1, 2 -> issues += ValidationIssue(
                    ValidationSeverity.ERROR,
                    "framework.warpType.unsupported",
                    "MC 补丁不支持 WarpType $value",
                    relativePath,
                )
            }
        }
    }

    /**
     * MC 补丁只处理 `WarpData` 与 `$override`；其他顶层字段会被运行时忽略。
     * 字段本体（例如 `CardImage:{}`）只要存在对应的 `WarpData` 配对就允许保留。
     */
    private fun validateMcPlainFields(
        root: JsonNode.ObjectNode,
        relativePath: String,
        issues: MutableList<ValidationIssue>,
    ) {
        val names = root.members.mapTo(HashSet()) { it.name }
        for (member in root.members) {
            if (isAllowedMcPatchKey(member.name, names)) continue
            issues += ValidationIssue(
                ValidationSeverity.ERROR,
                "framework.patch.plainFieldIgnored",
                "MC 补丁中的普通字段 ${member.name} 会被忽略，请放入 \$override 或改用 WarpData",
                relativePath,
            )
        }
    }

    private fun isAllowedMcPatchKey(name: String, names: Set<String>): Boolean {
        if (name.startsWith("$")) return true
        if (name in SchemaWalker.SPECIAL_KEYS) return true
        if (name.endsWith(WARP_DATA_SUFFIX) || name.endsWith(WARP_TYPE_SUFFIX)) return true
        if ("${name}WarpData" in names || "${name}WarpType" in names) return true
        return false
    }

    companion object {
        private const val OVERRIDE_KEY = "\$override"
        private const val MATCH_TARGETS_KEY = "\$matchTargets"
        private const val MATCH_TAG_KEY = "MatchTagWarpData"
        private const val MATCH_TYPE_KEY = "MatchTypeWarpData"
        private const val WARP_DATA_SUFFIX = "WarpData"
        private const val WARP_TYPE_SUFFIX = "WarpType"
        private const val CARD_DATA = "CardData"
    }
}
