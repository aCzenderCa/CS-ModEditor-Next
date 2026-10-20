package org.zender.editor.objects

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.model.EntryOrigin

/**
 * 数据包对象索引：Type/Name/GUID 三类查询。
 *
 * - `Objects` 文件扫描结果；
 * - `GUID` 索引补充 GUID 与中文显示名；
 * - `Name` 索引补充无 GUID 对象的显示名；
 * - 只有索引、没有对象文件的条目（游戏内置资源等）也会保留，作为引用目标。
 */
class ObjectIndex(
    val entries: List<ObjectEntry> = emptyList(),
    val issues: List<String> = emptyList(),
    /** `GUID` 索引覆盖的类型（UniqueIDScriptable 系）。 */
    val guidIndexTypes: Set<String> = emptySet(),
    /** `Name` 索引覆盖的类型（无 GUID 的 ScriptableObject 系）。 */
    val nameIndexTypes: Set<String> = emptySet(),
) {

    val types: Set<String> =
        (entries.mapTo(LinkedHashSet()) { it.type } + guidIndexTypes + nameIndexTypes)

    val referenceTypes: Set<String> = guidIndexTypes + nameIndexTypes

    val byType: Map<String, List<ObjectEntry>> =
        entries.groupByTo(LinkedHashMap()) { it.type }

    val byName: Map<String, Map<String, ObjectEntry>> =
        entries.groupBy { it.type }
            .mapValuesTo(LinkedHashMap()) { (_, values) ->
                values.associateByTo(LinkedHashMap()) { it.name }
            }

    val byNameAll: Map<String, List<ObjectEntry>> =
        entries.groupByTo(LinkedHashMap()) { it.name }

    /** 重复 GUID 时保留先出现的条目；冲突已由 `ObjectIndexBuilder` 记录诊断。 */
    val byGuid: Map<String, ObjectEntry> = buildMap {
        this@ObjectIndex.entries.forEach { entry ->
            val uniqueId = entry.uniqueId ?: return@forEach
            if (!containsKey(uniqueId)) put(uniqueId, entry)
        }
    }

    /** `CardData` 按 CardType 分组；没有分组的条目归入空字符串键。 */
    val cardDataByCardType: Map<String, List<ObjectEntry>> =
        entries.filter { it.type == CARD_DATA_TYPE }
            .groupByTo(LinkedHashMap()) { it.group.orEmpty() }

    fun find(type: String, name: String): ObjectEntry? = byName[type]?.get(name)

    fun findByGuid(uniqueId: String): ObjectEntry? = byGuid[uniqueId]

    fun findAllByName(name: String): List<ObjectEntry> = byNameAll[name].orEmpty()

    fun entriesOfType(type: String): List<ObjectEntry> = byType[type].orEmpty()

    fun entriesOfCardType(cardType: String): List<ObjectEntry> =
        cardDataByCardType[cardType].orEmpty()

    fun isReferenceType(type: String): Boolean = type in referenceTypes

    companion object {
        const val CARD_DATA_TYPE: String = "CardData"

        val EMPTY: ObjectIndex = ObjectIndex()
    }
}

object ObjectIndexBuilder {

    data class BuildResult(
        val index: ObjectIndex,
        val diagnostics: List<Diagnostic> = emptyList(),
    )

    /**
     * 合并对象扫描结果与 GUID/Name 索引。
     *
     * 对象名在同一类型内必须唯一；GUID 全局唯一。冲突时保留先出现的条目并记录诊断，
     * 不因为单个坏条目中断数据包加载。
     */
    fun build(
        scanned: List<ObjectEntry>,
        guidEntries: List<ObjectIndexEntry> = emptyList(),
        nameEntries: List<ObjectIndexEntry> = emptyList(),
        guidTypes: Set<String> = emptySet(),
        nameTypes: Set<String> = emptySet(),
        indexOrigin: EntryOrigin = EntryOrigin.BASE_PACK,
    ): BuildResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val issues = mutableListOf<String>()
        val entries = LinkedHashMap<Pair<String, String>, ObjectEntry>()
        val guidOwners = HashMap<String, Pair<String, String>>()

        fun put(key: Pair<String, String>, entry: ObjectEntry) {
            entries[key] = entry
        }

        for (entry in scanned) {
            val key = entry.type to entry.name
            if (entries.containsKey(key)) {
                diagnostics += Diagnostic(
                    DiagnosticSeverity.WARNING,
                    "对象名重复：${entry.type}/${entry.name}",
                    entry.relativePath,
                )
                continue
            }
            put(key, entry)
        }

        for (indexEntry in guidEntries) {
            val key = indexEntry.type to indexEntry.name
            val existing = entries[key]
            val uniqueId = indexEntry.value
            val owner = guidOwners.putIfAbsent(uniqueId, key)
            if (owner != null && owner != key) {
                val message = "GUID 重复：$uniqueId（${owner.first}/${owner.second} 与 " +
                    "${indexEntry.type}/${indexEntry.name}）"
                diagnostics += Diagnostic(DiagnosticSeverity.WARNING, message, indexEntry.sourcePath)
                issues += message
            }

            if (existing == null) {
                put(
                    key,
                    ObjectEntry(
                        key = ObjectKey.of(indexEntry.type, indexEntry.name, uniqueId),
                        relativePath = null,
                        group = indexEntry.group,
                        displayName = indexEntry.displayName,
                        origin = indexOrigin,
                    ),
                )
                continue
            }

            if (existing.uniqueId != null && existing.uniqueId != uniqueId) {
                val message = "对象 ${existing.type}/${existing.name} 已有关联 GUID " +
                    "${existing.uniqueId}，索引中的 $uniqueId 被忽略"
                diagnostics += Diagnostic(DiagnosticSeverity.WARNING, message, indexEntry.sourcePath)
                issues += message
                put(
                    key,
                    existing.copy(
                        group = existing.group ?: indexEntry.group,
                        displayName = existing.displayName ?: indexEntry.displayName,
                    ),
                )
            } else {
                put(
                    key,
                    existing.copy(
                        key = existing.key.copy(uniqueId = existing.uniqueId ?: uniqueId),
                        group = existing.group ?: indexEntry.group,
                        displayName = existing.displayName ?: indexEntry.displayName,
                    ),
                )
            }
        }

        for (indexEntry in nameEntries) {
            val key = indexEntry.type to indexEntry.name
            val existing = entries[key]
            if (existing == null) {
                put(
                    key,
                    ObjectEntry(
                        key = ObjectKey.of(indexEntry.type, indexEntry.name),
                        relativePath = null,
                        group = indexEntry.group,
                        displayName = indexEntry.displayName,
                        origin = indexOrigin,
                    ),
                )
            } else {
                put(
                    key,
                    existing.copy(
                        group = existing.group ?: indexEntry.group,
                        displayName = existing.displayName ?: indexEntry.displayName,
                    ),
                )
            }
        }

        val sorted = entries.values.sortedWith(compareBy({ it.type }, { it.name }))
        return BuildResult(
            index = ObjectIndex(
                entries = sorted,
                issues = issues.toList(),
                guidIndexTypes = guidTypes,
                nameIndexTypes = nameTypes,
            ),
            diagnostics = diagnostics,
        )
    }
}
