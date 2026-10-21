package org.zender.editor.framework

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonFormat
import org.zender.editor.document.JsonNode
import org.zender.editor.document.Member
import org.zender.editor.document.jsonArray
import org.zender.editor.document.jsonString
import org.zender.editor.document.toJsonText

/**
 * ML / MC 元数据的统一模型。
 *
 * [namespace] 在 ML 中是 `ModInfo.Name`，在 MC 中是 `ModMeta.Namespace`。
 * [names] / [descriptions] 保存按语言的值；ML 使用 [DEFAULT_LANGUAGE] 键。
 * [raw] 保留原文件对象，写回时用于保留未知字段与字段顺序。
 */
data class ModMetadata(
    val framework: FrameworkId,
    val namespace: String,
    val names: Map<String, String> = emptyMap(),
    val descriptions: Map<String, String> = emptyMap(),
    val author: String = "",
    val version: String = "",
    val modLoaderVersion: String? = null,
    val modEditorVersion: String? = null,
    val icon: String? = null,
    val pluginGuids: List<String> = emptyList(),
    val raw: JsonNode.ObjectNode? = null,
) {
    /** 优先中文展示名，其次英文，最后任意非空值或命名空间。 */
    fun displayName(
        preferredKeys: List<String> = listOf("SimpCn", "En", DEFAULT_LANGUAGE),
    ): String =
        preferredKeys.firstNotNullOfOrNull { names[it]?.takeIf(String::isNotBlank) }
            ?: names.values.firstOrNull { it.isNotBlank() }
            ?: namespace

    companion object {
        /** ML 只有单个 Name 字符串，映射到该键。 */
        const val DEFAULT_LANGUAGE: String = "Default"
    }
}

data class ModMetadataParseResult(
    val metadata: ModMetadata? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    val isSuccess: Boolean get() = metadata != null

    fun metadataOrThrow(): ModMetadata =
        metadata ?: throw IllegalStateException("元数据解析失败")
}

/**
 * `ModInfo.json` / `ModMeta.json` 读取器。
 */
object ModMetadataReader {

    fun parseMl(text: String): ModMetadataParseResult {
        val parsed = parseRoot(text, "ModInfo.json")
        val diagnostics = parsed.diagnostics.toMutableList()
        val root = parsed.root ?: return ModMetadataParseResult(diagnostics = diagnostics)

        val name = root.stringMember("Name", "ModInfo.json", diagnostics)
            ?: return ModMetadataParseResult(
                diagnostics = diagnostics + Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "缺少字符串字段 Name",
                    "ModInfo.json",
                ),
            )
        if (name.isBlank()) {
            return ModMetadataParseResult(
                diagnostics = diagnostics + Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "Name 不能为空",
                    "ModInfo.json",
                ),
            )
        }

        return ModMetadataParseResult(
            metadata = ModMetadata(
                framework = FrameworkId.ML,
                namespace = name,
                names = mapOf(ModMetadata.DEFAULT_LANGUAGE to name),
                author = root.stringMember("Author", "ModInfo.json", diagnostics).orEmpty(),
                version = root.stringMember("Version", "ModInfo.json", diagnostics).orEmpty(),
                modLoaderVersion = root.stringMember(
                    "ModLoaderVerison",
                    "ModInfo.json",
                    diagnostics,
                ) ?: root.stringMember("ModLoaderVersion", "ModInfo.json", diagnostics),
                modEditorVersion = root.stringMember(
                    "ModEditorVersion",
                    "ModInfo.json",
                    diagnostics,
                ),
                raw = root,
            ),
            diagnostics = diagnostics,
        )
    }

    fun parseMc(text: String): ModMetadataParseResult {
        val parsed = parseRoot(text, "ModMeta.json")
        val diagnostics = parsed.diagnostics.toMutableList()
        val root = parsed.root ?: return ModMetadataParseResult(diagnostics = diagnostics)

        val namespace = root.stringMember("Namespace", "ModMeta.json", diagnostics)
            ?: return ModMetadataParseResult(
                diagnostics = diagnostics + Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "缺少字符串字段 Namespace",
                    "ModMeta.json",
                ),
            )
        if (!ModNamespace.isValid(namespace)) {
            diagnostics += Diagnostic(
                DiagnosticSeverity.WARNING,
                "Namespace 包含非法字符（: @ |）：$namespace",
                "ModMeta.json",
            )
        }

        return ModMetadataParseResult(
            metadata = ModMetadata(
                framework = FrameworkId.MC,
                namespace = namespace,
                names = root.localizedMap("Name", "ModMeta.json", diagnostics),
                descriptions = root.localizedMap("Description", "ModMeta.json", diagnostics),
                author = root.stringMember("Author", "ModMeta.json", diagnostics).orEmpty(),
                version = root.stringMember("Version", "ModMeta.json", diagnostics).orEmpty(),
                icon = root.stringMember("Icon", "ModMeta.json", diagnostics),
                pluginGuids = root.stringList("PluginGuid", "ModMeta.json", diagnostics),
                raw = root,
            ),
            diagnostics = diagnostics,
        )
    }

    private data class RootParse(
        val root: JsonNode.ObjectNode?,
        val diagnostics: List<Diagnostic>,
    )

    private fun parseRoot(text: String, path: String): RootParse {
        val parsed = JsonDocument.parse(text)
        val diagnostics = parsed.issues.map {
            Diagnostic(DiagnosticSeverity.WARNING, it.message, path)
        }.toMutableList()
        val document = parsed.document
        if (document == null) {
            return RootParse(
                null,
                diagnostics + Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "不是合法 JSON：${parsed.error?.message}",
                    path,
                ),
            )
        }
        val root = document.root as? JsonNode.ObjectNode
        if (root == null) {
            return RootParse(
                null,
                diagnostics + Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "顶层必须是对象",
                    path,
                ),
            )
        }
        return RootParse(root, diagnostics)
    }
}

/**
 * 元数据写回。所有更新在 [ModMetadata.raw] 上进行：
 * 已有字段原位替换，未知字段与顺序保持不变。
 */
object ModMetadataWriter {

    fun writeMl(metadata: ModMetadata): String {
        require(metadata.framework == FrameworkId.ML) { "ML 元数据不能写入 MC 文件" }
        var root = metadata.raw ?: JsonNode.ObjectNode.EMPTY
        root = root
            .withMember("Name", jsonString(metadata.namespace))
            .withMember("Author", jsonString(metadata.author))
            .withMember("Version", jsonString(metadata.version))
        val loader = metadata.modLoaderVersion
        if (loader != null) {
            val key = if (root.contains("ModLoaderVersion") &&
                !root.contains("ModLoaderVerison")
            ) {
                "ModLoaderVersion"
            } else {
                "ModLoaderVerison"
            }
            root = root.withMember(key, jsonString(loader))
        } else {
            root = root.withoutMember("ModLoaderVerison").withoutMember("ModLoaderVersion")
        }
        val editor = metadata.modEditorVersion
        if (editor != null) {
            root = root.withMember("ModEditorVersion", jsonString(editor))
        } else {
            root = root.withoutMember("ModEditorVersion")
        }
        return root.toJsonText(JsonFormat.Pretty())
    }

    fun writeMc(metadata: ModMetadata): String {
        require(metadata.framework == FrameworkId.MC) { "MC 元数据不能写入 ML 文件" }
        var root = metadata.raw ?: JsonNode.ObjectNode.EMPTY
        root = root
            .withMember("Namespace", jsonString(metadata.namespace))
            .withMember(
                "Name",
                replaceLocalized(root.get("Name"), metadata.names),
            )
            .withMember(
                "Description",
                replaceLocalized(root.get("Description"), metadata.descriptions),
            )
            .withMember("Author", jsonString(metadata.author))
            .withMember("Version", jsonString(metadata.version))
            .withMember("Icon", jsonString(metadata.icon.orEmpty()))
            .withMember(
                "PluginGuid",
                jsonArray(*metadata.pluginGuids.map { jsonString(it) }.toTypedArray()),
            )
        return root.toJsonText(JsonFormat.Pretty())
    }

    /**
     * 用 [values] 完整替换本地化对象：保留未改动语言的顺序，删除已从元数据中移除的语言。
     */
    private fun replaceLocalized(
        existing: JsonNode?,
        values: Map<String, String>,
    ): JsonNode {
        val current = existing as? JsonNode.ObjectNode ?: JsonNode.ObjectNode.EMPTY
        var result = current
        current.members.forEach { member ->
            if (member.name !in values) result = result.withoutMember(member.name)
        }
        values.forEach { (language, text) ->
            result = result.withMember(language, jsonString(text))
        }
        return result
    }
}

internal fun JsonNode.ObjectNode.withoutMember(name: String): JsonNode.ObjectNode {
    if (get(name) == null) return this
    return JsonNode.ObjectNode(members.filterNot { it.name == name })
}

internal fun JsonNode.ObjectNode.withMember(
    name: String,
    value: JsonNode,
): JsonNode.ObjectNode {
    val index = lastIndexOf(name)
    if (index < 0) {
        return JsonNode.ObjectNode(members + Member(name, value))
    }
    val updated = members.toMutableList()
    updated[index] = Member(name, value)
    return JsonNode.ObjectNode(updated.toList())
}

private fun JsonNode.ObjectNode.stringMember(
    name: String,
    path: String,
    diagnostics: MutableList<Diagnostic>,
): String? {
    val node = get(name) ?: return null
    if (node is JsonNode.StringNode) return node.value.trim()
    diagnostics += Diagnostic(
        DiagnosticSeverity.WARNING,
        "字段 $name 必须是字符串，已忽略",
        path,
    )
    return null
}

private fun JsonNode.ObjectNode.localizedMap(
    name: String,
    path: String,
    diagnostics: MutableList<Diagnostic>,
): Map<String, String> {
    val node = get(name) ?: return emptyMap()
    if (node is JsonNode.StringNode) {
        return mapOf(ModMetadata.DEFAULT_LANGUAGE to node.value)
    }
    if (node !is JsonNode.ObjectNode) {
        diagnostics += Diagnostic(
            DiagnosticSeverity.WARNING,
            "字段 $name 必须是对象或字符串，已忽略",
            path,
        )
        return emptyMap()
    }
    val values = LinkedHashMap<String, String>()
    node.members.forEach { member ->
        val value = member.value
        if (value is JsonNode.StringNode) {
            values[member.name] = value.value
        } else {
            diagnostics += Diagnostic(
                DiagnosticSeverity.WARNING,
                "$name.${member.name} 必须是字符串，已忽略",
                path,
            )
        }
    }
    return values
}

private fun JsonNode.ObjectNode.stringList(
    name: String,
    path: String,
    diagnostics: MutableList<Diagnostic>,
): List<String> {
    val node = get(name) ?: return emptyList()
    val array = node as? JsonNode.ArrayNode
    if (array == null) {
        diagnostics += Diagnostic(
            DiagnosticSeverity.WARNING,
            "字段 $name 必须是字符串数组，已忽略",
            path,
        )
        return emptyList()
    }
    return array.elements.mapNotNull { element ->
        if (element is JsonNode.StringNode) {
            element.value
        } else {
            diagnostics += Diagnostic(
                DiagnosticSeverity.WARNING,
                "$name 中存在非字符串值，已忽略",
                path,
            )
            null
        }
    }
}
