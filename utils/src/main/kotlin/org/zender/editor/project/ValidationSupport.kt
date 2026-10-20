package org.zender.editor.project

import org.zender.editor.document.JsonPath
import org.zender.editor.pack.DataPack

/** 把文档相对路径与字段路径组合为可点击的定位文本。 */
internal fun fieldPathText(relativePath: String, path: JsonPath): String =
    if (path.isEmpty) relativePath else "$relativePath:${path.format()}"

/**
 * GameSourceModify 的目标类型：优先使用读取时解析的结果，其次查工程内对象，
 * 最后查数据包 GUID 索引。目标来自其他 Mod 时返回 null。
 */
internal fun gameSourceModifyTargetType(
    project: ModProject,
    pack: DataPack?,
    patch: GameSourceModify,
): String? =
    patch.targetKey?.type
        ?: project.objectByGuid(patch.targetGuid)?.type
        ?: pack?.objectEntryByGuid(patch.targetGuid)?.type
