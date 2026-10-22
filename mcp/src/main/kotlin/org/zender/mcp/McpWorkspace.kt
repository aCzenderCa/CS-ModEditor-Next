package org.zender.mcp

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.document.JsonPath
import org.zender.editor.edit.WarpType
import org.zender.editor.localization.LocalizationEntry
import org.zender.editor.pack.DataPack
import org.zender.editor.project.ModProject
import org.zender.editor.project.NewObjectRequest
import org.zender.editor.project.NewPatchRequest
import org.zender.editor.project.NewProjectRequest
import org.zender.editor.project.PatchKind
import org.zender.editor.project.ProjectObject
import org.zender.editor.project.ResourceImportResult
import org.zender.editor.resource.ResourceKind
import java.nio.file.Path

/**
 * 一个对象的最新可读文档。`source` 为 `project` 或 `pack`。
 */
data class ObjectDocument(
    val source: String,
    val document: JsonDocument,
    val relativePath: String,
    val uniqueId: String? = null,
    val group: String? = null,
    val displayName: String? = null,
)

data class SaveOutcome(
    val writtenFiles: List<String> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
)

data class EditOutcome(
    val changed: Boolean,
    val message: String? = null,
)

/** 新建补丁的结果。 */
data class PatchOutcome(
    val kind: PatchKind,
    val relativePath: String,
    val target: String,
)

/**
 * MCP 工具操作的工作区抽象。
 *
 * 两个实现：
 * - [FileMcpWorkspace]：无界面进程内直接读文件（测试与调试）；
 * - `EditorMcpWorkspace`（app 模块）：代理运行中的编辑器，保证 Claude 的修改与编辑器会话一致。
 */
interface McpWorkspace {

    val pack: DataPack?

    val project: ModProject?

    val dirty: Boolean

    val diagnostics: List<Diagnostic>

    fun openPack(root: Path): DataPack

    /**
     * 打开 Mod 工程。[packPath] 为空时由实现决定是否复用已加载的数据包
     * （编辑器实现会沿用编辑器设置中的工程↔数据包关联）。
     */
    fun openProject(root: Path, packPath: Path?): ModProject

    /**
     * 在空目录创建 ML/MC 工程并切换为当前工程。
     * 目录必须为空或不存在；已存在的工程请用 [openProject]。
     */
    fun createProject(request: NewProjectRequest): ModProject

    fun saveProject(targetRoot: Path?): SaveOutcome

    /** 读取对象最新文档；[source] 为 `auto` / `project` / `pack`。 */
    fun objectDocument(type: String, name: String, source: String): ObjectDocument?

    fun setField(type: String, name: String, path: JsonPath, value: JsonNode): EditOutcome

    fun setReference(
        type: String,
        name: String,
        field: JsonPath,
        referenceKey: String,
        warpType: WarpType,
        index: Int?,
    ): EditOutcome

    fun clearReference(type: String, name: String, field: JsonPath): EditOutcome

    fun removeReference(type: String, name: String, field: JsonPath, index: Int): EditOutcome

    fun createObject(request: NewObjectRequest): ProjectObject

    /** 为数据包/工程对象新建 GameSourceModify 或 DataObjectModify 补丁。 */
    fun createPatch(request: NewPatchRequest): PatchOutcome

    fun updateLocalization(key: String, english: String?, chinese: String?): LocalizationEntry

    fun importResource(
        source: Path,
        key: String?,
        kind: ResourceKind?,
        overwrite: Boolean,
    ): ResourceImportResult

    fun requireProject(): ModProject =
        project ?: throw McpToolException("尚未打开工程，请先调用 open_project")
}
