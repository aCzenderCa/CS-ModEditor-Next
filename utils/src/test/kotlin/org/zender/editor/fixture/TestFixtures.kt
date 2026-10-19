package org.zender.editor.fixture

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 清单中的一个样例 Mod。
 */
@Serializable
data class SampleMod(
    val id: String,
    val framework: String,
    val game: String,
    val path: String,
    val description: String = "",
)

@Serializable
private data class SampleModManifest(
    val mods: List<SampleMod> = emptyList(),
)

/**
 * 测试夹具入口。
 *
 * 夹具通过 classpath 解析（Gradle 会把 `src/test/resources` 复制到 `build/resources/test`），
 * 不依赖当前工作目录。真实游戏数据包属于可选外部资源，找不到时由调用方决定跳过。
 */
object TestFixtures {

    private const val FIXTURES_ROOT = "fixtures"

    private val json = Json { ignoreUnknownKeys = true }

    val root: Path by lazy {
        val url = checkNotNull(TestFixtures::class.java.classLoader.getResource(FIXTURES_ROOT)) {
            "找不到测试夹具目录：$FIXTURES_ROOT（请确认 src/test/resources 已配置）"
        }
        Paths.get(url.toURI()).toAbsolutePath().normalize()
    }

    /** 解析夹具内的相对路径。 */
    fun fixture(relative: String): Path = root.resolve(relative).normalize()

    /** 迷你数据包根目录。 */
    fun miniPack(): Path = fixture("minipack")

    /** 样例 Mod 清单。 */
    fun sampleMods(): List<SampleMod> {
        val manifestPath = fixture("mods/manifest.json")
        val text = Files.readString(manifestPath)
        return json.decodeFromString<SampleModManifest>(text).mods
    }

    /** 按 id 获取样例 Mod 根目录。 */
    fun sampleMod(id: String): Path {
        val mod = sampleMods().firstOrNull { it.id == id }
            ?: error("清单中不存在样例 Mod：$id，可用：${sampleMods().map { it.id }}")
        return fixture("mods/${mod.path}")
    }

    /** CSTI 真实数据包；本机未安装时返回 null。 */
    val cstiPack: Path? by lazy {
        externalPack(
            game = "csti",
            systemProperty = "csmode.pack.csti",
            environmentVariable = "CS_MODEDITOR_PACK_CSTI",
            fallback = "D:/SteamLibrary/steamapps/common/Card Survival Tropical Island/csti",
        )
    }

    /** CSFF 真实数据包；本机未安装时返回 null。 */
    val csffPack: Path? by lazy {
        externalPack(
            game = "csff",
            systemProperty = "csmode.pack.csff",
            environmentVariable = "CS_MODEDITOR_PACK_CSFF",
            fallback = "D:/SteamLibrary/steamapps/common/Card Survival Fantasy Forest/csff",
        )
    }

    /**
     * 查找真实数据包：系统属性 → 环境变量 → 默认路径。
     * 只有目录存在时才返回，避免测试读到半安装状态。
     */
    fun externalPack(
        game: String,
        systemProperty: String,
        environmentVariable: String,
        fallback: String,
    ): Path? {
        val candidates = listOfNotNull(
            System.getProperty(systemProperty),
            System.getenv(environmentVariable),
            fallback,
        )
        return candidates
            .asSequence()
            .filter { it.isNotBlank() }
            .map { Paths.get(it).toAbsolutePath().normalize() }
            .firstOrNull { Files.isDirectory(it) }
    }
}
