package org.zender.editor.project

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.framework.FrameworkId
import org.zender.editor.framework.KeyCodec
import org.zender.editor.framework.ModFormat
import org.zender.editor.framework.ModMetadata
import org.zender.editor.objects.ObjectKey
import org.zender.editor.objects.ReferenceKind
import org.zender.editor.pack.DataPack
import java.nio.file.Path

/**
 * 可写 Mod 工程。
 *
 * - [format] 决定目录、引用键与本地化布局；
 * - [metadata] 是 ML/MC 元数据的统一视图；
 * - [workingTree] 是对象、补丁、资源与本地化的不可变快照；
 * - [basePack] 仅用于读取时解析引用，不随工程写入。
 */
data class ModProject(
    val root: Path?,
    val format: ModFormat,
    val metadata: ModMetadata,
    val workingTree: WorkingTree = WorkingTree(),
    val basePack: DataPack? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    init {
        require(metadata.framework == format.id) {
            "元数据框架 ${metadata.framework} 与工程框架 ${format.id} 不一致"
        }
    }

    val framework: FrameworkId get() = format.id

    val namespace: String get() = metadata.namespace

    val keyCodec: KeyCodec get() = format.keyCodec(metadata)

    fun objectOf(type: String, name: String): ProjectObject? =
        workingTree.objectOf(type, name)

    fun objectByGuid(uniqueId: String): ProjectObject? =
        workingTree.objectByGuid(uniqueId)

    fun withWorkingTree(tree: WorkingTree): ModProject = copy(workingTree = tree)

    fun withMetadata(metadata: ModMetadata): ModProject = copy(
        metadata = metadata,
        workingTree = workingTree,
    )

    fun withRoot(root: Path?): ModProject = copy(root = root)

    fun withBasePack(basePack: DataPack?): ModProject = copy(basePack = basePack)

    fun withDiagnostics(diagnostics: List<Diagnostic>): ModProject =
        copy(diagnostics = diagnostics.toList())

    /** 按当前工程生成默认对象路径。 */
    fun defaultObjectPath(type: String, name: String, kind: ProjectObjectKind): String =
        format.objectPath(
            type = type,
            name = name,
            uniqueId = kind == ProjectObjectKind.UNIQUE_ID,
        )

    fun findObjectKey(guid: String): ObjectKey? {
        objectByGuid(guid)?.let { return it.key }
        return basePack?.objectEntryByGuid(guid)?.key
    }

    /**
     * 把引用本工程「非 GUID 对象」的短键规范化为 MC 的 `Namespace:Name`。
     *
     * - ML 引用使用裸对象名，不做处理；
     * - 已带命名空间/多态前缀（`:`、`@`、`|`）的键保持原样；
     * - 只有解析结果确实是本工程的无 GUID 对象时才补命名空间，
     *   游戏本体对象、其他 Mod 与资源键不受影响。
     */
    fun normalizeReferenceKey(fieldType: String, rawKey: String): String {
        val key = rawKey.trim()
        if (key.isEmpty()) return rawKey
        if (framework != FrameworkId.MC) return key
        if (key.any { it == ':' || it == '@' || it == '|' }) return key
        val resolved = ProjectReferenceResolver(this, basePack).resolve(fieldType, key)
        if (resolved !is ReferenceCheck.Resolved) return key
        if (resolved.origin != ReferenceOrigin.PROJECT) return key
        val targetKey = resolved.targetKey ?: return key
        val target = workingTree.objects[targetKey] ?: return key
        if (target.uniqueId != null) return key
        return keyCodec.encodeReference(target.key, ReferenceKind.PLAIN_OBJECT)
    }
}

data class ModProjectLoadResult(
    val project: ModProject? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    val isSuccess: Boolean get() = project != null

    fun projectOrThrow(): ModProject =
        project ?: throw IllegalStateException(
            "Mod 工程加载失败：" + diagnostics.joinToString("；") { it.toString() },
        )
}

data class ModProjectWriteResult(
    val writtenFiles: List<String> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    val isSuccess: Boolean get() = diagnostics.none { it.isError }
}
