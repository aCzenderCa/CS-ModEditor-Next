package org.zender.editor.project

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.framework.FrameworkId
import org.zender.editor.objects.ObjectKey
import org.zender.editor.pack.DataPack

/** 可新建的补丁类型。 */
enum class PatchKind {
    GAME_SOURCE_MODIFY,
    DATA_OBJECT_MODIFY,
}

data class NewPatchRequest(
    val kind: PatchKind,
    val type: String,
    val name: String,
    /** GameSourceModify 目录下的可选子文件夹（可多级）。 */
    val category: String? = null,
)

data class NewPatchResult(
    val project: ModProject? = null,
    val gameSourceModify: GameSourceModify? = null,
    val dataObjectModify: DataObjectModify? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    val isSuccess: Boolean get() = project != null && diagnostics.none { it.isError }
}

/**
 * 新建补丁：
 *
 * - `GameSourceModify`：目标必须是带 UniqueID 的对象（工程对象或数据包对象）；
 * - `DataObjectModify`：仅 MC，目标必须是无 GUID 的 ScriptableObject；
 *   工程对象使用 `Namespace:Name` 数据键，数据包本体对象使用裸名称。
 */
class ProjectPatchFactory(
    private val basePack: DataPack? = null,
) {

    fun create(project: ModProject, request: NewPatchRequest): NewPatchResult {
        val pack = basePack ?: project.basePack
        val type = request.type.trim()
        val name = request.name.trim()
        if (type.isBlank() || name.isBlank()) return failure("补丁目标类型/名称不能为空")

        val projectObject = project.objectOf(type, name)
        val packEntry = pack?.objectEntry(type, name)
        if (projectObject == null && packEntry == null) {
            return failure("补丁目标不存在：$type/$name")
        }
        val uniqueId = projectObject?.uniqueId ?: packEntry?.uniqueId

        return when (request.kind) {
            PatchKind.GAME_SOURCE_MODIFY -> createGameSourceModify(
                project = project,
                type = type,
                name = name,
                uniqueId = uniqueId,
                category = request.category,
            )

            PatchKind.DATA_OBJECT_MODIFY -> createDataObjectModify(
                project = project,
                type = type,
                name = name,
                uniqueId = uniqueId,
                isProjectObject = projectObject != null,
            )
        }
    }

    private fun createGameSourceModify(
        project: ModProject,
        type: String,
        name: String,
        uniqueId: String?,
        category: String?,
    ): NewPatchResult {
        if (uniqueId.isNullOrBlank()) {
            return failure("只有带 UniqueID 的对象可以创建 GameSourceModify：$type/$name")
        }
        if (project.workingTree.gameSourceModify(uniqueId) != null) {
            return failure("GameSourceModify 已存在：$uniqueId")
        }
        val normalizedCategory = try {
            ProjectFolderPath.normalize(category)
        } catch (e: IllegalArgumentException) {
            return failure("GameSourceModify 子文件夹不合法：${e.message}")
        }
        val relativePath = project.format.gameSourceModifyPath(uniqueId, normalizedCategory)
        val patch = GameSourceModify(
            targetGuid = uniqueId,
            document = JsonDocument(JsonNode.ObjectNode.EMPTY),
            relativePath = relativePath,
            targetKey = ObjectKey.of(type, name, uniqueId),
        )
        return NewPatchResult(
            project = project.withWorkingTree(
                project.workingTree.withGameSourceModify(patch),
            ),
            gameSourceModify = patch,
        )
    }

    private fun createDataObjectModify(
        project: ModProject,
        type: String,
        name: String,
        uniqueId: String?,
        isProjectObject: Boolean,
    ): NewPatchResult {
        if (project.framework != FrameworkId.MC ||
            project.format.dataObjectModifyDirectory(type) == null
        ) {
            return failure("ML 不支持 DataObjectModify；请使用 GameSourceModify")
        }
        if (!uniqueId.isNullOrBlank()) {
            return failure("带 UniqueID 的对象请使用 GameSourceModify：$type/$name")
        }
        val dataKey = if (isProjectObject) "${project.namespace}:$name" else name
        if (project.workingTree.dataObjectModify(type, dataKey) != null) {
            return failure("DataObjectModify 已存在：$type/$dataKey")
        }
        val relativePath = project.format.dataObjectModifyPath(type, dataKey)
            ?: return failure("当前框架不支持 DataObjectModify：${project.framework}")
        val patch = DataObjectModify(
            type = type,
            dataKey = dataKey,
            document = JsonDocument(JsonNode.ObjectNode.EMPTY),
            relativePath = relativePath,
            targetKey = ObjectKey.of(type, name),
        )
        return NewPatchResult(
            project = project.withWorkingTree(
                project.workingTree.withDataObjectModify(patch),
            ),
            dataObjectModify = patch,
        )
    }

    private fun failure(message: String): NewPatchResult = NewPatchResult(
        diagnostics = listOf(Diagnostic(DiagnosticSeverity.ERROR, message)),
    )
}
