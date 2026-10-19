dependencyResolutionManagement {

    // Use Aliyun mirrors, falling back to Maven Central, for dependencies in the shared build logic (`buildSrc`) project.
    @Suppress("UnstableApiUsage")
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        mavenCentral()
    }

    // Reuse the version catalog from the main build.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "buildSrc"