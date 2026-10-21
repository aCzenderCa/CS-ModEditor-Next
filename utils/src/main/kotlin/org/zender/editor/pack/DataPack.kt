package org.zender.editor.pack

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.field.FieldProjectionService
import org.zender.editor.io.AtomicFileStore
import org.zender.editor.io.FileStore
import org.zender.editor.io.resolveWithin
import org.zender.editor.localization.LocalizationTable
import org.zender.editor.localization.SimpCnLoader
import org.zender.editor.log.Log
import org.zender.editor.log.Logger
import org.zender.editor.objects.ObjectEntry
import org.zender.editor.objects.ObjectIndex
import org.zender.editor.objects.ObjectIndexBuilder
import org.zender.editor.objects.ObjectIndexReader
import org.zender.editor.objects.ObjectScanner
import org.zender.editor.objects.ReferenceIndex
import org.zender.editor.objects.ReferenceRule
import org.zender.editor.picker.ReferencePickerService
import org.zender.editor.resource.ResourceInventory
import org.zender.editor.resource.ResourceInventoryScanner
import org.zender.editor.schema.DefaultValueResolver
import org.zender.editor.schema.FieldDefinition
import org.zender.editor.schema.SchemaLoadOptions
import org.zender.editor.schema.SchemaRepository
import org.zender.editor.schema.TypeSchema
import org.zender.editor.schema.TypeStructDefinition
import java.nio.file.Path

data class PackLoadOptions(
    val loadLocalization: Boolean = true,
    val scanResources: Boolean = true,
    val schemaOptions: SchemaLoadOptions = SchemaLoadOptions(),
)

/** 参与合并加载的一层数据包。[root] 为该层的目录，[info] 为其 `PackInfo.json`。 */
data class PackLayer(
    val root: Path,
    val info: PackInfo,
)

/**
 * 已加载的只读数据包。
 *
 * 可以只包含主包，也可以由主包 + 若干额外数据包合并而成；
 * 组合数据层的各个服务，供视图层/Mod 工程层直接使用；不提供任何写操作。
 */
class DataPack(
    val root: Path,
    val info: PackInfo,
    val schemaRepository: SchemaRepository,
    val objectIndex: ObjectIndex,
    val referenceRule: ReferenceRule,
    val referenceIndex: ReferenceIndex,
    val localization: LocalizationTable,
    val resources: ResourceInventory,
    val picker: ReferencePickerService,
    val defaultValueResolver: DefaultValueResolver,
    val fieldProjection: FieldProjectionService,
    val diagnostics: List<Diagnostic> = emptyList(),
    private val fileStore: FileStore = AtomicFileStore(),
    /** 主包在前，额外数据包按加载顺序在后。 */
    val layers: List<PackLayer> = listOf(PackLayer(root, info)),
) {

    val typeNames: Set<String> get() = schemaRepository.typeNames

    fun schema(typeName: String): TypeSchema? = schemaRepository.schema(typeName)

    fun objectEntry(type: String, name: String): ObjectEntry? = objectIndex.find(type, name)

    fun objectEntryByGuid(uniqueId: String): ObjectEntry? = objectIndex.findByGuid(uniqueId)

    /** 读取对象文件原文。仅索引条目（没有数据文件）会抛出 [IllegalArgumentException]。 */
    fun readObjectText(entry: ObjectEntry): String {
        val relativePath = requireNotNull(entry.relativePath) {
            "对象 ${entry.type}/${entry.name} 只有索引、没有数据文件"
        }
        val base = entry.sourceRoot ?: root
        return fileStore.readText(fileStore.resolveWithin(base, relativePath))
    }

    /** 读取对象文件并解析为文档。解析错误由 [JsonDocument.parseOrThrow] 抛出。 */
    fun readObject(entry: ObjectEntry): JsonDocument =
        JsonDocument.parseOrThrow(readObjectText(entry))
}

data class PackLoadResult(
    val pack: DataPack? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    val isSuccess: Boolean get() = pack != null

    val errors: List<Diagnostic>
        get() = diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }

    fun packOrThrow(): DataPack =
        pack ?: throw IllegalStateException(
            "数据包加载失败：" + errors.joinToString("；") { it.toString() },
        )
}

/**
 * 数据包加载入口。
 *
 * 加载顺序与游戏运行时一致：PackInfo → 对象扫描/索引 → Schema → 本地化 → 资源。
 * 单个文件损坏时记录诊断并继续，只有缺少 PackInfo 等致命问题才返回失败。
 */
class DataPackLoader(
    private val fileStore: FileStore = AtomicFileStore(),
    private val logger: Logger = Log.getLogger("pack"),
    /** 提供后在加载成功时记录数据包目录；为 null 时不做任何持久化。 */
    private val historyStore: PackHistoryStore? = null,
) {

    fun load(
        root: Path,
        options: PackLoadOptions = PackLoadOptions(),
    ): PackLoadResult = loadCombined(root, emptyList(), options)

    /**
     * 加载主数据包，并按顺序叠加 [extraRoots]。
     *
     * 额外数据包按层合并：Schema / 对象索引 / 本地化 / 资源中同名项由后面的层覆盖；
     * 额外包无效时只记录警告并跳过，不影响主包。
     */
    fun loadCombined(
        root: Path,
        extraRoots: List<Path>,
        options: PackLoadOptions = PackLoadOptions(),
    ): PackLoadResult {
        val normalizedRoot = fileStore.normalize(root)
        if (!fileStore.isDirectory(normalizedRoot)) {
            return PackLoadResult(
                diagnostics = listOf(
                    Diagnostic(
                        DiagnosticSeverity.ERROR,
                        "数据包目录不存在：$normalizedRoot",
                    ),
                ),
            )
        }

        val diagnostics = mutableListOf<Diagnostic>()
        val mainLayer = loadLayer(normalizedRoot, options, diagnostics)
            ?: return PackLoadResult(diagnostics = diagnostics.toList())

        val layers = mutableListOf(mainLayer)
        val seenRoots = hashSetOf(normalizedRoot)
        extraRoots.forEach { extraRoot ->
            val normalizedExtra = fileStore.normalize(extraRoot)
            if (!seenRoots.add(normalizedExtra)) return@forEach
            val layerDiagnostics = mutableListOf<Diagnostic>()
            var layer = loadLayer(normalizedExtra, options, layerDiagnostics)
            if (layer == null) {
                // 非标准格式：ModCore-ModEditor 导出的 ML 额外数据（没有 PackInfo.json）。
                val alternateDiagnostics = mutableListOf<Diagnostic>()
                layer = loadAlternateLayer(normalizedExtra, options, alternateDiagnostics)
                if (layer != null) {
                    layerDiagnostics.clear()
                    layerDiagnostics += alternateDiagnostics
                }
            }
            if (layer == null) {
                diagnostics += Diagnostic(
                    DiagnosticSeverity.WARNING,
                    "额外数据包无效，已跳过：$normalizedExtra",
                )
                diagnostics += layerDiagnostics
                return@forEach
            }
            diagnostics += layerDiagnostics
            layers += layer
        }

        val objectIndex = mergeObjectIndexes(layers, diagnostics)
        val schemaRepository = SchemaRepository.merge(
            layers = layers.map { it.schema },
            referenceTypes = objectIndex.referenceTypes,
            options = options.schemaOptions,
        )
        val localization = mergeLocalization(layers)
        val resources = mergeResources(layers)
        layers.flatMap { it.diagnostics }.forEach { diagnostics += it }

        val referenceRule = ReferenceRule.from(objectIndex)
        val referenceIndex = ReferenceIndex(objectIndex, referenceRule, resources)
        val picker = ReferencePickerService(
            objectIndex = objectIndex,
            resources = resources,
            rule = referenceRule,
            referenceIndex = referenceIndex,
        )
        val defaultValueResolver = DefaultValueResolver(schemaRepository)
        val fieldProjection = FieldProjectionService(schemaRepository, defaultValueResolver)

        diagnostics.forEach { diagnostic ->
            when (diagnostic.severity) {
                DiagnosticSeverity.ERROR -> logger.error(diagnostic.toString())
                DiagnosticSeverity.WARNING -> logger.warn(diagnostic.toString())
            }
        }

        val pack = DataPack(
            root = normalizedRoot,
            info = mainLayer.info,
            schemaRepository = schemaRepository,
            objectIndex = objectIndex,
            referenceRule = referenceRule,
            referenceIndex = referenceIndex,
            localization = localization,
            resources = resources,
            picker = picker,
            defaultValueResolver = defaultValueResolver,
            fieldProjection = fieldProjection,
            diagnostics = diagnostics.toList(),
            fileStore = fileStore,
            layers = layers.map { PackLayer(it.root, it.info) },
        )
        // 历史记录失败不影响数据包本身可用。
        val history = historyStore
        if (history != null) {
            runCatching { history.record(pack) }
                .onFailure { error ->
                    logger.warn("记录数据包目录失败：${error.message}", error)
                }
        }
        return PackLoadResult(pack = pack, diagnostics = diagnostics.toList())
    }

    private data class LayerData(
        val root: Path,
        val info: PackInfo,
        val index: ObjectIndex,
        val schema: SchemaRepository,
        val localization: LocalizationTable,
        val resources: ResourceInventory,
        val diagnostics: List<Diagnostic>,
    )

    private fun loadLayer(
        root: Path,
        options: PackLoadOptions,
        inheritedDiagnostics: MutableList<Diagnostic>? = null,
    ): LayerData? {
        if (!fileStore.isDirectory(root)) {
            inheritedDiagnostics?.add(
                Diagnostic(DiagnosticSeverity.ERROR, "数据包目录不存在：$root"),
            )
            return null
        }
        val diagnostics = mutableListOf<Diagnostic>()

        val infoResult = PackInfoReader.read(fileStore, root.resolve(PackInfoReader.FILE_NAME))
        infoResult.error?.let { diagnostics += it }
        val info = infoResult.info
        if (info == null) {
            inheritedDiagnostics?.addAll(diagnostics)
            return null
        }

        val scan = ObjectScanner.scan(fileStore, root)
        diagnostics += scan.diagnostics
        val scannedEntries = scan.entries.map { it.copy(sourceRoot = root) }

        val guidIndex = ObjectIndexReader.readGuidIndex(fileStore, root)
        diagnostics += guidIndex.diagnostics

        val nameIndex = ObjectIndexReader.readNameIndex(fileStore, root)
        diagnostics += nameIndex.diagnostics

        val indexBuild = ObjectIndexBuilder.build(
            scanned = scannedEntries,
            guidEntries = guidIndex.entries,
            nameEntries = nameIndex.entries,
            guidTypes = guidIndex.types,
            nameTypes = nameIndex.types,
        )
        diagnostics += indexBuild.diagnostics
        val objectIndex = indexBuild.index

        val schemaLoad = SchemaRepository.load(
            fileStore = fileStore,
            packRoot = root,
            referenceTypes = objectIndex.referenceTypes,
            options = options.schemaOptions,
        )
        diagnostics += schemaLoad.diagnostics

        val localization = if (options.loadLocalization) {
            val local = SimpCnLoader.load(fileStore, root.resolve(SimpCnLoader.FILE_NAME))
            diagnostics += local.diagnostics
            local.table
        } else {
            LocalizationTable.EMPTY
        }

        val resources = if (options.scanResources) {
            val resourceScan = ResourceInventoryScanner.scan(fileStore, root)
            diagnostics += resourceScan.diagnostics
            ResourceInventory(
                resourceScan.inventory.entries.map { it.copy(sourceRoot = root) },
            )
        } else {
            ResourceInventory.EMPTY
        }

        return LayerData(
            root = root,
            info = info,
            index = objectIndex,
            schema = schemaLoad.repository,
            localization = localization,
            resources = resources,
            diagnostics = diagnostics.toList(),
        )
    }

    /**
     * 读取 ModCore-ModEditor 导出的 ML 额外数据格式：
     *
     * - `ScriptableObjectTypeJsonData/<Type>.json`：`字段 → 类型` 映射；
     * - `UniqueIDScriptableBaseJsonData/CommonBase/<Type>.json`：BaseJson 模板；
     * - `ScriptableObjectJsonDataWithWarpLitAllInOne/<Type>/<Name>.json`：对象数据；
     * - `ScriptableObjectObjectName/<Type>.txt`：对象名列表（按 Name 索引引用）。
     *
     * 返回 null 表示目录不是该格式。
     */
    private fun loadAlternateLayer(
        root: Path,
        options: PackLoadOptions,
        diagnostics: MutableList<Diagnostic>,
    ): LayerData? {
        val typeDirectory = root.resolve("ScriptableObjectTypeJsonData")
        if (!fileStore.isDirectory(typeDirectory)) return null

        val typeStructs = LinkedHashMap<String, TypeStructDefinition>()
        for (file in fileStore.list(typeDirectory)) {
            if (!fileStore.isRegularFile(file) || !isJsonFile(file)) continue
            val typeName = fileStem(file)
            if (typeName.isBlank()) continue
            val relative = relativePath(root, file)
            val parsed = JsonDocument.parse(fileStore.readText(file))
            parsed.issues.forEach {
                diagnostics += Diagnostic(DiagnosticSeverity.WARNING, it.message, relative)
            }
            val objectNode = parsed.document?.root as? JsonNode.ObjectNode
            if (objectNode == null) {
                diagnostics += Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "额外数据包类型定义不是合法 JSON 对象",
                    relative,
                )
                continue
            }
            val fields = objectNode.members.mapNotNull { member ->
                val typeNameValue = (member.value as? JsonNode.StringNode)?.value?.trim()
                if (typeNameValue.isNullOrEmpty()) {
                    diagnostics += Diagnostic(
                        DiagnosticSeverity.WARNING,
                        "类型 $typeName 的字段 ${member.name} 不是字符串类型",
                        relative,
                    )
                    null
                } else {
                    FieldDefinition(member.name, typeNameValue)
                }
            }
            typeStructs[typeName] = TypeStructDefinition(typeName, fields)
        }
        if (typeStructs.isEmpty()) return null

        val templates = LinkedHashMap<String, JsonNode.ObjectNode>()
        val templateDirectory = root.resolve("UniqueIDScriptableBaseJsonData/CommonBase")
        for (file in fileStore.list(templateDirectory)) {
            if (!fileStore.isRegularFile(file) || !isJsonFile(file)) continue
            val typeName = fileStem(file)
            val parsed = JsonDocument.parse(fileStore.readText(file))
            val objectNode = parsed.document?.root as? JsonNode.ObjectNode
            if (objectNode != null) templates[typeName] = objectNode
        }

        // AllInOne 下的 Base*.json 只是模板，不进入对象索引；CommonBase 缺失时用它补模板。
        val allInOneRoot = root.resolve("ScriptableObjectJsonDataWithWarpLitAllInOne")
        for (typeDirectoryEntry in fileStore.list(allInOneRoot)) {
            if (!fileStore.isDirectory(typeDirectoryEntry)) continue
            val typeName = typeDirectoryEntry.fileName.toString()
            if (typeName in templates) continue
            val templateFile = fileStore.walkFiles(typeDirectoryEntry)
                .firstOrNull { isJsonFile(it) }
                ?: continue
            val parsed = JsonDocument.parse(fileStore.readText(templateFile))
            val objectNode = parsed.document?.root as? JsonNode.ObjectNode
            if (objectNode != null) templates[typeName] = objectNode
        }

        // ObjectName 列表只用于标记类型的引用语义，不生成对象。
        val nameIndexTypes = LinkedHashSet<String>()
        val nameDirectory = root.resolve("ScriptableObjectObjectName")
        for (file in fileStore.list(nameDirectory)) {
            if (!fileStore.isRegularFile(file)) continue
            if (!file.fileName.toString().endsWith(".txt", ignoreCase = true)) continue
            nameIndexTypes += fileStem(file)
        }

        val index = ObjectIndex(
            entries = emptyList(),
            issues = emptyList(),
            guidIndexTypes = emptySet(),
            nameIndexTypes = nameIndexTypes,
        )
        val schema = SchemaRepository.fromDefinitions(
            typeStructs = typeStructs,
            templates = templates,
            options = options.schemaOptions,
        )
        return LayerData(
            root = root,
            info = PackInfo(
                name = root.fileName?.toString()?.ifBlank { "ML Extra" } ?: "ML Extra",
            ),
            index = index,
            schema = schema,
            localization = LocalizationTable.EMPTY,
            resources = ResourceInventory.EMPTY,
            diagnostics = diagnostics.toList(),
        )
    }

    private fun isJsonFile(file: Path): Boolean =
        file.fileName.toString().endsWith(".json", ignoreCase = true)

    private fun fileStem(file: Path): String {
        val name = file.fileName.toString()
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    private fun relativePath(root: Path, file: Path): String =
        try {
            root.relativize(file).toString().replace('\\', '/')
        } catch (_: IllegalArgumentException) {
            file.toString()
        }

    private fun mergeObjectIndexes(
        layers: List<LayerData>,
        diagnostics: MutableList<Diagnostic>,
    ): ObjectIndex {
        val entries = LinkedHashMap<Pair<String, String>, ObjectEntry>()
        layers.forEachIndexed { layerIndex, layer ->
            layer.index.entries.forEach { entry ->
                val key = entry.type to entry.name
                val previous = entries.put(key, entry)
                if (previous != null && layerIndex > 0 && previous != entry) {
                    diagnostics += Diagnostic(
                        DiagnosticSeverity.WARNING,
                        "额外数据包覆盖对象：${entry.type}/${entry.name}",
                    )
                }
            }
        }
        val guidTypes = layers.flatMapTo(LinkedHashSet()) { it.index.guidIndexTypes }
        val nameTypes = layers.flatMapTo(LinkedHashSet()) { it.index.nameIndexTypes }
        val issues = layers.flatMap { it.index.issues }
        return ObjectIndex(
            entries = entries.values.sortedWith(compareBy({ it.type }, { it.name })),
            issues = issues,
            guidIndexTypes = guidTypes,
            nameIndexTypes = nameTypes,
        )
    }

    private fun mergeLocalization(layers: List<LayerData>): LocalizationTable {
        val entries = LinkedHashMap<String, org.zender.editor.localization.LocalizationEntry>()
        layers.forEach { layer ->
            layer.localization.entries.forEach { entry -> entries[entry.key] = entry }
        }
        return LocalizationTable(entries.values.toList())
    }

    private fun mergeResources(layers: List<LayerData>): ResourceInventory {
        val entries = LinkedHashMap<Pair<org.zender.editor.resource.ResourceKind, String>, org.zender.editor.resource.ResourceEntry>()
        layers.forEach { layer ->
            layer.resources.entries.forEach { entry -> entries[entry.kind to entry.key] = entry }
        }
        return ResourceInventory(entries.values.toList())
    }
}
