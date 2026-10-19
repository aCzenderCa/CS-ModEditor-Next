package org.zender.editor.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.zender.editor.document.DocumentId
import org.zender.editor.document.JsonDocument
import org.zender.editor.edit.CommandStack
import org.zender.editor.edit.EditCommand
import org.zender.editor.edit.ReplaceDocumentCommand
import org.zender.editor.objects.ObjectKey
import org.zender.editor.project.DirtyTracker
import org.zender.editor.selection.FieldRef
import org.zender.editor.selection.SelectionState
import org.zender.editor.settings.FavoriteField
import java.nio.file.Path

/**
 * 单个文档的不可变状态快照。
 */
data class DocumentSession(
    val id: DocumentId,
    val title: String,
    val sourcePath: Path?,
    val document: JsonDocument,
    val revision: Long,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val undoLabel: String?,
    val redoLabel: String?,
    val isDirty: Boolean,
)

/**
 * 会话级状态。所有窗口共享同一份快照，`StateFlow` 天然完成跨窗口广播。
 */
data class EditorSessionState(
    val documents: Map<DocumentId, DocumentSession> = emptyMap(),
    /** 打开顺序，也是文件 Tab 的默认顺序。 */
    val documentOrder: List<DocumentId> = emptyList(),
    val activeDocumentId: DocumentId? = null,
    val selection: SelectionState = SelectionState(),
    val favorites: Set<FavoriteField> = emptySet(),
    val projectDirty: Boolean = false,
) {
    val hasUnsavedChanges: Boolean
        get() = projectDirty || documents.values.any { it.isDirty }
}

/**
 * 应用级编辑会话。
 *
 * 持有：
 * - 文档存储与每个文档的 [CommandStack]；
 * - 选择状态、收藏字段；
 * - 文档/工程脏状态。
 *
 * 视图层只读取 [state]，通过方法提交编辑；不要在窗口内各建一份状态，
 * 否则同一文档在多个窗口之间会分叉。
 */
class EditorSession(
    initialFavorites: Set<FavoriteField> = emptySet(),
) {

    private val lock = Any()
    private val stacks = LinkedHashMap<DocumentId, CommandStack>()
    private val titles = mutableMapOf<DocumentId, String>()
    private val sourcePaths = mutableMapOf<DocumentId, Path?>()
    private val order = mutableListOf<DocumentId>()
    private var activeDocumentId: DocumentId? = null
    private var selection = SelectionState()
    private var favorites: Set<FavoriteField> = initialFavorites.toSet()
    private val dirtyTracker = DirtyTracker()

    private val _state = MutableStateFlow(buildState())

    val state: StateFlow<EditorSessionState> = _state.asStateFlow()

    val currentState: EditorSessionState
        get() = _state.value

    // ---------------------------------------------------------------- 文档

    /**
     * 打开文档。已打开时只激活，不重建命令栈。
     */
    fun openDocument(
        id: DocumentId,
        document: JsonDocument,
        title: String = id.value,
        sourcePath: Path? = null,
        activate: Boolean = true,
    ): DocumentSession = synchronized(lock) {
        stacks[id]?.let { existing ->
            titles.putIfAbsent(id, title)
            if (sourcePaths[id] == null) sourcePaths[id] = sourcePath
            if (activate) activateInternal(id)
            emit()
            return snapshotOf(id) ?: error("文档状态缺失：$id")
        }

        stacks[id] = CommandStack(document)
        titles[id] = title
        sourcePaths[id] = sourcePath
        order += id
        dirtyTracker.open(id, document)
        if (activate || activeDocumentId == null) {
            activeDocumentId = id
            selection = selection.selectDocument(id)
        }
        emit()
        return snapshotOf(id) ?: error("文档状态缺失：$id")
    }

    /**
     * 关闭文档。有未保存修改且 [force] 为 false 时返回 false，不关闭。
     */
    fun closeDocument(id: DocumentId, force: Boolean = false): Boolean = synchronized(lock) {
        if (id !in stacks) return false
        if (!force && dirtyTracker.isDirty(id)) return false

        stacks.remove(id)
        titles.remove(id)
        sourcePaths.remove(id)
        order.remove(id)
        dirtyTracker.close(id)

        if (activeDocumentId == id) {
            activeDocumentId = order.lastOrNull()
        }
        if (selection.currentDocument == id) {
            selection = selection.selectDocument(activeDocumentId)
        }
        emit()
        return true
    }

    fun activateDocument(id: DocumentId): Boolean = synchronized(lock) {
        if (id !in stacks) return false
        activateInternal(id)
        emit()
        return true
    }

    fun document(id: DocumentId): JsonDocument? = synchronized(lock) { stacks[id]?.current }

    fun documentSnapshot(id: DocumentId): DocumentSession? = synchronized(lock) { snapshotOf(id) }

    // ---------------------------------------------------------------- 编辑

    /**
     * 执行编辑命令并返回新文档；文档不存在返回 null。
     */
    fun execute(id: DocumentId, command: EditCommand): JsonDocument? = synchronized(lock) {
        val stack = stacks[id] ?: return null
        val revisionBefore = stack.revision
        val updated = stack.execute(command)
        if (stack.revision == revisionBefore) return updated
        dirtyTracker.update(id, updated)
        emit()
        return updated
    }

    fun undo(id: DocumentId): Boolean = synchronized(lock) {
        val stack = stacks[id] ?: return false
        val updated = stack.undo() ?: return false
        dirtyTracker.update(id, updated)
        emit()
        return true
    }

    fun redo(id: DocumentId): Boolean = synchronized(lock) {
        val stack = stacks[id] ?: return false
        val updated = stack.redo() ?: return false
        dirtyTracker.update(id, updated)
        emit()
        return true
    }

    /** 整体替换文档（原始 JSON 提交、外部重载）。可撤销。 */
    fun replaceDocument(
        id: DocumentId,
        document: JsonDocument,
        label: String = "替换文档",
    ): JsonDocument? = execute(id, ReplaceDocumentCommand(label, document))

    /** 打断某个文档的连续编辑合并。 */
    fun endMergeGroup(id: DocumentId) = synchronized(lock) {
        stacks[id]?.endMergeGroup()
    }

    fun endAllMergeGroups() = synchronized(lock) {
        stacks.values.forEach { it.endMergeGroup() }
    }

    // ---------------------------------------------------------------- 保存

    fun markSaved(id: DocumentId): Boolean = synchronized(lock) {
        val stack = stacks[id] ?: return false
        dirtyTracker.markSaved(id, stack.current)
        emit()
        return true
    }

    fun markAllSaved() = synchronized(lock) {
        val currentDocuments = stacks.mapValues { it.value.current }
        dirtyTracker.markAllSaved(currentDocuments)
        dirtyTracker.markProjectSaved()
        emit()
    }

    fun markProjectDirty() = synchronized(lock) {
        dirtyTracker.markProjectDirty()
        emit()
    }

    fun markProjectSaved() = synchronized(lock) {
        dirtyTracker.markProjectSaved()
        emit()
    }

    // ---------------------------------------------------------------- 选择

    fun selectObject(objectKey: ObjectKey?, documentId: DocumentId? = activeDocumentId) =
        synchronized(lock) {
            if (documentId != null && documentId in stacks) {
                activeDocumentId = documentId
            }
            selection = selection.selectObject(objectKey, documentId ?: selection.currentDocument)
            emit()
        }

    fun selectField(field: FieldRef?) = synchronized(lock) {
        if (field != null && field.documentId in stacks) {
            activeDocumentId = field.documentId
        }
        selection = selection.selectField(field)
        emit()
    }

    /**
     * 记录最近聚焦的引用字段，供对象/资源选择器提交到该字段。
     */
    fun focusReferenceField(field: FieldRef) = synchronized(lock) {
        if (field.documentId in stacks) {
            activeDocumentId = field.documentId
        }
        selection = selection.focusReferenceField(field)
        emit()
    }

    fun clearSelection() = synchronized(lock) {
        selection = selection.clear()
        emit()
    }

    // ---------------------------------------------------------------- 收藏

    fun isFavorite(type: String, fieldPath: String): Boolean = synchronized(lock) {
        FavoriteField(type, fieldPath) in favorites
    }

    /**
     * 切换收藏状态，返回切换后是否为收藏。
     */
    fun toggleFavorite(type: String, fieldPath: String): Boolean = synchronized(lock) {
        require(type.isNotBlank()) { "收藏字段类型不能为空" }
        require(fieldPath.isNotBlank()) { "收藏字段路径不能为空" }
        val favorite = FavoriteField(type, fieldPath)
        val nowFavorite = favorite !in favorites
        favorites = if (nowFavorite) favorites + favorite else favorites - favorite
        emit()
        return nowFavorite
    }

    fun replaceFavorites(newFavorites: Set<FavoriteField>) = synchronized(lock) {
        favorites = newFavorites.toSet()
        emit()
    }

    // ---------------------------------------------------------------- 内部

    private fun activateInternal(id: DocumentId) {
        activeDocumentId = id
        selection = selection.selectDocument(id)
    }

    private fun snapshotOf(id: DocumentId): DocumentSession? {
        val stack = stacks[id] ?: return null
        return DocumentSession(
            id = id,
            title = titles[id] ?: id.value,
            sourcePath = sourcePaths[id],
            document = stack.current,
            revision = stack.revision,
            canUndo = stack.canUndo,
            canRedo = stack.canRedo,
            undoLabel = stack.undoLabel,
            redoLabel = stack.redoLabel,
            isDirty = dirtyTracker.isDirty(id),
        )
    }

    private fun buildState(): EditorSessionState {
        val documents = LinkedHashMap<DocumentId, DocumentSession>(order.size)
        order.forEach { id ->
            snapshotOf(id)?.let { documents[id] = it }
        }
        return EditorSessionState(
            documents = documents,
            documentOrder = order.toList(),
            activeDocumentId = activeDocumentId,
            selection = selection,
            favorites = favorites,
            projectDirty = dirtyTracker.hasUnsavedProjectChanges,
        )
    }

    private fun emit() {
        _state.value = buildState()
    }
}
