package org.zender.app.platform

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogParent
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import java.awt.Window
import java.nio.file.Path

/**
 * 视图层的文件系统选择抽象。
 *
 * 只用于「文件/目录路径」，对象与资源引用由后续的 Compose 选择器窗口负责。
 * 数据层不依赖该接口，也不接收 [PlatformFile]。
 */
interface FilePicker {

    suspend fun openFile(
        title: String? = null,
        extensions: Set<String>? = null,
        initialDirectory: Path? = null,
    ): Path?

    suspend fun openFiles(
        title: String? = null,
        extensions: Set<String>? = null,
        initialDirectory: Path? = null,
    ): List<Path>

    suspend fun pickDirectory(
        title: String? = null,
        initialDirectory: Path? = null,
    ): Path?

    suspend fun saveFile(
        suggestedName: String,
        title: String? = null,
        defaultExtension: String? = null,
        allowedExtensions: Set<String>? = null,
        initialDirectory: Path? = null,
    ): Path?
}

/**
 * FileKit 实现。
 *
 * [parent] 是触发对话框的窗口。把它传入 [FileKitDialogSettings.parent]，
 * 保证原生对话框作为该窗口的模态子窗口出现，而不是跑到其它窗口后面。
 * 调用方必须在挂起的对话框返回前保持 [parent] 存活。
 */
class FileKitFilePicker(
    private val parent: Window? = null,
) : FilePicker {

    override suspend fun openFile(
        title: String?,
        extensions: Set<String>?,
        initialDirectory: Path?,
    ): Path? = FileKit.openFilePicker(
        type = fileKitType(extensions),
        directory = initialDirectory?.toPlatformFile(),
        dialogSettings = dialogSettings(title),
    )?.file?.toPath()

    override suspend fun openFiles(
        title: String?,
        extensions: Set<String>?,
        initialDirectory: Path?,
    ): List<Path> = FileKit.openFilePicker(
        type = fileKitType(extensions),
        mode = FileKitMode.Multiple(),
        directory = initialDirectory?.toPlatformFile(),
        dialogSettings = dialogSettings(title),
    ).orEmpty().map { it.file.toPath() }

    override suspend fun pickDirectory(
        title: String?,
        initialDirectory: Path?,
    ): Path? = FileKit.openDirectoryPicker(
        directory = initialDirectory?.toPlatformFile(),
        dialogSettings = dialogSettings(title),
    )?.file?.toPath()

    override suspend fun saveFile(
        suggestedName: String,
        title: String?,
        defaultExtension: String?,
        allowedExtensions: Set<String>?,
        initialDirectory: Path?,
    ): Path? = FileKit.openFileSaver(
        suggestedName = suggestedName,
        defaultExtension = normalizeExtension(defaultExtension),
        allowedExtensions = normalizeExtensions(allowedExtensions),
        directory = initialDirectory?.toPlatformFile(),
        dialogSettings = dialogSettings(title),
    )?.file?.toPath()

    private fun dialogSettings(title: String?): FileKitDialogSettings =
        FileKitDialogSettings(
            title = title,
            parent = parent?.let { FileKitDialogParent.awt(it) },
        )
}

private fun fileKitType(extensions: Set<String>?): FileKitType =
    FileKitType.File(normalizeExtensions(extensions))

private fun Path.toPlatformFile(): PlatformFile = PlatformFile(toFile())

/**
 * 统一扩展名写法：去除前导点、转小写，并拒绝空集合或非字母数字内容。
 * `null` 表示不过滤。
 */
internal fun normalizeExtensions(extensions: Set<String>?): Set<String>? {
    if (extensions == null) return null
    val normalized = extensions
        .asSequence()
        .map { it.trim().removePrefix(".").lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()
    require(normalized.isNotEmpty()) { "扩展名集合不能为空或全部无效" }
    require(normalized.all { extension -> extension.all { it.isLetterOrDigit() } }) {
        "扩展名只能包含字母和数字：$normalized"
    }
    return normalized
}

internal fun normalizeExtension(extension: String?): String? =
    extension
        ?.trim()
        ?.removePrefix(".")
        ?.takeIf { it.isNotEmpty() }
        ?.lowercase()
