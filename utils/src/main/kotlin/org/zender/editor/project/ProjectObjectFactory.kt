package org.zender.editor.project

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.document.Member
import org.zender.editor.document.jsonArray
import org.zender.editor.document.jsonBoolean
import org.zender.editor.document.jsonNumber
import org.zender.editor.document.jsonObject
import org.zender.editor.document.jsonString
import org.zender.editor.framework.KeyCodec
import org.zender.editor.framework.ModFormat
import org.zender.editor.objects.ObjectIndex
import org.zender.editor.objects.ObjectKey
import org.zender.editor.pack.DataPack
import org.zender.editor.schema.FieldKind
import org.zender.editor.schema.FieldSchema
import org.zender.editor.schema.SchemaRepository
import java.util.UUID

data class NewObjectRequest(
    val type: String,
    val name: String? = null,
    val kind: ProjectObjectKind? = null,
    /** ML/MC 类型目录下的可选分组子目录，例如 CardData 的 `Item`。 */
    val group: String? = null,
    /** 指定 GUID；为空时自动生成。 */
    val uniqueId: String? = null,
    /** 从已有对象复制时提供源文档；会重新生成 GUID 与本地化 Key。 */
    val sourceDocument: JsonDocument? = null,
)

data class NewObjectResult(
    val project: ModProject? = null,
    val objectEntry: ProjectObject? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    val isSuccess: Boolean get() = objectEntry != null && diagnostics.none { it.isError }
}

/**
 * 新建工程对象：
 * - 依据数据包索引推断 UniqueID / ScriptableObject；
 * - 从 `BaseJson` 模板（缺失时从 TypeStruct）生成完整默认 JSON；
 * - UniqueID 自动生成 GUID，并同步 `UniqueID` 字段；
 * - 递归填充 `LocalizedString` 的 ParentObjectID / LocalizationKey；
 * - 对象名冲突直接拒绝，路径由 [ModFormat] 决定。
 */
class ProjectObjectFactory(
    private val basePack: DataPack? = null,
    private val guidGenerator: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {

    fun create(
        project: ModProject,
        request: NewObjectRequest,
    ): NewObjectResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val pack = basePack ?: project.basePack
        val type = request.type.trim()
        if (type.isBlank()) {
            return failure("对象类型不能为空")
        }
        if (!isFileSafeName(type)) {
            return failure("对象类型包含文件名非法字符：$type")
        }

        val kind = request.kind ?: inferKind(pack, type)
        val requestedName = request.name?.trim()?.takeIf { it.isNotEmpty() }
        if (requestedName != null && !isFileSafeName(requestedName)) {
            return failure("对象名包含文件名非法字符：$requestedName")
        }
        val group = request.group?.trim()?.takeIf { it.isNotEmpty() }
        if (group != null && !isFileSafeName(group)) {
            return failure("分组目录包含非法字符：$group")
        }

        val name = resolveName(project, type, requestedName)
            ?: return failure("对象名已存在：$type/${requestedName ?: type}")

        val uniqueId = if (kind == ProjectObjectKind.UNIQUE_ID) {
            resolveUniqueId(project, pack, request.uniqueId)
                ?: return failure("GUID 已存在：${request.uniqueId}")
        } else {
            null
        }

        val schema = pack?.schemaRepository
        if (schema?.schema(type) == null) {
            diagnostics += Diagnostic(
                DiagnosticSeverity.WARNING,
                "数据包中不存在类型 $type 的 TypeStruct，已创建空白对象",
                "Objects/$type/$name.json",
            )
        }
        if (pack == null) {
            diagnostics += Diagnostic(
                DiagnosticSeverity.WARNING,
                "尚未加载数据包，对象只包含空模板",
            )
        }

        var root = buildDocument(
            project = project,
            pack = pack,
            type = type,
            name = name,
            uniqueId = uniqueId,
            schema = schema,
            source = request.sourceDocument,
        )
        // CardData 在 Objects/CardData/<CardType>/ 下按 CardType 分目录；文件布局与字段必须一致。
        val layoutGroup = if (type == ObjectIndex.CARD_DATA_TYPE) {
            val cardType = resolveCardType(root, schema, group)
            val cardTypeValue = cardType?.second
            if (cardTypeValue != null) {
                val objectRoot = root.root as? JsonNode.ObjectNode
                if (objectRoot != null) {
                    root = JsonDocument(replaceMember(objectRoot, "CardType", cardTypeValue))
                }
            }
            cardType?.first
        } else {
            group
        }
        val directory = if (kind == ProjectObjectKind.UNIQUE_ID) {
            project.format.uniqueIdObjectDirectory(type)
        } else {
            project.format.scriptableObjectDirectory(type)
        }
        val relativePath = if (layoutGroup == null) {
            "$directory/$name.json"
        } else {
            "$directory/${layoutGroup.replace('\\', '/')}/$name.json"
        }
        val entry = ProjectObject(
            key = ObjectKey.of(type, name, uniqueId),
            kind = kind,
            document = root,
            relativePath = relativePath,
        )
        return try {
            NewObjectResult(
                project = project.withWorkingTree(project.workingTree.withObject(entry)),
                objectEntry = entry,
                diagnostics = diagnostics,
            )
        } catch (e: Exception) {
            failure("无法创建对象：${e.message}")
        }
    }

    private fun buildDocument(
        project: ModProject,
        pack: DataPack?,
        type: String,
        name: String,
        uniqueId: String?,
        schema: SchemaRepository?,
        source: JsonDocument?,
    ): JsonDocument {
        val typeSchema = schema?.schema(type)
        var root = (source?.root as? JsonNode.ObjectNode)
            ?: schema?.template(type)
            ?: typeSchema?.let { objectFromSchema(it, schema, 0) }
            ?: JsonNode.ObjectNode.EMPTY

        if (uniqueId != null) {
            root = replaceMember(root, "UniqueID", jsonString(uniqueId))
        }

        val keyCodec = runCatching { project.keyCodec }.getOrNull()
        if (keyCodec != null) {
            val populated = populateLocalizedStrings(
                node = root,
                path = JsonPath.ROOT,
                codec = keyCodec,
                objectName = name,
                uniqueId = uniqueId,
                forceKeys = source != null,
            )
            if (populated is JsonNode.ObjectNode) root = populated
        }
        return JsonDocument(root)
    }

    private fun objectFromSchema(
        schema: org.zender.editor.schema.TypeSchema,
        repository: SchemaRepository,
        depth: Int,
    ): JsonNode.ObjectNode {
        if (depth > MAX_DEFAULT_DEPTH) return JsonNode.ObjectNode.EMPTY
        val members = schema.fields.mapNotNull { field ->
            defaultNode(field, repository, depth)?.let { Member(field.name, it) }
        }
        return JsonNode.ObjectNode(members)
    }

    private fun defaultNode(
        field: FieldSchema,
        repository: SchemaRepository,
        depth: Int,
    ): JsonNode? {
        field.templateValue?.let { return it }
        return when (field.kind) {
            FieldKind.BOOLEAN -> jsonBoolean(false)
            FieldKind.INTEGER -> jsonNumber(0)
            FieldKind.FLOAT -> jsonNumber(0.0)
            FieldKind.STRING -> jsonString("")
            FieldKind.ENUM -> repository.enumValues(field.typeName).firstOrNull()?.value
            FieldKind.OBJECT -> repository.schema(field.typeName)
                ?.let { objectFromSchema(it, repository, depth + 1) }
                ?: jsonObject()

            FieldKind.LIST -> jsonArray()
            FieldKind.REFERENCE -> jsonObject()
            FieldKind.UNKNOWN -> null
        }
    }

    private fun populateLocalizedStrings(
        node: JsonNode,
        path: JsonPath,
        codec: KeyCodec,
        objectName: String,
        uniqueId: String?,
        forceKeys: Boolean = false,
    ): JsonNode = when (node) {
        is JsonNode.ObjectNode -> {
            val localized = node.contains("ParentObjectID") &&
                node.contains("LocalizationKey") &&
                node.contains("DefaultText")
            val members = node.members.map { member ->
                when {
                    localized && member.name == "ParentObjectID" -> {
                        val newValue = uniqueId ?: if (forceKeys) "" else null
                        if (newValue != null) {
                            Member(member.name, jsonString(newValue))
                        } else {
                            member
                        }
                    }

                    localized && member.name == "LocalizationKey" -> {
                        val current = (member.value as? JsonNode.StringNode)?.value.orEmpty()
                        if (forceKeys || current.isBlank()) {
                            val key = runCatching {
                                codec.localizationKey(objectName, path.format())
                            }.getOrNull()
                            if (key != null) Member(member.name, jsonString(key)) else member
                        } else {
                            member
                        }
                    }

                    else -> Member(
                        member.name,
                        populateLocalizedStrings(
                            member.value,
                            path.child(member.name),
                            codec,
                            objectName,
                            uniqueId,
                            forceKeys,
                        ),
                    )
                }
            }
            JsonNode.ObjectNode(members)
        }

        is JsonNode.ArrayNode -> JsonNode.ArrayNode(
            node.elements.mapIndexed { index, element ->
                populateLocalizedStrings(
                    element,
                    path.child(index),
                    codec,
                    objectName,
                    uniqueId,
                    forceKeys,
                )
            },
        )

        else -> node
    }

    /**
     * 解析 CardData 的 CardType 分组。
     *
     * 返回 Pair(目录名, 需要写回的枚举值)；请求的 group 若不是枚举名，只作为目录使用。
     * 未指定 group 时优先沿用文档里的 CardType，文档缺失时使用枚举首项。
     */
    private fun resolveCardType(
        document: JsonDocument,
        schema: SchemaRepository?,
        requested: String?,
    ): Pair<String, JsonNode?>? {
        val values = schema?.schema(ObjectIndex.CARD_DATA_TYPE)
            ?.field("CardType")
            ?.enumValues
            .orEmpty()
        if (requested != null) {
            val match = values.firstOrNull { it.name == requested }
            return requested to match?.value
        }
        val root = document.root as? JsonNode.ObjectNode ?: return null
        val current = root.get("CardType")
        val match = values.firstOrNull { it.value == current }
        if (match != null) return match.name to null
        if (current == null) {
            return values.firstOrNull()?.let { it.name to it.value }
        }
        return null
    }

    private fun replaceMember(
        node: JsonNode.ObjectNode,
        name: String,
        value: JsonNode,
    ): JsonNode.ObjectNode {
        val index = node.lastIndexOf(name)
        if (index < 0) return JsonNode.ObjectNode(node.members + Member(name, value))
        val members = node.members.toMutableList()
        members[index] = Member(name, value)
        return JsonNode.ObjectNode(members)
    }

    private fun resolveName(
        project: ModProject,
        type: String,
        requested: String?,
    ): String? {
        if (requested != null) {
            return requested.takeIf { project.workingTree.objectOf(type, it) == null }
        }
        val base = "New$type"
        if (project.workingTree.objectOf(type, base) == null) return base
        for (index in 2..MAX_NAME_ATTEMPTS) {
            val candidate = "$base$index"
            if (project.workingTree.objectOf(type, candidate) == null) return candidate
        }
        return null
    }

    private fun resolveUniqueId(
        project: ModProject,
        pack: DataPack?,
        requested: String?,
    ): String? {
        fun taken(guid: String): Boolean =
            project.workingTree.objectByGuid(guid) != null ||
                pack?.objectIndex?.findByGuid(guid) != null

        requested?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.takeUnless(::taken) }
        repeat(MAX_GUID_ATTEMPTS) {
            val candidate = guidGenerator().trim()
            if (candidate.isNotEmpty() && !taken(candidate)) return candidate
        }
        return null
    }

    private fun failure(message: String): NewObjectResult = NewObjectResult(
        diagnostics = listOf(Diagnostic(DiagnosticSeverity.ERROR, message)),
    )

    companion object {
        private const val MAX_NAME_ATTEMPTS = 1_000
        private const val MAX_GUID_ATTEMPTS = 50
        private const val MAX_DEFAULT_DEPTH = 8

        fun inferKind(pack: DataPack?, type: String): ProjectObjectKind {
            if (pack != null) {
                if (type in pack.objectIndex.guidIndexTypes) return ProjectObjectKind.UNIQUE_ID
                if (type in pack.objectIndex.nameIndexTypes) return ProjectObjectKind.SCRIPTABLE_OBJECT
            }
            val schema = pack?.schemaRepository?.schema(type)
            return if (schema?.containsField("UniqueID") == true || schema == null) {
                ProjectObjectKind.UNIQUE_ID
            } else {
                ProjectObjectKind.SCRIPTABLE_OBJECT
            }
        }

        fun isFileSafeName(value: String): Boolean =
            value.isNotBlank() &&
                value != "." &&
                value != ".." &&
                value.none { it in "\\/:*?\"<>|:@" }
    }
}
