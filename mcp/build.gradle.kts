plugins {
    // Shared Kotlin/JVM conventions (toolchain, JUnit Platform, test logging).
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    // MCP 服务端只复用数据层用例，不依赖 Compose / app。
    implementation(project(":utils"))
    implementation(libs.kotlinxSerialization)

    testImplementation(kotlin("test"))
}

tasks.test {
    // 复用 utils 的数据包/Mod 夹具，避免在 mcp 模块重复维护测试数据。
    systemProperty(
        "csmode.fixtures",
        rootProject.file("utils/src/test/resources/fixtures").absolutePath,
    )
}
