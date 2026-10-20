package org.zender.app.workspace

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.document.DocumentId
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonPath
import org.zender.editor.document.RawJsonDocument
import org.zender.editor.edit.WarpType
import org.zender.editor.objects.ObjectKey
import org.zender.editor.pack.DataPack
import org.zender.editor.project.ProjectObjectKind
import org.zender.editor.project.ValidationIssue
import org.zender.editor.session.EditorSessionState
import org.zender.editor.settings.AppSettings
import org.zender.editor.window.DocumentViewMode
import org.zender.editor.window.WindowGeometry
import org.zender.editor.window.WindowId

/** 主工作区页面。文件 Tab 与这些页面互斥显示。 */
enum class WorkspaceScreen {
    FILES,
    STAR_MAP,
    LOCALIZATION,
    RESOURCES,
    EXPORT,
    SETTINGS,
}

/** 引用选择器窗口类型。 */
enum class PickerWindowKind {
    OBJECT,
    RESOURCE,
}

/** 已打开工程时再次打开项目的默认行为。 */
enum class OpenProjectBehavior {
    ASK,
    NEW_WINDOW,
    CURRENT_WINDOW,
    ;

    companion object {
        fun fromStorage(value: String?): OpenProjectBehavior =
            entries.firstOrNull { it.name == value } ?: ASK
    }
}

/** 字段视图的显示过滤。 */
data class FieldViewOptions(
    /** 显示等于模板默认值的字段；补丁文档默认关闭。 */
    val showDefaults: Boolean = true,
    val showUnknown: Boolean = true,
    val onlyFavorites: Boolean = false,
    val search: String = "",
)

/**
 * 文档在工程中的来源。视图层通过它决定 Schema 类型、可编辑性与保存路径。
 */
sealed interface DocumentTarget {
    val schemaType: String?
    val relativePath: String
    val readOnly: Boolean
    val title: String

    data class ProjectObjectTarget(
        val type: String,
        val name: String,
        val uniqueId: String?,
        val kind: ProjectObjectKind,
        override val relativePath: String,
    ) : DocumentTarget {
        override val schemaType: String get() = type
        override val readOnly: Boolean get() = false
        override val title: String get() = "$name.json"
    }

    data class GameSourceModifyTarget(
        val targetGuid: String,
        override val schemaType: String?,
        override val relativePath: String,
    ) : DocumentTarget {
        override val readOnly: Boolean get() = false
        override val title: String get() = "$targetGuid.json"
    }

    data class DataObjectModifyTarget(
        val type: String,
        val dataKey: String,
        override val relativePath: String,
    ) : DocumentTarget {
        override val schemaType: String get() = type
        override val readOnly: Boolean get() = false
        override val title: String get() = "${dataKey.replace(':', '@')}.json"
    }

    data class PackObjectTarget(
        val type: String,
        val name: String,
        override val relativePath: String,
    ) : DocumentTarget {
        override val schemaType: String get() = type
        override val readOnly: Boolean get() = true
        override val title: String get() = "$name.json"
    }

    val isPatch: Boolean
        get() = this is GameSourceModifyTarget || this is DataObjectModifyTarget
}

/** 引用字段/引用列表元素的一次选择请求。 */
data class PickerRequest(
    val kind: PickerWindowKind,
    val documentId: DocumentId,
    val fieldPath: JsonPath,
    val fieldType: String,
    val isList: Boolean,
)

/** 已分离的编辑窗口（应用级窗口列表）。 */
data class DetachedWindowUi(
    val id: WindowId,
    val documentId: DocumentId,
    val title: String,
    val geometry: WindowGeometry,
    val viewMode: DocumentViewMode,
)

/** 引用解析的展示结果。 */
data class ReferenceDisplay(
    val key: String?,
    val warpType: WarpType?,
    val status: String,
    val resolved: Boolean,
    val warning: Boolean = false,
)

/** 请求星图定位到指定节点；[nonce] 保证重复定位同一对象也能触发一次新的滚动/选中。 */
data class StarMapFocusRequest(
    val key: ObjectKey,
    val nonce: Long,
)

/** 工作区 UI 快照。所有字段都是不可变数据，Compose 只渲染该快照。 */
data class WorkspaceUiState(
    val packRoot: java.nio.file.Path? = null,
    val pack: DataPack? = null,
    val projectRoot: java.nio.file.Path? = null,
    val project: org.zender.editor.project.ModProject? = null,
    val session: EditorSessionState = EditorSessionState(),
    val viewModes: Map<DocumentId, DocumentViewMode> = emptyMap(),
    val targets: Map<DocumentId, DocumentTarget> = emptyMap(),
    val originalDocuments: Map<DocumentId, JsonDocument> = emptyMap(),
    val rawDocuments: Map<DocumentId, RawJsonDocument> = emptyMap(),
    val fieldOptions: FieldViewOptions = FieldViewOptions(),
    val screen: WorkspaceScreen = WorkspaceScreen.FILES,
    val validation: List<ValidationIssue> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val picker: PickerRequest? = null,
    val starMapFocus: StarMapFocusRequest? = null,
    val detachedWindows: List<DetachedWindowUi> = emptyList(),
    val status: String = "就绪",
    val statusIsError: Boolean = false,
    val busy: Boolean = false,
    val lastSavedAt: Long? = null,
) {
    val activeDocument: org.zender.editor.session.DocumentSession?
        get() = session.activeDocumentId?.let { session.documents[it] }

    val activeTarget: DocumentTarget?
        get() = session.activeDocumentId?.let { targets[it] }

    val activeViewMode: DocumentViewMode
        get() = session.activeDocumentId?.let { viewModes[it] } ?: DocumentViewMode.FIELD

    val projectName: String
        get() = project?.metadata?.displayName() ?: projectRoot?.fileName?.toString().orEmpty()

    val packName: String
        get() = pack?.let { "${it.info.name} ${it.info.version}".trim() }.orEmpty()

    val hasUnsavedChanges: Boolean get() = session.hasUnsavedChanges

    val errorCount: Int get() = validation.count { it.isError }

    val warningCount: Int get() = validation.count { it.isWarning }
}
