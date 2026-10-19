package org.zender.editor.window

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.zender.editor.document.DocumentId
import java.util.concurrent.atomic.AtomicLong

@JvmInline
value class WindowId(val value: String) {
    init {
        require(value.isNotBlank()) { "WindowId 不能为空" }
    }

    override fun toString(): String = value
}

/**
 * 窗口内容键。同一内容允许在多个窗口中打开（共享同一文档实例与命令栈）。
 */
sealed interface WindowContentKey {

    val storageKey: String

    data class Document(val documentId: DocumentId) : WindowContentKey {
        override val storageKey: String = "document:${documentId.value}"
    }

    data class StarMap(val id: String = "default") : WindowContentKey {
        init {
            require(id.isNotBlank()) { "星图 id 不能为空" }
        }

        override val storageKey: String = "starmap:$id"
    }

    data object Settings : WindowContentKey {
        override val storageKey: String = "settings"
    }

    /** 预留扩展：差异视图、插件页面等。 */
    data class Custom(override val storageKey: String) : WindowContentKey {
        init {
            require(storageKey.isNotBlank()) { "自定义内容键不能为空" }
        }
    }

    companion object {
        /**
         * 解析持久化内容键。无法识别时返回 [Custom]，保证向前兼容。
         */
        fun fromStorageKey(key: String): WindowContentKey = when {
            key == Settings.storageKey -> Settings
            key.startsWith("document:") -> {
                val id = key.removePrefix("document:")
                if (id.isBlank()) Custom(key) else Document(DocumentId(id))
            }
            key.startsWith("starmap:") -> {
                val id = key.removePrefix("starmap:")
                if (id.isBlank()) Custom(key) else StarMap(id)
            }
            else -> Custom(key)
        }
    }
}

/** 文档编辑视图模式。 */
enum class DocumentViewMode {
    FIELD,
    RAW,
    DIFF,
}

/** 窗口几何，单位为 dp。 */
data class WindowGeometry(
    val x: Float? = null,
    val y: Float? = null,
    val width: Float = 1440f,
    val height: Float = 900f,
    val maximized: Boolean = false,
) {
    init {
        require(width > 0f && width.isFinite()) { "窗口宽度必须大于 0：$width" }
        require(height > 0f && height.isFinite()) { "窗口高度必须大于 0：$height" }
    }
}

data class ScrollPosition(
    val horizontal: Float = 0f,
    val vertical: Float = 0f,
)

data class WindowContent(
    val key: WindowContentKey,
    val title: String,
    val viewMode: DocumentViewMode = DocumentViewMode.FIELD,
    /** 每个窗口、每个视图分别保存滚动位置。 */
    val scrollPosition: ScrollPosition? = null,
)

data class EditorWindow(
    val id: WindowId,
    val content: WindowContent,
    val geometry: WindowGeometry,
    val isMain: Boolean = false,
)

data class WindowManagerState(
    val windows: List<EditorWindow> = emptyList(),
    val activeWindowId: WindowId? = null,
) {
    val mainWindow: EditorWindow?
        get() = windows.firstOrNull { it.isMain }

    val activeWindow: EditorWindow?
        get() = windows.firstOrNull { it.id == activeWindowId }
}

/**
 * 应用级窗口管理器。
 *
 * 只保存窗口内容键、视图模式与几何状态，不依赖 Compose；
 * Compose 层根据 [state] 渲染 `Window` 列表。
 */
class WindowManager(
    idGenerator: (() -> String)? = null,
) {

    private companion object {
        const val MAX_ID_ATTEMPTS = 1_000
    }

    private val lock = Any()
    private val counter = AtomicLong(0L)
    private val generateId: () -> String = idGenerator ?: { "window-${counter.incrementAndGet()}" }
    private val windows = mutableListOf<EditorWindow>()
    private var activeWindowId: WindowId? = null

    private val _state = MutableStateFlow(buildState())

    val state: StateFlow<WindowManagerState> = _state.asStateFlow()

    val currentState: WindowManagerState
        get() = _state.value

    fun open(
        content: WindowContent,
        geometry: WindowGeometry = WindowGeometry(),
        isMain: Boolean = false,
    ): EditorWindow = synchronized(lock) {
        require(!isMain || windows.none { it.isMain }) { "已经存在主窗口" }
        val id = nextUniqueId()
        val window = EditorWindow(
            id = id,
            content = content,
            geometry = geometry,
            isMain = isMain,
        )
        windows += window
        activeWindowId = id
        emit()
        return window
    }

    fun openMain(
        content: WindowContent,
        geometry: WindowGeometry = WindowGeometry(),
    ): EditorWindow = open(content, geometry, isMain = true)

    fun close(id: WindowId): Boolean = synchronized(lock) {
        val removed = windows.removeAll { it.id == id }
        if (!removed) return false
        if (activeWindowId == id) {
            activeWindowId = windows.lastOrNull()?.id
        }
        emit()
        return true
    }

    fun activate(id: WindowId): Boolean = synchronized(lock) {
        if (windows.none { it.id == id }) return false
        activeWindowId = id
        emit()
        return true
    }

    fun window(id: WindowId): EditorWindow? = synchronized(lock) {
        windows.firstOrNull { it.id == id }
    }

    fun updateGeometry(id: WindowId, geometry: WindowGeometry): Boolean = updateWindow(id) {
        it.copy(geometry = geometry)
    }

    fun updateContent(id: WindowId, content: WindowContent): Boolean = updateWindow(id) {
        it.copy(content = content)
    }

    fun updateViewMode(id: WindowId, mode: DocumentViewMode): Boolean = updateWindow(id) { window ->
        window.copy(content = window.content.copy(viewMode = mode))
    }

    fun updateScroll(id: WindowId, position: ScrollPosition?): Boolean = updateWindow(id) { window ->
        window.copy(content = window.content.copy(scrollPosition = position))
    }

    fun updateTitle(id: WindowId, title: String): Boolean = updateWindow(id) { window ->
        window.copy(content = window.content.copy(title = title))
    }

    /** 当前显示同一文档的全部窗口；用于保存/关闭联动。 */
    fun windowsForDocument(documentId: DocumentId): List<EditorWindow> = synchronized(lock) {
        windows.filter { window ->
            val key = window.content.key
            key is WindowContentKey.Document && key.documentId == documentId
        }
    }

    /**
     * 关闭所有显示指定文档的窗口，返回关闭数量。主窗口也会被关闭。
     */
    fun closeAllForDocument(documentId: DocumentId): Int = synchronized(lock) {
        val ids = windowsForDocument(documentId).map { it.id }.toSet()
        if (ids.isEmpty()) return 0
        windows.removeAll { it.id in ids }
        val active = activeWindowId
        if (active != null && active in ids) {
            activeWindowId = windows.lastOrNull()?.id
        }
        emit()
        return ids.size
    }

    private fun updateWindow(id: WindowId, transform: (EditorWindow) -> EditorWindow): Boolean =
        synchronized(lock) {
            val index = windows.indexOfFirst { it.id == id }
            if (index < 0) return false
            windows[index] = transform(windows[index])
            emit()
            return true
        }

    private fun nextUniqueId(): WindowId {
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = WindowId(generateId())
            if (windows.none { it.id == candidate }) return candidate
        }
        throw IllegalStateException("窗口 id 生成器无法产生唯一 id")
    }

    private fun buildState(): WindowManagerState = WindowManagerState(
        windows = windows.toList(),
        activeWindowId = activeWindowId,
    )

    private fun emit() {
        _state.value = buildState()
    }
}
