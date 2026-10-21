package org.zender.app.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.document.DocumentId
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonFormat
import org.zender.editor.document.JsonPath
import org.zender.editor.document.JsonPathSegment
import org.zender.editor.document.RawJsonDocument
import org.zender.editor.document.readPath
import org.zender.editor.edit.AddInlineElementCommand
import org.zender.editor.edit.AddReferenceToListCommand
import org.zender.editor.edit.ClearReferenceCommand
import org.zender.editor.edit.CommandStack
import org.zender.editor.edit.EditCommand
import org.zender.editor.edit.MoveElementCommand
import org.zender.editor.edit.RemoveElementCommand
import org.zender.editor.edit.ReplaceDocumentCommand
import org.zender.editor.edit.SetNestedFieldCommand
import org.zender.editor.edit.SetReferenceCommand
import org.zender.editor.edit.ValueParseException
import org.zender.editor.edit.ValueCodec
import org.zender.editor.edit.WarpFields
import org.zender.editor.edit.WarpType
import org.zender.editor.field.FieldEditorInfo
import org.zender.editor.field.FieldSchemaLocator
import org.zender.editor.field.FavoriteFieldStore
import org.zender.editor.framework.FrameworkId
import org.zender.editor.framework.ModMetadata
import org.zender.editor.framework.ModNamespace
import org.zender.editor.graph.GraphNode
import org.zender.editor.graph.GraphNodeOrigin
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.io.FileStore
import org.zender.editor.io.resolveWithin
import org.zender.editor.localization.LocalizationEntry
import org.zender.editor.log.Log
import org.zender.editor.model.EntryOrigin
import org.zender.editor.objects.ObjectKey
import org.zender.editor.objects.ReferenceKind
import org.zender.editor.objects.ReferenceResolution
import org.zender.editor.pack.DataPack
import org.zender.editor.pack.DataPackLoader
import org.zender.editor.pack.PackHistoryStore
import org.zender.editor.pack.PackInfo
import org.zender.editor.pack.PackInfoReader
import org.zender.editor.pack.PackLoadOptions
import org.zender.editor.schema.SchemaLoadOptions
import org.zender.editor.picker.PickerItem
import org.zender.editor.picker.PickerQuery
import org.zender.editor.project.DataObjectModifyId
import org.zender.editor.project.GameSourceModify
import org.zender.editor.project.ModProject
import org.zender.editor.project.ModProjectCreator
import org.zender.editor.project.ModProjectLoader
import org.zender.editor.project.ModProjectWriter
import org.zender.editor.project.NewProjectRequest
import org.zender.editor.project.ProjectFolder
import org.zender.editor.project.ProjectFolderPath
import org.zender.editor.project.ModValidator
import org.zender.editor.project.NewObjectRequest
import org.zender.editor.project.ProjectObject
import org.zender.editor.project.ProjectObjectFactory
import org.zender.editor.project.ProjectObjectKind
import org.zender.editor.project.ProjectReferenceResolver
import org.zender.editor.project.ReferenceCheck
import org.zender.editor.project.ResourceImporter
import org.zender.editor.project.ResourceImportResult
import org.zender.editor.project.ResourceId
import org.zender.editor.project.SchemaValidator
import org.zender.editor.project.ValidationIssue
import org.zender.editor.resource.ResourceEntry
import org.zender.editor.resource.ResourceKind
import org.zender.editor.selection.FieldRef
import org.zender.editor.session.EditorSession
import org.zender.editor.settings.AppSettings
import org.zender.editor.settings.DetachedWindowSettings
import org.zender.editor.settings.ExtraPackConfig
import org.zender.editor.settings.FavoriteField
import org.zender.editor.settings.MainPackConfig
import org.zender.editor.settings.ProjectPackTarget
import org.zender.editor.settings.SettingsStore
import org.zender.editor.window.DocumentViewMode
import org.zender.editor.window.WindowContent
import org.zender.editor.window.WindowContentKey
import org.zender.editor.window.WindowGeometry
import org.zender.editor.window.WindowId
import org.zender.editor.window.WindowManager
import java.nio.file.Path

/**
 * 应用级工作区控制器。
 *
 * 持有数据包、Mod 工程与 [EditorSession]；视图层只读取 [state] 并调用方法。
 * 所有文档共享同一份会话，主窗口与已分离窗口不会出现状态分叉。
 */
class WorkspaceController(
    private val settingsStore: SettingsStore,
    private val fileStore: FileStore = AtomicFileStore(),
    private val session: EditorSession = EditorSession(),
    private val projectLoader: ModProjectLoader = ModProjectLoader(fileStore),
    private val projectWriter: ModProjectWriter = ModProjectWriter(fileStore),
    private val resourceImporter: ResourceImporter = ResourceImporter(fileStore),
    private val favoriteStore: FavoriteFieldStore = FavoriteFieldStore(settingsStore),
    private val packHistoryStore: PackHistoryStore = PackHistoryStore(settingsStore),
    private val windowManager: WindowManager = WindowManager(),
) {
    private val logger = Log.getLogger("workspace")

    private var pack: DataPack? = null
    private var packRoot: Path? = null
    private var project: ModProject? = null
    private var projectRoot: Path? = null
    private var activePackTarget: ProjectPackTarget? = null
    private var diagnostics: List<Diagnostic> = emptyList()
    private var validation: List<ValidationIssue> = emptyList()
    private var settings: AppSettings = AppSettings()
    private var fieldOptions = FieldViewOptions()
    private var screen = WorkspaceScreen.FILES
    private var picker: PickerRequest? = null
    private var starMapFocus: StarMapFocusRequest? = null
    private var starMapFocusNonce = 0L
    private var status = "就绪"
    private var statusIsError = false
    private var busy = false
    private var lastSavedAt: Long? = null

    private val targets = LinkedHashMap<DocumentId, DocumentTarget>()
    private val originalDocuments = LinkedHashMap<DocumentId, JsonDocument>()
    private val rawDocuments = LinkedHashMap<DocumentId, RawJsonDocument>()
    private val viewModes = LinkedHashMap<DocumentId, DocumentViewMode>()
    private val windowDocuments = LinkedHashMap<WindowId, DocumentId>()
    private val recentPickedItems = mutableListOf<PickerItem>()

    private val packLoader = DataPackLoader(fileStore = fileStore, historyStore = packHistoryStore)

    private val _state = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    val settingsPath: Path get() = settingsStore.path

    init {
        settings = runCatching { settingsStore.load() }.getOrElse {
            logger.warn("读取设置失败，使用默认设置", it)
            AppSettings()
        }
        session.replaceFavorites(favoriteStore.favorites().toSet())
        refresh()
    }

    // ---------------------------------------------------------------- 加载

    suspend fun loadPack(root: Path): Boolean = loadPackInternal(root, emptyList(), null)

    /**
     * 加载「主包 + 额外包」组合，并记录为 [ProjectPackTarget]。
     */
    suspend fun loadPackCombined(
        mainRoot: Path,
        extraRoots: List<Path>,
        target: ProjectPackTarget,
    ): Boolean = loadPackInternal(mainRoot, extraRoots, target)

    private suspend fun loadPackInternal(
        root: Path,
        extraRoots: List<Path>,
        explicitTarget: ProjectPackTarget?,
    ): Boolean {
        busy = true
        status = "正在加载数据包：${root.fileName}" +
            if (extraRoots.isEmpty()) "" else "（+${extraRoots.size} 个额外包）"
        statusIsError = false
        refresh()

        val result = withContext(Dispatchers.IO) {
            packLoader.loadCombined(
                root,
                extraRoots,
                PackLoadOptions(
                    // 允许在字段视图修改 LocalizedString 的 ParentObjectID / LocalizationKey。
                    schemaOptions = SchemaLoadOptions(readOnlyFields = emptyMap()),
                ),
            )
        }
        busy = false
        if (!result.isSuccess) {
            diagnostics = result.diagnostics
            fail("数据包加载失败：${result.errors.firstOrNull()?.message ?: "未知错误"}")
            return false
        }

        val loaded = result.packOrThrow()
        pack = loaded
        packRoot = loaded.root
        diagnostics = result.diagnostics
        statusIsError = false
        activePackTarget = explicitTarget ?: ProjectPackTarget(
            mainPackKind = loaded.info.normalizedName,
            mainPackPath = loaded.root.toString(),
            framework = project?.framework?.name.orEmpty(),
            extraPackPaths = loaded.layers.drop(1).map { it.root.toString() },
        )
        rememberMainPackPath(loaded.info, loaded.root)

        val extraInfo = loaded.layers.drop(1)
            .joinToString("、") { it.info.name }
            .takeIf { it.isNotBlank() }
            ?.let { " + $it" }
            .orEmpty()
        val currentProject = project
        if (currentProject != null) {
            if (session.currentState.hasUnsavedChanges) {
                project = currentProject.withBasePack(loaded)
                status = "数据包已加载：${loaded.info.name}$extraInfo；当前有未保存修改，重新打开工程后新数据包才完全生效"
            } else {
                val reload = withContext(Dispatchers.IO) {
                    projectLoader.read(currentProject.root ?: return@withContext null, loaded)
                }
                if (reload != null && reload.isSuccess) {
                    resetWorkspaceDocuments()
                    applyProject(reload.projectOrThrow())
                    status = "数据包已加载并重新读取工程：${loaded.info.name}$extraInfo"
                } else {
                    project = currentProject.withBasePack(loaded)
                    diagnostics = diagnostics + (reload?.diagnostics.orEmpty())
                    status = "数据包已加载；工程重新读取失败，保留当前内容"
                }
            }
        } else {
            status = "数据包已加载：${loaded.info.name} ${loaded.info.version}$extraInfo".trim()
        }
        associateProjectPack()
        refresh()
        return true
    }

    suspend fun loadProject(root: Path) {
        busy = true
        status = "正在读取工程：${root.fileName}"
        statusIsError = false
        refresh()

        val normalizedRoot = fileStore.normalize(root)
        var effectivePack = pack
        val savedTarget = settings.projectPackTargets[normalizedRoot.toString()]
        val associated = settings.projectPackDirectories[normalizedRoot.toString()]
        if (savedTarget != null) {
            val mainRoot = runCatching { Path.of(savedTarget.mainPackPath) }.getOrNull()
            if (mainRoot != null) {
                val extraRoots = savedTarget.extraPackPaths.mapNotNull {
                    runCatching { Path.of(it) }.getOrNull()
                }
                val linkedResult = withContext(Dispatchers.IO) {
                    packLoader.loadCombined(
                        mainRoot,
                        extraRoots,
                        PackLoadOptions(
                            schemaOptions = SchemaLoadOptions(readOnlyFields = emptyMap()),
                        ),
                    )
                }
                if (linkedResult.isSuccess) {
                    effectivePack = linkedResult.packOrThrow()
                    pack = effectivePack
                    packRoot = effectivePack.root
                    activePackTarget = savedTarget
                    diagnostics = linkedResult.diagnostics
                } else {
                    diagnostics = linkedResult.diagnostics
                }
            }
        } else if (associated != null) {
            val linkedRoot = Path.of(associated)
            val currentPackRoot = packRoot
            val shouldLoad = currentPackRoot == null ||
                fileStore.normalize(currentPackRoot) != fileStore.normalize(linkedRoot)
            if (shouldLoad && fileStore.isDirectory(linkedRoot)) {
                val linkedResult = withContext(Dispatchers.IO) {
                    packLoader.load(
                        linkedRoot,
                        PackLoadOptions(
                            schemaOptions = SchemaLoadOptions(readOnlyFields = emptyMap()),
                        ),
                    )
                }
                if (linkedResult.isSuccess) {
                    effectivePack = linkedResult.packOrThrow()
                    pack = effectivePack
                    packRoot = effectivePack.root
                    activePackTarget = ProjectPackTarget(
                        mainPackKind = effectivePack.info.normalizedName,
                        mainPackPath = effectivePack.root.toString(),
                        framework = project?.framework?.name.orEmpty(),
                        extraPackPaths = effectivePack.layers.drop(1).map { it.root.toString() },
                    )
                    rememberMainPackPath(effectivePack.info, effectivePack.root)
                    diagnostics = linkedResult.diagnostics
                } else {
                    diagnostics = linkedResult.diagnostics
                }
            }
        }

        val result = withContext(Dispatchers.IO) { projectLoader.read(normalizedRoot, effectivePack) }
        busy = false
        if (!result.isSuccess) {
            diagnostics = result.diagnostics
            fail("工程读取失败：${result.diagnostics.firstOrNull()?.message ?: "未知错误"}")
            return
        }

        resetWorkspaceDocuments()
        applyProject(result.projectOrThrow())
        diagnostics = result.diagnostics
        validation = emptyList()
        associateProjectPack()
        runCatching {
            settings = settingsStore.update { it.withRecentProject(root) }
        }.onFailure { logger.warn("记录最近工程失败", it) }
        statusIsError = false
        status = "已打开工程：${project?.metadata?.displayName() ?: root.fileName}"
        refresh()
        restoreDetachedWindows()
        validateProject()
    }

    /**
     * 在空目录中新建 Mod 工程（只写元数据），随后由调用方打开该路径。
     */
    suspend fun createProject(
        root: Path,
        framework: FrameworkId,
        name: String,
        namespace: String? = null,
        author: String = "",
        version: String = "",
    ): Boolean {
        busy = true
        status = "正在创建工程：${root.fileName}"
        statusIsError = false
        refresh()
        val result = withContext(Dispatchers.IO) {
            ModProjectCreator(fileStore).create(
                NewProjectRequest(
                    root = root,
                    framework = framework,
                    name = name,
                    namespace = namespace,
                    author = author,
                    version = version,
                ),
            )
        }
        busy = false
        if (!result.isSuccess) {
            diagnostics = result.diagnostics
            fail(result.diagnostics.firstOrNull()?.message ?: "创建工程失败")
            return false
        }
        diagnostics = result.diagnostics
        status = "已创建工程：${root.fileName}，正在打开…"
        statusIsError = false
        refresh()
        return true
    }

    /** 记住「工程目录 → 数据包目录」，下次打开工程时自动加载。 */
    /** 直接选择 CSTI / CSFF 数据包时，顺手补全对应主包目录配置（不覆盖已有配置）。 */
    private fun rememberMainPackPath(info: PackInfo, root: Path) {
        val kind = info.normalizedName
        if (kind !in MAIN_PACK_KINDS) return
        if (settings.mainPack(kind)?.path != null) return
        runCatching {
            settings = settingsStore.update { it.withMainPackPath(kind, root) }
        }.onFailure { logger.warn("记录主包路径失败", it) }
    }

    private fun associateProjectPack() {
        val currentProjectRoot = projectRoot ?: return
        val currentPackRoot = packRoot ?: return
        val target = activePackTarget ?: pack?.let { loaded ->
            ProjectPackTarget(
                mainPackKind = loaded.info.normalizedName,
                mainPackPath = loaded.root.toString(),
                framework = project?.framework?.name.orEmpty(),
                extraPackPaths = loaded.layers.drop(1).map { it.root.toString() },
            )
        } ?: return
        runCatching {
            settings = settingsStore.update {
                it.withProjectPackTarget(currentProjectRoot, target)
                    .withProjectPackDirectory(currentProjectRoot, Path.of(target.mainPackPath))
            }
        }.onFailure { logger.warn("保存工程数据包关联失败", it) }
    }

    private fun applyProject(loaded: ModProject) {
        project = loaded
        projectRoot = loaded.root
        diagnostics = loaded.diagnostics
        loaded.root?.let { root ->
            runCatching {
                settings = settingsStore.update { it.withRecentProject(root) }
            }.onFailure { logger.warn("记录最近工程失败", it) }
        }
    }

    private fun resetWorkspaceDocuments() {
        session.currentState.documentOrder.forEach { session.closeDocument(it, force = true) }
        windowManager.currentState.windows.forEach { windowManager.close(it.id) }
        windowDocuments.clear()
        targets.clear()
        originalDocuments.clear()
        rawDocuments.clear()
        viewModes.clear()
        picker = null
    }

    // ---------------------------------------------------------------- 打开文档

    fun inferObjectKind(type: String): ProjectObjectKind =
        ProjectObjectFactory.inferKind(pack, type)

    /**
     * 新建工程对象并打开；UniqueID 对象自动生成 GUID，名称冲突直接失败。
     */
    fun createProjectObject(
        type: String,
        name: String?,
        kind: ProjectObjectKind? = null,
        group: String? = null,
        sourceDocument: org.zender.editor.document.JsonDocument? = null,
        uniqueId: String? = null,
    ): ProjectObject? {
        val current = project ?: run {
            fail("尚未打开工程")
            return null
        }
        val result = ProjectObjectFactory(pack).create(
            current,
            NewObjectRequest(
                type = type,
                name = name,
                kind = kind,
                group = group,
                uniqueId = uniqueId,
                sourceDocument = sourceDocument,
            ),
        )
        diagnostics = result.diagnostics
        val created = result.objectEntry
        if (created == null) {
            fail(result.diagnostics.firstOrNull { it.isError }?.message ?: "新建对象失败")
            return null
        }
        project = result.project
        session.markProjectDirty()
        status = "已新建对象：${created.type}/${created.name}"
        statusIsError = false
        refresh()
        openProjectObject(created)
        return created
    }

    /**
     * 更新工程元数据（ModInfo.json / ModMeta.json）。
     *
     * [ModMetadata.raw] 会保留原文件中的未知字段与字段顺序；
     * 修改只进入内存快照，写盘仍由保存工程完成。
     */
    fun updateMetadata(metadata: ModMetadata): Boolean {
        val current = project ?: return fail("尚未打开工程")
        if (metadata.framework != current.framework) {
            return fail("元数据框架与工程框架不一致")
        }
        if (metadata.namespace.isBlank()) {
            return fail("Mod 名称/Namespace 不能为空")
        }
        if (current.framework == FrameworkId.MC && !ModNamespace.isValid(metadata.namespace)) {
            return fail("MC Namespace 不能包含 : @ |")
        }
        project = current.withMetadata(metadata)
        session.markProjectDirty()
        status = "已更新工程元数据：${current.format.metadataFileName}"
        statusIsError = false
        refresh()
        return true
    }

    /**
     * 在类型目录下创建（多级）子文件夹。目录只进入工作树，保存工程时落盘。
     */
    fun createProjectFolder(type: String, path: String, kind: ProjectObjectKind): Boolean {
        val current = project ?: return fail("尚未打开工程")
        val normalized = try {
            ProjectFolderPath.normalize(path)
        } catch (e: IllegalArgumentException) {
            return fail(e.message ?: "文件夹路径不合法")
        } ?: return fail("文件夹名不能为空")
        val folders = ProjectFolderPath.ancestors(normalized).map { ancestor ->
            ProjectFolder(type = type, path = ancestor, kind = kind)
        }
        project = current.withWorkingTree(current.workingTree.withFolders(folders))
        session.markProjectDirty()
        status = "已创建文件夹：$type/$normalized"
        statusIsError = false
        refresh()
        return true
    }

    /**
     * 从工程删除对象：关闭对应文档，并从工作树移除；保存工程时删除磁盘文件。
     */
    fun deleteProjectObject(type: String, name: String): Boolean {
        val current = project ?: return fail("尚未打开工程")
        val entry = current.workingTree.objectOf(type, name)
            ?: return fail("工程中不存在对象：$type/$name")
        val documentId = objectDocumentId(type, name)

        val deletedWindows = windowDocuments.entries.filter { it.value == documentId }
            .map { it.key }
        deletedWindows.forEach { windowId ->
            windowManager.close(windowId)
            windowDocuments.remove(windowId)
        }
        if (deletedWindows.isNotEmpty()) persistDetachedWindows()
        if (documentId in targets) {
            closeDocument(documentId, force = true)
        }

        project = current.withWorkingTree(current.workingTree.withoutObject(entry.key))
        session.markProjectDirty()
        validation = emptyList()
        diagnostics = emptyList()
        status = "已删除对象：$type/$name（保存工程后删除磁盘文件）"
        statusIsError = false
        refresh()
        return true
    }

    /** 生成一个当前工程中不冲突的对象名。 */
    fun suggestObjectName(type: String, base: String): String {
        val current = project ?: return base
        val seed = base.ifBlank { "New$type" }
        if (current.workingTree.objectOf(type, seed) == null) return seed
        for (index in 2..1_000) {
            val candidate = "$seed$index"
            if (current.workingTree.objectOf(type, candidate) == null) return candidate
        }
        return seed
    }

    /** 读取数据包对象原文并解析，供“从数据包对象复制”使用。 */
    fun readPackObjectDocument(type: String, name: String): JsonDocument? {
        val currentPack = pack ?: return null
        val entry = currentPack.objectEntry(type, name) ?: return null
        if (!entry.hasDataFile) return null
        return runCatching { currentPack.readObject(entry) }
            .onFailure { logger.warn("读取数据包对象失败：$type/$name", it) }
            .getOrNull()
    }

    fun openObject(type: String, name: String): Boolean {
        val entry = project?.objectOf(type, name)
            ?: return fail("工程中不存在对象：$type/$name")
        return openProjectObject(entry)
    }

    fun openObjectKey(key: ObjectKey): Boolean =
        openObject(key.type, key.name)

    fun openProjectObject(entry: ProjectObject): Boolean {
        val id = objectDocumentId(entry.type, entry.name)
        val target = DocumentTarget.ProjectObjectTarget(
            type = entry.type,
            name = entry.name,
            uniqueId = entry.uniqueId,
            kind = entry.kind,
            relativePath = entry.relativePath,
        )
        return openDocument(id, entry.document, target, original = entry.document)
    }

    fun openGameSourceModify(targetGuid: String): Boolean {
        val current = project ?: return fail("尚未打开工程")
        val patch = current.workingTree.gameSourceModify(targetGuid)
            ?: return fail("工程中不存在 GameSourceModify：$targetGuid")
        return openPatch(patch)
    }

    fun openPatch(patch: GameSourceModify): Boolean {
        val current = project ?: return fail("尚未打开工程")
        val schemaType = patch.targetKey?.type
            ?: current.objectByGuid(patch.targetGuid)?.type
            ?: pack?.objectEntryByGuid(patch.targetGuid)?.type
        val target = DocumentTarget.GameSourceModifyTarget(
            targetGuid = patch.targetGuid,
            schemaType = schemaType,
            relativePath = patch.relativePath,
        )
        return openDocument(gsmDocumentId(patch.targetGuid), patch.document, target, original = patch.document)
    }

    fun openDataObjectModify(type: String, dataKey: String): Boolean {
        val current = project ?: return fail("尚未打开工程")
        val patch = current.workingTree.dataObjectModify(type, dataKey)
            ?: return fail("工程中不存在 DataObjectModify：$type/$dataKey")
        val target = DocumentTarget.DataObjectModifyTarget(
            type = type,
            dataKey = dataKey,
            relativePath = patch.relativePath,
        )
        return openDocument(domDocumentId(type, dataKey), patch.document, target, original = patch.document)
    }

    fun openPackObject(type: String, name: String): Boolean {
        val currentPack = pack ?: return fail("尚未加载数据包")
        val entry = currentPack.objectEntry(type, name)
            ?: return fail("数据包中不存在对象：$type/$name")
        if (!entry.hasDataFile) return fail("对象 $type/$name 只有索引，没有数据文件")
        val document = try {
            currentPack.readObject(entry)
        } catch (e: Exception) {
            return fail("读取对象失败：${e.message}")
        }
        val target = DocumentTarget.PackObjectTarget(
            type = type,
            name = name,
            relativePath = entry.relativePath.orEmpty(),
        )
        return openDocument(packDocumentId(type, name), document, target, original = document)
    }

    fun openDocumentById(id: DocumentId): Boolean = openDocumentByIdInternal(id)

    private fun openDocumentByIdInternal(id: DocumentId): Boolean {
        if (id in targets) {
            session.activateDocument(id)
            refresh()
            return true
        }
        val value = id.value
        return when {
            value.startsWith(OBJECT_PREFIX) -> {
                val rest = value.removePrefix(OBJECT_PREFIX)
                val type = rest.substringBefore('/')
                val name = rest.substringAfter('/', "")
                if (type.isBlank() || name.isBlank()) false else openObject(type, name)
            }

            value.startsWith(GSM_PREFIX) ->
                openGameSourceModify(value.removePrefix(GSM_PREFIX))

            value.startsWith(DOM_PREFIX) -> {
                val rest = value.removePrefix(DOM_PREFIX)
                val type = rest.substringBefore('/')
                val key = rest.substringAfter('/', "")
                if (type.isBlank() || key.isBlank()) false else openDataObjectModify(type, key)
            }

            value.startsWith(PACK_PREFIX) -> {
                val rest = value.removePrefix(PACK_PREFIX)
                val type = rest.substringBefore('/')
                val name = rest.substringAfter('/', "")
                if (type.isBlank() || name.isBlank()) false else openPackObject(type, name)
            }

            else -> false
        }
    }

    private fun openDocument(
        id: DocumentId,
        document: JsonDocument,
        target: DocumentTarget,
        original: JsonDocument,
    ): Boolean {
        targets[id] = target
        originalDocuments[id] = original
        if (rawDocuments[id] == null) {
            rawDocuments[id] = readRawFor(target, document)
        }
        viewModes.putIfAbsent(id, DocumentViewMode.FIELD)
        session.openDocument(
            id = id,
            document = document,
            title = target.title,
            sourcePath = sourcePathFor(target),
        )
        status = "已打开：${target.title}"
        statusIsError = false
        refresh()
        return true
    }

    private fun readRawFor(target: DocumentTarget, document: JsonDocument): RawJsonDocument {
        val root = if (target is DocumentTarget.PackObjectTarget) packRoot else projectRoot
        if (root != null && target.relativePath.isNotBlank()) {
            runCatching {
                return RawJsonDocument.parse(
                    fileStore.readText(fileStore.resolveWithin(root, target.relativePath)),
                    format = JsonFormat.Pretty(),
                )
            }
        }
        return RawJsonDocument.fromDocument(document, JsonFormat.Pretty())
    }

    private fun sourcePathFor(target: DocumentTarget): Path? {
        val root = if (target is DocumentTarget.PackObjectTarget) packRoot else projectRoot
        if (root == null || target.relativePath.isBlank()) return null
        return root.resolve(target.relativePath)
    }

    fun activateDocument(id: DocumentId): Boolean {
        val ok = session.activateDocument(id)
        if (ok) refresh()
        return ok
    }

    fun closeDocument(id: DocumentId, force: Boolean = false): Boolean {
        val closed = session.closeDocument(id, force)
        if (!closed) return false
        targets.remove(id)
        originalDocuments.remove(id)
        rawDocuments.remove(id)
        viewModes.remove(id)
        picker = picker?.takeIf { it.documentId != id }
        status = "已关闭文档"
        statusIsError = false
        refresh()
        return true
    }

    // ---------------------------------------------------------------- 保存

    fun saveProject(): Boolean {
        val current = project ?: return fail("尚未打开工程")
        val root = current.root ?: return fail("工程没有保存目录")
        return saveProjectTo(root, markSaved = true, updateProject = true, verb = "保存")
    }

    fun saveDocument(id: DocumentId): Boolean = saveProject()

    fun exportTo(targetRoot: Path): Boolean {
        val current = project ?: return fail("尚未打开工程")
        val synced = syncProjectFromSession(current) ?: return false
        val issues = ModValidator(pack).validate(synced)
        validation = issues
        val errors = issues.count { it.isError }
        if (errors > 0) {
            fail("导出前校验失败：$errors 个错误，请先修复")
            return false
        }
        return saveProjectTo(targetRoot, markSaved = false, updateProject = false, verb = "导出")
    }

    private fun saveProjectTo(
        targetRoot: Path,
        markSaved: Boolean,
        updateProject: Boolean,
        verb: String,
    ): Boolean {
        val current = project ?: return fail("尚未打开工程")
        val invalidRaw = rawDocuments.entries.firstOrNull { (id, raw) ->
            targets[id]?.readOnly == false && !raw.isValid
        }
        if (invalidRaw != null) {
            return fail("原始 JSON 存在未修复的错误：${targets[invalidRaw.key]?.title}")
        }

        val synced = syncProjectFromSession(current) ?: return false
        val normalizedRoot = fileStore.normalize(targetRoot)
        val result = try {
            projectWriter.write(synced, normalizedRoot)
        } catch (e: Exception) {
            logger.error("$verb 失败", e)
            return fail("$verb 失败：${e.message}")
        }
        diagnostics = result.diagnostics
        if (!result.isSuccess) {
            val message = result.diagnostics.firstOrNull { it.isError }?.message
                ?: "未知错误"
            return fail("$verb 失败：$message")
        }

        var rawWriteFailures = 0
        rawDocuments.forEach { (id, raw) ->
            val target = targets[id] ?: return@forEach
            if (target.readOnly || !raw.isValid) return@forEach
            try {
                fileStore.writeText(
                    fileStore.resolveWithin(normalizedRoot, target.relativePath),
                    raw.text,
                )
            } catch (e: Exception) {
                rawWriteFailures++
                logger.warn("写回原始文本失败：${target.relativePath}", e)
            }
        }

        if (updateProject) project = synced
        if (markSaved) {
            session.endAllMergeGroups()
            session.markAllSaved()
            session.markProjectSaved()
        }
        lastSavedAt = System.currentTimeMillis()
        statusIsError = rawWriteFailures > 0
        status = buildString {
            append("$verb 完成：").append(result.writtenFiles.size).append(" 个文件")
            if (rawWriteFailures > 0) append("，").append(rawWriteFailures).append(" 个原始文本写回失败")
        }
        refresh()
        return rawWriteFailures == 0
    }

    private fun syncProjectFromSession(current: ModProject): ModProject? {
        var tree = current.workingTree
        return try {
            targets.forEach { (id, target) ->
                val document = session.document(id) ?: return@forEach
                when (target) {
                    is DocumentTarget.ProjectObjectTarget -> {
                        val existing = tree.objectOf(target.type, target.name) ?: return@forEach
                        tree = tree.withObject(existing.copy(document = document))
                    }

                    is DocumentTarget.GameSourceModifyTarget -> {
                        val existing = tree.gameSourceModify(target.targetGuid) ?: return@forEach
                        tree = tree.withGameSourceModify(existing.copy(document = document))
                    }

                    is DocumentTarget.DataObjectModifyTarget -> {
                        val existing = tree.dataObjectModify(target.type, target.dataKey) ?: return@forEach
                        tree = tree.withDataObjectModify(existing.copy(document = document))
                    }

                    is DocumentTarget.PackObjectTarget -> Unit
                }
            }
            current.withWorkingTree(tree)
        } catch (e: Exception) {
            fail("同步工程失败：${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------- 校验

    fun validateProject(): List<ValidationIssue> {
        val current = project ?: return emptyList()
        val synced = syncProjectFromSession(current) ?: return emptyList()
        val issues = try {
            ModValidator(pack).validate(synced)
        } catch (e: Exception) {
            logger.error("校验失败", e)
            fail("校验失败：${e.message}")
            return emptyList()
        }
        validation = issues
        val errors = issues.count { it.isError }
        val warnings = issues.count { it.isWarning }
        statusIsError = errors > 0
        status = "校验完成：$errors 个错误，$warnings 个警告"
        refresh()
        return issues
    }

    // ---------------------------------------------------------------- 字段编辑

    fun fieldInfo(documentId: DocumentId, path: JsonPath): FieldEditorInfo? {
        val target = targets[documentId] ?: return null
        val type = target.schemaType ?: return null
        val schemas = pack?.schemaRepository ?: return null
        return FieldSchemaLocator.resolve(schemas, type, path)
    }

    fun setFieldText(
        documentId: DocumentId,
        path: JsonPath,
        text: String,
        info: FieldEditorInfo,
    ): Boolean {
        val node = try {
            ValueCodec.parse(info.kind, text, info.enumValues)
        } catch (e: ValueParseException) {
            return fail(e.message ?: "字段值不合法")
        }
        return setFieldNode(documentId, path, node, info)
    }

    fun setFieldNode(
        documentId: DocumentId,
        path: JsonPath,
        node: org.zender.editor.document.JsonNode,
        info: FieldEditorInfo? = null,
    ): Boolean {
        val target = requireEditableTarget(documentId) ?: return false
        val type = target.schemaType ?: return fail("该文档没有 Schema，请使用原始 JSON 视图")
        val current = project ?: return fail("尚未打开工程")
        val effective = info ?: fieldInfo(documentId, path)
        if (effective?.isReadOnly == true) return fail("字段只读：${path.format()}")

        val issues = SchemaValidator(pack).validateFieldWrite(current, type, path, node)
            .filter { it.isError }
        if (issues.isNotEmpty()) {
            return fail(issues.joinToString("；") { it.message })
        }
        val command = SetNestedFieldCommand(
            label = "设置 ${path.format()}",
            path = path,
            value = node,
            mergeKey = "field:$documentId:${path.format()}",
        )
        return executeCommand(documentId, command)
    }

    fun resetField(documentId: DocumentId, path: JsonPath, defaultValue: org.zender.editor.document.JsonNode): Boolean =
        setFieldNode(documentId, path, defaultValue)

    fun addInlineElement(
        documentId: DocumentId,
        listPath: JsonPath,
        element: org.zender.editor.document.JsonNode,
    ): Boolean {
        requireEditableTarget(documentId) ?: return false
        return executeCommand(
            documentId,
            AddInlineElementCommand("追加元素", listPath, element),
        )
    }

    fun removeElement(documentId: DocumentId, path: JsonPath, index: Int): Boolean {
        requireEditableTarget(documentId) ?: return false
        return executeCommand(documentId, RemoveElementCommand("删除元素", path, index))
    }

    fun moveElement(documentId: DocumentId, path: JsonPath, from: Int, to: Int): Boolean {
        requireEditableTarget(documentId) ?: return false
        return executeCommand(documentId, MoveElementCommand("移动元素", path, from, to))
    }

    fun removeReference(documentId: DocumentId, fieldPath: JsonPath, index: Int): Boolean {
        requireEditableTarget(documentId) ?: return false
        return executeCommand(
            documentId,
            RemoveElementCommand("删除引用", WarpFields.dataPath(fieldPath), index),
        )
    }

    fun moveReference(documentId: DocumentId, fieldPath: JsonPath, from: Int, to: Int): Boolean {
        requireEditableTarget(documentId) ?: return false
        return executeCommand(
            documentId,
            MoveElementCommand("移动引用", WarpFields.dataPath(fieldPath), from, to),
        )
    }

    fun clearReference(documentId: DocumentId, fieldPath: JsonPath): Boolean {
        requireEditableTarget(documentId) ?: return false
        return executeCommand(documentId, ClearReferenceCommand("清除引用", fieldPath))
    }

    // ---------------------------------------------------------------- 外部集成（MCP）

    /**
     * 确保工程对象文档已打开并返回其 [DocumentId]。
     * 供 MCP 等外部集成复用编辑器的文档、命令栈与校验。
     */
    fun ensureProjectObjectDocument(type: String, name: String): DocumentId? {
        val current = project ?: run {
            fail("尚未打开工程")
            return null
        }
        val entry = current.objectOf(type, name) ?: run {
            fail("工程中不存在对象：$type/$name")
            return null
        }
        return if (openProjectObject(entry)) objectDocumentId(type, name) else null
    }

    /** 设置标量/列表引用字段，语义与字段视图的引用选择一致。 */
    fun setReferenceByObject(
        type: String,
        name: String,
        fieldPath: JsonPath,
        referenceKey: String,
        warpType: WarpType,
        index: Int? = null,
    ): Boolean {
        val documentId = ensureProjectObjectDocument(type, name) ?: return false
        val current = project ?: return fail("尚未打开工程")
        val info = fieldInfo(documentId, fieldPath)
            ?: return fail("无法解析字段：${fieldPath.format()}")
        val fieldType = if (info.isReferenceList) {
            info.schemaField.elementTypeName ?: info.typeName ?: info.schemaField.typeName
        } else {
            info.typeName ?: info.schemaField.typeName
        }
        // MC 引用本工程的非 GUID 对象时统一写成 `Namespace:Name`。
        val key = current.normalizeReferenceKey(fieldType, referenceKey)
        val command = when {
            info.isReference -> SetReferenceCommand(
                label = "设置引用 ${fieldPath.format()}",
                fieldPath = fieldPath,
                referenceKey = key,
                warpType = warpType,
            )

            info.isReferenceList -> AddReferenceToListCommand(
                label = "添加引用 ${fieldPath.format()}",
                listFieldPath = fieldPath,
                referenceKey = key,
                warpType = warpType,
                index = index,
            )

            else -> return fail("字段不是引用字段：${fieldPath.format()}")
        }
        return executeCommand(documentId, command)
    }

    fun clearReferenceByObject(type: String, name: String, fieldPath: JsonPath): Boolean {
        val documentId = ensureProjectObjectDocument(type, name) ?: return false
        return clearReference(documentId, fieldPath)
    }

    fun removeReferenceByObject(
        type: String,
        name: String,
        fieldPath: JsonPath,
        index: Int,
    ): Boolean {
        val documentId = ensureProjectObjectDocument(type, name) ?: return false
        return removeReference(documentId, fieldPath, index)
    }

    private fun executeCommand(documentId: DocumentId, command: EditCommand): Boolean {
        val updated = try {
            session.execute(documentId, command)
        } catch (e: Exception) {
            return fail("编辑失败：${e.message}")
        } ?: return fail("文档未打开")
        val raw = rawDocuments[documentId]
        val format = raw?.currentFormat ?: JsonFormat.Pretty()
        rawDocuments[documentId] = raw?.withDocument(updated, format)
            ?: RawJsonDocument.fromDocument(updated, format)
        statusIsError = false
        status = command.label
        refresh()
        return true
    }

    // ---------------------------------------------------------------- 原始视图

    fun updateRawText(documentId: DocumentId, text: String) {
        val target = targets[documentId] ?: return
        if (target.readOnly) return
        val raw = rawDocuments[documentId] ?: return
        val updated = raw.withText(text)
        rawDocuments[documentId] = updated
        if (updated.isValid) {
            val document = updated.requireDocument()
            if (session.document(documentId) != document) {
                session.execute(
                    documentId,
                    ReplaceDocumentCommand("编辑原始 JSON", document, "raw:$documentId"),
                )
            }
            statusIsError = false
            status = "原始 JSON 有效"
        } else {
            statusIsError = true
            status = "原始 JSON 错误：${updated.error?.message ?: "无法解析"}"
        }
        refresh()
    }

    fun formatRaw(documentId: DocumentId, pretty: Boolean): Boolean {
        val raw = rawDocuments[documentId] ?: return fail("原始文档不存在")
        val formatted = try {
            if (pretty) raw.format(JsonFormat.Pretty()) else raw.compact()
        } catch (e: Exception) {
            return fail("格式化失败：${e.message}")
        }
        rawDocuments[documentId] = formatted
        statusIsError = false
        status = if (pretty) "已格式化为缩进 JSON" else "已压缩 JSON"
        refresh()
        return true
    }

    fun revertRaw(documentId: DocumentId): Boolean {
        val raw = rawDocuments[documentId] ?: return fail("原始文档不存在")
        val reverted = raw.revert()
        rawDocuments[documentId] = reverted
        if (reverted.isValid) {
            val document = reverted.requireDocument()
            if (session.document(documentId) != document) {
                session.execute(documentId, ReplaceDocumentCommand("还原原始 JSON", document))
            }
            status = "已还原为打开时的内容"
            statusIsError = false
        } else {
            status = "原始内容本身不是合法 JSON，字段视图保持最后一次有效状态"
            statusIsError = true
        }
        refresh()
        return true
    }

    fun setViewMode(documentId: DocumentId, mode: DocumentViewMode): Boolean {
        val raw = rawDocuments[documentId]
        if (mode != DocumentViewMode.RAW && raw != null && !raw.isValid) {
            return fail("原始 JSON 存在错误，请先修复或还原后再切换视图")
        }
        if (mode == DocumentViewMode.RAW && raw == null) {
            session.document(documentId)?.let {
                rawDocuments[documentId] = RawJsonDocument.fromDocument(it, JsonFormat.Pretty())
            }
        }
        viewModes[documentId] = mode
        statusIsError = false
        refresh()
        return true
    }

    fun undo(documentId: DocumentId): Boolean {
        if (!session.undo(documentId)) return false
        syncRawFromSession(documentId)
        refresh()
        return true
    }

    fun redo(documentId: DocumentId): Boolean {
        if (!session.redo(documentId)) return false
        syncRawFromSession(documentId)
        refresh()
        return true
    }

    fun endMergeGroup(documentId: DocumentId) {
        session.endMergeGroup(documentId)
    }

    private fun syncRawFromSession(documentId: DocumentId) {
        val document = session.document(documentId) ?: return
        val raw = rawDocuments[documentId]
        val format = raw?.currentFormat ?: JsonFormat.Pretty()
        rawDocuments[documentId] = raw?.withDocument(document, format)
            ?: RawJsonDocument.fromDocument(document, format)
    }

    // ---------------------------------------------------------------- 引用

    fun referenceDisplay(
        documentId: DocumentId,
        fieldPath: JsonPath,
        fieldTypeOverride: String? = null,
    ): ReferenceDisplay {
        val target = targets[documentId]
        val info = fieldInfo(documentId, fieldPath)
        val fieldType = fieldTypeOverride
            ?: info?.typeName
            ?: info?.schemaField?.typeName
            ?: ""
        val document = session.document(documentId)
        val keyNode = document?.root?.readPath(WarpFields.dataPath(fieldPath))
        val key = (keyNode as? org.zender.editor.document.JsonNode.StringNode)?.value
        val warpType = WarpType.fromNode(
            document?.root?.readPath(WarpFields.typePath(fieldPath)),
        )
        if (key.isNullOrBlank()) {
            return ReferenceDisplay(null, warpType, "未设置", resolved = false)
        }
        val described = describeReference(fieldType, key, target?.readOnly == true)
        return ReferenceDisplay(key, warpType, described.first, described.second, described.third)
    }

    /** 供引用列表的单个元素查询；不依赖文档路径。 */
    fun describeReferenceKey(fieldType: String, key: String): ReferenceDisplay {
        val described = describeReference(fieldType, key, readOnly = false)
        return ReferenceDisplay(key, null, described.first, described.second, described.third)
    }

    /**
     * 选择器详情预览。必须先把候选编码成实际引用键：
     * UniqueID 对象用 GUID，而不是对象名，否则会误报“目标不存在”。
     */
    fun previewPickerItem(item: PickerItem, fieldType: String): ReferenceDisplay {
        val current = project ?: return ReferenceDisplay(
            item.name,
            null,
            "尚未打开工程",
            resolved = false,
        )
        val key = try {
            when {
                item.origin == EntryOrigin.BASE_PACK &&
                    (item.referenceKind == ReferenceKind.SPRITE ||
                        item.referenceKind == ReferenceKind.AUDIO) -> item.name

                item.isResource -> current.keyCodec.encodeResourceKey(item.name)

                else -> current.keyCodec.encodeReference(
                    ObjectKey.of(item.type, item.name, item.uniqueId),
                    item.referenceKind,
                )
            }
        } catch (e: Exception) {
            return ReferenceDisplay(
                item.name,
                null,
                "无法生成引用键：${e.message}",
                resolved = false,
                warning = true,
            )
        }
        return describeReferenceKey(fieldType, key)
    }

    private fun describeReference(
        fieldType: String,
        key: String,
        readOnly: Boolean,
    ): Triple<String, Boolean, Boolean> {
        val current = project
        val currentPack = pack
        if (current != null && !readOnly) {
            return when (val check = ProjectReferenceResolver(current, currentPack).resolve(fieldType, key)) {
                is ReferenceCheck.Resolved -> Triple(
                    "已解析：${check.targetType ?: fieldType}（${originLabel(check.origin.name)}）",
                    true,
                    false,
                )

                is ReferenceCheck.NotFound -> Triple("目标不存在：$key", false, false)
                is ReferenceCheck.TypeMismatch ->
                    Triple("类型不匹配：目标是 ${check.targetType}", false, true)

                is ReferenceCheck.Ambiguous ->
                    Triple("存在多个候选：${check.candidates.joinToString("、")}", false, true)

                is ReferenceCheck.Unverified -> Triple("无法确认：${check.reason}", false, true)
            }
        }
        if (currentPack == null) return Triple("未加载数据包", false, false)
        return when (val resolution = currentPack.referenceIndex.resolveRaw(fieldType, key)) {
            is ReferenceResolution.Resolved ->
                Triple("已解析：${resolution.entry.type}/${resolution.entry.name}", true, false)

            is ReferenceResolution.ResolvedResource ->
                Triple("已解析资源：${resolution.entry.key}", true, false)

            is ReferenceResolution.NotFound -> Triple("目标不存在：$key", false, false)
            is ReferenceResolution.TypeMismatch ->
                Triple("类型不匹配：目标是 ${resolution.entry.type}", false, true)

            is ReferenceResolution.Ambiguous ->
                Triple(
                    "存在多个候选：${(resolution.candidates.map { it.name } + resolution.resources.map { it.key }).joinToString("、")}",
                    false,
                    true,
                )
        }
    }

    private fun originLabel(origin: String): String = when (origin) {
        "PROJECT" -> "工程"
        "BASE_PACK" -> "数据包"
        "PROJECT_RESOURCE" -> "工程资源"
        "BASE_PACK_RESOURCE" -> "数据包资源"
        else -> origin
    }

    fun openPicker(
        documentId: DocumentId,
        fieldPath: JsonPath,
        fieldType: String,
        isList: Boolean,
    ) {
        if (requireEditableTarget(documentId) == null) return
        session.focusReferenceField(FieldRef(documentId, fieldPath))
        val ruleKind = pack?.referenceRule?.kindFor(fieldType)
        val kind = when (ruleKind) {
            ReferenceKind.SPRITE, ReferenceKind.AUDIO -> PickerWindowKind.RESOURCE
            else -> PickerWindowKind.OBJECT
        }
        picker = PickerRequest(kind, documentId, fieldPath, fieldType, isList)
        refresh()
    }

    fun closePicker() {
        picker = null
        refresh()
    }

    fun applyPickerItem(item: PickerItem): Boolean = applyPickerItems(listOf(item))

    /**
     * 提交一个或多个选择。引用列表支持多选；任一写入失败时保留选择器并返回 false。
     */
    fun applyPickerItems(items: List<PickerItem>): Boolean {
        if (items.isEmpty()) return fail("没有选择引用目标")
        val request = picker ?: return fail("没有待提交的选择器")
        val target = requireEditableTarget(request.documentId) ?: return false
        val current = project ?: return fail("尚未打开工程")
        if (!request.isList && target.isPatch && current.framework == FrameworkId.MC) {
            return fail("MC 补丁的直接标量引用不会被运行时处理，请使用原始视图的 \$override 或列表引用")
        }

        var allOk = true
        var lastError: String? = null
        items.forEach { item ->
            val applied = applyPickerItemToRequest(request, item, current)
            if (!applied) {
                allOk = false
                lastError = status
            }
        }
        if (allOk) {
            recentPickedItems.removeAll { existing ->
                items.any { it.identity() == existing.identity() }
            }
            recentPickedItems.addAll(0, items)
            while (recentPickedItems.size > MAX_RECENT_PICKER_ITEMS) {
                recentPickedItems.removeAt(recentPickedItems.lastIndex)
            }
            picker = null
            statusIsError = false
            status = "已写入 ${items.size} 个引用"
        } else if (lastError != null) {
            status = lastError.orEmpty()
            statusIsError = true
        }
        refresh()
        return allOk
    }

    private fun applyPickerItemToRequest(
        request: PickerRequest,
        item: PickerItem,
        current: ModProject,
    ): Boolean {
        val target = requireEditableTarget(request.documentId) ?: return false
        val key = try {
            when {
                // 数据包内置图片/音频没有独立文件，键是 Name 索引中的裸名称；
                // MC 不能给它们加当前 Mod 的 Namespace。
                item.origin == EntryOrigin.BASE_PACK &&
                    (item.referenceKind == ReferenceKind.SPRITE ||
                        item.referenceKind == ReferenceKind.AUDIO) -> item.name

                item.isResource -> current.keyCodec.encodeResourceKey(item.name)

                else -> current.keyCodec.encodeReference(
                    ObjectKey.of(item.type, item.name, item.uniqueId),
                    item.referenceKind,
                )
            }
        } catch (e: Exception) {
            return fail("无法生成引用键：${e.message}")
        }

        val warpType = if (request.isList && target.isPatch) {
            WarpType.ADD_REFERENCE
        } else {
            WarpType.REFERENCE
        }
        val command: EditCommand = if (request.isList) {
            AddReferenceToListCommand(
                label = "添加引用",
                listFieldPath = request.fieldPath,
                referenceKey = key,
                warpType = warpType,
            )
        } else {
            SetReferenceCommand(
                label = "设置引用",
                fieldPath = request.fieldPath,
                referenceKey = key,
                warpType = warpType,
            )
        }
        return executeCommand(request.documentId, command)
    }

    /** 当前会话中最近提交的引用候选（用于选择器“最近使用”过滤）。 */
    fun recentPickerItems(): List<PickerItem> = recentPickedItems.toList()

    private fun PickerItem.identity(): String =
        "${origin}|${type}|${name}|${uniqueId}|${resourcePath}"

    fun pickerItems(
        request: PickerRequest,
        keyword: String = "",
        origin: EntryOrigin? = null,
    ): List<PickerItem> {
        val currentPack = pack
        val currentProject = project
        val items = mutableListOf<PickerItem>()

        val packItems = currentPack?.picker?.query(
            PickerQuery(
                fieldType = request.fieldType,
                keyword = keyword,
                origin = origin,
                limit = PickerQuery.MAX_LIMIT,
            ),
        )?.items.orEmpty()
        packItems.filter { item ->
            if (request.kind == PickerWindowKind.RESOURCE) {
                item.isResource ||
                    item.referenceKind == ReferenceKind.SPRITE ||
                    item.referenceKind == ReferenceKind.AUDIO
            } else {
                !item.isResource
            }
        }.forEach { items += it }

        val projectAllowed = origin == null || origin == EntryOrigin.PROJECT
        if (projectAllowed && currentProject != null) {
            val rule = currentPack?.referenceRule
            currentProject.workingTree.objects.values.forEach { entry ->
                val compatible = rule?.isCompatible(request.fieldType, entry.type)?.compatible
                    ?: (entry.type == request.fieldType)
                if (!compatible) return@forEach
                val referenceKind = when {
                    entry.kind == ProjectObjectKind.UNIQUE_ID -> ReferenceKind.UNIQUE_ID
                    else -> ReferenceKind.PLAIN_OBJECT
                }
                val item = PickerItem(
                    type = entry.type,
                    name = entry.name,
                    uniqueId = entry.uniqueId,
                    displayName = null,
                    referenceKind = referenceKind,
                    origin = EntryOrigin.PROJECT,
                )
                if (request.kind == PickerWindowKind.OBJECT && item.matchesKeyword(keyword)) {
                    items += item
                }
            }
            if (request.kind == PickerWindowKind.RESOURCE) {
                val wantedKind = when (currentPack?.referenceRule?.kindFor(request.fieldType)) {
                    ReferenceKind.AUDIO -> ResourceKind.AUDIO
                    else -> ResourceKind.IMAGE
                }
                currentProject.workingTree.resources.values
                    .filter { it.kind == wantedKind }
                    .forEach { resource ->
                        val item = PickerItem(
                            type = resource.typeName,
                            name = resource.key,
                            displayName = null,
                            referenceKind = if (resource.kind == ResourceKind.AUDIO) {
                                ReferenceKind.AUDIO
                            } else {
                                ReferenceKind.SPRITE
                            },
                            origin = EntryOrigin.PROJECT,
                            resourcePath = resource.relativePath,
                        )
                        if (item.matchesKeyword(keyword)) items += item
                    }
            }
        }

        val trimmed = keyword.trim()
        return items
            .distinctBy { "${it.origin}|${it.type}|${it.name}|${it.uniqueId}|${it.resourcePath}" }
            .sortedWith(
                compareBy<PickerItem> {
                    if (trimmed.isNotEmpty() && it.name.equals(trimmed, ignoreCase = true)) 0 else 1
                }
                    .thenBy { it.origin }
                    .thenBy { it.type }
                    .thenBy { it.name },
            )
    }

    private fun PickerItem.matchesKeyword(keyword: String): Boolean {
        val value = keyword.trim().lowercase()
        if (value.isEmpty()) return true
        return name.lowercase().contains(value) ||
            displayName?.lowercase()?.contains(value) == true ||
            uniqueId?.lowercase()?.contains(value) == true ||
            type.lowercase().contains(value)
    }

    fun resourceFile(item: PickerItem): Path? {
        val relative = item.resourcePath ?: return null
        return when (item.origin) {
            EntryOrigin.PROJECT ->
                project?.workingTree?.resource(
                    if (item.referenceKind == ReferenceKind.AUDIO) ResourceKind.AUDIO else ResourceKind.IMAGE,
                    item.name,
                )?.sourcePath ?: projectRoot?.resolve(relative)

            else -> {
                val kind = if (item.referenceKind == ReferenceKind.AUDIO) {
                    ResourceKind.AUDIO
                } else {
                    ResourceKind.IMAGE
                }
                val entry = pack?.resources?.entries?.firstOrNull {
                    it.key == item.name && it.kind == kind && it.relativePath == relative
                }
                (entry?.sourceRoot ?: packRoot)?.resolve(relative)
            }
        }
    }

    fun projectResourceFile(resourceRelativePath: String, kind: ResourceKind): Path? {
        val resource = project?.workingTree?.resources?.values?.firstOrNull {
            it.relativePath == resourceRelativePath
        }
        if (resource?.sourcePath != null) return resource.sourcePath
        return projectRoot?.resolve(resourceRelativePath)
    }

    fun packResourceFile(entry: ResourceEntry): Path? =
        (entry.sourceRoot ?: packRoot)?.resolve(entry.relativePath)

    // ---------------------------------------------------------------- 本地化

    fun localizationEntries(): List<LocalizationEntry> =
        project?.workingTree?.localization?.table?.entries.orEmpty()

    /**
     * 查询某个 Key 的当前中英文文本：工程本地化优先，空列回退到数据包。
     */
    fun localizedEntry(key: String?): LocalizationEntry? {
        if (key.isNullOrBlank()) return null
        val projectEntry = project?.workingTree?.localization?.entry(key)
        val packEntry = pack?.localization?.entry(key)
        if (projectEntry == null) return packEntry
        return LocalizationEntry(
            key = key,
            english = projectEntry.english.ifBlank { packEntry?.english.orEmpty() },
            chinese = projectEntry.chinese.ifBlank { packEntry?.chinese.orEmpty() },
        )
    }

    /** 编辑某个本地化 Key 的中英文；Key 不存在时新增。 */
    fun updateLocalizedText(key: String, english: String, chinese: String): Boolean =
        updateLocalizationEntry(key, english, chinese)

    fun updateLocalizationEntry(key: String, english: String, chinese: String): Boolean {
        val current = project ?: return fail("尚未打开工程")
        if (key.isBlank()) return fail("本地化 Key 不能为空")
        val localization = current.workingTree.localization
        val updated = localization.withEntry(LocalizationEntry(key.trim(), english, chinese))
        project = current.withWorkingTree(
            current.workingTree.withLocalization(updated),
        )
        session.markProjectDirty()
        statusIsError = false
        status = "已更新本地化：${key.trim()}"
        refresh()
        return true
    }

    fun removeLocalizationEntry(key: String): Boolean {
        val current = project ?: return fail("尚未打开工程")
        val localization = current.workingTree.localization
        if (localization.entry(key) == null) return false
        project = current.withWorkingTree(
            current.workingTree.withLocalization(localization.withoutKey(key)),
        )
        session.markProjectDirty()
        status = "已删除本地化 Key：$key"
        statusIsError = false
        refresh()
        return true
    }

    fun localizationPreview(): Map<String, String> {
        val current = project ?: return emptyMap()
        val localization = current.workingTree.localization
        val specs = localization.files.ifEmpty { current.format.defaultLocalizationFiles() }
        return when (current.framework) {
            FrameworkId.ML -> org.zender.editor.project.ProjectLocalizationFiles.writeMl(localization, specs)
            FrameworkId.MC -> org.zender.editor.project.ProjectLocalizationFiles.writeMc(localization, specs)
        }
    }

    // ---------------------------------------------------------------- 资源

    suspend fun importResource(source: Path, overwrite: Boolean = false): Boolean =
        importResource(source, key = null, kind = null, overwrite = overwrite) != null

    /**
     * 导入资源并返回完整结果，供 MCP 等外部集成使用。
     * [key] / [kind] 为空时沿用 [ResourceImporter] 的默认推断。
     */
    suspend fun importResource(
        source: Path,
        key: String?,
        kind: ResourceKind?,
        overwrite: Boolean = false,
    ): ResourceImportResult? {
        val current = project ?: run {
            fail("尚未打开工程")
            return null
        }
        val result = withContext(Dispatchers.IO) {
            if (key == null) {
                resourceImporter.import(current, source, kind = kind, overwrite = overwrite)
            } else {
                resourceImporter.import(
                    current,
                    source,
                    key = key,
                    kind = kind,
                    overwrite = overwrite,
                )
            }
        }
        diagnostics = result.diagnostics
        if (!result.isSuccess) {
            val message = result.diagnostics.firstOrNull()?.message ?: "资源导入失败"
            fail(message)
            return null
        }
        project = result.project
        session.markProjectDirty()
        statusIsError = result.diagnostics.any { it.isError }
        status = "已导入资源：${result.resource?.key}"
        refresh()
        return result
    }

    fun removeResource(kind: ResourceKind, key: String): Boolean {
        val current = project ?: return fail("尚未打开工程")
        val id = ResourceId(kind, key)
        if (id !in current.workingTree.resources) return false
        project = current.withWorkingTree(current.workingTree.withoutResource(id))
        session.markProjectDirty()
        status = "已从工程移除资源：$key（磁盘文件不会自动删除）"
        statusIsError = false
        refresh()
        return true
    }

    // ---------------------------------------------------------------- 选择与视图状态

    /**
     * 选择字段。[reference] 为 true 时同时记录为最近聚焦的引用字段，
     * 供星图拖放/选择器提交使用。
     */
    fun selectField(documentId: DocumentId, path: JsonPath, reference: Boolean = false) {
        val ref = FieldRef(documentId, path)
        session.selectField(ref)
        if (reference) session.focusReferenceField(ref)
        session.endMergeGroup(documentId)
        refresh()
    }

    /**
     * 打开校验问题对应的对象，并选中问题字段。
     *
     * 路径格式为「工程内相对路径」或「相对路径:字段路径」；
     * `XxxWarpData[0]` / `XxxWarpType` 会映射回引用字段 `Xxx`。
     */
    fun openValidationIssue(issue: ValidationIssue): Boolean {
        val rawPath = issue.path?.takeIf { it.isNotBlank() } ?: return false
        val relativePath = rawPath.substringBefore(':')
        val fieldText = rawPath.substringAfter(':', "")
        val documentId = openDocumentByRelativePath(relativePath)
            ?: return fail("无法定位校验问题：$rawPath")

        if (fieldText.isNotBlank()) {
            val parsed = runCatching { JsonPath.parse(fieldText) }.getOrNull()
            if (parsed != null) {
                val fieldPath = referenceFieldPath(parsed)
                if (viewModes[documentId] != DocumentViewMode.FIELD) {
                    // 原始视图有未修复错误时切换会失败；此时仍保持当前视图并尝试选中字段。
                    setViewMode(documentId, DocumentViewMode.FIELD)
                }
                // 清掉搜索/收藏过滤，确保被定位的字段一定能显示出来。
                fieldOptions = FieldViewOptions(showDefaults = true, showUnknown = true)
                val info = fieldInfo(documentId, fieldPath)
                selectField(
                    documentId = documentId,
                    path = fieldPath,
                    reference = info?.isReference == true || info?.isReferenceList == true,
                )
            }
        }
        screen = WorkspaceScreen.FILES
        status = "已定位校验问题：$rawPath"
        statusIsError = false
        refresh()
        return true
    }

    private fun openDocumentByRelativePath(relativePath: String): DocumentId? {
        val current = project
        if (current != null) {
            current.workingTree.objects.values
                .firstOrNull { it.relativePath == relativePath }
                ?.let { entry ->
                    return if (openProjectObject(entry)) {
                        objectDocumentId(entry.type, entry.name)
                    } else {
                        null
                    }
                }
            current.workingTree.gameSourceModifies.values
                .firstOrNull { it.relativePath == relativePath }
                ?.let { patch ->
                    return if (openPatch(patch)) {
                        gsmDocumentId(patch.targetGuid)
                    } else {
                        null
                    }
                }
            current.workingTree.dataObjectModifies.values
                .firstOrNull { it.relativePath == relativePath }
                ?.let { patch ->
                    return if (openDataObjectModify(patch.type, patch.dataKey)) {
                        domDocumentId(patch.type, patch.dataKey)
                    } else {
                        null
                    }
                }
        }
        pack?.objectIndex?.entries
            ?.firstOrNull { it.relativePath == relativePath && it.hasDataFile }
            ?.let { entry ->
                return if (openPackObject(entry.type, entry.name)) {
                    packDocumentId(entry.type, entry.name)
                } else {
                    null
                }
            }
        return null
    }

    /** `XxxWarpData[0]` / `XxxWarpType` → 引用字段 `Xxx`。 */
    private fun referenceFieldPath(path: JsonPath): JsonPath {
        val segments = path.segments
        val keyIndex = segments.indexOfLast { it is JsonPathSegment.Key }
        if (keyIndex < 0) return path
        val key = segments[keyIndex] as JsonPathSegment.Key
        val base = WarpFields.baseNameOrNull(key.key) ?: return path
        return JsonPath.of(segments.take(keyIndex) + JsonPathSegment.Key(base))
    }

    /** 打开星图节点对应的文档；资源/未解析节点返回 false。 */
    /** 解析引用键对应的工程/数据包对象或资源键。 */
    private fun referenceTargetKey(
        fieldType: String,
        key: String,
        readOnly: Boolean,
    ): ObjectKey? {
        val current = project
        val currentPack = pack
        if (current != null && !readOnly) {
            val check = ProjectReferenceResolver(current, currentPack).resolve(fieldType, key)
            if (check is ReferenceCheck.Resolved) return check.targetKey
        }
        return when (val resolution = currentPack?.referenceIndex?.resolveRaw(fieldType, key)) {
            is ReferenceResolution.Resolved -> resolution.entry.key
            is ReferenceResolution.ResolvedResource ->
                ObjectKey.of(resolution.entry.typeName, resolution.entry.key)

            else -> null
        }
    }

    /** 从引用字段/列表元素打开目标对象到文件 Tab。 */
    fun openReferenceInTab(
        fieldType: String,
        key: String,
        readOnly: Boolean = false,
    ): Boolean {
        val target = referenceTargetKey(fieldType, key, readOnly)
            ?: return fail("无法解析引用目标：$key")
        project?.workingTree?.objects?.get(target)?.let { entry ->
            return openProjectObject(entry)
        }
        val entry = pack?.objectEntry(target.type, target.name)
            ?: return fail("引用目标不存在：${target.type}/${target.name}")
        if (!entry.hasDataFile) {
            return fail("引用目标 ${target.type}/${target.name} 只有索引，没有可打开的数据文件")
        }
        return openPackObject(target.type, target.name)
    }

    /** 切换到星图并选中引用目标。 */
    fun focusReferenceInStarMap(
        fieldType: String,
        key: String,
        readOnly: Boolean = false,
    ): Boolean {
        val target = referenceTargetKey(fieldType, key, readOnly)
            ?: return fail("无法解析引用目标：$key")
        starMapFocusNonce += 1
        starMapFocus = StarMapFocusRequest(target, starMapFocusNonce)
        screen = WorkspaceScreen.STAR_MAP
        status = "星图定位：${target.type}/${target.name}"
        statusIsError = false
        refresh()
        return true
    }

    fun openGraphNode(node: GraphNode): Boolean {
        return when (node.origin) {
            GraphNodeOrigin.PROJECT_ADDED,
            GraphNodeOrigin.PROJECT_MODIFIED,
            -> if (project?.objectOf(node.key.type, node.key.name) != null) {
                openObject(node.key.type, node.key.name)
            } else {
                openPackObject(node.key.type, node.key.name)
            }

            GraphNodeOrigin.BASE_TARGET ->
                openPackObject(node.key.type, node.key.name)

            GraphNodeOrigin.RESOURCE ->
                fail("资源节点不提供字段编辑")

            GraphNodeOrigin.UNRESOLVED ->
                fail("未解析节点没有可用文档")
        }
    }

    /**
     * 把星图节点作为引用写入最近聚焦的引用字段/引用列表。
     */
    fun applyGraphReference(node: GraphNode, fieldRef: FieldRef): Boolean {
        val current = project ?: return fail("尚未打开工程")
        val target = requireEditableTarget(fieldRef.documentId) ?: return false
        val info = fieldInfo(fieldRef.documentId, fieldRef.path)
            ?: return fail("目标字段不可用")
        val isList = info.isReferenceList
        if (!isList && !info.isReference) {
            return fail("目标字段不是引用字段：${fieldRef.path.format()}")
        }
        if (!isList && target.isPatch && current.framework == FrameworkId.MC) {
            return fail("MC 补丁的标量引用不会被运行时处理，请使用 \$override 或列表引用")
        }

        val fieldType = if (isList) {
            info.schemaField.elementTypeName ?: info.typeName ?: info.schemaField.typeName
        } else {
            info.typeName ?: info.schemaField.typeName
        }
        val kind = if (node.origin == GraphNodeOrigin.RESOURCE) {
            if (node.key.type == "AudioClip") ReferenceKind.AUDIO else ReferenceKind.SPRITE
        } else {
            (pack ?: current.basePack)?.referenceRule?.kindFor(node.key.type)
                ?: if (node.key.uniqueId != null) ReferenceKind.UNIQUE_ID else ReferenceKind.PLAIN_OBJECT
        }
        val key = try {
            if (node.origin == GraphNodeOrigin.RESOURCE) {
                val resourceKind = if (node.key.type == "AudioClip") {
                    ResourceKind.AUDIO
                } else {
                    ResourceKind.IMAGE
                }
                val projectResource = current.workingTree.resource(resourceKind, node.key.name)
                if (projectResource != null) {
                    current.keyCodec.encodeResourceKey(node.key.name)
                } else {
                    // 数据包内置图片/音频使用裸名称。
                    node.key.name
                }
            } else {
                current.keyCodec.encodeReference(node.key, kind)
            }
        } catch (e: Exception) {
            return fail("无法生成引用键：${e.message}")
        }
        when (val check = ProjectReferenceResolver(current, pack ?: current.basePack)
            .resolve(fieldType, key)
        ) {
            is ReferenceCheck.Resolved,
            is ReferenceCheck.Unverified,
            -> Unit

            is ReferenceCheck.TypeMismatch ->
                return fail("引用类型不兼容：${check.targetType} -> $fieldType")

            is ReferenceCheck.Ambiguous ->
                return fail("引用存在多个候选：${check.candidates.joinToString("、")}")

            is ReferenceCheck.NotFound ->
                return fail("引用目标不存在：$key")
        }

        val warpType = if (isList && target.isPatch) {
            WarpType.ADD_REFERENCE
        } else {
            WarpType.REFERENCE
        }
        val command: EditCommand = if (isList) {
            AddReferenceToListCommand(
                label = "星图添加引用",
                listFieldPath = fieldRef.path,
                referenceKey = key,
                warpType = warpType,
            )
        } else {
            SetReferenceCommand(
                label = "星图设置引用",
                fieldPath = fieldRef.path,
                referenceKey = key,
                warpType = warpType,
            )
        }
        val ok = executeCommand(fieldRef.documentId, command)
        if (ok) {
            status = "已写入引用：$key → ${fieldRef.path.format()}"
            statusIsError = false
            refresh()
        }
        return ok
    }

    fun graphResourcePath(node: GraphNode): Path? {
        if (node.origin != GraphNodeOrigin.RESOURCE) return null
        project?.workingTree?.resources?.values
            ?.firstOrNull { it.key == node.key.name }
            ?.let { resource ->
                return resource.sourcePath ?: projectRoot?.resolve(resource.relativePath)
            }
        val entry = pack?.resources?.find(node.key.name) ?: return null
        return (entry.sourceRoot ?: packRoot)?.resolve(entry.relativePath)
    }

    fun setScreen(value: WorkspaceScreen) {
        screen = value
        refresh()
    }

    fun setFieldOptions(value: FieldViewOptions) {
        fieldOptions = value
        refresh()
    }

    fun setViewModeForActive(mode: DocumentViewMode): Boolean {
        val id = session.currentState.activeDocumentId ?: return false
        return setViewMode(id, mode)
    }

    // ---------------------------------------------------------------- 收藏与设置

    fun toggleFavorite(
        type: String,
        fieldPath: String,
        folderId: String? = null,
    ): Boolean {
        return try {
            val favorite = favoriteStore.toggle(type, fieldPath, folderId)
            session.replaceFavorites(favoriteStore.favorites().toSet())
            settings = settingsStore.load()
            status = if (favorite) "已收藏字段：$fieldPath" else "已取消收藏：$fieldPath"
            statusIsError = false
            refresh()
            favorite
        } catch (e: Exception) {
            logger.warn("更新字段收藏失败", e)
            fail("更新收藏失败：${e.message}")
            false
        }
    }

    /** 把字段放进指定收藏夹。 */
    fun addFavoriteToFolder(type: String, fieldPath: String, folderId: String): Boolean {
        return try {
            favoriteStore.addToFolder(type, fieldPath, folderId)
            session.replaceFavorites(favoriteStore.favorites().toSet())
            settings = settingsStore.load()
            status = "已收藏字段到收藏夹：$fieldPath"
            statusIsError = false
            refresh()
            true
        } catch (e: Exception) {
            logger.warn("收藏字段失败", e)
            fail("收藏失败：${e.message}")
            false
        }
    }

    fun addFavoriteFolder(name: String): Boolean {
        if (name.isBlank()) return fail("收藏夹名称不能为空")
        return try {
            favoriteStore.addFolder(name)
            settings = settingsStore.load()
            status = "已创建收藏夹：${name.trim()}"
            statusIsError = false
            refresh()
            true
        } catch (e: Exception) {
            fail("创建收藏夹失败：${e.message}")
            false
        }
    }

    fun removeFavoriteFolder(id: String): Boolean {
        return try {
            favoriteStore.removeFolder(id)
            session.replaceFavorites(favoriteStore.favorites().toSet())
            settings = settingsStore.load()
            status = "已删除收藏夹"
            statusIsError = false
            refresh()
            true
        } catch (e: Exception) {
            fail("删除收藏夹失败：${e.message}")
            false
        }
    }

    fun renameFavoriteFolder(id: String, name: String): Boolean {
        if (name.isBlank()) return fail("收藏夹名称不能为空")
        return try {
            favoriteStore.renameFolder(id, name)
            settings = settingsStore.load()
            status = "已重命名收藏夹"
            statusIsError = false
            refresh()
            true
        } catch (e: Exception) {
            fail("重命名收藏夹失败：${e.message}")
            false
        }
    }

    fun removeFavorite(favorite: FavoriteField): Boolean {
        return try {
            favoriteStore.setFavorite(favorite.type, favorite.fieldPath, false)
            session.replaceFavorites(favoriteStore.favorites().toSet())
            settings = settingsStore.load()
            status = "已取消收藏：${favorite.fieldPath}"
            statusIsError = false
            refresh()
            true
        } catch (e: Exception) {
            fail("取消收藏失败：${e.message}")
            false
        }
    }

    fun clearFavorites() {
        runCatching {
            favoriteStore.clear()
            session.replaceFavorites(emptySet())
            settings = settingsStore.load()
            status = "已清空字段收藏"
            statusIsError = false
        }.onFailure { fail("清空收藏失败：${it.message}") }
        refresh()
    }

    fun setLanguage(language: String?) {
        runCatching {
            settings = settingsStore.update { it.copy(language = language) }
            status = "界面语言偏好已保存"
            statusIsError = false
        }.onFailure { fail("保存语言失败：${it.message}") }
        refresh()
    }

    /** 持久化工作区三栏宽度（文件树 / 编辑器 / 检查器）。 */
    fun setPaneWidths(fileTreeWidth: Float, inspectorWidth: Float) {
        if (!fileTreeWidth.isFinite() || !inspectorWidth.isFinite()) return
        val tree = fileTreeWidth.coerceAtLeast(1f)
        val inspector = inspectorWidth.coerceAtLeast(1f)
        if (settings.fileTreeWidth == tree && settings.inspectorWidth == inspector) return
        settings = settingsStore.update {
            it.copy(fileTreeWidth = tree, inspectorWidth = inspector)
        }
        refresh()
    }

    fun setThemeMode(mode: org.zender.app.ui.theme.ThemeMode) {
        runCatching {
            settings = settingsStore.update { it.copy(themeMode = mode.name) }
            status = when (mode) {
                org.zender.app.ui.theme.ThemeMode.SYSTEM -> "主题：跟随系统"
                org.zender.app.ui.theme.ThemeMode.LIGHT -> "主题：浅色"
                org.zender.app.ui.theme.ThemeMode.DARK -> "主题：深色"
            }
            statusIsError = false
        }.onFailure { fail("保存主题失败：${it.message}") }
        refresh()
    }

    fun setOpenProjectBehavior(behavior: OpenProjectBehavior) {
        runCatching {
            settings = settingsStore.update { it.copy(openProjectBehavior = behavior.name) }
            status = when (behavior) {
                OpenProjectBehavior.ASK -> "打开工程时将询问窗口"
                OpenProjectBehavior.NEW_WINDOW -> "打开工程时默认使用新窗口"
                OpenProjectBehavior.CURRENT_WINDOW -> "打开工程时默认使用当前窗口"
            }
            statusIsError = false
        }.onFailure { fail("保存打开工程设置失败：${it.message}") }
        refresh()
    }

    /** 重新读取共享设置（其他窗口可能刚刚修改过）。 */
    fun reloadSettings() {
        settings = runCatching { settingsStore.load() }.getOrElse {
            logger.warn("重新读取设置失败", it)
            settings
        }
        refresh()
    }

    fun setShowWarpType(show: Boolean) {
        runCatching {
            settings = settingsStore.update { it.copy(showWarpType = show) }
            status = if (show) "字段视图将显示 WarpType" else "字段视图已隐藏 WarpType"
            statusIsError = false
        }.onFailure { fail("保存 WarpType 设置失败：${it.message}") }
        refresh()
    }

    fun setLocalizationInputLanguage(language: String, enabled: Boolean) {
        runCatching {
            settings = settingsStore.update { it.withLocalizationInputLanguage(language, enabled) }
            status = "本地化输入语言已更新"
            statusIsError = false
        }.onFailure { fail("保存本地化语言设置失败：${it.message}") }
        refresh()
    }

    fun removeRecentPack(path: String) {
        runCatching {
            packHistoryStore.remove(Path.of(path))
            settings = settingsStore.load()
            status = "已移除最近数据包：$path"
            statusIsError = false
        }.onFailure { fail("移除最近数据包失败：${it.message}") }
        refresh()
    }

    fun removeRecentProject(path: String) {
        runCatching {
            settings = settingsStore.update { current ->
                current.copy(recentProjects = current.recentProjects.filterNot { it == path })
            }
            status = "已移除最近工程：$path"
            statusIsError = false
        }.onFailure { fail("移除最近工程失败：${it.message}") }
        refresh()
    }

    // ---------------------------------------------------------------- 数据包组合

    fun mainPacks(): List<MainPackConfig> = settings.mainPacks

    fun extraPacks(): List<ExtraPackConfig> = settings.extraPacks

    /** 读取数据包目录的 `PackInfo.json`；目录不存在或文件非法时返回 null。 */
    fun packInfoAt(path: Path): PackInfo? {
        val normalized = fileStore.normalize(path)
        if (!fileStore.isDirectory(normalized)) return null
        return PackInfoReader.read(fileStore, normalized.resolve(PackInfoReader.FILE_NAME)).info
    }

    /** 设置 CSTI / CSFF 主包目录；目录的 PackInfo.Name 必须与主包标识一致。 */
    fun setMainPackPath(kind: String, path: Path): Boolean {
        val normalizedKind = kind.trim().uppercase()
        if (normalizedKind !in MAIN_PACK_KINDS) {
            return fail("未知主包：$kind（只支持 CSTI / CSFF）")
        }
        val normalized = fileStore.normalize(path)
        val info = packInfoAt(normalized)
            ?: return fail("目录中没有有效的 PackInfo.json：$normalized")
        if (info.normalizedName != normalizedKind) {
            return fail("该目录的 PackInfo.Name 是 ${info.name}，与主包 $normalizedKind 不匹配")
        }
        runCatching {
            settings = settingsStore.update { it.withMainPackPath(normalizedKind, normalized) }
            status = "已配置主包 $normalizedKind：$normalized"
            statusIsError = false
        }.onFailure { fail("保存主包路径失败：${it.message}") }
        refresh()
        return !statusIsError
    }

    /** 导入额外数据包，并指定它与哪个主包、哪个框架一起工作。 */
    fun addExtraPack(
        name: String,
        path: Path,
        mainPackKinds: Collection<String>,
        framework: FrameworkId,
    ): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return fail("额外数据包名称不能为空")
        val kinds = normalizeMainPackKinds(mainPackKinds)
        if (kinds.isEmpty()) return fail("额外数据包至少要关联一个主包（CSTI / CSFF）")
        val normalized = fileStore.normalize(path)
        val info = extraPackInfo(normalized, framework)
            ?: return fail("目录不是 ${framework.name} 额外数据包：$normalized")
        runCatching {
            settings = settingsStore.update {
                it.withExtraPack(
                    ExtraPackConfig(
                        id = normalized.toString(),
                        name = trimmedName,
                        path = normalized.toString(),
                        mainPackKinds = kinds,
                        framework = framework.name,
                    ),
                )
            }
            status = "已导入额外数据包：$trimmedName（${info.name} ${info.version}）"
            statusIsError = false
        }.onFailure { fail("保存额外数据包失败：${it.message}") }
        refresh()
        return !statusIsError
    }

    fun updateExtraPack(
        id: String,
        name: String,
        path: Path,
        mainPackKinds: Collection<String>,
        framework: FrameworkId,
        enabled: Boolean,
    ): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return fail("额外数据包名称不能为空")
        val kinds = normalizeMainPackKinds(mainPackKinds)
        if (kinds.isEmpty()) return fail("额外数据包至少要关联一个主包（CSTI / CSFF）")
        val normalized = fileStore.normalize(path)
        if (extraPackInfo(normalized, framework) == null) {
            return fail("目录不是 ${framework.name} 额外数据包：$normalized")
        }
        runCatching {
            settings = settingsStore.update { current ->
                current.withExtraPack(
                    ExtraPackConfig(
                        id = current.extraPacks.firstOrNull { it.id == id }?.id ?: id,
                        name = trimmedName,
                        path = normalized.toString(),
                        mainPackKinds = kinds,
                        framework = framework.name,
                        enabled = enabled,
                    ),
                )
            }
            status = "已更新额外数据包：$trimmedName"
            statusIsError = false
        }.onFailure { fail("保存额外数据包失败：${it.message}") }
        refresh()
        return !statusIsError
    }

    private fun normalizeMainPackKinds(values: Collection<String>): List<String> =
        values.map { it.trim().uppercase() }
            .filter { it in MAIN_PACK_KINDS }
            .distinct()

    /**
     * 分析目录格式与元信息（只读取少量索引文件，不加载整个数据包）。
     */
    fun analyzePackDirectory(path: Path): PackDirectoryAnalysis {
        val normalized = fileStore.normalize(path)
        if (!fileStore.isDirectory(normalized)) {
            return PackDirectoryAnalysis(
                path = normalized.toString(),
                error = "目录不存在：$normalized",
            )
        }

        val packInfoFile = normalized.resolve(PackInfoReader.FILE_NAME)
        if (fileStore.isRegularFile(packInfoFile)) {
            val info = PackInfoReader.read(fileStore, packInfoFile).info
            if (info != null) {
                val typeCount = fileStore.list(normalized.resolve("TypeStruct"))
                    .count {
                        fileStore.isRegularFile(it) &&
                            it.fileName.toString().endsWith(".json", ignoreCase = true)
                    }
                return PackDirectoryAnalysis(
                    path = normalized.toString(),
                    format = "MC",
                    framework = FrameworkId.MC,
                    info = info,
                    typeCount = typeCount,
                    mainPackKind = info.normalizedName.takeIf { it in MAIN_PACK_KINDS },
                )
            }
        }

        val mlTypeDirectory = normalized.resolve("ScriptableObjectTypeJsonData")
        if (fileStore.isDirectory(mlTypeDirectory)) {
            val typeCount = fileStore.list(mlTypeDirectory).count {
                fileStore.isRegularFile(it) &&
                    it.fileName.toString().endsWith(".json", ignoreCase = true)
            }
            return PackDirectoryAnalysis(
                path = normalized.toString(),
                format = "ML",
                framework = FrameworkId.ML,
                info = PackInfo(
                    name = normalized.fileName?.toString()?.ifBlank { "ML Extra" } ?: "ML Extra",
                ),
                typeCount = typeCount,
            )
        }

        return PackDirectoryAnalysis(
            path = normalized.toString(),
            error = "无法识别的数据包格式：需要 PackInfo.json（MC）或 " +
                "ScriptableObjectTypeJsonData 目录（ML）",
        )
    }

    /**
     * 额外数据包没有统一元数据：MC 用 `PackInfo.json`，ML 用 ModCore-ModEditor 的
     * `ScriptableObjectTypeJsonData` 目录。返回 null 表示该目录不符合指定框架的格式。
     */
    fun extraPackInfo(path: Path, framework: FrameworkId): PackInfo? {
        val normalized = fileStore.normalize(path)
        if (!fileStore.isDirectory(normalized)) return null
        return when (framework) {
            FrameworkId.MC -> packInfoAt(normalized)
            FrameworkId.ML -> {
                val marker = normalized.resolve("ScriptableObjectTypeJsonData")
                if (!fileStore.isDirectory(marker)) return null
                val name = normalized.fileName?.toString()?.ifBlank { "ML Extra" } ?: "ML Extra"
                // 该格式没有 PackInfo；用目录名作为显示名。
                PackInfo(name = name)
            }
        }
    }

    fun setExtraPackEnabled(id: String, enabled: Boolean): Boolean {
        val existing = settings.extraPacks.firstOrNull { it.id == id } ?: return false
        settings = settingsStore.update { it.withExtraPack(existing.copy(enabled = enabled)) }
        refresh()
        return true
    }

    fun removeExtraPack(id: String): Boolean {
        runCatching {
            settings = settingsStore.update { it.withoutExtraPack(id) }
            status = "已移除额外数据包"
            statusIsError = false
        }.onFailure { fail("移除额外数据包失败：${it.message}") }
        refresh()
        return !statusIsError
    }

    /**
     * 加载某个主包及其所有启用的、适用于 [framework] 的额外包，并让当前工程使用该组合。
     */
    suspend fun applyMainPack(mainPackKind: String, framework: FrameworkId): Boolean {
        val normalizedKind = normalizeMainPackKind(mainPackKind)
            ?: return fail("未知主包：$mainPackKind")
        val mainPath = settings.mainPack(normalizedKind)?.path
            ?.takeIf { it.isNotBlank() }
            ?: return fail("尚未配置 $normalizedKind 主包目录")
        val mainRoot = runCatching { Path.of(mainPath) }.getOrNull()
            ?: return fail("主包路径不合法：$mainPath")
        val extras = settings.extraPacks.filter {
            it.enabled &&
                it.belongsTo(normalizedKind) &&
                it.framework.equals(framework.name, ignoreCase = true)
        }
        val extraRoots = extras.mapNotNull { runCatching { Path.of(it.path) }.getOrNull() }
        val target = ProjectPackTarget(
            mainPackKind = normalizedKind,
            mainPackPath = mainRoot.toString(),
            framework = framework.name,
            extraPackPaths = extras.map { it.path },
        )
        val ok = loadPackCombined(mainRoot, extraRoots, target)
        if (ok) {
            val extraLabel = extras.joinToString("、") { it.displayName }
                .takeIf { it.isNotBlank() }
                ?.let { " + $it" }
                .orEmpty()
            status = buildString {
                append("当前数据包组合：").append(normalizedKind)
                    .append("（").append(framework.name).append("）")
                    .append(extraLabel)
                if (projectRoot != null) append(" · 已关联到当前工程")
            }
            statusIsError = false
            refresh()
        }
        return ok
    }

    private fun normalizeMainPackKind(value: String): String? =
        value.trim().uppercase().takeIf { it in MAIN_PACK_KINDS }

    // ---------------------------------------------------------------- 多窗口

    fun openInNewWindow(documentId: DocumentId): Boolean {
        if (documentId !in targets) return fail("文档未打开")
        if (windowDocuments.values.any { it == documentId }) {
            status = "该文档已经在独立窗口中打开"
            statusIsError = false
            refresh()
            return true
        }
        val mode = viewModes[documentId] ?: DocumentViewMode.FIELD
        val content = WindowContent(
            key = WindowContentKey.Document(documentId),
            title = documentTitle(documentId),
            viewMode = mode,
        )
        val geometry = settings.detachedWindows
            .firstOrNull { it.documentId == documentId.value }
            ?.toGeometry()
            ?: WindowGeometry()
        val window = windowManager.open(content, geometry)
        windowDocuments[window.id] = documentId
        persistDetachedWindows()
        status = "已在新窗口打开：${documentTitle(documentId)}"
        statusIsError = false
        refresh()
        return true
    }

    fun updateDetachedGeometry(windowId: WindowId, geometry: WindowGeometry) {
        if (windowManager.updateGeometry(windowId, geometry)) {
            refresh()
        }
    }

    fun closeDetachedWindow(windowId: WindowId) {
        windowManager.close(windowId)
        windowDocuments.remove(windowId)
        persistDetachedWindows()
        refresh()
    }

    fun documentTitle(documentId: DocumentId): String =
        targets[documentId]?.title
            ?: session.documentSnapshot(documentId)?.title
            ?: documentId.value

    private fun restoreDetachedWindows() {
        val pending = settings.detachedWindows
        if (pending.isEmpty()) return
        pending.forEach { entry ->
            val documentId = runCatching { DocumentId(entry.documentId) }.getOrNull()
                ?: return@forEach
            if (documentId !in targets && !openDocumentByIdInternal(documentId)) return@forEach
            if (windowDocuments.values.any { it == documentId }) return@forEach
            val mode = parseViewMode(entry.viewMode)
            viewModes[documentId] = mode
            val content = WindowContent(
                key = WindowContentKey.Document(documentId),
                title = documentTitle(documentId),
                viewMode = mode,
            )
            val window = windowManager.open(content, entry.toGeometry())
            windowDocuments[window.id] = documentId
        }
        persistDetachedWindows()
        refresh()
    }

    private fun persistDetachedWindows() {
        val entries = windowManager.currentState.windows.mapNotNull { window ->
            val documentId = windowDocuments[window.id]
                ?: (window.content.key as? WindowContentKey.Document)?.documentId
                ?: return@mapNotNull null
            DetachedWindowSettings(
                documentId = documentId.value,
                x = window.geometry.x,
                y = window.geometry.y,
                width = window.geometry.width,
                height = window.geometry.height,
                maximized = window.geometry.maximized,
                viewMode = (viewModes[documentId] ?: window.content.viewMode).name,
            )
        }
        runCatching {
            settings = settingsStore.update { it.withDetachedWindows(entries) }
        }.onFailure { logger.warn("保存多窗口布局失败", it) }
    }

    fun persistLayout() {
        persistDetachedWindows()
    }

    private fun parseViewMode(value: String): DocumentViewMode =
        runCatching { DocumentViewMode.valueOf(value) }.getOrDefault(DocumentViewMode.FIELD)

    private fun DetachedWindowSettings.toGeometry(): WindowGeometry = WindowGeometry(
        x = x,
        y = y,
        width = width.takeIf { it > 0f } ?: 1200f,
        height = height.takeIf { it > 0f } ?: 800f,
        maximized = maximized,
    )

    // ---------------------------------------------------------------- 内部

    private fun requireEditableTarget(documentId: DocumentId): DocumentTarget? {
        val target = targets[documentId] ?: run {
            fail("文档未打开")
            return null
        }
        if (target.readOnly) {
            fail("数据包对象只读；请在工程中新建对象后再编辑")
            return null
        }
        return target
    }

    private fun fail(message: String): Boolean {
        status = message
        statusIsError = true
        refresh()
        return false
    }

    private fun refresh() {
        _state.value = WorkspaceUiState(
            packRoot = packRoot,
            pack = pack,
            projectRoot = projectRoot,
            project = project,
            session = session.currentState,
            viewModes = viewModes.toMap(),
            targets = targets.toMap(),
            originalDocuments = originalDocuments.toMap(),
            rawDocuments = rawDocuments.toMap(),
            fieldOptions = fieldOptions,
            screen = screen,
            validation = validation,
            diagnostics = diagnostics,
            settings = settings,
            picker = picker,
            starMapFocus = starMapFocus,
            detachedWindows = windowManager.currentState.windows.mapNotNull { window ->
                val documentId = windowDocuments[window.id] ?: return@mapNotNull null
                DetachedWindowUi(
                    id = window.id,
                    documentId = documentId,
                    title = documentTitle(documentId),
                    geometry = window.geometry,
                    viewMode = viewModes[documentId] ?: window.content.viewMode,
                )
            },
            status = status,
            statusIsError = statusIsError,
            busy = busy,
            lastSavedAt = lastSavedAt,
        )
    }

    companion object {
        private const val OBJECT_PREFIX = "object:"
        private const val GSM_PREFIX = "patch:gsm:"
        private const val DOM_PREFIX = "patch:dom:"
        private const val PACK_PREFIX = "pack:"
        private const val MAX_RECENT_PICKER_ITEMS = 24

        /** 支持的主数据包标识。 */
        val MAIN_PACK_KINDS: Set<String> = setOf("CSTI", "CSFF")

        fun objectDocumentId(type: String, name: String): DocumentId =
            DocumentId("$OBJECT_PREFIX$type/$name")

        fun gsmDocumentId(targetGuid: String): DocumentId =
            DocumentId("$GSM_PREFIX$targetGuid")

        fun domDocumentId(type: String, dataKey: String): DocumentId =
            DocumentId("$DOM_PREFIX$type/$dataKey")

        fun packDocumentId(type: String, name: String): DocumentId =
            DocumentId("$PACK_PREFIX$type/$name")
    }
}
