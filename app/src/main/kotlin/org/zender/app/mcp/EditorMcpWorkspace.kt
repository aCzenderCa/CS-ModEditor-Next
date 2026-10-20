package org.zender.app.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.zender.app.workspace.DocumentTarget
import org.zender.app.workspace.WorkspaceController
import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.edit.WarpType
import org.zender.editor.localization.LocalizationEntry
import org.zender.editor.pack.DataPack
import org.zender.editor.project.ModProject
import org.zender.editor.project.NewObjectRequest
import org.zender.editor.project.ProjectObject
import org.zender.editor.project.ResourceImportResult
import org.zender.editor.resource.ResourceKind
import org.zender.mcp.EditOutcome
import org.zender.mcp.McpToolException
import org.zender.mcp.McpWorkspace
import org.zender.mcp.ObjectDocument
import org.zender.mcp.SaveOutcome
import java.nio.file.Path
import javax.swing.SwingUtilities

/**
 * 运行中编辑器的 MCP 工作区实现。
 *
 * 所有读写都通过 [WorkspaceController] 进入编辑器自己的 [org.zender.editor.session.EditorSession]：
 * - Claude 的修改会立即反映到已打开的文档、撤销栈与字段视图；
 * - 保存沿用编辑器的脏状态与原子写入；
 * - HTTP 线程通过 Swing EDT 调度，避免与 Compose 的状态更新竞争。
 */
class EditorMcpWorkspace(
    private val controllerProvider: () -> WorkspaceController,
) : McpWorkspace {

    private val controller: WorkspaceController
        get() = controllerProvider()

    private val state
        get() = controller.state.value

    override val pack: DataPack? get() = state.pack

    override val project: ModProject? get() = state.project

    override val dirty: Boolean get() = state.hasUnsavedChanges

    override val diagnostics: List<Diagnostic> get() = state.diagnostics

    override fun openPack(root: Path): DataPack {
        suspendOnEdt { controller.loadPack(root) }
        val current = state
        if (current.statusIsError) {
            throw McpToolException(current.status.ifBlank { "数据包加载失败" })
        }
        return current.pack ?: throw McpToolException("数据包加载失败：${current.status}")
    }

    override fun openProject(root: Path, packPath: Path?): ModProject {
        suspendOnEdt {
            if (packPath != null) controller.loadPack(packPath)
            controller.loadProject(root)
        }
        val current = state
        val loaded = current.project
        val normalized = root.toAbsolutePath().normalize()
        val loadedRoot = current.projectRoot?.toAbsolutePath()?.normalize()
        if (current.statusIsError || loaded == null || loadedRoot != normalized) {
            throw McpToolException(current.status.ifBlank { "工程读取失败：$root" })
        }
        if (packPath != null) {
            val normalizedPack = packPath.toAbsolutePath().normalize()
            val loadedPack = current.packRoot?.toAbsolutePath()?.normalize()
            if (loadedPack != normalizedPack) {
                throw McpToolException("数据包加载失败：$packPath")
            }
        }
        return loaded
    }

    override fun saveProject(targetRoot: Path?): SaveOutcome {
        val success = onEdt {
            if (targetRoot == null) controller.saveProject() else controller.exportTo(targetRoot)
        }
        val current = state
        if (!success) {
            throw McpToolException(current.status.ifBlank { "保存失败" })
        }
        return SaveOutcome(diagnostics = current.diagnostics)
    }

    override fun objectDocument(type: String, name: String, source: String): ObjectDocument? {
        val current = state
        if (source != "pack") {
            val objectEntry = current.project?.objectOf(type, name)
            if (objectEntry != null) {
                val documentId = current.targets.entries.firstOrNull { (_, target) ->
                    target is DocumentTarget.ProjectObjectTarget &&
                        target.type == type &&
                        target.name == name
                }?.key
                val document = documentId
                    ?.let { current.session.documents[it]?.document }
                    ?: objectEntry.document
                return ObjectDocument(
                    source = "project",
                    document = document,
                    relativePath = objectEntry.relativePath,
                    uniqueId = objectEntry.uniqueId,
                )
            }
            if (source == "project") return null
        }

        val currentPack = current.pack
            ?: throw McpToolException("尚未打开数据包，且工程中不存在对象：$type/$name")
        val entry = currentPack.objectEntry(type, name)
            ?: throw McpToolException("数据包中不存在对象：$type/$name")
        if (!entry.hasDataFile) {
            throw McpToolException("对象 $type/$name 只存在于索引中，没有可读取的 JSON 文件")
        }
        return ObjectDocument(
            source = "pack",
            document = currentPack.readObject(entry),
            relativePath = entry.relativePath.orEmpty(),
            uniqueId = entry.uniqueId,
            group = entry.group,
            displayName = entry.displayName,
        )
    }

    override fun setField(
        type: String,
        name: String,
        path: JsonPath,
        value: JsonNode,
    ): EditOutcome = onEdt {
        val documentId = controller.ensureProjectObjectDocument(type, name)
            ?: throw McpToolException(status())
        if (!controller.setFieldNode(documentId, path, value)) {
            throw McpToolException(status())
        }
        EditOutcome(changed = true)
    }

    override fun setReference(
        type: String,
        name: String,
        field: JsonPath,
        referenceKey: String,
        warpType: WarpType,
        index: Int?,
    ): EditOutcome = onEdt {
        val changed = controller.setReferenceByObject(
            type = type,
            name = name,
            fieldPath = field,
            referenceKey = referenceKey,
            warpType = warpType,
            index = index,
        )
        if (!changed) throw McpToolException(status())
        EditOutcome(changed = true)
    }

    override fun clearReference(type: String, name: String, field: JsonPath): EditOutcome = onEdt {
        if (!controller.clearReferenceByObject(type, name, field)) {
            throw McpToolException(status())
        }
        EditOutcome(changed = true)
    }

    override fun removeReference(
        type: String,
        name: String,
        field: JsonPath,
        index: Int,
    ): EditOutcome = onEdt {
        if (!controller.removeReferenceByObject(type, name, field, index)) {
            throw McpToolException(status())
        }
        EditOutcome(changed = true)
    }

    override fun createObject(request: NewObjectRequest): ProjectObject = onEdt {
        controller.createProjectObject(
            type = request.type,
            name = request.name,
            kind = request.kind,
            group = request.group,
            sourceDocument = request.sourceDocument,
            uniqueId = request.uniqueId,
        ) ?: throw McpToolException(status())
    }

    override fun updateLocalization(
        key: String,
        english: String?,
        chinese: String?,
    ): LocalizationEntry = onEdt {
        val existing = controller.localizedEntry(key)
        val entry = LocalizationEntry(
            key = key,
            english = english ?: existing?.english.orEmpty(),
            chinese = chinese ?: existing?.chinese.orEmpty(),
        )
        if (!controller.updateLocalizationEntry(key, entry.english, entry.chinese)) {
            throw McpToolException(status())
        }
        entry
    }

    override fun importResource(
        source: Path,
        key: String?,
        kind: ResourceKind?,
        overwrite: Boolean,
    ): ResourceImportResult = suspendOnEdt {
        controller.importResource(source, key, kind, overwrite)
            ?: throw McpToolException(status())
    }

    private fun status(): String =
        controller.state.value.status.ifBlank { "操作失败" }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var outcome: Result<T>? = null
        SwingUtilities.invokeAndWait { outcome = runCatching(block) }
        return outcome!!.getOrThrow()
    }

    private fun <T> suspendOnEdt(block: suspend () -> T): T =
        runBlocking(Dispatchers.Swing) { block() }
}
