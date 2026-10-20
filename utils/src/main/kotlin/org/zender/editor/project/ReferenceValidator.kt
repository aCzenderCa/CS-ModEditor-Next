package org.zender.editor.project

import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.edit.WarpFields
import org.zender.editor.framework.DecodedKey
import org.zender.editor.framework.FrameworkId
import org.zender.editor.framework.KeyCodec
import org.zender.editor.objects.ReferenceKind
import org.zender.editor.objects.ReferenceRule
import org.zender.editor.pack.DataPack
import org.zender.editor.resource.ResourceKind
import org.zender.editor.schema.FieldKind
import org.zender.editor.schema.SchemaRepository

/** 引用目标的来源。 */
enum class ReferenceOrigin {
    PROJECT,
    BASE_PACK,
    PROJECT_RESOURCE,
    BASE_PACK_RESOURCE,
}

/** 单条引用的检查结果，供校验器与字段检查器共用。 */
sealed interface ReferenceCheck {
    data class Resolved(
        val fieldType: String,
        val key: String,
        val targetType: String?,
        val origin: ReferenceOrigin,
    ) : ReferenceCheck

    data class NotFound(
        val fieldType: String,
        val key: String,
    ) : ReferenceCheck

    data class TypeMismatch(
        val fieldType: String,
        val key: String,
        val targetType: String,
    ) : ReferenceCheck

    data class Ambiguous(
        val fieldType: String,
        val key: String,
        val candidates: List<String>,
    ) : ReferenceCheck

    /** 其他 Mod 命名空间或未知字段类型，编辑器无法确认目标。 */
    data class Unverified(
        val fieldType: String,
        val key: String,
        val reason: String,
    ) : ReferenceCheck
}

/**
 * 工程级引用解析：同时考虑工程新增对象/资源与数据包内置目标。
 *
 * 与 [org.zender.editor.objects.ReferenceIndex] 的区别：
 * - 目标可以来自 `ModProject.workingTree`；
 * - MC 数据键会按当前工程 Namespace 与 `Namespace:Name` 规则解码；
 * - 支持导出前校验所需的「项目/数据包/其他 Mod」来源区分。
 */
class ProjectReferenceResolver(
    private val project: ModProject,
    private val pack: DataPack? = null,
) {

    private val rule: ReferenceRule = buildRule()

    private val codec: KeyCodec? = runCatching { project.keyCodec }.getOrNull()

    fun resolve(fieldType: String, rawKey: String): ReferenceCheck {
        val key = rawKey.trim()
        if (key.isEmpty()) return ReferenceCheck.NotFound(fieldType, key)

        val fieldKind = rule.kindFor(fieldType)
        if (fieldKind == null && !isGenericFieldType(fieldType)) {
            return ReferenceCheck.Unverified(
                fieldType,
                key,
                "字段类型 $fieldType 不是数据包中的已知引用类型",
            )
        }

        return when (fieldKind) {
            ReferenceKind.UNIQUE_ID -> resolveGuid(fieldType, key)
            ReferenceKind.SPRITE -> resolveResource(fieldType, key, ResourceKind.IMAGE)
            ReferenceKind.AUDIO -> resolveResource(fieldType, key, ResourceKind.AUDIO)
            ReferenceKind.PLAIN_OBJECT -> resolveObjectName(fieldType, key)
            null -> resolveGeneric(
                fieldType,
                key,
                uniqueOnly = fieldType == UNIQUE_ID_BASE,
            )
        }
    }

    private fun resolveGuid(fieldType: String, key: String): ReferenceCheck {
        project.workingTree.objectByGuid(key)?.let { entry ->
            return if (compatible(fieldType, entry.type, ReferenceKind.UNIQUE_ID)) {
                ReferenceCheck.Resolved(fieldType, key, entry.type, ReferenceOrigin.PROJECT)
            } else {
                ReferenceCheck.TypeMismatch(fieldType, key, entry.type)
            }
        }
        pack?.objectIndex?.findByGuid(key)?.let { entry ->
            return if (compatible(fieldType, entry.type, ReferenceKind.UNIQUE_ID)) {
                ReferenceCheck.Resolved(fieldType, key, entry.type, ReferenceOrigin.BASE_PACK)
            } else {
                ReferenceCheck.TypeMismatch(fieldType, key, entry.type)
            }
        }
        return ReferenceCheck.NotFound(fieldType, key)
    }

    private fun resolveObjectName(fieldType: String, key: String): ReferenceCheck {
        val decoded = decode(key)
        val targetName = decoded?.name ?: key
        val otherNamespace = decoded?.namespace?.takeIf {
            it.isNotBlank() && it != project.namespace
        }
        if (otherNamespace != null) {
            return ReferenceCheck.Unverified(
                fieldType,
                key,
                "引用了其他命名空间 $otherNamespace，目标可能来自其他 Mod",
            )
        }

        project.workingTree.objectOf(fieldType, targetName)?.let { entry ->
            return ReferenceCheck.Resolved(
                fieldType,
                key,
                entry.type,
                ReferenceOrigin.PROJECT,
            )
        }
        val projectConflict = project.workingTree.objects.values.firstOrNull {
            it.name == targetName
        }
        if (projectConflict != null && !compatible(fieldType, projectConflict.type, null)) {
            return ReferenceCheck.TypeMismatch(fieldType, key, projectConflict.type)
        }

        // 完整自身数据键（Namespace:Name）只查本工程，不回退到数据包裸名。
        if (isExplicitSelfNamespace(decoded)) return ReferenceCheck.NotFound(fieldType, key)

        pack?.objectIndex?.find(fieldType, targetName)?.let { entry ->
            return if (compatible(fieldType, entry.type, rule.kindFor(entry.type))) {
                ReferenceCheck.Resolved(fieldType, key, entry.type, ReferenceOrigin.BASE_PACK)
            } else {
                ReferenceCheck.TypeMismatch(fieldType, key, entry.type)
            }
        }
        val packConflict = pack?.objectIndex?.findAllByName(targetName)
            ?.firstOrNull { it.type != fieldType }
        if (packConflict != null) {
            return ReferenceCheck.TypeMismatch(fieldType, key, packConflict.type)
        }
        return ReferenceCheck.NotFound(fieldType, key)
    }

    private fun resolveResource(
        fieldType: String,
        key: String,
        kind: ResourceKind,
    ): ReferenceCheck {
        val decoded = decode(key)
        val targetName = decoded?.name ?: key
        val otherNamespace = decoded?.namespace?.takeIf {
            it.isNotBlank() && it != project.namespace
        }
        if (otherNamespace != null) {
            return ReferenceCheck.Unverified(
                fieldType,
                key,
                "引用了其他命名空间 $otherNamespace 的资源",
            )
        }

        project.workingTree.resource(kind, targetName)?.let { entry ->
            return ReferenceCheck.Resolved(
                fieldType,
                key,
                entry.typeName,
                ReferenceOrigin.PROJECT_RESOURCE,
            )
        }
        if (!isExplicitSelfNamespace(decoded)) {
            pack?.resources?.find(targetName, kind)?.let { entry ->
                return ReferenceCheck.Resolved(
                    fieldType,
                    key,
                    entry.typeName,
                    ReferenceOrigin.BASE_PACK_RESOURCE,
                )
            }
        }
        return ReferenceCheck.NotFound(fieldType, key)
    }

    private fun resolveGeneric(
        fieldType: String,
        key: String,
        uniqueOnly: Boolean,
    ): ReferenceCheck {
        // 1. GUID 优先。
        project.workingTree.objectByGuid(key)?.let { entry ->
            if (uniqueOnly && rule.kindFor(entry.type) != ReferenceKind.UNIQUE_ID) {
                return ReferenceCheck.TypeMismatch(fieldType, key, entry.type)
            }
            return ReferenceCheck.Resolved(
                fieldType,
                key,
                entry.type,
                ReferenceOrigin.PROJECT,
            )
        }
        pack?.objectIndex?.findByGuid(key)?.let { entry ->
            if (uniqueOnly && rule.kindFor(entry.type) != ReferenceKind.UNIQUE_ID) {
                return ReferenceCheck.TypeMismatch(fieldType, key, entry.type)
            }
            return ReferenceCheck.Resolved(
                fieldType,
                key,
                entry.type,
                ReferenceOrigin.BASE_PACK,
            )
        }

        val decoded = decode(key)
        val targetName = decoded?.name ?: key
        val otherNamespace = decoded?.namespace?.takeIf {
            it.isNotBlank() && it != project.namespace
        }
        if (otherNamespace != null) {
            return ReferenceCheck.Unverified(
                fieldType,
                key,
                "引用了其他命名空间 $otherNamespace，目标可能来自其他 Mod",
            )
        }

        val includeResources = fieldType in OBJECT_FIELD_TYPES
        // MC 的完整自身数据键只会命中本工程；短键按运行时顺序先查全局（数据包），
        // 再查 Mod 内部。ML 的裸对象名同样先加载游戏本体。
        val includePack = !isExplicitSelfNamespace(decoded)
        val candidates = mutableListOf<Candidate>()

        if (includePack) {
            pack?.objectIndex?.findAllByName(targetName)
                ?.filter { !uniqueOnly || rule.kindFor(it.type) == ReferenceKind.UNIQUE_ID }
                ?.forEach { candidates += Candidate.PackObject(it.type) }
        }

        project.workingTree.objects.values
            .filter { it.name == targetName }
            .filter { !uniqueOnly || rule.kindFor(it.type) == ReferenceKind.UNIQUE_ID }
            .forEach { candidates += Candidate.ProjectObject(it.type) }

        if (includeResources) {
            if (includePack) {
                pack?.resources?.findAll(targetName)
                    ?.forEach { candidates += Candidate.PackResource(it.typeName) }
            }
            project.workingTree.resources.values
                .filter { it.key == targetName }
                .forEach { candidates += Candidate.ProjectResource(it.typeName) }
        }

        // 同名同类型从工程与数据包重复命中属于“遮蔽”而不是歧义，保留先加载的一项。
        val distinct = candidates.distinctBy { it.typeName }
        return when {
            distinct.isEmpty() -> ReferenceCheck.NotFound(fieldType, key)
            distinct.size == 1 -> ReferenceCheck.Resolved(
                fieldType,
                key,
                distinct.single().typeName,
                distinct.single().origin,
            )

            else -> ReferenceCheck.Ambiguous(
                fieldType,
                key,
                distinct.map { "${it.typeName}(${it.origin.name})" },
            )
        }
    }

    private fun compatible(
        fieldType: String,
        targetType: String,
        targetKind: ReferenceKind?,
    ): Boolean = when {
        fieldType == targetType -> true
        fieldType in GENERIC_OBJECT_TYPES -> true
        fieldType == UNIQUE_ID_BASE -> targetKind == ReferenceKind.UNIQUE_ID
        else -> rule.isCompatible(fieldType, targetType).compatible
    }

    private fun decode(key: String): DecodedKey? {
        val keyCodec = codec ?: return null
        return runCatching { keyCodec.decodeObjectName(key) }.getOrNull()
    }

    /**
     * 完整自身数据键（`Namespace:Name`，不是短键）只在本 Mod 内查找。
     * MC 短键与 ML 裸名仍会先命中游戏本体。
     */
    private fun isExplicitSelfNamespace(decoded: DecodedKey?): Boolean =
        decoded != null &&
            !decoded.isShort &&
            decoded.namespace?.isNotBlank() == true &&
            decoded.namespace == project.namespace

    private fun buildRule(): ReferenceRule {
        val kinds = LinkedHashMap<String, ReferenceKind>()
        pack?.referenceRule?.referenceTypes?.forEach { type ->
            pack.referenceRule.kindFor(type)?.let { kinds[type] = it }
        }
        for (entry in project.workingTree.objects.values) {
            val kind = when {
                entry.kind == ProjectObjectKind.UNIQUE_ID -> ReferenceKind.UNIQUE_ID
                entry.type in SPRITE_TYPES -> ReferenceKind.SPRITE
                entry.type == AUDIO_TYPE -> ReferenceKind.AUDIO
                else -> ReferenceKind.PLAIN_OBJECT
            }
            kinds.putIfAbsent(entry.type, kind)
        }
        SPRITE_TYPES.forEach { kinds.putIfAbsent(it, ReferenceKind.SPRITE) }
        kinds.putIfAbsent(AUDIO_TYPE, ReferenceKind.AUDIO)
        return ReferenceRule.of(kinds)
    }

    private fun isGenericFieldType(type: String): Boolean =
        type in GENERIC_OBJECT_TYPES || type == UNIQUE_ID_BASE

    private sealed interface Candidate {
        val typeName: String
        val origin: ReferenceOrigin

        data class ProjectObject(override val typeName: String) : Candidate {
            override val origin: ReferenceOrigin get() = ReferenceOrigin.PROJECT
        }

        data class PackObject(override val typeName: String) : Candidate {
            override val origin: ReferenceOrigin get() = ReferenceOrigin.BASE_PACK
        }

        data class ProjectResource(override val typeName: String) : Candidate {
            override val origin: ReferenceOrigin get() = ReferenceOrigin.PROJECT_RESOURCE
        }

        data class PackResource(override val typeName: String) : Candidate {
            override val origin: ReferenceOrigin get() = ReferenceOrigin.BASE_PACK_RESOURCE
        }
    }

    companion object {
        private const val UNIQUE_ID_BASE = "UniqueIDScriptable"
        private const val AUDIO_TYPE = "AudioClip"

        private val SPRITE_TYPES = setOf("Sprite", "Texture", "Texture2D")

        private val GENERIC_OBJECT_TYPES = setOf(
            "ScriptableObject",
            "UnityEngine.Object",
            "Object",
        )

        /** `Object` 基类字段可以引用资源；`ScriptableObject` 不可以。 */
        private val OBJECT_FIELD_TYPES = setOf("UnityEngine.Object", "Object")
    }
}

/**
 * E05：引用校验。
 *
 * 遍历对象与补丁中的引用字段，检查：
 * - `WarpData` / `WarpType` 的形状；
 * - 目标是否存在（工程对象、数据包对象、工程/数据包资源）；
 * - 目标类型是否与字段类型兼容；
 * - 同名目标是否产生歧义；
 * - 其他 Mod 命名空间只告警，不判定失败。
 */
class ReferenceValidator(private val basePack: DataPack? = null) {

    /** 单条引用检查，供字段视图/选择器在提交前复用。 */
    fun check(project: ModProject, fieldType: String, rawKey: String): ReferenceCheck =
        ProjectReferenceResolver(project, basePack ?: project.basePack)
            .resolve(fieldType, rawKey)

    fun validate(project: ModProject): List<ValidationIssue> {
        val pack = basePack ?: project.basePack ?: return emptyList()
        val schemas = pack.schemaRepository
        val resolver = ProjectReferenceResolver(project, pack)
        val issues = mutableListOf<ValidationIssue>()

        for (entry in project.workingTree.objects.values) {
            if (schemas.schema(entry.type) == null) continue
            validateDocument(
                project = project,
                schemas = schemas,
                typeName = entry.type,
                document = entry.document,
                relativePath = entry.relativePath,
                resolver = resolver,
                issues = issues,
                isPatch = false,
            )
        }

        for (patch in project.workingTree.gameSourceModifies.values) {
            val type = gameSourceModifyTargetType(project, pack, patch) ?: continue
            if (schemas.schema(type) == null) continue
            validateDocument(
                project = project,
                schemas = schemas,
                typeName = type,
                document = patch.document,
                relativePath = patch.relativePath,
                resolver = resolver,
                issues = issues,
                isPatch = true,
            )
        }

        for (patch in project.workingTree.dataObjectModifies.values) {
            if (schemas.schema(patch.type) == null) continue
            validateDocument(
                project = project,
                schemas = schemas,
                typeName = patch.type,
                document = patch.document,
                relativePath = patch.relativePath,
                resolver = resolver,
                issues = issues,
                isPatch = true,
            )
        }

        return issues
    }

    private fun validateDocument(
        project: ModProject,
        schemas: SchemaRepository,
        typeName: String,
        document: JsonDocument,
        relativePath: String,
        resolver: ProjectReferenceResolver,
        issues: MutableList<ValidationIssue>,
        isPatch: Boolean,
    ) {
        val root = document.root as? JsonNode.ObjectNode ?: return
        val walker = SchemaWalker(schemas)
        val visitor = ReferenceVisitor(
            framework = project.framework,
            isPatch = isPatch,
            resolver = resolver,
            relativePath = relativePath,
            issues = issues,
        )
        walker.walkObject(typeName, root, JsonPath.ROOT, visitor)
        val override = root.get(OVERRIDE_KEY)
        if (override is JsonNode.ObjectNode) {
            walker.walkObject(typeName, override, JsonPath.key(OVERRIDE_KEY), visitor)
        }
    }

    private class ReferenceVisitor(
        private val framework: FrameworkId,
        private val isPatch: Boolean,
        private val resolver: ProjectReferenceResolver,
        private val relativePath: String,
        private val issues: MutableList<ValidationIssue>,
    ) : SchemaWalker.SchemaVisitor {

        override fun onReference(
            typeName: String,
            field: org.zender.editor.schema.FieldSchema,
            path: JsonPath,
            owner: JsonNode.ObjectNode,
        ) {
            val dataPath = WarpFields.dataPath(path)
            val typePath = WarpFields.typePath(path)
            val data = owner.get(WarpFields.dataName(field.name))
            val warpType = owner.get(WarpFields.typeName(field.name))
            val typeValue = parseWarpType(warpType, typePath)

            if (field.kind == FieldKind.LIST) {
                validateList(data, field.elementTypeName ?: field.typeName, warpType, dataPath)
            } else {
                validateScalar(data, field.typeName, warpType, dataPath)
            }

            if (typeValue != null && data == null && warpType != null) {
                issue(
                    ValidationSeverity.WARNING,
                    "reference.data.missing",
                    "存在 ${WarpFields.typeName(field.name)} 但缺少 ${WarpFields.dataName(field.name)}",
                    dataPath,
                )
            }
        }

        private fun validateScalar(
            data: JsonNode?,
            fieldType: String,
            warpType: JsonNode?,
            dataPath: JsonPath,
        ) {
            when (data) {
                null, JsonNode.NullNode -> Unit

                is JsonNode.StringNode -> {
                    checkKey(data.value, fieldType, dataPath)
                    warnMissingWarpType(warpType, dataPath)
                }

                else -> issue(
                    ValidationSeverity.ERROR,
                    "reference.data.invalid",
                    "标量引用的 WarpData 必须是字符串",
                    dataPath,
                )
            }
        }

        private fun validateList(
            data: JsonNode?,
            elementType: String,
            warpType: JsonNode?,
            dataPath: JsonPath,
        ) {
            when (data) {
                null, JsonNode.NullNode -> Unit

                is JsonNode.ArrayNode -> {
                    data.elements.forEachIndexed { index, element ->
                        val elementPath = dataPath.child(index)
                        when (element) {
                            is JsonNode.StringNode ->
                                checkKey(element.value, elementType, elementPath)

                            else -> issue(
                                ValidationSeverity.ERROR,
                                "reference.data.invalid",
                                "列表引用的 WarpData 元素必须是字符串",
                                elementPath,
                            )
                        }
                    }
                    if (data.elements.isNotEmpty()) {
                        warnMissingWarpType(warpType, dataPath)
                    }
                }

                else -> issue(
                    ValidationSeverity.ERROR,
                    "reference.data.invalid",
                    "列表引用的 WarpData 必须是数组",
                    dataPath,
                )
            }
        }

        private fun checkKey(rawKey: String, fieldType: String, path: JsonPath) {
            val key = rawKey.trim()
            if (key.isEmpty()) {
                issue(
                    ValidationSeverity.ERROR,
                    "reference.key.blank",
                    "引用键不能为空",
                    path,
                )
                return
            }
            when (val result = resolver.resolve(fieldType, key)) {
                is ReferenceCheck.Resolved -> Unit

                is ReferenceCheck.NotFound -> issue(
                    ValidationSeverity.WARNING,
                    "reference.target.missing",
                    "引用目标不存在：$key（字段类型 $fieldType）",
                    path,
                )

                is ReferenceCheck.TypeMismatch -> issue(
                    ValidationSeverity.ERROR,
                    "reference.type.mismatch",
                    "引用目标 $key 的类型为 ${result.targetType}，与字段类型 $fieldType 不兼容",
                    path,
                )

                is ReferenceCheck.Ambiguous -> issue(
                    ValidationSeverity.ERROR,
                    "reference.target.ambiguous",
                    "引用目标 $key 有多个候选：${result.candidates.joinToString("、")}",
                    path,
                )

                is ReferenceCheck.Unverified -> issue(
                    ValidationSeverity.WARNING,
                    "reference.target.external",
                    "无法确认引用 $key：${result.reason}",
                    path,
                )
            }
        }

        private fun parseWarpType(node: JsonNode?, path: JsonPath): Int? {
            if (node == null) return null
            val number = node as? JsonNode.NumberNode
            val value = number?.toLongOrNull()
            if (value == null || value !in 0L..6L) {
                issue(
                    ValidationSeverity.ERROR,
                    "reference.warpType.invalid",
                    "WarpType 必须是 0..6 的整数",
                    path,
                )
                return null
            }
            return value.toInt()
        }

        private fun warnMissingWarpType(warpType: JsonNode?, path: JsonPath) {
            if (warpType != null) return
            if (isPatch) return
            if (framework != FrameworkId.ML) return
            issue(
                ValidationSeverity.WARNING,
                "reference.warpType.missing",
                "ML 引用缺少对应的 WarpType，运行时可能不会处理该引用",
                path,
            )
        }

        private fun issue(
            severity: ValidationSeverity,
            code: String,
            message: String,
            path: JsonPath,
        ) {
            issues += ValidationIssue(
                severity,
                code,
                message,
                fieldPathText(relativePath, path),
            )
        }
    }

    companion object {
        private const val OVERRIDE_KEY = "\$override"
    }
}
