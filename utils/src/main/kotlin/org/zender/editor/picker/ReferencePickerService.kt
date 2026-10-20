package org.zender.editor.picker

import org.zender.editor.model.EntryOrigin
import org.zender.editor.objects.ObjectIndex
import org.zender.editor.objects.ReferenceIndex
import org.zender.editor.objects.ReferenceKind
import org.zender.editor.objects.ReferenceResolution
import org.zender.editor.objects.ReferenceRule
import org.zender.editor.resource.ResourceInventory
import org.zender.editor.resource.ResourceKind

/**
 * 对象/资源选择器的一条候选。视图层只渲染该模型，不直接访问索引。
 */
data class PickerItem(
    val type: String,
    val name: String,
    val uniqueId: String? = null,
    val displayName: String? = null,
    val referenceKind: ReferenceKind,
    val origin: EntryOrigin,
    val group: String? = null,
    /** 资源条目相对路径；对象条目为 null。 */
    val resourcePath: String? = null,
) {
    val displayText: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: name

    val isResource: Boolean get() = resourcePath != null
}

/**
 * 选择器查询。`offset` + `limit` 实现分页，避免一次渲染数千个候选。
 */
data class PickerQuery(
    /** 引用字段的声明类型；为 null 时不按类型过滤。 */
    val fieldType: String? = null,
    val kind: ReferenceKind? = null,
    val keyword: String = "",
    val origin: EntryOrigin? = null,
    val offset: Int = 0,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(offset >= 0) { "offset 不能为负数" }
        require(limit in 1..MAX_LIMIT) { "limit 必须在 1..$MAX_LIMIT 之间" }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 50
        const val MAX_LIMIT: Int = 1000
    }
}

data class PickerPage(
    val items: List<PickerItem>,
    val total: Int,
    val offset: Int,
    val limit: Int,
) {
    val hasMore: Boolean
        get() = offset + items.size < total
}

/**
 * 对象/资源引用选择器的数据层查询服务。
 *
 * 负责候选收集、类型/来源/关键字过滤与分页；不依赖 Compose，也不直接读文件。
 */
class ReferencePickerService(
    private val objectIndex: ObjectIndex,
    private val resources: ResourceInventory,
    private val rule: ReferenceRule,
    private val referenceIndex: ReferenceIndex,
) {

    fun query(query: PickerQuery = PickerQuery()): PickerPage {
        val keyword = query.keyword.trim()
        val keywordLower = keyword.lowercase()
        val filtered = allItems.asSequence()
            .filter { query.fieldType == null || matchesFieldType(query.fieldType, it) }
            .filter { query.kind == null || it.referenceKind == query.kind }
            .filter { query.origin == null || it.origin == query.origin }
            .filter { item -> keywordLower.isEmpty() || item.matches(keywordLower) }
            .sortedWith(
                compareBy<PickerItem> {
                    if (keywordLower.isNotEmpty() && it.name.equals(keyword, ignoreCase = true)) 0 else 1
                }
                    .thenBy { it.type }
                    .thenBy { it.name }
                    .thenBy { it.resourcePath.orEmpty() },
            )
            .toList()

        val pageItems = filtered.drop(query.offset).take(query.limit)
        return PickerPage(
            items = pageItems,
            total = filtered.size,
            offset = query.offset,
            limit = query.limit,
        )
    }

    /**
     * 候选快照。索引在数据包加载后不可变，因此只构建一次；
     * 过滤/分页在快照上进行，避免每次输入都重建数千个条目。
     */
    private val allItems: List<PickerItem> by lazy { buildItems() }

    /** 校验选择器返回的条目仍然存在且类型兼容。 */
    fun resolve(item: PickerItem): ReferenceResolution {
        if (item.uniqueId != null) {
            return referenceIndex.resolveUniqueId(item.type, item.uniqueId)
        }
        return referenceIndex.resolveName(item.type, item.name)
    }

    private fun buildItems(): List<PickerItem> {
        val objects = objectIndex.entries
            .filter { rule.isReferenceType(it.type) }
            .map { entry ->
                PickerItem(
                    type = entry.type,
                    name = entry.name,
                    uniqueId = entry.uniqueId,
                    displayName = entry.displayName,
                    referenceKind = rule.kindFor(entry.type) ?: ReferenceKind.PLAIN_OBJECT,
                    origin = entry.origin,
                    group = entry.group,
                )
            }
        val resourceItems = resources.entries.map { entry ->
            PickerItem(
                type = entry.typeName,
                name = entry.key,
                displayName = null,
                referenceKind = when (entry.kind) {
                    ResourceKind.IMAGE -> ReferenceKind.SPRITE
                    ResourceKind.AUDIO -> ReferenceKind.AUDIO
                },
                origin = entry.origin,
                resourcePath = entry.relativePath,
            )
        }
        return objects + resourceItems
    }

    private fun matchesFieldType(fieldType: String, item: PickerItem): Boolean =
        rule.isCompatible(fieldType, item.type).compatible

    private fun PickerItem.matches(keywordLower: String): Boolean =
        name.lowercase().contains(keywordLower) ||
            displayName?.lowercase()?.contains(keywordLower) == true ||
            uniqueId?.lowercase()?.contains(keywordLower) == true ||
            type.lowercase().contains(keywordLower) ||
            group?.lowercase()?.contains(keywordLower) == true
}
