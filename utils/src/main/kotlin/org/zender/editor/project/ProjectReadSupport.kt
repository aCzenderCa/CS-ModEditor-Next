package org.zender.editor.project

import org.zender.editor.diagnostics.DiagnosticCollector
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.io.FileStore
import org.zender.editor.objects.ObjectKey
import java.nio.file.Path

/**
 * 工程读取的共享文件/JSON 工具。
 */
internal class ProjectReadSupport(
    val fileStore: FileStore,
    val projectRoot: Path,
    val diagnostics: DiagnosticCollector,
) {

    fun relative(path: Path): String =
        try {
            projectRoot.relativize(path).toString().replace('\\', '/')
        } catch (_: IllegalArgumentException) {
            path.toString()
        }

    fun directories(directory: Path): List<Path> =
        fileStore.list(directory).filter { fileStore.isDirectory(it) }

    fun jsonFiles(directory: Path, includeJsonnet: Boolean): List<Path> =
        fileStore.walkFiles(directory).filter { file ->
            val name = file.fileName.toString().lowercase()
            name.endsWith(".json") || (includeJsonnet && name.endsWith(".jsonnet"))
        }

    fun readDocument(file: Path, relativePath: String): JsonDocument? {
        val text = try {
            fileStore.readText(file)
        } catch (e: Exception) {
            diagnostics.error("无法读取对象文件：${e.message}", relativePath)
            return null
        }
        val parsed = JsonDocument.parse(text)
        parsed.issues.forEach { diagnostics.warning(it.message, relativePath) }
        val document = parsed.document
        if (document == null) {
            val hint = if (file.fileName.toString().lowercase().endsWith(".jsonnet")) {
                "（jsonnet 求值暂不支持，请使用已求值的 JSON）"
            } else {
                ""
            }
            diagnostics.error("不是合法 JSON$hint：${parsed.error?.message}", relativePath)
            return null
        }
        if (document.root !is JsonNode.ObjectNode) {
            diagnostics.error("对象文件顶层必须是对象", relativePath)
            return null
        }
        return document
    }

    fun uniqueId(document: JsonDocument): String? {
        val root = document.root as? JsonNode.ObjectNode ?: return null
        val node = root.get("UniqueID") as? JsonNode.StringNode ?: return null
        return node.value.trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        fun stem(path: Path): String {
            val name = path.fileName.toString()
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(0, dot) else name
        }
    }
}

/**
 * 读取 UniqueID / ScriptableObject 类型目录中的对象，统一处理重名与重复 GUID。
 */
internal class ProjectObjectReader(
    private val support: ProjectReadSupport,
    private val objects: LinkedHashMap<ObjectKey, ProjectObject>,
) {

    private val namesByType = HashMap<String, MutableSet<String>>()
    private val guidOwners = HashMap<String, ObjectKey>()

    fun readUniqueIdType(
        type: String,
        directory: Path,
        includeJsonnet: Boolean,
    ) {
        for (file in support.jsonFiles(directory, includeJsonnet)) {
            val relativePath = support.relative(file)
            val name = ProjectReadSupport.stem(file)
            if (!addName(type, name)) {
                support.diagnostics.warning(
                    "类型 $type 下对象名重复：$name（保留首个文件）",
                    relativePath,
                )
                continue
            }
            val document = support.readDocument(file, relativePath) ?: continue
            val uniqueId = support.uniqueId(document)
            if (uniqueId == null) {
                support.diagnostics.warning(
                    "UniqueID 对象缺少非空 UniqueID 字段",
                    relativePath,
                )
            }
            val key = ObjectKey.of(type, name, uniqueId)
            if (uniqueId != null) {
                val owner = guidOwners.putIfAbsent(uniqueId, key)
                if (owner != null && owner != key) {
                    support.diagnostics.warning(
                        "GUID 重复：$uniqueId（${owner.type}/${owner.name} 与 $type/$name）",
                        relativePath,
                    )
                }
            }
            objects[key] = ProjectObject(
                key = key,
                kind = ProjectObjectKind.UNIQUE_ID,
                document = document,
                relativePath = relativePath,
            )
        }
    }

    fun readScriptableObjectType(
        type: String,
        directory: Path,
        includeJsonnet: Boolean,
    ) {
        for (file in support.jsonFiles(directory, includeJsonnet)) {
            val relativePath = support.relative(file)
            val name = ProjectReadSupport.stem(file)
            if (!addName(type, name)) {
                support.diagnostics.warning(
                    "类型 $type 下对象名重复：$name（保留首个文件）",
                    relativePath,
                )
                continue
            }
            val document = support.readDocument(file, relativePath) ?: continue
            val key = ObjectKey.of(type, name)
            objects[key] = ProjectObject(
                key = key,
                kind = ProjectObjectKind.SCRIPTABLE_OBJECT,
                document = document,
                relativePath = relativePath,
            )
        }
    }

    private fun addName(type: String, name: String): Boolean =
        namesByType.getOrPut(type) { LinkedHashSet() }.add(name)
}
