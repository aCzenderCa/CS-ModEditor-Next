package org.zender.editor.project

import org.zender.editor.document.JsonDocument
import org.zender.editor.localization.LocalizationEntry
import org.zender.editor.localization.LocalizationFileSpec
import org.zender.editor.localization.LocalizationTable
import org.zender.editor.objects.ObjectKey
import org.zender.editor.resource.ResourceKind
import java.nio.file.Path

enum class ProjectObjectKind {
    UNIQUE_ID,
    SCRIPTABLE_OBJECT,
}

/**
 * 工程中的新建/已有对象。文件路径相对工程根目录。
 */
data class ProjectObject(
    val key: ObjectKey,
    val kind: ProjectObjectKind,
    val document: JsonDocument,
    val relativePath: String,
) {
    init {
        require(relativePath.isNotBlank()) { "对象路径不能为空" }
    }

    val type: String get() = key.type

    val name: String get() = key.name

    val uniqueId: String? get() = key.uniqueId
}

/**
 * GameSourceModify 补丁。目标由文件名（GUID）决定。
 */
data class GameSourceModify(
    val targetGuid: String,
    val document: JsonDocument,
    val relativePath: String,
    /** 数据包中解析到的目标对象；其他 Mod 的目标可能为 null。 */
    val targetKey: ObjectKey? = null,
) {
    init {
        require(targetGuid.isNotBlank()) { "GameSourceModify 目标 GUID 不能为空" }
        require(relativePath.isNotBlank()) { "GameSourceModify 路径不能为空" }
    }
}

data class DataObjectModifyId(
    val type: String,
    val dataKey: String,
)

/**
 * DataObjectModify 补丁（仅 MC）。[dataKey] 为完整数据键（`@` 已规范化为 `:`）。
 */
data class DataObjectModify(
    val type: String,
    val dataKey: String,
    val document: JsonDocument,
    val relativePath: String,
    val targetKey: ObjectKey? = null,
) {
    init {
        require(type.isNotBlank()) { "DataObjectModify 类型不能为空" }
        require(dataKey.isNotBlank()) { "DataObjectModify 数据键不能为空" }
        require(relativePath.isNotBlank()) { "DataObjectModify 路径不能为空" }
    }

    val id: DataObjectModifyId get() = DataObjectModifyId(type, dataKey)
}

data class ResourceId(
    val kind: ResourceKind,
    val key: String,
) {
    init {
        require(key.isNotBlank()) { "资源键不能为空" }
    }
}

/**
 * 工程资源。读取时 [sourcePath] 指向工程内文件；导入外部资源时指向外部文件。
 */
data class ProjectResource(
    val id: ResourceId,
    val typeName: String,
    val relativePath: String,
    val sourcePath: Path? = null,
    val sizeBytes: Long = 0L,
) {
    init {
        require(typeName.isNotBlank()) { "资源类型不能为空" }
        require(relativePath.isNotBlank()) { "资源路径不能为空" }
    }

    val kind: ResourceKind get() = id.kind

    val key: String get() = id.key
}

/**
 * 工程本地化。
 *
 * [table] 是编辑器使用的统一 Key→English/中文 视图；
 * [files] 记录实际文件布局；[extraTexts] 保留未识别语言文件的内容。
 */
data class ProjectLocalization(
    val table: LocalizationTable = LocalizationTable.EMPTY,
    val files: List<LocalizationFileSpec> = emptyList(),
    val extraTexts: Map<String, Map<String, String>> = emptyMap(),
) {
    val keys: List<String>
        get() = table.entries.map { it.key }

    val isEmpty: Boolean
        get() = table.entries.isEmpty() && extraTexts.values.all { it.isEmpty() }

    fun entry(key: String): LocalizationEntry? = table.entry(key)

    fun withEntry(entry: LocalizationEntry): ProjectLocalization {
        require(entry.key.isNotBlank()) { "本地化 Key 不能为空" }
        val updated = LinkedHashMap<String, LocalizationEntry>()
        table.entries.forEach { updated[it.key] = it }
        updated[entry.key] = entry
        return copy(table = LocalizationTable(updated.values.toList()))
    }

    fun withoutKey(key: String): ProjectLocalization {
        val remaining = table.entries.filterNot { it.key == key }
        val extras = extraTexts.mapValues { (_, values) -> values - key }
        return copy(
            table = LocalizationTable(remaining),
            extraTexts = extras,
        )
    }

    fun withFiles(files: List<LocalizationFileSpec>): ProjectLocalization =
        copy(files = files.toList())
}

/**
 * Mod 工程的工作树。所有集合不可变，修改通过 `withXxx` 返回新快照。
 */
data class WorkingTree(
    val objects: Map<ObjectKey, ProjectObject> = emptyMap(),
    val gameSourceModifies: Map<String, GameSourceModify> = emptyMap(),
    val dataObjectModifies: Map<DataObjectModifyId, DataObjectModify> = emptyMap(),
    val resources: Map<ResourceId, ProjectResource> = emptyMap(),
    val localization: ProjectLocalization = ProjectLocalization(),
) {

    private val objectsByName: Map<Pair<String, String>, ProjectObject> =
        objects.values.associateByTo(LinkedHashMap()) { it.type to it.name }

    private val objectsByGuid: Map<String, ProjectObject> =
        objects.values.mapNotNull { objectEntry ->
            objectEntry.uniqueId?.let { it to objectEntry }
        }.toMap(LinkedHashMap())

    val objectCount: Int get() = objects.size

    val resourceCount: Int get() = resources.size

    fun objectOf(type: String, name: String): ProjectObject? = objectsByName[type to name]

    fun objectByGuid(uniqueId: String): ProjectObject? = objectsByGuid[uniqueId]

    fun uniqueIdObjectsOfType(type: String): List<ProjectObject> =
        objects.values.filter { it.type == type && it.kind == ProjectObjectKind.UNIQUE_ID }

    fun scriptableObjectsOfType(type: String): List<ProjectObject> =
        objects.values.filter { it.type == type && it.kind == ProjectObjectKind.SCRIPTABLE_OBJECT }

    fun gameSourceModify(targetGuid: String): GameSourceModify? =
        gameSourceModifies[targetGuid]

    fun dataObjectModify(type: String, dataKey: String): DataObjectModify? =
        dataObjectModifies[DataObjectModifyId(type, dataKey)]

    fun resource(kind: ResourceKind, key: String): ProjectResource? =
        resources[ResourceId(kind, key)]

    fun withObject(entry: ProjectObject): WorkingTree {
        val nameConflict = objectOf(entry.type, entry.name)
        require(nameConflict == null || nameConflict.key == entry.key) {
            "对象名冲突：${entry.type}/${entry.name}"
        }
        val guidConflict = entry.uniqueId?.let { objectByGuid(it) }
        require(guidConflict == null || guidConflict.key == entry.key) {
            "GUID 冲突：${entry.uniqueId}"
        }
        val updated = LinkedHashMap(objects)
        updated[entry.key] = entry
        return copy(objects = updated)
    }

    fun withoutObject(key: ObjectKey): WorkingTree {
        if (key !in objects) return this
        val updated = LinkedHashMap(objects)
        updated.remove(key)
        return copy(objects = updated)
    }

    fun withoutObject(type: String, name: String): WorkingTree {
        val existing = objectOf(type, name) ?: return this
        return withoutObject(existing.key)
    }

    fun withGameSourceModify(entry: GameSourceModify): WorkingTree {
        val updated = LinkedHashMap(gameSourceModifies)
        updated[entry.targetGuid] = entry
        return copy(gameSourceModifies = updated)
    }

    fun withoutGameSourceModify(targetGuid: String): WorkingTree {
        if (targetGuid !in gameSourceModifies) return this
        val updated = LinkedHashMap(gameSourceModifies)
        updated.remove(targetGuid)
        return copy(gameSourceModifies = updated)
    }

    fun withDataObjectModify(entry: DataObjectModify): WorkingTree {
        val updated = LinkedHashMap(dataObjectModifies)
        updated[entry.id] = entry
        return copy(dataObjectModifies = updated)
    }

    fun withoutDataObjectModify(type: String, dataKey: String): WorkingTree {
        val id = DataObjectModifyId(type, dataKey)
        if (id !in dataObjectModifies) return this
        val updated = LinkedHashMap(dataObjectModifies)
        updated.remove(id)
        return copy(dataObjectModifies = updated)
    }

    fun withResource(entry: ProjectResource): WorkingTree {
        val updated = LinkedHashMap(resources)
        updated[entry.id] = entry
        return copy(resources = updated)
    }

    fun withoutResource(id: ResourceId): WorkingTree {
        if (id !in resources) return this
        val updated = LinkedHashMap(resources)
        updated.remove(id)
        return copy(resources = updated)
    }

    fun withLocalization(localization: ProjectLocalization): WorkingTree =
        copy(localization = localization)
}
