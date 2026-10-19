package org.zender.editor.selection

import org.zender.editor.document.DocumentId
import org.zender.editor.document.JsonPath
import org.zender.editor.objects.ObjectKey

/**
 * 指向某个文档内某个字段的引用。
 */
data class FieldRef(
    val documentId: DocumentId,
    val path: JsonPath,
)

/**
 * 应用级选择状态。
 *
 * 所有窗口共享同一份状态；窗口内的滚动、展开等纯 UI 状态不放在这里。
 */
data class SelectionState(
    val currentDocument: DocumentId? = null,
    val currentObject: ObjectKey? = null,
    val currentField: FieldRef? = null,
    /**
     * 最近一次聚焦的引用字段。引用选择器确认后通过它找到提交目标；
     * 切换文档时清空，切换字段时保留。
     */
    val lastFocusedReferenceField: FieldRef? = null,
) {

    fun selectDocument(documentId: DocumentId?): SelectionState {
        if (documentId == currentDocument) return this
        return copy(
            currentDocument = documentId,
            currentObject = null,
            currentField = null,
            lastFocusedReferenceField = lastFocusedReferenceField
                ?.takeIf { it.documentId == documentId },
        )
    }

    fun selectObject(
        objectKey: ObjectKey?,
        documentId: DocumentId? = currentDocument,
    ): SelectionState {
        val sameDocument = documentId == currentDocument
        return copy(
            currentDocument = documentId,
            currentObject = objectKey,
            currentField = null,
            lastFocusedReferenceField = if (sameDocument) {
                lastFocusedReferenceField
            } else {
                lastFocusedReferenceField?.takeIf { it.documentId == documentId }
            },
        )
    }

    fun selectField(field: FieldRef?): SelectionState {
        val document = field?.documentId ?: currentDocument
        return copy(
            currentDocument = document,
            currentField = field,
            lastFocusedReferenceField = lastFocusedReferenceField
                ?.takeIf { it.documentId == document },
        )
    }

    fun focusReferenceField(field: FieldRef): SelectionState =
        copy(
            currentDocument = field.documentId,
            currentField = field,
            lastFocusedReferenceField = field,
        )

    fun clearField(): SelectionState = copy(currentField = null)

    fun clear(): SelectionState = SelectionState()
}
