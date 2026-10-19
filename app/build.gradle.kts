plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    // Compose Multiplatform provides Jetpack Compose for desktop JVM applications.
    alias(libs.plugins.composeMultiplatform)
    // The Compose compiler plugin is required since Kotlin 2.0 to compile @Composable functions.
    alias(libs.plugins.composeCompiler)

    // Apply the Application plugin to add support for building an executable JVM application.
    application
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
}

application {
    // Define the Fully Qualified Name for the application main class
    // (Note that Kotlin compiles `App.kt` to a class with FQN `com.example.app.AppKt`.)
    mainClass = "org.zender.app.AppKt"
}

distributions {
    main {
        contents {
            // Some JetBrains AndroidX ports ship a forwarding jar with the same file name
            // as the AndroidX implementation jar. Keep the first one and skip the stub.
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
}
