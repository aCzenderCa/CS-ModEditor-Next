package org.zender.editor.project

import org.zender.editor.document.DocumentId
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonFormat
import java.security.MessageDigest

/**
 * 文档级与工程级脏状态跟踪。
 *
 * 脏状态通过内容指纹判断，而不是“是否执行过命令”：
 * 撤销回保存点时文档会自动恢复为干净状态，避免了朴素的布尔标记误报。
 */
class DirtyTracker {

    private val savedFingerprints = mutableMapOf<DocumentId, String>()
    private val currentFingerprints = mutableMapOf<DocumentId, String>()
    private var projectDirty: Boolean = false

    /** 打开文档时登记保存基线。 */
    fun open(id: DocumentId, document: JsonDocument) {
        val fingerprint = fingerprint(document)
        savedFingerprints[id] = fingerprint
        currentFingerprints[id] = fingerprint
    }

    /**
     * 文档内容变化后更新指纹。未登记的文档按“打开时即干净”处理。
     */
    fun update(id: DocumentId, document: JsonDocument) {
        val fingerprint = fingerprint(document)
        if (id !in savedFingerprints) {
            savedFingerprints[id] = fingerprint
        }
        currentFingerprints[id] = fingerprint
    }

    /** 当前内容已写入磁盘，把保存基线前移。 */
    fun markSaved(id: DocumentId, document: JsonDocument) {
        val fingerprint = fingerprint(document)
        savedFingerprints[id] = fingerprint
        currentFingerprints[id] = fingerprint
    }

    /** 一次保存多个文档（例如“保存全部”）。 */
    fun markAllSaved(documents: Map<DocumentId, JsonDocument>) {
        documents.forEach { (id, document) -> markSaved(id, document) }
    }

    fun isDirty(id: DocumentId): Boolean =
        id in savedFingerprints && savedFingerprints[id] != currentFingerprints[id]

    fun dirtyDocuments(): Set<DocumentId> =
        savedFingerprints.keys.filterTo(LinkedHashSet()) { isDirty(it) }

    fun openDocuments(): Set<DocumentId> = savedFingerprints.keys.toSet()

    val hasDirtyDocuments: Boolean
        get() = savedFingerprints.keys.any { isDirty(it) }

    /** 资源、工程元数据等非文档内容的修改标记。 */
    fun markProjectDirty() {
        projectDirty = true
    }

    fun markProjectSaved() {
        projectDirty = false
    }

    val hasUnsavedProjectChanges: Boolean
        get() = projectDirty

    val hasUnsavedChanges: Boolean
        get() = projectDirty || hasDirtyDocuments

    /** 关闭文档时移除其基线。 */
    fun close(id: DocumentId) {
        savedFingerprints.remove(id)
        currentFingerprints.remove(id)
    }

    /** 清空所有状态（切换工程时使用）。 */
    fun reset() {
        savedFingerprints.clear()
        currentFingerprints.clear()
        projectDirty = false
    }

    private fun fingerprint(document: JsonDocument): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(document.toJsonText(JsonFormat.Compact).toByteArray(Charsets.UTF_8))
        return toHex(bytes)
    }

    private fun toHex(bytes: ByteArray): String {
        val hex = HEX_DIGITS
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(hex[value ushr 4])
                append(hex[value and 0x0F])
            }
        }
    }

    private companion object {
        const val HEX_DIGITS = "0123456789abcdef"
    }
}
