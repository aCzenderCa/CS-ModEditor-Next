package org.zender.editor.pack

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.document.JsonDocument
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
import org.zender.editor.schema.SchemaLoadOptions
import org.zender.editor.schema.SchemaRepository
import org.zender.editor.schema.TypeSchema
import java.nio.file.Path

data class PackLoadOptions(
    val loadLocalization: Boolean = true,
    val scanResources: Boolean = true,
    val schemaOptions: SchemaLoadOptions = SchemaLoadOptions(),
)

/**
 * 已加载的只读数据包。
 *
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
        return fileStore.readText(fileStore.resolveWithin(root, relativePath))
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
) {

    fun load(
        root: Path,
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

        val infoResult = PackInfoReader.read(fileStore, normalizedRoot.resolve(PackInfoReader.FILE_NAME))
        infoResult.error?.let { diagnostics += it }
        val info = infoResult.info
        if (info == null) {
            return PackLoadResult(diagnostics = diagnostics)
        }

        val scan = ObjectScanner.scan(fileStore, normalizedRoot)
        diagnostics += scan.diagnostics

        val guidIndex = ObjectIndexReader.readGuidIndex(fileStore, normalizedRoot)
        diagnostics += guidIndex.diagnostics

        val nameIndex = ObjectIndexReader.readNameIndex(fileStore, normalizedRoot)
        diagnostics += nameIndex.diagnostics

        val indexBuild = ObjectIndexBuilder.build(
            scanned = scan.entries,
            guidEntries = guidIndex.entries,
            nameEntries = nameIndex.entries,
            guidTypes = guidIndex.types,
            nameTypes = nameIndex.types,
        )
        diagnostics += indexBuild.diagnostics
        val objectIndex = indexBuild.index

        val schemaLoad = SchemaRepository.load(
            fileStore = fileStore,
            packRoot = normalizedRoot,
            referenceTypes = objectIndex.referenceTypes,
            options = options.schemaOptions,
        )
        diagnostics += schemaLoad.diagnostics
        val schemaRepository = schemaLoad.repository

        val localization = if (options.loadLocalization) {
            val local = SimpCnLoader.load(
                fileStore,
                normalizedRoot.resolve(SimpCnLoader.FILE_NAME),
            )
            diagnostics += local.diagnostics
            local.table
        } else {
            LocalizationTable.EMPTY
        }

        val resources = if (options.scanResources) {
            val resourceScan = ResourceInventoryScanner.scan(fileStore, normalizedRoot)
            diagnostics += resourceScan.diagnostics
            resourceScan.inventory
        } else {
            ResourceInventory.EMPTY
        }

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
            info = info,
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
        )
        return PackLoadResult(pack = pack, diagnostics = diagnostics.toList())
    }
}
