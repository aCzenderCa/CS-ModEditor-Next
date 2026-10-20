package org.zender.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MCP 工具测试夹具。
 *
 * 复用 `utils/src/test/resources/fixtures`；测试会把样例 Mod/数据包复制到临时目录，
 * 避免修改只读夹具。
 */
internal object Fixtures {

    val root: Path by lazy {
        System.getProperty("csmode.fixtures")
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: Path.of("..", "utils", "src", "test", "resources", "fixtures")
                .toAbsolutePath()
                .normalize()
    }

    fun copy(relative: String): Path {
        val source = root.resolve(relative)
        require(Files.isDirectory(source)) { "夹具目录不存在：$source" }
        val target = Files.createTempDirectory("csmode-mcp-")
        copyTree(source, target)
        return target
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    destination.parent?.let { Files.createDirectories(it) }
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}

/** 直接调用工具处理器，并把文本结果解析回 JSON。 */
internal class ToolHarness {

    val workspace = FileMcpWorkspace()
    private val registry = McpToolRegistry(workspace)

    fun call(name: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject {
        val tool = registry.find(name) ?: error("工具不存在：$name")
        val result = tool.execute(arguments)
        assertFalse(result.isError, "工具 $name 返回错误：${result.text}")
        return McpJson.parseToJsonElement(result.text).jsonObject
    }

    fun callExpectError(name: String, arguments: JsonObject = JsonObject(emptyMap())): String {
        val tool = registry.find(name) ?: error("工具不存在：$name")
        val result = tool.execute(arguments)
        assertTrue(result.isError, "工具 $name 预期失败，但返回成功：${result.text}")
        return result.text
    }
}

internal fun ToolHarness.openSampleProject(): Pair<Path, Path> {
    val pack = Fixtures.copy("minipack")
    val project = Fixtures.copy("mods/ml-sample")
    call(
        "open_project",
        buildJsonObject {
            put("path", project.toString())
            put("packPath", pack.toString())
        },
    )
    return pack to project
}
