package org.zender.app.mcp

import org.zender.editor.log.Log
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 把运行中的编辑器 MCP 端点自动注册到 Claude Code（user scope）。
 *
 * 用户打开编辑器即可，不需要手写 `.mcp.json` 或执行 `claude mcp add`。
 * 注册在后台线程进行，失败只写日志，不影响编辑器启动。
 *
 * 可通过环境变量 `CSMODE_MCP_AUTO_REGISTER=0` 关闭自动注册。
 */
object ClaudeMcpRegistrar {

    const val SERVER_NAME: String = "cs-modeditor"

    private val logger = Log.getLogger("mcp")

    fun register(endpoint: String) {
        if (System.getenv("CSMODE_MCP_AUTO_REGISTER") == "0") {
            logger.info("已禁用 MCP 自动注册（CSMODE_MCP_AUTO_REGISTER=0）")
            return
        }

        Thread({
            val claude = resolveClaudeCommand()
            if (claude == null) {
                logger.warn(
                    "未找到 claude CLI，无法自动注册 MCP 端点 $endpoint。" +
                        "请确认 Claude Code 已安装并在 PATH 中。",
                )
                return@Thread
            }

            // 先移除同名旧配置，保证端口/URL 变更后不会残留；不存在时忽略失败。
            run(claude + listOf("mcp", "remove", "-s", "user", SERVER_NAME), ignoreFailure = true)

            val added = run(
                claude + listOf(
                    "mcp", "add",
                    "-s", "user",
                    "-t", "http",
                    SERVER_NAME,
                    endpoint,
                ),
                ignoreFailure = false,
            )
            if (added) {
                logger.info("已将编辑器 MCP 端点注册到 Claude Code：$endpoint")
            } else {
                logger.warn(
                    "自动注册 MCP 端点失败。可手动执行：" +
                        "claude mcp add -s user -t http $SERVER_NAME $endpoint",
                )
            }
        }, "csmode-mcp-register").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 解析 Claude CLI：
     * - Windows 上 `claude` 实际是 npm 的 `.cmd` shim，必须经 `cmd.exe /c` 启动；
     * - GUI 进程的 PATH 可能不包含 npm 目录，因此优先探测常见安装位置。
     */
    private fun resolveClaudeCommand(): List<String>? {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) {
            val candidates = listOfNotNull(
                System.getenv("APPDATA")?.let { Path.of(it, "npm", "claude.cmd") },
                System.getenv("LOCALAPPDATA")?.let { Path.of(it, "pnpm", "claude.cmd") },
                System.getenv("USERPROFILE")?.let { Path.of(it, ".local", "bin", "claude.exe") },
            )
            candidates.firstOrNull(Files::isRegularFile)?.let { executable ->
                return listOf("cmd.exe", "/c", executable.toString())
            }
            // 交给 cmd 按 PATHEXT 查找；找不到时进程会失败并记录日志。
            return listOf("cmd.exe", "/c", "claude")
        }

        val home = System.getProperty("user.home")
        val candidates = listOf(
            Path.of(home, ".local", "bin", "claude"),
            Path.of("/usr/local/bin/claude"),
            Path.of("/opt/homebrew/bin/claude"),
        )
        candidates.firstOrNull(Files::isExecutable)?.let { return listOf(it.toString()) }
        return listOf("claude")
    }

    private fun run(command: List<String>, ignoreFailure: Boolean): Boolean = try {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            logger.warn("claude 命令超时：${command.joinToString(" ")}")
            false
        } else {
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val success = process.exitValue() == 0
            if (!success && !ignoreFailure) {
                logger.warn(
                    "claude 命令失败（exit=${process.exitValue()}）：$output",
                )
            }
            success
        }
    } catch (e: Exception) {
        if (!ignoreFailure) {
            logger.warn("无法执行 claude 命令：${e.message}", e)
        }
        false
    }

    private const val TIMEOUT_SECONDS: Long = 20
}
