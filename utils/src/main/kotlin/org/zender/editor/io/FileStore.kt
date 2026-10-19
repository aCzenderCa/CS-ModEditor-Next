package org.zender.editor.io

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * 文件系统异常。数据层统一使用该异常包装 IO 错误，避免调用方依赖具体的 NIO 异常类型。
 */
class FileStoreException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * 数据层文件访问抽象。
 *
 * - 所有路径在进入实现前都会做 `toAbsolutePath().normalize()`；
 * - 目录列表顺序稳定（按路径字符串排序），便于测试与可重复构建；
 * - 读取不存在的路径会抛出 [FileStoreException]，列表类方法返回空集合。
 */
interface FileStore {
    fun exists(path: Path): Boolean

    fun isDirectory(path: Path): Boolean

    fun readText(path: Path): String

    fun readBytes(path: Path): ByteArray

    /** 写入文本；父目录不存在时自动创建，写入过程是原子的。 */
    fun writeText(path: Path, text: String)

    /** 写入字节；父目录不存在时自动创建，写入过程是原子的。 */
    fun writeBytes(path: Path, bytes: ByteArray)

    fun createDirectories(path: Path)

    fun delete(path: Path): Boolean

    fun deleteRecursively(path: Path)

    /** 列出直接子项（文件与目录）。路径不存在时返回空列表。 */
    fun list(path: Path): List<Path>

    /** 递归列出普通文件。路径不存在时返回空列表。 */
    fun walkFiles(path: Path): List<Path>

    fun normalize(path: Path): Path
}

/**
 * 基于 `java.nio.file` 的实现。
 *
 * 写入策略：在目标文件同目录创建临时文件 → 写入并 `force(true)` → 原子替换目标文件。
 * 平台不支持原子移动时退化为 `REPLACE_EXISTING` 的普通移动；任何失败都会清理临时文件，
 * 目标文件的旧内容保持不变。
 */
class AtomicFileStore(
    private val charset: Charset = StandardCharsets.UTF_8,
) : FileStore {

    override fun exists(path: Path): Boolean = Files.exists(normalize(path))

    override fun isDirectory(path: Path): Boolean = Files.isDirectory(normalize(path))

    override fun readText(path: Path): String {
        val bytes = readBytes(path)
        val text = bytes.toString(charset)
        // UTF-8 BOM 不属于文本内容，读取时去除。
        val byteOrderMark = 0xFEFF.toChar()
        return if (text.startsWith(byteOrderMark)) text.substring(1) else text
    }

    override fun readBytes(path: Path): ByteArray {
        val normalized = normalize(path)
        return try {
            Files.readAllBytes(normalized)
        } catch (e: IOException) {
            throw FileStoreException("无法读取文件：$normalized", e)
        }
    }

    override fun writeText(path: Path, text: String) {
        writeBytes(path, text.toByteArray(charset))
    }

    override fun writeBytes(path: Path, bytes: ByteArray) {
        val target = normalize(path)
        val parent = target.parent
        try {
            if (parent != null) Files.createDirectories(parent)
            writeAtomically(target, bytes)
        } catch (e: IOException) {
            throw FileStoreException("无法写入文件：$target", e)
        }
    }

    override fun createDirectories(path: Path) {
        val normalized = normalize(path)
        try {
            Files.createDirectories(normalized)
        } catch (e: IOException) {
            throw FileStoreException("无法创建目录：$normalized", e)
        }
    }

    override fun delete(path: Path): Boolean {
        val normalized = normalize(path)
        return try {
            Files.deleteIfExists(normalized)
        } catch (e: IOException) {
            throw FileStoreException("无法删除：$normalized", e)
        }
    }

    override fun deleteRecursively(path: Path) {
        val normalized = normalize(path)
        if (!Files.exists(normalized)) return
        try {
            Files.walk(normalized).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        } catch (e: IOException) {
            throw FileStoreException("无法递归删除：$normalized", e)
        }
    }

    override fun list(path: Path): List<Path> {
        val normalized = normalize(path)
        if (!Files.isDirectory(normalized)) return emptyList()
        return try {
            Files.list(normalized).use { stream ->
                stream.map { it.toAbsolutePath().normalize() }.sorted().toList()
            }
        } catch (e: IOException) {
            throw FileStoreException("无法列出目录：$normalized", e)
        }
    }

    override fun walkFiles(path: Path): List<Path> {
        val normalized = normalize(path)
        if (!Files.isDirectory(normalized)) return emptyList()
        return try {
            Files.walk(normalized).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .map { it.toAbsolutePath().normalize() }
                    .sorted()
                    .toList()
            }
        } catch (e: IOException) {
            throw FileStoreException("无法递归列出目录：$normalized", e)
        }
    }

    override fun normalize(path: Path): Path = path.toAbsolutePath().normalize()

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        val parent = target.parent
        val temp = parent.resolve(".${target.fileName}.tmp-${UUID.randomUUID()}")
        try {
            FileChannel.open(
                temp,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    channel.write(buffer)
                }
                channel.force(true)
            }
            try {
                Files.move(
                    temp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            // 成功移动后临时文件已不存在；失败时清理残留。
            Files.deleteIfExists(temp)
        }
    }
}

/**
 * 把 [relative] 解析到 [base] 之下，并阻止 `..` 逃逸出基准目录。
 * Mod 工程中的资源、对象路径都应通过该方法拼接。
 */
fun FileStore.resolveWithin(base: Path, relative: String): Path {
    require('\u0000' !in relative) { "路径不能包含 NUL 字符" }
    val normalizedBase = normalize(base)
    val resolved = normalize(normalizedBase.resolve(relative))
    if (!resolved.startsWith(normalizedBase)) {
        throw FileStoreException("路径越界：$relative 不在 $normalizedBase 之下")
    }
    return resolved
}
