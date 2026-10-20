package org.zender.editor.pack

import org.zender.editor.diagnostics.Diagnostic
import org.zender.editor.diagnostics.DiagnosticSeverity
import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.io.FileStore
import java.nio.file.Path

/**
 * 数据包元信息（`PackInfo.json`）。
 *
 * - [name]：游戏/数据包标识，如 `CSTI`、`CSFF`；
 * - [version]：数据包版本，如 `1.05ae`；
 * - [isMainPack]：是否为主数据包。
 */
data class PackInfo(
    val name: String,
    val version: String = "",
    val isMainPack: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "数据包名称不能为空" }
    }

    /** 用于识别游戏的规范化名称（大小写不敏感）。 */
    val normalizedName: String
        get() = name.trim().uppercase()
}

data class PackInfoParseResult(
    val info: PackInfo? = null,
    val error: Diagnostic? = null,
) {
    val isSuccess: Boolean get() = info != null && error == null

    fun infoOrThrow(): PackInfo =
        info ?: throw IllegalStateException(error?.message ?: "PackInfo 解析失败")
}

/**
 * `PackInfo.json` 读取器。只读取 Name / Version / IsMainPack 三个字段，未知字段忽略。
 */
object PackInfoReader {

    const val FILE_NAME: String = "PackInfo.json"

    fun read(fileStore: FileStore, path: Path): PackInfoParseResult {
        if (!fileStore.exists(path)) {
            return PackInfoParseResult(
                error = Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "缺少 $FILE_NAME",
                    FILE_NAME,
                ),
            )
        }
        val text = try {
            fileStore.readText(path)
        } catch (e: Exception) {
            return PackInfoParseResult(
                error = Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "无法读取 $FILE_NAME：${e.message}",
                    FILE_NAME,
                ),
            )
        }
        return parse(text)
    }

    fun parse(text: String): PackInfoParseResult {
        val parsed = JsonDocument.parse(text)
        val document = parsed.document
            ?: return PackInfoParseResult(
                error = Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "PackInfo 不是合法 JSON：${parsed.error?.message}",
                    FILE_NAME,
                ),
            )
        val root = document.root as? JsonNode.ObjectNode
            ?: return PackInfoParseResult(
                error = Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "PackInfo 顶层必须是对象",
                    FILE_NAME,
                ),
            )

        val nameNode = root.get("Name") as? JsonNode.StringNode
            ?: return PackInfoParseResult(
                error = Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "缺少字符串字段 Name",
                    FILE_NAME,
                ),
            )
        if (nameNode.value.isBlank()) {
            return PackInfoParseResult(
                error = Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "Name 不能为空",
                    FILE_NAME,
                ),
            )
        }

        val version = when (val node = root.get("Version")) {
            null, JsonNode.NullNode -> ""
            is JsonNode.StringNode -> node.value
            else -> return PackInfoParseResult(
                error = Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "Version 必须是字符串",
                    FILE_NAME,
                ),
            )
        }
        val isMainPack = when (val node = root.get("IsMainPack")) {
            null, JsonNode.NullNode -> false
            is JsonNode.BooleanNode -> node.value
            else -> return PackInfoParseResult(
                error = Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "IsMainPack 必须是布尔值",
                    FILE_NAME,
                ),
            )
        }

        return PackInfoParseResult(
            info = PackInfo(
                name = nameNode.value.trim(),
                version = version.trim(),
                isMainPack = isMainPack,
            ),
        )
    }
}
