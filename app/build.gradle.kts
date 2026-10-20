import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import java.util.Properties

plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    // Compose Multiplatform provides Jetpack Compose for desktop JVM applications.
    alias(libs.plugins.composeMultiplatform)
    // The Compose compiler plugin is required since Kotlin 2.0 to compile @Composable functions.
    alias(libs.plugins.composeCompiler)
}

dependencies {
    // Project "app" depends on project "utils". (Project paths are separated with ":", so ":utils" refers to the top-level "utils" project.)
    implementation(project(":utils"))

    // Compose for Desktop: runtime, UI toolkit, and native artifacts for the current OS.
    implementation(compose.desktop.currentOs)
    // Material Design 3 components.
    implementation(libs.composeMaterial3)
    // Extended Material icon set (adds ~10k icons).
    implementation(libs.composeMaterialIconsExtended)
    // Compose resources: strings, images, fonts with type-safe accessors.
    implementation(libs.composeComponentsResources)
    // IDE preview support for @Preview composables.
    implementation(libs.composeUiToolingPreview)

    // Navigation for Compose (JetBrains' multiplatform port of AndroidX Navigation).
    implementation(libs.composeNavigation)
    // ViewModel and lifecycle-aware state holders for Compose.
    implementation(libs.composeLifecycleViewmodel)
    implementation(libs.composeLifecycleRuntime)
    // Coil 3: asynchronous image loading (local files and, with the OkHttp fetcher, URLs).
    implementation(libs.coilCompose)
    implementation(libs.coilNetworkOkhttp)
    // FileKit: native file/folder/save dialogs for Compose Multiplatform (no AWT/Swing dialogs).
    implementation(libs.filekitDialogsCompose)

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "org.zender.app.AppKt"
        buildTypes.release.proguard {
            // 补充 OkHttp 可选平台实现与 JNA 反射绑定规则。
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
        nativeDistributions {
            // 只生成 Windows 单文件安装器（内置运行时，无需用户预装 JRE）。
            // 需要 WiX Toolset 3.x；Compose Desktop 通过 jpackage 生成 exe。
            targetFormats(TargetFormat.Exe)
            packageName = "CS-ModEditor-Next"
            packageVersion = "1.0.0"
            description = "Card Survival Mod Editor (Next)"
            vendor = "zender"
            copyright = "Copyright © 2026 zender"
            windows {
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true
            }
        }
    }
}

/**
 * WiX Toolset 路径：优先使用 WIX_PATH 环境变量，其次读取 local.properties 的 wix.dir。
 * gradle.properties 已关闭自动下载，避免每次构建重复下载或使用损坏的缓存。
 */
val localWixDir = rootProject.file("local.properties")
    .takeIf { it.isFile }
    ?.let { propertiesFile ->
        Properties().apply {
            propertiesFile.inputStream().use { load(it) }
        }.getProperty("wix.dir")
    }
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
val wixDir = providers.environmentVariable("WIX_PATH").orNull?.let { file(it) } ?: localWixDir
if (wixDir != null && wixDir.isDirectory) {
    tasks.withType<AbstractJPackageTask>().configureEach {
        // createDistributable(AppImage) 与 Exe/Msi 一样属于 Windows 打包任务，
        // 插件在关闭自动下载后不会为它们注入 WiX 路径，这里统一补齐。
        if (targetFormat.isCompatibleWithCurrentOS) {
            wixToolsetDir.set(wixDir)
        }
    }
}

/**
 * 便捷任务：生成 Windows .exe 安装器。
 * 产物位于 build/compose/binaries/main/exe。
 */
tasks.register("packageAppExe") {
    group = "distribution"
    description = "Build a release Windows .exe installer with bundled runtime (build/compose/binaries/main-release/exe)"
    dependsOn("packageReleaseExe")
}

// Compose Desktop 的 nativeDistributions 负责打包与分发；
// `./gradlew :app:run` 由 Compose Desktop 插件提供。
