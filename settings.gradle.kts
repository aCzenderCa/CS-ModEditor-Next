// The settings file is the entry point of every Gradle build.
// Its primary purpose is to define the subprojects.
// It is also used for some aspects of project-wide configuration, like managing plugins, dependencies, etc.
// https://docs.gradle.org/current/userguide/settings_file_basics.html

pluginManagement {
    repositories {
        // Aliyun mirrors of the Gradle Plugin Portal and Maven Central (much faster in China).
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        // Fallbacks when a mirror does not have an artifact yet.
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // Use Aliyun mirrors first and fall back to Maven Central / Google as needed.
    @Suppress("UnstableApiUsage")
    repositories {
        // Aliyun's lazy mirror sometimes returns 404 for the desktop artifact of the
        // components group, so always resolve that group from Maven Central.
        maven("https://repo1.maven.org/maven2") {
            name = "mavenCentralComposeComponents"
            content {
                includeGroup("org.jetbrains.compose.components")
            }
        }
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        mavenCentral()
    }
}

plugins {
    // Use the Foojay Toolchains plugin to automatically download JDKs required by subprojects.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Include the `app` and `utils` subprojects in the build.
// If there are changes in only one of the projects, Gradle will rebuild only the one that has changed.
// Learn more about structuring projects with Gradle - https://docs.gradle.org/8.7/userguide/multi_project_builds.html
include(":app")
include(":utils")

rootProject.name = "CS-ModEditor-Next"